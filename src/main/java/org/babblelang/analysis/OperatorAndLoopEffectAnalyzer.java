package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Operators;

/**
 * Operator-method, binary/unary operator, range, and {@code for}-loop effect analysis extracted
 * from {@link EffectBodyWalker}.
 */
final class OperatorAndLoopEffectAnalyzer {
    private OperatorAndLoopEffectAnalyzer() {}

    static Reachability.Origin evalFor(EffectBodyWalker w, BabbleParser.ForExpressionContext ctx) {
        EffectChecker ec = w.ec;
        Reachability.Origin iterOrig = w.eval(ctx.iterable);
        Type iterType = w.effectiveType(ctx.iterable);
        TypeChecker.ForIterableInfo info = ec.checker.forIterableInfo(ctx);
        boolean trackConcurrentMut = false;
        boolean curatedFinite = false;
        boolean isArrayLoop = false;
        if (info != null) {
            w.recordTargetRead(iterOrig, iterType);
            boolean[] flags = {isArrayLoop, curatedFinite, trackConcurrentMut};
            applyForIterableEffects(w, iterType, info, iterOrig, ctx, flags);
            isArrayLoop = flags[0];
            curatedFinite = flags[1];
            trackConcurrentMut = flags[2];
        }
        Type loopVarType = ec.checker.typeOfDeclarationForEffects(ctx);
        Reachability.Origin elemOrig;
        if (loopVarType == null || ec.javaResolver.isDeeplyImmutable(loopVarType)) {
            elemOrig = Reachability.Origin.IMMUTABLE;
        } else {
            Reachability.Origin flat = iterOrig.flattenElements();
            elemOrig =
                    new Reachability.Origin(
                            flat.params,
                            flat.aliasesThis,
                            flat.external,
                            false,
                            null,
                            false,
                            null,
                            Set.of(ctx),
                            new Reachability.PathKey(ctx, List.of()));
            // The loop variable is one of the iterable's elements : it aliases whatever they do
            // (for y in list(b) : y aliases b), as an indexed read of the iterable would.
            Reachability.Origin element = iterOrig.deriveProjection(false);
            elemOrig = elemOrig.join(element.owned() ? element.withoutUnique() : element);
        }
        Reachability.Origin[] breakAcc = new Reachability.Origin[1];
        w.loopBreakOrigins.push(breakAcc);
        EffectBodyWalker.ActiveForLoop activeLoop =
                trackConcurrentMut ? new EffectBodyWalker.ActiveForLoop(iterOrig) : null;
        if (activeLoop != null) {
            w.activeForIterables.add(activeLoop);
        }
        boolean outerHermetic = w.hermetic;
        boolean outerNothrow = w.nothrow;
        boolean outerTerminating = w.terminating;
        w.hermetic = true;
        w.nothrow = true;
        w.terminating = true;
        Reachability.Origin bodyOrig = Reachability.Origin.IMMUTABLE;
        try {
            int iter = 0;
            while (true) {
                Map<ParserRuleContext, Reachability.Origin> before = new IdentityHashMap<>(w.env);
                w.env.put(ctx, elemOrig);
                bodyOrig = w.eval(ctx.forBlock);
                w.env.remove(ctx);
                Map<ParserRuleContext, Reachability.Origin> merged =
                        iter++ < 2 ? w.mergeEnvs(before, w.env) : w.widenEnvs(before, w.env);
                if (EffectBodyWalker.envsEqual(merged, before)) {
                    w.env = merged;
                    break;
                }
                w.env = merged;
            }
        } finally {
            boolean bodyHermetic = w.hermetic;
            boolean bodyNothrow = w.nothrow;
            boolean bodyTerminating = w.terminating;
            w.hermetic = outerHermetic && bodyHermetic;
            w.nothrow = outerNothrow && bodyNothrow;
            w.terminating = outerTerminating && bodyTerminating;
            boolean loopMutated = activeLoop != null && activeLoop.mutated;
            if (activeLoop != null) {
                w.activeForIterables.remove(w.activeForIterables.size() - 1);
                if (curatedFinite && (loopMutated || !bodyHermetic)) {
                    w.terminating = false;
                    w.nothrow = false;
                }
            }
            if (w.emitDiagnostics) {
                boolean loopFinite = isArrayLoop || (curatedFinite && !loopMutated && bodyHermetic);
                boolean loopNothrowOk = loopFinite && bodyNothrow && (isArrayLoop || outerNothrow);
                EnumSet<Effect> fx = EnumSet.noneOf(Effect.class);
                if (bodyHermetic) {
                    fx.add(Effect.PURE);
                    fx.add(Effect.HERMETIC);
                }
                if (loopNothrowOk) {
                    fx.add(Effect.NOTHROW);
                }
                ec.pass
                        .analysis
                        .effects()
                        .setForEffects(
                                ctx,
                                EffectSet.ofInferred(
                                        fx, false, false, false, loopFinite && bodyTerminating));
            }
            w.loopBreakOrigins.pop();
        }
        return breakAcc[0] != null ? BranchValueJoins.join(w, bodyOrig, breakAcc[0]) : bodyOrig;
    }

