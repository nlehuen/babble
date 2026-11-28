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
 * Wasm-portable structured HIR ({@code BIP042-I3}, {@code BIP042-D5}).
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

    private static final class BailOut extends RuntimeException {
        private static final BailOut INSTANCE = new BailOut();

        private BailOut() {
            super(null, null, false, false);
        }
    }

    private static <T> T req(T val) {
        if (val == null) {
            throw BailOut.INSTANCE;
        }
        return val;
    }

    /**
     * Lowers {@code best} into replacement statements for {@code stmts[lp.startIdx..lp.endIdx]},
     * returning {@code null} if {@code best} cannot be soundly lowered.
     */
    List<HirExpr> lower(EGraph.ExtractedTree best) {
        try {
            return switch (best.node().op()) {
                case PIPE_FOLD_PAIRWISE -> lowerPairwiseFold(best);
                case PIPE_FOLD -> lowerSequentialFold(best);
                case PIPE_FOR_EACH -> lowerForEach(best);
                default -> null;
            };
        } catch (BailOut b) {
            return null;
        }
    }

    // ---------------------------------------------------------------- PIPE_FOR_EACH

    private List<HirExpr> lowerForEach(EGraph.ExtractedTree root) {
        HirPipelineLifter.SinkInfo sink = req(lp.sinks.get(root.node().symbol()));
        return lowerStreamLoop(
                root.children().get(0),
                sink.statements(),
                sink.consumerFor(),
                sink.bodyDeclaredVars());
    }

    // ---------------------------------------------------------------- PIPE_FOLD (sequential)

    private List<HirExpr> lowerSequentialFold(EGraph.ExtractedTree root) {
        HirPipelineLifter.FoldInfo fi = req(lp.folds.get(root.node().symbol()));
        List<EGraph.ExtractedTree> stages = collectStages(root.children().get(0));
        HirPipelineLifter.SourceInfo src = resolveSource(stages.get(0));
        Map<EGraph.ExtractedTree, HirVar> distinctElemVars = new IdentityHashMap<>();
        List<HirVar> mapDeclaredVars = new ArrayList<>();
        assignStageBinders(stages, src, distinctElemVars, mapDeclaredVars);
        int line = fi.assignTemplate().meta().line();
        HirExpr elemExpr = lowerExpr(root.children().get(1), line, null, null);
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
        HirPipelineLifter.FoldInfo fi = req(lp.folds.get(root.node().symbol()));
        EGraph.ExtractedTree rangeTree = root.children().get(0);
        EGraph.ExtractedTree elemTree = root.children().get(1);
        ENode rangeNode = rangeTree.node();
        String rangeBinder = PipelineRules.rangeBinder(rangeNode.symbol());
        boolean incl = PipelineRules.isInclusiveRange(rangeNode.symbol());
        PrimitiveKind rk = rangeNode.kind();
        int line = fi.assignTemplate().meta().line();

        HirExpr loExpr =
                adaptKind(lowerExpr(rangeTree.children().get(0), line, null, null), rk, line);
        HirExpr hiExpr =
                adaptKind(lowerExpr(rangeTree.children().get(1), line, null, null), rk, line);

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
        HirPipelineLifter.SourceInfo src = resolveSource(stages.get(0));
        Map<EGraph.ExtractedTree, HirVar> distinctElemVars = new IdentityHashMap<>();
        List<HirVar> mapDeclaredVars = new ArrayList<>();
        assignStageBinders(stages, src, distinctElemVars, mapDeclaredVars);
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
            HirExpr loExpr =
                    adaptKind(lowerExpr(rootStage.children().get(0), line, null, null), rk, line);
            HirExpr hiExpr =
                    adaptKind(lowerExpr(rootStage.children().get(1), line, null, null), rk, line);

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
                    HirVar mapVar = req(binderToVar.get(sn.symbol()));
                    HirExpr rhs =
                            adaptKind(
                                    lowerExpr(stage.children().get(1), line, null, null),
                                    mapVar.primitiveKind(),
                                    line);
                    HirExpr def = makeDef(mapVar, rhs, line);
                    List<HirExpr> nextBody = new ArrayList<>(body.size() + 1);
                    nextBody.add(def);
                    nextBody.addAll(body);
                    body = nextBody;
                }
                case PIPE_FILTER, PIPE_GUARD_RANGE -> {
                    HirExpr pred =
                            ensureKind(
                                    lowerExpr(stage.children().get(1), line, null, null),
                                    PrimitiveKind.BOOL,
                                    line);
                    body =
                            List.of(
                                    new HirExpr.If(
                                            pred,
                                            wrapThenBranch(body, line),
                                            null,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                case PIPE_TAKE_WHILE -> {
                    HirExpr pred =
                            ensureKind(
                                    lowerExpr(stage.children().get(1), line, null, null),
                                    PrimitiveKind.BOOL,
                                    line);
                    HirExpr elseBreak =
                            new HirExpr.Break(null, ExprMeta.of(line, PrimitiveKind.NONE));
                    body =
                            List.of(
                                    new HirExpr.If(
                                            pred,
                                            wrapThenBranch(body, line),
                                            elseBreak,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                case PIPE_DISTINCT -> {
                    HirPipelineLifter.DistinctInfo di = req(lp.distincts.get(sn.symbol()));
                    HirVar elemVar = req(distinctElemVars.get(stage));
                    HirExpr seenAdd = buildDistinctAddCall(di, elemVar, line);
                    body =
                            List.of(
                                    new HirExpr.If(
                                            seenAdd,
                                            wrapThenBranch(body, line),
                                            null,
                                            ExprMeta.of(line, PrimitiveKind.NONE)));
                }
                default -> throw BailOut.INSTANCE;
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
        while (true) {
            ENode.Op op = cur.node().op();
            if (op == ENode.Op.PIPE_MATERIALIZE) {
                throw BailOut.INSTANCE;
            }
            rev.add(cur);
            if (op == ENode.Op.PIPE_SRC || op == ENode.Op.PIPE_RANGE) {
                break;
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
        String key = n.op() == ENode.Op.PIPE_RANGE ? n.symbol() : PipelineRules.srcId(n.symbol());
        return req(lp.sources.get(key));
    }

    private void assignStageBinders(
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
                HirVar mapVar =
                        (bi != null && bi.var() != null && !bi.fromLoopVar())
                                ? bi.var()
                                : freshDefVar(
                                        bi != null && bi.var() != null ? bi.var().name() : bSym,
                                        n.kind());
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
    }

    // ---------------------------------------------------------------- expression lowering

    private HirExpr lowerRangeGuard(
            EGraph.ExtractedTree guardTree, HirExpr loRead, HirExpr hiRead, int line) {
        // Guard shape from R7: AND(LTE(lo, hi), CMP(CALL(f, hi), lim))
        EGraph.ExtractedTree cmpTree = guardTree.children().get(1);
        HirExpr.BinaryOp cmpOp = req(BINARY_OPS_TO_HIR.get(cmpTree.node().op()));
        EGraph.ExtractedTree callTree = cmpTree.children().get(0);
        HirExpr fHi = rebuildUnaryCall(callTree.node().symbol(), hiRead, line);
        HirExpr limExpr = lowerExpr(cmpTree.children().get(1), line, null, null);
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
                yield substituteVars(req(lp.leaves.get(sym)));
            }
            case NEG ->
                    new HirExpr.Unary(
                            HirExpr.UnaryOp.NEGATE,
                            lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr),
                            null,
                            ExprMeta.of(line, n.kind()));
            case NOT ->
                    new HirExpr.Unary(
                            HirExpr.UnaryOp.NOT,
                            ensureKind(
                                    lowerExpr(
                                            tree.children().get(0),
                                            line,
                                            overrideBinder,
                                            overrideExpr),
                                    PrimitiveKind.BOOL,
                                    line),
                            null,
                            ExprMeta.of(line, PrimitiveKind.BOOL));
            case AND, OR -> {
                HirExpr l = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                HirExpr r = lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr);
                HirExpr.LogicOp lop =
                        n.op() == ENode.Op.AND ? HirExpr.LogicOp.AND : HirExpr.LogicOp.OR;
                yield new HirExpr.Logic(
                        lop,
                        ensureKind(l, PrimitiveKind.BOOL, line),
                        ensureKind(r, PrimitiveKind.BOOL, line),
                        false,
                        ExprMeta.of(line, PrimitiveKind.BOOL));
            }
            case ADD, SUB, MUL, FLOOR_DIV, MOD, EQ, NEQ, LT, LTE, GT, GTE -> {
                HirExpr l = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
                HirExpr r = lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr);
                yield binaryExpr(req(BINARY_OPS_TO_HIR.get(n.op())), l, r, n.kind(), line);
            }
            case CALL -> lowerCall(tree, line, overrideBinder, overrideExpr);
            default -> throw BailOut.INSTANCE;
        };
    }

    private HirExpr lowerCall(
            EGraph.ExtractedTree tree, int line, String overrideBinder, HirExpr overrideExpr) {
        ENode n = tree.node();
        String sym = n.symbol();
        if (AlgebraicRules.SYM_MIN.equals(sym) || AlgebraicRules.SYM_MAX.equals(sym)) {
            HirExpr a =
                    adaptKind(
                            lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr),
                            n.kind(),
                            line);
            HirExpr b =
                    adaptKind(
                            lowerExpr(tree.children().get(1), line, overrideBinder, overrideExpr),
                            n.kind(),
                            line);
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
        HirExpr arg = lowerExpr(tree.children().get(0), line, overrideBinder, overrideExpr);
        return rebuildUnaryCall(sym, arg, line);
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
        HirExpr tmpl = req(lp.callTemplates.get(sym));
        if (tmpl instanceof HirExpr.DirectBabbleCall c) {
            return new HirExpr.DirectBabbleCall(
                    c.callee(),
                    c.targetFunctionId(),
                    c.externalTarget(),
                    List.of(arg),
                    c.binding(),
                    c.meta().withLine(line));
        }
        HirExpr.JavaMethodCall c = (HirExpr.JavaMethodCall) tmpl;
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
        HirExpr.JavaMethodCall c = (HirExpr.JavaMethodCall) callTemplate;
        return new HirExpr.JavaMethodCall(
                c.receiver(),
                c.receiverText(),
                c.selectorLine(),
                c.pureStaticReceiver(),
                c.target(),
                List.of(a, b),
                c.meta());
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

    private static final Map<ENode.Op, HirExpr.BinaryOp> BINARY_OPS_TO_HIR =
            Map.ofEntries(
                    Map.entry(ENode.Op.ADD, HirExpr.BinaryOp.ADD),
                    Map.entry(ENode.Op.SUB, HirExpr.BinaryOp.SUB),
                    Map.entry(ENode.Op.MUL, HirExpr.BinaryOp.MUL),
                    Map.entry(ENode.Op.FLOOR_DIV, HirExpr.BinaryOp.FLOOR_DIV),
                    Map.entry(ENode.Op.MOD, HirExpr.BinaryOp.MOD),
                    Map.entry(ENode.Op.EQ, HirExpr.BinaryOp.EQ),
                    Map.entry(ENode.Op.NEQ, HirExpr.BinaryOp.NEQ),
                    Map.entry(ENode.Op.LT, HirExpr.BinaryOp.LT),
                    Map.entry(ENode.Op.LTE, HirExpr.BinaryOp.LTE),
                    Map.entry(ENode.Op.GT, HirExpr.BinaryOp.GT),
                    Map.entry(ENode.Op.GTE, HirExpr.BinaryOp.GTE));

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
        if (varSubst.isEmpty()) {
            return expr;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            HirVar rep = varSubst.get(vr.var().id());
            return rep != null ? new HirExpr.VarRead(rep, vr.meta()) : vr;
        }
        return HirRewriter.rewriteChildren(expr, this::substituteVars);
    }

    private static HirExpr replaceVarWithExpr(HirExpr expr, int varId, HirExpr replacement) {
        if (expr instanceof HirExpr.VarRead vr && vr.var().id() == varId) {
            return replacement;
        }
        return HirRewriter.rewriteChildren(expr, c -> replaceVarWithExpr(c, varId, replacement));
    }

    private static void collectNestedDeclaredVars(HirExpr expr, Set<Integer> out) {
        HirPipelineAnalysis.walkExpr(
                expr,
                e -> {
                    if (e instanceof HirExpr.Block blk) {
                        for (HirVar v : blk.declaredVars()) {
                            out.add(v.id());
                        }
                    } else if (e instanceof HirExpr.For f) {
                        out.add(f.loopVar().id());
                        for (HirVar v : f.bodyDeclaredVars()) {
                            out.add(v.id());
                        }
                    }
                });
    }
}
