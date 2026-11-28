package org.babblelang.opt.hir;

import static org.babblelang.opt.hir.HirPipelineAnalysis.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.egraph.PipelineRules;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;

/**
 * Soundness-checked HIR-to-{@link EGraph} pipeline lifter for {@link PipelineRewritePass} ({@code
 * BIP042-I3}, {@code BIP042-D5}).
 *
 * <p>Lifts qualifying single {@link HirExpr.For} loops and multi-stage {@code def tmp = list();
 * for1; ...; consumerFor} chains into the 11-op {@code ENODE.Op.PIPE_*} dialect only after
 * verifying all {@code BIP042} §7.1 soundness preconditions (escape analysis, producer purity,
 * alias-disjointness, {@code Narrow} barriers, and reflexivity).
 */
final class HirPipelineLifter {
    private static final int MAX_INTERVENING_DEFS = 4;
    private static final int MAX_FOLD_ELEM_NODES = 20;
    private static final Map<HirExpr.BinaryOp, ENode.Op> INTEGRAL_BINARY_OPS =
            Map.of(
                    HirExpr.BinaryOp.ADD, ENode.Op.ADD,
                    HirExpr.BinaryOp.SUB, ENode.Op.SUB,
                    HirExpr.BinaryOp.MUL, ENode.Op.MUL,
                    HirExpr.BinaryOp.FLOOR_DIV, ENode.Op.FLOOR_DIV,
                    HirExpr.BinaryOp.MOD, ENode.Op.MOD);

    record SourceInfo(HirExpr.For originFor, HirVar loopVar, String binderSym) {}

    record BindInfo(String binderSym, HirVar var, ExprMeta defMeta, boolean fromLoopVar) {}

    record DistinctInfo(String setId, HirVar seenVar, HirExpr.JavaMethodCall addTemplate) {}

    record SinkInfo(
            String sinkId,
            List<HirExpr> statements,
            HirExpr.For consumerFor,
            List<HirVar> bodyDeclaredVars) {}

    record FoldInfo(
            String foldSpec,
            String opSymbol,
            HirExpr.VarAssign assignTemplate,
            HirExpr callTemplate,
            HirExpr origElemExpr) {}

    /**
     * Per-pipeline lifting state shared between {@link HirPipelineLifter} and {@link
     * HirPipelineLowerer}.
     */
    static final class LiftedPipeline {
        final EGraph graph;
        final int rootId;
        final int startIdx;
        final int endIdx;
        final List<HirExpr> preservedInterveningDefs;
        final List<HirVar> allLoopDeclaredVars;
        final Map<String, SourceInfo> sources = new HashMap<>();
        final Map<String, BindInfo> binders = new LinkedHashMap<>();
        final Map<String, DistinctInfo> distincts = new HashMap<>();
        final Map<String, SinkInfo> sinks = new HashMap<>();
        final Map<String, FoldInfo> folds = new HashMap<>();
        final Map<String, HirExpr> leaves = new HashMap<>();
        final Map<String, HirExpr> callTemplates = new HashMap<>();
        final Map<String, HirExpr> opaqueCalls = new HashMap<>();
        final Map<Integer, String> varIdToBinder = new HashMap<>();

        LiftedPipeline(
                EGraph graph,
                int rootId,
                int startIdx,
                int endIdx,
                List<HirExpr> preservedInterveningDefs,
                List<HirVar> allLoopDeclaredVars) {
            this.graph = graph;
            this.rootId = rootId;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
            this.preservedInterveningDefs = preservedInterveningDefs;
            this.allLoopDeclaredVars = allLoopDeclaredVars;
        }
    }

    private final HirModule module;
    private final HirEffectAnalysis fx;
    private final List<HirExpr> wholeBody;
    private final Map<Integer, HirVar> freshLocalLists = new HashMap<>();
    private final Map<Integer, HirVar> freshLocalSets = new HashMap<>();

    HirPipelineLifter(HirModule module, HirEffectAnalysis fx, List<HirExpr> wholeBody) {
        this.module = module;
        this.fx = fx;
        this.wholeBody = wholeBody;
        scanFreshLocalCollections(wholeBody);
    }

    private void scanFreshLocalCollections(List<HirExpr> stmts) {
        for (HirExpr stmt : stmts) {
            HirPipelineAnalysis.walkExpr(
                    stmt,
                    expr -> {
                        if (expr instanceof HirExpr.Def d
                                && d.var() != null
                                && !d.var().cell()
                                && !d.var().isCaptured()) {
                            HirExpr rhs = HirRewriter.unwrapTransparent(d.value());
                            if (rhs instanceof HirExpr.CoreCollectionCall cc
                                    && cc.args().isEmpty()
                                    && cc.meta().checkedType() == null) {
                                if ("list".equals(cc.collectionKind())) {
                                    freshLocalLists.put(d.var().id(), d.var());
                                } else if ("set".equals(cc.collectionKind())) {
                                    freshLocalSets.put(d.var().id(), d.var());
                                }
                            }
                        }
                    });
        }
    }