    static Reachability.Origin evalBinaryOp(EffectBodyWalker w, BabbleParser.BinaryOpContext bin) {
        Reachability.Origin leftOrig = w.eval(bin.left);
        Reachability.Origin rightOrig = w.eval(bin.right);
        String op = bin.op.getText();
        Type lt = w.effectiveType(bin.left);
        Type rt = w.effectiveType(bin.right);
        Type res = w.ec.checker.rawTypeOf(bin);
        if (res == Type.ERROR
                || lt == Type.ERROR
                || rt == Type.ERROR
                || w.hasPriorDiagnostic(bin)) {
            return Reachability.Origin.IMMUTABLE;
        }
        String opMethod = Operators.method(op);
        if (opMethod != null) {
            Reachability.Origin opOrig =
                    applyOperatorMethodEffects(
                            w, bin, opMethod, bin.left, leftOrig, lt, bin.right, rightOrig);
            if (opOrig != null) {
                boolean allInstances = true;
                for (Type m : Type.membersOf(TypeAlgebra.unwrapFrozen(lt))) {
                    if (!(m instanceof Type.Instance)) {
                        allInstances = false;
                        break;
                    }
                }
                if (allInstances) {
                    return opOrig;
                }
                applyBuiltinBinaryEffects(w, bin, op, lt, rt, res, leftOrig, rightOrig);
                return opOrig;
            }
        }
        applyBuiltinBinaryEffects(w, bin, op, lt, rt, res, leftOrig, rightOrig);
        return Reachability.Origin.IMMUTABLE;
    }

    private static void applyBuiltinBinaryEffects(
            EffectBodyWalker w,
            BabbleParser.BinaryOpContext bin,
            String op,
            Type lt,
            Type rt,
            Type res,
            Reachability.Origin leftOrig,
            Reachability.Origin rightOrig) {
        EffectChecker ec = w.ec;
        switch (op) {
            case "+" -> {
                if (res == Type.STRING && (lt == Type.STRING || rt == Type.STRING)) {
                    if (lt != Type.STRING) {
                        w.applyEffects(ec.javaResolver.toStringProfile(lt));
                        if (lt instanceof Type.Array) {
                            w.recordTargetRead(leftOrig, lt);
                        }
                    }
                    if (rt != Type.STRING) {
                        w.applyEffects(ec.javaResolver.toStringProfile(rt));
                        if (rt instanceof Type.Array) {
                            w.recordTargetRead(rightOrig, rt);
                        }
                    }
                }
            }
            case "==", "!=", "<>" -> {
                w.applyEffects(ec.javaResolver.equalityProfile(lt, rt));
                if (lt instanceof Type.Array) {
                    w.recordTargetRead(leftOrig, lt);
                }
                if (rt instanceof Type.Array) {
                    w.recordTargetRead(rightOrig, rt);
                }
            }
            case "<", "<=", ">", ">=" -> w.applyEffects(ec.javaResolver.comparisonProfile(lt, rt));
            case "mod", "//" -> {
                if (!(lt instanceof Type.Instance)
                        && !EffectBodyWalker.isNonZeroIntegerConstant(bin.right)) {
                    w.nothrow = false;
                }
            }
            default -> {
                // -, *, / on numbers are pure deterministic nothrow (1 / 0 == Infinity).
            }
        }
    }

