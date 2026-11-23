package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Overload-set call and overloaded operator call resolution, including generic
 * alternative matching and union/nullable/interface-intersection split dispatch
 * ({@code BIP021-D11}, {@code BIP021-D21}, {@code BIP022}), extracted from
 * {@link CallSolver} (BIP027-I1).
 */
final class OverloadCallResolver {
    private final TypeChecker checker;
    private final CallSolver solver;

    private record OverloadCandidate(int index, Type.Function alt, CallSolver.BindResult bind,
                                     List<Type> effectiveParams, int maxSpreadRank,
                                     Map<Type.TypeVar, Type> genericEnv,
                                     LoweringTargets.SpreadAdapt[] spreadAdapts) {
    }

    private enum ResolutionKind { UNIQUE, NONE, AMBIGUOUS_SPREAD, AMBIGUOUS_LAMBDA }

    private record SingleResolution(ResolutionKind kind, OverloadCandidate winner,
                                    List<OverloadCandidate> tied, BabbleParser.FnParamContext untypedParam) {
    }

    private record ConcreteArgOutcome(LoweringTargets.SpreadAdapt adapt, int spreadRank) {
        static final ConcreteArgOutcome OK = new ConcreteArgOutcome(null, 0);
    }

    OverloadCallResolver(TypeChecker checker, CallSolver solver) {
        this.checker = checker;
        this.solver = solver;
    }

    // ---------------------------------------------------------------- overloaded operator calls

    Type overloadedOperatorCall(Type receiver, String method, Type.Overloaded set,
                                ParserRuleContext argument, Type argumentType,
                                ParserRuleContext at, String receiverText) {
        if (argument != null && TypeChecker.isUnresolved(argumentType)) {
            return Type.ERROR;
        }
        boolean exactSet = false;
        List<BabbleParser.FunctionLiteralContext> altLiterals = null;
        if (receiver instanceof Type.Instance inst && !inst.node().isInterface()) {
            TypeNode.Member member = inst.node().members.get(method);
            if (member != null && member.declaration instanceof BabbleParser.DefExpressionContext def
                    && def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                exactSet = def.type() == null;
                if (checker.inFile(ov)) {
                    altLiterals = checker.resolveOverloadLiterals(ov);
                }
            }
        }
        List<Type> argTypes = argument == null ? List.of() : List.of(argumentType);
        int chosen = OverloadSolver.selectAlternativeForPositionalArgs(checker.algebra(), set, argTypes);
        if (chosen >= 0) {
            return finishSingleOperatorCall(set, chosen, argument, argumentType, at, receiverText, method, exactSet, altLiterals);
        }
        if (argument != null && argumentType instanceof Type.Union union) {
            Type split = tryOperatorUnionSplit(set, union, method, argumentType, at, receiverText, exactSet, altLiterals);
            if (split != null) {
                return split;
            }
        }
        String argStr = argument == null ? "" : TypeDisplay.display(argumentType);
        checker.warn("No applicable overload of " + receiverText + "." + method + " for (" + argStr
                + ") : candidates are " + OverloadSolver.formatCandidates(set.alternatives()), at);
        return Type.ERROR;
    }

    private Type finishSingleOperatorCall(Type.Overloaded set, int chosen,
                                          ParserRuleContext argument, Type argumentType,
                                          ParserRuleContext at, String receiverText, String method,
                                          boolean exactSet, List<BabbleParser.FunctionLiteralContext> altLiterals) {
        Type.Function alt = set.alternatives().get(chosen);
        Type resultType = alt.result();
        Type expectedParam = argument != null ? alt.parameters().get(0).type() : null;
        if (!alt.typeParameters().isEmpty()) {
            if (argument != null) {
                TypeAlgebra.ConstraintStore store = checker.algebra().store(Set.copyOf(alt.typeParameters()), true);
                boolean borrowReadOnly = alt.parameters().get(0).borrow() && !alt.parameters().get(0).mut();
                checker.algebra().relate(argumentType, expectedParam, TypeAlgebra.RelateMode.ASSIGNABLE,
                        store, borrowReadOnly, false);
                Map<Type.TypeVar, Type> env = new LinkedHashMap<>();
                for (Type.TypeVar tv : alt.typeParameters()) {
                    Type sol = store.solution(tv);
                    if (sol != null && sol != Type.ERROR) {
                        env.put(tv, sol);
                    }
                }
                for (Type.TypeVar tv : alt.typeParameters()) {
                    Type arg = env.get(tv);
                    Type promoted = solver.promoteCallableToSamBound(arg, tv.bound(), env);
                    if (promoted != arg) {
                        env.put(tv, promoted);
                    }
                }
                expectedParam = checker.algebra().substitute(expectedParam, env);
                resultType = checker.algebra().substitute(resultType, env);
            }
            Set<Type.TypeVar> ownVars = Set.copyOf(alt.typeParameters());
            if (resultType.anyMatch(t -> t instanceof Type.TypeVar tv && ownVars.contains(tv))) {
                checker.warn("Missing type : cannot infer type arguments for " + receiverText + "." + method, at);
                return Type.ERROR;
            }
        }
        if (argument != null) {
            List<? extends ParserRuleContext> decls = checker.parameterDecls(alt);
            ParserRuleContext decl = decls != null && !decls.isEmpty() ? decls.get(0) : null;
            boolean frozenBorrowSafe = alt.effects().uniqueResult() || checker.isDeeplyImmutable(resultType);
            solver.bindArgument(decl, argumentType, expectedParam,
                    frozenBorrowSafe && alt.parameters().get(0).borrow() && !alt.parameters().get(0).mut(), argument);
        }
        BabbleParser.FunctionLiteralContext directTarget = (altLiterals != null && chosen < altLiterals.size())
                ? altLiterals.get(chosen) : null;
        checker.recordResolvedOverloadCall(at, new TypeChecker.ResolvedOverloadCall(
                chosen, alt, exactSet, directTarget, resultType, null));
        return resultType;
    }

