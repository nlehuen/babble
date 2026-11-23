package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified polymorphic call, operator, and function-value instantiation solver
 * ({@code docs/design/bip010_type-checker-simplification.md} §4.3):
 * argument binding, iterative {@code open -> closed} deferred lambda rounds, Spine-Local
 * {@code Peek}, and coalescing with {@code BIP010-D4} double-widening.
 *
 * <p>Core collection/array factories, Java reflection call solving, and overload-set
 * resolution live in {@link CollectionCallChecker}, {@link JavaCallSolver}, and
 * {@link OverloadCallResolver} (BIP027-P1).
 */
final class CallSolver {
    private final TypeChecker checker;
    private final CollectionCallChecker collections;
    private final JavaCallSolver javaCalls;
    private final OverloadCallResolver overloads;
    /** The Java method or constructor each call resolved to (for effects and lowering). */
    private final Map<ParserRuleContext, Method> resolvedMethods = new java.util.IdentityHashMap<>();
    private final Map<ParserRuleContext, Constructor<?>> resolvedConstructors = new java.util.IdentityHashMap<>();
    private final Set<ParserRuleContext> resolvedVarArgs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<ParserRuleContext, LoweringTargets.SpreadAdapt[]> resolvedSpreadAdapts = new java.util.IdentityHashMap<>();
    private final Map<ParserRuleContext, PrimitiveKind> resolvedRestKinds = new java.util.IdentityHashMap<>();

    CallSolver(TypeChecker checker) {
        this.checker = checker;
        this.collections = new CollectionCallChecker(checker);
        this.javaCalls = new JavaCallSolver(checker, this);
        this.overloads = new OverloadCallResolver(checker, this);
    }

    /** Forgets what a walk resolved, before the next one. */
    void reset() {
        resolvedMethods.clear();
        resolvedConstructors.clear();
        resolvedVarArgs.clear();
        resolvedSpreadAdapts.clear();
        resolvedRestKinds.clear();
    }

    void recordConstructor(Executable winner, boolean varArgs, ParserRuleContext ctx) {
        if (winner instanceof Constructor<?> constructor) {
            resolvedConstructors.put(ctx, constructor);
            if (varArgs) {
                resolvedVarArgs.add(ctx);
            }
        }
    }

    void recordMethod(Method winner, boolean varArgs, ParserRuleContext ctx) {
        resolvedMethods.put(ctx, winner);
        if (varArgs) {
            resolvedVarArgs.add(ctx);
        }
    }