    static Reachability.Origin applyOperatorMethodEffects(
            EffectBodyWalker w,
            ParserRuleContext at,
            String methodName,
            BabbleParser.ExpressionContext recvExpr,
            Reachability.Origin recvOrig,
            Type recvType,
            BabbleParser.ExpressionContext argExpr,
            Reachability.Origin argOrig) {
        EffectChecker ec = w.ec;
        Type unwrapped = TypeAlgebra.unwrapFrozen(recvType);
        List<Type.Instance> insts = new ArrayList<>();
        for (Type m : Type.membersOf(unwrapped)) {
            if (m instanceof Type.Instance inst) {
                insts.add(inst);
            }
        }
        if (insts.isEmpty()) {
            return null;
        }
        Type resType = ec.checker.rawTypeOf(at);
        TypeChecker.ResolvedOverloadCall roc = ec.checker.resolvedOverloadCalls().get(at);
        if (roc != null && !roc.isSplit() && roc.altType() != null) {
            boolean iface = insts.size() == 1 && insts.get(0).node().isInterface();
            return applyOperatorFnEffects(
                    w,
                    at,
                    methodName,
                    roc.altType(),
                    iface ? null : roc.directTarget(),
                    recvExpr,
                    recvOrig,
                    recvType,
                    argExpr,
                    argOrig,
                    resType);
        }
        Reachability.Origin joined = null;
        for (Type.Instance inst : insts) {
            TypeNode.Member member = ec.checker.findMemberOnNode(inst.node(), methodName);
            if (member == null) {
                continue;
            }
            boolean iface = inst.node().isInterface();
            Type mt = ec.checker.memberTypeForEffects(inst.node(), member);
            if (mt instanceof Type.Function tf) {
                BabbleParser.FunctionLiteralContext lit =
                        iface
                                ? null
                                : (member.function != null
                                        ? member.function
                                        : ec.checker.literalOf(tf));
                Reachability.Origin o =
                        applyOperatorFnEffects(
                                w,
                                at,
                                methodName,
                                tf,
                                lit,
                                recvExpr,
                                recvOrig,
                                recvType,
                                argExpr,
                                argOrig,
                                resType);
                joined = joined == null ? o : joined.join(o);
            } else if (mt instanceof Type.Overloaded ov) {
                for (Type.Function tf : ov.alternatives()) {
                    BabbleParser.FunctionLiteralContext lit =
                            iface ? null : ec.checker.literalOf(tf);
                    Reachability.Origin o =
                            applyOperatorFnEffects(
                                    w,
                                    at,
                                    methodName,
                                    tf,
                                    lit,
                                    recvExpr,
                                    recvOrig,
                                    recvType,
                                    argExpr,
                                    argOrig,
                                    resType);
                    joined = joined == null ? o : joined.join(o);
                }
            }
        }
        return joined;
    }

