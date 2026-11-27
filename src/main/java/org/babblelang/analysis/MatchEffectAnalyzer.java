package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/**
 * Effect, ownership, and case-expression purity analysis for {@code match} expressions ({@code
 * BIP038}). Extracted from {@link EffectBodyWalker} alongside {@link InitEffectAnalyzer}.
 *
 * <p>Every {@code case} pattern expression and {@code if} guard must be side-effect-free (pure): no
 * I/O, no mutation of enclosing variables, fields, parameters, {@code this}, collections, or
 * external state, no moving/freezing/consuming outer {@code unique} variables, no invoking {@code
 * calling}-forwarded callbacks, and no non-local {@code return}/{@code break}/{@code continue}.
 */
final class MatchEffectAnalyzer {

    static final class CasePureFrame {
        final ParserRuleContext root;
        boolean reportedViolation;

        CasePureFrame(ParserRuleContext root) {
            this.root = root;
        }
    }

    private MatchEffectAnalyzer() {}

    static Reachability.Origin evalMatch(
            EffectBodyWalker w, BabbleParser.MatchExpressionContext ctx) {
        Reachability.Origin scrutOrig = w.eval(ctx.scrutinee);
        Type scrutType = w.effectiveType(ctx.scrutinee);
        if (ctx.elseClause() == null
                && MatchExhaustiveness.containsExternalClosedJavaType(scrutType)) {
            // Runtime defense-in-depth throw if external Java enum/sealed adds a variant (BIP038
            // §5.3).
            w.nothrow = false;
        }
        Reachability.Origin activeScrutOrig = scrutOrig;
        Map<ParserRuleContext, Reachability.Origin> fallthroughEnv = new IdentityHashMap<>(w.env);
        List<Map<ParserRuleContext, Reachability.Origin>> armEnvs = new ArrayList<>();
        Reachability.Origin resultOrig = null;

        for (BabbleParser.CaseClauseContext cc : ctx.caseClause()) {
            w.env = new IdentityHashMap<>(fallthroughEnv);
            runCasePure(
                    w,
                    cc.pattern(),
                    () -> {
                        evalPatternEffects(w, cc.pattern(), activeScrutOrig, scrutType, true);
                        return null;
                    });
            if (cc.guard != null) {
                Map<ParserRuleContext, Reachability.Origin> postPatternEnv =
                        new IdentityHashMap<>(w.env);
                runCasePure(
                        w,
                        cc.guard,
                        () -> {
                            w.eval(cc.guard);
                            return null;
                        });
                // Guard cannot mutate or move outer/pattern state; restore pre-guard ownership so
                // confined guard-local aliases do not spuriously demote outer unique bindings.
                w.env = postPatternEnv;
            }
            Reachability.Origin armOrig = w.eval(cc.armBlock);
            armEnvs.add(w.env);
            resultOrig = resultOrig == null ? armOrig : resultOrig.join(armOrig);
        }
        if (ctx.elseClause() != null) {
            w.env = new IdentityHashMap<>(fallthroughEnv);
            BabbleParser.ElseClauseContext ecNode = ctx.elseClause();
            if (ecNode.name != null) {
                w.env.put(ecNode, bindPatternOrigin(w, ecNode, activeScrutOrig, true));
            }
            Reachability.Origin elseOrig = w.eval(ecNode.elseBlock);
            armEnvs.add(w.env);
            resultOrig = resultOrig == null ? elseOrig : resultOrig.join(elseOrig);
        } else {
            armEnvs.add(fallthroughEnv);
        }
        Map<ParserRuleContext, Reachability.Origin> merged = armEnvs.get(0);
        for (int i = 1; i < armEnvs.size(); i++) {
            merged = w.mergeEnvs(merged, armEnvs.get(i));
        }
        w.env = merged;
        return resultOrig != null ? resultOrig : Reachability.Origin.IMMUTABLE;
    }

    private static <T> T runCasePure(
            EffectBodyWalker w, ParserRuleContext root, Supplier<T> action) {
        boolean prevHermetic = w.hermetic;
        w.hermetic = true;
        CasePureFrame frame = new CasePureFrame(root);
        w.casePureStack.push(frame);
        T res;
        try {
            res = action.get();
        } finally {
            w.casePureStack.pop();
        }
        boolean innerHermetic = w.hermetic;
        w.hermetic = prevHermetic && innerHermetic;
        if (!innerHermetic && !frame.reportedViolation) {
            reportCaseViolation(
                    w, "Case expression must be pure : performs I/O or external mutation", root);
        }
        return res;
    }