    /**
     * Attempts to lift a pipeline starting at {@code stmts.get(startIdx)}. Returns {@code null} if
     * no qualifying single-loop or multi-stage pipeline starts at {@code startIdx}.
     */
    LiftedPipeline tryLiftAt(List<HirExpr> stmts, int startIdx, boolean valueIgnoredForLast) {
        // Future std/ combinator lifting seam (BIP042-Q11):
        // Recognized std/ calls (e.g. std/iter.map(xs, f), std/iter.filter(xs, p),
        // std/iter.distinct(xs), std/iter.reduce(xs, seed, op)) can be lifted directly
        // into the exact same PIPE_* E-nodes here with zero changes to PipelineRules or
        // HirPipelineLowerer.

        HirExpr first = stmts.get(startIdx);
        // 1. Try multi-stage producer-consumer chain starting with `def tmp = list()`
        if (isFreshListDef(first)
                && startIdx + 2 < stmts.size()
                && stmts.get(startIdx + 1) instanceof HirExpr.For) {
            LiftedPipeline chain = tryLiftChain(stmts, startIdx, valueIgnoredForLast);
            if (chain != null) {
                return chain;
            }
        }
        // 2. Try single `HirExpr.For` loop
        if (first instanceof HirExpr.For forLoop) {
            boolean isStmtPos = startIdx < stmts.size() - 1 || valueIgnoredForLast;
            return tryLiftSingleFor(forLoop, startIdx, isStmtPos);
        }
        return null;
    }

    // ---------------------------------------------------------------- multi-stage chain lifting

    private LiftedPipeline tryLiftChain(
            List<HirExpr> stmts, int startIdx, boolean valueIgnoredForLast) {
        LiftBuilder b =
                new LiftBuilder(collectLoopVariantVars(stmts.subList(startIdx, stmts.size())));
        List<HirExpr> preservedDefs = new ArrayList<>();
        List<HirExpr.For> producerFors = new ArrayList<>();
        List<HirVar> tempVars = new ArrayList<>();
        int curIdx = startIdx;
        int streamId = -1;
        HirVar curElemVar = null;
        String curBinderSym = null;
        int tmpCounter = 0;

        while (true) {
            HirVar tmpVar = ((HirExpr.Def) stmts.get(curIdx)).var();
            HirExpr.For prodFor = (HirExpr.For) stmts.get(curIdx + 1);
            if (prodFor.elemSamTarget() != null || containsControlFlowExit(prodFor.body())) {
                return null;
            }
            if (streamId < 0) {
                SourceStage src = b.liftSource(prodFor);
                streamId = src.streamId;
                curElemVar = prodFor.loopVar();
                curBinderSym = src.binderSym;
            } else {
                b.aliasVarToBinder(prodFor.loopVar(), curBinderSym);
                curElemVar = prodFor.loopVar();
            }
            b.addDeclaredVars(prodFor.bodyDeclaredVars());

            if (producerFors.isEmpty() && !isProducerIterablePureNothrow(prodFor)) {
                return null;
            }
            ProducerPeel peel =
                    b.peelProducerBody(prodFor.body(), tmpVar, streamId, curElemVar, curBinderSym);
            if (peel == null || countVarRefsInBody(wholeBody, tmpVar.id()) != 2) {
                return null;
            }
            streamId = b.graph.add(ENode.pipeMaterialize("tmp#" + (tmpCounter++), peel.streamId));
            curBinderSym = peel.outBinderSym;
            producerFors.add(prodFor);
            tempVars.add(tmpVar);

            int scan = curIdx + 2;
            List<HirExpr> between = new ArrayList<>();
            int nextChainStart = -1;
            int consumerForIdx = -1;
            while (scan < stmts.size() && between.size() <= MAX_INTERVENING_DEFS) {
                HirExpr s = stmts.get(scan);
                if (s instanceof HirExpr.For f && iteratesVar(f, tmpVar)) {
                    consumerForIdx = scan;
                    break;
                }
                if (isFreshListDef(s)
                        && scan + 1 < stmts.size()
                        && stmts.get(scan + 1) instanceof HirExpr.For nextProd
                        && iteratesVar(nextProd, tmpVar)
                        && isChainedTempList(stmts, scan + 2, ((HirExpr.Def) s).var(), wholeBody)) {
                    nextChainStart = scan;
                    break;
                }
                if (!isHoistableInterveningDef(s, tmpVar, producerFors)) {
                    return null;
                }
                between.add(s);
                scan++;
            }
            preservedDefs.addAll(between);

            if (nextChainStart >= 0) {
                curIdx = nextChainStart;
                continue;
            }
            if (consumerForIdx < 0) {
                return null;
            }

            HirExpr.For consumerFor = (HirExpr.For) stmts.get(consumerForIdx);
            if (consumerFor.elemSamTarget() != null
                    || (consumerFor.target() != null && consumerFor.target().elemWidenRank() > 0)) {
                return null;
            }
            if (!areProducersDisjointFromConsumer(producerFors, tempVars, consumerFor)) {
                return null;
            }
            b.aliasVarToBinder(consumerFor.loopVar(), curBinderSym);
            b.addDeclaredVars(consumerFor.bodyDeclaredVars());

            boolean isStmtPos = consumerForIdx < stmts.size() - 1 || valueIgnoredForLast;
            int rootId =
                    b.peelConsumerBody(
                            consumerFor,
                            consumerFor.body(),
                            streamId,
                            consumerFor.loopVar(),
                            curBinderSym,
                            isStmtPos);
            if (rootId < 0) {
                return null;
            }
            return b.build(rootId, startIdx, consumerForIdx, preservedDefs);
        }
    }

