package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The algebra of {@link Type}s ({@code docs/design/bip010_type-checker-simplification.md} §4.1),
 * bound to the {@link Env} of one analysis : a cycle-guarded polarized {@link #relate} with its
 * local {@link ConstraintStore}, erased runtime overlap ({@link #overlapsErased}), directional
 * {@link #canCast}, union elimination ({@link #narrowTarget} / {@link #subtractType}), the two
 * joins, projections onto Babble and Java supertypes, and type-variable substitution.
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

    /** What the algebra needs from the analysis it serves. */
    interface Env {
        /** The type parameters a class or interface declares, as type variables. */
        List<Type.TypeVar> typeParameters(TypeNode node);

        /** The {@code index}th {@code implements}/{@code extends} type of a class or interface, or {@link Type#ERROR}. */
        Type parentType(TypeNode node, int index);

        /** The function type of a Java functional interface, with its host effects. */
        Type.Function samFunctionType(Type.JavaInstance java);

        /** Whether function types are compared with their effects (the final, effect-checking walk). */
        boolean checkingEffects();

        /** Whether a callable that is not a Babble function (a Java method, a constructor, print) satisfies the effects of {@code target}. */
        boolean callableSatisfies(Type from, Type.Function target);

        /** {@link #substitute} derived {@code to} from {@code from} (to track which literal a function type comes from). */
        default void derived(Type.Function from, Type.Function to) {
        }
    }

    private record RelationKey(Type sub, Type sup, RelateMode mode) {
    }

    /**
     * Local call-site constraint store with mark/reset rollback for speculative right-union trials
     * and lazy lower-bound joining ({@code [Par20]}, {@code [XCJO26]}).
     */
    final class ConstraintStore {
        private final Set<Type.TypeVar> solving;
        private final boolean allowNumericWidening;
        private final Map<Type.TypeVar, Type> lowers = new LinkedHashMap<>();
        private final Map<Type.TypeVar, List<Type>> uppers = new LinkedHashMap<>();
        private final List<Runnable> undoLog = new ArrayList<>();

        private ConstraintStore(Set<Type.TypeVar> solving, boolean allowNumericWidening) {
            this.solving = solving;
            this.allowNumericWidening = allowNumericWidening;
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
                        ? join(prev, bound)
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
            for (Type upper : uppers.getOrDefault(tv, List.of())) {
                if (!assignable(next, upper)) {
                    return false;
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
            return low == null || assignable(low, bound);
        }

        Type lower(Type.TypeVar tv) {
            return lowers.get(tv);
        }

        /** The current solution of {@code tv} : its lower bound, else its first upper bound, else null. */
        Type solution(Type.TypeVar tv) {
            Type lower = lowers.get(tv);
            if (lower != null) {
                return lower;
            }
            List<Type> list = uppers.get(tv);
            return list == null || list.isEmpty() ? null : list.get(0);
        }

        List<Type> uppers(Type.TypeVar tv) {
            return uppers.getOrDefault(tv, List.of());
        }

        Map<Type.TypeVar, Type> snapshotLowers() {
            return new HashMap<>(lowers);
        }
    }

    private final Env env;

    TypeAlgebra(Env env) {
        this.env = env;
    }

    /** A constraint store solving {@code solving} ; {@code allowNumericWidening} joins {@code int} and {@code double} lower bounds. */
    ConstraintStore store(Set<Type.TypeVar> solving, boolean allowNumericWidening) {
        return new ConstraintStore(solving, allowNumericWidening);
    }

    // ---------------------------------------------------------------- relations

    /** Whether a value of type {@code from} can be used where {@code to} is declared. */
    boolean assignable(Type from, Type to) {
        return relate(from, to, RelateMode.ASSIGNABLE, null);
    }

    /** Assignability without the implicit {@code int -> double} widening (container arguments, results). */
    boolean subtype(Type from, Type to) {
        return relate(from, to, RelateMode.SUBTYPE, null);
    }

    /** Subtyping of runtime representations (no widening, functions only by identity). */
    boolean runtimeSubtype(Type from, Type to) {
        return relate(from, to, RelateMode.RUNTIME_EXACT, null);
    }

    /** Whether {@code from as to} always holds with no conversion (a redundant cast). */
    boolean alwaysIs(Type from, Type to) {
        return from != Type.ERROR && to != Type.ERROR && from != Type.NOTHING && from != Type.NULL
                && !needsDoubleWidening(from, to)
                && assignable(from, to);
    }

    /** Function assignability by parameters and result only, ignoring effects, {@code mut} and {@code borrow}. */
    boolean assignableIgnoringEffects(Type.Function from, Type.Function to) {
        return functionRelates(from, to, null, new HashSet<>(), false);
    }

    boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store) {
        return relate(sub, sup, mode, store, new HashSet<>());
    }

    private boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, Set<RelationKey> active) {
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
                    if (!relate(member, sup, mode, store, active)) {
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
                        || (sup instanceof Type.Union union && anyMemberRelates(sub, union, mode, store, active));
            }
            if (sup instanceof Type.TypeVar tv && tv.owner() == null) {
                return true;
            }
            // Right-union existential search with ConstraintStore rollback
            if (sup instanceof Type.Union union) {
                return anyMemberRelates(sub, union, mode, store, active);
            }
            if (sub == Type.NULL) {
                return sup == Type.NULL;
            }
            if (sup == Type.ANY) {
                return true;
            }
            if (mode == RelateMode.ASSIGNABLE && Type.isNumber(sub) && Type.numericRank(sub) < Type.numericRank(sup)) {
                // byte -> short -> int -> long -> float -> double (BIP011-D4).
                return true;
            }
            RelateMode argMode = mode == RelateMode.INVARIANT_ARG ? RelateMode.INVARIANT_ARG : RelateMode.SUBTYPE;
            if (sup instanceof Type.JavaInstance java) {
                Type projected = projectToJava(sub, java.type());
                if (projected instanceof Type.JavaInstance proj) {
                    if (proj.typeArguments().size() != java.typeArguments().size()) {
                        return java.type().isAssignableFrom(proj.type());
                    }
                    return argumentsRelate(proj.typeArguments(), java.typeArguments(), argMode, store, active);
                }
                if (projected != null) {
                    return true;
                }
                if (mode != RelateMode.RUNTIME_EXACT && JavaTypes.isFunctionalInterface(java.type())) {
                    if (sub instanceof Type.Function fn) {
                        return samFunctionRelates(fn, java, store, active);
                    }
                    if (isCallable(sub)) {
                        Type.Function sam = env.samFunctionType(java);
                        return sam == null || callableSatisfies(sub, sam);
                    }
                }
                return false;
            }
            if (sup instanceof Type.JavaArray target) {
                return sub instanceof Type.JavaArray array
                        && target.javaClass().isAssignableFrom(array.javaClass())
                        && relate(array.element(), target.element(), argMode, store, active);
            }
            if (sup instanceof Type.Instance target) {
                if (!(sub instanceof Type.Instance instance)
                        || !(projectToBabble(instance, target.node()) instanceof Type.Instance proj)) {
                    return false;
                }
                return proj.typeArguments().size() != target.typeArguments().size()
                        || argumentsRelate(proj.typeArguments(), target.typeArguments(), argMode, store, active);
            }
            if (sup instanceof Type.Function target) {
                if (mode == RelateMode.RUNTIME_EXACT) {
                    return false;
                }
                if (sub instanceof Type.Function fn) {
                    return functionRelates(fn, target, store, active, env.checkingEffects());
                }
                return callableSatisfies(sub, target);
            }
            return false;
        } finally {
            active.remove(key);
        }
    }

    private boolean argumentsRelate(List<Type> subArgs, List<Type> supArgs, RelateMode mode,
                                    ConstraintStore store, Set<RelationKey> active) {
        for (int i = 0; i < supArgs.size(); i++) {
            if (!relate(subArgs.get(i), supArgs.get(i), mode, store, active)) {
                return false;
            }
        }
        return true;
    }

    private boolean anyMemberRelates(Type sub, Type.Union supUnion, RelateMode mode,
                                     ConstraintStore store, Set<RelationKey> active) {
        for (Type member : supUnion.members()) {
            int mark = store != null ? store.mark() : 0;
            if (relate(sub, member, mode, store, active)) {
                return true;
            }
            if (store != null) {
                store.reset(mark);
            }
        }
        return false;
    }

    /** A non-function callable stands for a function type when it is callable and, while checking effects, satisfies them. */
    private boolean callableSatisfies(Type from, Type.Function target) {
        return isCallable(from) && (!env.checkingEffects() || env.callableSatisfies(from, target));
    }

    private boolean samFunctionRelates(Type.Function from, Type.JavaInstance java,
                                       ConstraintStore store, Set<RelationKey> active) {
        if (!from.typeParameters().isEmpty()) {
            return false;
        }
        Method samMethod = JavaTypes.functionalMethod(java.type());
        Type.Function sam = samMethod != null ? env.samFunctionType(java) : null;
        if (sam == null) {
            return false;
        }
        if (samMethod.getReturnType() == void.class) {
            sam = new Type.Function(sam.parameters(), Type.NULLABLE_ANY, sam.effects());
        }
        return functionRelates(from, sam, store, active, env.checkingEffects());
    }

    /**
     * A function can stand for another when it accepts the other's arguments by position,
     * at types at least as wide, needs no more of them, and returns a narrower result ; with
     * {@code withEffects}, its effects must also satisfy the other's, and it may not mutate a
     * parameter the other doesn't, nor keep one the other only borrows. Parameter names aren't
     * compared : a callback is normally called by position.
     */
    private boolean functionRelates(Type.Function from, Type.Function to, ConstraintStore store,
                                    Set<RelationKey> active, boolean withEffects) {
        if (!from.typeParameters().isEmpty() || !to.typeParameters().isEmpty()
                || from.parameters().size() < to.parameters().size()) {
            return false;
        }
        for (int i = 0; i < from.parameters().size(); i++) {
            Type.Parameter fp = from.parameters().get(i);
            if (i < to.parameters().size()
                    ? !relate(to.parameters().get(i).type(), fp.type(), RelateMode.ASSIGNABLE, store, active)
                    : !fp.optional()) {
                return false;
            }
        }
        if (!relate(from.result(), to.result(), RelateMode.SUBTYPE, store, active)) {
            return false;
        }
        if (!withEffects) {
            return true;
        }
        if (!from.effects().satisfies(to.effects())) {
            return false;
        }
        for (int i = 0; i < to.parameters().size(); i++) {
            Type.Parameter fp = from.parameters().get(i);
            Type.Parameter tp = to.parameters().get(i);
            if (fp.mut() && !tp.mut() || tp.borrow() && !fp.borrow()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- runtime tags

    private boolean isErasedSubtype(Type from, Type to) {
        if (to == Type.ANY) {
            return true;
        }
        if (from instanceof Type.Instance f && to instanceof Type.Instance t) {
            return isSubtype(f.node(), t.node());
        }
        if (from instanceof Type.Instance f && to instanceof Type.JavaInstance t) {
            return implementsJava(f.node(), t.type());
        }
        if (to instanceof Type.JavaInstance t) {
            Class<?> fc = JavaTypeBridge.javaClassOf(from);
            return fc != null && t.type().isAssignableFrom(fc);
        }
        if (to instanceof Type.JavaArray t) {
            return from instanceof Type.JavaArray a && t.javaClass().isAssignableFrom(a.javaClass());
        }
        return false;
    }

    /** Symmetric erased runtime-value inhabitation : whether some value can have both types. */
    boolean overlapsErased(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR || a == Type.NOTHING || b == Type.NOTHING || a.equals(b)) {
            return true;
        }
        if (a == Type.NULL || b == Type.NULL) {
            return a == b;
        }
        if (a instanceof Type.TypeVar || b instanceof Type.TypeVar || a == Type.ANY || b == Type.ANY) {
            return true;
        }
        if (a instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> overlapsErased(m, b));
        }
        if (b instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> overlapsErased(a, m));
        }
        if (runtimeSubtype(a, b) || runtimeSubtype(b, a) || isErasedSubtype(a, b) || isErasedSubtype(b, a)) {
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
        Class<?> ca = JavaTypeBridge.javaClassOf(a);
        Class<?> cb = JavaTypeBridge.javaClassOf(b);
        if (ca != null && cb != null) {
            return (ca.isInterface() && cb.isInterface())
                    || (ca.isInterface() && !Modifier.isFinal(cb.getModifiers()))
                    || (cb.isInterface() && !Modifier.isFinal(ca.getModifiers()));
        }
        return false;
    }

    /** Directional runtime castability : whether {@code from as to} can succeed. */
    boolean canCast(Type from, Type to) {
        if (from == Type.ERROR || to == Type.ERROR || from == Type.NOTHING) {
            return true;
        }
        if (from instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(member, to));
        }
        if (to instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(from, member));
        }
        if (from == Type.NULL || to == Type.NULL) {
            return from == to;
        }
        if (from instanceof Type.TypeVar || to instanceof Type.TypeVar || from == Type.ANY || to == Type.ANY) {
            return true;
        }
        if (Type.isNumber(from) && Type.isNumber(to)) {
            return Type.numericRank(from) <= Type.numericRank(to);
        }
        return assignable(from, to) || assignable(to, from) || overlapsErased(from, to);
    }

    /** What {@code x is target} narrows {@code current} to. */
    Type narrowTarget(Type current, Type target) {
        List<Type> narrowed = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (member == Type.NULL) {
                continue;
            }
            if (runtimeSubtype(member, target)) {
                narrowed.add(member);
            } else if (overlapsErased(member, target)) {
                narrowed.add(target);
            }
        }
        return narrowed.isEmpty() ? target : Type.union(narrowed);
    }

    /** What remains of {@code current} when {@code x is target} fails. */
    Type subtractType(Type current, Type target) {
        List<Type> remaining = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (!runtimeSubtype(member, target)) {
                remaining.add(member);
            }
        }
        return remaining.isEmpty() ? Type.NOTHING : Type.union(remaining);
    }

    // ---------------------------------------------------------------- joins

    /**
     * The type of a value that is one of two ({@code if} branches, a loop or null), widening
     * {@code int} and {@code double} to {@code double} and uniting other types ; null when an
     * error prevents joining.
     */
    Type join(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return null;
        }
        if (a.equals(b) || b == Type.NOTHING) {
            return a;
        }
        if (a == Type.NOTHING) {
            return b;
        }
        if (a instanceof Type.Function fa && b instanceof Type.Function fb
                && fa.typeParameters().equals(fb.typeParameters()) && fa.result().equals(fb.result())
                && sameParameters(fa, fb)) {
            return mergeFunctions(fa, fb);
        }
        Type widest = widestNumber(a, b);
        if (widest != null) {
            return widest;
        }
        return Type.union(a, b);
    }

    /**
     * Widening allowed across the reassignments of an unannotated def or member : only
     * numeric widening ({@code int -> long -> double}...), {@code T <-> null}, and compatible
     * functions (intersecting their effects) ; null for heterogeneous types.
     */
    Type reassignmentJoin(Type current, Type value) {
        if (current == Type.ERROR || value == Type.ERROR) {
            return null;
        }
        if (value == Type.NOTHING || current.equals(value)) {
            return current;
        }
        if (current == Type.NOTHING) {
            return value;
        }
        if (assignable(value, current)) {
            return current;
        }
        if (current instanceof Type.Function cf && value instanceof Type.Function vf
                && assignableIgnoringEffects(vf, cf) && cf.parameters().size() == vf.parameters().size()) {
            return mergeFunctions(cf, vf);
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
        return widestNumber(current, value);
    }

    private static boolean sameParameters(Type.Function fa, Type.Function fb) {
        if (fa.parameters().size() != fb.parameters().size()) {
            return false;
        }
        for (int i = 0; i < fa.parameters().size(); i++) {
            Type.Parameter pa = fa.parameters().get(i);
            Type.Parameter pb = fb.parameters().get(i);
            if (!pa.name().equals(pb.name()) || !pa.type().equals(pb.type()) || pa.optional() != pb.optional()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The function type both {@code first} and {@code second} can stand for : the first's
     * signature, a parameter {@code mut} if either mutates it and borrowed if both borrow it, and
     * the effects both guarantee (not {@code pure} once a parameter is mutated).
     */
    private static Type.Function mergeFunctions(Type.Function first, Type.Function second) {
        List<Type.Parameter> params = new ArrayList<>(first.parameters().size());
        boolean anyMutParam = false;
        for (int i = 0; i < first.parameters().size(); i++) {
            Type.Parameter a = first.parameters().get(i);
            Type.Parameter b = second.parameters().get(i);
            boolean mut = a.mut() || b.mut();
            anyMutParam |= mut;
            params.add(new Type.Parameter(a.name(), a.type(), a.optional(), mut,
                    a.borrow() && b.borrow(), a.explicitBorrow() && b.explicitBorrow()));
        }
        EffectSet common = first.effects().intersect(second.effects());
        if (anyMutParam && common.isPure()) {
            EnumSet<Effect> effs = EffectSet.copySet(common.effects());
            effs.remove(Effect.PURE);
            EnumSet<Effect> decl = EffectSet.copySet(common.declared());
            decl.remove(Effect.PURE);
            common = new EffectSet(effs, decl, common.mutThis(), common.borrowThis(),
                    common.explicitBorrowThis(), false, common.axiomatic());
        }
        return new Type.Function(first.typeParameters(), params, first.result(), common);
    }

    // ---------------------------------------------------------------- generics and projections

    /** The type arguments of an instance by the type variables of its class. */
    Map<Type.TypeVar, Type> instanceEnv(Type.Instance instance) {
        List<Type.TypeVar> params = env.typeParameters(instance.node());
        List<Type> args = instance.typeArguments();
        if (params.isEmpty() || args.isEmpty()) {
            return Map.of();
        }
        Map<Type.TypeVar, Type> bindings = new HashMap<>(params.size());
        for (int i = 0; i < Math.min(params.size(), args.size()); i++) {
            bindings.put(params.get(i), args.get(i));
        }
        return bindings;
    }

    /** {@code type} with its free type variables replaced by their {@code bindings}. */
    Type substitute(Type type, Map<Type.TypeVar, Type> bindings) {
        if (type == null || bindings.isEmpty()) {
            return type;
        }
        return type.mapTypes(t -> {
            if (t instanceof Type.TypeVar tv) {
                return bindings.getOrDefault(tv, tv);
            }
            if (t instanceof Type.Function fn) {
                Map<Type.TypeVar, Type> active = bindings;
                if (!fn.typeParameters().isEmpty()) {
                    active = new HashMap<>(bindings);
                    fn.typeParameters().forEach(active::remove);
                    if (active.isEmpty()) {
                        return fn;
                    }
                }
                List<Type.Parameter> params = new ArrayList<>(fn.parameters().size());
                for (Type.Parameter p : fn.parameters()) {
                    params.add(p.withType(substitute(p.type(), active)));
                }
                Type.Function substituted = new Type.Function(fn.typeParameters(), params, substitute(fn.result(), active), fn.effects());
                env.derived(fn, substituted);
                return substituted;
            }
            return null;
        });
    }

    /** {@code source} seen as an instance of {@code targetNode}, one of its ancestors, or null. */
    Type projectToBabble(Type.Instance source, TypeNode targetNode) {
        if (source.node() == targetNode) {
            return source;
        }
        Map<Type.TypeVar, Type> bindings = instanceEnv(source);
        for (int i = 0; i < source.node().parents.size(); i++) {
            if (substitute(env.parentType(source.node(), i), bindings) instanceof Type.Instance parent) {
                Type projected = projectToBabble(parent, targetNode);
                if (projected != null) {
                    return projected;
                }
            }
        }
        return null;
    }

    /** {@code source} seen as an instance of the Java type {@code targetClass}, or null. */
    Type projectToJava(Type source, Class<?> targetClass) {
        if (targetClass == Object.class) {
            return Type.ANY;
        }
        if (source instanceof Type.JavaInstance ji) {
            if (ji.type() == targetClass) {
                return ji;
            }
            if (!targetClass.isAssignableFrom(ji.type())) {
                return null;
            }
            TypeVariable<?>[] targetParams = targetClass.getTypeParameters();
            if (targetParams.length == 0) {
                return JavaTypeBridge.fromJava(targetClass);
            }
            Map<TypeVariable<?>, Type> javaEnv = JavaTypeBridge.javaTypeEnv(ji.type(), ji.typeArguments());
            List<Type> args = new ArrayList<>(targetParams.length);
            for (TypeVariable<?> tp : targetParams) {
                args.add(javaEnv.getOrDefault(tp, Type.NULLABLE_ANY));
            }
            return new Type.JavaInstance(targetClass, args);
        }
        if (source instanceof Type.Instance inst) {
            Map<Type.TypeVar, Type> bindings = instanceEnv(inst);
            for (int i = 0; i < inst.node().parents.size(); i++) {
                Type projected = projectToJava(substitute(env.parentType(inst.node(), i), bindings), targetClass);
                if (projected != null) {
                    return projected;
                }
            }
            return null;
        }
        if (source instanceof Type.Simple simple && simple.javaClass() != null) {
            return projectToJava(new Type.JavaInstance(simple.javaClass()), targetClass);
        }
        if (source instanceof Type.JavaArray array) {
            return targetClass.isAssignableFrom(array.javaClass()) ? JavaTypeBridge.fromJava(targetClass) : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- structural predicates

    /** Whether a value of this type can be called : functions, Java methods, classes, and print. */
    static boolean isCallable(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeAlgebra::isCallable);
        }
        return type instanceof Type.Function || type instanceof Type.JavaMethod
                || type instanceof Type.ClassValue value && !value.node().isInterface()
                || type == Type.PRINT;
    }

    /** Whether a value of type {@code from} is converted to a wider number where {@code to} is expected. */
    static boolean needsDoubleWidening(Type from, Type to) {
        return wideningMask(from, to) != 0;
    }

    /**
     * The numbers a value of type {@code from} converts to where {@code to} is expected, as a
     * mask of numeric ranks ({@code 1 << Type.numericRank}, what {@code Values.widen} takes) : the
     * numbers of {@code to}, when one of {@code from}'s isn't among them but a wider one is ; else 0.
     */
    static int wideningMask(Type from, Type to) {
        if (from == Type.ERROR || to == Type.ERROR) {
            return 0;
        }
        int targets = 0;
        for (Type member : Type.membersOf(to)) {
            int rank = Type.numericRank(member);
            if (rank >= 0) {
                targets |= 1 << rank;
            }
        }
        if (targets == 0) {
            return 0;
        }
        for (Type member : Type.membersOf(from)) {
            int rank = Type.numericRank(member);
            if (rank >= 0 && (targets & (1 << rank)) == 0 && (targets >> (rank + 1)) != 0) {
                return targets;
            }
        }
        return 0;
    }

    /** {@code type} with its numbers widened as they are where {@code target} is expected ({@link #wideningMask}). */
    static Type widenTo(Type type, Type target) {
        int mask = wideningMask(type, target);
        if (mask == 0) {
            return type;
        }
        List<Type> members = new ArrayList<>();
        for (Type member : Type.membersOf(type)) {
            int rank = Type.numericRank(member);
            if (rank >= 0 && (mask & (1 << rank)) == 0 && (mask >> (rank + 1)) != 0) {
                int wider = rank + 1;
                while ((mask & (1 << wider)) == 0) {
                    wider++;
                }
                members.add(Type.NUMBERS.get(wider));
            } else {
                members.add(member);
            }
        }
        return Type.union(members);
    }

    /**
     * The join of two different numbers, each possibly nullable : the wider one ({@code int} and
     * {@code long?} give {@code long?}) ; null when they aren't both single numbers.
     */
    static Type widestNumber(Type a, Type b) {
        Type na = Type.withoutNull(a);
        Type nb = Type.withoutNull(b);
        if (!Type.isNumber(na) || !Type.isNumber(nb) || na == nb) {
            return null;
        }
        Type widest = Type.numericRank(na) > Type.numericRank(nb) ? na : nb;
        return Type.containsNull(a) || Type.containsNull(b) ? Type.nullable(widest) : widest;
    }

    static boolean containsType(Type type, Type target) {
        return type.equals(target) || (type instanceof Type.Union union && union.members().contains(target));
    }

    /** Whether a value of this type may be null (a type variable might be instantiated nullable). */
    static boolean canBeNull(Type type) {
        if (type == Type.NULL || type instanceof Type.TypeVar) {
            return true;
        }
        return type instanceof Type.Union union && union.members().stream().anyMatch(TypeAlgebra::canBeNull);
    }

    static boolean containsFunction(Type type) {
        if (type instanceof Type.Function) {
            return true;
        }
        return type instanceof Type.Union union && union.members().stream().anyMatch(TypeAlgebra::containsFunction);
    }

    /** Whether a class or interface is {@code target} or has it among its ancestors. */
    static boolean isSubtype(TypeNode node, TypeNode target) {
        if (node == target) {
            return true;
        }
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode && isSubtype(parentNode, target)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a class or interface implements the Java type {@code java}, directly or through its ancestors. */
    static boolean implementsJava(TypeNode node, Class<?> java) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode ? implementsJava(parentNode, java) : java.isAssignableFrom((Class<?>) parent)) {
                return true;
            }
        }
        return java == Object.class;
    }
}
