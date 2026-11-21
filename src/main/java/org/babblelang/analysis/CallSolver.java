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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
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
    private final Map<ParserRuleContext, Method> resolvedMethods = new java.util.IdentityHashMap<>();
    private final Map<ParserRuleContext, Constructor<?>> resolvedConstructors = new java.util.IdentityHashMap<>();
    private final Set<ParserRuleContext> resolvedVarArgs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private record JavaCallMatch<E extends Executable>(List<E> applicable, boolean varArgs) {
    }

    CallSolver(TypeChecker checker) {
        this.checker = checker;
    }

    /** Forgets what a walk resolved, before the next one. */
    void reset() {
        resolvedMethods.clear();
        resolvedConstructors.clear();
        resolvedVarArgs.clear();
    }

    private void recordConstructor(Executable winner, boolean varArgs, ParserRuleContext ctx) {
        if (winner instanceof Constructor<?> constructor) {
            resolvedConstructors.put(ctx, constructor);
            if (varArgs) {
                resolvedVarArgs.add(ctx);
            }
        }
    }

    private void recordMethod(Method winner, boolean varArgs, ParserRuleContext ctx) {
        resolvedMethods.put(ctx, winner);
        if (varArgs) {
            resolvedVarArgs.add(ctx);
        }
    }

    Method resolvedMethod(ParserRuleContext call) {
        return resolvedMethods.get(call);
    }

    Constructor<?> resolvedConstructor(ParserRuleContext call) {
        return resolvedConstructors.get(call);
    }

    boolean isResolvedVarArgs(ParserRuleContext call) {
        return resolvedVarArgs.contains(call);
    }

    /** The type of a call of {@code callee} ; {@code recordTarget} records the Java executable it resolves to (not for a union member). */
    Type call(Type callee, BabbleParser.CallContext ctx, Type expected, boolean recordTarget) {
        Type result = callInner(callee, ctx, expected, recordTarget);
        if (result != Type.ERROR && result != Type.NOTHING
                && ctx.expression() instanceof BabbleParser.SelectorContext sel
                && sel.expression() != null) {
            Type recv = checker.types().get(sel.expression());
            if (recv != null && Type.withoutNull(recv) instanceof Type.Frozen) {
                return Type.frozen(result);
            }
        }
        return result;
    }

    private Type callInner(Type callee, BabbleParser.CallContext ctx, Type expected, boolean recordTarget) {
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        if (callee == Type.ERROR || callee == Type.NOTHING) {
            return Type.ERROR;
        }
        if (callee == Type.PRINT) {
            Type appendable = new Type.JavaInstance(Appendable.class);
            for (BabbleParser.CallParameterContext argument : arguments) {
                if ("to".equals(ScopeAnalysis.callParamName(argument))) {
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
        if (callee instanceof Type.CoreCollection cc) {
            return coreCollectionCall(cc.kind(), cc.typeArguments().isEmpty() ? null : cc.typeArguments(), ctx, expected);
        }
        if (callee instanceof Type.ArrayOfFactory af) {
            return arrayOfCall(af, ctx, expected);
        }
        if (callee instanceof Type.Function function) {
            List<? extends ParserRuleContext> decls = checker.parameterDecls(function);
            boolean frozenBorrowSafe = function.effects().uniqueResult() || checker.isDeeplyImmutable(function.result());
            Set<Integer> relaxed = withCapturedCallbacks(function.forwardedEffectParams(), function.parameters(),
                    function.result());
            if (function.typeParameters().isEmpty()) {
                bind(function.parameters(), decls, arguments, ctx, frozenBorrowSafe, relaxed);
                return instantiateCapturedCallings(function.result(), function.parameters(), arguments);
            }
            Type result = genericBabbleCall(function.typeParameters(), function.parameters(), decls, function.result(),
                    function.effects().uniqueResult(), relaxed, arguments, ctx, ctx.expression().getText(), expected);
            return instantiateCapturedCallings(result, function.parameters(), arguments);
        }
        if (callee instanceof Type.ClassValue value && !value.node().isInterface()) {
            TypeNode node = value.node();
            List<Type.TypeVar> classVars = checker.annotator().typeVarsOf(node);
            List<Type.Parameter> parameters = checker.constructorParameters(node);
            List<? extends ParserRuleContext> decls = checker.parameterDecls(node);
            if (!value.typeArguments().isEmpty()) {
                Map<Type.TypeVar, Type> env = new HashMap<>();
                for (int i = 0; i < value.typeArguments().size(); i++) {
                    env.put(classVars.get(i), value.typeArguments().get(i));
                }
                if (parameters != null) {
                    List<Type.Parameter> substitutedParams = new ArrayList<>(parameters.size());
                    for (Type.Parameter p : parameters) {
                        substitutedParams.add(p.withType(checker.algebra().substitute(p.type(), env)));
                    }
                    bind(substitutedParams, decls, arguments, ctx, false);
                } else if (!arguments.isEmpty()) {
                    checker.warn("Too many parameters : 0 declared, " + arguments.size() + " given", ctx);
                    synthAll(arguments);
                }
                return new Type.Instance(node, value.typeArguments());
            }
            if (classVars.isEmpty()) {
                if (parameters != null) {
                    bind(parameters, decls, arguments, ctx, false);
                }
                return new Type.Instance(node);
            }
            if (parameters == null) {
                parameters = List.of();
            }
            Type patternReturn = new Type.Instance(node, List.copyOf(classVars));
            return genericBabbleCall(classVars, parameters, decls, patternReturn, false, Set.of(), node, arguments, ctx, node.name, expected);
        }
        if (callee instanceof Type.JavaClassValue value) {
            Class<?> java = value.type();
            if (!value.typeArguments().isEmpty()) {
                List<Type> explicitTypeArgs = value.typeArguments();
                if (java.isInterface() && arguments.size() == 1 && ScopeAnalysis.callParamName(arguments.get(0)) == null
                        && JavaTypes.isFunctionalInterface(java)) {
                    Type.JavaInstance samTarget = new Type.JavaInstance(java, explicitTypeArgs);
                    Type actualArg = checker.check(arguments.get(0).expression(), samTarget);
                    checker.subsumes(arguments.get(0).expression(), actualArg, samTarget);
                    return samTarget;
                }
                TypeVariable<?>[] classParams = java.getTypeParameters();
                Map<TypeVariable<?>, Type> env = new HashMap<>();
                for (int i = 0; i < classParams.length; i++) {
                    env.put(classParams[i], explicitTypeArgs.get(i));
                }
                List<Type> argumentTypes = new ArrayList<>();
                JavaCallMatch<Executable> match = javaCall(Arrays.asList(java.getConstructors()), java.getCanonicalName(),
                        env, arguments, ctx, expected, argumentTypes);
                List<Executable> matching = match.applicable();
                Executable winner = matching.isEmpty() ? null
                        : matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching, match.varArgs());
                if (winner != null) {
                    if (recordTarget) {
                        recordConstructor(winner, match.varArgs(), ctx);
                    }
                    checkJavaArgumentWidening(winner, env, arguments, argumentTypes, match.varArgs());
                }
                return new Type.JavaInstance(java, explicitTypeArgs);
            }
            if (java.isInterface() && arguments.size() == 1 && ScopeAnalysis.callParamName(arguments.get(0)) == null
                    && JavaTypes.isFunctionalInterface(java)) {
                return javaGenericSamCall(java, arguments.get(0).expression(), ctx, expected);
            }
            return javaConstructorCall(java, arguments, ctx, expected, recordTarget);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> all = JavaTypes.methods(method.owner(), method.name());
            Map<TypeVariable<?>, Type> receiverEnv = method.typeArguments().isEmpty()
                    ? Map.of() : JavaTypeBridge.javaTypeEnv(method.owner(), method.typeArguments());
            if (!method.methodTypeArguments().isEmpty()) {
                List<Type> explicitTypeArgs = method.methodTypeArguments();
                List<Method> candidates = matchingGenericJavaMethods(all, receiverEnv, explicitTypeArgs);
                if (candidates.isEmpty()) {
                    checker.warn(ctx.expression().getText() + " is not generic", ctx);
                    synthAll(arguments);
                    return Type.ERROR;
                }
                Map<TypeVariable<?>, Type> callEnv = new HashMap<>(receiverEnv);
                for (Method candidate : candidates) {
                    TypeVariable<?>[] mParams = candidate.getTypeParameters();
                    for (int i = 0; i < mParams.length; i++) {
                        callEnv.put(mParams[i], explicitTypeArgs.get(i));
                    }
                }
                List<Type> argumentTypes = new ArrayList<>();
                JavaCallMatch<Method> match = javaCall(candidates, method.owner().getCanonicalName() + "." + method.name(),
                        callEnv, arguments, ctx, expected, argumentTypes);
                List<Method> applicable = match.applicable();
                if (applicable.isEmpty()) {
                    return Type.ERROR;
                }
                Method best = applicable.size() == 1 ? applicable.get(0) : JavaTypeBridge.mostSpecific(applicable, match.varArgs());
                if (best == null) {
                    return Type.ERROR;
                }
                if (recordTarget) {
                    recordMethod(best, match.varArgs(), ctx);
                }
                Map<TypeVariable<?>, Type> bestEnv = new HashMap<>(receiverEnv);
                TypeVariable<?>[] bestParams = best.getTypeParameters();
                for (int i = 0; i < bestParams.length; i++) {
                    bestEnv.put(bestParams[i], explicitTypeArgs.get(i));
                }
                checkJavaArgumentWidening(best, bestEnv, arguments, argumentTypes, match.varArgs());
                return JavaTypeBridge.javaMethodResult(best, bestEnv, checker.algebra());
            }
            return javaMethodCall(all, method.owner().getCanonicalName() + "." + method.name(),
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
            checker.warn("Missing type : cannot infer type arguments for " + ScopeAnalysis.selectorName(ctx), ctx);
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
            checker.warn("Missing type : cannot infer type arguments for " + ScopeAnalysis.selectorName(ctx), ctx);
            return Type.ERROR;
        }
        Set<Type.TypeVar> solving = Set.copyOf(fn.typeParameters());
        Type.Function unparameterized = new Type.Function(List.of(), fn.parameters(), fn.result(), fn.effects(),
                fn.forwardedEffectParams(), fn.capturedCallings());
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
            checker.warn("Missing type : cannot infer type arguments for " + ScopeAnalysis.selectorName(ctx), ctx);
            return Type.ERROR;
        }
        if (checker.algebra().findPrimitiveArraySubstitution(unparameterized, inferred) != null) {
            return unparameterized;
        }
        return checker.algebra().substitute(unparameterized, inferred);
    }

    /**
     * The argument binding rules of {@code Arguments}, applied to a known signature.
     */
    void bind(List<Type.Parameter> parameters, List<? extends ParserRuleContext> declarations,
              List<BabbleParser.CallParameterContext> arguments, ParserRuleContext ctx, boolean frozenBorrowSafe) {
        bind(parameters, declarations, arguments, ctx, frozenBorrowSafe, Set.of());
    }

    /**
     * {@code forwarded} plus the callback parameters the result captures and forwards
     * ({@code compose}'s {@code f} and {@code g}, BIP017-D5) : like a {@code calling}
     * parameter, they accept a callback of any effects.
     */
    static Set<Integer> withCapturedCallbacks(Set<Integer> forwarded, List<Type.Parameter> parameters, Type result) {
        Set<String> captured = Type.Function.capturedCallingsOf(result);
        if (captured.isEmpty()) {
            return forwarded;
        }
        Set<Integer> relaxed = new LinkedHashSet<>(forwarded);
        for (int i = 0; i < parameters.size(); i++) {
            if (captured.contains(parameters.get(i).name()) && parameters.get(i).type() instanceof Type.Function) {
                relaxed.add(i);
            }
        }
        return relaxed;
    }

    /**
     * The result of a call, with the effects of the callbacks it was given folded into every
     * function type of the result that captures them (BIP017-D5) : {@code compose(h, k)} of an
     * {@code io h} is an {@code io} function. A callback the call doesn't give stays captured.
     */
    Type instantiateCapturedCallings(Type result, List<Type.Parameter> parameters,
                                     List<BabbleParser.CallParameterContext> arguments) {
        if (result == Type.ERROR || Type.Function.capturedCallingsOf(result).isEmpty()) {
            return result;
        }
        Map<String, Type> given = new HashMap<>();
        Type.Parameter[] argParams = mapArgumentsToParameters(parameters, arguments);
        for (int i = 0; i < arguments.size(); i++) {
            if (argParams[i] != null) {
                given.put(argParams[i].name(), checker.synth(arguments.get(i).expression()));
            }
        }
        return instantiateCapturedCallings(result, given);
    }

    private static Type instantiateCapturedCallings(Type type, Map<String, Type> given) {
        if (type instanceof Type.Union union) {
            List<Type> members = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                members.add(instantiateCapturedCallings(member, given));
            }
            return Type.union(members);
        }
        if (!(type instanceof Type.Function fn)) {
            return type;
        }
        Type result = instantiateCapturedCallings(fn.result(), given);
        EffectSet effects = fn.effects();
        Set<String> remaining = new java.util.TreeSet<>();
        for (String name : fn.capturedCallings()) {
            Type callback = given.get(name);
            if (callback == null) {
                remaining.add(name);
                continue;
            }
            for (Type member : Type.membersOf(callback)) {
                if (member instanceof Type.Function cb) {
                    effects = effects.combineForwarded(cb.effects());
                } else if (member != Type.NULL && member != Type.ERROR) {
                    // A callback of unknown effects (a Java functional object) : assume the worst.
                    effects = effects.combineForwarded(EffectSet.IO_DECLARED);
                }
            }
        }
        if (result == fn.result() && remaining.equals(fn.capturedCallings())) {
            return fn;
        }
        return new Type.Function(fn.typeParameters(), fn.parameters(), result, effects,
                fn.forwardedEffectParams(), remaining);
    }

    void bind(List<Type.Parameter> parameters, List<? extends ParserRuleContext> declarations,
              List<BabbleParser.CallParameterContext> arguments, ParserRuleContext ctx,
              boolean frozenBorrowSafe, Set<Integer> forwardedEffectParams) {
        String[] names = argumentNames(arguments);
        BindResult res = bindArguments(parameters, names);
        boolean bindingError = false;
        if (!res.unknownNames().isEmpty()) {
            checker.warn("No such parameter : " + String.join(", ", res.unknownNames()), ctx);
            bindingError = true;
        } else if (res.outOfPosition() != null) {
            checker.warn("Named argument out of position : " + res.outOfPosition(), ctx);
            bindingError = true;
        } else if (res.duplicate() != null) {
            checker.warn("Parameter given twice : " + res.duplicate(), ctx);
            bindingError = true;
        } else if (res.extraPositional() > 0) {
            int declared = res.keywordOnlyStart() < parameters.size() ? res.keywordOnlyStart() : parameters.size();
            int given = res.keywordOnlyStart() < parameters.size()
                    ? res.positionalCount()
                    : (res.distinctNameCount() > 0 ? res.positionalCount() + res.distinctNameCount() : arguments.size());
            checker.warn("Too many parameters : " + declared + " declared, " + given + " given", ctx);
            bindingError = true;
        }
        int[] matched = res.paramToArg();
        boolean[] checked = new boolean[arguments.size()];
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = matched[index];
            if (found < 0) {
                if (!parameter.optional() && !bindingError) {
                    checker.warn("Missing parameter : " + parameter.name(), ctx);
                }
                continue;
            }
            checked[found] = true;
            BabbleParser.ExpressionContext argument = arguments.get(found).expression();
            ParserRuleContext decl = declarations != null && index < declarations.size()
                    ? declarations.get(index) : null;
            Type expectedParam = parameter.type();
            if (forwardedEffectParams.contains(index) && expectedParam instanceof Type.Function cbType) {
                expectedParam = cbType.withEffects(cbType.effects().relaxedForForwardedCallback());
            }
            Type actual = checker.check(argument, expectedParam);
            bindArgument(decl, actual, expectedParam, frozenBorrowSafe && parameter.borrow() && !parameter.mut(), argument);
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!checked[i]) {
                checker.synth(arguments.get(i).expression());
            }
        }
    }

    private void bindArgument(ParserRuleContext decl, Type actual, Type expectedParam,
                              boolean borrowReadOnly, ParserRuleContext argument) {
        if (decl != null && checker.p7Params().contains(decl)) {
            Type inferredParam = checker.inferredParams().get(decl);
            boolean substitutedClassTypeVar = inferredParam != null && inferredParam.hasTypeVar()
                    && !expectedParam.equals(inferredParam);
            if (substitutedClassTypeVar) {
                if (borrowReadOnly) {
                    checker.subsumesBorrow(argument, actual, expectedParam);
                } else {
                    checker.subsumes(argument, actual, expectedParam);
                }
            } else {
                checker.recordInferredParam(decl, actual, argument);
                Type latest = checker.inferredParams().get(decl);
                if (latest != null) {
                    checker.coerce(argument, actual, latest);
                }
            }
        } else if (borrowReadOnly) {
            checker.subsumesBorrow(argument, actual, expectedParam);
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
            List<? extends ParserRuleContext> decls = checker.parameterDecls(function);
            ParserRuleContext decl = decls != null && !decls.isEmpty() ? decls.get(0) : null;
            boolean frozenBorrowSafe = function.effects().uniqueResult() || checker.isDeeplyImmutable(function.result());
            bindArgument(decl, argumentType, expectedParam,
                    frozenBorrowSafe && parameters.get(0).borrow() && !parameters.get(0).mut(), argument);
        }
        return function.result();
    }

    Type genericBabbleCall(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                           List<? extends ParserRuleContext> declarations,
                           Type resultPattern, boolean uniqueResult, List<BabbleParser.CallParameterContext> arguments,
                           BabbleParser.CallContext ctx, String description, Type expected) {
        return genericBabbleCall(solvingList, parameters, declarations, resultPattern, uniqueResult,
                Set.of(), null, arguments, ctx, description, expected);
    }

    Type genericBabbleCall(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                           List<? extends ParserRuleContext> declarations,
                           Type resultPattern, boolean uniqueResult, Set<Integer> forwardedEffectParams,
                           List<BabbleParser.CallParameterContext> arguments,
                           BabbleParser.CallContext ctx, String description, Type expected) {
        return genericBabbleCall(solvingList, parameters, declarations, resultPattern, uniqueResult,
                forwardedEffectParams, null, arguments, ctx, description, expected);
    }

    Type genericBabbleCall(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                           List<? extends ParserRuleContext> declarations,
                           Type resultPattern, boolean uniqueResult, Set<Integer> forwardedEffectParams,
                           TypeNode ctorNode,
                           List<BabbleParser.CallParameterContext> arguments,
                           BabbleParser.CallContext ctx, String description, Type expected) {
        Type.Parameter[] argParams = mapArgumentsToParameters(parameters, arguments);
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, true);
        Map<Type.TypeVar, Type> retHints = collectReturnHints(resultPattern, expected, solving);
        boolean frozenBorrowSafePattern = uniqueResult || checker.isDeeplyImmutable(resultPattern);

        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type pattern = argParams[i] != null ? argParams[i].type() : null;
            if (pattern instanceof Type.Function cbPattern && !forwardedEffectParams.isEmpty()) {
                int pIdx = parameters.indexOf(argParams[i]);
                if (forwardedEffectParams.contains(pIdx)) {
                    pattern = cbPattern.withEffects(cbPattern.effects().relaxedForForwardedCallback());
                }
            }
            paramPatterns.add(pattern);
            argExprs.add(arguments.get(i).expression());
        }
        Type[] argTypes = new Type[arguments.size()];
        boolean[] argFailedRelate = new boolean[arguments.size()];
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
                        boolean borrowReadOnly = frozenBorrowSafePattern
                                && argParams[i] != null && argParams[i].borrow() && !argParams[i].mut();
                        if (!checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowReadOnly)
                                && !TypeChecker.isUnresolved(actual)) {
                            argFailedRelate[i] = true;
                        }
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
                    boolean borrowReadOnly = frozenBorrowSafePattern
                            && argParams[i] != null && argParams[i].borrow() && !argParams[i].mut();
                    if (!checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowReadOnly)
                            && !TypeChecker.isUnresolved(actual)) {
                        argFailedRelate[i] = true;
                    }
                }
            }
        }
        int firstFailedArg = -1;
        for (int i = 0; i < argFailedRelate.length; i++) {
            if (argFailedRelate[i]) {
                firstFailedArg = i;
                break;
            }
        }
        Map<Type.TypeVar, Type> inferred = solveAll(solvingList, store, retHints, paramPatterns, argExprs);
        if (inferred == null) {
            if (Arrays.stream(argTypes).noneMatch(TypeChecker::isUnresolved)) {
                if (firstFailedArg >= 0) {
                    Map<Type.TypeVar, Type> partial = new HashMap<>();
                    for (Type.TypeVar tv : solvingList) {
                        Type s = store.solution(tv);
                        if (s != null) {
                            partial.put(tv, s);
                        }
                    }
                    Type expectedPat = checker.algebra().substitute(paramPatterns.get(firstFailedArg), partial);
                    checker.warn("Type error : expected " + expectedPat + ", got " + argTypes[firstFailedArg],
                            argExprs.get(firstFailedArg));
                } else {
                    checker.warn("Missing type : cannot infer type arguments for " + description, ctx);
                }
            }
            return Type.ERROR;
        }
        String badArrayMsg = findGenericBabbleArrayError(solvingList, parameters, resultPattern, ctorNode, inferred);
        if (badArrayMsg != null) {
            if (firstFailedArg >= 0) {
                checker.warn("Type error : expected " + paramPatterns.get(firstFailedArg) + ", got " + argTypes[firstFailedArg],
                        argExprs.get(firstFailedArg));
            } else {
                checker.warn(badArrayMsg, ctx);
            }
            return Type.ERROR;
        }
        Type substitutedResult = checker.algebra().substitute(resultPattern, inferred);
        boolean frozenBorrowSafe = uniqueResult || checker.isDeeplyImmutable(substitutedResult);
        List<Type.Parameter> substitutedParams = new ArrayList<>(parameters.size());
        for (Type.Parameter p : parameters) {
            substitutedParams.add(p.withType(checker.algebra().substitute(p.type(), inferred)));
        }
        bind(substitutedParams, declarations, arguments, ctx, frozenBorrowSafe, forwardedEffectParams);
        return substitutedResult;
    }

    private String findGenericBabbleArrayError(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                                               Type resultPattern, TypeNode ctorNode, Map<Type.TypeVar, Type> inferred) {
        for (Type.Parameter p : parameters) {
            TypeAlgebra.BadArraySubstitution bad = checker.algebra().findPrimitiveArraySubstitution(p.type(), inferred);
            if (bad != null) {
                return bad.message();
            }
        }
        TypeAlgebra.BadArraySubstitution badRes = checker.algebra().findPrimitiveArraySubstitution(resultPattern, inferred);
        if (badRes != null) {
            return badRes.message();
        }
        if (ctorNode != null) {
            List<Type> ordered = new ArrayList<>(solvingList.size());
            for (Type.TypeVar tv : solvingList) {
                ordered.add(inferred.get(tv));
            }
            JavaTypeBridge.BadJavaArrayTypeArg badNode = checker.annotator().checkBabbleNodeArrayTypeArgs(ctorNode, ordered);
            if (badNode != null) {
                return badNode.message();
            }
        }
        return null;
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
        JavaTypeBridge.BadJavaArrayTypeArg badArrSam = JavaTypeBridge.checkJavaClassArrayTypeArgs(java, solvedArgs, checker.algebra());
        if (badArrSam != null) {
            checker.warn(badArrSam.message(), argExpr);
            return Type.ERROR;
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
        JavaCallMatch<Executable> match = javaCall(Arrays.asList(java.getConstructors()), description, Map.of(), arguments, ctx, expected, argumentTypes);
        List<Executable> matching = match.applicable();
        boolean varArgs = match.varArgs();
        TypeVariable<?>[] classParams = java.getTypeParameters();
        Executable winner = matching.isEmpty() ? null : matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching, varArgs);
        if (classParams.length == 0) {
            if (winner != null) {
                checkJavaArgumentWidening(winner, Map.of(), arguments, argumentTypes, varArgs);
                if (recordTarget) {
                    recordConstructor(winner, varArgs, ctx);
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
            recordConstructor(winner, varArgs, ctx);
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
            checkJavaArgumentWidening(best, receiverEnv, arguments, argumentTypes, varArgs);
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
            recordMethod(best, varArgs, ctx);
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
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = varArgs
                    ? JavaTypeBridge.javaVarArgParamType(executable, i, patternEnv)
                    : JavaTypeBridge.javaExecutableParamType(executable, i, patternEnv);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            int pIdx = varArgs ? Math.min(i, fixed) : i;
            checker.algebra().relate(argumentTypes.get(i), paramPat, TypeAlgebra.RelateMode.ASSIGNABLE, store,
                    isJavaParamBorrow(executable, pIdx), !isJavaParamMut(executable, pIdx));
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
        JavaTypeBridge.BadJavaArrayTypeArg badArrJava = JavaTypeBridge.checkJavaExecutableArrayTypeArgs(
                executable, variables, solved, checker.algebra(), varArgs);
        if (badArrJava != null) {
            checker.warn(badArrJava.message(), ctx);
            return null;
        }
        checkJavaArgumentWidening(executable, solved, arguments, argumentTypes, varArgs);
        return solved;
    }

    private <E extends Executable> JavaCallMatch<E> javaCall(List<E> candidates, String description,
                                                             Map<TypeVariable<?>, Type> receiverEnv,
                                                             List<BabbleParser.CallParameterContext> arguments,
                                                             ParserRuleContext ctx, Type expected,
                                                             List<Type> outArgumentTypes) {
        List<E> fixedCandidates = new ArrayList<>();
        List<E> varArgCandidates = new ArrayList<>();
        for (E candidate : candidates) {
            if (candidate.getParameterCount() == arguments.size()) {
                fixedCandidates.add(candidate);
            }
            if (candidate.isVarArgs() && candidate.getParameterCount() > 0
                    && arguments.size() >= candidate.getParameterCount() - 1) {
                varArgCandidates.add(candidate);
            }
        }
        boolean varArgs = fixedCandidates.isEmpty() && !varArgCandidates.isEmpty();
        List<E> matching = new ArrayList<>(varArgs ? varArgCandidates : fixedCandidates);
        Type[] argTypes = new Type[arguments.size()];
        boolean[] deferred = new boolean[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            boolean isLambda = checker.unwrapFunctionLiteral(argument) != null;
            Type argExpected = JavaTypeBridge.expectedGenericJavaArgumentType(matching, i, isLambda, receiverEnv, varArgs);
            if (needsDeferredLambdaTyping(argument, argExpected)) {
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
            Map<TypeVariable<?>, Type> refinedEnv = refineJavaEnvFromArgs(matching, receiverEnv, argTypes, deferred, ctx, expected, varArgs);
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
        if (!varArgs && matching.isEmpty() && !varArgCandidates.isEmpty()) {
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
            checker.warn("No applicable overload of " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        } else if (matching.size() > 1
                && outArgumentTypes.stream().noneMatch(TypeChecker::isUnresolved)
                && JavaTypeBridge.mostSpecific(matching, varArgs) == null) {
            checker.warn("Ambiguous call to " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        }
        return new JavaCallMatch<>(matching, varArgs);
    }

    private <E extends Executable> Map<TypeVariable<?>, Type> refineJavaEnvFromArgs(
            List<E> matching, Map<TypeVariable<?>, Type> receiverEnv, Type[] argTypes, boolean[] deferred,
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
                                           List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes,
                                           boolean varArgs) {
        for (int i = 0; i < arguments.size(); i++) {
            if (i < argumentTypes.size()) {
                BabbleParser.ExpressionContext argExpr = arguments.get(i).expression();
                Type argType = argumentTypes.get(i);
                Type paramType = varArgs
                        ? JavaTypeBridge.javaVarArgParamType(candidate, i, env)
                        : JavaTypeBridge.javaExecutableParamType(candidate, i, env);
                if (!Type.containsNull(paramType) && Type.containsNull(argType) && !TypeChecker.isUnresolved(argType)) {
                    checker.subsumes(argExpr, argType, paramType);
                }
                checker.coerce(argExpr, argType, paramType);
            }
        }
    }

    // ---------------------------------------------------------------- constraint helpers

    private Map<Type.TypeVar, Type> collectReturnHints(Type resultPattern, Type actualExpected, Set<Type.TypeVar> solving) {
        if (resultPattern == null || actualExpected == null || actualExpected == Type.ERROR || resultPattern == Type.ERROR) {
            return Map.of();
        }
        Type patBase = resultPattern;
        Type expBase = Type.containsNull(resultPattern) ? actualExpected : Type.withoutNull(actualExpected);
        if (Type.containsNull(resultPattern) && Type.containsNull(actualExpected)) {
            patBase = Type.withoutNull(resultPattern);
            expBase = Type.withoutNull(actualExpected);
        }
        if (expBase == Type.NOTHING || patBase == Type.NOTHING || expBase instanceof Type.Union) {
            return Map.of();
        }
        TypeAlgebra.ConstraintStore retStore = checker.algebra().store(solving, false);
        checker.algebra().relate(patBase, expBase, TypeAlgebra.RelateMode.SUBTYPE, retStore);
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
            names[i] = ScopeAnalysis.callParamName(arguments.get(i));
        }
        return names;
    }

    record BindResult(int[] paramToArg, List<String> unknownNames, String outOfPosition,
                      String duplicate, int extraPositional, int positionalCount, int distinctNameCount,
                      int keywordOnlyStart) {
        boolean hasErrors(List<Type.Parameter> parameters) {
            if (!unknownNames.isEmpty() || outOfPosition != null || duplicate != null || extraPositional > 0) {
                return true;
            }
            for (int i = 0; i < parameters.size(); i++) {
                if (paramToArg[i] < 0 && !parameters.get(i).optional()) {
                    return true;
                }
            }
            return false;
        }

        ScopeAnalysis.ArgumentBinding toArgumentBinding(int argCount) {
            boolean reordered = false;
            int missingParamBits = 0;
            boolean seenOmitted = false;
            for (int p = 0; p < paramToArg.length; p++) {
                int a = paramToArg[p];
                if (a < 0) {
                    seenOmitted = true;
                    if (p < 32) {
                        missingParamBits |= (1 << p);
                    }
                } else {
                    if (a != p || seenOmitted) {
                        reordered = true;
                    }
                }
            }
            return new ScopeAnalysis.ArgumentBinding(paramToArg, argCount, reordered, missingParamBits);
        }
    }

    static int keywordOnlyStart(List<Type.Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).keywordOnly()) {
                return i;
            }
        }
        return parameters.size();
    }

    /**
     * Binds call arguments to parameters under {@code BIP021-D23} (C#'s rule) and keyword-only
     * parameters after {@code ...}.
     */
    static BindResult bindArguments(List<Type.Parameter> parameters, String[] names) {
        int n = parameters.size();
        int kwStart = keywordOnlyStart(parameters);
        int[] map = new int[n];
        Arrays.fill(map, -1);
        List<String> unknown = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        String firstOutOfPos = null;
        String outOfPosError = null;
        String dupError = null;
        int extra = 0;
        int posCount = 0;
        for (int j = 0; j < names.length; j++) {
            String name = names[j];
            if (name == null) {
                posCount++;
                if (firstOutOfPos != null) {
                    if (outOfPosError == null) {
                        outOfPosError = firstOutOfPos;
                    }
                } else if (j < kwStart) {
                    map[j] = j;
                } else {
                    extra++;
                }
            } else {
                boolean added = distinct.add(name);
                int pIdx = -1;
                for (int i = 0; i < n; i++) {
                    if (name.equals(parameters.get(i).name())) {
                        pIdx = i;
                        break;
                    }
                }
                if (pIdx < 0) {
                    if (added) {
                        unknown.add(name);
                    }
                } else {
                    if (map[pIdx] >= 0) {
                        if (dupError == null) {
                            dupError = name;
                        }
                    } else {
                        map[pIdx] = j;
                    }
                    if ((pIdx != j || pIdx >= kwStart) && firstOutOfPos == null) {
                        firstOutOfPos = name;
                    }
                }
            }
        }
        return new BindResult(map, unknown, outOfPosError, dupError, extra, posCount, distinct.size(), kwStart);
    }

    static ScopeAnalysis.ArgumentBinding computeArgumentBinding(List<Type.Parameter> parameters,
                                                                List<BabbleParser.CallParameterContext> arguments) {
        BindResult res = bindArguments(parameters, argumentNames(arguments));
        return res.hasErrors(parameters) ? null : res.toArgumentBinding(arguments.size());
    }

    /** The parameter each argument binds to, or null. */
    static Type.Parameter[] mapArgumentsToParameters(List<Type.Parameter> parameters,
                                                     List<BabbleParser.CallParameterContext> arguments) {
        Type.Parameter[] mapping = new Type.Parameter[arguments.size()];
        int[] matched = bindArguments(parameters, argumentNames(arguments)).paramToArg();
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
            for (BabbleParser.FnParamContext p : literal.fnParameters().fnParam()) {
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

    private EffectChecker.JavaCallProfile javaProfile(Executable exec) {
        EffectChecker ec = checker.effectChecker();
        if (ec == null || exec == null) {
            return null;
        }
        if (exec instanceof Constructor<?> ctor) {
            return ec.resolveJavaConstructorProfile(ctor.getDeclaringClass(), ctor);
        }
        if (exec instanceof Method m) {
            return ec.resolveJavaMethodProfile(m.getDeclaringClass(), m.getName(), m);
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
        int expectedArity = ("map".equals(kind) || "sortedmap".equals(kind) || "entry".equals(kind)) ? 2 : 1;
        List<BabbleParser.CallParameterContext> args = ctx.callParameters().callParameter();
        List<Type> expectedTypeArgs = extractExpectedCollectionArgs(expected, javaClass, expectedArity);
        if ("entry".equals(kind)) {
            return checkEntryCall(args, explicitArgs, expectedTypeArgs, ctx);
        }
        if ("map".equals(kind) || "sortedmap".equals(kind)) {
            return checkMapCall(kind, javaClass, args, explicitArgs, expectedTypeArgs, ctx);
        }
        return checkSeqCollectionCall(kind, javaClass, args, explicitArgs, expectedTypeArgs, ctx);
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
            case Type.Union u -> u.members().stream().anyMatch(CallSolver::dependsOnTypeVar);
            case Type.Intersection i -> i.members().stream().anyMatch(CallSolver::dependsOnTypeVar);
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

    private Type arrayOfCall(Type.ArrayOfFactory factory, BabbleParser.CallContext ctx, Type expected) {
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

    private Type checkSeqCollectionCall(String kind, Class<?> javaClass, List<BabbleParser.CallParameterContext> args,
                                        List<Type> explicitArgs, List<Type> expectedArgs,
                                        BabbleParser.CallContext ctx) {
        boolean isSorted = "sortedset".equals(kind);
        Type hintT = explicitArgs != null ? explicitArgs.get(0) : (expectedArgs != null ? expectedArgs.get(0) : null);
        int count = args.size();
        String n0 = count > 0 ? ScopeAnalysis.callParamName(args.get(0)) : null;
        String n1 = count > 1 ? ScopeAnalysis.callParamName(args.get(1)) : null;

        Type elemType = null;
        boolean hasComparator = false;
        boolean comparatorIsNull = false;

        if (count == 0) {
            elemType = hintT;
            if (elemType == null) {
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
            elemType = hintT;
            if (elemType == null) {
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
            elemType = checkComparatorArg(args.get(0).expression(), hintT, kind, ctx);
            if (elemType == Type.ERROR) {
                return Type.ERROR;
            }
            comparatorIsNull = checker.synth(args.get(0).expression()) == Type.NULL;
        } else if ((count == 1 && "from".equals(n0))
                || (count == 2 && isSorted && (("from".equals(n0) && "comparator".equals(n1)) || ("comparator".equals(n0) && "from".equals(n1))))) {
            int fromIdx = "from".equals(n0) ? 0 : 1;
            Type expectedIterable = hintT != null ? new Type.JavaInstance(Iterable.class, List.of(hintT)) : null;
            Type fromType = checker.check(args.get(fromIdx).expression(), expectedIterable);
            elemType = hintT != null ? hintT : extractIterableElement(fromType);
            if (elemType == null) {
                if (!TypeChecker.isUnresolved(fromType)) {
                    checker.warn("Type error : expected java.lang.Iterable, got " + fromType, args.get(fromIdx).expression());
                }
                if (count == 2) {
                    checker.synth(args.get(1 - fromIdx).expression());
                }
                return Type.ERROR;
            }
            checker.subsumesBorrow(args.get(fromIdx).expression(), fromType, new Type.JavaInstance(Iterable.class, List.of(elemType)));
            if (count == 2) {
                int compIdx = 1 - fromIdx;
                hasComparator = true;
                Type compPattern = Type.nullable(new Type.JavaInstance(Comparator.class, List.of(elemType)));
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
            List<BabbleParser.ExpressionContext> exprs = new ArrayList<>(count);
            List<Type> actuals = new ArrayList<>(count);
            for (BabbleParser.CallParameterContext arg : args) {
                exprs.add(arg.expression());
                actuals.add(checker.check(arg.expression(), hintT));
            }
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
                        return Type.ERROR;
                    }
                }
                for (int i = 0; i < count; i++) {
                    checker.coerce(exprs.get(i), actuals.get(i), elemType);
                }
            }
        }

        if (isSorted && (!hasComparator || comparatorIsNull) && !TypeChecker.isUnresolved(elemType)) {
            if (!isNaturallyComparable(elemType)) {
                checker.warn("Type error : " + elemType + " is not Comparable (pass comparator: ...)", ctx);
                return Type.ERROR;
            }
        }
        return new Type.JavaInstance(javaClass, List.of(elemType));
    }

    private Type checkMapCall(String kind, Class<?> javaClass, List<BabbleParser.CallParameterContext> args,
                              List<Type> explicitArgs, List<Type> expectedArgs,
                              BabbleParser.CallContext ctx) {
        boolean isSorted = "sortedmap".equals(kind);
        Type hintK = explicitArgs != null ? explicitArgs.get(0) : (expectedArgs != null ? expectedArgs.get(0) : null);
        Type hintV = explicitArgs != null ? explicitArgs.get(1) : (expectedArgs != null ? expectedArgs.get(1) : null);
        int count = args.size();
        String n0 = count > 0 ? ScopeAnalysis.callParamName(args.get(0)) : null;
        String n1 = count > 1 ? ScopeAnalysis.callParamName(args.get(1)) : null;

        Type keyType = null;
        Type valType = null;
        boolean hasComparator = false;
        boolean comparatorIsNull = false;

        if (count == 0) {
            keyType = hintK;
            valType = hintV;
            if (keyType == null || valType == null) {
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
            keyType = hintK;
            valType = hintV;
            if (keyType == null || valType == null) {
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
            keyType = checkComparatorArg(args.get(0).expression(), hintK, kind, ctx);
            if (keyType == Type.ERROR) {
                return Type.ERROR;
            }
            valType = hintV;
            if (valType == null) {
                checker.warn("Missing type : cannot infer type arguments for " + kind, ctx);
                return Type.ERROR;
            }
            comparatorIsNull = checker.synth(args.get(0).expression()) == Type.NULL;
        } else if ((count == 1 && "from".equals(n0))
                || (count == 2 && isSorted && (("from".equals(n0) && "comparator".equals(n1)) || ("comparator".equals(n0) && "from".equals(n1))))) {
            int fromIdx = "from".equals(n0) ? 0 : 1;
            Type expectedMap = (hintK != null && hintV != null) ? new Type.JavaInstance(Map.class, List.of(hintK, hintV)) : null;
            Type fromType = checker.check(args.get(fromIdx).expression(), expectedMap);
            List<Type> fromKV = extractMapEntries(fromType);
            keyType = hintK != null ? hintK : (fromKV != null ? fromKV.get(0) : null);
            valType = hintV != null ? hintV : (fromKV != null ? fromKV.get(1) : null);
            if (keyType == null || valType == null) {
                if (!TypeChecker.isUnresolved(fromType)) {
                    checker.warn("Type error : expected map, got " + fromType, args.get(fromIdx).expression());
                }
                if (count == 2) {
                    checker.synth(args.get(1 - fromIdx).expression());
                }
                return Type.ERROR;
            }
            checker.subsumesBorrow(args.get(fromIdx).expression(), fromType, new Type.JavaInstance(Map.class, List.of(keyType, valType)));
            if (count == 2) {
                int compIdx = 1 - fromIdx;
                hasComparator = true;
                Type compPattern = Type.nullable(new Type.JavaInstance(Comparator.class, List.of(keyType)));
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
            Type entryHint = (hintK != null && hintV != null) ? new Type.JavaInstance(Map.Entry.class, List.of(hintK, hintV)) : null;
            List<BabbleParser.ExpressionContext> exprs = new ArrayList<>(count);
            List<Type> actuals = new ArrayList<>(count);
            for (BabbleParser.CallParameterContext arg : args) {
                exprs.add(arg.expression());
                actuals.add(checker.check(arg.expression(), entryHint));
            }
            if (explicitArgs != null) {
                keyType = explicitArgs.get(0);
                valType = explicitArgs.get(1);
                Type expectedEntry = new Type.JavaInstance(Map.Entry.class, explicitArgs);
                for (int i = 0; i < count; i++) {
                    checker.subsumes(exprs.get(i), actuals.get(i), expectedEntry);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    Type argType = actuals.get(i);
                    if (TypeChecker.isUnresolved(argType)) {
                        return Type.ERROR;
                    }
                    Type projected = checker.algebra().projectToJava(argType, Map.Entry.class);
                    if (!(projected instanceof Type.JavaInstance entryInst) || entryInst.typeArguments().size() != 2) {
                        checker.warn("Type error : expected entry, got " + argType, exprs.get(i));
                        return Type.ERROR;
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
                            return Type.ERROR;
                        }
                    }
                }
            }
        }

        if (isSorted && (!hasComparator || comparatorIsNull) && !TypeChecker.isUnresolved(keyType)) {
            if (!isNaturallyComparable(keyType)) {
                checker.warn("Type error : " + keyType + " is not Comparable (pass comparator: ...)", ctx);
                return Type.ERROR;
            }
        }
        return new Type.JavaInstance(javaClass, List.of(keyType, valType));
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

    private Type extractIterableElement(Type type) {
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

    private List<Method> matchingGenericJavaMethods(List<Method> all, Map<TypeVariable<?>, Type> receiverEnv,
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

    private JavaTypeBridge.BadJavaArrayTypeArg firstJavaMethodArrayError(
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

    /** First-class generic type application {@code expr of typeArguments} (BIP012-D4). */
    Type applyTypes(BabbleParser.TypeApplyContext ctx, Type target) {
        List<Type> explicitTypeArgs = checker.annotator().resolveTypeArguments(ctx.typeArguments(), checker.scopeOf(ctx));
        boolean badTypeArg = explicitTypeArgs.contains(Type.ERROR);
        if (badTypeArg || target == Type.ERROR || target == Type.NOTHING) {
            return Type.ERROR;
        }
        if (target instanceof Type.CoreCollection cc) {
            if (!cc.typeArguments().isEmpty()) {
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            int expectedArity = ("map".equals(cc.kind()) || "sortedmap".equals(cc.kind()) || "entry".equals(cc.kind())) ? 2 : 1;
            if (explicitTypeArgs.size() != expectedArity) {
                checker.warn("Wrong number of type arguments : expected " + expectedArity
                        + ", got " + explicitTypeArgs.size(), ctx.typeArguments());
                return Type.ERROR;
            }
            return new Type.CoreCollection(cc.kind(), explicitTypeArgs);
        }
        if (target instanceof Type.Function fn) {
            if (fn.typeParameters().isEmpty()) {
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            if (fn.typeParameters().size() != explicitTypeArgs.size()) {
                checker.warn("Wrong number of type arguments : expected " + fn.typeParameters().size()
                        + ", got " + explicitTypeArgs.size(), ctx.typeArguments());
                return Type.ERROR;
            }
            Map<Type.TypeVar, Type> env = new HashMap<>();
            for (int i = 0; i < explicitTypeArgs.size(); i++) {
                env.put(fn.typeParameters().get(i), explicitTypeArgs.get(i));
            }
            TypeAlgebra.BadArraySubstitution badFnArr = null;
            for (Type.Parameter p : fn.parameters()) {
                badFnArr = checker.algebra().findPrimitiveArraySubstitution(p.type(), env);
                if (badFnArr != null) {
                    break;
                }
            }
            if (badFnArr == null) {
                badFnArr = checker.algebra().findPrimitiveArraySubstitution(fn.result(), env);
            }
            if (badFnArr != null) {
                int idx = badFnArr.cause() != null ? fn.typeParameters().indexOf(badFnArr.cause()) : 0;
                checker.warn(badFnArr.message(), TypeAnnotations.typeArgumentNode(ctx.typeArguments(), Math.max(0, idx)));
                return Type.ERROR;
            }
            List<Type.Parameter> substitutedParams = new ArrayList<>(fn.parameters().size());
            for (Type.Parameter p : fn.parameters()) {
                substitutedParams.add(p.withType(checker.algebra().substitute(p.type(), env)));
            }
            Type substitutedResult = checker.algebra().substitute(fn.result(), env);
            Type.Function specialized = new Type.Function(List.of(), substitutedParams, substitutedResult, fn.effects(),
                    fn.forwardedEffectParams(), fn.capturedCallings());
            BabbleParser.FunctionLiteralContext lit = checker.functionLiterals().get(fn);
            if (lit != null) {
                checker.functionLiterals().put(specialized, lit);
            }
            return specialized;
        }
        if (target instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            if (!cv.typeArguments().isEmpty()) {
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            TypeNode node = cv.node();
            List<Type.TypeVar> classVars = checker.annotator().typeVarsOf(node);
            if (classVars.size() != explicitTypeArgs.size()) {
                checker.warn("Wrong number of type arguments : expected " + classVars.size()
                        + ", got " + explicitTypeArgs.size(), ctx.typeArguments());
                return Type.ERROR;
            }
            JavaTypeBridge.BadJavaArrayTypeArg badClsArr = checker.annotator().checkBabbleNodeArrayTypeArgs(node, explicitTypeArgs);
            if (badClsArr != null) {
                checker.warn(badClsArr.message(), TypeAnnotations.typeArgumentNode(ctx.typeArguments(), badClsArr.index()));
                return Type.ERROR;
            }
            return new Type.ClassValue(node, explicitTypeArgs);
        }
        if (target instanceof Type.JavaClassValue jcv) {
            if (!jcv.typeArguments().isEmpty()) {
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            Class<?> java = jcv.type();
            TypeVariable<?>[] classParams = java.getTypeParameters();
            if (classParams.length != explicitTypeArgs.size()) {
                checker.warn("Wrong number of type arguments : expected " + classParams.length
                        + ", got " + explicitTypeArgs.size(), ctx.typeArguments());
                return Type.ERROR;
            }
            Map<TypeVariable<?>, Type> env = new HashMap<>();
            for (int i = 0; i < classParams.length; i++) {
                env.put(classParams[i], explicitTypeArgs.get(i));
            }
            for (int i = 0; i < classParams.length; i++) {
                if (!JavaTypeBridge.satisfiesJavaBounds(explicitTypeArgs.get(i), classParams[i], env, checker.algebra())) {
                    checker.warn("Type error : type argument " + explicitTypeArgs.get(i) + " does not satisfy bound "
                            + classParams[i].getName() + " of " + java.getCanonicalName(),
                            TypeAnnotations.typeArgumentNode(ctx.typeArguments(), i));
                    return Type.ERROR;
                }
            }
            JavaTypeBridge.BadJavaArrayTypeArg badJavaClsArr = JavaTypeBridge.checkJavaClassArrayTypeArgs(
                    java, explicitTypeArgs, checker.algebra());
            if (badJavaClsArr != null) {
                checker.warn(badJavaClsArr.message(), TypeAnnotations.typeArgumentNode(ctx.typeArguments(), badJavaClsArr.index()));
                return Type.ERROR;
            }
            return new Type.JavaClassValue(java, explicitTypeArgs);
        }
        if (target instanceof Type.JavaMethod method) {
            if (!method.methodTypeArguments().isEmpty()) {
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            List<Method> all = JavaTypes.methods(method.owner(), method.name());
            Map<TypeVariable<?>, Type> receiverEnv = method.typeArguments().isEmpty()
                    ? Map.of() : JavaTypeBridge.javaTypeEnv(method.owner(), method.typeArguments());
            List<Method> candidates = matchingGenericJavaMethods(all, receiverEnv, explicitTypeArgs);
            if (candidates.isEmpty()) {
                JavaTypeBridge.BadJavaArrayTypeArg badM = firstJavaMethodArrayError(all, receiverEnv, explicitTypeArgs);
                if (badM != null) {
                    checker.warn(badM.message(), TypeAnnotations.typeArgumentNode(ctx.typeArguments(), badM.index()));
                    return Type.ERROR;
                }
                checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
                return Type.ERROR;
            }
            return new Type.JavaMethod(method.owner(), method.typeArguments(), method.name(), explicitTypeArgs);
        }
        checker.warn(ctx.expression().getText() + " is not generic", ctx.typeArguments());
        return Type.ERROR;
    }

}