    // ---------------------------------------------------------------- single-loop lifting

    private LiftedPipeline tryLiftSingleFor(HirExpr.For forLoop, int idx, boolean isStmtPos) {
        if (forLoop.elemSamTarget() != null || forLoop.loopVar() == null) {
            return null;
        }
        LiftBuilder b = new LiftBuilder(collectLoopVariantVars(List.of(forLoop)));
        SourceStage src = b.liftSource(forLoop);
        if (src == null) {
            return null;
        }
        b.addDeclaredVars(forLoop.bodyDeclaredVars());
        int rootId =
                b.peelConsumerBody(
                        forLoop,
                        forLoop.body(),
                        src.streamId,
                        forLoop.loopVar(),
                        src.binderSym,
                        isStmtPos);
        if (rootId < 0) {
            return null;
        }
        // Only worth saturating if at least one transform (filter/map/distinct/fold) was peeled
        if (!b.hasRewriteOpportunity) {
            return null;
        }
        return b.build(rootId, idx, idx, List.of());
    }

    // ---------------------------------------------------------------- builder

    private record SourceStage(int streamId, String binderSym) {}

    private record ProducerPeel(int streamId, String outBinderSym) {}

    private final class LiftBuilder {
        final EGraph graph = new EGraph();
        final Map<String, SourceInfo> sources = new HashMap<>();
        final Map<String, BindInfo> binders = new LinkedHashMap<>();
        final Map<String, DistinctInfo> distincts = new HashMap<>();
        final Map<String, SinkInfo> sinks = new HashMap<>();
        final Map<String, FoldInfo> folds = new HashMap<>();
        final Map<String, HirExpr> leaves = new HashMap<>();
        final Map<String, HirExpr> callTemplates = new HashMap<>();
        final Map<String, HirExpr> opaqueCalls = new HashMap<>();
        final Map<Integer, String> varIdToBinder = new HashMap<>();
        final List<HirVar> allLoopDeclaredVars = new ArrayList<>();
        final Set<Integer> seenDeclaredIds = new HashSet<>();
        int nextBinderIdx = 0;
        int nextOpaqueIdx = 0;
        int nextDistinctIdx = 0;
        boolean hasRewriteOpportunity = false;

        /**
         * Locals assigned in a lifted loop body (or captured as mutable cells) : a leaf reading one
         * is not loop-invariant, so it is lowered as depending on the source binder, which keeps
         * rules that evaluate binder-free leaves before the loop ({@code R6} range clamping, {@code
         * R7} early exit and whole-range pre-check) from hoisting it.
         */
        final Set<Integer> loopVariantVars;

        String sourceBinderSym;

        LiftBuilder(Set<Integer> loopVariantVars) {
            this.loopVariantVars = loopVariantVars;
        }

        private boolean readsLoopVariantState(HirExpr expr) {
            return anyExpr(
                    expr,
                    e ->
                            (e instanceof HirExpr.VarRead vr
                                            && !varIdToBinder.containsKey(vr.var().id())
                                            && (vr.var().cell()
                                                    || loopVariantVars.contains(vr.var().id())))
                                    || (e instanceof HirExpr.NameRead nr && !nr.finalBinding()));
        }

        void addDeclaredVars(List<HirVar> vars) {
            for (HirVar v : vars) {
                if (seenDeclaredIds.add(v.id())) {
                    allLoopDeclaredVars.add(v);
                }
            }
        }

