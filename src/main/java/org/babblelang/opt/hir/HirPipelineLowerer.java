package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.egraph.PipelineRules;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirRewriter;

/**
 * Lowers an extracted minimum-cost pipeline {@link EGraph.ExtractedTree} back to verified,
 * Wasm-portable structured HIR ({@code BIP042-P3}, {@code BIP042-D5}).
 *
 * <p>Emits strictly standard HIR nodes ({@link HirExpr.For}, {@link HirExpr.While}, {@link
 * HirExpr.If}, {@link HirExpr.Break}, {@link HirExpr.Block}, {@link HirExpr.Def}, {@link
 * HirExpr.VarAssign}, {@link HirExpr.Convert}), wraps only the rewritten slice in a tight {@link
 * HirExpr.Block} when hoisted locals are introduced, and maintains single-owner {@code
 * declaredVars} placement.
 */
final class HirPipelineLowerer {
    private final HirPipelineLifter.LiftedPipeline lp;
    private final int ownerMethodId;
    private final int[] nextVarId;
    private final Map<String, HirVar> binderToVar = new HashMap<>();
    private final Map<Integer, HirVar> varSubst = new HashMap<>();
    private final List<HirExpr> hoistedDefs = new ArrayList<>();
    private final List<HirVar> hoistedVars = new ArrayList<>();
    private int nextTempIdx = 0;

    HirPipelineLowerer(HirPipelineLifter.LiftedPipeline lp, int ownerMethodId, int[] nextVarId) {
        this.lp = lp;
        this.ownerMethodId = ownerMethodId;
        this.nextVarId = nextVarId;
    }

    /**
     * Lowers {@code best} into replacement statements for {@code stmts[lp.startIdx..lp.endIdx]},
     * returning {@code null} if {@code best} cannot be soundly lowered.
     */
    List<HirExpr> lower(EGraph.ExtractedTree best) {
        ENode root = best.node();
        if (root.op() == ENode.Op.PIPE_FOLD_PAIRWISE) {
            return lowerPairwiseFold(best);
        }
        if (root.op() == ENode.Op.PIPE_FOLD) {
            return lowerSequentialFold(best);
        }
        if (root.op() == ENode.Op.PIPE_FOR_EACH) {
            return lowerForEach(best);
        }
        return null;
    }

    // ---------------------------------------------------------------- PIPE_FOR_EACH

    private List<HirExpr> lowerForEach(EGraph.ExtractedTree root) {
        String sinkId = root.node().symbol();
        HirPipelineLifter.SinkInfo sink = lp.sinks.get(sinkId);
        if (sink == null) {
            return null;
        }
        return lowerStreamLoop(
                root.children().get(0),
                sink.statements(),
                sink.consumerFor(),
                sink.bodyDeclaredVars());
    }

    // ---------------------------------------------------------------- PIPE_FOLD (sequential)

    private List<HirExpr> lowerSequentialFold(EGraph.ExtractedTree root) {
        HirPipelineLifter.FoldInfo fi = lp.folds.get(root.node().symbol());
        if (fi == null) {
            return null;
        }
        EGraph.ExtractedTree streamTree = root.children().get(0);
        EGraph.ExtractedTree elemTree = root.children().get(1);
        List<EGraph.ExtractedTree> stages = collectStages(streamTree);
        if (stages == null || stages.isEmpty()) {
            return null;
        }
        HirPipelineLifter.SourceInfo src = resolveSource(stages.get(0));
        if (src == null) {
            return null;
        }
        // Pre-assign binders so we can lower elemTree into the fold sink statement
        Map<EGraph.ExtractedTree, HirVar> distinctElemVars = new IdentityHashMap<>();
        List<HirVar> mapDeclaredVars = new ArrayList<>();
        if (!assignStageBinders(stages, src, distinctElemVars, mapDeclaredVars)) {
            return null;
        }
        int line = fi.assignTemplate().meta().line();
        HirExpr elemExpr = lowerExpr(elemTree, line, null, null);
        if (elemExpr == null) {
            return null;
        }
        HirExpr foldStmt = buildFoldAssign(fi, elemExpr);
        return lowerStreamLoopPreAssigned(
                stages,
                src,
                distinctElemVars,
                mapDeclaredVars,
                List.of(foldStmt),
                src.originFor(),
                lp.allLoopDeclaredVars);
    }

    // ---------------------------------------------------------------- PIPE_FOLD_PAIRWISE (R8)

