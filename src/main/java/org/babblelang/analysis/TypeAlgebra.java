package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core algebraic type operations ({@code docs/design/bip010_type-checker-simplification.md} §4.1):
 * cycle-guarded polarized {@link #relate}, local {@link ConstraintStore}, erased runtime overlap
 * ({@link #overlapsErased}), directional {@link #canCast}, union elimination ({@link #narrowTarget} /
 * {@link #subtractType}), and parameterized {@link #join}.
 */
final class TypeAlgebra {
    enum RelateMode {
        /** Top-level value flow: allows {@code int -> double} widening and Function/SAM subtyping. */
        ASSIGNABLE,
        /** Container type arguments and Function returns: forbids {@code int -> double}, keeps Function/SAM subtyping. */
        SUBTYPE,
        /** Runtime representation ({@code narrowTarget}/{@code subtractType}/{@code overlapsErased}): no widening or Function/SAM variance. */
        RUNTIME_EXACT,
        /** Exact type-argument matching inside invariant generics. */
        INVARIANT_ARG
    }

    enum JoinPolicy {
        /** Expression-level join ({@code if} branches, unannotated returns). */
        EXPRESSION,
        /** Variable/member reassignment join ({@code int <-> double}, {@code T <-> null}, compatible function effect intersection). */
        REASSIGNMENT
    }

    /** Projection and host-SAM callbacks supplied by {@link TypeChecker} / {@link JavaTypeBridge}. */
    interface Env {
        Type projectToBabble(Type.Instance source, TypeNode targetNode);

        Type projectToJava(Type source, Class<?> targetClass);

        boolean isSubtype(TypeNode sub, TypeNode sup);

        boolean implementsJava(TypeNode node, Class<?> iface);

        Class<?> javaClassOf(Type type);

        Type.Function samFunctionType(Type.JavaInstance java);

        boolean isFunctionalInterface(Class<?> java);

        Method functionalMethod(Class<?> java);

        boolean isCallable(Type type);

        boolean checkingEffects();

        boolean nonFunctionCallableAssignable(Type from, Type.Function target);

        boolean functionAssignableWithoutEffects(Type.Function from, Type.Function to);
    }

    private record RelationKey(Type sub, Type sup, RelateMode mode) {
    }

    /**
     * Local call-site constraint store with mark/reset rollback for speculative right-union trials
     * and lazy lower-bound joining ({@code [Par20]}, {@code [XCJO26]}).
     */
    static final class ConstraintStore {
        private final Set<Type.TypeVar> solving;
        private final boolean allowNumericWidening;
        private final Env env;
        private final Map<Type.TypeVar, Type> lowers = new LinkedHashMap<>();
        private final Map<Type.TypeVar, List<Type>> uppers = new LinkedHashMap<>();
        private final List<Runnable> undoLog = new ArrayList<>();

        ConstraintStore(Set<Type.TypeVar> solving, boolean allowNumericWidening, Env env) {
            this.solving = solving;
            this.allowNumericWidening = allowNumericWidening;
            this.env = env;
        }

        Set<Type.TypeVar> solving() {
            return solving;
        }

        int mark() {
            return undoLog.size();
        }

        void reset(int mark) {
            for (int i = undoLog.size() - 1; i >= mark; i--) {
                undoLog.remove(i).run();
            }
        }

        boolean addLower(Type.TypeVar tv, Type bound) {
            Type prev = lowers.get(tv);
            Type next;
            if (prev == null) {
                next = bound;
            } else {
                next = allowNumericWidening
                        ? join(prev, bound, JoinPolicy.EXPRESSION, env)
                        : (prev.equals(bound) ? prev : Type.union(prev, bound));
                if (next == null) {
                    return false;
                }
            }
            lowers.put(tv, next);
            undoLog.add(() -> {
                if (prev == null) {
                    lowers.remove(tv);
                } else {
                    lowers.put(tv, prev);
                }
            });
            List<Type> uList = uppers.get(tv);
            if (uList != null) {
                for (Type upper : uList) {
                    if (!relate(next, upper, RelateMode.ASSIGNABLE, null, env)) {
                        return false;
                    }
                }
            }
            return true;
        }

        boolean addUpper(Type.TypeVar tv, Type bound) {
            List<Type> list = uppers.computeIfAbsent(tv, k -> new ArrayList<>());
            list.add(bound);
            int idx = list.size() - 1;
            undoLog.add(() -> list.remove(idx));
            Type low = lowers.get(tv);
            return low == null || relate(low, bound, RelateMode.ASSIGNABLE, null, env);
        }

        Type lower(Type.TypeVar tv) {
            return lowers.get(tv);
        }

        List<Type> uppers(Type.TypeVar tv) {
            return uppers.getOrDefault(tv, List.of());
        }

        Map<Type.TypeVar, Type> snapshotLowers() {
            return new HashMap<>(lowers);
        }
    }

    private TypeAlgebra() {
    }

    static boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, Env env) {
        return relate(sub, sup, mode, store, env, new HashSet<>());
    }

    private static boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, Env env,
                                  Set<RelationKey> active) {
        if (sub == Type.ERROR || sup == Type.ERROR || sub == Type.NOTHING) {
            return true;
        }
        if (store != null) {
            if (sub instanceof Type.TypeVar tv && store.solving().contains(tv)) {
                if (sup.equals(tv)) {
                    return store.addLower(tv, tv);
                }
                if (mode == RelateMode.INVARIANT_ARG && !store.addLower(tv, sup)) {
                    return false;
                }
                return store.addUpper(tv, sup);
            }
            if (sup instanceof Type.TypeVar tv && store.solving().contains(tv)) {
                if (mode == RelateMode.INVARIANT_ARG && !store.addUpper(tv, sub)) {
                    return false;
                }
                return store.addLower(tv, sub);
            }
        }
        if (sub.equals(sup) && (store == null || !sub.anyMatch(t -> t instanceof Type.TypeVar tv && store.solving().contains(tv)))) {
            return true;
        }
        RelationKey key = new RelationKey(sub, sup, mode);
        if (!active.add(key)) {
            return true;
        }
        try {
            // Left-union universal split ([FCB08])
            if (sub instanceof Type.Union union) {
                for (Type member : union.members()) {
                    if (!relate(member, sup, mode, store, env, active)) {
                        return false;
                    }
                }
                return true;
            }
            // Unconstrained TypeVar top-guard
            if (sub instanceof Type.TypeVar tv) {
                if (tv.owner() == null && store != null) {
                    return true;
                }
                return sup.equals(Type.NULLABLE_ANY)
                        || (sup instanceof Type.Union union && anyMemberRelates(sub, union, mode, store, env, active));
            }
            if (sup instanceof Type.TypeVar tv && tv.owner() == null) {
                return true;
            }
            // Right-union existential search with ConstraintStore rollback
            if (sup instanceof Type.Union union) {
                return anyMemberRelates(sub, union, mode, store, env, active);
            }
            if (sub == Type.NULL) {
                return sup == Type.NULL;
            }
            if (sup == Type.ANY) {
                return true;
            }
            if (mode == RelateMode.ASSIGNABLE && sub == Type.INT && sup == Type.DOUBLE) {
                return true;
            }
            RelateMode argMode = mode == RelateMode.INVARIANT_ARG ? RelateMode.INVARIANT_ARG : RelateMode.SUBTYPE;
            if (sup instanceof Type.JavaInstance java) {
                Type projected = env.projectToJava(sub, java.type());
                if (projected instanceof Type.JavaInstance proj) {
                    if (proj.typeArguments().size() != java.typeArguments().size()) {
                        return java.type().isAssignableFrom(proj.type());
                    }
                    for (int i = 0; i < java.typeArguments().size(); i++) {
                        if (!relate(proj.typeArguments().get(i), java.typeArguments().get(i), argMode, store, env, active)) {
                            return false;
                        }
                    }
                    return true;
                }
                if (projected != null) {
                    return true;
                }
                if (mode != RelateMode.RUNTIME_EXACT && env.isFunctionalInterface(java.type())) {
                    if (sub instanceof Type.Function fn) {
                        return samFunctionRelates(fn, java, store, env, active);
                    }
                    if (env.isCallable(sub)) {
                        Type.Function sam = env.samFunctionType(java);
                        return sam == null || env.nonFunctionCallableAssignable(sub, sam);
                    }
                }
                return false;
            }
            if (sup instanceof Type.JavaArray target) {
                return sub instanceof Type.JavaArray array
                        && target.javaClass().isAssignableFrom(array.javaClass())
                        && relate(array.element(), target.element(), argMode, store, env, active);
            }
            if (sup instanceof Type.Instance target) {
                if (!(sub instanceof Type.Instance instance)) {
                    return false;
                }
                Type projected = env.projectToBabble(instance, target.node());
                if (!(projected instanceof Type.Instance proj)) {
                    return false;
                }
                if (proj.typeArguments().size() != target.typeArguments().size()) {
                    return true;
                }
                for (int i = 0; i < target.typeArguments().size(); i++) {
                    if (!relate(proj.typeArguments().get(i), target.typeArguments().get(i), argMode, store, env, active)) {
                        return false;
                    }
                }
                return true;
            }
            if (sup instanceof Type.Function target) {
                if (mode == RelateMode.RUNTIME_EXACT) {
                    return false;
                }
                if (sub instanceof Type.Function fn) {
                    return functionRelates(fn, target, store, env, active);
                }
                return env.nonFunctionCallableAssignable(sub, target);
            }
            return false;
        } finally {
            active.remove(key);
        }
    }

    private static boolean anyMemberRelates(Type sub, Type.Union supUnion, RelateMode mode,
                                            ConstraintStore store, Env env, Set<RelationKey> active) {
        for (Type member : supUnion.members()) {
            int mark = store != null ? store.mark() : 0;
            if (relate(sub, member, mode, store, env, active)) {
                return true;
            }
            if (store != null) {
                store.reset(mark);
            }
        }
        return false;
    }

    private static boolean samFunctionRelates(Type.Function from, Type.JavaInstance java,
                                                ConstraintStore store, Env env, Set<RelationKey> active) {
        if (!from.typeParameters().isEmpty()) {
            return false;
        }
        Method samMethod = env.functionalMethod(java.type());
        if (samMethod == null) {
            return false;
        }
        Type.Function sam = env.samFunctionType(java);
        if (sam == null) {
            return false;
        }
        if (samMethod.getReturnType() == void.class) {
            sam = new Type.Function(sam.parameters(), Type.NULLABLE_ANY, sam.effects());
        }
        return functionRelates(from, sam, store, env, active);
    }

    private static boolean functionRelates(Type.Function from, Type.Function to,
                                           ConstraintStore store, Env env, Set<RelationKey> active) {
        if (!from.typeParameters().isEmpty() || !to.typeParameters().isEmpty()) {
            return false;
        }
        if (from.parameters().size() < to.parameters().size()) {
            return false;
        }
        for (int i = 0; i < from.parameters().size(); i++) {
            Type.Parameter fp = from.parameters().get(i);
            if (i < to.parameters().size()) {
                Type.Parameter tp = to.parameters().get(i);
                if (!relate(tp.type(), fp.type(), RelateMode.ASSIGNABLE, store, env, active)) {
                    return false;
                }
            } else if (!fp.optional()) {
                return false;
            }
        }
        if (!relate(from.result(), to.result(), RelateMode.SUBTYPE, store, env, active)) {
            return false;
        }
        if (!env.checkingEffects()) {
            return true;
        }
        if (!from.effects().satisfies(to.effects())) {
            return false;
        }
        for (int i = 0; i < to.parameters().size(); i++) {
            Type.Parameter fp = from.parameters().get(i);
            Type.Parameter tp = to.parameters().get(i);
            if (fp.mut() && !tp.mut()) {
                return false;
            }
            if (tp.borrow() && !fp.borrow()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isErasedSubtype(Type from, Type to, Env env) {
        if (to == Type.ANY) {
            return true;
        }
        if (from instanceof Type.Instance f && to instanceof Type.Instance t) {
            return env.isSubtype(f.node(), t.node());
        }
        if (from instanceof Type.Instance f && to instanceof Type.JavaInstance t) {
            return env.implementsJava(f.node(), t.type());
        }
        if (to instanceof Type.JavaInstance t) {
            Class<?> fc = env.javaClassOf(from);
            return fc != null && t.type().isAssignableFrom(fc);
        }
        if (to instanceof Type.JavaArray t) {
            return from instanceof Type.JavaArray a && t.javaClass().isAssignableFrom(a.javaClass());
        }
        return false;
    }

    /** Symmetric erased runtime-value inhabitation ({@code overlapsErased}). */
    static boolean overlapsErased(Type a, Type b, Env env) {
        if (a == Type.ERROR || b == Type.ERROR || a == Type.NOTHING || b == Type.NOTHING || a.equals(b)) {
            return true;
        }
        if (a == Type.NULL || b == Type.NULL) {
            return a == b;
        }
        if (a instanceof Type.TypeVar || b instanceof Type.TypeVar) {
            return true;
        }
        if (a == Type.ANY || b == Type.ANY) {
            return true;
        }
        if (a instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> overlapsErased(m, b, env));
        }
        if (b instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> overlapsErased(a, m, env));
        }
        if (relate(a, b, RelateMode.RUNTIME_EXACT, null, env)
                || relate(b, a, RelateMode.RUNTIME_EXACT, null, env)
                || isErasedSubtype(a, b, env)
                || isErasedSubtype(b, a, env)) {
            return true;
        }
        if (a instanceof Type.Instance f && b instanceof Type.Instance t) {
            return f.node().isInterface() && t.node().isInterface();
        }
        if (a instanceof Type.Instance f && b instanceof Type.JavaInstance t) {
            return f.node().isInterface() && t.type().isInterface();
        }
        if (a instanceof Type.JavaInstance f && b instanceof Type.Instance t) {
            return f.type().isInterface() && t.node().isInterface();
        }
        Class<?> ca = env.javaClassOf(a);
        Class<?> cb = env.javaClassOf(b);
        if (ca != null && cb != null) {
            return (ca.isInterface() && cb.isInterface())
                    || (ca.isInterface() && !Modifier.isFinal(cb.getModifiers()))
                    || (cb.isInterface() && !Modifier.isFinal(ca.getModifiers()));
        }
        return false;
    }

    /** Directional runtime castability ({@code from as to}). */
    static boolean canCast(Type from, Type to, Env env) {
        if (from == Type.ERROR || to == Type.ERROR || from == Type.NOTHING) {
            return true;
        }
        if (from instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(member, to, env));
        }
        if (to instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(from, member, env));
        }
        if (from == Type.NULL || to == Type.NULL) {
            return from == to;
        }
        if (from instanceof Type.TypeVar || to instanceof Type.TypeVar) {
            return true;
        }
        if (from == Type.ANY || to == Type.ANY) {
            return true;
        }
        if (from == Type.INT && to == Type.DOUBLE) {
            return true;
        }
        if (from == Type.DOUBLE && to == Type.INT) {
            return false;
        }
        if (relate(from, to, RelateMode.ASSIGNABLE, null, env)
                || relate(to, from, RelateMode.ASSIGNABLE, null, env)) {
            return true;
        }
        return overlapsErased(from, to, env);
    }

    static Type narrowTarget(Type current, Type target, Env env) {
        List<Type> narrowed = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (member == Type.NULL) {
                continue;
            }
            if (relate(member, target, RelateMode.RUNTIME_EXACT, null, env)) {
                narrowed.add(member);
            } else if (overlapsErased(member, target, env)) {
                narrowed.add(target);
            }
        }
        return narrowed.isEmpty() ? target : Type.union(narrowed);
    }

    static Type subtractType(Type current, Type target, Env env) {
        List<Type> remaining = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (!relate(member, target, RelateMode.RUNTIME_EXACT, null, env)) {
                remaining.add(member);
            }
        }
        return remaining.isEmpty() ? Type.NOTHING : Type.union(remaining);
    }

    static Type join(Type a, Type b, JoinPolicy policy, Env env) {
        if (policy == JoinPolicy.EXPRESSION) {
            return joinExpression(a, b, env);
        }
        return joinReassignment(a, b, env);
    }

    private static Type joinExpression(Type a, Type b, Env env) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return null;
        }
        if (a.equals(b) || b == Type.NOTHING) {
            return a;
        }
        if (a == Type.NOTHING) {
            return b;
        }
        if (a instanceof Type.Function fa && b instanceof Type.Function fb) {
            Type.Function merged = intersectCompatibleFunctions(fa, fb);
            if (merged != null) {
                return merged;
            }
        }
        if (isIntOrDouble(a) && isIntOrDouble(b) && (containsType(a, Type.DOUBLE) || containsType(b, Type.DOUBLE))
                && !(containsType(a, Type.INT) && containsType(a, Type.DOUBLE))
                && !(containsType(b, Type.INT) && containsType(b, Type.DOUBLE))) {
            return (Type.containsNull(a) || Type.containsNull(b)) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE;
        }
        return Type.union(a, b);
    }

    private static Type.Function intersectCompatibleFunctions(Type.Function fa, Type.Function fb) {
        if (!fa.typeParameters().equals(fb.typeParameters())
                || fa.parameters().size() != fb.parameters().size()
                || !fa.result().equals(fb.result())) {
            return null;
        }
        List<Type.Parameter> params = new ArrayList<>(fa.parameters().size());
        boolean anyMutParam = false;
        for (int i = 0; i < fa.parameters().size(); i++) {
            Type.Parameter pa = fa.parameters().get(i);
            Type.Parameter pb = fb.parameters().get(i);
            if (!pa.name().equals(pb.name()) || !pa.type().equals(pb.type()) || pa.optional() != pb.optional()) {
                return null;
            }
            boolean mut = pa.mut() || pb.mut();
            boolean borrow = pa.borrow() && pb.borrow();
            boolean explicitBorrow = pa.explicitBorrow() && pb.explicitBorrow();
            anyMutParam |= mut;
            params.add(new Type.Parameter(pa.name(), pa.type(), pa.optional(), mut, borrow, explicitBorrow));
        }
        EffectSet common = fa.effects().intersect(fb.effects());
        if (anyMutParam && common.isPure()) {
            EnumSet<Effect> effs = EffectSet.copySet(common.effects());
            effs.remove(Effect.PURE);
            EnumSet<Effect> decl = EffectSet.copySet(common.declared());
            decl.remove(Effect.PURE);
            common = new EffectSet(effs, decl, common.mutThis(), common.borrowThis(),
                    common.explicitBorrowThis(), false, common.axiomatic());
        }
        return new Type.Function(fa.typeParameters(), params, fa.result(), common);
    }

    private static Type joinReassignment(Type current, Type value, Env env) {
        if (current == Type.ERROR || value == Type.ERROR) {
            return null;
        }
        if (value == Type.NOTHING || current.equals(value)) {
            return current;
        }
        if (current == Type.NOTHING) {
            return value;
        }
        if (relate(value, current, RelateMode.ASSIGNABLE, null, env)) {
            return current;
        }
        if (current instanceof Type.Function cf && value instanceof Type.Function vf
                && env.functionAssignableWithoutEffects(vf, cf) && cf.parameters().size() == vf.parameters().size()) {
            List<Type.Parameter> params = new ArrayList<>(cf.parameters().size());
            boolean anyMutParam = false;
            for (int i = 0; i < cf.parameters().size(); i++) {
                Type.Parameter cp = cf.parameters().get(i);
                Type.Parameter vp = vf.parameters().get(i);
                boolean mut = cp.mut() || vp.mut();
                boolean borrow = cp.borrow() && vp.borrow();
                boolean explicitBorrow = cp.explicitBorrow() && vp.explicitBorrow();
                anyMutParam |= mut;
                params.add(new Type.Parameter(cp.name(), cp.type(), cp.optional(), mut, borrow, explicitBorrow));
            }
            EffectSet common = cf.effects().intersect(vf.effects());
            if (anyMutParam && common.isPure()) {
                EnumSet<Effect> effs = EffectSet.copySet(common.effects());
                effs.remove(Effect.PURE);
                EnumSet<Effect> decl = EffectSet.copySet(common.declared());
                decl.remove(Effect.PURE);
                common = new EffectSet(effs, decl, common.mutThis(), common.borrowThis(),
                        common.explicitBorrowThis(), false, common.axiomatic());
            }
            return new Type.Function(cf.typeParameters(), params, cf.result(), common);
        }
        if (current == Type.NULL) {
            return Type.nullable(value);
        }
        if (value == Type.NULL) {
            return Type.nullable(current);
        }
        if (Type.withoutNull(current).equals(Type.withoutNull(value))) {
            return Type.union(current, value);
        }
        if (isIntOrDouble(current) && isIntOrDouble(value)
                && (containsType(current, Type.INT) || containsType(value, Type.INT))
                && (containsType(current, Type.DOUBLE) || containsType(value, Type.DOUBLE))
                && !(containsType(current, Type.INT) && containsType(current, Type.DOUBLE))
                && !(containsType(value, Type.INT) && containsType(value, Type.DOUBLE))) {
            return (Type.containsNull(current) || Type.containsNull(value)) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE;
        }
        return null;
    }

    static boolean containsType(Type type, Type target) {
        return type.equals(target) || (type instanceof Type.Union union && union.members().contains(target));
    }

    static boolean isIntOrDouble(Type type) {
        Type base = Type.withoutNull(type);
        if (base == Type.INT || base == Type.DOUBLE) {
            return true;
        }
        return base instanceof Type.Union u
                && u.members().stream().allMatch(m -> m == Type.INT || m == Type.DOUBLE);
    }
}