        void aliasVarToBinder(HirVar var, String binderSym) {
            varIdToBinder.put(var.id(), binderSym);
            BindInfo existing = binders.get(binderSym);
            if (existing == null || existing.var() == null) {
                binders.put(
                        binderSym,
                        new BindInfo(
                                binderSym,
                                var,
                                existing != null ? existing.defMeta() : null,
                                true));
            }
        }

        SourceStage liftSource(HirExpr.For f) {
            HirVar loopVar = f.loopVar();
            String binderSym = "$e" + (nextBinderIdx++);
            sourceBinderSym = binderSym;
            varIdToBinder.put(loopVar.id(), binderSym);
            binders.put(binderSym, new BindInfo(binderSym, loopVar, null, true));
            int binderId = graph.add(ENode.opaque(binderSym, loopVar.primitiveKind()));
            if (isVarReflexiveEq(loopVar, f)) {
                graph.markReflexiveEq(binderId);
            }

            HirExpr.CountedRangeLoop cr = f.countedRange();
            if (cr != null
                    && (cr.elemKind() == PrimitiveKind.INT || cr.elemKind() == PrimitiveKind.LONG)
                    && isUnitStep(cr.step())
                    && HirPurity.isPureNothrowTerminating(cr.start(), module)
                    && HirPurity.isPureNothrowTerminating(cr.end(), module)) {
                int loId = lowerExpr(cr.start());
                int hiId = lowerExpr(cr.end());
                String spec = PipelineRules.rangeSpec(cr.descending(), cr.inclusive(), binderSym);
                sources.put(spec, new SourceInfo(f, loopVar, binderSym));
                int streamId = graph.add(ENode.pipeRange(spec, cr.elemKind(), loId, hiId));
                return new SourceStage(streamId, binderSym);
            }

            String srcId = "src#0";
            String spec = PipelineRules.srcSpec(srcId, binderSym);
            sources.put(srcId, new SourceInfo(f, loopVar, binderSym));
            int streamId = graph.add(ENode.pipeSrc(spec, loopVar.primitiveKind()));
            return new SourceStage(streamId, binderSym);
        }

        ProducerPeel peelProducerBody(
                List<HirExpr> body,
                HirVar tmpVar,
                int streamId,
                HirVar curElemVar,
                String curBinderSym) {
            Set<Integer> modified = collectModifiedVars(body);
            // Bail out if curElemVar or any binder var is mutated in producer body
            if (modified.contains(curElemVar.id())) {
                return null;
            }
            List<HirExpr> stmts = unwrapSingleBlockStmts(body);
            int idx = 0;
            while (idx < stmts.size()) {
                HirExpr s = stmts.get(idx);
                if (idx < stmts.size() - 1) {
                    if (s instanceof HirExpr.Def d
                            && isPureMapDef(d, modified)
                            && countVarRefs(d.value(), tmpVar.id()) == 0) {
                        String nextBinder = "$e" + (nextBinderIdx++);
                        int mapExprId = lowerExpr(d.value());
                        varIdToBinder.put(d.var().id(), nextBinder);
                        binders.put(nextBinder, new BindInfo(nextBinder, d.var(), d.meta(), false));
                        int binderLeaf =
                                graph.add(ENode.opaque(nextBinder, d.var().primitiveKind()));
                        if (d.value().meta().stringType()) {
                            graph.markReflexiveEq(binderLeaf);
                        }
                        streamId =
                                graph.add(
                                        ENode.pipeMap(
                                                nextBinder,
                                                d.var().primitiveKind(),
                                                streamId,
                                                mapExprId));
                        curElemVar = d.var();
                        curBinderSym = nextBinder;
                        hasRewriteOpportunity = true;
                        idx++;
                        continue;
                    }
                    return null;
                }
                // Sole remaining statement at idx == stmts.size() - 1
                HirExpr last = unwrapStmtConvert(s);
                if (last instanceof HirExpr.If ifExpr
                        && ifExpr.elseBranch() == null
                        && countVarRefs(ifExpr.cond(), tmpVar.id()) == 0) {
                    if (!HirPurity.isPureNothrowTerminating(ifExpr.cond(), module)) {
                        return null;
                    }
                    boolean narrowed = containsNarrowOrRightNarrowed(ifExpr.cond());
                    int predId = lowerFilterPred(ifExpr.cond(), narrowed);
                    streamId = graph.add(ENode.pipeFilter(narrowed, streamId, predId));
                    hasRewriteOpportunity = true;
                    stmts = unwrapThenStmts(ifExpr.thenBranch());
                    idx = 0;
                    continue;
                }
                // Must be `tmp.add(elemExpr)`
                HirExpr addedElem = extractListAddArg(last, tmpVar);
                if (addedElem == null
                        || countVarRefs(addedElem, tmpVar.id()) != 0
                        || !HirPurity.isPureNothrowTerminating(addedElem, module)) {
                    return null;
                }
                HirExpr unwrappedElem = unwrapBoxConvert(addedElem);
                if (unwrappedElem instanceof HirExpr.VarRead vr
                        && vr.var().id() == curElemVar.id()) {
                    hasRewriteOpportunity = true;
                    return new ProducerPeel(streamId, curBinderSym);
                }
                // Emit an explicit PIPE_MAP stage for `tmp.add(expr)`
                String nextBinder = "$e" + (nextBinderIdx++);
                int mapExprId = lowerExpr(unwrappedElem);
                PrimitiveKind outKind = unwrappedElem.meta().kind();
                binders.put(
                        nextBinder, new BindInfo(nextBinder, null, unwrappedElem.meta(), false));
                int binderLeaf = graph.add(ENode.opaque(nextBinder, outKind));
                if (unwrappedElem.meta().stringType()) {
                    graph.markReflexiveEq(binderLeaf);
                }
                streamId = graph.add(ENode.pipeMap(nextBinder, outKind, streamId, mapExprId));
                hasRewriteOpportunity = true;
                return new ProducerPeel(streamId, nextBinder);
            }
            return null;
        }