    private List<HirExpr> lowerPairwiseFold(EGraph.ExtractedTree root) {
        HirPipelineLifter.FoldInfo fi = lp.folds.get(root.node().symbol());
        if (fi == null) {
            return null;
        }
        EGraph.ExtractedTree rangeTree = root.children().get(0);
        EGraph.ExtractedTree elemTree = root.children().get(1);
        ENode rangeNode = rangeTree.node();
        if (rangeNode.op() != ENode.Op.PIPE_RANGE
                || !PipelineRules.isAscendingRange(rangeNode.symbol())
                || !rangeNode.isIntegral()) {
            return null;
        }
        String rangeBinder = PipelineRules.rangeBinder(rangeNode.symbol());
        if (rangeBinder == null) {
            return null;
        }
        boolean incl = PipelineRules.isInclusiveRange(rangeNode.symbol());
        PrimitiveKind rk = rangeNode.kind();
        int line = fi.assignTemplate().meta().line();

        HirExpr loExpr = lowerExpr(rangeTree.children().get(0), line, null, null);
        HirExpr hiExpr = lowerExpr(rangeTree.children().get(1), line, null, null);
        if (loExpr == null || hiExpr == null) {
            return null;
        }
        loExpr = adaptKind(loExpr, rk, line);
        hiExpr = adaptKind(hiExpr, rk, line);

        HirVar loVar = freshDefVar("$lo", rk);
        HirVar hiVar = freshDefVar("$hi", rk);
        HirVar idxVar = freshMutVar("$i", rk);
        hoistedVars.add(loVar);
        hoistedVars.add(hiVar);
        hoistedVars.add(idxVar);
        hoistedDefs.add(makeDef(loVar, loExpr, line));
        hoistedDefs.add(makeDef(hiVar, hiExpr, line));
        hoistedDefs.add(makeDef(idxVar, varRead(loVar, line), line));

        HirExpr iRead = varRead(idxVar, line);
        HirExpr hiRead = varRead(hiVar, line);
        HirExpr one = integralConst(rk, 1L, line);
        HirExpr two = integralConst(rk, 2L, line);

        HirExpr iPlus1 = binaryExpr(HirExpr.BinaryOp.ADD, iRead, one, rk, line);
        HirExpr elem0 = lowerExpr(elemTree, line, rangeBinder, iRead);
        HirExpr elem1 = lowerExpr(elemTree, line, rangeBinder, iPlus1);
        if (elem0 == null || elem1 == null) {
            return null;
        }

        // while ($i < $hi and $i < $hi - 1) then ( acc = op(acc, op(elem($i), elem($i+1))); $i = $i
        // + 2 )
        HirExpr hiMinus1 = binaryExpr(HirExpr.BinaryOp.SUB, hiRead, one, rk, line);
        HirExpr condLeft = binaryExpr(HirExpr.BinaryOp.LT, iRead, hiRead, PrimitiveKind.BOOL, line);
        HirExpr condRight =
                binaryExpr(HirExpr.BinaryOp.LT, iRead, hiMinus1, PrimitiveKind.BOOL, line);
        HirExpr whileCond =
                new HirExpr.Logic(
                        HirExpr.LogicOp.AND,
                        condLeft,
                        condRight,
                        false,
                        ExprMeta.of(line, PrimitiveKind.BOOL));

        HirExpr pairOp = buildAssocCall(fi.callTemplate(), elem0, elem1);
        HirExpr pairAssign = buildFoldAssign(fi, pairOp);
        HirExpr stepAssign =
                new HirExpr.VarAssign(
                        idxVar,
                        null,
                        binaryExpr(HirExpr.BinaryOp.ADD, iRead, two, rk, line),
                        idxVar.name(),
                        "",
                        ExprMeta.of(line, rk));
        HirExpr whileBody =
                new HirExpr.Block(
                        List.of(pairAssign, ensureKind(stepAssign, PrimitiveKind.NONE, line)),
                        false,
                        new String[0],
                        List.of(),
                        -1,
                        ExprMeta.of(line, PrimitiveKind.NONE));
        HirExpr whileExpr =
                new HirExpr.While(whileCond, whileBody, ExprMeta.of(line, PrimitiveKind.NONE));

        // Overflow-free epilogue (BIP042 §7.2 Proof 2)
        HirExpr epilogue;
        if (!incl) {
            // Exclusive (lo..<hi): if ($i < $hi) then ( acc = op(acc, elem($i)) )
            HirExpr singleAssign =
                    ensureKind(
                            buildFoldAssign(fi, lowerExpr(elemTree, line, rangeBinder, iRead)),
                            PrimitiveKind.NONE,
                            line);
            epilogue =
                    new HirExpr.If(
                            binaryExpr(
                                    HirExpr.BinaryOp.LT, iRead, hiRead, PrimitiveKind.BOOL, line),
                            singleAssign,
                            null,
                            ExprMeta.of(line, PrimitiveKind.NONE));
        } else {
            // Inclusive (lo..hi):
            // if ($i < $hi) then ( acc = op(acc, op(elem($i), elem($i + 1))) )
            // else ( if ($i == $hi) then ( acc = op(acc, elem($i)) ) )
            HirExpr epElem0 = lowerExpr(elemTree, line, rangeBinder, iRead);
            HirExpr epElem1 = lowerExpr(elemTree, line, rangeBinder, iPlus1);
            HirExpr epPair =
                    ensureKind(
                            buildFoldAssign(
                                    fi, buildAssocCall(fi.callTemplate(), epElem0, epElem1)),
                            PrimitiveKind.NONE,
                            line);
            HirExpr epSingle =
                    ensureKind(
                            buildFoldAssign(fi, lowerExpr(elemTree, line, rangeBinder, iRead)),
                            PrimitiveKind.NONE,
                            line);
            HirExpr eqIf =
                    new HirExpr.If(
                            binaryExpr(
                                    HirExpr.BinaryOp.EQ, iRead, hiRead, PrimitiveKind.BOOL, line),
                            epSingle,
                            null,
                            ExprMeta.of(line, PrimitiveKind.NONE));
            epilogue =
                    new HirExpr.If(
                            binaryExpr(
                                    HirExpr.BinaryOp.LT, iRead, hiRead, PrimitiveKind.BOOL, line),
                            epPair,
                            eqIf,
                            ExprMeta.of(line, PrimitiveKind.NONE));
        }

        List<HirExpr> blockStmts = new ArrayList<>(hoistedDefs.size() + 2);
        blockStmts.addAll(hoistedDefs);
        blockStmts.add(whileExpr);
        blockStmts.add(epilogue);
        HirExpr tightBlock =
                new HirExpr.Block(
                        blockStmts,
                        false,
                        new String[0],
                        List.copyOf(hoistedVars),
                        -1,
                        ExprMeta.of(line, PrimitiveKind.NONE));
        return wrapWithPreservedDefs(tightBlock);
    }