    private Type tryOperatorUnionSplit(Type.Overloaded set, Type.Union union, String method,
                                       Type argumentType, ParserRuleContext at, String receiverText,
                                       boolean exactSet, List<BabbleParser.FunctionLiteralContext> altLiterals) {
        List<Type> components = new ArrayList<>(union.members());
        boolean addedMeet;
        do {
            addedMeet = false;
            for (int i = 0; i < components.size(); i++) {
                Type a = components.get(i);
                for (int j = i + 1; j < components.size(); j++) {
                    Type b = components.get(j);
                    if (OverloadSolver.isInterfaceLike(a) && OverloadSolver.isInterfaceLike(b)) {
                        Type meet = canonicalizeIntersection(OverloadSolver.staticMeet(checker.algebra(), a, b));
                        if (meet != null && !containsSplitType(components, meet)) {
                            components.add(meet);
                            addedMeet = true;
                        }
                    }
                }
            }
        } while (addedMeet);

        List<Integer> compAlts = new ArrayList<>(components.size());
        List<Type> compResults = new ArrayList<>(components.size());
        for (Type comp : components) {
            int idx = OverloadSolver.selectAlternativeForPositionalArgs(checker.algebra(), set, List.of(comp));
            if (idx < 0) {
                return null;
            }
            Type branchResult = instantiateOperatorBranchResult(set.alternatives().get(idx), comp);
            if (branchResult == null) {
                return null;
            }
            compAlts.add(idx);
            compResults.add(branchResult);
        }

        for (int i = 0; i < components.size(); i++) {
            for (int j = i + 1; j < components.size(); j++) {
                if (!compAlts.get(i).equals(compAlts.get(j))) {
                    Type ca = components.get(i);
                    Type cb = components.get(j);
                    boolean ordered = checker.algebra().subtype(ca, cb) || checker.algebra().subtype(cb, ca)
                            || (isRuntimeInterfaceLike(ca) && isRuntimeInterfaceLike(cb)
                            && containsSplitType(components, canonicalInterfaceMeet(ca, cb)));
                    if (!ordered && !OverloadSolver.runtimeDistinguishable(checker.algebra(), ca, cb)) {
                        checker.warn("Cannot dispatch " + receiverText + "." + method + " on " + TypeDisplay.display(argumentType)
                                + " : its alternatives are not told apart at runtime", at);
                        return Type.ERROR;
                    }
                }
            }
        }

        List<Integer> order = topologicalOrder(components.size(),
                (i, j) -> checker.algebra().subtype(components.get(i), components.get(j))
                        && !checker.algebra().subtype(components.get(j), components.get(i)),
                (a, b) -> {
                    Type ta = components.get(a);
                    Type tb = components.get(b);
                    if (ta == Type.NULL && tb != Type.NULL) return -1;
                    if (tb == Type.NULL && ta != Type.NULL) return 1;
                    int da = OverloadSolver.runtimeTestDescriptors(ta).size();
                    int db = OverloadSolver.runtimeTestDescriptors(tb).size();
                    if (da != db) return Integer.compare(db, da);
                    return Integer.compare(a, b);
                });

        List<TypeChecker.ResolvedSplitBranch> branches = new ArrayList<>(order.size());
        List<Type> results = new ArrayList<>(order.size());
        for (int idx : order) {
            Type comp = components.get(idx);
            int altIdx = compAlts.get(idx);
            Type.Function alt = set.alternatives().get(altIdx);
            Type branchResult = compResults.get(idx);
            List<String> descs = OverloadSolver.runtimeTestDescriptors(comp);
            LoweringTargets.OverloadSplitCheck check = new LoweringTargets.OverloadSplitCheck(0, descs, comp == Type.NULL);
            BabbleParser.FunctionLiteralContext directTarget = (altLiterals != null && altIdx < altLiterals.size())
                    ? altLiterals.get(altIdx) : null;
            branches.add(new TypeChecker.ResolvedSplitBranch(List.of(check), altIdx, alt, exactSet,
                    directTarget, branchResult, null, null));
            results.add(branchResult);
        }
        Type joined = Type.union(results);
        checker.recordResolvedOverloadCall(at, new TypeChecker.ResolvedOverloadCall(
                -1, null, exactSet, null, joined, branches));
        return joined;
    }

    private Type instantiateOperatorBranchResult(Type.Function alt, Type comp) {
        if (alt.typeParameters().isEmpty()) {
            return alt.result();
        }
        TypeAlgebra.ConstraintStore store = checker.algebra().store(Set.copyOf(alt.typeParameters()), true);
        Type expectedParam = alt.parameters().get(0).type();
        boolean borrowReadOnly = alt.parameters().get(0).borrow() && !alt.parameters().get(0).mut();
        checker.algebra().relate(comp, expectedParam, TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowReadOnly, false);
        Map<Type.TypeVar, Type> env = new LinkedHashMap<>();
        for (Type.TypeVar tv : alt.typeParameters()) {
            Type sol = store.solution(tv);
            if (sol != null && sol != Type.ERROR) {
                env.put(tv, sol);
            }
        }
        Type substituted = checker.algebra().substitute(alt.result(), env);
        Set<Type.TypeVar> ownVars = Set.copyOf(alt.typeParameters());
        if (substituted.anyMatch(t -> t instanceof Type.TypeVar tv && ownVars.contains(tv))) {
            return null;
        }
        return substituted;
    }