    void recordSpreadAdapts(ParserRuleContext ctx, LoweringTargets.SpreadAdapt[] adapts) {
        resolvedSpreadAdapts.put(ctx, adapts);
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

    LoweringTargets.SpreadAdapt[] resolvedSpreadAdapts(ParserRuleContext call) {
        return resolvedSpreadAdapts.get(call);
    }

    PrimitiveKind resolvedRestKind(ParserRuleContext call) {
        return resolvedRestKinds.get(call);
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
            return collections.coreCollectionCall(cc.kind(), cc.typeArguments().isEmpty() ? null : cc.typeArguments(), ctx, expected);
        }
        if (callee instanceof Type.ArrayOfFactory af) {
            return collections.arrayOfCall(af, ctx, expected);
        }
        if (callee instanceof Type.Overloaded overloaded) {
            return overloads.overloadedCall(overloaded, arguments, ctx, expected);
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
                JavaCallSolver.JavaCallMatch<Executable> match = javaCalls.javaCall(
                        Arrays.asList(java.getConstructors()), java.getCanonicalName(),
                        env, arguments, ctx, expected, argumentTypes);
                List<Executable> matching = match.applicable();
                Executable winner = matching.isEmpty() ? null
                        : matching.size() == 1 ? matching.get(0) : JavaTypeBridge.mostSpecific(matching, match.varArgs());
                if (winner != null) {
                    if (recordTarget) {
                        recordConstructor(winner, match.varArgs(), ctx);
                    }
                    javaCalls.checkJavaArgumentWidening(winner, env, arguments, argumentTypes, match.varArgs(), ctx);
                }
                return new Type.JavaInstance(java, explicitTypeArgs);
            }
            if (java.isInterface() && arguments.size() == 1 && ScopeAnalysis.callParamName(arguments.get(0)) == null
                    && JavaTypes.isFunctionalInterface(java)) {
                return javaCalls.javaGenericSamCall(java, arguments.get(0).expression(), ctx, expected);
            }
            return javaCalls.javaConstructorCall(java, arguments, ctx, expected, recordTarget);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> all = JavaTypes.methods(method.owner(), method.name());
            Map<TypeVariable<?>, Type> receiverEnv = method.typeArguments().isEmpty()
                    ? Map.of() : JavaTypeBridge.javaTypeEnv(method.owner(), method.typeArguments());
            if (!method.methodTypeArguments().isEmpty()) {
                List<Type> explicitTypeArgs = method.methodTypeArguments();
                List<Method> candidates = javaCalls.matchingGenericJavaMethods(all, receiverEnv, explicitTypeArgs);
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
                JavaCallSolver.JavaCallMatch<Method> match = javaCalls.javaCall(
                        candidates, method.owner().getCanonicalName() + "." + method.name(),
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
                javaCalls.checkJavaArgumentWidening(best, bestEnv, arguments, argumentTypes, match.varArgs(), ctx);
                return JavaTypeBridge.javaMethodResult(best, bestEnv, checker.algebra());
            }
            return javaCalls.javaMethodCall(all, method.owner().getCanonicalName() + "." + method.name(),
                    receiverEnv, arguments, ctx, expected, recordTarget);
        }
        checker.warn(ctx.expression().getText() + " is not callable", ctx);
        return Type.ERROR;
    }


    /**
     * Instantiates an unapplied generic function value {@code fn} against a monomorphic function or
     * Java SAM {@code expected}, or preserves {@code fn} polymorphically otherwise ({@code BIP021-D20}).
     */
    Type instantiateFunctionValue(Type.Function fn, BabbleParser.SelectorContext ctx, Type expected) {
        if (expected == null || expected == Type.ERROR || expected == Type.ANY || expected == Type.NULLABLE_ANY) {
            return fn;
        }
        Type target = Type.withoutNull(expected);
        Type.Function expectedFn = null;
        if (target instanceof Type.Function f) {
            if (!f.typeParameters().isEmpty()) {
                return fn;
            }
            expectedFn = f;
        } else if (JavaTypeBridge.resolveJavaSam(target) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            expectedFn = JavaTypeBridge.samFunctionType(valid.samType(), checker.effectChecker());
        }
        if (expectedFn == null) {
            return fn;
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
            return fn;
        }
        Map<Type.TypeVar, Type> inferred = solveAll(fn.typeParameters(), store, Map.of(), List.of(), List.of());
        if (inferred == null) {
            checker.warn("Missing type : cannot infer type arguments for " + ScopeAnalysis.selectorName(ctx), ctx);
            return Type.ERROR;
        }
        if (!checkBabbleBounds(fn.typeParameters(), inferred, ctx, null)) {
            return Type.ERROR;
        }
        if (checker.algebra().findPrimitiveArraySubstitution(unparameterized, inferred) != null) {
            return unparameterized;
        }
        return checker.algebra().substitute(unparameterized, inferred);
    }

