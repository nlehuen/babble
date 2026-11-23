package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java reflection method, constructor, generic SAM, and varargs/spread call solver
 * extracted from {@link CallSolver} (BIP027-P1).
 */
final class JavaCallSolver {
    record JavaCallMatch<E extends Executable>(List<E> applicable, boolean varArgs) {
    }

    private final TypeChecker checker;
    private final CallSolver solver;

    JavaCallSolver(TypeChecker checker, CallSolver solver) {
        this.checker = checker;
        this.solver = solver;
    }

    Type javaGenericSamCall(Class<?> java, BabbleParser.ExpressionContext argExpr, BabbleParser.CallContext ctx, Type expected) {
        TypeVariable<?>[] typeParams = java.getTypeParameters();
        if (typeParams.length == 0) {
            Type expectedSam = new Type.JavaInstance(java);
            Type actualArg = checker.check(argExpr, expectedSam);
            if (TypeAlgebra.isCallable(actualArg)) {
                checker.coerce(argExpr, actualArg, expectedSam);
                return expectedSam;
            }
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx, expected, true);
        }
        List<Type.TypeVar> solvingList = new ArrayList<>(typeParams.length);
        for (int i = 0; i < typeParams.length; i++) {
            solvingList.add(new Type.TypeVar(null, typeParams[i].getName(), i));
        }
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Type.JavaInstance samPattern = new Type.JavaInstance(java, List.copyOf(solvingList));
        Type.Function samFn = JavaTypeBridge.samFunctionType(samPattern, checker.effectChecker());
        Map<Type.TypeVar, Type> retHints = solver.collectReturnHints(samPattern, expected, solving);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Type expectedForArg = checker.algebra().substitute(samPattern, CallSolver.placeholderEnvWithHints(solving, store, retHints));
        Type actualArg = checker.check(argExpr, expectedForArg);
        if (TypeChecker.isUnresolved(actualArg)) {
            return Type.ERROR;
        }
        if (!TypeAlgebra.isCallable(actualArg)) {
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx, expected, true);
        }
        if (actualArg instanceof Type.Function actualFn && samFn != null) {
            checker.algebra().relate(actualFn, samFn, TypeAlgebra.RelateMode.ASSIGNABLE, store);
        }
        Map<Type.TypeVar, Type> inferred = solver.solveAll(
                solvingList, store, retHints, samFn != null ? List.of(samFn) : List.of(), List.of(argExpr));
        if (inferred == null) {
            checker.warn("Missing type : cannot infer type arguments for " + java.getCanonicalName(), ctx);
            return Type.ERROR;
        }
        List<Type> solvedArgs = new ArrayList<>(typeParams.length);
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        for (int i = 0; i < typeParams.length; i++) {
            solvedArgs.add(inferred.get(solvingList.get(i)));
            env.put(typeParams[i], inferred.get(solvingList.get(i)));
        }
        for (int i = 0; i < typeParams.length; i++) {
            if (!JavaTypeBridge.satisfiesJavaBounds(solvedArgs.get(i), typeParams[i], env, checker.algebra())) {
                checker.warn("Type error : type argument " + solvedArgs.get(i) + " does not satisfy bound "
                        + typeParams[i].getName() + " of " + java.getCanonicalName(), argExpr);
                return Type.ERROR;
            }
        }
        JavaTypeBridge.BadJavaArrayTypeArg badArrSam = JavaTypeBridge.checkJavaClassArrayTypeArgs(java, solvedArgs, checker.algebra());
        if (badArrSam != null) {
            checker.warn(badArrSam.message(), argExpr);
            return Type.ERROR;
        }
        Type.JavaInstance result = new Type.JavaInstance(java, solvedArgs);
        if (actualArg instanceof Type.Function actualFn) {
            checker.subsumes(argExpr, null, actualFn, result);
        }
        checker.coerceSam(argExpr, actualArg, result);
        return result;
    }

    Type javaConstructorCall(Class<?> java, List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallContext ctx, Type expected, boolean recordTarget) {
        List<Type> argumentTypes = new ArrayList<>();
        String description = java.getCanonicalName();
        JavaCallMatch<Executable> match = javaCall(Arrays.asList(java.getConstructors()), description, Map.of(), arguments, ctx, expected, argumentTypes);
        List<Executable> matching = match.applicable();
        boolean varArgs = match.varArgs();
        TypeVariable<?>[] classParams = java.getTypeParameters();
        Executable winner = matching.isEmpty() ? null : matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching, varArgs);
        if (classParams.length == 0) {
            if (winner != null) {
                checkJavaArgumentWidening(winner, Map.of(), arguments, argumentTypes, varArgs, ctx);
                if (recordTarget) {
                    solver.recordConstructor(winner, varArgs, ctx);
                }
            }
            return new Type.JavaInstance(java);
        }
        if (winner == null) {
            return Type.ERROR;
        }
        List<TypeVariable<?>> variables = new ArrayList<>(Arrays.asList(classParams));
        variables.addAll(Arrays.asList(winner.getTypeParameters()));
        Map<TypeVariable<?>, Type> solved = solveJava(winner, variables, Map.of(),
                synthetic -> new Type.JavaInstance(java, Arrays.stream(classParams).map(tp -> (Type) synthetic.get(tp)).toList()),
                arguments, argumentTypes, description, ctx, expected, varArgs);
        if (solved == null) {
            return Type.ERROR;
        }
        if (recordTarget) {
            solver.recordConstructor(winner, varArgs, ctx);
        }
        return new Type.JavaInstance(java, Arrays.stream(classParams).map(solved::get).toList());
    }

    Type javaMethodCall(List<Method> candidates, String description, Map<TypeVariable<?>, Type> receiverEnv,
                        List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx,
                        Type expected, boolean recordTarget) {
        List<Type> argumentTypes = new ArrayList<>();
        JavaCallMatch<Method> match = javaCall(candidates, description, receiverEnv, arguments, ctx, expected, argumentTypes);
        List<Method> applicable = match.applicable();
        boolean varArgs = match.varArgs();
        if (applicable.isEmpty()) {
            return Type.ERROR;
        }
        Method best = applicable.size() == 1 ? applicable.get(0) : null;
        if (best == null && argumentTypes.stream().noneMatch(TypeChecker::isUnresolved)) {
            best = JavaTypeBridge.mostSpecific(applicable, varArgs);
        }
        if (best == null) {
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1 && applicable.stream().allMatch(m -> m.getTypeParameters().length == 0)) {
                return JavaTypeBridge.javaMethodResult(applicable.get(0), receiverEnv, checker.algebra());
            }
            return Type.ERROR;
        }
        Map<TypeVariable<?>, Type> env = receiverEnv;
        if (best.getTypeParameters().length == 0) {
            checkJavaArgumentWidening(best, receiverEnv, arguments, argumentTypes, varArgs, ctx);
        } else {
            Method generic = best;
            env = solveJava(best, Arrays.asList(best.getTypeParameters()), receiverEnv,
                    synthetic -> {
                        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
                        patternEnv.putAll(synthetic);
                        return JavaTypeBridge.fromJavaType(generic.getGenericReturnType(), generic.getAnnotatedReturnType(),
                                patternEnv, false, false, JavaNullability.returnNullness(generic), JavaNullability.isNullMarked(generic));
                    },
                    arguments, argumentTypes, description, ctx, expected, varArgs);
            if (env == null) {
                return Type.ERROR;
            }
        }
        Type result = JavaTypeBridge.javaMethodResult(best, env, checker.algebra());
        if (!best.getReturnType().isPrimitive() && !Type.containsNull(result)) {
            String desc = LoweringAnalyzer.descriptor(result);
            if (desc != null) {
                checker.callChecks().put(ctx, desc);
            }
        }
        if (recordTarget) {
            solver.recordMethod(best, varArgs, ctx);
        }
        return result;
    }

    /**
     * Solves the type {@code variables} of the winning Java {@code executable} (its class's and
     * its own) from the argument types and the expected result ({@code resultPattern} over their
     * synthetic variables), checks their bounds, and records the {@code int -> double} argument
     * widenings ; the solved environment (with {@code knownEnv}), or null after reporting why not.
     */
    private Map<TypeVariable<?>, Type> solveJava(Executable executable, List<TypeVariable<?>> variables,
                                                 Map<TypeVariable<?>, Type> knownEnv,
                                                 java.util.function.Function<Map<TypeVariable<?>, Type.TypeVar>, Type> resultPattern,
                                                 List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes,
                                                 String description, BabbleParser.CallContext ctx, Type expected, boolean varArgs) {
        Map<TypeVariable<?>, Type.TypeVar> synthetic = new HashMap<>();
        for (TypeVariable<?> tp : variables) {
            synthetic.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), synthetic.size()));
        }
        List<Type.TypeVar> solvingList = List.copyOf(synthetic.values());
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(knownEnv);
        patternEnv.putAll(synthetic);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        int fixed = executable.getParameterCount() - 1;
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        boolean spreadIntoVarArgs = executable.isVarArgs() && !varArgs
                && arguments.size() == executable.getParameterCount()
                && !arguments.isEmpty() && ScopeAnalysis.isSpread(arguments.get(arguments.size() - 1));
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = varArgs
                    ? JavaTypeBridge.javaVarArgParamType(executable, i, patternEnv)
                    : JavaTypeBridge.javaExecutableParamType(executable, i, patternEnv);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            int pIdx = varArgs ? Math.min(i, fixed) : i;
            if (ScopeAnalysis.isSpread(arguments.get(i))) {
                Type sourceElem = solver.extractSequenceElement(argumentTypes.get(i));
                Type patElem = solver.sequenceTargetElement(paramPat);
                if (sourceElem != null && patElem != null) {
                    boolean isArr = executable.getParameterTypes()[pIdx].isArray();
                    checker.algebra().relate(sourceElem, patElem,
                            isArr ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE,
                            store, false, false);
                } else {
                    checker.algebra().relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store,
                            isJavaParamBorrow(executable, pIdx), !isJavaParamMut(executable, pIdx));
                }
            } else {
                checker.algebra().relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store,
                        isJavaParamBorrow(executable, pIdx), !isJavaParamMut(executable, pIdx));
            }
        }
        Map<Type.TypeVar, Type> retHints = solver.collectReturnHints(resultPattern.apply(synthetic), expected, solving);
        Map<Type.TypeVar, Type> inferred = solver.solveAll(solvingList, store, retHints, paramPatterns, argExprs);
        if (inferred == null) {
            if (argumentTypes.stream().noneMatch(TypeChecker::isUnresolved)) {
                checker.warn("Missing type : cannot infer type arguments for " + description, ctx);
            }
            return null;
        }
        Map<TypeVariable<?>, Type> solved = new HashMap<>(knownEnv);
        synthetic.forEach((tp, tv) -> solved.put(tp, inferred.get(tv)));
        for (TypeVariable<?> tp : variables) {
            Type resolved = solved.get(tp);
            if (resolved != null && TypeAlgebra.mayContainBabbleCallable(resolved)) {
                Type bound = JavaTypeBridge.translateTypeVarBounds(tp, solved);
                Type targetBound = Type.containsNull(resolved) ? bound : Type.withoutNull(bound);
                JavaTypeBridge.JavaSamResolution samRes = JavaTypeBridge.resolveJavaSam(targetBound);
                if (samRes instanceof JavaTypeBridge.JavaSamResolution.Valid) {
                    if (checker.algebra().assignable(resolved, targetBound)) {
                        solved.put(tp, targetBound);
                        resolved = targetBound;
                    } else if (TypeAlgebra.isCallable(resolved)) {
                        checker.reportTypeMismatch(resolved, targetBound, ctx);
                        return null;
                    }
                } else if (samRes instanceof JavaTypeBridge.JavaSamResolution.NotFunctional
                        && TypeAlgebra.isCallable(resolved)) {
                    checker.reportTypeMismatch(resolved, targetBound, ctx);
                    return null;
                }
            }
            if (!JavaTypeBridge.satisfiesJavaBounds(resolved, tp, solved, checker.algebra())) {
                checker.warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + description, ctx);
                return null;
            }
        }
        JavaTypeBridge.BadJavaArrayTypeArg badArrJava = JavaTypeBridge.checkJavaExecutableArrayTypeArgs(
                executable, variables, solved, checker.algebra(), varArgs || spreadIntoVarArgs);
        if (badArrJava != null) {
            checker.warn(badArrJava.message(), ctx);
            return null;
        }
        checkJavaArgumentWidening(executable, solved, arguments, argumentTypes, varArgs, ctx);
        return solved;
    }

    <E extends Executable> JavaCallMatch<E> javaCall(List<E> candidates, String description,
                                                     Map<TypeVariable<?>, Type> receiverEnv,
                                                     List<BabbleParser.CallParameterContext> arguments,
                                                     ParserRuleContext ctx, Type expected,
                                                     List<Type> outArgumentTypes) {
        boolean anySpread = false;
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (ScopeAnalysis.isSpread(arg)) {
                anySpread = true;
                break;
            }
        }
        List<E> fixedCandidates = new ArrayList<>();
        List<E> varArgCandidates = new ArrayList<>();
        for (E candidate : candidates) {
            if (candidate.getParameterCount() == arguments.size()) {
                fixedCandidates.add(candidate);
            }
            if (!anySpread && candidate.isVarArgs() && candidate.getParameterCount() > 0
                    && arguments.size() >= candidate.getParameterCount() - 1) {
                varArgCandidates.add(candidate);
            }
        }
        boolean varArgs = !anySpread && fixedCandidates.isEmpty() && !varArgCandidates.isEmpty();
        List<E> matching = new ArrayList<>(varArgs ? varArgCandidates : fixedCandidates);
        Type[] argTypes = new Type[arguments.size()];
        boolean[] deferred = new boolean[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            BabbleParser.CallParameterContext argCtx = arguments.get(i);
            BabbleParser.ExpressionContext argument = argCtx.expression();
            boolean spread = ScopeAnalysis.isSpread(argCtx);
            if (spread) {
                Type actual = checker.check(argument, null);
                argTypes[i] = actual;
                final int index = i;
                matching.removeIf(candidate -> !applicableJavaSpread(candidate, index, actual, receiverEnv,
                        isJavaParamBorrow(candidate, index), isJavaParamMut(candidate, index)));
                continue;
            }
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type argExpected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, receiverEnv, varArgs);
            if (solver.needsDeferredLambdaTyping(argument, argExpected)) {
                deferred[i] = true;
                continue;
            }
            Type actual = checker.check(argument, argExpected);
            argTypes[i] = actual;
            final int index = i;
            final boolean va = varArgs;
            matching.removeIf(candidate -> {
                int pIdx = va ? Math.min(index, candidate.getParameterCount() - 1) : index;
                return !JavaTypeBridge.applicableGeneric(candidate, index, actual, receiverEnv,
                        checker.algebra(), isJavaParamBorrow(candidate, pIdx), isJavaParamMut(candidate, pIdx), va);
            });
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!deferred[i]) {
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Map<TypeVariable<?>, Type> refinedEnv = refineJavaEnvFromArgs(matching, receiverEnv, arguments, argTypes, deferred, ctx, expected, varArgs);
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type argExpected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, refinedEnv, varArgs);
            Type actual = checker.check(argument, argExpected);
            argTypes[i] = actual;
            final int index = i;
            final boolean va = varArgs;
            matching.removeIf(candidate -> {
                int pIdx = va ? Math.min(index, candidate.getParameterCount() - 1) : index;
                return !JavaTypeBridge.applicableGeneric(candidate, index, actual, refinedEnv,
                        checker.algebra(), isJavaParamBorrow(candidate, pIdx), isJavaParamMut(candidate, pIdx), va);
            });
        }
        // Phase 3 fallback (BIP021-D8): if fixed-arity phase 1-2 found nothing, try variable-arity candidates.
        if (!anySpread && !varArgs && matching.isEmpty() && !varArgCandidates.isEmpty()) {
            List<E> vaMatching = new ArrayList<>(varArgCandidates);
            for (int i = 0; i < argTypes.length; i++) {
                final int index = i;
                final Type actual = argTypes[i];
                vaMatching.removeIf(candidate -> {
                    int pIdx = Math.min(index, candidate.getParameterCount() - 1);
                    return !JavaTypeBridge.applicableGeneric(candidate, index, actual, receiverEnv,
                            checker.algebra(), isJavaParamBorrow(candidate, pIdx), isJavaParamMut(candidate, pIdx), true);
                });
            }
            if (!vaMatching.isEmpty()) {
                matching = vaMatching;
                varArgs = true;
            }
        }
        outArgumentTypes.clear();
        for (Type t : argTypes) {
            outArgumentTypes.add(t);
        }
        if (matching.isEmpty() && !candidates.isEmpty()) {
            String nonSeqParam = anySpread ? findNonSequenceJavaSpreadParam(fixedCandidates, arguments, argTypes) : null;
            if (nonSeqParam != null) {
                checker.warn("Spread into a parameter that takes no sequence : " + nonSeqParam, ctx);
            } else {
                checker.warn("No applicable overload of " + description + " for "
                        + formatJavaArgTypes(arguments, outArgumentTypes), ctx);
            }
        } else if (matching.size() > 1
                && outArgumentTypes.stream().noneMatch(TypeChecker::isUnresolved)
                && JavaTypeBridge.mostSpecific(matching, varArgs) == null) {
            checker.warn("Ambiguous call to " + description + " for "
                    + formatJavaArgTypes(arguments, outArgumentTypes), ctx);
        }
        return new JavaCallMatch<>(matching, varArgs);
    }

    private static String formatJavaArgTypes(List<BabbleParser.CallParameterContext> arguments, List<Type> argTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < argTypes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i < arguments.size() && ScopeAnalysis.isSpread(arguments.get(i))) {
                sb.append("...");
            }
            sb.append(argTypes.get(i));
        }
        sb.append(')');
        return sb.toString();
    }

    private <E extends Executable> String findNonSequenceJavaSpreadParam(List<E> fixedCandidates,
                                                                         List<BabbleParser.CallParameterContext> arguments,
                                                                         Type[] argTypes) {
        if (fixedCandidates.isEmpty()) {
            return null;
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!ScopeAnalysis.isSpread(arguments.get(i)) || solver.extractSequenceElement(argTypes[i]) == null) {
                continue;
            }
            boolean anySeq = false;
            for (E c : fixedCandidates) {
                Class<?> raw = c.getParameterTypes()[i];
                if (raw.isArray() || Iterable.class.isAssignableFrom(raw)) {
                    anySeq = true;
                    break;
                }
            }
            if (!anySeq) {
                return fixedCandidates.get(0).getParameters()[i].getName();
            }
        }
        return null;
    }

    private boolean applicableJavaSpread(Executable candidate, int index, Type actual,
                                         Map<TypeVariable<?>, Type> receiverEnv,
                                         boolean borrow, boolean mut) {
        if (TypeChecker.isUnresolved(actual)) {
            return true;
        }
        Type sourceElem = solver.extractSequenceElement(actual);
        if (sourceElem == null) {
            return false;
        }
        Class<?> rawParam = candidate.getParameterTypes()[index];
        if (!rawParam.isArray() && !Iterable.class.isAssignableFrom(rawParam)) {
            return false;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = JavaTypeBridge.candidateSolvingVars(candidate);
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
        patternEnv.putAll(tvToSyn);
        Type paramPat = JavaTypeBridge.javaExecutableParamType(candidate, index, patternEnv);
        if (!paramPat.anyMatch(t -> t instanceof Type.TypeVar tv && tv.owner() == null)) {
            java.lang.reflect.Type genericParam = candidate.getGenericParameterTypes()[index];
            return classifyJavaSpread(actual, paramPat, rawParam, genericParam, borrow, mut) != null;
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Type patElem = solver.sequenceTargetElement(paramPat);
        if (patElem != null) {
            return checker.algebra().relate(sourceElem, patElem,
                    rawParam.isArray() ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE,
                    store, false, false);
        }
        return checker.algebra().relate(actual, paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrow, !mut);
    }

    private LoweringTargets.SpreadAdapt classifyJavaSpread(Type sourceType, Type paramType, Class<?> rawParam,
                                                           java.lang.reflect.Type genericParam,
                                                           boolean borrow, boolean mut) {
        if (TypeChecker.isUnresolved(sourceType) || TypeChecker.isUnresolved(paramType)) {
            return null;
        }
        if (rawParam.isArray()) {
            Type sourceElem = solver.extractSequenceElement(sourceType);
            if (sourceElem == null) {
                return null;
            }
            Type baseParam = TypeAlgebra.unwrapFrozen(Type.withoutNull(paramType));
            Type targetElem = baseParam instanceof Type.Array arr ? arr.element() : JavaTypeBridge.fromJava(rawParam.getComponentType());
            if (!checker.algebra().relate(sourceElem, targetElem, TypeAlgebra.RelateMode.ASSIGNABLE, null, false, false)) {
                return null;
            }
            LoweringTargets.JavaSamTarget elemSam = CallSolver.samTargetForElement(targetElem);
            if (elemSam == null && genericParam != null) {
                java.lang.reflect.Type compGen = null;
                if (genericParam instanceof GenericArrayType gat) {
                    compGen = gat.getGenericComponentType();
                } else if (genericParam instanceof Class<?> c && c.isArray()) {
                    compGen = c.getComponentType();
                }
                if (compGen instanceof TypeVariable<?> tv) {
                    elemSam = CallSolver.samTargetForElement(JavaTypeBridge.translateTypeVarBounds(tv, Map.of()));
                } else if (compGen != null) {
                    elemSam = CallSolver.samTargetForElement(JavaTypeBridge.fromJavaType(compGen, Map.of(), false, true));
                }
            }
            boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(sourceElem);
            if (!needsSamAdapt && !(sourceType instanceof Type.Frozen) && sourceType instanceof Type.Array srcArr) {
                Class<?> srcCls = JavaTypeBridge.checkedArrayClass(srcArr);
                if (srcCls != null && rawParam.isAssignableFrom(srcCls)) {
                    return LoweringTargets.SpreadAdapt.AS_IS;
                }
            }
            return LoweringTargets.SpreadAdapt.toArray(rawParam.getComponentType(), needsSamAdapt ? elemSam : null);
        }
        return solver.classifySpread(sourceType, paramType, borrow, mut, borrow && !mut);
    }

    private <E extends Executable> Map<TypeVariable<?>, Type> refineJavaEnvFromArgs(
            List<E> matching, Map<TypeVariable<?>, Type> receiverEnv,
            List<BabbleParser.CallParameterContext> arguments, Type[] argTypes, boolean[] deferred,
            ParserRuleContext ctx, Type expected, boolean varArgs) {
        if (matching.size() != 1) {
            return receiverEnv;
        }
        E cand = matching.get(0);
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = JavaTypeBridge.candidateSolvingVars(cand);
        if (tvToSyn.isEmpty()) {
            return receiverEnv;
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
        patternEnv.putAll(tvToSyn);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        int fixed = cand.getParameterCount() - 1;
        for (int i = 0; i < argTypes.length; i++) {
            if (!deferred[i] && argTypes[i] != null) {
                Type paramPat = varArgs
                        ? JavaTypeBridge.javaVarArgParamType(cand, i, patternEnv)
                        : JavaTypeBridge.javaExecutableParamType(cand, i, patternEnv);
                int pIdx = varArgs ? Math.min(i, fixed) : i;
                if (ScopeAnalysis.isSpread(arguments.get(i))) {
                    Type sourceElem = solver.extractSequenceElement(argTypes[i]);
                    Type patElem = solver.sequenceTargetElement(paramPat);
                    if (sourceElem != null && patElem != null) {
                        boolean isArr = cand.getParameterTypes()[pIdx].isArray();
                        checker.algebra().relate(sourceElem, patElem,
                                isArr ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE,
                                store, false, false);
                        continue;
                    }
                }
                checker.algebra().relate(argTypes[i], paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store,
                        isJavaParamBorrow(cand, pIdx), !isJavaParamMut(cand, pIdx));
            }
        }
        Type ctxExpected = expected;
        Map<Type.TypeVar, Type> retHints = Map.of();
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            Type retPat;
            if (cand instanceof Method m) {
                retPat = JavaTypeBridge.fromJavaType(m.getGenericReturnType(), m.getAnnotatedReturnType(),
                        patternEnv, false, false, JavaNullability.returnNullness(m), JavaNullability.isNullMarked(m));
            } else {
                Class<?> cls = cand.getDeclaringClass();
                List<Type> clsVars = Arrays.stream(cls.getTypeParameters())
                        .map(tp -> (Type) tvToSyn.get(tp)).toList();
                retPat = new Type.JavaInstance(cls, clsVars);
            }
            retHints = solver.collectReturnHints(retPat, ctxExpected, solving);
        }
        Map<TypeVariable<?>, Type> refined = new HashMap<>(receiverEnv);
        Map<Type.TypeVar, Type> finalHints = retHints;
        tvToSyn.forEach((tv, syn) -> {
            Type solved = store.solution(syn);
            if (solved == null) {
                solved = finalHints.get(syn);
            }
            if (solved != null) {
                refined.put(tv, solved);
            }
        });
        return refined;
    }

    void checkJavaArgumentWidening(Executable candidate, Map<TypeVariable<?>, Type> env,
                                   List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes,
                                   boolean varArgs, ParserRuleContext ctx) {
        LoweringTargets.SpreadAdapt[] adapts = null;
        for (int i = 0; i < arguments.size(); i++) {
            if (i < argumentTypes.size()) {
                BabbleParser.CallParameterContext argCtx = arguments.get(i);
                BabbleParser.ExpressionContext argExpr = argCtx.expression();
                Type argType = argumentTypes.get(i);
                Type paramType = varArgs
                        ? JavaTypeBridge.javaVarArgParamType(candidate, i, env)
                        : JavaTypeBridge.javaExecutableParamType(candidate, i, env);
                if (ScopeAnalysis.isSpread(argCtx)) {
                    int pIdx = varArgs ? Math.min(i, candidate.getParameterCount() - 1) : i;
                    LoweringTargets.SpreadAdapt adapt = classifyJavaSpread(argType, paramType,
                            candidate.getParameterTypes()[pIdx], candidate.getGenericParameterTypes()[pIdx],
                            isJavaParamBorrow(candidate, pIdx), isJavaParamMut(candidate, pIdx));
                    if (adapt != null) {
                        if (adapts == null) {
                            adapts = new LoweringTargets.SpreadAdapt[arguments.size()];
                        }
                        adapts[i] = adapt;
                    }
                    continue;
                }
                if (!Type.containsNull(paramType) && Type.containsNull(argType) && !TypeChecker.isUnresolved(argType)) {
                    checker.subsumes(argExpr, argType, paramType);
                }
                checker.coerce(argExpr, argType, paramType);
            }
        }
        if (adapts != null) {
            solver.recordSpreadAdapts(ctx, adapts);
        }
    }

    List<Method> matchingGenericJavaMethods(List<Method> all, Map<TypeVariable<?>, Type> receiverEnv,
                                            List<Type> explicitTypeArgs) {
        List<Method> candidates = new ArrayList<>();
        for (Method m : all) {
            TypeVariable<?>[] mParams = m.getTypeParameters();
            if (mParams.length != explicitTypeArgs.size()) {
                continue;
            }
            Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
            for (int i = 0; i < mParams.length; i++) {
                env.put(mParams[i], explicitTypeArgs.get(i));
            }
            boolean boundsOk = true;
            for (int i = 0; i < mParams.length; i++) {
                if (!JavaTypeBridge.satisfiesJavaBounds(explicitTypeArgs.get(i), mParams[i], env, checker.algebra())) {
                    boundsOk = false;
                    break;
                }
            }
            if (boundsOk && JavaTypeBridge.checkJavaExecutableArrayTypeArgs(m, Arrays.asList(mParams), env, checker.algebra()) == null) {
                candidates.add(m);
            }
        }
        return candidates;
    }

    JavaTypeBridge.BadJavaArrayTypeArg firstJavaMethodArrayError(
            List<Method> all, Map<TypeVariable<?>, Type> receiverEnv, List<Type> explicitTypeArgs) {
        for (Method m : all) {
            TypeVariable<?>[] mParams = m.getTypeParameters();
            if (mParams.length != explicitTypeArgs.size()) {
                continue;
            }
            Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
            for (int i = 0; i < mParams.length; i++) {
                env.put(mParams[i], explicitTypeArgs.get(i));
            }
            JavaTypeBridge.BadJavaArrayTypeArg bad = JavaTypeBridge.checkJavaExecutableArrayTypeArgs(
                    m, Arrays.asList(mParams), env, checker.algebra());
            if (bad != null) {
                return bad;
            }
        }
        return null;
    }

    private EffectChecker.JavaCallProfile javaProfile(Executable exec) {
        EffectChecker ec = checker.effectChecker();
        if (ec == null || exec == null) {
            return null;
        }
        if (exec instanceof java.lang.reflect.Constructor<?> ctor) {
            return ec.javaResolver().resolveJavaConstructorProfile(ctor.getDeclaringClass(), ctor);
        }
        if (exec instanceof Method m) {
            return ec.javaResolver().resolveJavaMethodProfile(m.getDeclaringClass(), m.getName(), m);
        }
        return null;
    }

    private boolean isJavaParamMut(Executable exec, int index) {
        EffectChecker.JavaCallProfile profile = javaProfile(exec);
        return profile != null && index < profile.mutParams().length && profile.mutParams()[index];
    }

    private boolean isJavaParamBorrow(Executable exec, int index) {
        EffectChecker.JavaCallProfile profile = javaProfile(exec);
        return profile != null && index < profile.borrowParams().length && profile.borrowParams()[index]
                && (index >= profile.mutParams().length || !profile.mutParams()[index]);
    }
}
