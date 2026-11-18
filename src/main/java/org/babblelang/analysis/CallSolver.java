package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
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
    /** The Java method or constructor each call resolved to (for effects and lowering). */
    private final Map<BabbleParser.CallContext, Method> resolvedMethods = new java.util.IdentityHashMap<>();
    private final Map<BabbleParser.CallContext, Constructor<?>> resolvedConstructors = new java.util.IdentityHashMap<>();

    CallSolver(TypeChecker checker) {
        this.checker = checker;
    }

    /** Forgets what a walk resolved, before the next one. */
    void reset() {
        resolvedMethods.clear();
        resolvedConstructors.clear();
    }

    private void recordConstructor(Executable winner, BabbleParser.CallContext ctx) {
        if (winner instanceof Constructor<?> constructor) {
            resolvedConstructors.put(ctx, constructor);
        }
    }

    Method resolvedMethod(BabbleParser.CallContext call) {
        return resolvedMethods.get(call);
    }

    Constructor<?> resolvedConstructor(BabbleParser.CallContext call) {
        return resolvedConstructors.get(call);
    }

    /** The type of a call of {@code callee} ; {@code recordTarget} records the Java executable it resolves to (not for a union member). */
    Type call(Type callee, BabbleParser.CallContext ctx, Type expected, boolean recordTarget) {
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        if (callee == Type.ERROR || callee == Type.NOTHING) {
            return Type.ERROR;
        }
        if (callee == Type.PRINT) {
            Type appendable = new Type.JavaInstance(Appendable.class);
            for (BabbleParser.CallParameterContext argument : arguments) {
                if (argument.ID() != null && "to".equals(argument.ID().getText())) {
                    checker.subsumes(argument.expression(), null, checker.check(argument.expression(), appendable), appendable);
                } else {
                    checker.check(argument.expression(), Type.NULLABLE_ANY);
                }
            }
            return Type.NULL;
        }
        if (callee instanceof Type.Union union) {
            if (Type.containsNull(union)) {
                checker.warn(ctx.expression().getText() + " is not callable", ctx);
                return Type.ERROR;
            }
            for (Type member : union.members()) {
                if (!TypeAlgebra.isCallable(member)) {
                    checker.warn(ctx.expression().getText() + " is not callable", ctx);
                    return Type.ERROR;
                }
            }
            List<Type> results = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                Type r = call(member, ctx, expected, false);
                if (r == Type.ERROR) {
                    return Type.ERROR;
                }
                results.add(r);
            }
            return Type.union(results);
        }
        if (callee instanceof Type.Function function) {
            List<BabbleParser.ParameterDeclarationContext> decls = checker.parameterDecls(function);
            if (function.typeParameters().isEmpty()) {
                bind(function.parameters(), decls, arguments, ctx);
                return function.result();
            }
            Type result = genericBabbleCall(function.typeParameters(), function.parameters(), decls, function.result(),
                    arguments, ctx, ctx.expression().getText(), expected);
            return result;
        }
        if (callee instanceof Type.ClassValue value && !value.node().isInterface()) {
            TypeNode node = value.node();
            List<Type.TypeVar> classVars = checker.annotator().typeVarsOf(node);
            List<Type.Parameter> parameters = checker.constructorParameters(node);
            List<BabbleParser.ParameterDeclarationContext> decls = checker.parameterDecls(node);
            if (classVars.isEmpty()) {
                if (parameters != null) {
                    bind(parameters, decls, arguments, ctx);
                }
                return new Type.Instance(node);
            }
            if (parameters == null) {
                parameters = List.of();
            }
            Type patternReturn = new Type.Instance(node, List.copyOf(classVars));
            return genericBabbleCall(classVars, parameters, decls, patternReturn, arguments, ctx, node.name, expected);
        }
        if (callee instanceof Type.JavaClassValue value) {
            Class<?> java = value.type();
            if (java.isInterface() && arguments.size() == 1 && arguments.get(0).ID() == null
                    && JavaTypes.isFunctionalInterface(java)) {
                return javaGenericSamCall(java, arguments.get(0).expression(), ctx, expected);
            }
            return javaConstructorCall(java, arguments, ctx, expected, recordTarget);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> candidates = JavaTypes.methods(method.owner(), method.name());
            Map<TypeVariable<?>, Type> receiverEnv = method.typeArguments().isEmpty()
                    ? Map.of() : JavaTypeBridge.javaTypeEnv(method.owner(), method.typeArguments());
            return javaMethodCall(candidates, method.owner().getCanonicalName() + "." + method.name(),
                    receiverEnv, arguments, ctx, expected, recordTarget);
        }
        checker.warn(ctx.expression().getText() + " is not callable", ctx);
        return Type.ERROR;
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
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, false);
        if (!checker.algebra().relate(unparameterized, expectedFn, TypeAlgebra.RelateMode.ASSIGNABLE, store)) {
            return unparameterized;
        }
        Map<Type.TypeVar, Type> inferred = solveAll(fn.typeParameters(), store, Map.of(), List.of(), List.of());
        if (inferred == null) {
            checker.warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
            return Type.ERROR;
        }
        return checker.algebra().substitute(unparameterized, inferred);
    }

    /**
     * The argument binding rules of {@code Arguments}, applied to a known signature.
     */
    void bind(List<Type.Parameter> parameters, List<BabbleParser.ParameterDeclarationContext> declarations,
              List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        int count = arguments.size();
        String[] names = argumentNames(arguments);
        boolean named = Arrays.stream(names).anyMatch(java.util.Objects::nonNull);
        boolean[] consumed = new boolean[count];
        int[] matched = matchArguments(parameters, names, consumed);
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = matched[index];
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
            boolean substitutedClassTypeVar = inferredParam != null && inferredParam.hasTypeVar()
                    && !expectedParam.equals(inferredParam);
            if (substitutedClassTypeVar) {
                checker.subsumes(argument, actual, expectedParam);
            } else {
                checker.recordInferredParam(decl, actual, argument);
                Type latest = checker.inferredParams().get(decl);
                if (latest != null) {
                    checker.coerce(argument, actual, latest);
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
        Type callee = checker.members().member(receiver, method, false, at, receiverText);
        if (!(callee instanceof Type.Function function)) {
            if (!TypeChecker.isUnresolved(callee) && !TypeAlgebra.isCallable(callee)) {
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
                           BabbleParser.CallContext ctx, String description, Type expected) {
        Type.Parameter[] argParams = mapArgumentsToParameters(parameters, arguments);
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Map<Type.TypeVar, Type> retHints = collectReturnHints(resultPattern, expected, solving);

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
                Type expectedArg = paramPattern != null ? checker.algebra().substitute(paramPattern, env) : null;
                if (!needsDeferredLambdaTyping(argExprs.get(i), expectedArg)) {
                    Type actual = checker.check(argExprs.get(i), expectedArg);
                    argTypes[i] = actual;
                    if (paramPattern != null) {
                        checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store);
                    }
                    progress = true;
                }
            }
        } while (progress);

        Map<Type.TypeVar, Type> finalHintEnv = placeholderEnvWithHints(solving, store, retHints);
        for (int i = 0; i < arguments.size(); i++) {
            if (argTypes[i] == null) {
                Type paramPattern = paramPatterns.get(i);
                Type expectedArg = paramPattern != null ? checker.algebra().substitute(paramPattern, finalHintEnv) : null;
                Type actual = checker.check(argExprs.get(i), expectedArg);
                argTypes[i] = actual;
                if (paramPattern != null) {
                    checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store);
                }
            }
        }
        Map<Type.TypeVar, Type> inferred = solveAll(solvingList, store, retHints, paramPatterns, argExprs);
        if (inferred == null) {
            if (Arrays.stream(argTypes).noneMatch(TypeChecker::isUnresolved)) {
                checker.warn("Missing type : cannot infer type arguments for " + description, ctx);
            }
            return Type.ERROR;
        }
        List<Type.Parameter> substitutedParams = new ArrayList<>(parameters.size());
        for (Type.Parameter p : parameters) {
            substitutedParams.add(p.withType(checker.algebra().substitute(p.type(), inferred)));
        }
        bind(substitutedParams, declarations, arguments, ctx);
        return checker.algebra().substitute(resultPattern, inferred);
    }

    Type javaGenericSamCall(Class<?> java, BabbleParser.ExpressionContext argExpr, BabbleParser.CallContext ctx, Type expected) {
        TypeVariable<?>[] typeParams = java.getTypeParameters();
        if (typeParams.length == 0) {
            Type expectedSam = new Type.JavaInstance(java);
            if (TypeAlgebra.isCallable(checker.check(argExpr, expectedSam))) {
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
        Map<Type.TypeVar, Type> retHints = collectReturnHints(samPattern, expected, solving);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Type expectedForArg = checker.algebra().substitute(samPattern, placeholderEnvWithHints(solving, store, retHints));
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
        Map<Type.TypeVar, Type> inferred = solveAll(
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
        Type.JavaInstance result = new Type.JavaInstance(java, solvedArgs);
        if (actualArg instanceof Type.Function actualFn) {
            checker.subsumes(argExpr, null, actualFn, result);
        }
        return result;
    }

    Type javaConstructorCall(Class<?> java, List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallContext ctx, Type expected, boolean recordTarget) {
        List<Type> argumentTypes = new ArrayList<>();
        String description = java.getCanonicalName();
        List<Executable> matching = javaCall(Arrays.asList(java.getConstructors()), description, Map.of(), arguments, ctx, expected, argumentTypes);
        TypeVariable<?>[] classParams = java.getTypeParameters();
        Executable winner = matching.isEmpty() ? null : matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching);
        if (classParams.length == 0) {
            if (winner != null) {
                checkJavaArgumentWidening(winner, Map.of(), arguments, argumentTypes);
                if (recordTarget) {
                    recordConstructor(winner, ctx);
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
                arguments, argumentTypes, description, ctx, expected);
        if (solved == null) {
            return Type.ERROR;
        }
        if (recordTarget) {
            recordConstructor(winner, ctx);
        }
        return new Type.JavaInstance(java, Arrays.stream(classParams).map(solved::get).toList());
    }

    Type javaMethodCall(List<Method> candidates, String description, Map<TypeVariable<?>, Type> receiverEnv,
                        List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx,
                        Type expected, boolean recordTarget) {
        List<Type> argumentTypes = new ArrayList<>();
        List<Method> applicable = javaCall(candidates, description, receiverEnv, arguments, ctx, expected, argumentTypes);
        if (applicable.isEmpty()) {
            return Type.ERROR;
        }
        Method best = applicable.size() == 1 ? applicable.get(0) : null;
        if (best == null && argumentTypes.stream().noneMatch(TypeChecker::isUnresolved)) {
            best = JavaTypeBridge.mostSpecific(applicable);
        }
        if (best == null) {
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1 && applicable.stream().allMatch(m -> m.getTypeParameters().length == 0)) {
                return JavaTypeBridge.javaResult(applicable.get(0).getGenericReturnType(), receiverEnv, checker.algebra());
            }
            return Type.ERROR;
        }
        Map<TypeVariable<?>, Type> env = receiverEnv;
        if (best.getTypeParameters().length == 0) {
            checkJavaArgumentWidening(best, receiverEnv, arguments, argumentTypes);
        } else {
            Method generic = best;
            env = solveJava(best, Arrays.asList(best.getTypeParameters()), receiverEnv,
                    synthetic -> {
                        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
                        patternEnv.putAll(synthetic);
                        return JavaTypeBridge.fromJavaType(generic.getGenericReturnType(), patternEnv, false, false);
                    },
                    arguments, argumentTypes, description, ctx, expected);
            if (env == null) {
                return Type.ERROR;
            }
        }
        java.lang.reflect.Type genericRet = best.getGenericReturnType();
        Type result = JavaTypeBridge.javaResult(genericRet, env, checker.algebra());
        if (genericRet instanceof TypeVariable<?> || genericRet instanceof WildcardType) {
            String desc = LoweringAnalyzer.descriptor(result);
            if (desc != null) {
                checker.callChecks().put(ctx, desc);
            }
        }
        if (recordTarget) {
            resolvedMethods.put(ctx, best);
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
                                                 String description, BabbleParser.CallContext ctx, Type expected) {
        Map<TypeVariable<?>, Type.TypeVar> synthetic = new HashMap<>();
        for (TypeVariable<?> tp : variables) {
            synthetic.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), synthetic.size()));
        }
        List<Type.TypeVar> solvingList = List.copyOf(synthetic.values());
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(knownEnv);
        patternEnv.putAll(synthetic);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        java.lang.reflect.Type[] genericParams = executable.getGenericParameterTypes();
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = JavaTypeBridge.fromJavaType(genericParams[i], patternEnv, false, true);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            checker.algebra().relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store);
        }
        Map<Type.TypeVar, Type> retHints = collectReturnHints(resultPattern.apply(synthetic), expected, solving);
        Map<Type.TypeVar, Type> inferred = solveAll(solvingList, store, retHints, paramPatterns, argExprs);
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
            if (!JavaTypeBridge.satisfiesJavaBounds(resolved, tp, solved, checker.algebra())) {
                checker.warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + description, ctx);
                return null;
            }
        }
        checkJavaArgumentWidening(executable, solved, arguments, argumentTypes);
        return solved;
    }

    private <E extends Executable> List<E> javaCall(List<E> candidates, String description,
                                                    Map<TypeVariable<?>, Type> receiverEnv,
                                                    List<BabbleParser.CallParameterContext> arguments,
                                                    BabbleParser.CallContext ctx, Type expected,
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
            Type argExpected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, receiverEnv);
            if (needsDeferredLambdaTyping(argument, argExpected)) {
                deferred[i] = true;
                continue;
            }
            Type actual = checker.check(argument, argExpected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !JavaTypeBridge.applicableGeneric(candidate, index, actual, receiverEnv, checker.algebra()));
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!deferred[i]) {
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Map<TypeVariable<?>, Type> refinedEnv = refineJavaEnvFromArgs(matching, receiverEnv, argTypes, deferred, ctx, expected);
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type argExpected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, refinedEnv);
            Type actual = checker.check(argument, argExpected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !JavaTypeBridge.applicableGeneric(candidate, index, actual, refinedEnv, checker.algebra()));
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
            BabbleParser.CallContext ctx, Type expected) {
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
        java.lang.reflect.Type[] genericParams = cand.getGenericParameterTypes();
        for (int i = 0; i < argTypes.length; i++) {
            if (!deferred[i] && argTypes[i] != null) {
                Type paramPat = JavaTypeBridge.fromJavaType(genericParams[i], patternEnv, false, true);
                checker.algebra().relate(argTypes[i], paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store);
            }
        }
        Type ctxExpected = expected;
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

    private void checkJavaArgumentWidening(Executable candidate, Map<TypeVariable<?>, Type> env,
                                           List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes) {
        java.lang.reflect.Type[] genericParams = candidate.getGenericParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            if (i < argumentTypes.size()) {
                Type paramType = JavaTypeBridge.fromJavaType(genericParams[i], env, false, true);
                checker.coerce(arguments.get(i).expression(), argumentTypes.get(i), paramType);
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
        TypeAlgebra.ConstraintStore retStore = checker.algebra().store(solving, false);
        checker.algebra().relate(resultPattern, expBase, TypeAlgebra.RelateMode.SUBTYPE, retStore);
        Map<Type.TypeVar, Type> hints = new HashMap<>();
        for (Type.TypeVar tv : solving) {
            Type hint = retStore.solution(tv);
            if (hint != null) {
                hints.put(tv, hint);
            }
        }
        return hints;
    }

    /**
     * The solution of every variable of {@code variables} — its bounds, else the return hint,
     * promoted from {@code int} to {@code double} where the hint asks for it and every occurrence
     * can be coerced ({@code BIP010-D4}) — or null when one has none.
     */
    private Map<Type.TypeVar, Type> solveAll(List<Type.TypeVar> variables, TypeAlgebra.ConstraintStore store,
                                             Map<Type.TypeVar, Type> retHints, List<Type> paramPatterns,
                                             List<BabbleParser.ExpressionContext> argExprs) {
        Map<Type.TypeVar, Type> inferred = coalesceAndApplyDoubleWidening(variables, store, retHints, paramPatterns, argExprs);
        return inferred.keySet().containsAll(variables) ? inferred : null;
    }

    private Map<Type.TypeVar, Type> coalesceAndApplyDoubleWidening(
            List<Type.TypeVar> solvingList,
            TypeAlgebra.ConstraintStore store,
            Map<Type.TypeVar, Type> retHints,
            List<Type> paramPatterns,
            List<BabbleParser.ExpressionContext> argExprs) {
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        for (Type.TypeVar tv : solvingList) {
            Type resolved = store.solution(tv);
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
            if (current != null && isIntOrDouble(current) && TypeAlgebra.needsDoubleWidening(current, hint)
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
                    && fn.parameters().stream().noneMatch(p -> p.type().anyMatch(tv::equals))
                    && i < argExprs.size()) {
                BabbleParser.FunctionLiteralContext lit = checker.unwrapFunctionLiteral(argExprs.get(i));
                if (lit != null && lit.type() == null) {
                    continue;
                }
            }
            if (pat.anyMatch(tv::equals)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIntOrDouble(Type type) {
        Type base = Type.withoutNull(type);
        return base == Type.INT || base == Type.DOUBLE;
    }

    private static String[] argumentNames(List<BabbleParser.CallParameterContext> arguments) {
        String[] names = new String[arguments.size()];
        for (int i = 0; i < names.length; i++) {
            if (arguments.get(i).ID() != null) {
                names[i] = arguments.get(i).ID().getText();
            }
        }
        return names;
    }

    /**
     * The argument each parameter gets, by the rules of {@code Arguments} : the last argument
     * named after it, else the positional argument at its index ; -1 for none. {@code consumed}
     * marks the arguments taken.
     */
    private static int[] matchArguments(List<Type.Parameter> parameters, String[] names, boolean[] consumed) {
        int[] matched = new int[parameters.size()];
        for (int index = 0; index < parameters.size(); index++) {
            int found = -1;
            for (int i = 0; i < names.length; i++) {
                if (parameters.get(index).name().equals(names[i])) {
                    found = i;
                    consumed[i] = true;
                }
            }
            if (found < 0 && index < names.length && names[index] == null) {
                found = index;
                consumed[index] = true;
            }
            matched[index] = found;
        }
        return matched;
    }

    /** The parameter each argument binds to, or null. */
    static Type.Parameter[] mapArgumentsToParameters(List<Type.Parameter> parameters,
                                                     List<BabbleParser.CallParameterContext> arguments) {
        Type.Parameter[] mapping = new Type.Parameter[arguments.size()];
        int[] matched = matchArguments(parameters, argumentNames(arguments), new boolean[arguments.size()]);
        for (int index = 0; index < matched.length; index++) {
            if (matched[index] >= 0) {
                mapping[matched[index]] = parameters.get(index);
            }
        }
        return mapping;
    }

    private static Map<Type.TypeVar, Type> placeholderEnvWithHints(Set<Type.TypeVar> solving,
                                                                   TypeAlgebra.ConstraintStore store,
                                                                   Map<Type.TypeVar, Type> hints) {
        Map<Type.TypeVar, Type> env = new HashMap<>(solving.size());
        for (Type.TypeVar tv : solving) {
            Type resolved = store.solution(tv);
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
                if (p.type() != null && p.type().hasSyntheticTypeVar()) {
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
        return expectedBase != null && expectedBase.hasSyntheticTypeVar();
    }

}