    private static Reachability.Origin bindPatternOrigin(
            EffectBodyWalker w,
            ParserRuleContext decl,
            Reachability.Origin sourceOrig,
            boolean isTopLevel) {
        Type boundType = w.ec.checker.typeOfDeclarationForEffects(decl);
        if (boundType == null || w.ec.javaResolver.isDeeplyImmutable(boundType)) {
            return Reachability.Origin.IMMUTABLE;
        }
        if (sourceOrig.moved) {
            return Reachability.Origin.MOVED;
        }
        // Consuming scrutinee (`match move(x)` or fresh construction): uniqueOwner == null
        // transfers @UNIQUE to top-level bindings.
        if (isTopLevel && sourceOrig.unique && sourceOrig.uniqueOwner == null) {
            return new Reachability.Origin(
                    (BitSet) sourceOrig.params.clone(),
                    sourceOrig.aliasesThis,
                    sourceOrig.external,
                    true,
                    sourceOrig.elements(),
                    false,
                    decl,
                    Set.of(decl),
                    new Reachability.PathKey(decl, List.of()));
        }
        // Borrowing scrutinee (`match x`) or nested destructured field (`is R(def f)`):
        // inherit @BORROWED provenance pointing back to scrutinee without stealing uniqueness or
        // demoting `x` (BIP038-D9).
        Reachability.Origin flat = sourceOrig.flattenElements();
        Set<Object> keys = new HashSet<>(flat.directKeys);
        keys.add(decl);
        return new Reachability.Origin(
                (BitSet) flat.params.clone(),
                flat.aliasesThis,
                flat.external,
                false,
                null,
                false,
                null,
                Set.copyOf(keys),
                new Reachability.PathKey(decl, List.of()));
    }

    private static void evalPatternEffects(
            EffectBodyWalker w,
            BabbleParser.PatternContext pat,
            Reachability.Origin scrutOrig,
            Type scrutType,
            boolean isTopLevel) {
        for (BabbleParser.AtomicPatternContext atom : pat.atomicPattern()) {
            switch (atom) {
                case BabbleParser.ValuePatternContext vp -> {
                    if (BlockScopes.isWildcardExpr(vp.expr)) {
                        continue;
                    }
                    LoweringMetadata.RangePatternSource rps = w.ec.checker.matchRangePattern(vp);
                    if (rps != null) {
                        w.eval(rps.lo());
                        w.eval(rps.hi());
                        if (rps.step() != null) {
                            w.eval(rps.step());
                            if (!EffectBodyWalker.isPositiveIntegerConstant(rps.step())) {
                                w.nothrow = false;
                            }
                        }
                        if (rps.elemKind() == PrimitiveKind.NONE && !rps.stringElement()) {
                            Type elem = Type.withoutNull(scrutType);
                            w.applyEffects(w.ec.javaResolver.comparisonProfile(elem, elem));
                        }
                    } else {
                        w.eval(vp.expr);
                        w.applyEffects(
                                w.ec.javaResolver.equalityProfile(
                                        scrutType, w.effectiveType(vp.expr)));
                    }
                }
                case BabbleParser.RelationalPatternContext rp -> {
                    w.eval(rp.rhs);
                    Type rhsType = w.effectiveType(rp.rhs);
                    String op = rp.op.getText();
                    if ("==".equals(op) || "!=".equals(op) || "<>".equals(op)) {
                        w.applyEffects(w.ec.javaResolver.equalityProfile(scrutType, rhsType));
                    } else {
                        w.applyEffects(
                                w.ec.javaResolver.comparisonProfile(
                                        Type.withoutNull(scrutType), rhsType));
                    }
                }
                case BabbleParser.MembershipPatternContext mp -> {
                    Reachability.Origin contOrig = w.eval(mp.container);
                    Type contType = w.effectiveType(mp.container);
                    LoweringTargets.MembershipTarget mt = w.ec.checker.matchMembershipTarget(mp);
                    if (mt != null) {
                        switch (mt.kind()) {
                            case DYNAMIC -> {
                                w.recordTargetRead(contOrig, contType);
                                w.deterministic = false;
                                w.nothrow = false;
                            }
                            case ARRAY -> w.recordTargetRead(contOrig, contType);
                            case COLLECTION -> {
                                w.recordTargetRead(contOrig, contType);
                                Type unwrapped = TypeAlgebra.unwrapFrozen(contType);
                                Class<?> cls =
                                        unwrapped instanceof Type.JavaInstance ji
                                                ? ji.type()
                                                : java.util.Collection.class;
                                w.applyEffects(
                                        w.ec.javaResolver
                                                .resolveJavaMethodProfile(
                                                        cls,
                                                        "contains",
                                                        JavaEffectResolver.lookupPublicMethod(
                                                                cls, "contains", Object.class))
                                                .effects());
                            }
                            case MAP -> {
                                w.recordTargetRead(contOrig, contType);
                                Type unwrapped = TypeAlgebra.unwrapFrozen(contType);
                                Class<?> cls =
                                        unwrapped instanceof Type.JavaInstance ji
                                                ? ji.type()
                                                : java.util.Map.class;
                                w.applyEffects(
                                        w.ec.javaResolver
                                                .resolveJavaMethodProfile(
                                                        cls,
                                                        "containsKey",
                                                        JavaEffectResolver.lookupPublicMethod(
                                                                cls, "containsKey", Object.class))
                                                .effects());
                            }
                            case RANGE -> {}
                        }
                    }
                }
                case BabbleParser.BindingPatternContext bp -> {
                    Reachability.Origin bound = bindPatternOrigin(w, bp, scrutOrig, isTopLevel);
                    w.env.put(bp, bound);
                    if (bp.patternArgs() != null) {
                        evalPatternArgsEffects(w, bp, bp.patternArgs(), scrutOrig, scrutType);
                    }
                }
                case BabbleParser.TypeOrDestructurePatternContext tp -> {
                    if (tp.patternArgs() != null) {
                        evalPatternArgsEffects(w, tp, tp.patternArgs(), scrutOrig, scrutType);
                    }
                }
                default -> {}
            }
        }
    }