    // ---------------------------------------------------------------- overload set calls (BIP021-D11)

    Type overloadedCall(Type.Overloaded set, List<BabbleParser.CallParameterContext> arguments,
                        BabbleParser.CallContext ctx, Type expected) {
        String callName = ctx.expression().getText();
        int n = arguments.size();
        String[] argNames = new String[n];
        boolean[] untypedLambda = new boolean[n];
        Type[] argTypes = new Type[n];
        boolean anyError = false;
        for (int i = 0; i < n; i++) {
            BabbleParser.CallParameterContext cp = arguments.get(i);
            argNames[i] = ScopeAnalysis.callParamName(cp);
            if (!ScopeAnalysis.isSpread(cp)
                    && cp.expression() instanceof BabbleParser.FunctionLiteralContext lit
                    && hasUntypedParam(lit)) {
                untypedLambda[i] = true;
            } else {
                argTypes[i] = checker.synth(cp.expression());
                if (argTypes[i] == Type.ERROR) {
                    anyError = true;
                }
            }
        }
        if (anyError) {
            synthUntypedLambdas(arguments, untypedLambda);
            return Type.ERROR;
        }

        boolean exactSet = checker.isExactOverloadSetExpr(ctx.expression());
        List<BabbleParser.FunctionLiteralContext> localLits = checker.resolveOverloadLiterals(ctx.expression());

        SingleResolution res = resolveOverloadSingle(set, arguments, argNames, argTypes, untypedLambda);
        if (res.kind() == ResolutionKind.UNIQUE) {
            OverloadCandidate win = res.winner();
            Type result = solver.call(win.alt(), ctx, expected, false);
            BabbleParser.FunctionLiteralContext directLit = localLits != null && win.index() < localLits.size()
                    ? localLits.get(win.index()) : null;
            checker.recordResolvedOverloadCall(ctx, new TypeChecker.ResolvedOverloadCall(
                    win.index(), win.alt(), exactSet, directLit, result, null));
            return result;
        }
        if (res.kind() == ResolutionKind.AMBIGUOUS_LAMBDA) {
            checker.warn("Missing type : " + res.untypedParam().ID().getText(), res.untypedParam());
            return Type.ERROR;
        }
        if (res.kind() == ResolutionKind.AMBIGUOUS_SPREAD) {
            checker.warn("Ambiguous call to " + callName + " for (" + formatOverloadArgs(arguments, argTypes, untypedLambda)
                    + ") : candidates are " + OverloadSolver.formatCandidates(
                            res.tied().stream().map(OverloadCandidate::alt).toList()), ctx);
            return Type.ERROR;
        }

        // No single alternative accepted the arguments whole : try union / nullable splits (BIP021-D11 #4).
        List<Integer> unionArgIndices = new ArrayList<>();
        int productSize = 1;
        for (int i = 0; i < n; i++) {
            if (!untypedLambda[i] && !ScopeAnalysis.isSpread(arguments.get(i)) && argTypes[i] instanceof Type.Union u) {
                unionArgIndices.add(i);
                productSize *= u.members().size();
                if (productSize > 64) {
                    break;
                }
            }
        }
        if (!unionArgIndices.isEmpty() && productSize <= 64) {
            Type splitResult = tryOverloadUnionSplit(set, callName, arguments, argNames, argTypes,
                    untypedLambda, unionArgIndices, exactSet, localLits, ctx);
            if (splitResult != null) {
                return splitResult;
            }
        }

        // No applicable overload : synth any untyped lambdas for clean state and report diagnostic.
        synthUntypedLambdas(arguments, untypedLambda);
        checker.warn("No applicable overload of " + callName + " for (" + formatOverloadArgs(arguments, argTypes, untypedLambda)
                + ") : candidates are " + OverloadSolver.formatCandidates(set.alternatives()), ctx);
        return Type.ERROR;
    }

    private void synthUntypedLambdas(List<BabbleParser.CallParameterContext> arguments, boolean[] untypedLambda) {
        for (int i = 0; i < untypedLambda.length; i++) {
            if (untypedLambda[i]) {
                checker.synth(arguments.get(i).expression());
            }
        }
    }