    private static Reachability.Origin applyOperatorFnEffects(
            EffectBodyWalker w,
            ParserRuleContext at,
            String methodName,
            Type.Function tf,
            BabbleParser.FunctionLiteralContext lit,
            BabbleParser.ExpressionContext recvExpr,
            Reachability.Origin recvOrig,
            Type recvType,
            BabbleParser.ExpressionContext argExpr,
            Reachability.Origin argOrig,
            Type resType) {
        EffectChecker ec = w.ec;
        EffectChecker.CallableSummary sum = lit != null ? ec.summaries.get(lit) : null;
        EffectSet eff = sum != null ? sum.effects() : tf.effects();
        if (sum != null) {
            w.applyCallableSummaryEffects(sum);
        } else {
            w.applyEffects(eff);
            if (!eff.isNothrow()) {
                w.throwsJavaError = true;
            }
        }
        boolean recvShallow = sum == null || !sum.deepMutThis();
        if (eff.mutThis()) {
            if (w.containsFrozen(recvType)) {
                w.reportOnce("Cannot call mut method " + methodName + " on frozen value", at);
            }
            w.recordTargetMutation(
                    recvOrig, recvExpr, "operator method " + methodName, at, recvShallow);
        }
        if (sum != null && sum.readsThis()) {
            w.recordTargetRead(recvOrig, recvType);
        }
        if (!eff.borrowThis()) {
            w.markStoredOrigin(recvOrig, "operator method " + methodName, at);
        }
        boolean mutP = false;
        boolean borrowP = true;
        if (argExpr != null && !tf.parameters().isEmpty()) {
            mutP =
                    sum != null
                            ? (sum.mutParams().length > 0 && sum.mutParams()[0])
                            : tf.parameters().get(0).mut();
            borrowP =
                    sum != null
                            ? (sum.borrowParams().length > 0 && sum.borrowParams()[0])
                            : tf.parameters().get(0).borrow();
            boolean readP = sum != null && sum.readParams().length > 0 && sum.readParams()[0];
            if (mutP) {
                if (w.containsFrozen(w.effectiveType(argExpr))) {
                    w.reportOnce(
                            "Cannot pass frozen value to mut parameter of operator method "
                                    + methodName,
                            argExpr);
                }
                w.recordTargetMutation(
                        argOrig, argExpr, "operator method " + methodName, at, false);
            }
            if (readP) {
                w.recordTargetRead(argOrig, w.effectiveType(argExpr));
            }
            if (!borrowP) {
                w.markStoredOrigin(argOrig, "operator method " + methodName, at);
            }
            if ((eff.mutThis() || mutP)
                    && Reachability.staticallyAliases(
                            recvOrig, eff.mutThis() && recvShallow, argOrig, false)) {
                String mutLabel = eff.mutThis() ? recvExpr.getText() : argExpr.getText();
                String otherLabel = eff.mutThis() ? argExpr.getText() : recvExpr.getText();
                w.reportOnce(
                        "Exclusivity error : mut target "
                                + mutLabel
                                + " aliases argument "
                                + otherLabel,
                        argExpr);
            }
        }
        boolean immRes = ec.javaResolver.isDeeplyImmutable(resType);
        boolean fnRes = resType instanceof Type.Function || resType instanceof Type.Overloaded;
        if (immRes && !fnRes) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (eff.uniqueResult()) {
            return Reachability.Origin.ofUnique(at, null, false);
        }
        boolean known = sum != null && sum.returnKnown();
        boolean resultDiscarded = at != null && at == w.discardedExpr;
        Reachability.Origin out =
                (known ? sum.returnExternal() : !(eff.isHermetic() && eff.isDeterministic()))
                        ? Reachability.Origin.ofExternal(null, false)
                        : Reachability.Origin.IMMUTABLE;
        boolean includeRecv = !known || sum.returnThis() || !eff.borrowThis();
        if (includeRecv && recvOrig != null && recvOrig != Reachability.Origin.IMMUTABLE) {
            if (!resultDiscarded && recvOrig.owned() && recvOrig.uniqueOwner != null) {
                w.demoteUniqueOwner(recvOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
            }
            Reachability.Origin flat = recvOrig.flattenElements();
            out = out == Reachability.Origin.IMMUTABLE ? flat : out.join(flat);
        }
        if (argExpr != null && argOrig != null && argOrig != Reachability.Origin.IMMUTABLE) {
            boolean includeArg = !known || sum.returnParams().get(0) || !borrowP;
            if (includeArg) {
                if (!resultDiscarded && argOrig.owned() && argOrig.uniqueOwner != null) {
                    w.demoteUniqueOwner(argOrig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                Reachability.Origin flat = argOrig.flattenElements();
                out = out == Reachability.Origin.IMMUTABLE ? flat : out.join(flat);
            }
        }
        if (out == Reachability.Origin.IMMUTABLE) {
            return fnRes
                    ? Reachability.Origin.IMMUTABLE
                    : Reachability.Origin.ofUnique(at, null, false);
        }
        return out.owned() ? out.withoutUnique() : out;
    }

    private static void applyForIterableEffects(
            EffectBodyWalker w,
            Type iterType,
            TypeChecker.ForIterableInfo info,
            Reachability.Origin iterOrig,
            BabbleParser.ForExpressionContext ctx,
            boolean[] flags) {
        EffectChecker ec = w.ec;
        if (info == null) {
            w.terminating = false;
            w.nothrow = false;
            return;
        }
        switch (info.kind()) {
            case ARRAY, COUNTED_RANGE -> {
                flags[0] = true;
            }
            case JAVA_ITERABLE -> {
                boolean curatedFinite = false;
                boolean curatedNothrow = false;
                Type unwrapped = iterType instanceof Type.Frozen f ? f.of() : iterType;
                if (unwrapped instanceof Type.JavaInstance ji) {
                    if (ji.type() == org.babblelang.runtime.Range.class) {
                        curatedFinite = true;
                        curatedNothrow = true;
                    } else {
                        EffectChecker.JavaCallProfile iterProf =
                                ec.javaResolver.resolveJavaMethodProfile(
                                        ji.type(),
                                        "iterator",
                                        JavaEffectResolver.lookupPublicMethod(
                                                ji.type(), "iterator"));
                        w.applyEffects(iterProf.effects());
                        if (iterProf.mutReceiver()) {
                            w.recordTargetMutation(
                                    iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                        }
                        if (iterProf.view()) {
                            flags[2] = true;
                            if (iterProf.effects().isHermetic()
                                    && iterProf.effects().terminating()) {
                                curatedFinite = true;
                                curatedNothrow = iterProf.effects().isNothrow();
                            }
                        }
                    }
                }
                if (curatedFinite) {
                    flags[1] = true;
                } else {
                    w.terminating = false;
                }
                if (!curatedNothrow) {
                    w.nothrow = false;
                }
            }
            case JAVA_ITERATOR -> {
                w.terminating = false;
                w.nothrow = false;
                if (w.containsFrozen(iterType)) {
                    w.reportOnce("Cannot mutate frozen value via for loop", ctx.iterable);
                }
                w.recordTargetMutation(iterOrig, ctx.iterable, "iterator.next", ctx.iterable, true);
            }
            case BABBLE_TO_JAVA_ITERABLE -> {
                w.terminating = false;
                w.nothrow = false;
                if (info.iteratorFn() != null) {
                    w.applyEffects(info.iteratorFn().effects());
                    if (info.iteratorFn().effects().mutThis()) {
                        if (w.containsFrozen(iterType)) {
                            w.reportOnce("Cannot mutate frozen value via for loop", ctx.iterable);
                        }
                        w.recordTargetMutation(
                                iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                    }
                }
            }
            case BABBLE_ITERABLE -> {
                w.terminating = false;
                boolean iterReturnsAlias = true;
                if (info.iteratorFn() != null) {
                    BabbleParser.FunctionLiteralContext iterLit =
                            ec.checker.literalOf(info.iteratorFn());
                    EffectChecker.CallableSummary iterSum =
                            iterLit != null ? ec.summaries.get(iterLit) : null;
                    if (iterSum != null) {
                        w.applyCallableSummaryEffects(iterSum);
                        iterReturnsAlias =
                                !iterSum.returnKnown()
                                        || iterSum.returnThis()
                                        || !iterSum.effects().uniqueResult();
                    } else {
                        w.applyEffects(info.iteratorFn().effects());
                        iterReturnsAlias = !info.iteratorFn().effects().uniqueResult();
                    }
                    if (info.iteratorFn().effects().mutThis()) {
                        if (w.containsFrozen(iterType)) {
                            w.reportOnce("Cannot mutate frozen value via for loop", ctx.iterable);
                        }
                        w.recordTargetMutation(
                                iterOrig, ctx.iterable, "iterator", ctx.iterable, false);
                    }
                }
                boolean mutIter = false;
                if (info.hasNextFn() != null) {
                    BabbleParser.FunctionLiteralContext hnLit =
                            ec.checker.literalOf(info.hasNextFn());
                    EffectChecker.CallableSummary hnSum =
                            hnLit != null ? ec.summaries.get(hnLit) : null;
                    EffectSet hnEff = hnSum != null ? hnSum.effects() : info.hasNextFn().effects();
                    if (hnSum != null) {
                        w.applyCallableSummaryEffects(hnSum);
                    } else {
                        w.applyEffects(hnEff);
                    }
                    mutIter |= hnEff.mutThis();
                }
                if (info.nextFn() != null) {
                    BabbleParser.FunctionLiteralContext nxLit = ec.checker.literalOf(info.nextFn());
                    EffectChecker.CallableSummary nxSum =
                            nxLit != null ? ec.summaries.get(nxLit) : null;
                    EffectSet nxEff = nxSum != null ? nxSum.effects() : info.nextFn().effects();
                    if (nxSum != null) {
                        w.applyCallableSummaryEffects(nxSum);
                    } else {
                        w.applyEffects(nxEff);
                    }
                    mutIter |= nxEff.mutThis();
                }
                if (mutIter && iterReturnsAlias) {
                    if (w.containsFrozen(iterType)) {
                        w.reportOnce("Cannot mutate frozen value via for loop", ctx.iterable);
                    }
                    w.recordTargetMutation(
                            iterOrig, ctx.iterable, "for loop iterator", ctx.iterable, false);
                }
            }
            case BABBLE_ITERATOR -> {
                w.terminating = false;
                boolean mutIter = false;
                if (info.hasNextFn() != null) {
                    w.applyEffects(info.hasNextFn().effects());
                    if (info.hasNextFn().effects().mutThis()) {
                        mutIter = true;
                        w.recordTargetMutation(
                                iterOrig, ctx.iterable, "hasNext", ctx.iterable, false);
                    }
                }
                if (info.nextFn() != null) {
                    w.applyEffects(info.nextFn().effects());
                    if (info.nextFn().effects().mutThis()) {
                        mutIter = true;
                        w.recordTargetMutation(iterOrig, ctx.iterable, "next", ctx.iterable, false);
                    }
                }
                if (mutIter && w.containsFrozen(iterType)) {
                    w.reportOnce("Cannot mutate frozen value via for loop", ctx.iterable);
                }
            }
            case DYNAMIC_UNION -> {
                w.terminating = false;
                w.nothrow = false;
                boolean frozenOuter = iterType instanceof Type.Frozen;
                Type unwrapped = frozenOuter ? ((Type.Frozen) iterType).of() : iterType;
                for (Type member : Type.membersOf(Type.withoutNull(unwrapped))) {
                    Type effMember = frozenOuter ? Type.frozen(member) : member;
                    TypeChecker.ForIterableInfo sub =
                            ec.checker.resolveForIterableInfoForEffects(effMember);
                    if (sub != null && sub.kind() != LoweringTargets.ForKind.DYNAMIC_UNION) {
                        boolean[] subFlags = {false, false, false};
                        applyForIterableEffects(w, effMember, sub, iterOrig, ctx, subFlags);
                        flags[2] |= subFlags[2];
                    }
                }
            }
        }
    }

    static Reachability.Origin evalRange(
            EffectBodyWalker w, BabbleParser.RangeExpressionContext range) {
        w.eval(range.left);
        w.eval(range.right);
        Type res = w.ec.checker.rawTypeOf(range);
        if (res == Type.ERROR || w.hasPriorDiagnostic(range)) {
            return Reachability.Origin.IMMUTABLE;
        }
        Type elem = Type.rangeElement(res);
        if (elem != null
                && !Type.isNumericScalar(elem)
                && elem != Type.CHAR
                && elem != Type.STRING) {
            w.applyEffects(w.ec.javaResolver.comparisonProfile(elem, elem));
        }
        return w.ec.javaResolver.isDeeplyImmutable(res)
                ? Reachability.Origin.IMMUTABLE
                : Reachability.Origin.ofUnique(range, null, false);
    }
}
