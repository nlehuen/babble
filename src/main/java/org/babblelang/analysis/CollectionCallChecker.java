package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Core collection and array factory call typing extracted from {@link CallSolver} (BIP027-I1):
 * {@code array}, {@code array.of}, {@code entry}, {@code list}, {@code set}, {@code sortedset},
 * {@code map}, and {@code sortedmap}, with unified container factory checking.
 */
final class CollectionCallChecker {
    private final TypeChecker checker;

    CollectionCallChecker(TypeChecker checker) {
        this.checker = checker;
    }

    Type coreCollectionCall(String kind, List<Type> explicitArgs, BabbleParser.CallContext ctx, Type expected) {
        if ("array".equals(kind)) {
            return checkArrayCall(explicitArgs, ctx, expected);
        }
        Class<?> javaClass = switch (kind) {
            case "list" -> List.class;
            case "set" -> Set.class;
            case "sortedset" -> SortedSet.class;
            case "map" -> Map.class;
            case "sortedmap" -> SortedMap.class;
            case "entry" -> Map.Entry.class;
            default -> throw new IllegalStateException("Unknown core collection: " + kind);
        };
        boolean isMap = "map".equals(kind) || "sortedmap".equals(kind);
        int expectedArity = (isMap || "entry".equals(kind)) ? 2 : 1;
        List<BabbleParser.CallParameterContext> args = ctx.callParameters().callParameter();
        List<Type> expectedTypeArgs = extractExpectedCollectionArgs(expected, javaClass, expectedArity);
        if ("entry".equals(kind)) {
            return checkEntryCall(args, explicitArgs, expectedTypeArgs, ctx);
        }
        return checkContainerFactoryCall(kind, javaClass, isMap, args, explicitArgs, expectedTypeArgs, ctx);
    }

    Type arrayOfCall(Type.ArrayOfFactory factory, BabbleParser.CallContext ctx, Type expected) {
        List<BabbleParser.CallParameterContext> args = ctx.callParameters().callParameter();
        for (BabbleParser.CallParameterContext arg : args) {
            String name = ScopeAnalysis.callParamName(arg);
            if (name != null) {
                synthAll(args);
                checker.warn("No such parameter : " + name, ctx);
                return Type.ERROR;
            }
        }
        Type explicitElem = factory.element();
        Type expectedElem = explicitElem == null ? extractExpectedArrayElement(expected) : null;
        Type hintElem = explicitElem != null ? explicitElem : expectedElem;

        if (args.isEmpty()) {
            if (hintElem == null) {
                checker.warn("Missing type : cannot infer type arguments for array", ctx);
                return Type.ERROR;
            }
            if (dependsOnTypeVar(hintElem)) {
                checker.warn("Generic array creation : " + new Type.Array(hintElem), ctx);
                return Type.ERROR;
            }
            return new Type.Array(hintElem);
        }

        List<BabbleParser.ExpressionContext> exprs = new ArrayList<>(args.size());
        List<Type> actuals = new ArrayList<>(args.size());
        for (BabbleParser.CallParameterContext arg : args) {
            exprs.add(arg.expression());
            actuals.add(checker.check(arg.expression(), hintElem));
        }

        Type elem;
        if (explicitElem != null) {
            elem = explicitElem;
            for (int i = 0; i < exprs.size(); i++) {
                checker.subsumes(exprs.get(i), actuals.get(i), elem);
            }
        } else if (expectedElem != null && allAssignable(actuals, expectedElem)) {
            elem = expectedElem;
            for (int i = 0; i < exprs.size(); i++) {
                checker.coerce(exprs.get(i), actuals.get(i), elem);
            }
        } else {
            elem = actuals.get(0);
            for (int i = 1; i < actuals.size(); i++) {
                elem = checker.algebra().join(elem, actuals.get(i));
                if (elem == null) {
                    return Type.ERROR;
                }
            }
            if (TypeChecker.isUnresolved(elem)) {
                return Type.ERROR;
            }
            for (int i = 0; i < exprs.size(); i++) {
                checker.coerce(exprs.get(i), actuals.get(i), elem);
            }
        }

        if (dependsOnTypeVar(elem)) {
            checker.warn("Generic array creation : " + new Type.Array(elem), ctx);
            return Type.ERROR;
        }
        return new Type.Array(elem);
    }

    private void synthAll(List<BabbleParser.CallParameterContext> args) {
        for (BabbleParser.CallParameterContext arg : args) {
            checker.synth(arg.expression());
        }
    }