        int peelConsumerBody(
                HirExpr.For consumerFor,
                List<HirExpr> body,
                int streamId,
                HirVar curElemVar,
                String curBinderSym,
                boolean isStmtPos) {
            Set<Integer> modified = collectModifiedVars(body);
            List<HirExpr> stmts = unwrapSingleBlockStmts(body);
            int idx = 0;
            int peeledStages = 0;
            while (idx < stmts.size()) {
                HirExpr s = stmts.get(idx);
                if (idx < stmts.size() - 1) {
                    if (s instanceof HirExpr.Def d && isPureMapDef(d, modified)) {
                        String nextBinder = "$e" + (nextBinderIdx++);
                        int mapExprId = lowerExpr(d.value());
                        varIdToBinder.put(d.var().id(), nextBinder);
                        binders.put(nextBinder, new BindInfo(nextBinder, d.var(), d.meta(), false));
                        int binderLeaf =
                                graph.add(ENode.opaque(nextBinder, d.var().primitiveKind()));
                        if (d.value().meta().stringType()) {
                            graph.markReflexiveEq(binderLeaf);
                        }
                        streamId =
                                graph.add(
                                        ENode.pipeMap(
                                                nextBinder,
                                                d.var().primitiveKind(),
                                                streamId,
                                                mapExprId));
                        curElemVar = d.var();
                        curBinderSym = nextBinder;
                        peeledStages++;
                        idx++;
                        continue;
                    }
                    break;
                }
                // Last statement: check if it's an unguarded `if` (distinct or filter)
                HirExpr last = unwrapStmtConvert(s);
                if (last instanceof HirExpr.If ifExpr && ifExpr.elseBranch() == null) {
                    DistinctMatch dm = matchDistinctGuard(ifExpr.cond(), curElemVar);
                    if (dm != null) {
                        String setId = "seen#" + (nextDistinctIdx++);
                        distincts.put(setId, new DistinctInfo(setId, dm.seenVar, dm.addCall));
                        streamId = graph.add(ENode.pipeDistinct(setId, streamId));
                        hasRewriteOpportunity = true;
                        peeledStages++;
                        stmts = unwrapThenStmts(ifExpr.thenBranch());
                        idx = 0;
                        continue;
                    }
                    boolean pureNothrow = HirPurity.isPureNothrowTerminating(ifExpr.cond(), module);
                    boolean narrowed = !pureNothrow || containsNarrowOrRightNarrowed(ifExpr.cond());
                    int predId = lowerFilterPred(ifExpr.cond(), narrowed);
                    streamId = graph.add(ENode.pipeFilter(narrowed, streamId, predId));
                    hasRewriteOpportunity = true;
                    peeledStages++;
                    stmts = unwrapThenStmts(ifExpr.thenBranch());
                    idx = 0;
                    continue;
                }
                break;
            }

            List<HirExpr> remaining = stmts.subList(idx, stmts.size());
            // Check R8: associative fold `acc = op(acc, elemExpr)` in statement position
            if (isStmtPos && remaining.size() == 1) {
                int foldRoot = tryLiftAssociativeFold(remaining.get(0), streamId);
                if (foldRoot >= 0) {
                    hasRewriteOpportunity = true;
                    return foldRoot;
                }
            }

            if (peeledStages == 0 && !hasRewriteOpportunity) {
                return -1;
            }
            String sinkId = "sink#0";
            sinks.put(
                    sinkId,
                    new SinkInfo(
                            sinkId,
                            List.copyOf(remaining),
                            consumerFor,
                            List.copyOf(allLoopDeclaredVars)));
            return graph.add(ENode.pipeForEach(sinkId, streamId));
        }

