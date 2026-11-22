package org.babblelang.analysis;

import org.babblelang.runtime.ClassShape;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static well-formedness checking ({@code BIP021-D10}), most-specific overload ordering
 * ({@code BIP021-D11}), static overlap/meet, runtime disjointness, and target-type selection
 * ({@code BIP021-D12}) for Babble overload sets.
 */
final class OverloadSolver {

    private OverloadSolver() {
    }

    /** One call shape tested during exhaustive ambiguity checking. */
    record CallShape(int posCount, List<String> kwNames) {
    }

    /** Result of comparing two alternatives at a call shape or concrete argument list. */
    enum Order {
        FIRST_BETTER,
        SECOND_BETTER,
        INCOMPARABLE
    }

    // ---------------------------------------------------------------- exhaustive ambiguity check (BIP021-D10)

    /**
     * Checks whether {@code alternatives} form an unambiguous overload set under {@code BIP021-D10}.
     *
     * @return {@code null} when well-formed, or the exact diagnostic message for the first witness.
     */
    static String checkWellFormed(TypeAlgebra algebra, String setName, List<Type.Function> alternatives) {
        int n = alternatives.size();
        for (int i = 0; i < n; i++) {
            Type.Function a = alternatives.get(i);
            for (int j = i + 1; j < n; j++) {
                Type.Function b = alternatives.get(j);
                String err = checkPair(algebra, setName, alternatives, i, j, a, b);
                if (err != null) {
                    return err;
                }
            }
        }
        return null;
    }

    private static String checkPair(TypeAlgebra algebra, String setName, List<Type.Function> all,
                                    int idxA, int idxB, Type.Function a, Type.Function b) {
        List<CallShape> shapes = commonShapes(a, b);
        for (CallShape shape : shapes) {
            List<Type> paramsA = effectiveParamTypes(a, shape);
            List<Type> paramsB = effectiveParamTypes(b, shape);
            if (paramsA == null || paramsB == null || paramsA.size() != paramsB.size()) {
                continue;
            }
            if (!allOverlap(algebra, paramsA, paramsB)) {
                continue;
            }
            Order order = compareAlternatives(algebra, a, paramsA, b, paramsB);
            if (order != Order.INCOMPARABLE) {
                continue;
            }
            List<Type> witness = new ArrayList<>(paramsA.size());
            boolean hasWitness = true;
            for (int r = 0; r < paramsA.size(); r++) {
                Type meet = staticMeet(algebra, paramsA.get(r), paramsB.get(r));
                if (meet == null || meet == Type.NOTHING) {
                    hasWitness = false;
                    break;
                }
                witness.add(meet);
            }
            if (!hasWitness) {
                continue;
            }
            if (hasMeetAlternative(algebra, all, idxA, idxB, a, paramsA, b, paramsB, shape, witness)) {
                continue;
            }
            return formatAmbiguity(setName, shape, witness, a, b);
        }
        return null;
    }

    private static boolean hasMeetAlternative(TypeAlgebra algebra, List<Type.Function> all,
                                              int idxA, int idxB,
                                              Type.Function a, List<Type> paramsA,
                                              Type.Function b, List<Type> paramsB,
                                              CallShape shape, List<Type> witness) {
        for (int k = 0; k < all.size(); k++) {
            if (k == idxA || k == idxB) {
                continue;
            }
            Type.Function c = all.get(k);
            List<Type> paramsC = effectiveParamTypes(c, shape);
            if (paramsC == null || paramsC.size() != witness.size()) {
                continue;
            }
            // C must accept the witness and be strictly more specific than both A and B.
            if (!acceptsWitness(algebra, c, paramsC, witness)) {
                continue;
            }
            if (compareAlternatives(algebra, c, paramsC, a, paramsA) == Order.FIRST_BETTER
                    && compareAlternatives(algebra, c, paramsC, b, paramsB) == Order.FIRST_BETTER) {
                return true;
            }
        }
        return false;
    }