    /** Verifies every declared upper bound on {@code vars} against {@code env} (BIP021-D19). */
    private boolean checkBabbleBounds(List<Type.TypeVar> vars, Map<Type.TypeVar, Type> env,
                                      ParserRuleContext ctx, BabbleParser.TypeArgumentsContext typeArgsCtx) {
        for (int i = 0; i < vars.size(); i++) {
            Type.TypeVar tv = vars.get(i);
            if (tv.bound() != null && tv.bound() != Type.ERROR) {
                Type arg = env.get(tv);
                Type subBound = checker.algebra().substitute(tv.bound(), env);
                if (arg != null && !TypeChecker.isUnresolved(arg) && subBound != Type.ERROR
                        && !checker.algebra().assignable(arg, subBound)) {
                    ParserRuleContext errNode = typeArgsCtx != null ? TypeAnnotations.typeArgumentNode(typeArgsCtx, i) : ctx;
                    checker.warn("Type error : type argument " + arg + " does not satisfy bound " + subBound, errNode);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * When a solved type variable with a Java SAM / SAM+markers bound holds a type that may contain
     * a Babble callable (directly, frozen, or inside a union/nullable), promotes it to the
     * substituted SAM bound (preserving non-nullness when {@code arg} is non-null).
     */
    Type promoteCallableToSamBound(Type arg, Type rawBound, Map<Type.TypeVar, Type> env) {
        if (arg == null || rawBound == null || rawBound == Type.ERROR || !TypeAlgebra.mayContainBabbleCallable(arg)) {
            return arg;
        }
        Type subBound = checker.algebra().substitute(rawBound, env);
        Type targetBound = Type.containsNull(arg) ? subBound : Type.withoutNull(subBound);
        if (JavaTypeBridge.resolveJavaSam(targetBound) instanceof JavaTypeBridge.JavaSamResolution.Valid
                && checker.algebra().assignable(arg, targetBound)) {
            return targetBound;
        }
        return arg;
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
        LoweringTargets.SpreadAdapt[] adapts = null;
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            if (parameter.rest()) {
                resolvedRestKinds.put(ctx, PrimitiveKind.of(parameter.type()));
                boolean borrowReadOnly = frozenBorrowSafe && parameter.borrow() && !parameter.mut();
                Type expectedElem = parameter.type();
                if (forwardedEffectParams.contains(index) && expectedElem instanceof Type.Function cbType) {
                    expectedElem = cbType.withEffects(cbType.effects().relaxedForForwardedCallback());
                }
                for (int argIdx : res.restArgs()) {
                    checked[argIdx] = true;
                    BabbleParser.CallParameterContext argCtx = arguments.get(argIdx);
                    BabbleParser.ExpressionContext argument = argCtx.expression();
                    if (ScopeAnalysis.isSpread(argCtx)) {
                        Type actual = checker.check(argument, null);
                        if (!TypeChecker.isUnresolved(actual)) {
                            Type elem = extractSequenceElement(actual);
                            if (elem == null) {
                                checker.warn("Type error : expected iterable or array, got " + actual, argument);
                            } else if (!TypeChecker.isUnresolved(elem) && !TypeChecker.isUnresolved(expectedElem)) {
                                LoweringTargets.JavaSamTarget elemSam = samTargetForElement(expectedElem);
                                boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(elem);
                                TypeAlgebra.RelateMode mode = needsSamAdapt
                                        ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
                                boolean ok = checker.algebra().relate(elem, expectedElem,
                                        mode, null, borrowReadOnly, false);
                                if (!ok) {
                                    checker.warn("Type error : expected " + expectedElem + " elements, got " + elem, argument);
                                } else if (needsSamAdapt) {
                                    if (adapts == null) {
                                        adapts = new LoweringTargets.SpreadAdapt[arguments.size()];
                                    }
                                    adapts[argIdx] = LoweringTargets.SpreadAdapt.toIterable(elemSam);
                                }
                            }
                        }
                    } else {
                        Type actual = checker.check(argument, expectedElem);
                        bindArgument(null, actual, expectedElem, borrowReadOnly, argument);
                    }
                }
                continue;
            }
            int found = matched[index];
            if (found < 0) {
                if (!parameter.optional() && !bindingError) {
                    checker.warn("Missing parameter : " + parameter.name(), ctx);
                }
                continue;
            }
            checked[found] = true;
            BabbleParser.CallParameterContext argCtx = arguments.get(found);
            BabbleParser.ExpressionContext argument = argCtx.expression();
            ParserRuleContext decl = declarations != null && index < declarations.size()
                    ? declarations.get(index) : null;
            Type expectedParam = parameter.type();
            if (forwardedEffectParams.contains(index) && expectedParam instanceof Type.Function cbType) {
                expectedParam = cbType.withEffects(cbType.effects().relaxedForForwardedCallback());
            }
            if (ScopeAnalysis.isSpread(argCtx)) {
                Type actual = checker.check(argument, null);
                boolean borrowReadOnly = frozenBorrowSafe && parameter.borrow() && !parameter.mut();
                LoweringTargets.SpreadAdapt adapt = checkSpreadIntoParam(
                        argument, actual, parameter.name(), expectedParam,
                        parameter.borrow(), parameter.mut(), borrowReadOnly);
                if (adapt != null) {
                    if (adapts == null) {
                        adapts = new LoweringTargets.SpreadAdapt[arguments.size()];
                    }
                    adapts[found] = adapt;
                }
            } else {
                Type actual = checker.check(argument, expectedParam);
                bindArgument(decl, actual, expectedParam, frozenBorrowSafe && parameter.borrow() && !parameter.mut(), argument);
            }
        }
        if (adapts != null) {
            resolvedSpreadAdapts.put(ctx, adapts);
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!checked[i]) {
                checker.synth(arguments.get(i).expression());
            }
        }
    }

    /** Element type of an iterable or array (never a bare {@code Iterator} or nullable sequence). */
    Type extractSequenceElement(Type type) {
        if (type == null || TypeChecker.isUnresolved(type) || Type.containsNull(type)) {
            return null;
        }
        boolean frozen = type instanceof Type.Frozen;
        Type unwrapped = TypeAlgebra.unwrapFrozen(type);
        Type elem = unwrapped instanceof Type.Array arr ? arr.element() : collections.extractIterableElement(unwrapped);
        return frozen && elem != null ? Type.frozen(elem) : elem;
    }

    /** Whether a parameter of type {@code target} accepts a spread argument ({@code BIP021-D7}). */
    boolean isSequenceTarget(Type target) {
        if (target == null || TypeChecker.isUnresolved(target)) {
            return false;
        }
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(target));
        if (base instanceof Type.Array) {
            return true;
        }
        return checker.algebra().projectToJava(base, Iterable.class) != null;
    }

    /** Element type pattern of a sequence conversion target ({@code array}, {@code list}, or {@code Iterable}). */
    Type sequenceTargetElement(Type target) {
        if (target == null || TypeChecker.isUnresolved(target)) {
            return null;
        }
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(target));
        if (base instanceof Type.Array arr) {
            return arr.element();
        }
        if (base instanceof Type.JavaInstance ji && ji.typeArguments().size() == 1
                && (ji.type() == Iterable.class || ji.type() == List.class)) {
            return ji.typeArguments().get(0);
        }
        return null;
    }

