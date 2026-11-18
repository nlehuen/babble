package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified polymorphic call, overload, operator, and function-value instantiation solver
 * ({@code docs/design/bip010_type-checker-simplification.md} §4.3):
 * argument binding, iterative {@code open -> closed} deferred lambda rounds, Spine-Local
 * {@code Peek}, coalescing with {@code BIP010-D4} double-widening, Java bound verification,
 * and JLS single-walk overload resolution.
 */
final class CallSolver {
    private final TypeChecker checker;

    CallSolver(TypeChecker checker) {
        this.checker = checker;
    }

    /**
     * Instantiates an unapplied generic function value {@code fn} against {@code expected}.
     */
    Type instantiateFunctionValue(Type.Function fn, BabbleParser.SelectorContext ctx, Type expected) {
        if (expected == null) {
            checker.warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
            return Type.ERROR;
        }
        Type target = Type.withoutNull(expected);
        Type.Function expectedFn = null;
        if (target instanceof Type.Function f) {
            expectedFn = f;
        } else if (target instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
            expectedFn = JavaTypeBridge.samFunctionType(ji, checker.effectChecker());
        }
        if (expectedFn == null || !expectedFn.typeParameters().isEmpty()) {
            checker.warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
            return Type.ERROR;
        }
        Set<Type.TypeVar> solving = Set.copyOf(fn.typeParameters());
        Type.Function unparameterized = new Type.Function(fn.parameters(), fn.result(), fn.effects());
        BabbleParser.FunctionLiteralContext lit = checker.functionLiterals().get(fn);
        if (lit != null) {
            checker.functionLiterals().put(unparameterized, lit);
        }
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, false, checker.algebraEnv());
        if (!TypeAlgebra.relate(unparameterized, expectedFn, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv())) {
            return unparameterized;
        }
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        for (Type.TypeVar tv : fn.typeParameters()) {
            Type resolved = store.lower(tv);
            if (resolved == null && !store.uppers(tv).isEmpty()) {
                resolved = store.uppers(tv).get(0);
            }
            if (resolved == null) {
                checker.warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
                return Type.ERROR;
            }
            inferred.put(tv, resolved);
        }
        return checker.substitute(unparameterized, inferred);
    }

    /**
     * The argument binding rules of {@code Arguments}, applied to a known signature.
     */
    void bind(List<Type.Parameter> parameters, List<BabbleParser.ParameterDeclarationContext> declarations,
              List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        int count = arguments.size();
        String[] names = new String[count];
        boolean named = false;
        for (int i = 0; i < count; i++) {
            if (arguments.get(i).ID() != null) {
                names[i] = arguments.get(i).ID().getText();
                named = true;
            }
        }
        boolean[] consumed = new boolean[count];
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = -1;
            for (int i = 0; i < count; i++) {
                if (parameter.name().equals(names[i])) {
                    found = i;
                    consumed[i] = true;
                }
            }
            if (found < 0 && index < count && names[index] == null) {
                found = index;
                consumed[index] = true;
            }
            if (found < 0) {
                if (!parameter.optional()) {
                    checker.warn("Missing parameter : " + parameter.name(), ctx);
                }
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(found).expression();
            BabbleParser.ParameterDeclarationContext decl = declarations != null && index < declarations.size()
                    ? declarations.get(index) : null;
            Type actual = checker.check(argument, parameter.type());
            bindArgument(decl, actual, parameter.type(), argument);
        }
        if (!named) {
            if (count > parameters.size()) {
                checker.warn("Too many parameters : " + parameters.size() + " declared, " + count + " given", ctx);
            }
            return;
        }
        Set<String> distinctNames = new LinkedHashSet<>();
        List<String> unknownNames = new ArrayList<>();
        int positional = 0;
        int extraPositional = 0;
        for (int i = 0; i < count; i++) {
            if (names[i] == null) {
                positional++;
                if (!consumed[i]) {
                    extraPositional++;
                }
            } else if (distinctNames.add(names[i]) && !consumed[i]) {
                unknownNames.add(names[i]);
            }
        }
        if (!unknownNames.isEmpty()) {
            checker.warn("No such parameter : " + String.join(", ", unknownNames), ctx);
        } else if (extraPositional > 0) {
            checker.warn("Too many parameters : " + parameters.size() + " declared, " + (positional + distinctNames.size()) + " given", ctx);
        }
    }

    private void bindArgument(BabbleParser.ParameterDeclarationContext decl, Type actual, Type expectedParam,
                              ParserRuleContext argument) {
        if (decl != null && checker.p7Params().contains(decl)) {
            Type inferredParam = checker.inferredParams().get(decl);
            boolean substitutedClassTypeVar = inferredParam != null && TypeChecker.containsTypeVar(inferredParam)
                    && !expectedParam.equals(inferredParam);
            if (substitutedClassTypeVar) {
                checker.subsumes(argument, actual, expectedParam);
            } else {
                checker.recordInferredParam(decl, actual, argument);
                Type latest = checker.inferredParams().get(decl);
                if (latest != null && TypeChecker.needsDoubleWidening(actual, latest)) {
                    checker.widened().add(argument);
                }
            }
        } else {
            checker.subsumes(argument, actual, expectedParam);
        }
    }

    /**
     * {@code a + b}, or {@code -a}, on an instance of a class or interface: typed as the call
     * of its operator method, {@code a.plus(b)} ({@code argument} null for {@code a.neg()}).
     */
    Type operatorCall(Type receiver, String method, ParserRuleContext argument, Type argumentType,
                      ParserRuleContext at, String receiverText) {
        Type callee = checker.member(receiver, method, false, at, receiverText);
        if (!(callee instanceof Type.Function function)) {
            if (!TypeChecker.isUnresolved(callee) && !TypeChecker.isFunction(callee)) {
                checker.warn(receiverText + "." + method + " is not callable", at);
            }
            return Type.ERROR;
        }
        List<Type.Parameter> parameters = function.parameters();
        int given = argument == null ? 0 : 1;
        if (given < parameters.size() && !parameters.get(given).optional()) {
            checker.warn("Missing parameter : " + parameters.get(given).name(), at);
        } else if (given > parameters.size()) {
            checker.warn("Too many parameters : " + parameters.size() + " declared, " + given + " given", at);
        } else if (argument != null) {
            Type expectedParam = parameters.get(0).type();
            List<BabbleParser.ParameterDeclarationContext> decls = checker.parameterDecls(function);
            BabbleParser.ParameterDeclarationContext decl = decls != null && !decls.isEmpty() ? decls.get(0) : null;
            bindArgument(decl, argumentType, expectedParam, argument);
        }
        return function.result();
    }

    Type genericBabbleCall(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                           List<BabbleParser.ParameterDeclarationContext> declarations,
                           Type resultPattern, List<BabbleParser.CallParameterContext> arguments,
                           BabbleParser.CallContext ctx, String description) {
        Type.Parameter[] argParams = mapArgumentsToParameters(parameters, arguments);
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, true, checker.algebraEnv());
        Map<Type.TypeVar, Type> retHints = collectReturnHints(resultPattern, checker.currentExpected(), solving);

        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            paramPatterns.add(argParams[i] != null ? argParams[i].type() : null);
            argExprs.add(arguments.get(i).expression());
        }
        Type[] argTypes = new Type[arguments.size()];
        boolean progress;
        do {
            progress = false;
            for (int i = 0; i < arguments.size(); i++) {
                if (argTypes[i] != null) {
                    continue;
                }
                Map<Type.TypeVar, Type> env = placeholderEnvWithHints(solving, store, retHints);
                Type paramPattern = paramPatterns.get(i);
                Type expectedArg = paramPattern != null ? checker.substitute(paramPattern, env) : null;
                if (!needsDeferredLambdaTyping(argExprs.get(i), expectedArg)) {
                    Type actual = checker.check(argExprs.get(i), expectedArg);
                    argTypes[i] = actual;
                    if (paramPattern != null) {
                        TypeAlgebra.relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
                    }
                    progress = true;
                }
            }
        } while (progress);

        Map<Type.TypeVar, Type> finalHintEnv = placeholderEnvWithHints(solving, store, retHints);
        for (int i = 0; i < arguments.size(); i++) {
            if (argTypes[i] == null) {
                Type paramPattern = paramPatterns.get(i);
                Type expectedArg = paramPattern != null ? checker.substitute(paramPattern, finalHintEnv) : null;
                Type actual = checker.check(argExprs.get(i), expectedArg);
                argTypes[i] = actual;
                if (paramPattern != null) {
                    TypeAlgebra.relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
                }
            }
        }
        Map<Type.TypeVar, Type> inferred = coalesceAndApplyDoubleWidening(solvingList, store, retHints, paramPatterns, argExprs);
        boolean argErrored = Arrays.stream(argTypes).anyMatch(TypeChecker::isUnresolved);
        for (Type.TypeVar tv : solvingList) {
            if (!inferred.containsKey(tv)) {
                if (!argErrored) {
                    checker.warn("Missing type : cannot infer type arguments for " + description, ctx);
                }
                return Type.ERROR;
            }
        }
        List<Type.Parameter> substitutedParams = new ArrayList<>(parameters.size());
        for (Type.Parameter p : parameters) {
            substitutedParams.add(p.withType(checker.substitute(p.type(), inferred)));
        }
        bind(substitutedParams, declarations, arguments, ctx);
        return checker.substitute(resultPattern, inferred);
    }

    Type javaGenericSamCall(Class<?> java, BabbleParser.ExpressionContext argExpr, BabbleParser.CallContext ctx) {
        TypeVariable<?>[] typeParams = java.getTypeParameters();
        if (typeParams.length == 0) {
            Type expectedSam = new Type.JavaInstance(java);
            if (TypeChecker.isFunction(checker.check(argExpr, expectedSam))) {
                return expectedSam;
            }
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx, true);
        }
        List<Type.TypeVar> solvingList = new ArrayList<>(typeParams.length);
        for (int i = 0; i < typeParams.length; i++) {
            solvingList.add(new Type.TypeVar(null, typeParams[i].getName(), i));
        }
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Type.JavaInstance samPattern = new Type.JavaInstance(java, List.copyOf(solvingList));
        Type.Function samFn = JavaTypeBridge.samFunctionType(samPattern, checker.effectChecker());
        Map<Type.TypeVar, Type> retHints = collectReturnHints(samPattern, checker.currentExpected(), solving);
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, true, checker.algebraEnv());
        Type expectedForArg = checker.substitute(samPattern, placeholderEnvWithHints(solving, store, retHints));
        Type actualArg = checker.check(argExpr, expectedForArg);
        if (TypeChecker.isUnresolved(actualArg)) {
            return Type.ERROR;
        }
        if (!TypeChecker.isFunction(actualArg)) {
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx, true);
        }
        if (actualArg instanceof Type.Function actualFn && samFn != null) {
            TypeAlgebra.relate(actualFn, samFn, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
        }
        Map<Type.TypeVar, Type> inferred = coalesceAndApplyDoubleWidening(
                solvingList, store, retHints, samFn != null ? List.of(samFn) : List.of(), List.of(argExpr));
        List<Type> solvedArgs = new ArrayList<>(typeParams.length);
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        for (int i = 0; i < typeParams.length; i++) {
            Type resolved = inferred.get(solvingList.get(i));
            if (resolved == null) {
                checker.warn("Missing type : cannot infer type arguments for " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
            solvedArgs.add(resolved);
            env.put(typeParams[i], resolved);
        }
        for (int i = 0; i < typeParams.length; i++) {
            if (!JavaTypeBridge.satisfiesJavaBounds(solvedArgs.get(i), typeParams[i], env, checker.algebraEnv())) {
                checker.warn("Type error : type argument " + solvedArgs.get(i) + " does not satisfy bound "
                        + typeParams[i].getName() + " of " + java.getCanonicalName(), argExpr);
                return Type.ERROR;
            }
        }
        Type.JavaInstance result = new Type.JavaInstance(java, solvedArgs);
        if (actualArg instanceof Type.Function actualFn) {
            checker.subsumes(argExpr, null, actualFn, result);
        }
        return result;
    }

    Type javaConstructorCall(Class<?> java, List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallContext ctx, boolean recordTarget) {
        List<Executable> candidates = Arrays.asList(java.getConstructors());
        List<Type> argumentTypes = new ArrayList<>();
        List<Executable> matching = javaCall(candidates, java.getCanonicalName(), Map.of(), arguments, ctx, argumentTypes);
        TypeVariable<?>[] classParams = java.getTypeParameters();
        if (classParams.length == 0) {
            if (!matching.isEmpty()) {
                Executable winner = matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching);
                if (winner != null) {
                    checkJavaArgumentWidening(winner, Map.of(), arguments, argumentTypes);
                    if (recordTarget) {
                        recordJavaConstructor(winner, arguments, argumentTypes, ctx);
                    }
                }
            }
            return new Type.JavaInstance(java);
        }
        if (matching.isEmpty()) {
            return Type.ERROR;
        }
        Executable winner = matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching);
        if (winner == null) {
            return Type.ERROR;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSynthetic = new HashMap<>();
        List<Type.TypeVar> classSolving = new ArrayList<>(classParams.length);
        int idx = 0;
        for (TypeVariable<?> tp : classParams) {
            Type.TypeVar tv = new Type.TypeVar(null, tp.getName(), idx++);
            classSolving.add(tv);
            tvToSynthetic.put(tp, tv);
        }
        for (TypeVariable<?> tp : winner.getTypeParameters()) {
            tvToSynthetic.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), idx++));
        }
        List<Type.TypeVar> solvingList = List.copyOf(tvToSynthetic.values());
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(tvToSynthetic);
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, true, checker.algebraEnv());
        java.lang.reflect.Type[] genericParams = winner.getGenericParameterTypes();
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = JavaTypeBridge.fromJavaType(genericParams[i], patternEnv, false, true);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            TypeAlgebra.relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
        }
        Type.JavaInstance patternReturn = new Type.JavaInstance(java, List.copyOf(classSolving));
        Map<Type.TypeVar, Type> retHints = collectReturnHints(patternReturn, checker.currentExpected(), solving);
        Map<Type.TypeVar, Type> inferred = coalesceAndApplyDoubleWidening(solvingList, store, retHints, paramPatterns, argExprs);
        boolean argErrored = argumentTypes.stream().anyMatch(TypeChecker::isUnresolved);
        Map<TypeVariable<?>, Type> solvedEnv = new HashMap<>();
        List<Type> solvedClassArgs = new ArrayList<>(classParams.length);
        for (TypeVariable<?> tp : tvToSynthetic.keySet()) {
            Type resolved = inferred.get(tvToSynthetic.get(tp));
            if (resolved == null) {
                if (!argErrored) {
                    checker.warn("Missing type : cannot infer type arguments for " + java.getCanonicalName(), ctx);
                }
                return Type.ERROR;
            }
            solvedEnv.put(tp, resolved);
        }
        for (int i = 0; i < classParams.length; i++) {
            Type resolved = solvedEnv.get(classParams[i]);
            solvedClassArgs.add(resolved);
            if (!JavaTypeBridge.satisfiesJavaBounds(resolved, classParams[i], solvedEnv, checker.algebraEnv())) {
                checker.warn("Type error : type argument " + resolved + " does not satisfy bound " + classParams[i].getName()
                        + " of " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
        }
        for (TypeVariable<?> tp : winner.getTypeParameters()) {
            Type resolved = solvedEnv.get(tp);
            if (!JavaTypeBridge.satisfiesJavaBounds(resolved, tp, solvedEnv, checker.algebraEnv())) {
                checker.warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
        }
        checkJavaArgumentWidening(winner, solvedEnv, arguments, argumentTypes);
        if (recordTarget) {
            recordJavaConstructor(winner, arguments, argumentTypes, ctx);
        }
        return new Type.JavaInstance(java, solvedClassArgs);
    }

    Type javaMethodCall(List<Method> candidates, String description, Map<TypeVariable<?>, Type> receiverEnv,
                        List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx,
                        boolean recordTarget) {
        List<Type> argumentTypes = new ArrayList<>();
        List<Method> applicable = javaCall(candidates, description, receiverEnv, arguments, ctx, argumentTypes);
        if (applicable.isEmpty()) {
            return Type.ERROR;
        }
        Method best = applicable.size() == 1 ? applicable.get(0) : null;
        if (best == null) {
            boolean known = argumentTypes.stream().noneMatch(TypeChecker::isUnresolved);
            best = known ? JavaTypeBridge.mostSpecific(applicable) : null;
        }
        if (best == null) {
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1 && applicable.stream().allMatch(m -> m.getTypeParameters().length == 0)) {
                return JavaTypeBridge.javaResult(applicable.get(0).getGenericReturnType(), receiverEnv, checker.algebraEnv());
            }
            return Type.ERROR;
        }
        TypeVariable<?>[] methodParams = best.getTypeParameters();
        if (methodParams.length == 0) {
            checkJavaArgumentWidening(best, receiverEnv, arguments, argumentTypes);
            java.lang.reflect.Type genericRet = best.getGenericReturnType();
            Type result = JavaTypeBridge.javaResult(genericRet, receiverEnv, checker.algebraEnv());
            if (genericRet instanceof TypeVariable<?> || genericRet instanceof WildcardType) {
                String desc = LoweringAnalyzer.descriptor(result);
                if (desc != null) {
                    checker.callChecks().put(ctx, desc);
                }
            }
            if (recordTarget) {
                recordJavaMethodCall(best, arguments, argumentTypes, ctx);
            }
            return result;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSynthetic = new HashMap<>();
        List<Type.TypeVar> solvingList = new ArrayList<>(methodParams.length);
        for (int i = 0; i < methodParams.length; i++) {
            Type.TypeVar tv = new Type.TypeVar(null, methodParams[i].getName(), i);
            tvToSynthetic.put(methodParams[i], tv);
            solvingList.add(tv);
        }
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
        patternEnv.putAll(tvToSynthetic);
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, true, checker.algebraEnv());
        java.lang.reflect.Type[] genericParams = best.getGenericParameterTypes();
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = JavaTypeBridge.fromJavaType(genericParams[i], patternEnv, false, true);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            TypeAlgebra.relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
        }
        Type retPat = JavaTypeBridge.fromJavaType(best.getGenericReturnType(), patternEnv, false, false);
        Map<Type.TypeVar, Type> retHints = collectReturnHints(retPat, checker.currentExpected(), solving);
        Map<Type.TypeVar, Type> inferred = coalesceAndApplyDoubleWidening(solvingList, store, retHints, paramPatterns, argExprs);
        boolean argErrored = argumentTypes.stream().anyMatch(TypeChecker::isUnresolved);
        Map<TypeVariable<?>, Type> solvedEnv = new HashMap<>(receiverEnv);
        for (TypeVariable<?> tp : methodParams) {
            Type resolved = inferred.get(tvToSynthetic.get(tp));
            if (resolved == null) {
                if (!argErrored) {
                    checker.warn("Missing type : cannot infer type arguments for " + description, ctx);
                }
                return Type.ERROR;
            }
            solvedEnv.put(tp, resolved);
        }
        for (TypeVariable<?> tp : methodParams) {
            Type resolved = solvedEnv.get(tp);
            if (!JavaTypeBridge.satisfiesJavaBounds(resolved, tp, solvedEnv, checker.algebraEnv())) {
                checker.warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + description, ctx);
                return Type.ERROR;
            }
        }
        checkJavaArgumentWidening(best, solvedEnv, arguments, argumentTypes);
        java.lang.reflect.Type genericRet = best.getGenericReturnType();
        Type result = JavaTypeBridge.javaResult(genericRet, solvedEnv, checker.algebraEnv());
        if (genericRet instanceof TypeVariable<?> || genericRet instanceof WildcardType) {
            String desc = LoweringAnalyzer.descriptor(result);
            if (desc != null) {
                checker.callChecks().put(ctx, desc);
            }
        }
        if (recordTarget) {
            recordJavaMethodCall(best, arguments, argumentTypes, ctx);
        }
        return result;
    }

    private <E extends Executable> List<E> javaCall(List<E> candidates, String description,
                                                    Map<TypeVariable<?>, Type> receiverEnv,
                                                    List<BabbleParser.CallParameterContext> arguments,
                                                    BabbleParser.CallContext ctx,
                                                    List<Type> outArgumentTypes) {
        List<E> matching = new ArrayList<>();
        for (E candidate : candidates) {
            if (candidate.getParameterCount() == arguments.size()) {
                matching.add(candidate);
            }
        }
        Type[] argTypes = new Type[arguments.size()];
        boolean[] deferred = new boolean[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type expected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, receiverEnv);
            if (needsDeferredLambdaTyping(argument, expected)) {
                deferred[i] = true;
                continue;
            }
            Type actual = checker.check(argument, expected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !JavaTypeBridge.applicableGeneric(candidate, index, actual, receiverEnv, checker.algebraEnv()));
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!deferred[i]) {
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Map<TypeVariable<?>, Type> refinedEnv = refineJavaEnvFromArgs(matching, receiverEnv, argTypes, deferred, ctx);
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type expected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, refinedEnv);
            Type actual = checker.check(argument, expected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !JavaTypeBridge.applicableGeneric(candidate, index, actual, refinedEnv, checker.algebraEnv()));
        }
        outArgumentTypes.clear();
        for (Type t : argTypes) {
            outArgumentTypes.add(t);
        }
        if (matching.isEmpty() && !candidates.isEmpty()) {
            checker.warn("No applicable overload of " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        } else if (matching.size() > 1
                && outArgumentTypes.stream().noneMatch(TypeChecker::isUnresolved)
                && JavaTypeBridge.mostSpecific(matching) == null) {
            checker.warn("Ambiguous call to " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        }
        return matching;
    }

    private <E extends Executable> Map<TypeVariable<?>, Type> refineJavaEnvFromArgs(
            List<E> matching, Map<TypeVariable<?>, Type> receiverEnv, Type[] argTypes, boolean[] deferred,
            BabbleParser.CallContext ctx) {
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
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(solving, true, checker.algebraEnv());
        java.lang.reflect.Type[] genericParams = cand.getGenericParameterTypes();
        for (int i = 0; i < argTypes.length; i++) {
            if (!deferred[i] && argTypes[i] != null) {
                Type paramPat = JavaTypeBridge.fromJavaType(genericParams[i], patternEnv, false, true);
                TypeAlgebra.relate(argTypes[i], paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store, checker.algebraEnv());
            }
        }
        Type ctxExpected = checker.currentExpected();
        Map<Type.TypeVar, Type> retHints = Map.of();
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            Type retPat;
            if (cand instanceof Method m) {
                retPat = JavaTypeBridge.fromJavaType(m.getGenericReturnType(), patternEnv, false, false);
            } else {
                Class<?> cls = cand.getDeclaringClass();
                List<Type> clsVars = Arrays.stream(cls.getTypeParameters())
                        .map(tp -> (Type) tvToSyn.get(tp)).toList();
                retPat = new Type.JavaInstance(cls, clsVars);
            }
            retHints = collectReturnHints(retPat, ctxExpected, solving);
        }
        Map<TypeVariable<?>, Type> refined = new HashMap<>(receiverEnv);
        Map<Type.TypeVar, Type> finalHints = retHints;
        tvToSyn.forEach((tv, syn) -> {
            Type solved = store.lower(syn);
            if (solved == null && !store.uppers(syn).isEmpty()) {
                solved = store.uppers(syn).get(0);
            }
            if (solved == null) {
                solved = finalHints.get(syn);
            }
            if (solved != null) {
                refined.put(tv, solved);
            }
        });
        return refined;
    }

    private void recordJavaConstructor(Executable winner, List<BabbleParser.CallParameterContext> arguments,
                                       List<Type> argumentTypes, BabbleParser.CallContext ctx) {
        if (!(winner instanceof Constructor<?> ctor)) {
            return;
        }
        checker.resolvedJavaConstructors().put(ctx, ctor);
        if (ctx.expression() instanceof BabbleParser.SelectorContext sel && sel.safe != null) {
            return;
        }
        Class<?> java = ctor.getDeclaringClass();
        if (!Modifier.isPublic(ctor.getModifiers()) || !Modifier.isPublic(java.getModifiers())
                || !JavaTypes.isAccessible(java) || ctor.getParameterCount() != arguments.size()) {
            return;
        }
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (arg.ID() != null) {
                return;
            }
        }
        Class<?>[] paramTypes = ctor.getParameterTypes();
        if (argumentTypes.size() != paramTypes.length) {
            return;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Type argType = argumentTypes.get(i);
            if (!JavaTypeBridge.guaranteedApplicable(paramTypes[i], argType)
                    || !JavaTypeBridge.supportedStaticJavaArg(paramTypes[i], argType)) {
                return;
            }
        }
        checker.javaConstructorTargets().put(ctx, new ScopeAnalysis.JavaConstructorTarget(ctor));
    }

    private void recordJavaMethodCall(Method best, List<BabbleParser.CallParameterContext> arguments,
                                      List<Type> argumentTypes, BabbleParser.CallContext ctx) {
        checker.resolvedJavaMethods().put(ctx, best);
        if (!(ctx.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.safe != null
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        Type receiverType = checker.types().get(sel.expression());
        if (!(receiverType instanceof Type.JavaClassValue
                || receiverType instanceof Type.JavaInstance
                || receiverType instanceof Type.JavaArray
                || receiverType == Type.STRING)) {
            return;
        }
        Method accessible = JavaTypes.accessible(best);
        if (accessible == null
                || !Modifier.isPublic(accessible.getModifiers())
                || !Modifier.isPublic(accessible.getDeclaringClass().getModifiers())
                || !JavaTypes.isAccessible(accessible.getDeclaringClass())
                || !JavaTypeBridge.isBytecodeAccessible(accessible.getReturnType())
                || accessible.getParameterCount() != arguments.size()) {
            return;
        }
        boolean isStatic = Modifier.isStatic(accessible.getModifiers());
        if (receiverType instanceof Type.JavaClassValue && !isStatic) {
            return;
        }
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (arg.ID() != null) {
                return;
            }
        }
        Class<?>[] paramTypes = accessible.getParameterTypes();
        if (argumentTypes.size() != paramTypes.length) {
            return;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Type argType = argumentTypes.get(i);
            if (!JavaTypeBridge.guaranteedApplicable(paramTypes[i], argType)
                    || !JavaTypeBridge.supportedStaticJavaArg(paramTypes[i], argType)) {
                return;
            }
        }
        boolean unwrapReceiver = !isStatic && receiverType != Type.STRING;
        checker.javaCallTargets().put(ctx, new ScopeAnalysis.JavaCallTarget(accessible, isStatic, unwrapReceiver));
    }

    private void checkJavaArgumentWidening(Executable candidate, Map<TypeVariable<?>, Type> env,
                                           List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes) {
        java.lang.reflect.Type[] genericParams = candidate.getGenericParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            if (i < argumentTypes.size()) {
                Type paramType = JavaTypeBridge.fromJavaType(genericParams[i], env, false, true);
                if (TypeChecker.needsDoubleWidening(argumentTypes.get(i), paramType)) {
                    checker.widened().add(arguments.get(i).expression());
                }
            }
        }
    }

    // ---------------------------------------------------------------- constraint helpers

    private Map<Type.TypeVar, Type> collectReturnHints(Type resultPattern, Type actualExpected, Set<Type.TypeVar> solving) {
        if (resultPattern == null || actualExpected == null || actualExpected == Type.ERROR || resultPattern == Type.ERROR) {
            return Map.of();
        }
        Type expBase = Type.containsNull(resultPattern) ? actualExpected : Type.withoutNull(actualExpected);
        if (expBase == Type.NOTHING || expBase instanceof Type.Union) {
            return Map.of();
        }
        TypeAlgebra.ConstraintStore retStore = new TypeAlgebra.ConstraintStore(solving, false, checker.algebraEnv());
        TypeAlgebra.relate(resultPattern, expBase, TypeAlgebra.RelateMode.SUBTYPE, retStore, checker.algebraEnv());
        Map<Type.TypeVar, Type> hints = new HashMap<>();
        for (Type.TypeVar tv : solving) {
            Type hint = retStore.lower(tv);
            if (hint == null && !retStore.uppers(tv).isEmpty()) {
                hint = retStore.uppers(tv).get(0);
            }
            if (hint != null) {
                hints.put(tv, hint);
            }
        }
        return hints;
    }

    private Map<Type.TypeVar, Type> coalesceAndApplyDoubleWidening(
            List<Type.TypeVar> solvingList,
            TypeAlgebra.ConstraintStore store,
            Map<Type.TypeVar, Type> retHints,
            List<Type> paramPatterns,
            List<BabbleParser.ExpressionContext> argExprs) {
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        for (Type.TypeVar tv : solvingList) {
            Type resolved = store.lower(tv);
            if (resolved == null && !store.uppers(tv).isEmpty()) {
                resolved = store.uppers(tv).get(0);
            }
            if (resolved == null) {
                resolved = retHints.get(tv);
            }
            if (resolved != null) {
                inferred.put(tv, resolved);
            }
        }
        for (Map.Entry<Type.TypeVar, Type> entry : retHints.entrySet()) {
            Type.TypeVar tv = entry.getKey();
            Type hint = entry.getValue();
            Type current = inferred.get(tv);
            if (current != null && isIntOrDouble(current) && TypeChecker.needsDoubleWidening(current, hint)
                    && canWidenTypeVarToDouble(tv, paramPatterns, argExprs)) {
                inferred.put(tv, Type.containsNull(current) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE);
            }
        }
        return inferred;
    }

    private boolean canWidenTypeVarToDouble(Type.TypeVar tv,
                                            List<Type> paramPatterns,
                                            List<BabbleParser.ExpressionContext> argExprs) {
        for (int i = 0; i < paramPatterns.size(); i++) {
            Type pat = paramPatterns.get(i);
            if (pat == null) {
                continue;
            }
            if (Type.withoutNull(pat).equals(tv)) {
                continue;
            }
            if (pat instanceof Type.Function fn
                    && Type.withoutNull(fn.result()).equals(tv)
                    && fn.parameters().stream().noneMatch(p -> mentionsTypeVar(p.type(), tv))
                    && i < argExprs.size()) {
                BabbleParser.FunctionLiteralContext lit = checker.unwrapFunctionLiteral(argExprs.get(i));
                if (lit != null && lit.type() == null) {
                    continue;
                }
            }
            if (mentionsTypeVar(pat, tv)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIntOrDouble(Type type) {
        Type base = Type.withoutNull(type);
        return base == Type.INT || base == Type.DOUBLE;
    }

    private static boolean mentionsTypeVar(Type type, Type.TypeVar target) {
        return type != null && type.anyMatch(t -> t.equals(target));
    }

    static Type.Parameter[] mapArgumentsToParameters(List<Type.Parameter> parameters,
                                                             List<BabbleParser.CallParameterContext> arguments) {
        int count = arguments.size();
        Type.Parameter[] mapping = new Type.Parameter[count];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            if (arguments.get(i).ID() != null) {
                names[i] = arguments.get(i).ID().getText();
            }
        }
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = -1;
            for (int i = 0; i < count; i++) {
                if (parameter.name().equals(names[i])) {
                    found = i;
                }
            }
            if (found < 0 && index < count && names[index] == null) {
                found = index;
            }
            if (found >= 0) {
                mapping[found] = parameter;
            }
        }
        return mapping;
    }

    private static Map<Type.TypeVar, Type> placeholderEnvWithHints(Set<Type.TypeVar> solving,
                                                                   TypeAlgebra.ConstraintStore store,
                                                                   Map<Type.TypeVar, Type> hints) {
        Map<Type.TypeVar, Type> env = new HashMap<>(solving.size());
        for (Type.TypeVar tv : solving) {
            Type resolved = store.lower(tv);
            if (resolved == null && !store.uppers(tv).isEmpty()) {
                resolved = store.uppers(tv).get(0);
            }
            if (resolved == null && hints != null) {
                resolved = hints.get(tv);
            }
            env.put(tv, resolved != null ? resolved : new Type.TypeVar(null, tv.name(), tv.index()));
        }
        return env;
    }

    private boolean needsDeferredLambdaTyping(BabbleParser.ExpressionContext argExpr, Type expectedArg) {
        BabbleParser.FunctionLiteralContext literal = checker.unwrapFunctionLiteral(argExpr);
        if (literal != null) {
            boolean hasUnannotated = false;
            for (BabbleParser.ParameterDeclarationContext p : literal.parametersDeclaration().parameterDeclaration()) {
                if (p.type() == null && p.defaultValue == null) {
                    hasUnannotated = true;
                    break;
                }
            }
            if (!hasUnannotated) {
                return false;
            }
            if (expectedArg == null) {
                return true;
            }
            return paramInputHasSyntheticTypeVar(Type.withoutNull(expectedArg));
        }
        BabbleParser.ExpressionContext unwrapped = argExpr;
        while (unwrapped instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            unwrapped = block.block().expression(0);
        }
        if (unwrapped instanceof BabbleParser.SelectorContext selector
                && checker.isGenericFunctionSelector(selector)
                && (expectedArg == null || paramInputHasSyntheticTypeVar(Type.withoutNull(expectedArg)))) {
            return true;
        }
        return false;
    }

    private boolean paramInputHasSyntheticTypeVar(Type expectedBase) {
        if (expectedBase instanceof Type.Function fn) {
            for (Type.Parameter p : fn.parameters()) {
                if (containsSyntheticTypeVar(p.type())) {
                    return true;
                }
            }
            return false;
        }
        if (expectedBase instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
            Type.Function sam = JavaTypeBridge.samFunctionType(ji, checker.effectChecker());
            if (sam != null) {
                return paramInputHasSyntheticTypeVar(sam);
            }
        }
        return containsSyntheticTypeVar(expectedBase);
    }

    private static boolean containsSyntheticTypeVar(Type type) {
        return type != null && type.anyMatch(t -> t instanceof Type.TypeVar tv && tv.owner() == null);
    }
}