    private SingleResolution resolveOverloadSingle(Type.Overloaded set,
                                                   List<BabbleParser.CallParameterContext> arguments,
                                                   String[] argNames, Type[] argTypes,
                                                   boolean[] untypedLambda) {
        List<OverloadCandidate> applicable = new ArrayList<>();
        List<Type.Function> alts = set.alternatives();
        for (int i = 0; i < alts.size(); i++) {
            OverloadCandidate cand = matchOverloadAlternative(i, alts.get(i), arguments, argNames, argTypes, untypedLambda);
            if (cand != null) {
                applicable.add(cand);
            }
        }
        if (applicable.isEmpty()) {
            return new SingleResolution(ResolutionKind.NONE, null, List.of(), null);
        }
        if (applicable.size() == 1) {
            return new SingleResolution(ResolutionKind.UNIQUE, applicable.get(0), List.of(), null);
        }
        // Filter by minimum spread adaptation rank (BIP021-D7: AS_IS < view < copy).
        int bestSpreadRank = Integer.MAX_VALUE;
        for (OverloadCandidate c : applicable) {
            bestSpreadRank = Math.min(bestSpreadRank, c.maxSpreadRank());
        }
        List<OverloadCandidate> bestSpread = new ArrayList<>();
        for (OverloadCandidate c : applicable) {
            if (c.maxSpreadRank() == bestSpreadRank) {
                bestSpread.add(c);
            }
        }
        if (bestSpread.size() == 1) {
            return new SingleResolution(ResolutionKind.UNIQUE, bestSpread.get(0), List.of(), null);
        }
        // Find the unique most-specific candidate on non-lambda parameters.
        List<OverloadCandidate> minimal = new ArrayList<>();
        for (OverloadCandidate cand : bestSpread) {
            boolean beaten = false;
            for (OverloadCandidate other : bestSpread) {
                if (other == cand) {
                    continue;
                }
                if (OverloadSolver.compareAlternatives(checker.algebra(),
                        other.alt(), other.effectiveParams(),
                        cand.alt(), cand.effectiveParams()) == OverloadSolver.Order.FIRST_BETTER) {
                    beaten = true;
                    break;
                }
            }
            if (!beaten) {
                minimal.add(cand);
            }
        }
        if (minimal.size() == 1) {
            return new SingleResolution(ResolutionKind.UNIQUE, minimal.get(0), List.of(), null);
        }
        // Check if an untyped lambda caused the ambiguity (BIP021-D11 #6).
        for (int i = 0; i < untypedLambda.length; i++) {
            if (untypedLambda[i] && arguments.get(i).expression() instanceof BabbleParser.FunctionLiteralContext lit) {
                BabbleParser.FnParamContext firstUntyped = firstUntypedParam(lit);
                if (firstUntyped != null) {
                    return new SingleResolution(ResolutionKind.AMBIGUOUS_LAMBDA, null, minimal, firstUntyped);
                }
            }
        }
        return new SingleResolution(ResolutionKind.AMBIGUOUS_SPREAD, null, minimal, null);
    }

    private OverloadCandidate matchOverloadAlternative(int index, Type.Function alt,
                                                       List<BabbleParser.CallParameterContext> arguments,
                                                       String[] argNames, Type[] argTypes,
                                                       boolean[] untypedLambda) {
        List<Type.Parameter> params = alt.parameters();
        CallSolver.BindResult bind = CallSolver.bindArguments(params, argNames);
        if (bind.hasErrors(params)) {
            return null;
        }
        if (alt.typeParameters().isEmpty()) {
            return matchNonGenericAlternative(index, alt, bind, arguments, argTypes, untypedLambda);
        }
        return matchGenericAlternative(index, alt, bind, arguments, argTypes, untypedLambda);
    }

    private OverloadCandidate matchNonGenericAlternative(int index, Type.Function alt, CallSolver.BindResult bind,
                                                         List<BabbleParser.CallParameterContext> arguments,
                                                         Type[] argTypes, boolean[] untypedLambda) {
        List<Type.Parameter> params = alt.parameters();
        int argCount = arguments.size();
        LoweringTargets.SpreadAdapt[] adapts = null;
        int maxSpreadRank = 0;
        List<Type> effectiveParams = new ArrayList<>(argCount);
        boolean frozenBorrowSafe = alt.effects().uniqueResult() || checker.isDeeplyImmutable(alt.result());

        for (int i = 0; i < argCount; i++) {
            int pIdx = paramIndexForArg(bind, i);
            if (pIdx < 0) {
                return null;
            }
            Type.Parameter param = params.get(pIdx);
            Type paramType = param.type();
            if (untypedLambda[i]) {
                if (!(paramType instanceof Type.Function pf)
                        || !untypedLambdaMatchesShape((BabbleParser.FunctionLiteralContext) arguments.get(i).expression(), pf)) {
                    return null;
                }
                // Exclude untyped lambda position from specificity comparison by using ANY placeholder.
                effectiveParams.add(Type.ANY);
                continue;
            }
            ConcreteArgOutcome outcome = verifyConcreteArg(
                    param, paramType, arguments.get(i), argTypes[i], frozenBorrowSafe);
            if (outcome == null) {
                return null;
            }
            if (outcome.adapt() != null) {
                if (adapts == null) {
                    adapts = new LoweringTargets.SpreadAdapt[argCount];
                }
                adapts[i] = outcome.adapt();
            }
            maxSpreadRank = Math.max(maxSpreadRank, outcome.spreadRank());
            effectiveParams.add(paramType);
        }
        return new OverloadCandidate(index, alt, bind, effectiveParams, maxSpreadRank, Map.of(), adapts);
    }

    private OverloadCandidate matchGenericAlternative(int index, Type.Function alt, CallSolver.BindResult bind,
                                                      List<BabbleParser.CallParameterContext> arguments,
                                                      Type[] argTypes, boolean[] untypedLambda) {
        List<Type.Parameter> params = alt.parameters();
        int argCount = arguments.size();
        List<Type> effectiveParams = new ArrayList<>(argCount);
        int maxSpreadRank = 0;

        // Generic alternative (BIP021-D21): solve type vars against non-lambda arguments and check bounds.
        Set<Type.TypeVar> solving = new LinkedHashSet<>(alt.typeParameters());
        TypeAlgebra.ConstraintStore store = checker.algebra().store(solving, false);
        for (int i = 0; i < argCount; i++) {
            int pIdx = paramIndexForArg(bind, i);
            if (pIdx < 0) {
                return null;
            }
            Type.Parameter param = params.get(pIdx);
            Type pattern = param.type();
            if (untypedLambda[i]) {
                if (!(pattern instanceof Type.Function pf)
                        || !untypedLambdaMatchesShape((BabbleParser.FunctionLiteralContext) arguments.get(i).expression(), pf)) {
                    return null;
                }
                effectiveParams.add(Type.ANY);
                continue;
            }
            int spreadRank = collectGenericArgConstraint(param, pattern, arguments.get(i), argTypes[i], store);
            if (spreadRank < 0) {
                return null;
            }
            maxSpreadRank = Math.max(maxSpreadRank, spreadRank);
            effectiveParams.add(pattern);
        }

        Map<Type.TypeVar, Type> solved = solveAndValidateGenericBounds(alt, store);
        if (solved == null) {
            return null;
        }
        Type instResult = checker.algebra().substitute(alt.result(), solved);
        boolean frozenBorrowSafe = alt.effects().uniqueResult() || checker.isDeeplyImmutable(instResult);

        // Re-verify all arguments (including frozen borrow safety and spread adaptation) against instantiated parameter types.
        LoweringTargets.SpreadAdapt[] adapts = null;
        for (int i = 0; i < argCount; i++) {
            if (untypedLambda[i]) {
                continue;
            }
            Type.Parameter param = params.get(paramIndexForArg(bind, i));
            Type instParam = checker.algebra().substitute(param.type(), solved);
            ConcreteArgOutcome outcome = verifyConcreteArg(
                    param, instParam, arguments.get(i), argTypes[i], frozenBorrowSafe);
            if (outcome == null) {
                return null;
            }
            if (outcome.adapt() != null) {
                if (adapts == null) {
                    adapts = new LoweringTargets.SpreadAdapt[argCount];
                }
                adapts[i] = outcome.adapt();
            }
            maxSpreadRank = Math.max(maxSpreadRank, outcome.spreadRank());
        }
        return new OverloadCandidate(index, alt, bind, effectiveParams, maxSpreadRank, solved, adapts);
    }

