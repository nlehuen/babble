package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.PipelineRules;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code -O2} E-graph pipeline & algebraic loop rewrite pass ({@code BIP042}, completing {@code
 * BIP014-P5} Half B / {@code O12}).
 *
 * <p>Follows the 4-stage Lift → Saturate → Extract → Lower architecture:
 *
 * <ol>
 *   <li>Fast-skips any statement list containing no {@link HirExpr.For} with zero allocations.
 *   <li>Lifts qualifying single loops and multi-stage {@code list()}/{@code set()} pipelines via
 *       {@link HirPipelineLifter}.
 *   <li>Saturates jointly with {@link PipelineRules#allRules()} under bounded budgets ({@value
 *       #PIPELINE_MAX_ITERS} iterations, {@value #PIPELINE_MAX_NODES} e-nodes).
 *   <li>Extracts the global minimum-cost plan via {@link PipelineRules#costModel()} and lowers via
 *       {@link HirPipelineLowerer} only when {@code extractedCost < originalCost}.
 * </ol>
 */
public final class PipelineRewritePass implements HirPass {
    public static final int PIPELINE_MAX_ITERS = 8;
    public static final int PIPELINE_MAX_NODES = 192;

    @Override
    public String name() {
        return "PipelineRewrite";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] rewrites = {0};
        HirEffectAnalysis moduleFx = HirEffectAnalysis.forModule(module);

        HirModule updated =
                HirRewriter.rewriteModule(
                        module,
                        (ownerId, fnId, stmts) -> {
                            HirFunction fn = fnId >= 0 ? module.function(fnId) : null;
                            HirEffectAnalysis fx =
                                    fn != null
                                            ? HirEffectAnalysis.forFunction(fn, module)
                                            : moduleFx;
                            return rewriteBody(
                                    module, fx, ownerId, stmts, stmts, false, nextVarId, rewrites);
                        },
                        null,
                        () -> nextVarId[0]);

        return updated == module ? Result.unchanged(module) : new Result(updated, rewrites[0]);
    }

    private static List<HirExpr> rewriteBody(
            HirModule module,
            HirEffectAnalysis fx,
            int ownerId,
            List<HirExpr> wholeBody,
            List<HirExpr> stmts,
            boolean valueIgnoredForLast,
            int[] nextVarId,
            int[] rewrites) {
        if (stmts.isEmpty()) {
            return stmts;
        }
        // First recursively rewrite nested blocks/control-flow expressions
        List<HirExpr> rec = null;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = rewriteNested(module, fx, ownerId, wholeBody, old, nextVarId, rewrites);
            if (nw != old) {
                if (rec == null) {
                    rec = new ArrayList<>(stmts);
                }
                rec.set(i, nw);
            }
        }
        List<HirExpr> current = rec != null ? rec : stmts;

        // Zero-allocation fast skip when no For loop exists at this statement level
        if (!containsDirectFor(current)) {
            return current;
        }

        HirPipelineLifter lifter = new HirPipelineLifter(module, fx, wholeBody);
        List<HirExpr> out = null;
        int i = 0;
        while (i < current.size()) {
            HirPipelineLifter.LiftedPipeline lp = lifter.tryLiftAt(current, i, valueIgnoredForLast);
            if (lp != null) {
                List<HirExpr> lowered = optimizeAndLower(lp, ownerId, nextVarId);
                if (lowered != null) {
                    if (out == null) {
                        out = new ArrayList<>(current.size());
                        for (int k = 0; k < i; k++) {
                            out.add(current.get(k));
                        }
                    }
                    out.addAll(lowered);
                    rewrites[0]++;
                    i = lp.endIdx + 1;
                    continue;
                }
            }
            if (out != null) {
                out.add(current.get(i));
            }
            i++;
        }
        return out != null ? out : current;
    }

    private static List<HirExpr> optimizeAndLower(
            HirPipelineLifter.LiftedPipeline lp, int ownerId, int[] nextVarId) {
        EGraph.CostModel costModel = PipelineRules.costModel();
        EGraph.ExtractedTree origTree = lp.graph.extract(lp.rootId, costModel);
        int origCost = evaluateCost(origTree, costModel);

        lp.graph.saturate(PipelineRules.allRules(), PIPELINE_MAX_ITERS, PIPELINE_MAX_NODES);

        EGraph.ExtractedTree bestTree = lp.graph.extract(lp.rootId, costModel);
        int bestCost = evaluateCost(bestTree, costModel);
        if (bestCost >= origCost) {
            return null;
        }
        HirPipelineLowerer lowerer = new HirPipelineLowerer(lp, ownerId, nextVarId);
        return lowerer.lower(bestTree);
    }

    static int evaluateCost(EGraph.ExtractedTree tree, EGraph.CostModel model) {
        int arity = tree.children().size();
        int[] cc = arity == 0 ? EMPTY_COSTS : new int[arity];
        for (int i = 0; i < arity; i++) {
            cc[i] = evaluateCost(tree.children().get(i), model);
        }
        int total = model.cost(tree.node(), cc);
        return (total < 0 || total > 1_000_000) ? 1_000_000 : total;
    }

    private static final int[] EMPTY_COSTS = new int[0];

    private static HirExpr rewriteNested(
            HirModule module,
            HirEffectAnalysis fx,
            int ownerId,
            List<HirExpr> wholeBody,
            HirExpr expr,
            int[] nextVarId,
            int[] rewrites) {
        if (expr == null) {
            return null;
        }
        // Prevent re-wrapping an already-guarded PIPE_GUARD_RANGE For across PassManager rounds
        if (isAlreadyRangeGuardedIf(expr)) {
            return expr;
        }
        if (expr instanceof HirExpr.Block blk) {
            List<HirExpr> nb =
                    rewriteBody(
                            module,
                            fx,
                            ownerId,
                            wholeBody,
                            blk.statements(),
                            blk.meta().kind() == PrimitiveKind.NONE,
                            nextVarId,
                            rewrites);
            if (nb == blk.statements()) {
                return blk;
            }
            return new HirExpr.Block(
                    nb,
                    blk.opensNamespaceScope(),
                    blk.declaredNames(),
                    blk.declaredVars(),
                    blk.innerWideningRank(),
                    blk.meta());
        }
        if (expr instanceof HirExpr.For f) {
            List<HirExpr> nb =
                    rewriteBody(
                            module,
                            fx,
                            ownerId,
                            wholeBody,
                            f.body(),
                            f.meta().kind() == PrimitiveKind.NONE,
                            nextVarId,
                            rewrites);
            if (nb == f.body()) {
                return f;
            }
            return new HirExpr.For(
                    f.iterable(),
                    f.target(),
                    f.loopVar(),
                    f.bodyDeclaredVars(),
                    nb,
                    f.bodyWideningRank(),
                    f.elemSamTarget(),
                    f.countedRange(),
                    f.meta());
        }
        return HirRewriter.rewriteChildren(
                expr, c -> rewriteNested(module, fx, ownerId, wholeBody, c, nextVarId, rewrites));
    }

    private static boolean containsDirectFor(List<HirExpr> stmts) {
        for (HirExpr s : stmts) {
            if (s instanceof HirExpr.For) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognizes the {@code if ($lo <= $hi and f($hi) >= lim) then ( for x in $lo..$hi ... )} guard
     * emitted by {@link HirPipelineLowerer} for {@code PIPE_GUARD_RANGE} so subsequent {@code
     * PassManager} rounds do not re-wrap the same loop ({@code BIP042} §7.3).
     */
    private static boolean isAlreadyRangeGuardedIf(HirExpr expr) {
        if (!(expr instanceof HirExpr.If ifExpr) || ifExpr.elseBranch() != null) {
            return false;
        }
        if (!(ifExpr.cond() instanceof HirExpr.Logic l)
                || l.op() != HirExpr.LogicOp.AND
                || !(l.left() instanceof HirExpr.Binary cmp)
                || cmp.op() != HirExpr.BinaryOp.LTE
                || !(cmp.left() instanceof HirExpr.VarRead loVr)
                || !(cmp.right() instanceof HirExpr.VarRead hiVr)) {
            return false;
        }
        HirExpr thenU = HirRewriter.unwrapTransparent(ifExpr.thenBranch());
        while (thenU instanceof HirExpr.Convert cv && cv.targetKind() == PrimitiveKind.NONE) {
            thenU = HirRewriter.unwrapTransparent(cv.operand());
        }
        if (!(thenU instanceof HirExpr.For f) || f.countedRange() == null) {
            return false;
        }
        HirExpr.CountedRangeLoop cr = f.countedRange();
        return cr.start() instanceof HirExpr.VarRead sVr
                && cr.end() instanceof HirExpr.VarRead eVr
                && sVr.var().id() == loVr.var().id()
                && eVr.var().id() == hiVr.var().id();
    }
}