    private List<Type> extractExpectedCollectionArgs(Type expected, Class<?> targetClass, int arity) {
        if (expected == null || expected == Type.ERROR || expected == Type.NOTHING) {
            return null;
        }
        Type base = Type.withoutNull(expected);
        if (base instanceof Type.JavaInstance ji) {
            Type projected = checker.algebra().projectToJava(new Type.JavaInstance(targetClass, List.of()), ji.type());
            if (projected != null && ji.typeArguments().size() == arity && ji.type().isAssignableFrom(targetClass)) {
                return ji.typeArguments();
            }
            if (arity == 1 && (ji.type() == Iterable.class || ji.type() == Collection.class) && ji.typeArguments().size() == 1) {
                return ji.typeArguments();
            }
        }
        return null;
    }

    private Type extractExpectedArrayElement(Type expected) {
        if (expected == null || expected == Type.ERROR || expected == Type.NOTHING) {
            return null;
        }
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(expected));
        if (base instanceof Type.Array arr && !arr.element().hasSyntheticTypeVar()) {
            return arr.element();
        }
        return null;
    }

    private static boolean dependsOnTypeVar(Type t) {
        return switch (t) {
            case Type.TypeVar ignored -> true;
            case Type.Frozen f -> dependsOnTypeVar(f.of());
            case Type.Array a -> dependsOnTypeVar(a.element());
            case Type.Union u -> u.members().stream().anyMatch(CollectionCallChecker::dependsOnTypeVar);
            case Type.Intersection i -> i.members().stream().anyMatch(CollectionCallChecker::dependsOnTypeVar);
            default -> false;
        };
    }

    private static boolean hasDefaultElement(Type elem) {
        return Type.isPrimitiveScalar(elem) || Type.containsNull(elem);
    }

    private Type checkArrayCall(List<Type> explicitArgs, BabbleParser.CallContext ctx, Type expected) {
        List<BabbleParser.CallParameterContext> args = ctx.callParameters().callParameter();
        Type explicitElem = explicitArgs != null && explicitArgs.size() == 1 ? explicitArgs.get(0) : null;
        Type expectedElem = explicitElem == null ? extractExpectedArrayElement(expected) : null;
        Type hintElem = explicitElem != null ? explicitElem : expectedElem;

        if (args.isEmpty()) {
            checker.warn("Missing parameter : size", ctx);
            return Type.ERROR;
        }
        if (args.size() > 1) {
            checker.warn("Too many parameters : 1 declared, " + args.size() + " given", ctx);
            synthAll(args);
            return Type.ERROR;
        }

        BabbleParser.CallParameterContext arg0 = args.get(0);
        String name0 = ScopeAnalysis.callParamName(arg0);
        if ("from".equals(name0)) {
            Type fromHint = hintElem != null ? new Type.JavaInstance(Iterable.class, List.of(hintElem)) : null;
            Type srcType = checker.check(arg0.expression(), fromHint);
            if (TypeChecker.isUnresolved(srcType)) {
                return hintElem != null ? new Type.Array(hintElem) : Type.ERROR;
            }
            Type unwrappedSrc = TypeAlgebra.unwrapFrozen(srcType);
            Type srcElem = unwrappedSrc instanceof Type.Array sa ? sa.element() : extractIterableElement(unwrappedSrc);
            if (srcElem == null) {
                checker.warn("Type error : expected iterable or array, got " + srcType, arg0.expression());
                return Type.ERROR;
            }
            Type elem = hintElem != null ? hintElem : srcElem;
            if (hintElem != null && !TypeChecker.isUnresolved(srcElem) && !checker.algebra().assignable(srcElem, hintElem)) {
                checker.warn("Type error : expected " + hintElem + " elements, got " + srcElem, arg0.expression());
                return Type.ERROR;
            }
            if (dependsOnTypeVar(elem)) {
                checker.warn("Generic array creation : " + new Type.Array(elem), ctx);
                return Type.ERROR;
            }
            return new Type.Array(elem);
        }

        if (name0 == null) {
            Type lenType = checker.check(arg0.expression(), Type.INT);
            checker.subsumes(arg0.expression(), lenType, Type.INT);
            Type elem = hintElem;
            if (elem == null) {
                checker.warn("Missing type : cannot infer type arguments for array", ctx);
                return Type.ERROR;
            }
            if (dependsOnTypeVar(elem)) {
                checker.warn("Generic array creation : " + new Type.Array(elem), ctx);
                return Type.ERROR;
            }
            if (!TypeChecker.isUnresolved(elem) && !hasDefaultElement(elem)) {
                checker.warn("No default element : " + new Type.Array(elem) + " has non-null elements", ctx);
                return Type.ERROR;
            }
            return new Type.Array(elem);
        }

        synthAll(args);
        checker.warn("No such parameter : " + name0, ctx);
        return Type.ERROR;
    }

    private boolean allAssignable(List<Type> actuals, Type target) {
        for (Type a : actuals) {
            if (TypeChecker.isUnresolved(a) || !checker.algebra().assignable(a, target)) {
                return false;
            }
        }
        return true;
    }

    private Type checkEntryCall(List<BabbleParser.CallParameterContext> args, List<Type> explicitArgs,
                                List<Type> expectedArgs, BabbleParser.CallContext ctx) {
        if (args.size() != 2) {
            checker.warn(args.size() < 2 ? "Missing parameter : " + (args.isEmpty() ? "key" : "value")
                    : "Too many parameters : 2 declared, " + args.size() + " given", ctx);
            synthAll(args);
            return Type.ERROR;
        }
        String n0 = ScopeAnalysis.callParamName(args.get(0));
        String n1 = ScopeAnalysis.callParamName(args.get(1));
        int keyIdx = 0;
        int valIdx = 1;
        if (n0 != null || n1 != null) {
            if ("value".equals(n0) && "key".equals(n1)) {
                keyIdx = 1;
                valIdx = 0;
            } else if ((n0 != null && !"key".equals(n0)) || (n1 != null && !"value".equals(n1))
                    || (n0 != null && n0.equals(n1))) {
                checker.warn("No such parameter : " + (n0 != null && !"key".equals(n0) && !"value".equals(n0) ? n0 : n1), ctx);
                synthAll(args);
                return Type.ERROR;
            }
        }
        Type expK = explicitArgs != null ? explicitArgs.get(0) : (expectedArgs != null ? expectedArgs.get(0) : null);
        Type expV = explicitArgs != null ? explicitArgs.get(1) : (expectedArgs != null ? expectedArgs.get(1) : null);
        Type k = checker.check(args.get(keyIdx).expression(), expK);
        Type v = checker.check(args.get(valIdx).expression(), expV);
        if (explicitArgs != null) {
            checker.subsumes(args.get(keyIdx).expression(), k, explicitArgs.get(0));
            checker.subsumes(args.get(valIdx).expression(), v, explicitArgs.get(1));
            return new Type.JavaInstance(Map.Entry.class, explicitArgs);
        }
        return new Type.JavaInstance(Map.Entry.class, List.of(k, v));
    }

    /**
     * Unified factory checker for sequence ({@code list}/{@code set}/{@code sortedset}) and
     * map ({@code map}/{@code sortedmap}) constructors: shares {@code capacity:}, {@code comparator:},
     * {@code from:}, positional-element, and {@code Comparable} validation.
     */
    private Type checkContainerFactoryCall(String kind, Class<?> javaClass, boolean isMap,
                                           List<BabbleParser.CallParameterContext> args,
                                           List<Type> explicitArgs, List<Type> expectedArgs,
                                           BabbleParser.CallContext ctx) {
        boolean isSorted = "sortedset".equals(kind) || "sortedmap".equals(kind);
        Type hint0 = explicitArgs != null ? explicitArgs.get(0) : (expectedArgs != null ? expectedArgs.get(0) : null);
        Type hint1 = isMap ? (explicitArgs != null ? explicitArgs.get(1) : (expectedArgs != null ? expectedArgs.get(1) : null)) : null;
        int count = args.size();
        String n0 = count > 0 ? ScopeAnalysis.callParamName(args.get(0)) : null;
        String n1 = count > 1 ? ScopeAnalysis.callParamName(args.get(1)) : null;

        Type primaryType = null;
        Type secondaryType = null;
        boolean hasComparator = false;
        boolean comparatorIsNull = false;

        if (count == 0) {
            primaryType = hint0;
            secondaryType = hint1;
            if (primaryType == null || (isMap && secondaryType == null)) {
                checker.warn("Missing type : cannot infer type arguments for " + kind, ctx);
                return Type.ERROR;
            }
        } else if (count == 1 && "capacity".equals(n0)) {
            if (isSorted) {
                checker.warn("No such parameter : capacity", ctx);
                synthAll(args);
                return Type.ERROR;
            }
            Type capType = checker.check(args.get(0).expression(), Type.INT);
            checker.subsumes(args.get(0).expression(), capType, Type.INT);
            primaryType = hint0;
            secondaryType = hint1;
            if (primaryType == null || (isMap && secondaryType == null)) {
                checker.warn("Missing type : cannot infer type arguments for " + kind, ctx);
                return Type.ERROR;
            }
        } else if (count == 1 && "comparator".equals(n0)) {
            if (!isSorted) {
                checker.warn("No such parameter : comparator", ctx);
                synthAll(args);
                return Type.ERROR;
            }
            hasComparator = true;
            primaryType = checkComparatorArg(args.get(0).expression(), hint0, kind, ctx);
            if (primaryType == Type.ERROR) {
                return Type.ERROR;
            }
            if (isMap) {
                secondaryType = hint1;
                if (secondaryType == null) {
                    checker.warn("Missing type : cannot infer type arguments for " + kind, ctx);
                    return Type.ERROR;
                }
            }
            comparatorIsNull = checker.synth(args.get(0).expression()) == Type.NULL;
        } else if ((count == 1 && "from".equals(n0))
                || (count == 2 && isSorted && (("from".equals(n0) && "comparator".equals(n1)) || ("comparator".equals(n0) && "from".equals(n1))))) {
            int fromIdx = "from".equals(n0) ? 0 : 1;
            Type expectedSource = isMap
                    ? ((hint0 != null && hint1 != null) ? new Type.JavaInstance(Map.class, List.of(hint0, hint1)) : null)
                    : (hint0 != null ? new Type.JavaInstance(Iterable.class, List.of(hint0)) : null);
            Type fromType = checker.check(args.get(fromIdx).expression(), expectedSource);
            if (isMap) {
                List<Type> fromKV = extractMapEntries(fromType);
                primaryType = hint0 != null ? hint0 : (fromKV != null ? fromKV.get(0) : null);
                secondaryType = hint1 != null ? hint1 : (fromKV != null ? fromKV.get(1) : null);
            } else {
                primaryType = hint0 != null ? hint0 : extractIterableElement(fromType);
            }
            if (primaryType == null || (isMap && secondaryType == null)) {
                if (!TypeChecker.isUnresolved(fromType)) {
                    checker.warn("Type error : expected " + (isMap ? "map" : "java.lang.Iterable") + ", got " + fromType,
                            args.get(fromIdx).expression());
                }
                if (count == 2) {
                    checker.synth(args.get(1 - fromIdx).expression());
                }
                return Type.ERROR;
            }
            Type borrowTarget = isMap
                    ? new Type.JavaInstance(Map.class, List.of(primaryType, secondaryType))
                    : new Type.JavaInstance(Iterable.class, List.of(primaryType));
            checker.subsumesBorrow(args.get(fromIdx).expression(), fromType, borrowTarget);
            if (count == 2) {
                int compIdx = 1 - fromIdx;
                hasComparator = true;
                Type compPattern = Type.nullable(new Type.JavaInstance(Comparator.class, List.of(primaryType)));
                Type compType = checker.check(args.get(compIdx).expression(), compPattern);
                checker.subsumes(args.get(compIdx).expression(), compType, compPattern);
                comparatorIsNull = compType == Type.NULL;
            }
        } else {
            for (int i = 0; i < count; i++) {
                if (ScopeAnalysis.callParamName(args.get(i)) != null) {
                    checker.warn("No such parameter : " + ScopeAnalysis.callParamName(args.get(i)), ctx);
                    synthAll(args);
                    return Type.ERROR;
                }
            }
            Type[] resolved = isMap
                    ? checkMapPositionalEntries(args, explicitArgs, hint0, hint1)
                    : checkSeqPositionalElements(args, explicitArgs, hint0);
            if (resolved == null) {
                return Type.ERROR;
            }
            primaryType = resolved[0];
            secondaryType = isMap ? resolved[1] : null;
        }

        if (isSorted && (!hasComparator || comparatorIsNull) && !TypeChecker.isUnresolved(primaryType)) {
            if (!isNaturallyComparable(primaryType)) {
                checker.warn("Type error : " + primaryType + " is not Comparable (pass comparator: ...)", ctx);
                return Type.ERROR;
            }
        }
        return new Type.JavaInstance(javaClass, isMap ? List.of(primaryType, secondaryType) : List.of(primaryType));
    }

    private Type[] checkSeqPositionalElements(List<BabbleParser.CallParameterContext> args, List<Type> explicitArgs, Type hintT) {
        int count = args.size();
        List<BabbleParser.ExpressionContext> exprs = new ArrayList<>(count);
        List<Type> actuals = new ArrayList<>(count);
        for (BabbleParser.CallParameterContext arg : args) {
            exprs.add(arg.expression());
            actuals.add(checker.check(arg.expression(), hintT));
        }
        Type elemType;
        if (explicitArgs != null) {
            elemType = explicitArgs.get(0);
            for (int i = 0; i < count; i++) {
                checker.subsumes(exprs.get(i), actuals.get(i), elemType);
            }
        } else {
            elemType = actuals.get(0);
            for (int i = 1; i < count; i++) {
                elemType = checker.algebra().join(elemType, actuals.get(i));
                if (elemType == null) {
                    return null;
                }
            }
            for (int i = 0; i < count; i++) {
                checker.coerce(exprs.get(i), actuals.get(i), elemType);
            }
        }
        return new Type[]{elemType};
    }

    private Type[] checkMapPositionalEntries(List<BabbleParser.CallParameterContext> args, List<Type> explicitArgs,
                                             Type hintK, Type hintV) {
        int count = args.size();
        Type entryHint = (hintK != null && hintV != null) ? new Type.JavaInstance(Map.Entry.class, List.of(hintK, hintV)) : null;
        List<BabbleParser.ExpressionContext> exprs = new ArrayList<>(count);
        List<Type> actuals = new ArrayList<>(count);
        for (BabbleParser.CallParameterContext arg : args) {
            exprs.add(arg.expression());
            actuals.add(checker.check(arg.expression(), entryHint));
        }
        if (explicitArgs != null) {
            Type expectedEntry = new Type.JavaInstance(Map.Entry.class, explicitArgs);
            for (int i = 0; i < count; i++) {
                checker.subsumes(exprs.get(i), actuals.get(i), expectedEntry);
            }
            return new Type[]{explicitArgs.get(0), explicitArgs.get(1)};
        }
        Type keyType = null;
        Type valType = null;
        for (int i = 0; i < count; i++) {
            Type argType = actuals.get(i);
            if (TypeChecker.isUnresolved(argType)) {
                return null;
            }
            Type projected = checker.algebra().projectToJava(argType, Map.Entry.class);
            if (!(projected instanceof Type.JavaInstance entryInst) || entryInst.typeArguments().size() != 2) {
                checker.warn("Type error : expected entry, got " + argType, exprs.get(i));
                return null;
            }
            Type k = entryInst.typeArguments().get(0);
            Type v = entryInst.typeArguments().get(1);
            if (keyType == null) {
                keyType = k;
                valType = v;
            } else {
                keyType = checker.algebra().join(keyType, k);
                valType = checker.algebra().join(valType, v);
                if (keyType == null || valType == null) {
                    return null;
                }
            }
        }
        return new Type[]{keyType, valType};
    }

    private Type checkComparatorArg(BabbleParser.ExpressionContext compExpr, Type hintT, String kind,
                                    BabbleParser.CallContext ctx) {
        if (hintT != null) {
            Type compPattern = new Type.JavaInstance(Comparator.class, List.of(hintT));
            Type compType = checker.check(compExpr, compPattern);
            checker.subsumes(compExpr, compType, compPattern);
            return hintT;
        }
        Type.TypeVar tv = new Type.TypeVar(null, "T", 0);
        Set<Type.TypeVar> solving = Set.of(tv);
        Type compPattern = new Type.JavaInstance(Comparator.class, List.of(tv));
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Type actualComp = checker.check(compExpr, compPattern);
        if (TypeChecker.isUnresolved(actualComp)) {
            return Type.ERROR;
        }
        checker.algebra().relate(actualComp, compPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store);
        Type solved = store.solution(tv);
        if (solved == null) {
            checker.warn("Missing type : cannot infer type arguments for " + kind, ctx);
            return Type.ERROR;
        }
        return solved;
    }

    Type extractIterableElement(Type type) {
        if (TypeChecker.isUnresolved(type)) {
            return null;
        }
        Type projected = checker.algebra().projectToJava(type, Iterable.class);
        if (projected instanceof Type.JavaInstance ji && ji.typeArguments().size() == 1) {
            return ji.typeArguments().get(0);
        }
        return null;
    }

    private List<Type> extractMapEntries(Type type) {
        if (TypeChecker.isUnresolved(type)) {
            return null;
        }
        Type projected = checker.algebra().projectToJava(type, Map.class);
        if (projected instanceof Type.JavaInstance ji && ji.typeArguments().size() == 2) {
            return ji.typeArguments();
        }
        return null;
    }

    private boolean isNaturallyComparable(Type type) {
        if (Type.containsNull(type)) {
            return false;
        }
        if (type == Type.BYTE || type == Type.SHORT || type == Type.INT || type == Type.LONG
                || type == Type.FLOAT || type == Type.DOUBLE || type == Type.BOOL
                || type == Type.CHAR || type == Type.STRING) {
            return true;
        }
        if (type instanceof Type.JavaInstance ji) {
            return Comparable.class.isAssignableFrom(ji.type());
        }
        if (type instanceof Type.Instance inst) {
            return checker.algebra().implementsJava(inst.node(), Comparable.class);
        }
        return false;
    }
}