    static LoweringTargets.JavaSamTarget samTargetForElement(Type elemType) {
        if (JavaTypeBridge.resolveJavaSam(elemType) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid.toTarget();
        }
        return null;
    }

    /**
     * Classifies the adaptation needed to spread {@code sourceType} into a non-rest parameter of
     * type {@code targetParamType}, returning null if incompatible (without reporting errors).
     */
    LoweringTargets.SpreadAdapt classifySpread(Type sourceType, Type targetParamType,
                                             boolean targetBorrowed, boolean targetMut,
                                             boolean borrowReadOnly) {
        if (TypeChecker.isUnresolved(sourceType) || TypeChecker.isUnresolved(targetParamType)) {
            return null;
        }
        if (!isSequenceTarget(targetParamType)) {
            return null;
        }
        Type sourceElem = extractSequenceElement(sourceType);
        if (sourceElem == null) {
            return null;
        }
        Type targetElem = sequenceTargetElement(targetParamType);
        LoweringTargets.JavaSamTarget elemSam = targetElem != null ? samTargetForElement(targetElem) : null;
        boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(sourceElem);
        if (!needsSamAdapt && checker.algebra().relate(sourceType, targetParamType, TypeAlgebra.RelateMode.ASSIGNABLE,
                null, borrowReadOnly, targetBorrowed && !targetMut)) {
            return LoweringTargets.SpreadAdapt.AS_IS;
        }
        Type baseTarget = TypeAlgebra.unwrapFrozen(Type.withoutNull(targetParamType));
        if (baseTarget instanceof Type.JavaInstance ji && ji.type() == Iterable.class && ji.typeArguments().size() == 1) {
            if (checker.algebra().relate(sourceElem, targetElem, TypeAlgebra.RelateMode.SUBTYPE, null, borrowReadOnly, false)) {
                if (sourceType instanceof Type.Frozen && !borrowReadOnly
                        && !(Type.withoutNull(targetParamType) instanceof Type.Frozen)) {
                    return LoweringTargets.SpreadAdapt.toList(false, needsSamAdapt ? elemSam : null);
                }
                return LoweringTargets.SpreadAdapt.toIterable(needsSamAdapt ? elemSam : null);
            }
            return null;
        }
        if (baseTarget instanceof Type.JavaInstance ji && ji.type() == List.class && ji.typeArguments().size() == 1) {
            if (checker.algebra().relate(sourceElem, targetElem, TypeAlgebra.RelateMode.SUBTYPE, null, borrowReadOnly, false)) {
                if (!needsSamAdapt && TypeAlgebra.unwrapFrozen(sourceType) instanceof Type.Array && borrowReadOnly) {
                    return LoweringTargets.SpreadAdapt.TO_LIST_BORROWED;
                }
                return LoweringTargets.SpreadAdapt.toList(false, needsSamAdapt ? elemSam : null);
            }
            return null;
        }
        if (baseTarget instanceof Type.Array arr) {
            if (checker.algebra().relate(sourceElem, targetElem, TypeAlgebra.RelateMode.ASSIGNABLE, null, false, false)) {
                Class<?> arrCls = JavaTypeBridge.checkedArrayClass(arr);
                Class<?> comp = arrCls != null ? arrCls.getComponentType() : Object.class;
                return LoweringTargets.SpreadAdapt.toArray(comp, needsSamAdapt ? elemSam : null);
            }
            return null;
        }
        return null;
    }