        private int tryLiftAssociativeFold(HirExpr rawStmt, int streamId) {
            HirExpr stmt = unwrapStmtConvert(rawStmt);
            if (!(stmt instanceof HirExpr.VarAssign va)
                    || va.operator() != null
                    || va.var().cell()
                    || va.var().isCaptured()) {
                return -1;
            }
            HirExpr rhs = HirRewriter.unwrapTransparent(va.value());
            if (rhs.meta().wideningRank() >= 0
                    || rhs.meta().checkedType() != null
                    || rhs.meta().kind() != va.var().primitiveKind()
                    || !HirPurity.isAssociativeCall(rhs, module)
                    || !HirPurity.isPureNothrowTerminating(rhs, module)) {
                return -1;
            }
            String opSym;
            List<HirExpr> args;
            if (rhs instanceof HirExpr.DirectBabbleCall c
                    && c.args().size() == 2
                    && (c.binding() == null || c.binding().isIdentityPositional())) {
                opSym =
                        c.externalTarget() != null
                                ? "babble-ext#" + c.externalTarget().canonicalKey()
                                : "babble#" + c.targetFunctionId();
                args = c.args();
            } else if (rhs instanceof HirExpr.JavaMethodCall c
                    && c.target().isStatic()
                    && c.args().size() == 2) {
                Method m = c.target().method();
                opSym = "jstatic#" + m.getDeclaringClass().getName() + "." + m.getName() + ":2";
                args = c.args();
            } else {
                return -1;
            }
            HirExpr a0 = HirRewriter.unwrapTransparent(args.get(0));
            HirExpr arg1 = args.get(1);
            if (!(a0 instanceof HirExpr.VarRead vr)
                    || vr.var().id() != va.var().id()
                    || countVarRefs(arg1, va.var().id()) != 0
                    || hirNodeCount(arg1) > MAX_FOLD_ELEM_NODES
                    || containsDeclOrControlFlow(arg1)) {
                return -1;
            }
            graph.registerCallProps(
                    opSym,
                    new EGraph.CallProps(
                            HirPurity.isIdempotentCall(rhs, module),
                            true,
                            HirPurity.isCommutativeCall(rhs, module),
                            false,
                            false,
                            false));
            callTemplates.putIfAbsent(opSym, rhs);
            int elemId = lowerExpr(arg1);
            String foldSpec = PipelineRules.foldSpec(opSym, "acc#" + va.var().id());
            folds.put(foldSpec, new FoldInfo(foldSpec, opSym, va, rhs, arg1));
            return graph.add(ENode.pipeFold(foldSpec, va.var().primitiveKind(), streamId, elemId));
        }

        private record DistinctMatch(HirVar seenVar, HirExpr.JavaMethodCall addCall) {}

        private DistinctMatch matchDistinctGuard(HirExpr cond, HirVar curElemVar) {
            HirExpr u = HirRewriter.unwrapTransparent(cond);
            if (!HirPipelineAnalysis.isCollectionAddOn(u, freshLocalSets::containsKey)) {
                return null;
            }
            HirExpr.JavaMethodCall c = (HirExpr.JavaMethodCall) u;
            HirVar seenVar =
                    freshLocalSets.get(
                            ((HirExpr.VarRead) HirRewriter.unwrapTransparent(c.receiver()))
                                    .var()
                                    .id());
            if (c.meta().checkedType() != null
                    || countVarRefsInBody(wholeBody, seenVar.id()) != 1) {
                return null;
            }
            HirExpr arg = unwrapBoxConvert(c.args().get(0));
            if (!(arg instanceof HirExpr.VarRead avr) || avr.var().id() != curElemVar.id()) {
                return null;
            }
            return new DistinctMatch(seenVar, c);
        }

        private int lowerFilterPred(HirExpr rawPred, boolean narrowed) {
            if (narrowed) {
                return lowerOpaqueWithBinderDeps(rawPred);
            }
            return lowerExpr(rawPred);
        }

        private int markStringReflexive(int id, HirExpr expr) {
            if (expr.meta().stringType()) {
                graph.markReflexiveEq(id);
            }
            return id;
        }

