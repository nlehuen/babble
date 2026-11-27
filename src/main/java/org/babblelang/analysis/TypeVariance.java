package org.babblelang.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.runtime.ClassShape;

/**
 * Soundness-preserving per-parameter covariance analysis for Babble generic types ({@code record},
 * {@code class}, {@code interface}) and Java generic classes/interfaces ({@code BIP011-D8}, {@code
 * BIP032}).
 *
 * <p>A type parameter {@code T_i} of a generic type {@code G} is covariant in a given access mode
 * ({@code readOnly = false} for owned/shared references, {@code readOnly = true} for {@code
 * borrow}-covariant non-{@code mut} parameters and {@code frozen} values) only if every callable
 * operation on {@code G} in that mode uses {@code T_i} strictly in covariant (positive) positions:
 *
 * <ul>
 *   <li>Under {@code readOnly = false}: mutable ({@code var}) fields, non-{@code record} classes
 *       and interfaces, mutable containers ({@code list}/{@code map}/{@code array}), and
 *       contravariant function/method parameters all inhibit covariance.
 *   <li>Under {@code readOnly = true}: {@code var} field writes and {@code mut} methods are
 *       statically forbidden by {@link EffectChecker}, so mutable containers become read-only
 *       covariant, while non-{@code mut} methods and function-typed fields with contravariant
 *       parameters remain invariant (closing {@code T1} contravariant-method escapes).
 * </ul>
 */
final class TypeVariance {
    interface Lookup {
        List<Type.TypeVar> typeParameters(TypeNode node);

        Type parentType(TypeNode node, int index);

        Type memberType(TypeNode owner, TypeNode.Member member);

        boolean isJavaMethodMut(Class<?> owner, Method method);
    }

    private record Key(Object owner, int paramIndex, boolean readOnly) {}

    private static final Set<Class<?>> OWNED_COVARIANT_JAVA =
            Set.of(
                    java.util.Optional.class,
                    java.util.Map.Entry.class,
                    org.babblelang.runtime.Range.class);

    /**
     * Core JDK collection protocols and standard concrete collections whose read-only ({@code
     * borrow} / {@code frozen}) projection is covariant by design ({@code BIP011-D8}), where
     * mutation requires {@code mut}.
     */
    private static final Set<Class<?>> READ_ONLY_COVARIANT_JAVA_COLLECTIONS =
            Set.of(
                    Iterable.class,
                    java.util.Iterator.class,
                    java.util.ListIterator.class,
                    java.util.Collection.class,
                    java.util.List.class,
                    java.util.Set.class,
                    java.util.Queue.class,
                    java.util.Deque.class,
                    java.util.SequencedCollection.class,
                    java.util.SequencedSet.class,
                    java.util.Map.class,
                    java.util.SequencedMap.class,
                    java.util.ArrayList.class,
                    java.util.LinkedList.class,
                    java.util.HashSet.class,
                    java.util.LinkedHashSet.class,
                    java.util.HashMap.class,
                    java.util.LinkedHashMap.class,
                    java.util.ArrayDeque.class,
                    java.util.AbstractCollection.class,
                    java.util.AbstractList.class,
                    java.util.AbstractSet.class,
                    java.util.AbstractMap.class);

    private final Lookup lookup;
    private final Map<Key, Boolean> cache = new HashMap<>();

    TypeVariance(Lookup lookup) {
        this.lookup = lookup;
    }

    boolean isBabbleParamCovariant(TypeNode node, int paramIndex, boolean readOnly) {
        return isBabbleParamCovariant(node, paramIndex, readOnly, new HashSet<>(), new boolean[1]);
    }

    boolean isJavaParamCovariant(Class<?> raw, int paramIndex, boolean readOnly) {
        return isJavaParamCovariant(raw, paramIndex, readOnly, new HashSet<>(), new boolean[1]);
    }