    /** Validates a spread into a non-rest parameter, emitting {@code BIP021-D7} diagnostics on failure. */
    private LoweringTargets.SpreadAdapt checkSpreadIntoParam(BabbleParser.ExpressionContext argument, Type actual,
                                                           String paramName, Type expectedParam,
                                                           boolean targetBorrowed, boolean targetMut,
                                                           boolean borrowReadOnly) {
        if (TypeChecker.isUnresolved(actual) || TypeChecker.isUnresolved(expectedParam)) {
            return null;
        }
        if (!isSequenceTarget(expectedParam)) {
            checker.warn("Spread into a parameter that takes no sequence : " + paramName, argument);
            return null;
        }
        Type sourceElem = extractSequenceElement(actual);
        if (sourceElem == null) {
            checker.warn("Type error : expected iterable or array, got " + actual, argument);
            return null;
        }
        LoweringTargets.SpreadAdapt adapt = classifySpread(actual, expectedParam, targetBorrowed, targetMut, borrowReadOnly);
        if (adapt != null) {
            return adapt;
        }
        Type targetElem = sequenceTargetElement(expectedParam);
        if (targetElem != null) {
            checker.warn("Type error : expected " + targetElem + " elements, got " + sourceElem, argument);
        } else {
            checker.subsumes(argument, actual, expectedParam);
        }
        return null;
    }