    private int collectGenericArgConstraint(Type.Parameter param, Type pattern,
                                            BabbleParser.CallParameterContext arg, Type argType,
                                            TypeAlgebra.ConstraintStore store) {
        boolean borrowCollect = param.borrow() && !param.mut();
        if (param.rest()) {
            if (ScopeAnalysis.isSpread(arg)) {
                Type seqElem = solver.extractSequenceElement(argType);
                if (seqElem == null) {
                    return -1;
                }
                LoweringTargets.JavaSamTarget elemSam = CallSolver.samTargetForElement(pattern);
                boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(seqElem);
                TypeAlgebra.RelateMode mode = needsSamAdapt
                        ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
                if (!checker.algebra().relate(seqElem, pattern, mode, store, borrowCollect, false)) {
                    return -1;
                }
                return !needsSamAdapt && isIterableDirectly(argType) ? 0 : 1;
            }
            return checker.algebra().relate(argType, pattern,
                    TypeAlgebra.RelateMode.ASSIGNABLE, store, borrowCollect, false) ? 0 : -1;
        }
        if (ScopeAnalysis.isSpread(arg)) {
            Type seqElem = solver.extractSequenceElement(argType);
            Type targetElem = solver.sequenceTargetElement(pattern);
            if (seqElem == null || targetElem == null
                    || !checker.algebra().relate(seqElem, targetElem, TypeAlgebra.RelateMode.ASSIGNABLE,
                    store, borrowCollect, false)) {
                return -1;
            }
            return 0;
        }
        return checker.algebra().relate(argType, pattern, TypeAlgebra.RelateMode.ASSIGNABLE,
                store, borrowCollect, false) ? 0 : -1;
    }

    private Map<Type.TypeVar, Type> solveAndValidateGenericBounds(Type.Function alt, TypeAlgebra.ConstraintStore store) {
        Map<Type.TypeVar, Type> solved = OverloadSolver.solveConstraints(store, alt.typeParameters());
        for (Type.TypeVar tv : alt.typeParameters()) {
            Type arg = solved.get(tv);
            Type promoted = solver.promoteCallableToSamBound(arg, tv.bound(), solved);
            if (promoted != arg) {
                solved.put(tv, promoted);
            }
        }
        for (Type.TypeVar tv : alt.typeParameters()) {
            Type inferred = solved.get(tv);
            if (inferred == null || inferred == Type.NOTHING) {
                continue;
            }
            if (tv.bound() != null) {
                Type bound = checker.algebra().substitute(tv.bound(), solved);
                if (!checker.algebra().assignable(inferred, bound)) {
                    return null;
                }
            }
        }
        for (Type.Parameter p : alt.parameters()) {
            if (checker.algebra().findPrimitiveArraySubstitution(p.type(), solved) != null) {
                return null;
            }
        }
        if (checker.algebra().findPrimitiveArraySubstitution(alt.result(), solved) != null) {
            return null;
        }
        return solved;
    }