    private boolean isBabbleParamCovariant(
            TypeNode node, int paramIndex, boolean readOnly, Set<Key> active, boolean[] hitCycle) {
        if (node == null || paramIndex < 0) {
            return false;
        }
        if (!readOnly && node.kind != ClassShape.Kind.VALUE_CLASS) {
            return false;
        }
        Key key = new Key(node, paramIndex, readOnly);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!active.add(key)) {
            hitCycle[0] = true;
            return true;
        }
        boolean result = true;
        try {
            List<Type.TypeVar> typeParams = lookup.typeParameters(node);
            if (paramIndex >= typeParams.size()) {
                result = false;
            } else {
                Type.TypeVar tv = typeParams.get(paramIndex);
                for (TypeNode.Member m : node.members.values()) {
                    if (!memberAllowsCovariance(node, m, tv, readOnly, active, hitCycle)) {
                        result = false;
                        break;
                    }
                }
                if (result) {
                    for (int i = 0; i < node.parents.size(); i++) {
                        Type pt = lookup.parentType(node, i);
                        if (!isBabbleTypeCovariantIn(pt, tv, 1, readOnly, active, hitCycle)) {
                            result = false;
                            break;
                        }
                    }
                }
            }
        } finally {
            active.remove(key);
        }
        if (active.isEmpty() || !hitCycle[0]) {
            cache.put(key, result);
        }
        return result;
    }

    private boolean memberAllowsCovariance(
            TypeNode node,
            TypeNode.Member member,
            Type.TypeVar tv,
            boolean readOnly,
            Set<Key> active,
            boolean[] hitCycle) {
        Type mt = lookup.memberType(node, member);
        if (mt == null) {
            hitCycle[0] = true;
            return false;
        }
        if (mt == Type.ERROR || !mentionsTypeVar(mt, tv)) {
            return true;
        }
        if (member.isMethod()) {
            return methodTypeAllowsCovariance(mt, tv, readOnly, active, hitCycle);
        }
        if (member.isMutable() && !readOnly) {
            return false;
        }
        return isBabbleTypeCovariantIn(mt, tv, 1, readOnly, active, hitCycle);
    }

    private boolean methodTypeAllowsCovariance(
            Type mt, Type.TypeVar tv, boolean readOnly, Set<Key> active, boolean[] hitCycle) {
        if (mt instanceof Type.Function fn) {
            // On a read-only (`borrow` non-`mut` or `frozen`) receiver, `mut` methods cannot be
            // called, so their parameters do not expose contravariant writes.
            if (readOnly && fn.effects().mutThis()) {
                return true;
            }
            for (Type.Parameter p : fn.parameters()) {
                if (!isBabbleTypeCovariantIn(p.type(), tv, -1, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return isBabbleTypeCovariantIn(fn.result(), tv, 1, readOnly, active, hitCycle);
        }
        if (mt instanceof Type.Overloaded ov) {
            for (Type.Function alt : ov.alternatives()) {
                if (!methodTypeAllowsCovariance(alt, tv, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        return isBabbleTypeCovariantIn(mt, tv, 1, readOnly, active, hitCycle);
    }

    private boolean isBabbleTypeCovariantIn(
            Type t,
            Type.TypeVar tv,
            int polarity,
            boolean readOnly,
            Set<Key> active,
            boolean[] hitCycle) {
        if (t == null || t == Type.ERROR || !mentionsTypeVar(t, tv)) {
            return true;
        }
        if (t.equals(tv)) {
            return polarity > 0;
        }
        if (t instanceof Type.Frozen f) {
            return polarity != 0
                    && isBabbleTypeCovariantIn(f.of(), tv, polarity, true, active, hitCycle);
        }
        if (t instanceof Type.Union u) {
            for (Type m : u.members()) {
                if (!isBabbleTypeCovariantIn(m, tv, polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        if (t instanceof Type.Intersection inter) {
            for (Type m : inter.members()) {
                if (!isBabbleTypeCovariantIn(m, tv, polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        if (t instanceof Type.Function fn) {
            if (polarity == 0) {
                return false;
            }
            for (Type.Parameter p : fn.parameters()) {
                if (!isBabbleTypeCovariantIn(p.type(), tv, -polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return isBabbleTypeCovariantIn(fn.result(), tv, polarity, readOnly, active, hitCycle);
        }
        if (t instanceof Type.Overloaded ov) {
            for (Type.Function alt : ov.alternatives()) {
                if (!isBabbleTypeCovariantIn(alt, tv, polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        if (t instanceof Type.Array arr) {
            return readOnly
                    && polarity != 0
                    && isBabbleTypeCovariantIn(arr.element(), tv, polarity, true, active, hitCycle);
        }
        if (t instanceof Type.Instance inst) {
            List<Type> args = inst.typeArguments();
            for (int i = 0; i < args.size(); i++) {
                Type arg = args.get(i);
                if (!mentionsTypeVar(arg, tv)) {
                    continue;
                }
                if (polarity == 0
                        || !isBabbleParamCovariant(inst.node(), i, readOnly, active, hitCycle)) {
                    return false;
                }
                if (!isBabbleTypeCovariantIn(arg, tv, polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        if (t instanceof Type.JavaInstance ji) {
            List<Type> args = ji.typeArguments();
            for (int i = 0; i < args.size(); i++) {
                Type arg = args.get(i);
                if (!mentionsTypeVar(arg, tv)) {
                    continue;
                }
                if (polarity == 0
                        || !isJavaParamCovariant(ji.type(), i, readOnly, active, hitCycle)) {
                    return false;
                }
                if (!isBabbleTypeCovariantIn(arg, tv, polarity, readOnly, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isJavaParamCovariant(
            Class<?> raw, int paramIndex, boolean readOnly, Set<Key> active, boolean[] hitCycle) {
        if (raw == null || paramIndex < 0) {
            return false;
        }
        if (OWNED_COVARIANT_JAVA.contains(raw)) {
            return true;
        }
        if (!readOnly) {
            return false;
        }
        if (READ_ONLY_COVARIANT_JAVA_COLLECTIONS.contains(raw)) {
            return true;
        }
        Key key = new Key(raw, paramIndex, true);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!active.add(key)) {
            hitCycle[0] = true;
            return true;
        }
        boolean result = true;
        try {
            TypeVariable<?>[] typeParams = raw.getTypeParameters();
            if (paramIndex >= typeParams.length) {
                result = false;
            } else {
                TypeVariable<?> tv = typeParams[paramIndex];
                for (Field f : raw.getFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!isJavaTypeCovariantIn(f.getGenericType(), tv, 1, active, hitCycle)) {
                        result = false;
                        break;
                    }
                }
                if (result) {
                    for (Method m : raw.getMethods()) {
                        if (Modifier.isStatic(m.getModifiers())
                                || m.isSynthetic()
                                || m.isBridge()
                                || m.getDeclaringClass() == Object.class) {
                            continue;
                        }
                        if (lookup.isJavaMethodMut(raw, m)) {
                            continue;
                        }
                        for (java.lang.reflect.Type gp : m.getGenericParameterTypes()) {
                            if (!isJavaTypeCovariantIn(gp, tv, -1, active, hitCycle)) {
                                result = false;
                                break;
                            }
                        }
                        if (!result) {
                            break;
                        }
                        if (!isJavaTypeCovariantIn(
                                m.getGenericReturnType(), tv, 1, active, hitCycle)) {
                            result = false;
                            break;
                        }
                    }
                }
            }
        } finally {
            active.remove(key);
        }
        if (active.isEmpty() || !hitCycle[0]) {
            cache.put(key, result);
        }
        return result;
    }

    private boolean isJavaTypeCovariantIn(
            java.lang.reflect.Type gt,
            TypeVariable<?> tv,
            int polarity,
            Set<Key> active,
            boolean[] hitCycle) {
        if (gt == null || gt instanceof Class<?>) {
            return true;
        }
        if (gt instanceof TypeVariable<?> v) {
            return !v.equals(tv) || polarity > 0;
        }
        if (gt instanceof GenericArrayType arr) {
            return isJavaTypeCovariantIn(
                    arr.getGenericComponentType(), tv, polarity, active, hitCycle);
        }
        if (gt instanceof WildcardType wt) {
            for (java.lang.reflect.Type upper : wt.getUpperBounds()) {
                if (!isJavaTypeCovariantIn(upper, tv, polarity, active, hitCycle)) {
                    return false;
                }
            }
            for (java.lang.reflect.Type lower : wt.getLowerBounds()) {
                if (!isJavaTypeCovariantIn(lower, tv, -polarity, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        if (gt instanceof ParameterizedType pt) {
            Class<?> raw = pt.getRawType() instanceof Class<?> c ? c : null;
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                java.lang.reflect.Type arg = args[i];
                if (!javaTypeMentions(arg, tv)) {
                    continue;
                }
                if (arg instanceof WildcardType wt) {
                    if (!isJavaTypeCovariantIn(wt, tv, polarity, active, hitCycle)) {
                        return false;
                    }
                    continue;
                }
                if (raw == null || !isJavaParamCovariant(raw, i, true, active, hitCycle)) {
                    return false;
                }
                if (!isJavaTypeCovariantIn(arg, tv, polarity, active, hitCycle)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean mentionsTypeVar(Type t, Type.TypeVar tv) {
        return t.anyMatch(x -> x.equals(tv));
    }

    private static boolean javaTypeMentions(java.lang.reflect.Type gt, TypeVariable<?> tv) {
        if (gt == null || gt instanceof Class<?>) {
            return false;
        }
        if (gt instanceof TypeVariable<?> v) {
            return v.equals(tv);
        }
        if (gt instanceof GenericArrayType arr) {
            return javaTypeMentions(arr.getGenericComponentType(), tv);
        }
        if (gt instanceof WildcardType wt) {
            for (java.lang.reflect.Type u : wt.getUpperBounds()) {
                if (javaTypeMentions(u, tv)) {
                    return true;
                }
            }
            for (java.lang.reflect.Type l : wt.getLowerBounds()) {
                if (javaTypeMentions(l, tv)) {
                    return true;
                }
            }
            return false;
        }
        if (gt instanceof ParameterizedType pt) {
            for (java.lang.reflect.Type a : pt.getActualTypeArguments()) {
                if (javaTypeMentions(a, tv)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