        private int lowerExpr(HirExpr raw) {
            HirExpr expr = HirRewriter.unwrapTransparent(raw);
            if (!HirPurity.isPureNothrowTerminating(expr, module)
                    || expr.meta().wideningRank() >= 0
                    || expr.meta().checkedType() != null) {
                return lowerOpaqueWithBinderDeps(raw);
            }
            if (expr instanceof HirExpr.Const c) {
                return graph.add(ENode.constVal(c.value(), c.meta().kind()));
            }
            if (expr instanceof HirExpr.VarRead v && !readsLoopVariantState(v)) {
                String binder = varIdToBinder.get(v.var().id());
                String key = binder != null ? binder : ("v#" + v.var().id());
                if (binder == null) {
                    leaves.putIfAbsent(key, v);
                }
                return markStringReflexive(graph.add(ENode.opaque(key, v.meta().kind())), v);
            }
            if (expr instanceof HirExpr.Logic l && !l.rightNarrowed()) {
                ENode.Op op = l.op() == HirExpr.LogicOp.AND ? ENode.Op.AND : ENode.Op.OR;
                return graph.add(
                        ENode.binary(
                                op, PrimitiveKind.BOOL, lowerExpr(l.left()), lowerExpr(l.right())));
            }
            if (expr instanceof HirExpr.Unary u && u.operand().meta().wideningRank() < 0) {
                if (u.op() == HirExpr.UnaryOp.NOT) {
                    return graph.add(
                            ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, lowerExpr(u.operand())));
                }
                if (u.op() == HirExpr.UnaryOp.NEGATE && HirPurity.isIntegralKind(u.meta().kind())) {
                    return graph.add(
                            ENode.unary(ENode.Op.NEG, u.meta().kind(), lowerExpr(u.operand())));
                }
            }
            if (expr instanceof HirExpr.Binary b) {
                PrimitiveKind rk = b.meta().kind();
                if (HirPurity.isIntegralKind(rk)
                        && b.left().meta().wideningRank() < 0
                        && b.right().meta().wideningRank() < 0) {
                    ENode.Op op = INTEGRAL_BINARY_OPS.get(b.op());
                    if (op != null) {
                        return graph.add(
                                ENode.binary(op, rk, lowerExpr(b.left()), lowerExpr(b.right())));
                    }
                }
                if (HirEGraphCodec.isLowerableComparison(b)) {
                    return graph.add(
                            ENode.binary(
                                    HirEGraphCodec.toComparisonOp(b.op()),
                                    PrimitiveKind.BOOL,
                                    lowerExpr(b.left()),
                                    lowerExpr(b.right())));
                }
            }
            if (expr instanceof HirExpr.DirectBabbleCall c
                    && HirEGraphCodec.isAlgebraicUnaryBabbleEligible(c, module)) {
                String sym =
                        c.externalTarget() != null
                                ? "babble-ext#" + c.externalTarget().canonicalKey()
                                : "babble#" + c.targetFunctionId();
                return lowerUnaryAlgebraicCall(sym, c, c.args().get(0));
            }
            if (expr instanceof HirExpr.JavaMethodCall c
                    && HirEGraphCodec.isAlgebraicUnaryJavaEligible(c, module)) {
                Method m = c.target().method();
                String sym =
                        (c.target().isStatic() ? "jstatic#" : "jinst#")
                                + m.getDeclaringClass().getName()
                                + "."
                                + m.getName()
                                + ":"
                                + m.getParameterCount();
                HirExpr arg = c.target().isStatic() ? c.args().get(0) : c.receiver();
                return lowerUnaryAlgebraicCall(sym, c, arg);
            }
            return lowerOpaqueWithBinderDeps(raw);
        }

        private int lowerUnaryAlgebraicCall(String sym, HirExpr call, HirExpr arg) {
            callTemplates.putIfAbsent(sym, call);
            graph.registerCallProps(
                    sym,
                    new EGraph.CallProps(
                            HirPurity.isIdempotentCall(call, module),
                            false,
                            false,
                            false,
                            HirPurity.isMonotoneCall(call, module),
                            HirPurity.isInjectiveCall(call, module)));
            return markStringReflexive(
                    graph.add(ENode.call(sym, call.meta().kind(), lowerExpr(arg))), call);
        }

        private int lowerOpaqueWithBinderDeps(HirExpr raw) {
            Set<String> deps = new LinkedHashSet<>();
            collectReferencedBinders(raw, varIdToBinder, deps);
            if (sourceBinderSym != null && readsLoopVariantState(raw)) {
                deps.add(sourceBinderSym);
            }
            if (deps.isEmpty()) {
                String key = "p#" + (nextOpaqueIdx++);
                leaves.put(key, raw);
                return markStringReflexive(graph.add(ENode.opaque(key, raw.meta().kind())), raw);
            }
            String sym = "$opaque#" + (nextOpaqueIdx++);
            opaqueCalls.put(sym, raw);
            int[] depIds = new int[deps.size()];
            int di = 0;
            for (String bSym : deps) {
                BindInfo bi = binders.get(bSym);
                PrimitiveKind bk =
                        bi != null && bi.var() != null
                                ? bi.var().primitiveKind()
                                : (bi != null && bi.defMeta() != null
                                        ? bi.defMeta().kind()
                                        : PrimitiveKind.NONE);
                depIds[di++] = graph.add(ENode.opaque(bSym, bk));
            }
            int id = graph.add(ENode.call(sym, raw.meta().kind(), depIds));
            if (raw.meta().stringType()) {
                graph.markReflexiveEq(id);
            }
            return id;
        }