    /** Verifies one concrete argument against its (possibly instantiated) parameter type. */
    private ConcreteArgOutcome verifyConcreteArg(Type.Parameter param, Type paramType,
                                                 BabbleParser.CallParameterContext arg, Type argType,
                                                 boolean frozenBorrowSafe) {
        boolean borrowReadOnly = frozenBorrowSafe && param.borrow() && !param.mut();
        if (param.rest()) {
            if (ScopeAnalysis.isSpread(arg)) {
                Type seqElem = solver.extractSequenceElement(argType);
                if (seqElem == null) {
                    return null;
                }
                LoweringTargets.JavaSamTarget elemSam = CallSolver.samTargetForElement(paramType);
                boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(seqElem);
                TypeAlgebra.RelateMode mode = needsSamAdapt
                        ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
                if (!checker.algebra().relate(seqElem, paramType, mode, null, borrowReadOnly, false)) {
                    return null;
                }
                LoweringTargets.SpreadAdapt adapt = needsSamAdapt ? LoweringTargets.SpreadAdapt.toIterable(elemSam) : null;
                int rank = !needsSamAdapt && isIterableDirectly(argType) ? 0 : 1;
                return new ConcreteArgOutcome(adapt, rank);
            }
            LoweringTargets.JavaSamTarget elemSam = CallSolver.samTargetForElement(paramType);
            boolean needsSamAdapt = elemSam != null && TypeAlgebra.mayContainBabbleCallable(argType);
            TypeAlgebra.RelateMode mode = needsSamAdapt
                    ? TypeAlgebra.RelateMode.ASSIGNABLE : TypeAlgebra.RelateMode.SUBTYPE;
            if (!checker.algebra().relate(argType, paramType, mode, null, borrowReadOnly, false)
                    && (needsSamAdapt || !OverloadSolver.paramAccepts(checker.algebra(), paramType, argType, borrowReadOnly))) {
                return null;
            }
            return needsSamAdapt ? new ConcreteArgOutcome(LoweringTargets.SpreadAdapt.toSam(elemSam), 0) : ConcreteArgOutcome.OK;
        }
        if (ScopeAnalysis.isSpread(arg)) {
            LoweringTargets.SpreadAdapt adapt = solver.classifySpread(argType, paramType,
                    param.borrow(), param.mut(), borrowReadOnly);
            if (adapt == null) {
                return null;
            }
            return new ConcreteArgOutcome(adapt, spreadRank(adapt.kind()));
        }
        boolean ok = checker.algebra().relate(argType, paramType, TypeAlgebra.RelateMode.ASSIGNABLE,
                null, borrowReadOnly, false);
        if (!ok && !OverloadSolver.paramAccepts(checker.algebra(), paramType, argType, borrowReadOnly)) {
            return null;
        }
        LoweringTargets.JavaSamTarget argSam = CallSolver.samTargetForElement(paramType);
        if (argSam != null && TypeAlgebra.mayContainBabbleCallable(argType)) {
            return new ConcreteArgOutcome(LoweringTargets.SpreadAdapt.toSam(argSam), 0);
        }
        return ConcreteArgOutcome.OK;
    }

    // ---------------------------------------------------------------- union / nullable split dispatch

    private Type tryOverloadUnionSplit(Type.Overloaded set, String callName,
                                       List<BabbleParser.CallParameterContext> arguments,
                                       String[] argNames, Type[] argTypes, boolean[] untypedLambda,
                                       List<Integer> unionArgIndices, boolean exactSet,
                                       List<BabbleParser.FunctionLiteralContext> localLits,
                                       BabbleParser.CallContext ctx) {
        List<Type[]> combos = buildUnionCombos(argTypes, unionArgIndices);
        List<OverloadCandidate> winners = new ArrayList<>(combos.size());
        for (Type[] combo : combos) {
            SingleResolution r = resolveOverloadSingle(set, arguments, argNames, combo, untypedLambda);
            if (r.kind() != ResolutionKind.UNIQUE) {
                return null;
            }
            winners.add(r.winner());
        }

        addInterfaceMeets(set, arguments, argNames, untypedLambda, unionArgIndices, combos, winners);

        if (!allPairsDistinguishable(combos, winners, unionArgIndices)) {
            Type firstUnion = argTypes[unionArgIndices.get(0)];
            checker.warn("Cannot dispatch " + callName + " on " + TypeDisplay.display(firstUnion)
                    + " : its alternatives are not told apart at runtime", ctx);
            return Type.ERROR;
        }

        List<Integer> order = orderSplitCombos(combos, unionArgIndices);
        List<TypeChecker.ResolvedSplitBranch> branches = new ArrayList<>(order.size());
        List<Type> branchResults = new ArrayList<>(order.size());
        for (int ci : order) {
            Type[] combo = combos.get(ci);
            OverloadCandidate win = winners.get(ci);
            TypeChecker.ResolvedSplitBranch branch = buildSplitBranch(combo, win, unionArgIndices, exactSet, localLits);
            branches.add(branch);
            branchResults.add(branch.resultType());
        }

        Type joinedResult = Type.union(branchResults);
        checker.recordResolvedOverloadCall(ctx, new TypeChecker.ResolvedOverloadCall(
                -1, null, exactSet, null, joinedResult, List.copyOf(branches)));
        return joinedResult;
    }

    private static List<Type[]> buildUnionCombos(Type[] argTypes, List<Integer> unionArgIndices) {
        List<Type[]> combos = new ArrayList<>();
        combos.add(argTypes.clone());
        for (int idx : unionArgIndices) {
            List<Type> members = Type.membersOf(argTypes[idx]);
            List<Type[]> next = new ArrayList<>(combos.size() * members.size());
            for (Type[] base : combos) {
                for (Type m : members) {
                    Type[] copy = base.clone();
                    copy[idx] = m;
                    if (!containsCombo(next, copy)) {
                        next.add(copy);
                    }
                }
            }
            combos = next;
        }
        return combos;
    }