    private static boolean acceptsWitness(TypeAlgebra algebra, Type.Function fn, List<Type> fnParams, List<Type> witness) {
        if (fn.typeParameters().isEmpty()) {
            for (int i = 0; i < fnParams.size(); i++) {
                if (!paramAccepts(algebra, fnParams.get(i), witness.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return genericAccepts(algebra, fn, fnParams, witness);
    }

    private static List<CallShape> commonShapes(Type.Function a, Type.Function b) {
        int minPosA = minPositional(a);
        int minPosB = minPositional(b);
        int maxPosA = maxPositional(a);
        int maxPosB = maxPositional(b);
        int minPos = Math.max(minPosA, minPosB);
        int maxPos;
        if (maxPosA == Integer.MAX_VALUE && maxPosB == Integer.MAX_VALUE) {
            maxPos = Math.max(a.restIndex(), b.restIndex()) + 1;
        } else if (maxPosA == Integer.MAX_VALUE) {
            maxPos = Math.max(maxPosB, a.restIndex());
        } else if (maxPosB == Integer.MAX_VALUE) {
            maxPos = Math.max(maxPosA, b.restIndex());
        } else {
            maxPos = Math.min(maxPosA, maxPosB);
        }
        List<CallShape> shapes = new ArrayList<>();
        for (int p = minPos; p <= maxPos; p++) {
            addShapesForPosCount(a, b, p, shapes);
        }
        for (int p = Math.min(minPos - 1, maxPos); p >= 0; p--) {
            addShapesForPosCount(a, b, p, shapes);
        }
        return shapes;
    }

    private static void addShapesForPosCount(Type.Function a, Type.Function b, int p, List<CallShape> shapes) {
        Set<String> reqKws = new LinkedHashSet<>();
        collectRequiredNamedAfter(a, p, reqKws);
        collectRequiredNamedAfter(b, p, reqKws);
        if (!canSupplyNamed(a, p, reqKws) || !canSupplyNamed(b, p, reqKws)) {
            return;
        }
        List<String> baseKw = List.copyOf(reqKws);
        shapes.add(new CallShape(p, baseKw));
        // Also test common optional named parameters that could distinguish or create a cross-ambiguity.
        Set<String> optA = availableNamedAfter(a, p, reqKws);
        Set<String> optB = availableNamedAfter(b, p, reqKws);
        List<String> commonOpts = new ArrayList<>();
        for (String name : optA) {
            if (optB.contains(name)) {
                commonOpts.add(name);
            }
        }
        for (String opt : commonOpts) {
            List<String> withOpt = new ArrayList<>(baseKw.size() + 1);
            withOpt.addAll(baseKw);
            withOpt.add(opt);
            shapes.add(new CallShape(p, withOpt));
        }
        if (commonOpts.size() >= 2 && commonOpts.size() <= 4) {
            List<String> withAll = new ArrayList<>(baseKw.size() + commonOpts.size());
            withAll.addAll(baseKw);
            withAll.addAll(commonOpts);
            shapes.add(new CallShape(p, withAll));
        }
    }

    private static int minPositional(Type.Function fn) {
        // Required positional parameters can also be passed by name! So positional count can go down to 0
        // if required positional params are passed by name — wait: in commonShapes we keep required positional
        // params as positional, and handle keyword-only params in reqKws.
        // Wait: what if A is (a: int, b: double) and B is (b: int, a: double)? Passed by name f(a: 1, b: 1)
        // has posCount = 0, kwNames = [a, b]! Let's support posCount starting at 0 when named params overlap!
        int count = 0;
        for (Type.Parameter p : fn.parameters()) {
            if (p.keywordOnly() || p.rest() || p.optional()) {
                break;
            }
            count++;
        }
        return count;
    }

    private static int maxPositional(Type.Function fn) {
        if (fn.restIndex() >= 0) {
            return Integer.MAX_VALUE;
        }
        int count = 0;
        for (Type.Parameter p : fn.parameters()) {
            if (p.keywordOnly()) {
                break;
            }
            count++;
        }
        return count;
    }

    private static void collectRequiredNamedAfter(Type.Function fn, int posCount, Set<String> out) {
        for (int i = 0; i < fn.parameters().size(); i++) {
            Type.Parameter p = fn.parameters().get(i);
            if (p.rest()) {
                continue;
            }
            if (p.keywordOnly()) {
                if (!p.optional() && !p.name().isEmpty()) {
                    out.add(p.name());
                }
            } else if (i >= posCount && !p.optional() && !p.name().isEmpty()) {
                out.add(p.name());
            }
        }
    }

    private static boolean canSupplyNamed(Type.Function fn, int posCount, Set<String> names) {
        for (String name : names) {
            int idx = findNamedParam(fn, name);
            if (idx < 0) {
                return false;
            }
            Type.Parameter p = fn.parameters().get(idx);
            if (p.rest()) {
                return false;
            }
            if (!p.keywordOnly() && idx < posCount) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> availableNamedAfter(Type.Function fn, int posCount, Set<String> already) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < fn.parameters().size(); i++) {
            Type.Parameter p = fn.parameters().get(i);
            if (p.rest() || p.name().isEmpty() || already.contains(p.name())) {
                continue;
            }
            if (p.keywordOnly() || i >= posCount) {
                out.add(p.name());
            }
        }
        return out;
    }

    private static int findNamedParam(Type.Function fn, String name) {
        for (int i = 0; i < fn.parameters().size(); i++) {
            Type.Parameter p = fn.parameters().get(i);
            if (!p.rest() && name.equals(p.name())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the parameter types of {@code fn} at {@code shape} (positional args first, then named args
     * in {@code shape.kwNames()} order), or {@code null} if {@code fn} does not accept {@code shape}.
     */
    static List<Type> effectiveParamTypes(Type.Function fn, CallShape shape) {
        int restIdx = fn.restIndex();
        int maxPos = maxPositional(fn);
        if (shape.posCount() > maxPos) {
            return null;
        }
        List<Type.Parameter> params = fn.parameters();
        boolean[] filled = new boolean[params.size()];
        List<Type> types = new ArrayList<>(shape.posCount() + shape.kwNames().size());
        for (int i = 0; i < shape.posCount(); i++) {
            int pIdx = (restIdx >= 0 && i >= restIdx) ? restIdx : i;
            if (pIdx >= params.size()) {
                return null;
            }
            Type.Parameter p = params.get(pIdx);
            if (p.keywordOnly()) {
                return null;
            }
            if (!p.rest()) {
                filled[pIdx] = true;
            }
            types.add(p.type());
        }
        for (String name : shape.kwNames()) {
            int pIdx = findNamedParam(fn, name);
            if (pIdx < 0 || filled[pIdx]) {
                return null;
            }
            filled[pIdx] = true;
            types.add(params.get(pIdx).type());
        }
        for (int i = 0; i < params.size(); i++) {
            Type.Parameter p = params.get(i);
            if (!p.rest() && !p.optional() && !filled[i]) {
                return null;
            }
        }
        return types;
    }

    private static String formatAmbiguity(String setName, CallShape shape, List<Type> witness,
                                          Type.Function a, Type.Function b) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < witness.size(); i++) {
            if (i > 0) {
                args.append(", ");
            }
            if (i >= shape.posCount()) {
                args.append(shape.kwNames().get(i - shape.posCount())).append(": ");
            }
            args.append(TypeDisplay.display(witness.get(i)));
        }
        return "Ambiguous overloads of " + setName + " : " + setName + "(" + args + ") fits "
                + formatAlternative(a) + " and " + formatAlternative(b);
    }

    static String formatAlternative(Type.Function fn) {
        return fn.typeParameters().isEmpty() ? "fn " + fn : fn.toString();
    }

    // ---------------------------------------------------------------- specificity ordering (BIP021-D10, BIP021-D11)

    /**
     * Compares two alternatives at a common parameter shape (same arity).
     * {@code A} is strictly more specific than {@code B} when {@code B} accepts every argument list
     * {@code A} accepts (by subtyping, numeric widening, or generic instantiation) and not vice versa.
     */
    static Order compareAlternatives(TypeAlgebra algebra,
                                     Type.Function a, List<Type> paramsA,
                                     Type.Function b, List<Type> paramsB) {
        boolean bAcceptsA = alternativeAccepts(algebra, b, paramsB, a, paramsA);
        boolean aAcceptsB = alternativeAccepts(algebra, a, paramsA, b, paramsB);
        if (bAcceptsA && !aAcceptsB) {
            return Order.FIRST_BETTER;
        }
        if (aAcceptsB && !bAcceptsA) {
            return Order.SECOND_BETTER;
        }
        return Order.INCOMPARABLE;
    }

    /** Whether {@code general} can accept every argument tuple that {@code specific} accepts. */
    private static boolean alternativeAccepts(TypeAlgebra algebra,
                                              Type.Function general, List<Type> genParams,
                                              Type.Function specific, List<Type> specParams) {
        if (genParams.size() != specParams.size()) {
            return false;
        }
        if (general.typeParameters().isEmpty()) {
            for (int i = 0; i < genParams.size(); i++) {
                if (!paramAccepts(algebra, genParams.get(i), specParams.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return genericAccepts(algebra, general, genParams, specParams);
    }

    /** Whether a generic alternative {@code general} can be instantiated to accept {@code argTypes}. */
    private static boolean genericAccepts(TypeAlgebra algebra, Type.Function general,
                                          List<Type> genParams, List<Type> argTypes) {
        // Rename general's type vars fresh so they never collide with argTypes' own type vars.
        Map<Type.TypeVar, Type> freshMap = new HashMap<>();
        List<Type.TypeVar> freshVars = new ArrayList<>(general.typeParameters().size());
        for (int i = 0; i < general.typeParameters().size(); i++) {
            Type.TypeVar tv = general.typeParameters().get(i);
            Type.TypeVar fresh = new Type.TypeVar(tv.owner(), tv.name(), i, null);
            freshMap.put(tv, fresh);
            freshVars.add(fresh);
        }
        // Apply bounds with fresh vars substituted (supports F-bounds).
        List<Type.TypeVar> boundedFresh = new ArrayList<>(freshVars.size());
        Map<Type.TypeVar, Type> finalMap = new HashMap<>();
        for (int i = 0; i < general.typeParameters().size(); i++) {
            Type.TypeVar orig = general.typeParameters().get(i);
            Type bound = orig.bound() != null ? algebra.substitute(orig.bound(), freshMap) : null;
            Type.TypeVar fv = new Type.TypeVar(orig.owner(), orig.name(), i, bound);
            boundedFresh.add(fv);
            finalMap.put(orig, fv);
        }
        Set<Type.TypeVar> solving = new LinkedHashSet<>(boundedFresh);
        TypeAlgebra.ConstraintStore store = algebra.store(solving, true);
        for (int i = 0; i < genParams.size(); i++) {
            Type pattern = algebra.substitute(genParams.get(i), finalMap);
            Type arg = argTypes.get(i);
            if (!algebra.relate(arg, pattern, TypeAlgebra.RelateMode.ASSIGNABLE, store)) {
                return false;
            }
        }
        Map<Type.TypeVar, Type> solved = solveConstraints(store, boundedFresh);
        for (Type.TypeVar tv : boundedFresh) {
            Type inferred = solved.get(tv);
            if (inferred == null || inferred == Type.NOTHING) {
                continue;
            }
            if (tv.bound() != null) {
                Type bound = algebra.substitute(tv.bound(), solved);
                if (!algebra.assignable(inferred, bound)) {
                    return false;
                }
            }
        }
        // Verify each substituted parameter accepts the corresponding arg type.
        for (int i = 0; i < genParams.size(); i++) {
            Type instParam = algebra.substitute(algebra.substitute(genParams.get(i), finalMap), solved);
            if (!paramAccepts(algebra, instParam, argTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    static Map<Type.TypeVar, Type> solveConstraints(TypeAlgebra.ConstraintStore store, List<Type.TypeVar> vars) {
        Map<Type.TypeVar, Type> solved = new HashMap<>();
        for (Type.TypeVar tv : vars) {
            Type t = store.lower(tv);
            if (t == null) {
                List<Type> ups = store.uppers(tv);
                if (!ups.isEmpty()) {
                    t = ups.get(0);
                }
            }
            if (t != null) {
                solved.put(tv, t);
            }
        }
        return solved;
    }

    /** Whether a parameter of type {@code paramType} accepts an argument of type {@code argType} (subtype or widening). */
    static boolean paramAccepts(TypeAlgebra algebra, Type paramType, Type argType) {
        if (paramType.equals(argType)) {
            return true;
        }
        if (algebra.assignable(argType, paramType)) {
            return true;
        }
        // Numeric widening along byte < short < int < long < float < double (BIP011-D4).
        int rArg = Type.numericRank(argType);
        int rParam = Type.numericRank(paramType);
        return rArg >= 0 && rParam >= 0 && rArg <= rParam;
    }

    // ---------------------------------------------------------------- static overlap & meet (BIP021 §4.5 table)

    private static boolean allOverlap(TypeAlgebra algebra, List<Type> a, List<Type> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!staticOverlaps(algebra, a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Static overlap per BIP021 §4.5 table : whether some static argument type fits both {@code a} and {@code b}. */
    static boolean staticOverlaps(TypeAlgebra algebra, Type a, Type b) {
        return staticOverlaps(algebra, a, b, new HashSet<>());
    }

    private static boolean staticOverlaps(TypeAlgebra algebra, Type a, Type b, Set<String> active) {
        a = TypeAlgebra.unwrapFrozen(a);
        b = TypeAlgebra.unwrapFrozen(b);
        if (a == Type.ERROR || b == Type.ERROR || a == Type.NOTHING || b == Type.NOTHING || a.equals(b)) {
            return true;
        }
        if (a instanceof Type.Union || b instanceof Type.Union) {
            for (Type ma : Type.membersOf(a)) {
                for (Type mb : Type.membersOf(b)) {
                    if (staticOverlaps(algebra, ma, mb, active)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (a == Type.NULL || b == Type.NULL) {
            return a == b;
        }
        if (a == Type.ANY || b == Type.ANY) {
            return true;
        }
        if (a instanceof Type.TypeVar tv) {
            return typeVarOverlaps(algebra, tv, b, active);
        }
        if (b instanceof Type.TypeVar tv) {
            return typeVarOverlaps(algebra, tv, a, active);
        }
        if (a instanceof Type.Intersection ia) {
            return intersectionOverlaps(algebra, ia, b, active);
        }
        if (b instanceof Type.Intersection ib) {
            return intersectionOverlaps(algebra, ib, a, active);
        }
        // Numeric scalars overlap along the widening chain byte -> short -> int -> long -> float -> double.
        if (Type.isNumericScalar(a) && Type.isNumericScalar(b)) {
            return true;
        }
        if (Type.isPrimitiveScalar(a) && Type.isPrimitiveScalar(b)) {
            return a.equals(b);
        }
        // Function types overlap iff they share a reachable arity and have overlapping results.
        if (a instanceof Type.Function fa && b instanceof Type.Function fb) {
            return !commonShapes(fa, fb).isEmpty() && staticOverlaps(algebra, fa.result(), fb.result(), active);
        }
        if (a instanceof Type.Function || b instanceof Type.Function) {
            return false;
        }
        // Arrays: primitive elements must be equal; reference elements must statically overlap.
        if (a instanceof Type.Array aa && b instanceof Type.Array ba) {
            Type ae = TypeAlgebra.unwrapFrozen(aa.element());
            Type be = TypeAlgebra.unwrapFrozen(ba.element());
            if (Type.isPrimitiveScalar(ae) || Type.isPrimitiveScalar(be)) {
                return ae.equals(be);
            }
            return staticOverlaps(algebra, ae, be, active);
        }
        if (a instanceof Type.Array || b instanceof Type.Array) {
            return algebra.assignable(a, b) || algebra.assignable(b, a);
        }
        // Babble classes, records, and interfaces.
        if (a instanceof Type.Instance ia && b instanceof Type.Instance ib) {
            if (ia.node() == ib.node()) {
                return typeArgsOverlap(algebra, ia.node().kind == ClassShape.Kind.VALUE_CLASS,
                        ia.typeArguments(), ib.typeArguments(), active);
            }
            if (ia.node().isInterface() && ib.node().isInterface()) {
                return true;
            }
            if (ia.node().isInterface()) {
                Type proj = algebra.projectToBabble(ib, ia.node());
                return proj instanceof Type.Instance pi
                        && typeArgsOverlap(algebra, false, ia.typeArguments(), pi.typeArguments(), active);
            }
            if (ib.node().isInterface()) {
                Type proj = algebra.projectToBabble(ia, ib.node());
                return proj instanceof Type.Instance pi
                        && typeArgsOverlap(algebra, false, pi.typeArguments(), ib.typeArguments(), active);
            }
            // Two distinct Babble classes/records never overlap (no class inheritance).
            return false;
        }
        if (a instanceof Type.Instance ia && b instanceof Type.JavaInstance jb) {
            return babbleJavaOverlap(algebra, ia, jb, active);
        }
        if (a instanceof Type.JavaInstance ja && b instanceof Type.Instance ib) {
            return babbleJavaOverlap(algebra, ib, ja, active);
        }
        // Parameterized Java instances (including list, set, map, entry, Iterable, etc.).
        if (a instanceof Type.JavaInstance ja && b instanceof Type.JavaInstance jb) {
            return javaTypesOverlap(algebra, ja, jb, active);
        }
        // Core scalar vs Java interface/supertype (e.g. int vs Comparable of int, string vs CharSequence).
        if (algebra.assignable(a, b) || algebra.assignable(b, a)) {
            return true;
        }
        return javaClassesCastable(JavaTypeBridge.javaClassOf(a), JavaTypeBridge.javaClassOf(b));
    }

    private static boolean typeVarOverlaps(TypeAlgebra algebra, Type.TypeVar tv, Type other, Set<String> active) {
        String key = "TV:" + System.identityHashCode(tv) + ":" + other;
        if (!active.add(key)) {
            return true;
        }
        try {
            Type bound = tv.bound() != null ? tv.bound() : Type.NULLABLE_ANY;
            return staticOverlaps(algebra, bound, other, active);
        } finally {
            active.remove(key);
        }
    }

    private static boolean intersectionOverlaps(TypeAlgebra algebra, Type.Intersection inter, Type other, Set<String> active) {
        for (Type m : inter.members()) {
            if (!staticOverlaps(algebra, m, other, active)) {
                return false;
            }
        }
        return true;
    }

    private static boolean javaClassesCastable(Class<?> ca, Class<?> cb) {
        if (ca == null || cb == null) {
            return false;
        }
        return (ca.isInterface() && cb.isInterface())
                || (ca.isInterface() && !Modifier.isFinal(cb.getModifiers()))
                || (cb.isInterface() && !Modifier.isFinal(ca.getModifiers()));
    }

    private static boolean babbleJavaOverlap(TypeAlgebra algebra, Type.Instance bi, Type.JavaInstance ji, Set<String> active) {
        if (ji.type().isInterface()) {
            if (bi.node().isInterface()) {
                return true;
            }
            Type proj = algebra.projectToJava(bi, ji.type());
            return proj instanceof Type.JavaInstance pj
                    && typeArgsOverlap(algebra, isCovariantJavaClass(ji.type()),
                    pj.typeArguments(), ji.typeArguments(), active);
        }
        return (bi.node().isInterface() && !Modifier.isFinal(ji.type().getModifiers()))
                || ji.type() == Object.class;
    }

    private static boolean javaTypesOverlap(TypeAlgebra algebra, Type.JavaInstance ja, Type.JavaInstance jb, Set<String> active) {
        Class<?> ca = ja.type();
        Class<?> cb = jb.type();
        if (ca == cb) {
            return typeArgsOverlap(algebra, isCovariantJavaClass(ca), ja.typeArguments(), jb.typeArguments(), active);
        }
        if (ca.isAssignableFrom(cb)) {
            return javaSuperOverlap(algebra, ja, jb, active);
        }
        if (cb.isAssignableFrom(ca)) {
            return javaSuperOverlap(algebra, jb, ja, active);
        }
        return javaClassesCastable(ca, cb);
    }

    private static boolean javaSuperOverlap(TypeAlgebra algebra, Type.JavaInstance sup, Type.JavaInstance sub, Set<String> active) {
        Type proj = algebra.projectToJava(sub, sup.type());
        return !(proj instanceof Type.JavaInstance pj)
                || typeArgsOverlap(algebra, isCovariantJavaClass(sup.type()), sup.typeArguments(), pj.typeArguments(), active);
    }

    private static boolean isCovariantJavaClass(Class<?> cls) {
        return cls == java.util.Map.Entry.class || cls == Iterable.class || cls == java.util.Iterator.class;
    }

    private static boolean typeArgsOverlap(TypeAlgebra algebra, boolean covariant,
                                           List<Type> argsA, List<Type> argsB, Set<String> active) {
        if (argsA.isEmpty() || argsB.isEmpty() || argsA.size() != argsB.size()) {
            return true;
        }
        for (int i = 0; i < argsA.size(); i++) {
            Type ta = argsA.get(i);
            Type tb = argsB.get(i);
            boolean ok = (covariant || ta instanceof Type.TypeVar || tb instanceof Type.TypeVar)
                    ? staticOverlaps(algebra, ta, tb, active)
                    : ta.equals(tb);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes a static witness type that fits both {@code a} and {@code b} (their static meet),
     * or {@code null} if they do not statically overlap.
     */
    static Type staticMeet(TypeAlgebra algebra, Type a, Type b) {
        return staticMeet(algebra, a, b, new HashSet<>());
    }

    private static Type staticMeet(TypeAlgebra algebra, Type a, Type b, Set<String> active) {
        if (!staticOverlaps(algebra, a, b)) {
            return null;
        }
        if (a.equals(b)) {
            return a;
        }
        if (a == Type.NULL || b == Type.NULL) {
            Type other = a == Type.NULL ? b : a;
            return Type.containsNull(other) ? Type.NULL : null;
        }
        if (a == Type.ANY || b == Type.ANY) {
            Type nonNull = Type.withoutNull(a == Type.ANY ? b : a);
            return nonNull != Type.NOTHING ? nonNull : null;
        }
        if (a instanceof Type.TypeVar tv) {
            return typeVarMeet(algebra, tv, b, active);
        }
        if (b instanceof Type.TypeVar tv) {
            return typeVarMeet(algebra, tv, a, active);
        }
        if (a instanceof Type.Union || b instanceof Type.Union) {
            // Prefer non-null witness when available.
            Type nullWitness = null;
            for (Type x : Type.membersOf(a)) {
                for (Type y : Type.membersOf(b)) {
                    Type m = staticMeet(algebra, x, y, active);
                    if (m != null && m != Type.NOTHING) {
                        if (m == Type.NULL) {
                            nullWitness = m;
                        } else {
                            return m;
                        }
                    }
                }
            }
            return nullWitness;
        }
        if (Type.isNumericScalar(a) && Type.isNumericScalar(b)) {
            return Type.numericRank(a) <= Type.numericRank(b) ? a : b;
        }
        if (paramAccepts(algebra, b, a)) {
            return a;
        }
        if (paramAccepts(algebra, a, b)) {
            return b;
        }
        // Two interfaces (or intersections of interfaces) meet at their intersection A & B (BIP022).
        if (isInterfaceLike(a) && isInterfaceLike(b)) {
            return Type.intersection(a, b);
        }
        return a;
    }

    private static Type typeVarMeet(TypeAlgebra algebra, Type.TypeVar tv, Type other, Set<String> active) {
        String key = "M:" + System.identityHashCode(tv) + ":" + other;
        if (!active.add(key)) {
            return other;
        }
        try {
            Type bound = tv.bound() != null ? tv.bound() : Type.NULLABLE_ANY;
            return staticMeet(algebra, bound, other, active);
        } finally {
            active.remove(key);
        }
    }

    static boolean isInterfaceLike(Type t) {
        if (t instanceof Type.Instance i) {
            return i.node().isInterface();
        }
        if (t instanceof Type.JavaInstance j) {
            return j.type().isInterface();
        }
        return t instanceof Type.Intersection;
    }

    // ---------------------------------------------------------------- target-type selection & signature key (BIP021-D12)

    /**
     * Picks the index of the most specific alternative in {@code set} assignable to {@code target},
     * or {@code -1} if none matches.
     */
    static int selectAlternativeForFunction(TypeAlgebra algebra, Type.Overloaded set, Type.Function target,
                                            boolean checkingEffects) {
        List<Type.Function> alts = set.alternatives();
        List<Type> targetParams = target.parameters().stream().map(Type.Parameter::type).toList();
        int best = -1;
        for (int i = 0; i < alts.size(); i++) {
            Type.Function alt = alts.get(i);
            if (!algebra.functionRelates(alt, target, null, new HashSet<>(), checkingEffects)) {
                continue;
            }
            if (best < 0) {
                best = i;
            } else {
                CallShape shape = new CallShape(targetParams.size(), List.of());
                List<Type> pa = effectiveParamTypes(alt, shape);
                List<Type> pb = effectiveParamTypes(alts.get(best), shape);
                if (pa != null && pb != null && compareAlternatives(algebra, alt, pa, alts.get(best), pb) == Order.FIRST_BETTER) {
                    best = i;
                }
            }
        }
        return best;
    }

    /**
     * Picks the index of the most specific alternative in {@code set} assignable to the functional
     * Java interface {@code samInterface}, or {@code -1} if none matches.
     */
    static int selectAlternativeForSam(TypeAlgebra algebra, Type.Overloaded set, Type.JavaInstance samInterface) {
        List<Type.Function> alts = set.alternatives();
        int best = -1;
        for (int i = 0; i < alts.size(); i++) {
            Type.Function alt = alts.get(i);
            if (!algebra.assignable(alt, samInterface)) {
                continue;
            }
            if (best < 0) {
                best = i;
            } else {
                Type.Function prev = alts.get(best);
                if (alt.parameters().size() == prev.parameters().size()) {
                    List<Type> pa = alt.parameters().stream().map(Type.Parameter::type).toList();
                    List<Type> pb = prev.parameters().stream().map(Type.Parameter::type).toList();
                    if (compareAlternatives(algebra, alt, pa, prev, pb) == Order.FIRST_BETTER) {
                        best = i;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Picks the index of the most specific alternative in {@code set} accepting a Java interface
     * method's parameter types (BIP021-D13), or {@code -1} if none matches. Tries exact Java
     * nullability first, then falls back to non-null at {@code UNSPECIFIED} positions when needed.
     */
    static int selectAlternativeForJavaMethod(TypeAlgebra algebra, Type.Overloaded set,
                                              java.lang.reflect.Method jm,
                                              List<Type> javaParamTypes,
                                              List<Type> fallbackParamTypes) {
        int match = matchJavaMethodPass(algebra, set, jm, javaParamTypes);
        if (match >= 0 || fallbackParamTypes == null) {
            return match;
        }
        return matchJavaMethodPass(algebra, set, jm, fallbackParamTypes);
    }

    private static int matchJavaMethodPass(TypeAlgebra algebra, Type.Overloaded set,
                                           java.lang.reflect.Method jm, List<Type> paramTypes) {
        List<Type.Function> alts = set.alternatives();
        int count = paramTypes.size();
        CallShape shape = new CallShape(count, List.of());
        List<Integer> matchedIdx = new ArrayList<>();
        List<List<Type>> matchedEff = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            Type.Function alt = alts.get(i);
            List<Type> eff = effectiveParamTypes(alt, shape);
            if (eff != null && acceptsWitness(algebra, alt, eff, paramTypes)) {
                matchedIdx.add(i);
                matchedEff.add(eff);
                continue;
            }
            if (jm.isVarArgs() && count > 0 && alt.restIndex() == count - 1) {
                Type last = Type.withoutNull(paramTypes.get(count - 1));
                if (last instanceof Type.Array arr) {
                    List<Type> expanded = new ArrayList<>(count);
                    List<Type> effVar = new ArrayList<>(count);
                    for (int j = 0; j < count - 1; j++) {
                        expanded.add(paramTypes.get(j));
                        effVar.add(alt.parameters().get(j).type());
                    }
                    expanded.add(arr.element());
                    effVar.add(alt.parameters().get(count - 1).type());
                    if (acceptsWitness(algebra, alt, effVar, expanded)) {
                        matchedIdx.add(i);
                        matchedEff.add(effVar);
                    }
                }
            }
        }
        return pickMostSpecificIndex(algebra, alts, matchedIdx, matchedEff);
    }

    /** Picks the index of the most specific alternative in {@code set} accepting positional {@code argTypes}, or {@code -1}. */
    static int selectAlternativeForPositionalArgs(TypeAlgebra algebra, Type.Overloaded set, List<Type> argTypes) {
        List<Type.Function> alts = set.alternatives();
        CallShape shape = new CallShape(argTypes.size(), List.of());
        List<Integer> matchedIdx = new ArrayList<>();
        List<List<Type>> matchedEff = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            Type.Function alt = alts.get(i);
            List<Type> eff = effectiveParamTypes(alt, shape);
            if (eff != null && acceptsWitness(algebra, alt, eff, argTypes)) {
                matchedIdx.add(i);
                matchedEff.add(eff);
            }
        }
        return pickMostSpecificIndex(algebra, alts, matchedIdx, matchedEff);
    }

    private static int pickMostSpecificIndex(TypeAlgebra algebra, List<Type.Function> alts,
                                             List<Integer> matchedIdx, List<List<Type>> matchedEff) {
        if (matchedIdx.isEmpty()) {
            return -1;
        }
        int bestPos = -1;
        for (int i = 0; i < matchedIdx.size(); i++) {
            boolean beaten = false;
            Type.Function cand = alts.get(matchedIdx.get(i));
            List<Type> candEff = matchedEff.get(i);
            for (int j = 0; j < matchedIdx.size(); j++) {
                if (i == j) {
                    continue;
                }
                Type.Function other = alts.get(matchedIdx.get(j));
                if (compareAlternatives(algebra, other, matchedEff.get(j), cand, candEff) == Order.FIRST_BETTER) {
                    beaten = true;
                    break;
                }
            }
            if (!beaten) {
                if (bestPos >= 0) {
                    return -1;
                }
                bestPos = i;
            }
        }
        return bestPos >= 0 ? matchedIdx.get(bestPos) : -1;
    }

    /** Fully qualified Java method signature for BIP021 §4.8 diagnostics, e.g. {@code java.lang.Appendable.append(java.lang.CharSequence, int, int)}. */
    static String javaMethodSignature(java.lang.reflect.Method method) {
        StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName())
                .append('.').append(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getTypeName());
        }
        return sb.append(')').toString();
    }

    /** Canonical signature key for {@link org.babblelang.runtime.OverloadSet#select(String)}. */
    static String signatureKey(Type.Function fn) {
        StringBuilder sb = new StringBuilder();
        List<Type.Parameter> params = fn.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(paramDescriptor(params.get(i).type()));
        }
        sb.append('#').append(fn.restIndex());
        return sb.toString();
    }

    /** Runtime type descriptor for a parameter type (used in {@link org.babblelang.runtime.OverloadSet}). */
    static String paramDescriptor(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NULLABLE_ANY || type instanceof Type.TypeVar) {
            return "";
        }
        String desc = LoweringAnalyzer.descriptor(type);
        return desc != null ? desc : "";
    }

    /** Parameter descriptors array for runtime {@link org.babblelang.runtime.OverloadSet.AltMeta}. */
    static String[] paramDescriptors(Type.Function fn) {
        List<Type.Parameter> params = fn.parameters();
        String[] descs = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            descs[i] = paramDescriptor(params.get(i).type());
        }
        return descs;
    }

    /** Runtime type-test descriptor list for one union-split component (supports {@code null} and {@code A & B}). */
    static List<String> runtimeTestDescriptors(Type component) {
        if (component == Type.NULL) {
            return List.of("null");
        }
        Type base = TypeAlgebra.unwrapFrozen(component);
        if (base instanceof Type.Intersection inter) {
            List<String> descs = new ArrayList<>(inter.members().size());
            for (Type m : inter.members()) {
                String d = LoweringAnalyzer.descriptor(m);
                if (d != null) {
                    descs.add(d);
                }
            }
            return descs;
        }
        String d = LoweringAnalyzer.descriptor(base);
        return d != null ? List.of(d) : List.of();
    }

    /**
     * Checks whether two distinct split components {@code ca} and {@code cb} (that select different
     * alternatives) can be reliably told apart at runtime (BIP021-D11 #4).
     *
     * <p>Returns {@code true} if they are runtime-disjoint ({@code !overlapsErased}), OR if both
     * have concrete runtime test descriptors and one is strictly more specific than the other
     * (e.g. {@code Shape & Named} vs {@code Shape}, or {@code Circle} vs {@code Shape}) with
     * distinct runtime test descriptors (so erased generics like {@code list of int} vs
     * {@code list of string} return {@code false}).</p>
     */
    static boolean runtimeDistinguishable(TypeAlgebra algebra, Type ca, Type cb) {
        if (!algebra.overlapsErased(ca, cb)) {
            return true;
        }
        // Two functions or any TypeVar are never distinguishable at runtime.
        Type ua = TypeAlgebra.unwrapFrozen(ca);
        Type ub = TypeAlgebra.unwrapFrozen(cb);
        if (ua instanceof Type.TypeVar || ub instanceof Type.TypeVar
                || ua instanceof Type.Function || ub instanceof Type.Function
                || ua instanceof Type.Overloaded || ub instanceof Type.Overloaded
                || ua == Type.ANY || ub == Type.ANY) {
            return false;
        }
        List<String> da = runtimeTestDescriptors(ca);
        List<String> db = runtimeTestDescriptors(cb);
        if (da.isEmpty() || db.isEmpty() || new HashSet<>(da).equals(new HashSet<>(db))) {
            return false;
        }
        // Must be strictly ordered by specificity so testing the more specific one first is sound.
        boolean aSubB = algebra.assignable(ca, cb);
        boolean bSubA = algebra.assignable(cb, ca);
        return aSubB != bSubA;
    }

    static String formatCandidates(List<Type.Function> alternatives) {
        return alternatives.stream()
                .map(OverloadSolver::formatParamList)
                .collect(Collectors.joining(", "));
    }

    private static String formatParamList(Type.Function fn) {
        String s = TypeDisplay.display(fn);
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        return open >= 0 && close > open ? s.substring(open, close + 1) : s;
    }
}