        LiftedPipeline build(
                int rootId, int startIdx, int endIdx, List<HirExpr> preservedInterveningDefs) {
            LiftedPipeline lp =
                    new LiftedPipeline(
                            graph,
                            rootId,
                            startIdx,
                            endIdx,
                            List.copyOf(preservedInterveningDefs),
                            List.copyOf(allLoopDeclaredVars));
            lp.sources.putAll(sources);
            lp.binders.putAll(binders);
            lp.distincts.putAll(distincts);
            lp.sinks.putAll(sinks);
            lp.folds.putAll(folds);
            lp.leaves.putAll(leaves);
            lp.callTemplates.putAll(callTemplates);
            lp.opaqueCalls.putAll(opaqueCalls);
            lp.varIdToBinder.putAll(varIdToBinder);
            return lp;
        }

        private List<HirExpr> unwrapThenStmts(HirExpr thenBranch) {
            return unwrapSingleBlockStmts(List.of(thenBranch));
        }

        private List<HirExpr> unwrapSingleBlockStmts(List<HirExpr> stmts) {
            while (stmts.size() == 1) {
                HirExpr u = unwrapStmtConvert(stmts.get(0));
                if (u instanceof HirExpr.Block blk && !blk.opensNamespaceScope()) {
                    addDeclaredVars(blk.declaredVars());
                    stmts = blk.statements();
                } else {
                    return List.of(u);
                }
            }
            return stmts;
        }
    }

    // ---------------------------------------------------------------- soundness helpers

    private boolean isFreshListDef(HirExpr stmt) {
        return stmt instanceof HirExpr.Def d
                && d.var() != null
                && freshLocalLists.containsKey(d.var().id());
    }

    private boolean isChainedTempList(
            List<HirExpr> stmts, int fromIdx, HirVar nextTmp, List<HirExpr> wholeBody) {
        if (countVarRefsInBody(wholeBody, nextTmp.id()) != 2) {
            return false;
        }
        int limit = Math.min(stmts.size(), fromIdx + MAX_INTERVENING_DEFS + 2);
        for (int i = fromIdx; i < limit; i++) {
            if (stmts.get(i) instanceof HirExpr.For f && iteratesVar(f, nextTmp)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPureMapDef(HirExpr.Def d, Set<Integer> modifiedVars) {
        return d.var() != null
                && !d.var().cell()
                && !d.var().isCaptured()
                && !modifiedVars.contains(d.var().id())
                && d.value().meta().wideningRank() < 0
                && d.value().meta().checkedType() == null
                && HirPurity.isPureNothrowTerminating(d.value(), module);
    }

    private boolean isProducerIterablePureNothrow(HirExpr.For prodFor) {
        if (prodFor.countedRange() != null) {
            HirExpr.CountedRangeLoop cr = prodFor.countedRange();
            return HirPurity.isPureNothrowTerminating(cr.start(), module)
                    && HirPurity.isPureNothrowTerminating(cr.end(), module)
                    && (cr.step() == null || HirPurity.isPureNothrowTerminating(cr.step(), module));
        }
        return prodFor.iterable() != null
                && HirPurity.isPureNothrowTerminating(prodFor.iterable(), module);
    }

    private boolean isHoistableInterveningDef(
            HirExpr stmt, HirVar tmpVar, List<HirExpr.For> producerFors) {
        if (!(stmt instanceof HirExpr.Def d)
                || d.var() == null
                || countVarRefs(d.value(), tmpVar.id()) != 0) {
            return false;
        }
        for (HirExpr.For pf : producerFors) {
            if (countVarRefs(pf, d.var().id()) != 0) {
                return false;
            }
        }
        if (freshLocalLists.containsKey(d.var().id()) || freshLocalSets.containsKey(d.var().id())) {
            return true;
        }
        return d.value().meta().checkedType() == null
                && HirPurity.isPureNothrowTerminating(d.value(), module);
    }

    private boolean areProducersDisjointFromConsumer(
            List<HirExpr.For> producers, List<HirVar> tempVars, HirExpr.For consumer) {
        return HirPipelineAnalysis.areProducersDisjointFromConsumer(
                module, fx, freshLocalLists, freshLocalSets, producers, tempVars, consumer);
    }
}