    /**
     * If two components at a split position are distinct interfaces (e.g. {@code Shape} and
     * {@code Named}), a runtime value may implement both : include their intersection meet
     * ({@code Shape & Named}) so it is tested first in the dispatch chain (BIP021-D11 #4, BIP022).
     */
    private void addInterfaceMeets(Type.Overloaded set, List<BabbleParser.CallParameterContext> arguments,
                                   String[] argNames, boolean[] untypedLambda,
                                   List<Integer> unionArgIndices, List<Type[]> combos,
                                   List<OverloadCandidate> winners) {
        boolean addedMeet;
        do {
            addedMeet = false;
            int currentSize = combos.size();
            for (int i = 0; i < currentSize && combos.size() <= 64; i++) {
                for (int j = i + 1; j < currentSize && combos.size() <= 64; j++) {
                    if (winners.get(i).index() == winners.get(j).index()) {
                        continue;
                    }
                    for (int pos : unionArgIndices) {
                        Type ca = combos.get(i)[pos];
                        Type cb = combos.get(j)[pos];
                        if (sameSplitType(ca, cb) || OverloadSolver.runtimeDistinguishable(checker.algebra(), ca, cb)) {
                            continue;
                        }
                        if (isRuntimeInterfaceLike(ca) && isRuntimeInterfaceLike(cb)) {
                            Type meet = canonicalInterfaceMeet(ca, cb);
                            if (!sameSplitType(meet, ca) && !sameSplitType(meet, cb)) {
                                Type[] meetCombo = combos.get(i).clone();
                                meetCombo[pos] = meet;
                                if (!containsCombo(combos, meetCombo)) {
                                    SingleResolution mr = resolveOverloadSingle(set, arguments, argNames, meetCombo, untypedLambda);
                                    if (mr.kind() == ResolutionKind.UNIQUE) {
                                        combos.add(meetCombo);
                                        winners.add(mr.winner());
                                        addedMeet = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } while (addedMeet);
    }

    private boolean allPairsDistinguishable(List<Type[]> combos, List<OverloadCandidate> winners,
                                            List<Integer> unionArgIndices) {
        for (int i = 0; i < combos.size(); i++) {
            for (int j = i + 1; j < combos.size(); j++) {
                if (winners.get(i).index() == winners.get(j).index()) {
                    continue;
                }
                boolean distinguishable = false;
                for (int pos : unionArgIndices) {
                    Type ca = combos.get(i)[pos];
                    Type cb = combos.get(j)[pos];
                    if (sameSplitType(ca, cb)) {
                        continue;
                    }
                    if (OverloadSolver.runtimeDistinguishable(checker.algebra(), ca, cb)) {
                        distinguishable = true;
                        break;
                    }
                    if (isRuntimeInterfaceLike(ca) && isRuntimeInterfaceLike(cb)) {
                        Type meet = canonicalInterfaceMeet(ca, cb);
                        Type[] meetCombo = combos.get(i).clone();
                        meetCombo[pos] = meet;
                        if (containsCombo(combos, meetCombo)) {
                            distinguishable = true;
                            break;
                        }
                    }
                }
                if (!distinguishable) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<Integer> orderSplitCombos(List<Type[]> combos, List<Integer> unionArgIndices) {
        return topologicalOrder(combos.size(),
                (i, j) -> isStrictComponentwiseSubtype(combos.get(i), combos.get(j), unionArgIndices),
                (aIdx, bIdx) -> compareComboSpecificity(combos.get(aIdx), combos.get(bIdx), unionArgIndices, aIdx, bIdx));
    }

    private boolean isStrictComponentwiseSubtype(Type[] ca, Type[] cb, List<Integer> unionArgIndices) {
        boolean strictSub = false;
        for (int pos : unionArgIndices) {
            if (sameSplitType(ca[pos], cb[pos])) {
                continue;
            }
            if (!checker.algebra().subtype(ca[pos], cb[pos])) {
                return false;
            }
            if (!checker.algebra().subtype(cb[pos], ca[pos])) {
                strictSub = true;
            }
        }
        return strictSub;
    }

    private static int compareComboSpecificity(Type[] ca, Type[] cb, List<Integer> unionArgIndices,
                                               int aIdx, int bIdx) {
        int nullA = 0, nullB = 0, descA = 0, descB = 0;
        for (int pos : unionArgIndices) {
            if (ca[pos] == Type.NULL) {
                nullA++;
            } else {
                descA += OverloadSolver.runtimeTestDescriptors(ca[pos]).size();
            }
            if (cb[pos] == Type.NULL) {
                nullB++;
            } else {
                descB += OverloadSolver.runtimeTestDescriptors(cb[pos]).size();
            }
        }
        if (nullA != nullB) {
            return Integer.compare(nullB, nullA);
        }
        if (descA != descB) {
            return Integer.compare(descB, descA);
        }
        return Integer.compare(aIdx, bIdx);
    }

    private TypeChecker.ResolvedSplitBranch buildSplitBranch(Type[] combo, OverloadCandidate win,
                                                             List<Integer> unionArgIndices, boolean exactSet,
                                                             List<BabbleParser.FunctionLiteralContext> localLits) {
        List<LoweringTargets.OverloadSplitCheck> checks = new ArrayList<>(unionArgIndices.size());
        for (int pos : unionArgIndices) {
            Type comp = combo[pos];
            if (comp == Type.NULL) {
                checks.add(new LoweringTargets.OverloadSplitCheck(pos, List.of(), true));
            } else {
                checks.add(new LoweringTargets.OverloadSplitCheck(pos, OverloadSolver.runtimeTestDescriptors(comp), false));
            }
        }
        Type branchResult = win.genericEnv().isEmpty()
                ? win.alt().result()
                : checker.algebra().substitute(win.alt().result(), win.genericEnv());
        BabbleParser.FunctionLiteralContext directLit = localLits != null && win.index() < localLits.size()
                ? localLits.get(win.index()) : null;
        PrimitiveKind restKind = PrimitiveKind.NONE;
        int restIdx = win.alt().restIndex();
        if (restIdx >= 0) {
            Type elemType = win.genericEnv().isEmpty()
                    ? win.alt().parameters().get(restIdx).type()
                    : checker.algebra().substitute(win.alt().parameters().get(restIdx).type(), win.genericEnv());
            restKind = LoweringAnalyzer.primitiveKind(elemType);
        }
        return new TypeChecker.ResolvedSplitBranch(
                List.copyOf(checks), win.index(), win.alt(), exactSet, directLit,
                branchResult, win.spreadAdapts(), restKind);
    }

    // ---------------------------------------------------------------- shared utilities

    private static List<Integer> topologicalOrder(int n, BiPredicate<Integer, Integer> strictEdge,
                                                  Comparator<Integer> tieBreaker) {
        List<List<Integer>> adj = new ArrayList<>(n);
        int[] indegree = new int[n];
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && strictEdge.test(i, j)) {
                    adj.get(i).add(j);
                    indegree[j]++;
                }
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>(tieBreaker);
        for (int i = 0; i < n; i++) {
            if (indegree[i] == 0) {
                ready.add(i);
            }
        }
        List<Integer> order = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int u = ready.poll();
            order.add(u);
            for (int v : adj.get(u)) {
                if (--indegree[v] == 0) {
                    ready.add(v);
                }
            }
        }
        if (order.size() < n) {
            boolean[] emitted = new boolean[n];
            for (int idx : order) {
                emitted[idx] = true;
            }
            for (int i = 0; i < n; i++) {
                if (!emitted[i]) {
                    order.add(i);
                }
            }
        }
        return order;
    }

    private static int paramIndexForArg(CallSolver.BindResult bind, int argIndex) {
        int[] p2a = bind.paramToArg();
        for (int p = 0; p < p2a.length; p++) {
            if (p2a[p] == argIndex) {
                return p;
            }
        }
        if (bind.restParam() >= 0) {
            for (int r : bind.restArgs()) {
                if (r == argIndex) {
                    return bind.restParam();
                }
            }
        }
        return -1;
    }

    private static int spreadRank(LoweringTargets.SpreadKind kind) {
        return switch (kind) {
            case NONE, AS_IS, TO_SAM -> 0;
            case TO_ITERABLE, TO_LIST_BORROWED -> 1;
            case TO_LIST_COPY, TO_ARRAY -> 2;
        };
    }

    private static boolean isIterableDirectly(Type t) {
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(t));
        return base instanceof Type.JavaInstance ji && Iterable.class.isAssignableFrom(ji.type());
    }

    private static boolean hasUntypedParam(BabbleParser.FunctionLiteralContext lit) {
        return firstUntypedParam(lit) != null;
    }

    private static BabbleParser.FnParamContext firstUntypedParam(BabbleParser.FunctionLiteralContext lit) {
        for (BabbleParser.FnParamContext p : lit.fnParameters().fnParam()) {
            if (p.type() == null) {
                return p;
            }
        }
        return null;
    }

    private static boolean untypedLambdaMatchesShape(BabbleParser.FunctionLiteralContext lit, Type.Function expected) {
        return lit.fnParameters().fnParam().size() == expected.parameters().size();
    }

    private static String formatOverloadArgs(List<BabbleParser.CallParameterContext> arguments,
                                             Type[] argTypes, boolean[] untypedLambda) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String name = ScopeAnalysis.callParamName(arguments.get(i));
            if (name != null) {
                sb.append(name).append(": ");
            }
            if (ScopeAnalysis.isSpread(arguments.get(i))) {
                sb.append("...");
            }
            sb.append(untypedLambda[i] ? "fn (...)" : TypeDisplay.display(argTypes[i]));
        }
        return sb.toString();
    }

    private static Type canonicalInterfaceMeet(Type a, Type b) {
        Type inter = canonicalizeIntersection(Type.intersection(TypeAlgebra.unwrapFrozen(a), TypeAlgebra.unwrapFrozen(b)));
        return (a instanceof Type.Frozen || b instanceof Type.Frozen) ? Type.frozen(inter) : inter;
    }

    private static Type canonicalizeIntersection(Type t) {
        if (t instanceof Type.Frozen f) {
            Type inner = canonicalizeIntersection(f.of());
            return inner == f.of() ? t : Type.frozen(inner);
        }
        if (!(t instanceof Type.Intersection inter)) {
            return t;
        }
        List<Type> sorted = new ArrayList<>(inter.members());
        sorted.sort(Comparator.comparing(Type::toString)
                .thenComparingInt(m -> m instanceof Type.Instance inst ? System.identityHashCode(inst.node())
                        : (m instanceof Type.JavaInstance ji ? System.identityHashCode(ji.type()) : 0)));
        return new Type.Intersection(List.copyOf(sorted));
    }

    private static boolean sameSplitType(Type a, Type b) {
        if (a.equals(b)) {
            return true;
        }
        if (a instanceof Type.Frozen fa && b instanceof Type.Frozen fb) {
            return sameSplitType(fa.of(), fb.of());
        }
        if (a instanceof Type.Intersection ia && b instanceof Type.Intersection ib) {
            List<Type> ma = ia.members();
            List<Type> mb = ib.members();
            return ma.size() == mb.size() && allMembersMatch(ma, mb) && allMembersMatch(mb, ma);
        }
        return false;
    }

    private static boolean allMembersMatch(List<Type> src, List<Type> dst) {
        for (Type a : src) {
            boolean found = false;
            for (Type b : dst) {
                if (sameSplitType(a, b)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsSplitType(List<Type> list, Type target) {
        for (Type existing : list) {
            if (sameSplitType(existing, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRuntimeInterfaceLike(Type t) {
        Type u = TypeAlgebra.unwrapFrozen(t);
        if (u instanceof Type.Instance i) {
            return i.node().isInterface();
        }
        if (u instanceof Type.JavaInstance j) {
            return j.type().isInterface();
        }
        return u instanceof Type.Intersection;
    }

    private static boolean containsCombo(List<Type[]> combos, Type[] candidate) {
        for (Type[] existing : combos) {
            if (existing.length == candidate.length) {
                boolean match = true;
                for (int i = 0; i < existing.length; i++) {
                    if (!sameSplitType(existing[i], candidate[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
        }
        return false;
    }
}