    private static void evalPatternArgsEffects(
            EffectBodyWalker w,
            ParserRuleContext owner,
            BabbleParser.PatternArgsContext args,
            Reachability.Origin scrutOrig,
            Type scrutType) {
        MatchTyper.RecordDestructureInfo recInfo = w.ec.checker.matchRecordDestructure(owner);
        MatchTyper.SequenceDestructureInfo seqInfo = w.ec.checker.matchSequenceDestructure(owner);
        if (recInfo != null && recInfo.isJavaRecord()) {
            for (java.lang.reflect.Method acc : recInfo.javaAccessors()) {
                w.applyEffects(
                        w.ec.javaResolver
                                .resolveJavaMethodProfile(
                                        acc.getDeclaringClass(), acc.getName(), acc)
                                .effects());
            }
        }
        if (seqInfo != null) {
            w.recordTargetRead(scrutOrig, scrutType);
        }
        List<BabbleParser.PatternArgContext> argList = args.patternArg();
        for (int i = 0; i < argList.size(); i++) {
            Type subType = Type.NULLABLE_ANY;
            if (recInfo != null && i < recInfo.fieldTypes().size()) {
                subType = recInfo.fieldTypes().get(i);
            } else if (seqInfo != null) {
                subType = seqInfo.elemType();
            }
            evalPatternEffects(w, argList.get(i).pattern(), scrutOrig, subType, false);
        }
        if (args.restPattern() != null && args.restPattern().name != null) {
            BabbleParser.RestPatternContext rp = args.restPattern();
            Reachability.Provenance elemProv = Reachability.Provenance.EMPTY;
            Set<Reachability.PathKey> restElemPaths = Set.of();
            boolean restUnique = true;
            if (seqInfo != null && !w.ec.javaResolver.isDeeplyImmutable(seqInfo.elemType())) {
                Reachability.Origin flat = scrutOrig.flattenElements();
                elemProv =
                        new Reachability.Provenance(flat.params, flat.aliasesThis, flat.external);
                restElemPaths =
                        Reachability.unionPaths(
                                Reachability.truncatePaths(scrutOrig.paths, scrutOrig.directKeys),
                                scrutOrig.elementPaths);
                restUnique = scrutOrig.unique && scrutOrig.uniqueOwner == null;
            }
            w.env.put(
                    rp,
                    new Reachability.Origin(
                            Reachability.Origin.EMPTY_BITS,
                            false,
                            false,
                            restUnique,
                            elemProv,
                            false,
                            rp,
                            Set.of(rp),
                            Set.of(new Reachability.PathKey(rp, List.of())),
                            restElemPaths));
        }
    }

    // ---------------------------------------------------------------- case-expression purity

    static void reportCaseViolation(EffectBodyWalker w, String msg, ParserRuleContext at) {
        for (CasePureFrame f : w.casePureStack) {
            f.reportedViolation = true;
        }
        w.reportCasePureOnce(msg, at);
    }