    void bindArgument(ParserRuleContext decl, Type actual, Type expectedParam,
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
        if (callee instanceof Type.Overloaded set) {
            return overloads.overloadedOperatorCall(receiver, method, set, argument, argumentType, at, receiverText);
        }
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
            return function.result();
        }
        if (given > parameters.size()) {
            checker.warn("Too many parameters : " + parameters.size() + " declared, " + given + " given", at);
            return function.result();
        }
        Type resultType = function.result();
        Type expectedParam = argument != null ? parameters.get(0).type() : null;
        if (!function.typeParameters().isEmpty()) {
            Map<Type.TypeVar, Type> env = new java.util.LinkedHashMap<>();
            if (argument != null) {
                TypeAlgebra.ConstraintStore store = checker.algebra().store(Set.copyOf(function.typeParameters()), true);
                boolean borrowReadOnly = parameters.get(0).borrow() && !parameters.get(0).mut();
                checker.algebra().relate(argumentType, expectedParam, TypeAlgebra.RelateMode.ASSIGNABLE,
                        store, borrowReadOnly, false);
                for (Type.TypeVar tv : function.typeParameters()) {
                    Type sol = store.solution(tv);
                    if (sol != null && sol != Type.ERROR) {
                        env.put(tv, sol);
                    }
                }
                for (Type.TypeVar tv : function.typeParameters()) {
                    Type arg = env.get(tv);
                    Type promoted = promoteCallableToSamBound(arg, tv.bound(), env);
                    if (promoted != arg) {
                        env.put(tv, promoted);
                    }
                }
                if (!checkBabbleBounds(function.typeParameters(), env, at, null)) {
                    return Type.ERROR;
                }
                String badArrayMsg = findGenericBabbleArrayError(function.typeParameters(), parameters, resultType, null, env);
                if (badArrayMsg != null) {
                    checker.warn(badArrayMsg, at);
                    return Type.ERROR;
                }
                expectedParam = checker.algebra().substitute(expectedParam, env);
                resultType = checker.algebra().substitute(resultType, env);
            }
            Set<Type.TypeVar> ownVars = Set.copyOf(function.typeParameters());
            if (resultType.anyMatch(t -> t instanceof Type.TypeVar tv && ownVars.contains(tv))) {
                checker.warn("Missing type : cannot infer type arguments for " + receiverText + "." + method, at);
                return Type.ERROR;
            }
        }
        if (argument != null) {
            List<? extends ParserRuleContext> decls = checker.parameterDecls(function);
            ParserRuleContext decl = decls != null && !decls.isEmpty() ? decls.get(0) : null;
            boolean frozenBorrowSafe = function.effects().uniqueResult() || checker.isDeeplyImmutable(resultType);
            bindArgument(decl, argumentType, expectedParam,
                    frozenBorrowSafe && parameters.get(0).borrow() && !parameters.get(0).mut(), argument);
        }
        return resultType;
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
                boolean spread = ScopeAnalysis.isSpread(arguments.get(i));
                Type expectedArg = !spread && paramPattern != null ? checker.algebra().substitute(paramPattern, env) : null;
                if (!needsDeferredLambdaTyping(argExprs.get(i), expectedArg)) {
                    Type actual = checker.check(argExprs.get(i), expectedArg);
                    argTypes[i] = actual;
                    if (paramPattern != null) {
                        boolean borrowReadOnly = frozenBorrowSafePattern
                                && argParams[i] != null && argParams[i].borrow() && !argParams[i].mut();
                        if (!relateGenericArg(actual, paramPattern, argParams[i], spread, store, borrowReadOnly)
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
                boolean spread = ScopeAnalysis.isSpread(arguments.get(i));
                Type expectedArg = !spread && paramPattern != null ? checker.algebra().substitute(paramPattern, finalHintEnv) : null;
                Type actual = checker.check(argExprs.get(i), expectedArg);
                argTypes[i] = actual;
                if (paramPattern != null) {
                    boolean borrowReadOnly = frozenBorrowSafePattern
                            && argParams[i] != null && argParams[i].borrow() && !argParams[i].mut();
                    if (!relateGenericArg(actual, paramPattern, argParams[i], spread, store, borrowReadOnly)
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
        for (Type.TypeVar tv : solvingList) {
            Type arg = inferred.get(tv);
            Type promoted = promoteCallableToSamBound(arg, tv.bound(), inferred);
            if (promoted != arg) {
                inferred.put(tv, promoted);
            }
        }
        if (!checkBabbleBounds(solvingList, inferred, ctx, null)) {
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

    private boolean relateGenericArg(Type actual, Type paramPattern, Type.Parameter param, boolean spread,
                                     TypeAlgebra.ConstraintStore store, boolean borrowReadOnly) {
        if (!spread) {
            return checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowReadOnly);
        }
        Type sourceElem = extractSequenceElement(actual);
        if (sourceElem == null) {
            return false;
        }
        if (param != null && param.rest()) {
            LoweringTargets.JavaSamTarget elemSam = samTargetForElement(paramPattern);
            boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(sourceElem);
            TypeAlgebra.RelateMode mode = needsSamAdapt
                    ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
            return checker.algebra().relate(sourceElem, paramPattern, mode, store, borrowReadOnly);
        }
        Type targetElem = sequenceTargetElement(paramPattern);
        if (targetElem != null) {
            Type baseTarget = TypeAlgebra.unwrapFrozen(Type.withoutNull(paramPattern));
            TypeAlgebra.RelateMode mode = baseTarget instanceof Type.Array
                    ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
            return checker.algebra().relate(sourceElem, targetElem, mode, store, borrowReadOnly);
        }
        return checker.algebra().relate(actual, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowReadOnly);
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

    // ---------------------------------------------------------------- constraint helpers

    Map<Type.TypeVar, Type> collectReturnHints(Type resultPattern, Type actualExpected, Set<Type.TypeVar> solving) {
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
    Map<Type.TypeVar, Type> solveAll(List<Type.TypeVar> variables, TypeAlgebra.ConstraintStore store,
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
                      int keywordOnlyStart, int restParam, int[] restArgs) {
        boolean hasErrors(List<Type.Parameter> parameters) {
            if (!unknownNames.isEmpty() || outOfPosition != null || duplicate != null || extraPositional > 0) {
                return true;
            }
            for (int i = 0; i < parameters.size(); i++) {
                if (i == restParam) {
                    continue;
                }
                if (paramToArg[i] < 0 && !parameters.get(i).optional()) {
                    return true;
                }
            }
            return false;
        }

        LoweringTargets.ArgumentBinding toArgumentBinding(List<BabbleParser.CallParameterContext> arguments,
                                                        PrimitiveKind restElementKind,
                                                        LoweringTargets.SpreadAdapt[] argAdapts) {
            int argCount = arguments.size();
            boolean reordered = restParam >= 0;
            int missingParamBits = 0;
            boolean seenOmitted = false;
            for (int p = 0; p < paramToArg.length; p++) {
                if (p == restParam) {
                    continue;
                }
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
            boolean[] restSpreads = null;
            if (restParam >= 0) {
                restSpreads = new boolean[restArgs.length];
                for (int i = 0; i < restArgs.length; i++) {
                    restSpreads[i] = ScopeAnalysis.isSpread(arguments.get(restArgs[i]));
                }
            }
            return new LoweringTargets.ArgumentBinding(paramToArg, argCount, reordered, missingParamBits,
                    restParam, restArgs, restSpreads,
                    restElementKind != null ? restElementKind : PrimitiveKind.NONE,
                    argAdapts);
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

    static int restParamIndex(List<Type.Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).rest()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Binds call arguments to parameters under {@code BIP021-D23} (C#'s rule), rest parameters
     * ({@code BIP021-D5}), and keyword-only parameters after {@code ...}.
     */
    static BindResult bindArguments(List<Type.Parameter> parameters, String[] names) {
        int n = parameters.size();
        int kwStart = keywordOnlyStart(parameters);
        int restIdx = restParamIndex(parameters);
        int[] map = new int[n];
        Arrays.fill(map, -1);
        List<String> unknown = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        List<Integer> restArgList = restIdx >= 0 ? new ArrayList<>() : null;
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
                } else if (restIdx >= 0 && j >= restIdx) {
                    restArgList.add(j);
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
                if (pIdx < 0 || pIdx == restIdx) {
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
        int[] restArgs = restArgList == null ? null : restArgList.stream().mapToInt(Integer::intValue).toArray();
        return new BindResult(map, unknown, outOfPosError, dupError, extra, posCount, distinct.size(), kwStart, restIdx, restArgs);
    }

    static LoweringTargets.ArgumentBinding computeArgumentBinding(List<Type.Parameter> parameters,
                                                                List<BabbleParser.CallParameterContext> arguments,
                                                                PrimitiveKind restElementKind,
                                                                LoweringTargets.SpreadAdapt[] argAdapts) {
        BindResult res = bindArguments(parameters, argumentNames(arguments));
        if (res.hasErrors(parameters)) {
            return null;
        }
        PrimitiveKind rk = restElementKind;
        if (rk == null && res.restParam() >= 0) {
            rk = PrimitiveKind.of(parameters.get(res.restParam()).type());
        }
        return res.toArgumentBinding(arguments, rk, argAdapts);
    }

    /** The parameter each argument binds to, or null. */
    static Type.Parameter[] mapArgumentsToParameters(List<Type.Parameter> parameters,
                                                     List<BabbleParser.CallParameterContext> arguments) {
        Type.Parameter[] mapping = new Type.Parameter[arguments.size()];
        BindResult res = bindArguments(parameters, argumentNames(arguments));
        int[] matched = res.paramToArg();
        for (int index = 0; index < matched.length; index++) {
            if (matched[index] >= 0) {
                mapping[matched[index]] = parameters.get(index);
            }
        }
        if (res.restParam() >= 0 && res.restArgs() != null) {
            Type.Parameter restParam = parameters.get(res.restParam());
            for (int argIdx : res.restArgs()) {
                mapping[argIdx] = restParam;
            }
        }
        return mapping;
    }

    static Map<Type.TypeVar, Type> placeholderEnvWithHints(Set<Type.TypeVar> solving,
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

    boolean needsDeferredLambdaTyping(BabbleParser.ExpressionContext argExpr, Type expectedArg) {
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
        if (JavaTypeBridge.resolveJavaSam(expectedBase) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            Type.Function sam = JavaTypeBridge.samFunctionType(valid.samType(), checker.effectChecker());
            if (sam != null) {
                return paramInputHasSyntheticTypeVar(sam);
            }
        }
        return expectedBase != null && expectedBase.hasSyntheticTypeVar();
    }

    private void synthAll(List<BabbleParser.CallParameterContext> args) {
        for (BabbleParser.CallParameterContext arg : args) {
            checker.synth(arg.expression());
        }
    }

    /** First-class generic type application {@code expr of typeArguments} (BIP012-D4). */
    Type applyTypes(BabbleParser.TypeApplyContext ctx, Type target) {
        List<Type> explicitTypeArgs = checker.annotator().resolveTypeArguments(ctx.typeArguments(), checker.scopeOf(ctx));
        boolean badTypeArg = explicitTypeArgs.contains(Type.ERROR);
        if (badTypeArg || target == Type.ERROR || target == Type.NOTHING) {
            return Type.ERROR;
        }
        if (target instanceof Type.Overloaded) {
            checker.warn("Cannot apply type arguments to an overload set : " + ctx.expression().getText(), ctx.typeArguments());
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
            if (!checkBabbleBounds(fn.typeParameters(), env, ctx, ctx.typeArguments())) {
                return Type.ERROR;
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
            Map<Type.TypeVar, Type> classEnv = new HashMap<>();
            for (int i = 0; i < explicitTypeArgs.size(); i++) {
                classEnv.put(classVars.get(i), explicitTypeArgs.get(i));
            }
            if (!checkBabbleBounds(classVars, classEnv, ctx, ctx.typeArguments())) {
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
            List<Method> candidates = javaCalls.matchingGenericJavaMethods(all, receiverEnv, explicitTypeArgs);
            if (candidates.isEmpty()) {
                JavaTypeBridge.BadJavaArrayTypeArg badM = javaCalls.firstJavaMethodArrayError(all, receiverEnv, explicitTypeArgs);
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