    // ---------------------------------------------------------------- stream loop lowering

    private List<HirExpr> lowerStreamLoop(
            EGraph.ExtractedTree streamTree,
            List<HirExpr> rawSinkStmts,
            HirExpr.For consumerFor,
            List<HirVar> bodyDeclaredVars) {
        List<EGraph.ExtractedTree> stages = collectStages(streamTree);
        if (stages == null || stages.isEmpty()) {
            return null;
        }
        HirPipelineLifter.SourceInfo src = resolveSource(stages.get(0));
        if (src == null) {
            return null;
        }
        Map<EGraph.ExtractedTree, HirVar> distinctElemVars = new IdentityHashMap<>();
        List<HirVar> mapDeclaredVars = new ArrayList<>();
        if (!assignStageBinders(stages, src, distinctElemVars, mapDeclaredVars)) {
            return null;
        }
        return lowerStreamLoopPreAssigned(
                stages,
                src,
                distinctElemVars,
                mapDeclaredVars,
                rawSinkStmts,
                consumerFor,
                bodyDeclaredVars);
    }

    private List<HirExpr> lowerStreamLoopPreAssigned(
            List<EGraph.ExtractedTree> stages,
            HirPipelineLifter.SourceInfo src,
            Map<EGraph.ExtractedTree, HirVar> distinctElemVars,
            List<HirVar> mapDeclaredVars,
            List<HirExpr> rawSinkStmts,
            HirExpr.For consumerFor,
            List<HirVar> bodyDeclaredVars) {
        HirExpr.For originFor = src.originFor();
        int line = consumerFor.meta().line();
        EGraph.ExtractedTree rootStage = stages.get(0);

        // Check if stage 1 is PIPE_GUARD_RANGE
        HirExpr outerRangeGuard = null;
        HirExpr.CountedRangeLoop newCountedRange = originFor.countedRange();
        if (rootStage.node().op() == ENode.Op.PIPE_RANGE) {
            String spec = rootStage.node().symbol();
            PrimitiveKind rk = rootStage.node().kind();
            boolean incl = PipelineRules.isInclusiveRange(spec);
            boolean desc = PipelineRules.isDescendingRange(spec);
            HirExpr loExpr = lowerExpr(rootStage.children().get(0), line, null, null);
            HirExpr hiExpr = lowerExpr(rootStage.children().get(1), line, null, null);
            if (loExpr == null || hiExpr == null) {
                return null;
            }
            loExpr = adaptKind(loExpr, rk, line);
            hiExpr = adaptKind(hiExpr, rk, line);

            if (stages.size() > 1 && stages.get(1).node().op() == ENode.Op.PIPE_GUARD_RANGE) {
                // Hoist $lo and $hi once for both guard and CountedRangeLoop
                HirVar loVar = freshDefVar("$lo", rk);
                HirVar hiVar = freshDefVar("$hi", rk);
                hoistedVars.add(loVar);
                hoistedVars.add(hiVar);
                hoistedDefs.add(makeDef(loVar, loExpr, line));
                hoistedDefs.add(makeDef(hiVar, hiExpr, line));
                HirExpr loRead = varRead(loVar, line);
                HirExpr hiRead = varRead(hiVar, line);
                outerRangeGuard =
                        lowerRangeGuard(stages.get(1).children().get(2), loRead, hiRead, line);
                if (outerRangeGuard == null) {
                    return null;
                }
                newCountedRange =
                        new HirExpr.CountedRangeLoop(loRead, hiRead, null, incl, desc, rk);
            } else {
                newCountedRange =
                        new HirExpr.CountedRangeLoop(loExpr, hiExpr, null, incl, desc, rk);
            }
        }

        // Substitute remapped/aliased variables in sink statements
        List<HirExpr> body = substituteVarsList(rawSinkStmts);

        // Build nested loop body from outermost stage back to innermost stage (index 1)
        for (int i = stages.size() - 1; i >= 1; i--) {
            EGraph.ExtractedTree stage = stages.get(i);
            ENode sn = stage.node();
            switch (sn.op()) {
                case PIPE_MAP -> {
                    HirVar mapVar = binderToVar.get(sn.symbol());
                    if (mapVar == null) {
                        return null;
                    }
                    HirExpr rhs = lowerExpr(stage.children().get(1), line, null, null);
                    if (rhs == null) {
                        return null;
                    }
                    rhs = adaptKind(rhs, mapVar.primitiveKind(), line);
                    HirExpr def = makeDef(mapVar, rhs, line);
                    List<HirExpr> nextBody = new ArrayList<>(body.size() + 1);
                    nextBody.add(def);
                    nextBody.addAll(body);
                    body = nextBody;
                }
                case PIPE_FILTER, PIPE_GUARD_RANGE -> {
                    HirExpr pred = lowerExpr(stage.children().get(1), line, null, null);
                    if (pred == null) {
                        return null;
                    }
                    pred = ensureKind(pred, PrimitiveKind.BOOL, line);
                    HirExpr thenBranch = wrapThenBranch(body, line);
                    body =
                            List.of(
                                    new HirExpr.If(
                                            pred,
                                            thenBranch,
                                            null,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                case PIPE_TAKE_WHILE -> {
                    HirExpr pred = lowerExpr(stage.children().get(1), line, null, null);
                    if (pred == null) {
                        return null;
                    }
                    pred = ensureKind(pred, PrimitiveKind.BOOL, line);
                    HirExpr thenBranch = wrapThenBranch(body, line);
                    HirExpr elseBreak =
                            new HirExpr.Break(null, ExprMeta.of(line, PrimitiveKind.NONE));
                    body =
                            List.of(
                                    new HirExpr.If(
                                            pred,
                                            thenBranch,
                                            elseBreak,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                case PIPE_DISTINCT -> {
                    HirPipelineLifter.DistinctInfo di = lp.distincts.get(sn.symbol());
                    HirVar elemVar = distinctElemVars.get(stage);
                    if (di == null || elemVar == null) {
                        return null;
                    }
                    HirExpr seenAdd = buildDistinctAddCall(di, elemVar, line);
                    HirExpr thenBranch = wrapThenBranch(body, line);
                    body =
                            List.of(
                                    new HirExpr.If(
                                            seenAdd,
                                            thenBranch,
                                            null,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                default -> {
                    return null;
                }
            }
        }

        // Assemble deduplicated single-owner For.bodyDeclaredVars (LoopEmitter expects loopVar in
        // it)
        Set<Integer> nestedDeclared = new HashSet<>();
        for (HirExpr s : body) {
            collectNestedDeclaredVars(s, nestedDeclared);
        }
        Map<Integer, HirVar> finalDeclared = new LinkedHashMap<>();
        finalDeclared.put(src.loopVar().id(), src.loopVar());
        for (HirVar v : bodyDeclaredVars) {
            if (!varSubst.containsKey(v.id()) && !nestedDeclared.contains(v.id())) {
                finalDeclared.putIfAbsent(v.id(), v);
            }
        }
        for (HirVar v : mapDeclaredVars) {
            if (!nestedDeclared.contains(v.id())) {
                finalDeclared.putIfAbsent(v.id(), v);
            }
        }

        HirExpr emittedFor =
                new HirExpr.For(
                        newCountedRange != null ? null : originFor.iterable(),
                        originFor.target(),
                        originFor.loopVar(),
                        List.copyOf(finalDeclared.values()),
                        body,
                        consumerFor.bodyWideningRank(),
                        originFor.elemSamTarget(),
                        newCountedRange,
                        consumerFor.meta());

        HirExpr core =
                outerRangeGuard != null
                        ? new HirExpr.If(
                                outerRangeGuard,
                                ensureKind(emittedFor, PrimitiveKind.NONE, line),
                                null,
                                ExprMeta.of(line, PrimitiveKind.NONE))
                        : emittedFor;

        if (hoistedVars.isEmpty()) {
            return wrapWithPreservedDefs(core);
        }
        List<HirExpr> blockStmts = new ArrayList<>(hoistedDefs.size() + 1);
        blockStmts.addAll(hoistedDefs);
        blockStmts.add(core);
        HirExpr tightBlock =
                new HirExpr.Block(
                        blockStmts,
                        false,
                        new String[0],
                        List.copyOf(hoistedVars),
                        -1,
                        core.meta().withWideningRank(-1));
        return wrapWithPreservedDefs(tightBlock);
    }

    private List<HirExpr> wrapWithPreservedDefs(HirExpr rewrittenPipeline) {
        if (lp.preservedInterveningDefs.isEmpty()) {
            return List.of(rewrittenPipeline);
        }
        List<HirExpr> out = new ArrayList<>(lp.preservedInterveningDefs.size() + 1);
        out.addAll(lp.preservedInterveningDefs);
        out.add(rewrittenPipeline);
        return out;
    }

    // ---------------------------------------------------------------- stage extraction & binders

    private static List<EGraph.ExtractedTree> collectStages(EGraph.ExtractedTree streamTree) {
        List<EGraph.ExtractedTree> rev = new ArrayList<>();
        EGraph.ExtractedTree cur = streamTree;
        while (cur != null) {
            ENode.Op op = cur.node().op();
            if (op == ENode.Op.PIPE_MATERIALIZE) {
                return null;
            }
            rev.add(cur);
            if (op == ENode.Op.PIPE_SRC || op == ENode.Op.PIPE_RANGE) {
                break;
            }
            if (cur.children().isEmpty()) {
                return null;
            }
            cur = cur.children().get(0);
        }
        List<EGraph.ExtractedTree> ordered = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) {
            ordered.add(rev.get(i));
        }
        return ordered;
    }

    private HirPipelineLifter.SourceInfo resolveSource(EGraph.ExtractedTree rootStage) {
        ENode n = rootStage.node();
        if (n.op() == ENode.Op.PIPE_RANGE) {
            return lp.sources.get(n.symbol());
        }
        if (n.op() == ENode.Op.PIPE_SRC) {
            return lp.sources.get(PipelineRules.srcId(n.symbol()));
        }
        return null;
    }

    private boolean assignStageBinders(
            List<EGraph.ExtractedTree> stages,
            HirPipelineLifter.SourceInfo src,
            Map<EGraph.ExtractedTree, HirVar> distinctElemVars,
            List<HirVar> mapDeclaredVars) {
        HirVar curVar = src.loopVar();
        binderToVar.put(src.binderSym(), curVar);

        for (int i = 1; i < stages.size(); i++) {
            EGraph.ExtractedTree stage = stages.get(i);
            ENode n = stage.node();
            if (n.op() == ENode.Op.PIPE_MAP) {
                String bSym = n.symbol();
                HirPipelineLifter.BindInfo bi = lp.binders.get(bSym);
                HirVar mapVar;
                if (bi != null && bi.var() != null && !bi.fromLoopVar()) {
                    mapVar = bi.var();
                } else {
                    String name = bi != null && bi.var() != null ? bi.var().name() : bSym;
                    PrimitiveKind kind =
                            bi != null && bi.var() != null ? bi.var().primitiveKind() : n.kind();
                    mapVar = freshDefVar(name, kind);
                }
                binderToVar.put(bSym, mapVar);
                mapDeclaredVars.add(mapVar);
                curVar = mapVar;
            } else if (n.op() == ENode.Op.PIPE_DISTINCT) {
                distinctElemVars.put(stage, curVar);
            }
        }

        for (Map.Entry<Integer, String> e : lp.varIdToBinder.entrySet()) {
            HirVar target = binderToVar.get(e.getValue());
            if (target != null && target.id() != e.getKey()) {
                varSubst.put(e.getKey(), target);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- expression lowering

    private HirExpr lowerRangeGuard(
            EGraph.ExtractedTree guardTree, HirExpr loRead, HirExpr hiRead, int line) {
        // Guard shape from R7: AND(LTE(lo, hi), CMP(CALL(f, hi), lim))
        if (guardTree.node().op() != ENode.Op.AND || guardTree.children().size() != 2) {
            return null;
        }
        EGraph.ExtractedTree cmpTree = guardTree.children().get(1);
        HirExpr.BinaryOp cmpOp = toHirBinaryOp(cmpTree.node().op());
        if (cmpOp == null || cmpTree.children().size() != 2) {
            return null;
        }
        EGraph.ExtractedTree callTree = cmpTree.children().get(0);
        if (callTree.node().op() != ENode.Op.CALL || callTree.children().size() != 1) {
            return null;
        }
        HirExpr fHi = rebuildUnaryCall(callTree.node().symbol(), hiRead, line);
        HirExpr limExpr = lowerExpr(cmpTree.children().get(1), line, null, null);
        if (fHi == null || limExpr == null) {
            return null;
        }
        HirExpr nonEmpty =
                binaryExpr(HirExpr.BinaryOp.LTE, loRead, hiRead, PrimitiveKind.BOOL, line);
        HirExpr boundCheck = binaryExpr(cmpOp, fHi, limExpr, PrimitiveKind.BOOL, line);
        return new HirExpr.Logic(
                HirExpr.LogicOp.AND,
                nonEmpty,
                boundCheck,
                false,
                ExprMeta.of(line, PrimitiveKind.BOOL));
    }

    private HirExpr lowerExpr(
            EGraph.ExtractedTree tree, int line, String overrideBinder, HirExpr overrideExpr) {
        ENode n = tree.node();
        return switch (n.op()) {
            case CONST ->
                    new HirExpr.Const(
                            n.literal(),
                            ExprMeta.of(
                                    line,
                                    n.kind(),
                                    n.kind().isPrimitive() || n.literal() instanceof String,
                                    n.literal() instanceof String));
            case OPAQUE -> {
                String sym = n.symbol();
                if (overrideBinder != null && overrideBinder.equals(sym)) {
                    yield overrideExpr;
                }
                HirVar bv = binderToVar.get(sym);
                if (bv != null) {
                    HirPipelineLifter.BindInfo bi = lp.binders.get(sym);
                    boolean isStr = bi != null && bi.defMeta() != null && bi.defMeta().stringType();
                    ExprMeta m =
                            isStr
                                    ? ExprMeta.of(line, bv.primitiveKind(), true, true)
                                    : ExprMeta.of(line, bv.primitiveKind());
                    yield new HirExpr.VarRead(bv, m);
                }
                HirExpr leaf = lp.leaves.get(sym);
                yield leaf != null ? substituteVars(leaf) : null;
            }
            case NEG -> {
                HirExpr op = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                yield op == null
                        ? null
                        : new HirExpr.Unary(
                                HirExpr.UnaryOp.NEGATE, op, null, ExprMeta.of(line, n.kind()));
            }
            case NOT -> {
                HirExpr op = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                yield op == null
                        ? null
                        : new HirExpr.Unary(
                                HirExpr.UnaryOp.NOT,
                                ensureKind(op, PrimitiveKind.BOOL, line),
                                null,
                                ExprMeta.of(line, PrimitiveKind.BOOL));
            }
            case AND, OR -> {
                HirExpr l = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                HirExpr r = lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr);
                if (l == null || r == null) {
                    yield null;
                }
                HirExpr.LogicOp lop =
                        n.op() == ENode.Op.AND ? HirExpr.LogicOp.AND : HirExpr.LogicOp.OR;
                yield new HirExpr.Logic(
                        lop,
                        ensureKind(l, PrimitiveKind.BOOL, line),
                        ensureKind(r, PrimitiveKind.BOOL, line),
                        false,
                        ExprMeta.of(line, PrimitiveKind.BOOL));
            }
            case ADD, SUB, MUL, DIV, FLOOR_DIV, MOD, EQ, NEQ, LT, LTE, GT, GTE -> {
                HirExpr l = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                HirExpr r = lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr);
                HirExpr.BinaryOp bop = toHirBinaryOp(n.op());
                if (l == null || r == null || bop == null) {
                    yield null;
                }
                yield binaryExpr(bop, l, r, n.kind(), line);
            }
            case CALL -> lowerCall(tree, line, overrideBinder, overrideExpr);
            default -> null;
        };
    }

    private HirExpr lowerCall(
            EGraph.ExtractedTree tree, int line, String overrideBinder, HirExpr overrideExpr) {
        ENode n = tree.node();
        String sym = n.symbol();
        if (AlgebraicRules.SYM_MIN.equals(sym) || AlgebraicRules.SYM_MAX.equals(sym)) {
            if (tree.children().size() != 2 || !n.isIntegral()) {
                return null;
            }
            HirExpr a = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
            HirExpr b = lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr);
            if (a == null || b == null) {
                return null;
            }
            a = adaptKind(a, n.kind(), line);
            b = adaptKind(b, n.kind(), line);
            HirExpr aRef = hoistIfComplex(a, "$a", n.kind(), line);
            HirExpr bRef = hoistIfComplex(b, "$b", n.kind(), line);
            boolean isMin = AlgebraicRules.SYM_MIN.equals(sym);
            HirExpr cond = binaryExpr(HirExpr.BinaryOp.LT, aRef, bRef, PrimitiveKind.BOOL, line);
            HirExpr minMaxIf =
                    new HirExpr.If(
                            cond,
                            isMin ? aRef : bRef,
                            isMin ? bRef : aRef,
                            ExprMeta.of(line, n.kind()));
            HirVar mVar = freshDefVar(isMin ? "$min" : "$max", n.kind());
            hoistedVars.add(mVar);
            hoistedDefs.add(makeDef(mVar, minMaxIf, line));
            return varRead(mVar, line);
        }
        HirExpr opaqueOrig = lp.opaqueCalls.get(sym);
        if (opaqueOrig != null) {
            if (overrideBinder != null) {
                HirPipelineLifter.BindInfo bi = lp.binders.get(overrideBinder);
                if (bi != null && bi.var() != null) {
                    return replaceVarWithExpr(
                            substituteVars(opaqueOrig), bi.var().id(), overrideExpr);
                }
            }
            return substituteVars(opaqueOrig);
        }
        if (tree.children().size() == 1) {
            HirExpr arg = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
            if (arg == null) {
                return null;
            }
            return rebuildUnaryCall(sym, arg, line);
        }
        return null;
    }

    private HirExpr hoistIfComplex(HirExpr expr, String prefix, PrimitiveKind kind, int line) {
        if (expr instanceof HirExpr.Const || expr instanceof HirExpr.VarRead) {
            return expr;
        }
        HirVar v = freshDefVar(prefix, kind);
        hoistedVars.add(v);
        hoistedDefs.add(makeDef(v, expr, line));
        return varRead(v, line);
    }

    private HirExpr rebuildUnaryCall(String sym, HirExpr arg, int line) {
        HirExpr tmpl = lp.callTemplates.get(sym);
        if (tmpl instanceof HirExpr.DirectBabbleCall c) {
            return new HirExpr.DirectBabbleCall(
                    c.callee(),
                    c.targetFunctionId(),
                    c.externalTarget(),
                    List.of(arg),
                    c.binding(),
                    c.meta().withLine(line));
        }
        if (tmpl instanceof HirExpr.JavaMethodCall c) {
            if (c.target().isStatic()) {
                return new HirExpr.JavaMethodCall(
                        c.receiver(),
                        c.receiverText(),
                        c.selectorLine(),
                        c.pureStaticReceiver(),
                        c.target(),
                        List.of(arg),
                        c.meta().withLine(line));
            }
            return new HirExpr.JavaMethodCall(
                    arg,
                    c.receiverText(),
                    c.selectorLine(),
                    false,
                    c.target(),
                    c.args(),
                    c.meta().withLine(line));
        }
        return null;
    }

    private HirExpr buildAssocCall(HirExpr callTemplate, HirExpr a, HirExpr b) {
        if (callTemplate instanceof HirExpr.DirectBabbleCall c) {
            return new HirExpr.DirectBabbleCall(
                    c.callee(),
                    c.targetFunctionId(),
                    c.externalTarget(),
                    List.of(a, b),
                    c.binding(),
                    c.meta());
        }
        if (callTemplate instanceof HirExpr.JavaMethodCall c) {
            return new HirExpr.JavaMethodCall(
                    c.receiver(),
                    c.receiverText(),
                    c.selectorLine(),
                    c.pureStaticReceiver(),
                    c.target(),
                    List.of(a, b),
                    c.meta());
        }
        throw new IllegalStateException("Unsupported associative call template : " + callTemplate);
    }

    private HirExpr buildFoldAssign(HirPipelineLifter.FoldInfo fi, HirExpr elemExpr) {
        HirExpr.VarAssign va = fi.assignTemplate();
        HirExpr accRead = varRead(va.var(), va.meta().line());
        HirExpr rhs = buildAssocCall(fi.callTemplate(), accRead, elemExpr);
        return new HirExpr.VarAssign(
                va.var(), null, rhs, va.targetText(), va.valueText(), va.meta());
    }

    private HirExpr buildDistinctAddCall(
            HirPipelineLifter.DistinctInfo di, HirVar elemVar, int line) {
        HirExpr.JavaMethodCall tmpl = di.addTemplate();
        HirExpr arg = varRead(elemVar, line);
        if (elemVar.primitiveKind().isPrimitive()) {
            arg =
                    new HirExpr.Convert(
                            arg, PrimitiveKind.NONE, ExprMeta.of(line, PrimitiveKind.NONE));
        }
        return new HirExpr.JavaMethodCall(
                varRead(di.seenVar(), line),
                tmpl.receiverText(),
                tmpl.selectorLine(),
                false,
                tmpl.target(),
                List.of(arg),
                tmpl.meta().withLine(line));
    }

    // ---------------------------------------------------------------- HIR node helpers

    private HirExpr wrapThenBranch(List<HirExpr> body, int line) {
        if (body.size() == 1) {
            return ensureKind(body.get(0), PrimitiveKind.NONE, line);
        }
        List<HirExpr> aligned = new ArrayList<>(body);
        int last = aligned.size() - 1;
        aligned.set(last, ensureKind(aligned.get(last), PrimitiveKind.NONE, line));
        return new HirExpr.Block(
                aligned,
                false,
                new String[0],
                List.of(),
                -1,
                ExprMeta.of(line, PrimitiveKind.NONE));
    }

    private static HirExpr ensureKind(HirExpr expr, PrimitiveKind targetKind, int line) {
        if (expr.meta().kind() == targetKind) {
            return expr;
        }
        return new HirExpr.Convert(expr, targetKind, ExprMeta.of(line, targetKind));
    }

    private static HirExpr adaptKind(HirExpr expr, PrimitiveKind targetKind, int line) {
        if (expr.meta().kind() == targetKind) {
            return expr;
        }
        return new HirExpr.Convert(expr, targetKind, ExprMeta.of(line, targetKind));
    }

    private HirVar freshDefVar(String baseName, PrimitiveKind kind) {
        int id = nextVarId[0]++;
        return new HirVar(
                id,
                baseName + "#" + (nextTempIdx++),
                Resolution.Variable.Kind.DEFINITION,
                ownerMethodId,
                kind,
                false,
                true,
                false,
                true,
                false,
                false);
    }

    private HirVar freshMutVar(String baseName, PrimitiveKind kind) {
        int id = nextVarId[0]++;
        return new HirVar(
                id,
                baseName + "#" + (nextTempIdx++),
                Resolution.Variable.Kind.DEFINITION,
                ownerMethodId,
                kind,
                false,
                true,
                false,
                true,
                true,
                false);
    }

    private static final LoweringTargets.DefTarget LEXICAL_DEF =
            new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1);

    private static HirExpr.Def makeDef(HirVar var, HirExpr rhs, int line) {
        return new HirExpr.Def(
                var.name(), LEXICAL_DEF, var, rhs, ExprMeta.of(line, var.primitiveKind()));
    }

    private static HirExpr.VarRead varRead(HirVar var, int line) {
        return new HirExpr.VarRead(var, ExprMeta.of(line, var.primitiveKind()));
    }

    private static HirExpr.Const integralConst(PrimitiveKind kind, long value, int line) {
        Object boxed =
                kind == PrimitiveKind.LONG
                        ? (Object) Long.valueOf(value)
                        : Integer.valueOf((int) value);
        return new HirExpr.Const(boxed, ExprMeta.of(line, kind));
    }

    private static HirExpr.Binary binaryExpr(
            HirExpr.BinaryOp op, HirExpr left, HirExpr right, PrimitiveKind resultKind, int line) {
        return new HirExpr.Binary(op, left, right, null, null, ExprMeta.of(line, resultKind));
    }

    private static HirExpr.BinaryOp toHirBinaryOp(ENode.Op op) {
        return switch (op) {
            case ADD -> HirExpr.BinaryOp.ADD;
            case SUB -> HirExpr.BinaryOp.SUB;
            case MUL -> HirExpr.BinaryOp.MUL;
            case DIV -> HirExpr.BinaryOp.DIV;
            case FLOOR_DIV -> HirExpr.BinaryOp.FLOOR_DIV;
            case MOD -> HirExpr.BinaryOp.MOD;
            case EQ -> HirExpr.BinaryOp.EQ;
            case NEQ -> HirExpr.BinaryOp.NEQ;
            case LT -> HirExpr.BinaryOp.LT;
            case LTE -> HirExpr.BinaryOp.LTE;
            case GT -> HirExpr.BinaryOp.GT;
            case GTE -> HirExpr.BinaryOp.GTE;
            default -> null;
        };
    }

    private List<HirExpr> substituteVarsList(List<HirExpr> list) {
        if (varSubst.isEmpty()) {
            return list;
        }
        List<HirExpr> out = new ArrayList<>(list.size());
        for (HirExpr s : list) {
            out.add(substituteVars(s));
        }
        return out;
    }

    private HirExpr substituteVars(HirExpr expr) {
        if (expr == null || varSubst.isEmpty()) {
            return expr;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            HirVar rep = varSubst.get(vr.var().id());
            return rep != null ? new HirExpr.VarRead(rep, vr.meta()) : vr;
        }
        return HirRewriter.rewriteChildren(expr, this::substituteVars);
    }

    private static HirExpr replaceVarWithExpr(HirExpr expr, int varId, HirExpr replacement) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.VarRead vr && vr.var().id() == varId) {
            return replacement;
        }
        return HirRewriter.rewriteChildren(expr, c -> replaceVarWithExpr(c, varId, replacement));
    }

    private static void collectNestedDeclaredVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.Block blk) {
            for (HirVar v : blk.declaredVars()) {
                out.add(v.id());
            }
        } else if (expr instanceof HirExpr.For f) {
            if (f.loopVar() != null) {
                out.add(f.loopVar().id());
            }
            for (HirVar v : f.bodyDeclaredVars()) {
                out.add(v.id());
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    collectNestedDeclaredVars(c, out);
                    return c;
                });
    }
}