    static boolean isInsideSubtree(ParserRuleContext node, ParserRuleContext root) {
        for (ParserRuleContext cur = node; cur != null; cur = cur.getParent()) {
            if (cur == root) {
                return true;
            }
        }
        return false;
    }

    static boolean isOwnedInsideCaseExpr(
            Reachability.Origin target, ParserRuleContext root, boolean shallow) {
        if (!(target.unique || (target.localFresh() && shallow))
                || target.external
                || !target.params.isEmpty()
                || target.aliasesThis) {
            return false;
        }
        if (!shallow && target.hasElementTaint()) {
            return false;
        }
        if (target.uniqueOwner != null) {
            return target.uniqueOwner instanceof ParserRuleContext ctx
                    && isInsideSubtree(ctx, root);
        }
        if (target.directKeys.isEmpty()) {
            return false;
        }
        for (Object k : target.directKeys) {
            if (!(k instanceof ParserRuleContext ctx) || !isInsideSubtree(ctx, root)) {
                return false;
            }
        }
        return true;
    }

    static void checkCaseVarAssign(
            EffectBodyWalker w, Object resolved, String name, ParserRuleContext at) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        ParserRuleContext decl = resolved instanceof ParserRuleContext p ? p : null;
        if (decl == null || !isInsideSubtree(decl, w.casePureStack.peek().root)) {
            reportCaseViolation(
                    w, "Case expression must be pure : cannot assign to variable " + name, at);
        }
    }

    static void checkCaseConstructingFieldAssign(
            EffectBodyWalker w,
            BabbleParser.ExpressionContext recv,
            String fieldName,
            ParserRuleContext at) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        ScopeNode ts = BlockScopes.thisScope(w.ec.checker.occurrenceScopeOf(recv));
        if (ts == null
                || !(ts.owner instanceof ParserRuleContext ownerCtx)
                || !isInsideSubtree(ownerCtx, w.casePureStack.peek().root)) {
            reportCaseViolation(
                    w,
                    "Case expression must be pure : cannot perform field assignment ." + fieldName,
                    at);
        }
    }

    static void checkCaseFieldAssign(EffectBodyWalker w, String fieldName, ParserRuleContext at) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        reportCaseViolation(
                w,
                "Case expression must be pure : cannot perform field assignment ." + fieldName,
                at);
    }

    static void checkCaseTargetMutation(
            EffectBodyWalker w,
            Reachability.Origin target,
            String reason,
            ParserRuleContext at,
            boolean shallow) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        if (!isOwnedInsideCaseExpr(target, w.casePureStack.peek().root, shallow)) {
            reportCaseViolation(w, "Case expression must be pure : cannot perform " + reason, at);
        }
    }

    static void checkCaseConsume(
            EffectBodyWalker w, ParserRuleContext decl, String verb, ParserRuleContext at) {
        if (w.casePureStack.isEmpty() || decl == null) {
            return;
        }
        if (!isInsideSubtree(decl, w.casePureStack.peek().root)) {
            reportCaseViolation(
                    w,
                    "Case expression must be pure : cannot " + verb + " variable " + at.getText(),
                    at);
        }
    }

    static void checkCaseForwardedCallback(
            EffectBodyWalker w, BabbleParser.ExpressionContext expr) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        reportCaseViolation(
                w,
                "Case expression must be pure : cannot invoke calling parameter " + expr.getText(),
                expr);
    }

    static void checkCaseFrameWrite(EffectBodyWalker w, ParserRuleContext at) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        ParserRuleContext reportAt = at != null ? at : w.casePureStack.peek().root;
        reportCaseViolation(
                w,
                "Case expression must be pure : cannot mutate outer variable via closure",
                reportAt);
    }

    static void checkCaseControlFlow(EffectBodyWalker w, ParserRuleContext at, String kind) {
        if (w.casePureStack.isEmpty()) {
            return;
        }
        if ("return".equals(kind)) {
            reportCaseViolation(
                    w, "Case expression must be pure : cannot return from case expression", at);
            return;
        }
        ParserRuleContext root = w.casePureStack.peek().root;
        ParserRuleContext loop = null;
        for (ParserRuleContext cur = at.getParent(); cur != null; cur = cur.getParent()) {
            if (cur instanceof BabbleParser.FunctionLiteralContext) {
                break;
            }
            if (cur instanceof BabbleParser.WhileExpressionContext
                    || cur instanceof BabbleParser.ForExpressionContext) {
                loop = cur;
                break;
            }
        }
        if (loop == null || !isInsideSubtree(loop, root)) {
            reportCaseViolation(
                    w,
                    "Case expression must be pure : cannot " + kind + " out of case expression",
                    at);
        }
    }
}
