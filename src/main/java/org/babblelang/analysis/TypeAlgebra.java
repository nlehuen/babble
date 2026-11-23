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

        /** Whether a value of {@code type} is deeply immutable (scalars, null, frozen, deep record, deep entry). */
        boolean isDeeplyImmutable(Type type);

        /** {@link #substitute} derived {@code to} from {@code from} (to track which literal a function type comes from). */
        default void derived(Type.Function from, Type.Function to) {
        }
    }

    private record RelationKey(Type sub, Type sup, RelateMode mode, boolean borrowCovariant, boolean waiveArrayNullness) {
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

    boolean isDeeplyImmutable(Type type) {
        return env.isDeeplyImmutable(type);
    }

    /** A constraint store solving {@code solving} ; {@code allowNumericWidening} joins {@code int} and {@code double} lower bounds. */
    ConstraintStore store(Set<Type.TypeVar> solving, boolean allowNumericWidening) {
        return new ConstraintStore(solving, allowNumericWidening);
    }

    // ---------------------------------------------------------------- relations

    /** Whether a value of type {@code from} can be used where {@code to} is declared. */
    boolean assignable(Type from, Type to) {
        return relate(from, to, RelateMode.ASSIGNABLE, null, new HashSet<>(), false, false);
    }

    /** Whether a value of type {@code from} can be passed to a non-{@code mut} {@code borrow} parameter of type {@code to}. */
    boolean borrowAssignable(Type from, Type to) {
        return relate(from, to, RelateMode.ASSIGNABLE, null, new HashSet<>(), true, false);
    }

    /** Assignability without the implicit {@code int -> double} widening (container arguments, results). */
    boolean subtype(Type from, Type to) {
        return relate(from, to, RelateMode.SUBTYPE, null, new HashSet<>(), false, false);
    }

    /** Subtyping of runtime representations (no widening, functions only by identity). */
    boolean runtimeSubtype(Type from, Type to) {
        return relate(from, to, RelateMode.RUNTIME_EXACT, null, new HashSet<>(), false, false);
    }

    /** Whether {@code from as to} always holds with no conversion (a redundant cast). */
    boolean alwaysIs(Type from, Type to) {
        return from != Type.ERROR && to != Type.ERROR && from != Type.NOTHING && from != Type.NULL
                && !needsNumericWidening(from, to)
                && !needsSamConversion(from, to)
                && assignable(from, to);
    }

    static boolean mayContainBabbleCallable(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return false;
        }
        if (isCallable(type) || type == Type.ANY) {
            return true;
        }
        if (type instanceof Type.Union union) {
            return union.members().stream().anyMatch(TypeAlgebra::mayContainBabbleCallable);
        }
        if (type instanceof Type.Frozen frozen) {
            return mayContainBabbleCallable(frozen.of());
        }
        return false;
    }

    private static boolean needsSamConversion(Type from, Type to) {
        if (!mayContainBabbleCallable(from)) {
            return false;
        }
        if (JavaTypeBridge.resolveJavaSam(to) instanceof JavaTypeBridge.JavaSamResolution.Valid) {
            return true;
        }
        if (Type.withoutNull(to) instanceof Type.Union union) {
            return union.members().stream()
                    .anyMatch(m -> JavaTypeBridge.resolveJavaSam(m) instanceof JavaTypeBridge.JavaSamResolution.Valid);
        }
        return false;
    }

    /** Function assignability by parameters and result only, ignoring effects, {@code mut} and {@code borrow}. */
    boolean assignableIgnoringEffects(Type.Function from, Type.Function to) {
        return functionRelates(from, to, null, new HashSet<>(), false);
    }

    boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store) {
        return relate(sub, sup, mode, store, new HashSet<>(), false, false);
    }

    boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, boolean borrowCovariant) {
        return relate(sub, sup, mode, store, new HashSet<>(), borrowCovariant, false);
    }

    boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, boolean borrowCovariant, boolean waiveArrayNullness) {
        return relate(sub, sup, mode, store, new HashSet<>(), borrowCovariant, waiveArrayNullness);
    }

    private boolean relate(Type sub, Type sup, RelateMode mode, ConstraintStore store, Set<RelationKey> active,
                           boolean borrowCovariant, boolean waiveArrayNullness) {
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
        if (mode == RelateMode.INVARIANT_ARG) {
            return relate(sub, sup, RelateMode.SUBTYPE, store, active, false, false)
                    && relate(sup, sub, RelateMode.SUBTYPE, store, active, false, false);
        }
        RelationKey key = new RelationKey(sub, sup, mode, borrowCovariant, waiveArrayNullness);
        if (!active.add(key)) {
            return true;
        }
        try {
            // An intersection with a union member is a union of intersections, first (BIP022-D2)
            if (sub instanceof Type.Intersection intersection) {
                Type distributed = distribute(intersection);
                if (distributed != intersection) {
                    return relate(distributed, sup, mode, store, active, borrowCovariant, waiveArrayNullness);
                }
            }
            // Left-union universal split ([FCB08])
            if (sub instanceof Type.Union union) {
                for (Type member : union.members()) {
                    if (!relate(member, sup, mode, store, active, borrowCovariant, waiveArrayNullness)) {
                        return false;
                    }
                }
                return true;
            }
            // Right intersection : a value of every member (BIP022-D3), or a Babble callable -> SAM + markers (BIP022-D7)
            if (sup instanceof Type.Intersection intersection) {
                Type callableSub = sub instanceof Type.Frozen subf && borrowCovariant ? subf.of() : sub;
                if (isCallable(callableSub)) {
                    if (mode == RelateMode.RUNTIME_EXACT) {
                        return false;
                    }
                    if (JavaTypeBridge.resolveJavaSam(intersection) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
                        return relate(callableSub, valid.samType(), mode, store, active, borrowCovariant, waiveArrayNullness);
                    }
                    return false;
                }
                for (Type member : intersection.members()) {
                    if (!relate(sub, member, mode, store, active, borrowCovariant, waiveArrayNullness)) {
                        return false;
                    }
                }
                return true;
            }
            // TypeVar upper-bound & top-guard (BIP021-D19)
            if (sub instanceof Type.TypeVar tv) {
                if (tv.owner() == null && store != null) {
                    return true;
                }
                if (tv.bound() != null && tv.bound() != Type.ERROR
                        && relate(tv.bound(), sup, mode, store, active, borrowCovariant, waiveArrayNullness)) {
                    return true;
                }
                return sup.equals(Type.NULLABLE_ANY)
                        || (sup instanceof Type.Union union && anyMemberRelates(sub, union, mode, store, active, borrowCovariant, waiveArrayNullness));
            }
            if (sup instanceof Type.TypeVar tv && tv.owner() == null) {
                return true;
            }
            // Right-union existential search with ConstraintStore rollback
            if (sup instanceof Type.Union union) {
                return anyMemberRelates(sub, union, mode, store, active, borrowCovariant, waiveArrayNullness);
            }
            // Left intersection : one member suffices (BIP022-D3) ; union members were distributed above
            if (sub instanceof Type.Intersection intersection) {
                for (Type member : intersection.members()) {
                    int mark = store != null ? store.mark() : 0;
                    if (relate(member, sup, mode, store, active, borrowCovariant, waiveArrayNullness)) {
                        return true;
                    }
                    if (store != null) {
                        store.reset(mark);
                    }
                }
                return false;
            }
            if (sub == Type.NULL) {
                return sup == Type.NULL;
            }
            if (sup instanceof Type.Frozen sf) {
                if (sub instanceof Type.Frozen subf) {
                    return relate(subf.of(), sf.of(), mode, store, active, true, false);
                }
                if (sub != Type.ANY && env.isDeeplyImmutable(sub)) {
                    return relate(sub, sf.of(), mode, store, active, true, false);
                }
                return false;
            }
            if (sub instanceof Type.Frozen subf) {
                return mode != RelateMode.RUNTIME_EXACT && borrowCovariant
                        && relate(subf.of(), sup, mode, store, active, true, waiveArrayNullness);
            }
            if (sup == Type.ANY) {
                return true;
            }
            if (mode == RelateMode.ASSIGNABLE && Type.isNumericScalar(sub) && Type.isNumericScalar(sup)
                    && Type.numericRank(sub) < Type.numericRank(sup)) {
                return true;
            }
            RelateMode argMode = (mode != RelateMode.RUNTIME_EXACT && !borrowCovariant && isMutableParameterized(sup))
                    ? RelateMode.INVARIANT_ARG
                    : RelateMode.SUBTYPE;
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
                    if (sub instanceof Type.Overloaded ov) {
                        for (Type.Function alt : ov.alternatives()) {
                            if (samFunctionRelates(alt, java, store, active)) {
                                return true;
                            }
                        }
                        return false;
                    }
                    if (isCallable(sub)) {
                        Type.Function sam = env.samFunctionType(java);
                        return sam == null || callableSatisfies(sub, sam);
                    }
                }
                return false;
            }
            if (sup instanceof Type.Array target) {
                if (!(sub instanceof Type.Array array)) {
                    return false;
                }
                Type se = array.element();
                Type te = target.element();
                boolean solvingTarget = store != null && te.anyMatch(t -> t instanceof Type.TypeVar tv && store.solving().contains(tv));
                boolean solvingSource = store != null && se.anyMatch(t -> t instanceof Type.TypeVar tv && store.solving().contains(tv));
                if (Type.isPrimitiveScalar(se) || Type.isPrimitiveScalar(te)) {
                    return !(solvingTarget || solvingSource) && se.equals(te);
                }
                if (!arrayClassDependsOnSolvingVar(se, store)
                        && !arrayClassDependsOnSolvingVar(te, store)
                        && !jvmArrayAssignable(se, te)) {
                    return false;
                }
                RelateMode elemMode = mode == RelateMode.RUNTIME_EXACT ? RelateMode.RUNTIME_EXACT : RelateMode.SUBTYPE;
                if (!relate(se, te, elemMode, store, active, false, false)) {
                    return false;
                }
                if (mode == RelateMode.RUNTIME_EXACT || borrowCovariant) {
                    return true;
                }
                if (!waiveArrayNullness && !solvingSource && Type.containsNull(te) && !Type.containsNull(se)) {
                    return false;
                }
                if (solvingTarget || solvingSource) {
                    return true;
                }
                if (!waiveArrayNullness && Type.containsNull(se) != Type.containsNull(te)) {
                    return false;
                }
                if (isErasedToObjectArray(unwrapFrozen(Type.withoutNull(se)))) {
                    Type srcCheck = waiveArrayNullness ? Type.withoutNull(se) : se;
                    Type tgtCheck = waiveArrayNullness ? Type.withoutNull(te) : te;
                    return srcCheck.equals(tgtCheck) || relate(tgtCheck, srcCheck, RelateMode.SUBTYPE, null, active, false, false);
                }
                return true;
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
                if (sub instanceof Type.Overloaded ov) {
                    for (Type.Function alt : ov.alternatives()) {
                        if (functionRelates(alt, target, store, active, env.checkingEffects())) {
                            return true;
                        }
                    }
                    return false;
                }
                return callableSatisfies(sub, target);
            }
            if (sup instanceof Type.Overloaded target) {
                if (mode == RelateMode.RUNTIME_EXACT) {
                    return false;
                }
                if (sub instanceof Type.Overloaded ov) {
                    for (Type.Function req : target.alternatives()) {
                        boolean matched = false;
                        for (Type.Function alt : ov.alternatives()) {
                            if (functionRelates(alt, req, store, active, env.checkingEffects())) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            return false;
                        }
                    }
                    return true;
                }
                if (sub instanceof Type.Function fn) {
                    for (Type.Function req : target.alternatives()) {
                        if (!functionRelates(fn, req, store, active, env.checkingEffects())) {
                            return false;
                        }
                    }
                    return true;
                }
                return false;
            }
            return false;
        } finally {
            active.remove(key);
        }
    }

    /** {@code (A | B) & C} as {@code (A & C) | (B & C)} ; an intersection without union members as is. */
    static Type distribute(Type.Intersection intersection) {
        List<List<Type>> products = new ArrayList<>();
        products.add(new ArrayList<>());
        boolean any = false;
        for (Type member : intersection.members()) {
            List<Type> choices = Type.membersOf(member);
            any |= choices.size() > 1;
            List<List<Type>> next = new ArrayList<>();
            for (List<Type> product : products) {
                for (Type choice : choices) {
                    List<Type> extended = new ArrayList<>(product);
                    extended.add(choice);
                    next.add(extended);
                }
            }
            products = next;
        }
        if (!any) {
            return intersection;
        }
        List<Type> alternatives = new ArrayList<>(products.size());
        for (List<Type> product : products) {
            alternatives.add(Type.intersection(product));
        }
        return Type.union(alternatives);
    }

    /** The members of a union, with intersections of unions distributed : {@code Shape & (Named | Label)} has two. */
    static List<Type> alternatives(Type type) {
        List<Type> out = new ArrayList<>();
        for (Type member : Type.membersOf(type)) {
            if (member instanceof Type.Intersection intersection) {
                out.addAll(Type.membersOf(distribute(intersection)));
            } else {
                out.add(member);
            }
        }
        return out;
    }

    /** Interface types and intersections of them : what {@code is} can intersect (BIP022-D5). */
    static boolean isInterfaceLike(Type type) {
        return type instanceof Type.Intersection
                || type instanceof Type.Instance instance && instance.node().isInterface()
                || type instanceof Type.JavaInstance java && java.type().isInterface();
    }

    private static boolean isMutableParameterized(Type type) {
        if (type instanceof Type.JavaInstance java) {
            return java.type() != java.util.Optional.class && java.type() != java.util.Map.Entry.class;
        }
        if (type instanceof Type.Instance inst) {
            return inst.node().kind != org.babblelang.runtime.ClassShape.Kind.VALUE_CLASS;
        }
        return false;
    }

    private boolean argumentsRelate(List<Type> subArgs, List<Type> supArgs, RelateMode mode,
                                    ConstraintStore store, Set<RelationKey> active) {
        for (int i = 0; i < supArgs.size(); i++) {
            if (!relate(subArgs.get(i), supArgs.get(i), mode, store, active, false, false)) {
                return false;
            }
        }
        return true;
    }

    private boolean anyMemberRelates(Type sub, Type.Union supUnion, RelateMode mode,
                                     ConstraintStore store, Set<RelationKey> active,
                                     boolean borrowCovariant, boolean waiveArrayNullness) {
        for (Type member : supUnion.members()) {
            int mark = store != null ? store.mark() : 0;
            if (relate(sub, member, mode, store, active, borrowCovariant, waiveArrayNullness)) {
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
     * <p>
     * Polymorphic functions ({@code BIP021-D20}): polymorphic &lt;= polymorphic by renaming of
     * equal arity and compatible bounds; polymorphic &lt;= monomorphic by instantiation satisfying
     * every bound; monomorphic is never &lt;= polymorphic.
     */
    boolean functionRelates(Type.Function from, Type.Function to, ConstraintStore store,
                                    Set<RelationKey> active, boolean withEffects) {
        int fromRest = from.restIndex();
        int toRest = to.restIndex();
        if (fromRest < 0 && from.parameters().size() < to.parameters().size()) {
            return false;
        }
        boolean fromPoly = !from.typeParameters().isEmpty();
        boolean toPoly = !to.typeParameters().isEmpty();
        if (!fromPoly && toPoly) {
            return false;
        }
        if (fromPoly && toPoly) {
            List<Type.TypeVar> fVars = from.typeParameters();
            List<Type.TypeVar> tVars = to.typeParameters();
            if (fVars.size() != tVars.size()) {
                return false;
            }
            Map<Type.TypeVar, Type> rename = new HashMap<>(fVars.size());
            for (int i = 0; i < fVars.size(); i++) {
                rename.put(fVars.get(i), tVars.get(i));
            }
            for (int i = 0; i < fVars.size(); i++) {
                Type fBound = fVars.get(i).bound();
                if (fBound != null && fBound != Type.ERROR) {
                    Type renamedFBound = substitute(fBound, rename);
                    Type tBound = tVars.get(i).effectiveBound();
                    if (!relate(tBound, renamedFBound, RelateMode.ASSIGNABLE, null, active, false, false)) {
                        return false;
                    }
                }
            }
            Type.Function renamedFrom = stripTypeParams(from, rename);
            Type.Function strippedTo = stripTypeParams(to, Map.of());
            return functionRelates(renamedFrom, strippedTo, store, active, withEffects);
        }
        if (fromPoly) {
            // Polymorphic from <= monomorphic to by instantiation (BIP021-D20).
            List<Type.TypeVar> fVars = from.typeParameters();
            Type.Function monoFrom = stripTypeParams(from, Map.of());
            ConstraintStore instStore = store(Set.copyOf(fVars), false);
            if (!functionRelates(monoFrom, to, instStore, active, false)) {
                return false;
            }
            Map<Type.TypeVar, Type> solved = new HashMap<>(fVars.size());
            for (Type.TypeVar tv : fVars) {
                Type sol = instStore.solution(tv);
                if (sol == null || sol == Type.ERROR) {
                    return false;
                }
                solved.put(tv, sol);
            }
            for (Type.TypeVar tv : fVars) {
                if (tv.bound() != null && tv.bound() != Type.ERROR) {
                    Type subBound = substitute(tv.bound(), solved);
                    if (!relate(solved.get(tv), subBound, RelateMode.ASSIGNABLE, null, active, false, false)) {
                        return false;
                    }
                }
            }
            if (findPrimitiveArraySubstitution(monoFrom, solved) != null) {
                return false;
            }
            Type.Function instantiated = stripTypeParams(from, solved);
            return functionRelates(instantiated, to, store, active, withEffects);
        }
        boolean frozenBorrowSafe = from.effects().uniqueResult() || env.isDeeplyImmutable(from.result());
        if (toRest >= 0) {
            if (fromRest < 0 || fromRest > toRest) {
                return false;
            }
            int fromKw = from.parameters().size() - (fromRest + 1);
            int toKw = to.parameters().size() - (toRest + 1);
            if (fromKw < toKw) {
                return false;
            }
            for (int i = 0; i <= toRest; i++) {
                int fi = Math.min(i, fromRest);
                Type.Parameter fp = from.parameters().get(fi);
                Type.Parameter tp = to.parameters().get(i);
                if (i < fromRest) {
                    if (tp.optional() && !fp.optional()) {
                        return false;
                    }
                    if (!tp.keywordOnly() && fp.keywordOnly()) {
                        return false;
                    }
                }
                if (!paramRelates(from, fi, fp, to, i, tp, frozenBorrowSafe, store, active, withEffects)) {
                    return false;
                }
            }
            for (int k = 0; k < fromKw; k++) {
                int fi = fromRest + 1 + k;
                Type.Parameter fp = from.parameters().get(fi);
                if (k < toKw) {
                    int ti = toRest + 1 + k;
                    Type.Parameter tp = to.parameters().get(ti);
                    if (tp.optional() && !fp.optional()) {
                        return false;
                    }
                    if (!paramRelates(from, fi, fp, to, ti, tp, frozenBorrowSafe, store, active, withEffects)) {
                        return false;
                    }
                } else if (!fp.optional()) {
                    return false;
                }
            }
        } else if (fromRest >= 0) {
            // Non-variadic target satisfied by variadic source (BIP021-D5):
            // target must not have keyword-only params (no PackedRest sentinel on non-variadic calls).
            for (Type.Parameter tp : to.parameters()) {
                if (tp.keywordOnly()) {
                    return false;
                }
            }
            int toPos = to.parameters().size();
            for (int i = 0; i < toPos; i++) {
                int fi = Math.min(i, fromRest);
                Type.Parameter fp = from.parameters().get(fi);
                Type.Parameter tp = to.parameters().get(i);
                if (i < fromRest && tp.optional() && !fp.optional()) {
                    return false;
                }
                if (!paramRelates(from, fi, fp, to, i, tp, frozenBorrowSafe, store, active, withEffects)) {
                    return false;
                }
            }
            for (int i = toPos; i < fromRest; i++) {
                if (!from.parameters().get(i).optional()) {
                    return false;
                }
            }
            for (int i = fromRest + 1; i < from.parameters().size(); i++) {
                if (!from.parameters().get(i).optional()) {
                    return false;
                }
            }
        } else {
            for (int i = 0; i < from.parameters().size(); i++) {
                Type.Parameter fp = from.parameters().get(i);
                if (i < to.parameters().size()) {
                    Type.Parameter tp = to.parameters().get(i);
                    // BIP021-D3 : a required parameter cannot satisfy an optional one,
                    // and a keyword-only parameter cannot satisfy a positional one.
                    if (tp.optional() && !fp.optional()) {
                        return false;
                    }
                    if (!tp.keywordOnly() && fp.keywordOnly()) {
                        return false;
                    }
                    if (!paramRelates(from, i, fp, to, i, tp, frozenBorrowSafe, store, active, withEffects)) {
                        return false;
                    }
                } else if (!fp.optional()) {
                    return false;
                }
            }
        }
        if (!relate(from.result(), to.result(), RelateMode.SUBTYPE, store, active, false, false)) {
            return false;
        }
        if (!withEffects) {
            return true;
        }
        EffectSet effectiveFromEffects = from.effects();
        for (int idx : from.forwardedEffectParams()) {
            if (!to.forwardedEffectParams().contains(idx) && idx < to.parameters().size()
                    && to.parameters().get(idx).type() instanceof Type.Function toCb) {
                effectiveFromEffects = effectiveFromEffects.combineForwarded(toCb.effects());
            }
        }
        if (!effectiveFromEffects.satisfies(to.effects())) {
            return false;
        }
        // A function forwarding a captured callback's effects has those effects on top of its
        // own, which only a type forwarding the same callback accounts for (BIP017-D5).
        if (!to.capturedCallings().containsAll(from.capturedCallings())) {
            return false;
        }
        return true;
    }

    private boolean paramRelates(Type.Function from, int fi, Type.Parameter fp,
                                 Type.Function to, int ti, Type.Parameter tp,
                                 boolean frozenBorrowSafe, ConstraintStore store,
                                 Set<RelationKey> active, boolean withEffects) {
        boolean borrowReadOnly = frozenBorrowSafe && fp.borrow() && !fp.mut();
        Type toParamType = tp.type();
        Type fromParamType = fp.type();
        if (withEffects) {
            if (to.forwardedEffectParams().contains(ti) && toParamType instanceof Type.Function tf) {
                toParamType = tf.withEffects(tf.effects().relaxedForForwardedCallback());
            }
            if (from.forwardedEffectParams().contains(fi) && fromParamType instanceof Type.Function ff) {
                fromParamType = ff.withEffects(ff.effects().relaxedForForwardedCallback());
            }
        }
        if (!relate(toParamType, fromParamType, RelateMode.ASSIGNABLE, store, active, borrowReadOnly, false)) {
            return false;
        }
        if (withEffects && (fp.mut() && !tp.mut() || tp.borrow() && !fp.borrow() || fp.unique() && !tp.unique())) {
            return false;
        }
        return true;
    }

    private Type.Function stripTypeParams(Type.Function fn, Map<Type.TypeVar, Type> mapping) {
        List<Type.Parameter> params = fn.parameters();
        if (!mapping.isEmpty()) {
            List<Type.Parameter> sub = new ArrayList<>(params.size());
            for (Type.Parameter p : params) {
                sub.add(p.withType(substitute(p.type(), mapping)));
            }
            params = sub;
        }
        Type res = mapping.isEmpty() ? fn.result() : substitute(fn.result(), mapping);
        return new Type.Function(List.of(), params, res, fn.effects(),
                fn.forwardedEffectParams(), fn.capturedCallings());
    }

    static Type unwrapFrozen(Type t) {
        while (t instanceof Type.Frozen f) {
            t = f.of();
        }
        return t;
    }

    static boolean isErasedToObjectArray(Type base) {
        if (base instanceof Type.Simple s) {
            return s == Type.ANY || s == Type.NULL || s == Type.NOTHING || s == Type.ERROR;
        }
        if (base instanceof Type.JavaInstance ji) {
            return ji.type() == Object.class;
        }
        return !(base instanceof Type.Instance) && !(base instanceof Type.Array);
    }

    private static Class<?> jvmRefElemClass(Type base) {
        if (base instanceof Type.Simple simple) {
            return simple.javaClass();
        }
        if (base instanceof Type.JavaInstance ji) {
            return ji.type();
        }
        return null;
    }

    static boolean jvmArrayAssignable(Type srcElem, Type tgtElem) {
        Type s = unwrapFrozen(srcElem);
        Type t = unwrapFrozen(tgtElem);
        if (Type.isPrimitiveScalar(s) || Type.isPrimitiveScalar(t)) {
            return s.equals(t);
        }
        Type sBase = unwrapFrozen(Type.withoutNull(s));
        Type tBase = unwrapFrozen(Type.withoutNull(t));
        if (sBase == Type.NOTHING || isErasedToObjectArray(tBase)) {
            return true;
        }
        if (isErasedToObjectArray(sBase)) {
            return false;
        }
        if (tBase instanceof Type.Array tInner) {
            return sBase instanceof Type.Array sInner && jvmArrayAssignable(sInner.element(), tInner.element());
        }
        if (sBase instanceof Type.Array) {
            return tBase instanceof Type.JavaInstance tj
                    && (tj.type() == Cloneable.class || tj.type() == java.io.Serializable.class);
        }
        if (tBase instanceof Type.Instance tInst) {
            return sBase instanceof Type.Instance sInst && isSubtype(sInst.node(), tInst.node());
        }
        if (tBase instanceof Type.JavaInstance tJava) {
            if (sBase instanceof Type.Instance sInst) {
                return implementsJava(sInst.node(), tJava.type());
            }
            Class<?> sc = jvmRefElemClass(sBase);
            return sc != null && tJava.type().isAssignableFrom(sc);
        }
        if (tBase instanceof Type.Simple tSimple && tSimple.javaClass() != null) {
            Class<?> sc = jvmRefElemClass(sBase);
            return sc != null && tSimple.javaClass().isAssignableFrom(sc);
        }
        return false;
    }

    // ---------------------------------------------------------------- runtime tags

    private boolean isErasedSubtype(Type from, Type to) {
        if (to == Type.ANY) {
            return !(from instanceof Type.Frozen);
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
        if (to instanceof Type.Array t) {
            return from instanceof Type.Array a && jvmArrayAssignable(a.element(), t.element());
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
        if (a instanceof Type.Frozen af) {
            return overlapsErased(af.of(), b);
        }
        if (b instanceof Type.Frozen bf) {
            return overlapsErased(a, bf.of());
        }
        if ((a instanceof Type.Function || a instanceof Type.Overloaded)
                && (b instanceof Type.Function || b instanceof Type.Overloaded)) {
            return true;
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
        if (a instanceof Type.Intersection i) {
            return i.members().stream().allMatch(m -> overlapsErased(m, b));
        }
        if (b instanceof Type.Intersection i) {
            return i.members().stream().allMatch(m -> overlapsErased(a, m));
        }
        if (a instanceof Type.Array aa && b instanceof Type.Array ba) {
            Type ae = unwrapFrozen(aa.element());
            Type be = unwrapFrozen(ba.element());
            if (Type.isPrimitiveScalar(ae) || Type.isPrimitiveScalar(be)) {
                return ae.equals(be);
            }
            Type aBase = unwrapFrozen(Type.withoutNull(ae));
            Type bBase = unwrapFrozen(Type.withoutNull(be));
            if (aBase == Type.NOTHING || bBase == Type.NOTHING) {
                return true;
            }
            return overlapsErased(aBase, bBase);
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
        if (from instanceof Type.Intersection intersection) {
            return intersection.members().stream().allMatch(member -> canCast(member, to));
        }
        if (to instanceof Type.Intersection intersection) {
            if (isCallable(from)) {
                return JavaTypeBridge.resolveJavaSam(intersection) instanceof JavaTypeBridge.JavaSamResolution.Valid
                        && assignable(from, intersection);
            }
            return intersection.members().stream().allMatch(member -> canCast(from, member));
        }
        if (from == Type.NULL || to == Type.NULL) {
            return from == to;
        }
        if (from instanceof Type.Frozen ff) {
            if (to instanceof Type.Frozen tf) {
                return canCast(ff.of(), tf.of());
            }
            if (Type.isCoreScalar(to)) {
                return canCast(ff.of(), to);
            }
            return false;
        }
        if (to instanceof Type.Frozen tf) {
            return env.isDeeplyImmutable(from) && canCast(from, tf.of());
        }
        if (from instanceof Type.TypeVar || to instanceof Type.TypeVar || from == Type.ANY || to == Type.ANY) {
            return true;
        }
        if (Type.isNumericScalar(from) && Type.isNumericScalar(to)) {
            return Type.numericRank(from) <= Type.numericRank(to);
        }
        return assignable(from, to) || assignable(to, from) || overlapsErased(from, to);
    }

    /** What {@code x is target} narrows {@code current} to (preserving {@code frozen} per BIP007-D7). */
    Type narrowTarget(Type current, Type target) {
        List<Type> narrowed = new ArrayList<>();
        for (Type member : alternatives(current)) {
            if (member == Type.NULL) {
                continue;
            }
            if (member instanceof Type.Frozen mf) {
                if (runtimeSubtype(mf.of(), target)) {
                    narrowed.add(member);
                } else if (overlapsErased(mf.of(), target)) {
                    narrowed.add(Type.frozen(target));
                }
            } else if (runtimeSubtype(member, target)) {
                narrowed.add(member);
            } else if (overlapsErased(member, target)) {
                // An interface tested against an interface keeps what it was : Shape is Named gives Shape & Named (BIP022-D5).
                narrowed.add(isInterfaceLike(member) && isInterfaceLike(target) ? Type.intersection(member, target) : target);
            }
        }
        return narrowed.isEmpty() ? target : Type.union(narrowed);
    }

    /** What remains of {@code current} when {@code x is target} fails. */
    Type subtractType(Type current, Type target) {
        List<Type> remaining = new ArrayList<>();
        for (Type member : alternatives(current)) {
            if (member instanceof Type.Frozen mf) {
                if (!runtimeSubtype(mf.of(), target)) {
                    remaining.add(member);
                }
            } else if (!runtimeSubtype(member, target)) {
                remaining.add(member);
            }
        }
        return remaining.isEmpty() ? Type.NOTHING : Type.union(remaining);
    }

    // ---------------------------------------------------------------- joins

    /**
     * The type of a value that is one of two ({@code if} branches, a loop or null), widening
     * along {@code byte < short < int < long < float < double} and uniting other types ; null when an
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
        int ra = singleNumericRank(a);
        int rb = singleNumericRank(b);
        if (ra >= 0 && rb >= 0 && ra != rb) {
            Type joined = Type.numericOfRank(Math.max(ra, rb));
            return (Type.containsNull(a) || Type.containsNull(b)) ? Type.nullable(joined) : joined;
        }
        return Type.union(a, b);
    }

    /**
     * Widening allowed across the reassignments of an unannotated def or member :
     * numeric widening along {@code byte..double}, {@code T <-> null}, and compatible functions
     * (intersecting their effects) ; null for heterogeneous types.
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
        int rc = singleNumericRank(current);
        int rv = singleNumericRank(value);
        if (rc >= 0 && rv >= 0 && rc != rv) {
            Type joined = Type.numericOfRank(Math.max(rc, rv));
            return (Type.containsNull(current) || Type.containsNull(value)) ? Type.nullable(joined) : joined;
        }
        return null;
    }

    private static boolean sameParameters(Type.Function fa, Type.Function fb) {
        if (fa.parameters().size() != fb.parameters().size()) {
            return false;
        }
        for (int i = 0; i < fa.parameters().size(); i++) {
            Type.Parameter pa = fa.parameters().get(i);
            Type.Parameter pb = fb.parameters().get(i);
            if (!pa.name().equals(pb.name()) || !pa.type().equals(pb.type())
                    || pa.optional() != pb.optional() || pa.keywordOnly() != pb.keywordOnly()) {
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
            params.add(new Type.Parameter(a.name(), a.type(), a.optional() && b.optional(),
                    a.keywordOnly() || b.keywordOnly(), mut,
                    a.borrow() && b.borrow(), a.explicitShared() || b.explicitShared(),
                    a.unique() || b.unique()));
        }
        EffectSet common = first.effects().intersect(second.effects());
        if (anyMutParam && common.isPure()) {
            EnumSet<Effect> effs = EffectSet.copySet(common.effects());
            effs.remove(Effect.PURE);
            EnumSet<Effect> decl = EffectSet.copySet(common.declared());
            decl.remove(Effect.PURE);
            common = new EffectSet(effs, decl, common.mutThis(), common.borrowThis(),
                    common.explicitSharedThis(), common.uniqueResult(), common.explicitUniqueResult(),
                    common.terminating(), common.isIO(), common.explicitVolatile(), common.axiomatic());
        }
        Set<Integer> commonForwarded = Set.of();
        if (!first.forwardedEffectParams().isEmpty() && !second.forwardedEffectParams().isEmpty()) {
            Set<Integer> inter = new java.util.LinkedHashSet<>(first.forwardedEffectParams());
            inter.retainAll(second.forwardedEffectParams());
            commonForwarded = inter;
        }
        // Forwarding a captured callback weakens a function : the join forwards those of both.
        Set<String> captured = new java.util.TreeSet<>(first.capturedCallings());
        captured.addAll(second.capturedCallings());
        return new Type.Function(first.typeParameters(), params, first.result(), common, commonForwarded, captured);
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
                Type.Function substituted = new Type.Function(fn.typeParameters(), params, substitute(fn.result(), active),
                        fn.effects(), fn.forwardedEffectParams(), fn.capturedCallings());
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
        if (source instanceof Type.Frozen) {
            return projectToJava(unwrapFrozen(source), targetClass);
        }
        if (source instanceof Type.Intersection inter) {
            for (Type member : inter.members()) {
                Type projected = projectToJava(member, targetClass);
                if (projected != null) {
                    return projected;
                }
            }
            return null;
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
        if (source instanceof Type.Array) {
            return (targetClass == Object.class || targetClass == Cloneable.class || targetClass == java.io.Serializable.class)
                    ? JavaTypeBridge.fromJava(targetClass) : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- structural predicates

    /** Whether a value of this type can be called : functions, overload sets, Java methods, and classes. */
    static boolean isCallable(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeAlgebra::isCallable);
        }
        return type instanceof Type.Function || type instanceof Type.Overloaded || type instanceof Type.JavaMethod
                || type instanceof Type.ClassValue value && !value.node().isInterface();
    }

    /**
     * Target numeric rank (1=short, 2=int, 3=long, 4=float, 5=double) when {@code from} contains
     * a numeric scalar strictly narrower than the unique numeric scalar in {@code to} (and {@code to}
     * does not already contain that narrower scalar), else -1.
     */
    static int numericWideningTargetRank(Type from, Type to) {
        if (from == Type.ERROR || to == Type.ERROR) {
            return -1;
        }
        int targetRank = -1;
        for (Type m : Type.membersOf(to)) {
            int r = Type.numericRank(m);
            if (r >= 0) {
                if (targetRank >= 0 && targetRank != r) {
                    return -1;
                }
                targetRank = r;
            }
        }
        if (targetRank <= 0) {
            return -1;
        }
        for (Type m : Type.membersOf(from)) {
            int r = Type.numericRank(m);
            if (r >= 0 && r < targetRank && !containsType(to, m)) {
                return targetRank;
            }
        }
        return -1;
    }

    static boolean needsNumericWidening(Type from, Type to) {
        return numericWideningTargetRank(from, to) >= 0;
    }

    /** Whether a value of type {@code from} is converted to {@code double} where {@code to} is expected. */
    static boolean needsDoubleWidening(Type from, Type to) {
        return numericWideningTargetRank(from, to) == 5;
    }

    /** The unique numeric rank if {@code type} (ignoring {@code null}) is a single numeric scalar, else -1. */
    static int singleNumericRank(Type type) {
        Type base = Type.withoutNull(type);
        return Type.numericRank(base);
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
        if (type instanceof Type.Function || type instanceof Type.Overloaded) {
            return true;
        }
        return type instanceof Type.Union union && union.members().stream().anyMatch(TypeAlgebra::containsFunction);
    }

    /** Whether a type is a numeric scalar (or a union of them), possibly nullable. */
    static boolean isIntOrDouble(Type type) {
        Type base = Type.withoutNull(type);
        if (Type.isNumericScalar(base)) {
            return true;
        }
        return base instanceof Type.Union u
                && u.members().stream().allMatch(Type::isNumericScalar);
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

    /** Whether {@code elem}'s runtime JVM array class still depends on a variable in {@code store.solving()}. */
    private static boolean arrayClassDependsOnSolvingVar(Type elem, ConstraintStore store) {
        if (store == null || elem == null) {
            return false;
        }
        return switch (elem) {
            case Type.TypeVar tv -> store.solving().contains(tv);
            case Type.Frozen f -> arrayClassDependsOnSolvingVar(f.of(), store);
            case Type.Array a -> arrayClassDependsOnSolvingVar(a.element(), store);
            case Type.Union u -> u.members().stream().anyMatch(m -> arrayClassDependsOnSolvingVar(m, store));
            case Type.Intersection i -> i.members().stream().anyMatch(m -> arrayClassDependsOnSolvingVar(m, store));
            default -> false;
        };
    }

    record BadArraySubstitution(Type.TypeVar cause, Type.Array patternArray, Type substitutedElement) {
        String message() {
            return "Type error : type argument " + substitutedElement + " cannot be a primitive in " + patternArray;
        }
    }

    /**
     * Checks BIP020-D13 (§2.6) : whether substituting {@code env} into {@code pattern} turns any
     * generic reference array ({@code Object[]} in generic code) into a primitive array ({@code int[]}, etc.).
     */
    BadArraySubstitution findPrimitiveArraySubstitution(Type pattern, Map<Type.TypeVar, Type> env) {
        if (pattern == null || env == null || env.isEmpty()) {
            return null;
        }
        boolean anyPrimitive = false;
        for (Type v : env.values()) {
            if (v != null && Type.isPrimitiveScalar(v)) {
                anyPrimitive = true;
                break;
            }
        }
        if (!anyPrimitive) {
            return null;
        }
        return scanPrimitiveArraySubstitution(pattern, env);
    }

    private BadArraySubstitution scanPrimitiveArraySubstitution(Type type, Map<Type.TypeVar, Type> env) {
        if (type == null) {
            return null;
        }
        if (type instanceof Type.Array arr) {
            Type elem = arr.element();
            if (!Type.isPrimitiveScalar(elem)) {
                Type substitutedElem = substitute(elem, env);
                if (Type.isPrimitiveScalar(substitutedElem)) {
                    Type.TypeVar cause = findPrimitivelyBoundVar(elem, env);
                    return new BadArraySubstitution(cause, arr, substitutedElem);
                }
            }
            return scanPrimitiveArraySubstitution(elem, env);
        }
        return switch (type) {
            case Type.Frozen f -> scanPrimitiveArraySubstitution(f.of(), env);
            case Type.Union u -> {
                for (Type m : u.members()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(m, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Type.Intersection i -> {
                for (Type m : i.members()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(m, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Type.Function fn -> {
                for (Type.Parameter p : fn.parameters()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(p.type(), env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield scanPrimitiveArraySubstitution(fn.result(), env);
            }
            case Type.Overloaded ov -> {
                for (Type.Function alt : ov.alternatives()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(alt, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Type.Instance inst -> {
                for (Type a : inst.typeArguments()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(a, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Type.JavaInstance ji -> {
                for (Type a : ji.typeArguments()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(a, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            case Type.CoreCollection cc -> {
                for (Type a : cc.typeArguments()) {
                    BadArraySubstitution found = scanPrimitiveArraySubstitution(a, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    private static Type.TypeVar findPrimitivelyBoundVar(Type elem, Map<Type.TypeVar, Type> env) {
        return switch (elem) {
            case Type.TypeVar tv -> Type.isPrimitiveScalar(env.get(tv)) ? tv : null;
            case Type.Frozen f -> findPrimitivelyBoundVar(f.of(), env);
            case Type.Union u -> {
                for (Type m : u.members()) {
                    Type.TypeVar found = findPrimitivelyBoundVar(m, env);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            default -> null;
        };
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
