package org.babblelang.ir.egraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Declarative pipeline rewrite rules ({@code R1}–{@code R8}) and monotonic selectivity-weighted
 * {@link EGraph.CostModel} for {@code BIP042} ({@code BIP014-I5} Half B).
 *
 * <p>Zero ANTLR or HIR dependencies: operates purely on {@link ENode} / {@link EGraph} so pipeline
 * transformations compose directly with {@link AlgebraicRules} during equality saturation and can
 * be unit-tested independently of HIR lifting and lowering.
 */
public final class PipelineRules {
    private PipelineRules() {}

    public static final String NARROW_TAG = "narrow";

    private static final List<EGraph.RewriteRule> PIPELINE_ONLY_RULES =
            List.of(
                    PipelineRules::minMaxConstFoldRule,
                    PipelineRules::streamFusionRule,
                    PipelineRules::filterFusionAndReorderRules,
                    PipelineRules::mapFilterPushDownRule,
                    PipelineRules::distinctPushDownRule,
                    PipelineRules::rangeBoundClampRule,
                    PipelineRules::monotoneRangeRules,
                    PipelineRules::associativePairwiseFoldRule);

    private static final List<EGraph.RewriteRule> ALL_RULES;

    static {
        List<EGraph.RewriteRule> combined = new ArrayList<>(AlgebraicRules.optimizerRules());
        combined.addAll(PIPELINE_ONLY_RULES);
        ALL_RULES = List.copyOf(combined);
    }

    private static final EGraph.CostModel COST_MODEL = PipelineRules::computePipelineCost;

    private static final Set<ENode.Op> COMPARISON_OPS =
            java.util.EnumSet.of(
                    ENode.Op.EQ,
                    ENode.Op.NEQ,
                    ENode.Op.LT,
                    ENode.Op.LTE,
                    ENode.Op.GT,
                    ENode.Op.GTE);

    private static final Set<ENode.Op> ORDER_OPS =
            java.util.EnumSet.of(ENode.Op.LT, ENode.Op.LTE, ENode.Op.GT, ENode.Op.GTE);

    private static final Set<ENode.Op> STREAM_PASSTHROUGH_OPS =
            java.util.EnumSet.of(
                    ENode.Op.PIPE_MATERIALIZE,
                    ENode.Op.PIPE_FILTER,
                    ENode.Op.PIPE_DISTINCT,
                    ENode.Op.PIPE_TAKE_WHILE,
                    ENode.Op.PIPE_GUARD_RANGE);

    /**
     * Joint rule set combining {@link AlgebraicRules#optimizerRules()} and {@code R1}–{@code R8}.
     */
    public static List<EGraph.RewriteRule> allRules() {
        return ALL_RULES;
    }

    /**
     * Monotonic selectivity-weighted {@link EGraph.CostModel} ({@code BIP042-D3}).
     *
     * <p>All child coefficients are strictly non-negative (and positive on stream/expression
     * children) and saturated at {@code 1_000_000}, guaranteeing convergence to the global minimum.
     */
    public static EGraph.CostModel costModel() {
        return COST_MODEL;
    }

    // ---------------------------------------------------------------- symbol encoding helpers

    /** Encodes a {@link ENode.Op#PIPE_SRC} symbol carrying its opaque source id and binder. */
    public static String srcSpec(String srcId, String binder) {
        return srcId + ":" + binder;
    }

    /** Extracts the opaque source id from a {@link ENode.Op#PIPE_SRC} symbol. */
    public static String srcId(String spec) {
        return spec.substring(0, spec.indexOf(':'));
    }

    /** Extracts the element binder symbol from a {@link ENode.Op#PIPE_SRC} symbol. */
    public static String srcBinder(String spec) {
        return spec.substring(spec.indexOf(':') + 1);
    }

    /**
     * Encodes a self-contained {@link ENode.Op#PIPE_RANGE} symbol: {@code
     * "(asc|desc):(incl|excl):<binder>"}.
     */
    public static String rangeSpec(boolean descending, boolean inclusive, String binder) {
        return (descending ? "desc:" : "asc:") + (inclusive ? "incl:" : "excl:") + binder;
    }

    public static boolean isAscendingRange(String spec) {
        return spec.startsWith("asc:");
    }

    public static boolean isDescendingRange(String spec) {
        return spec.startsWith("desc:");
    }

    public static boolean isInclusiveRange(String spec) {
        return spec.contains(":incl:");
    }

    public static String rangeBinder(String spec) {
        return spec.substring(spec.lastIndexOf(':') + 1);
    }

    /** Encodes a {@link ENode.Op#PIPE_FOLD} / {@link ENode.Op#PIPE_FOLD_PAIRWISE} symbol. */
    public static String foldSpec(String opSymbol, String accId) {
        return opSymbol + "|" + accId;
    }

    public static String foldOpSymbol(String spec) {
        return spec.substring(0, spec.lastIndexOf('|'));
    }

    // ---------------------------------------------------------------- selectivity cost model

    private static int computePipelineCost(ENode node, int[] cc) {
        return switch (node.op()) {
            case PIPE_MATERIALIZE -> satAdd(200, cc[0]);
            case PIPE_FILTER -> satAdd(20, satAdd(satMul2(cc[0]), satMul2(cc[1])));
            case PIPE_DISTINCT -> satAdd(15, satMul2(cc[0]));
            case PIPE_TAKE_WHILE -> satAdd(12, satAdd(cc[0], cc[1]));
            case PIPE_GUARD_RANGE -> satAdd(15, satAdd(cc[0], cc[1]));
            case PIPE_MAP -> satAdd(5, satAdd(cc[0], cc[1]));
            case PIPE_FOLD -> satAdd(30, satAdd(cc[0], cc[1]));
            case PIPE_FOLD_PAIRWISE -> satAdd(15, satAdd(cc[0], cc[1]));
            case AND -> satAdd(4, satAdd(satMul2(cc[0]), cc[1]));
            default -> {
                int sum = EGraph.defaultCost(node);
                for (int c : cc) {
                    sum = satAdd(sum, c);
                }
                yield sum;
            }
        };
    }

    private static int satAdd(int a, int b) {
        return Math.min(1_000_000, a + b);
    }

    private static int satMul2(int a) {
        return Math.min(1_000_000, a * 2);
    }

    // ---------------------------------------------------------------- $min / $max constant folding

    private static void minMaxConstFoldRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.CALL || n.arity() != 2 || !n.isIntegral()) {
            return;
        }
        boolean isMin = AlgebraicRules.SYM_MIN.equals(n.symbol());
        if (!isMin && !AlgebraicRules.SYM_MAX.equals(n.symbol())) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        if (g.find(a) == g.find(b)) {
            out.union(id, a);
            return;
        }
        Long va = constantIntegral(g, a);
        Long vb = constantIntegral(g, b);
        if (va != null && vb != null) {
            out.addAndUnion(
                    id, integralConst(n.kind(), isMin ? Math.min(va, vb) : Math.max(va, vb)));
            return;
        }
        // Reassociate nested `$min($min(x, c1), c2) -> $min(x, min(c1, c2))` for multi-bound clamps
        if (vb != null) {
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.CALL && n.symbol().equals(left.symbol())) {
                    Long v1 = constantIntegral(g, left.child(1));
                    if (v1 != null) {
                        long merged = isMin ? Math.min(v1, vb) : Math.max(v1, vb);
                        int mergedId = g.add(integralConst(n.kind(), merged));
                        out.addAndUnion(
                                id, ENode.call(n.symbol(), n.kind(), left.child(0), mergedId));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- R1: Stream fusion

    private static void streamFusionRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.PIPE_MATERIALIZE) {
            out.union(id, n.child(0));
        }
    }

    // ---------------------------------------------------------------- R2 & R3: Filter fusion /
    // reorder / split

    private static void filterFusionAndReorderRules(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_FILTER) {
            return;
        }
        int stream = n.child(0);
        int q = n.child(1);
        boolean outerNarrow = NARROW_TAG.equals(n.symbol());

        // R2 (Filter Fusion) & R3 (Guard-Safe Filter Reordering)
        for (ENode inner : g.nodesIn(stream)) {
            if (inner.op() != ENode.Op.PIPE_FILTER) {
                continue;
            }
            int s = inner.child(0);
            int p = inner.child(1);
            boolean innerNarrow = NARROW_TAG.equals(inner.symbol());
            int conj = g.add(ENode.binary(ENode.Op.AND, PrimitiveKind.BOOL, p, q));
            out.addAndUnion(id, ENode.pipeFilter(innerNarrow || outerNarrow, s, conj));
            if (!innerNarrow && !outerNarrow && g.find(p) != g.find(q)) {
                int swappedInner = g.add(ENode.pipeFilter(false, s, q));
                out.addAndUnion(id, ENode.pipeFilter(false, swappedInner, p));
            }
        }

        // Filter Split: PIPE_FILTER(s, AND(p, q)) -> PIPE_FILTER(PIPE_FILTER(s, p), q)
        // Enables R3 reordering, R4 partial push-down, and R6 multi-bound clamping on `a and b`.
        for (ENode predNode : g.nodesIn(q)) {
            if (predNode.op() != ENode.Op.AND) {
                continue;
            }
            int pLeft = predNode.child(0);
            int pRight = predNode.child(1);
            int innerFilter = g.add(ENode.pipeFilter(outerNarrow, stream, pLeft));
            out.addAndUnion(id, ENode.pipeFilter(outerNarrow, innerFilter, pRight));
        }
    }

    // ---------------------------------------------------------------- R4: Map-filter push-down

    private static void mapFilterPushDownRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_FILTER) {
            return;
        }
        int stream = n.child(0);
        int pred = n.child(1);
        boolean narrowed = NARROW_TAG.equals(n.symbol());

        for (ENode inner : g.nodesIn(stream)) {
            if (inner.op() != ENode.Op.PIPE_MAP) {
                continue;
            }
            String outBinder = inner.symbol();
            int s = inner.child(0);
            int mapExpr = inner.child(1);

            // Case 4a: predicate does not reference outBinder
            if (isIndependentOf(g, pred, outBinder)) {
                int pushed = g.add(ENode.pipeFilter(narrowed, s, pred));
                out.addAndUnion(id, ENode.pipeMap(outBinder, inner.kind(), pushed, mapExpr));
                continue;
            }

            // Case 4b: comparison on outBinder where mapExpr is a unary monotone/injective call
            if (narrowed || !hasMonotoneOrInjectiveUnaryCall(g, mapExpr)) {
                continue;
            }
            for (ENode pNode : g.nodesIn(pred)) {
                if (!isComparisonOp(pNode.op())) {
                    continue;
                }
                int lhs = pNode.child(0);
                int rhs = pNode.child(1);
                if (isBinderClass(g, lhs, outBinder) && isIndependentOf(g, rhs, outBinder)) {
                    int subst = g.add(ENode.binary(pNode.op(), PrimitiveKind.BOOL, mapExpr, rhs));
                    int pushed = g.add(ENode.pipeFilter(false, s, subst));
                    out.addAndUnion(id, ENode.pipeMap(outBinder, inner.kind(), pushed, mapExpr));
                } else if (isBinderClass(g, rhs, outBinder) && isIndependentOf(g, lhs, outBinder)) {
                    int subst = g.add(ENode.binary(pNode.op(), PrimitiveKind.BOOL, lhs, mapExpr));
                    int pushed = g.add(ENode.pipeFilter(false, s, subst));
                    out.addAndUnion(id, ENode.pipeMap(outBinder, inner.kind(), pushed, mapExpr));
                }
            }
        }
    }

    // ---------------------------------------------------------------- R5: Injective distinct
    // push-down

    private static void distinctPushDownRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_DISTINCT) {
            return;
        }
        int stream = n.child(0);
        String setId = n.symbol();
        for (ENode inner : g.nodesIn(stream)) {
            if (inner.op() != ENode.Op.PIPE_MAP) {
                continue;
            }
            int s = inner.child(0);
            int mapExpr = inner.child(1);
            String inBinder = streamBinder(g, s, new HashSet<>());
            if (inBinder == null) {
                continue;
            }
            for (ENode exprNode : g.nodesIn(mapExpr)) {
                if (exprNode.op() != ENode.Op.CALL || exprNode.arity() != 1) {
                    continue;
                }
                if (!g.callProps(exprNode.symbol()).injective()) {
                    continue;
                }
                int prev = exprNode.child(0);
                if (isBinderClass(g, prev, inBinder) && g.isReflexiveEq(prev)) {
                    int pushedDistinct = g.add(ENode.pipeDistinct(setId, s));
                    out.addAndUnion(
                            id,
                            ENode.pipeMap(inner.symbol(), inner.kind(), pushedDistinct, mapExpr));
                }
            }
        }
    }

    // ---------------------------------------------------------------- R6: Direct range bound
    // clamping

    private static void rangeBoundClampRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_FILTER) {
            return;
        }
        int stream = n.child(0);
        int pred = n.child(1);
        for (ENode r : g.nodesIn(stream)) {
            if (r.op() != ENode.Op.PIPE_RANGE || !isAscendingRange(r.symbol()) || !r.isIntegral()) {
                continue;
            }
            boolean incl = isInclusiveRange(r.symbol());
            String binder = rangeBinder(r.symbol());
            if (binder == null) {
                continue;
            }
            PrimitiveKind rk = r.kind();
            int lo = r.child(0);
            int hi = r.child(1);

            for (ENode p : g.nodesIn(pred)) {
                ENode.Op normOp = normalizeOrderAgainstBinder(g, p, binder);
                if (normOp == null) {
                    continue;
                }
                int bound = isBinderClass(g, p.child(0), binder) ? p.child(1) : p.child(0);
                if (!isIndependentOf(g, bound, binder) || inferKind(g, bound) != rk) {
                    continue;
                }
                switch (normOp) {
                    case LTE -> {
                        if (incl) {
                            int newHi = g.add(ENode.call(AlgebraicRules.SYM_MIN, rk, hi, bound));
                            out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, lo, newHi));
                        } else {
                            Long c = constantIntegral(g, bound);
                            if (c != null && c < maxVal(rk)) {
                                int adj = g.add(integralConst(rk, c + 1L));
                                int newHi = g.add(ENode.call(AlgebraicRules.SYM_MIN, rk, hi, adj));
                                out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, lo, newHi));
                            }
                        }
                    }
                    case LT -> {
                        if (!incl) {
                            int newHi = g.add(ENode.call(AlgebraicRules.SYM_MIN, rk, hi, bound));
                            out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, lo, newHi));
                        } else {
                            Long c = constantIntegral(g, bound);
                            if (c != null && c > minVal(rk)) {
                                int adj = g.add(integralConst(rk, c - 1L));
                                int newHi = g.add(ENode.call(AlgebraicRules.SYM_MIN, rk, hi, adj));
                                out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, lo, newHi));
                            }
                        }
                    }
                    case GTE -> {
                        int newLo = g.add(ENode.call(AlgebraicRules.SYM_MAX, rk, lo, bound));
                        out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, newLo, hi));
                    }
                    case GT -> {
                        Long c = constantIntegral(g, bound);
                        if (c != null && c < maxVal(rk)) {
                            int adj = g.add(integralConst(rk, c + 1L));
                            int newLo = g.add(ENode.call(AlgebraicRules.SYM_MAX, rk, lo, adj));
                            out.addAndUnion(id, ENode.pipeRange(r.symbol(), rk, newLo, hi));
                        }
                    }
                    default -> {}
                }
            }
        }
    }

    // ---------------------------------------------------------------- R7: Monotone early exit &
    // whole-range guard

    private static void monotoneRangeRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_FILTER || NARROW_TAG.equals(n.symbol())) {
            return;
        }
        int stream = n.child(0);
        int pred = n.child(1);
        for (ENode r : g.nodesIn(stream)) {
            if (r.op() != ENode.Op.PIPE_RANGE || !r.isIntegral()) {
                continue;
            }
            boolean asc = isAscendingRange(r.symbol());
            boolean incl = isInclusiveRange(r.symbol());
            String binder = rangeBinder(r.symbol());
            int lo = r.child(0);
            int hi = r.child(1);

            for (ENode p : g.nodesIn(pred)) {
                if (!isOrderOp(p.op())) {
                    continue;
                }
                int lhs = p.child(0);
                int rhs = p.child(1);
                tryMonotonePredicate(
                        g, id, stream, pred, lo, hi, asc, incl, binder, p.op(), lhs, rhs, out);
                tryMonotonePredicate(
                        g,
                        id,
                        stream,
                        pred,
                        lo,
                        hi,
                        asc,
                        incl,
                        binder,
                        flipOrderOp(p.op()),
                        rhs,
                        lhs,
                        out);
            }
        }
    }

    private static void tryMonotonePredicate(
            EGraph g,
            int filterClassId,
            int rangeClassId,
            int predClassId,
            int lo,
            int hi,
            boolean asc,
            boolean incl,
            String binder,
            ENode.Op normOp,
            int callSide,
            int limSide,
            EGraph.MatchRecorder out) {
        if (!isIndependentOf(g, limSide, binder)) {
            return;
        }
        for (ENode call : g.nodesIn(callSide)) {
            if (call.op() != ENode.Op.CALL || call.arity() != 1) {
                continue;
            }
            if (!g.callProps(call.symbol()).monotone()) {
                continue;
            }
            if (!isBinderClass(g, call.child(0), binder)) {
                continue;
            }
            boolean isUpper = normOp == ENode.Op.LTE || normOp == ENode.Op.LT;
            // Early exit (PIPE_TAKE_WHILE): ascending <= / < or descending >= / >
            if (asc == isUpper) {
                out.addAndUnion(filterClassId, ENode.pipeTakeWhile(rangeClassId, predClassId));
                return;
            }
            // O(1) whole-range guard (PIPE_GUARD_RANGE): ascending inclusive >= / >
            if (asc && incl) {
                int nonEmpty = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, lo, hi));
                int fHi = g.add(ENode.call(call.symbol(), call.kind(), hi));
                int boundCheck = g.add(ENode.binary(normOp, PrimitiveKind.BOOL, fHi, limSide));
                int guard =
                        g.add(ENode.binary(ENode.Op.AND, PrimitiveKind.BOOL, nonEmpty, boundCheck));
                out.addAndUnion(
                        filterClassId, ENode.pipeGuardRange(rangeClassId, predClassId, guard));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- R8: Associative pairwise
    // fold

    private static void associativePairwiseFoldRule(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.PIPE_FOLD) {
            return;
        }
        String opSym = foldOpSymbol(n.symbol());
        if (!g.callProps(opSym).associative()) {
            return;
        }
        int stream = n.child(0);
        int elemExpr = n.child(1);
        for (ENode r : g.nodesIn(stream)) {
            if (r.op() == ENode.Op.PIPE_RANGE && isAscendingRange(r.symbol()) && r.isIntegral()) {
                out.addAndUnion(id, ENode.pipeFoldPairwise(n.symbol(), n.kind(), stream, elemExpr));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- cycle-safe helpers

    /**
     * Cycle-safe reachability check: returns {@code true} iff e-class {@code classId} has an
     * acyclic representation that does not reference {@code OPAQUE(binderSym)}.
     */
    public static boolean isIndependentOf(EGraph g, int classId, String binderSym) {
        return isIndependentRec(g, g.find(classId), binderSym, new HashSet<>(), new HashMap<>());
    }

    private static boolean isIndependentRec(
            EGraph g,
            int classId,
            String binderSym,
            Set<Integer> visiting,
            Map<Integer, Boolean> memo) {
        Boolean cached = memo.get(classId);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(classId)) {
            return false;
        }
        boolean independent = false;
        for (ENode node : g.nodesIn(classId)) {
            if (node.op() == ENode.Op.OPAQUE && binderSym.equals(node.symbol())) {
                continue;
            }
            boolean allKids = true;
            for (int i = 0; i < node.arity(); i++) {
                if (!isIndependentRec(g, g.find(node.child(i)), binderSym, visiting, memo)) {
                    allKids = false;
                    break;
                }
            }
            if (allKids) {
                independent = true;
                break;
            }
        }
        visiting.remove(classId);
        memo.put(classId, independent);
        return independent;
    }

    private static boolean isBinderClass(EGraph g, int classId, String binderSym) {
        for (ENode node : g.nodesIn(classId)) {
            if (node.op() == ENode.Op.OPAQUE && binderSym.equals(node.symbol())) {
                return true;
            }
        }
        return false;
    }

    private static String streamBinder(EGraph g, int streamClassId, Set<Integer> visiting) {
        int root = g.find(streamClassId);
        if (!visiting.add(root)) {
            return null;
        }
        try {
            for (ENode node : g.nodesIn(root)) {
                ENode.Op op = node.op();
                if (op == ENode.Op.PIPE_MAP) {
                    return node.symbol();
                }
                if (op == ENode.Op.PIPE_RANGE) {
                    return rangeBinder(node.symbol());
                }
                if (op == ENode.Op.PIPE_SRC) {
                    return srcBinder(node.symbol());
                }
                if (STREAM_PASSTHROUGH_OPS.contains(op)) {
                    String b = streamBinder(g, node.child(0), visiting);
                    if (b != null) {
                        return b;
                    }
                }
            }
            return null;
        } finally {
            visiting.remove(root);
        }
    }

    private static boolean hasMonotoneOrInjectiveUnaryCall(EGraph g, int classId) {
        for (ENode node : g.nodesIn(classId)) {
            if (node.op() == ENode.Op.CALL && node.arity() == 1) {
                EGraph.CallProps props = g.callProps(node.symbol());
                if (props.monotone() || props.injective()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isComparisonOp(ENode.Op op) {
        return COMPARISON_OPS.contains(op);
    }

    private static boolean isOrderOp(ENode.Op op) {
        return ORDER_OPS.contains(op);
    }

    private static ENode.Op flipOrderOp(ENode.Op op) {
        return switch (op) {
            case LT -> ENode.Op.GT;
            case LTE -> ENode.Op.GTE;
            case GT -> ENode.Op.LT;
            case GTE -> ENode.Op.LTE;
            default -> op;
        };
    }

    private static ENode.Op normalizeOrderAgainstBinder(EGraph g, ENode p, String binder) {
        if (!isOrderOp(p.op())) {
            return null;
        }
        if (isBinderClass(g, p.child(0), binder)) {
            return p.op();
        }
        if (isBinderClass(g, p.child(1), binder)) {
            return flipOrderOp(p.op());
        }
        return null;
    }

    private static PrimitiveKind inferKind(EGraph g, int classId) {
        for (ENode node : g.nodesIn(classId)) {
            if (node.kind() != PrimitiveKind.NONE) {
                return node.kind();
            }
        }
        return PrimitiveKind.NONE;
    }

    private static Long constantIntegral(EGraph g, int classId) {
        ENode c = g.constantOf(classId);
        return c != null ? c.asIntegralLong() : null;
    }

    private static ENode integralConst(PrimitiveKind kind, long value) {
        Object boxed =
                kind == PrimitiveKind.LONG
                        ? (Object) Long.valueOf(value)
                        : Integer.valueOf((int) value);
        return ENode.constVal(boxed, kind);
    }

    private static long minVal(PrimitiveKind kind) {
        return kind == PrimitiveKind.LONG ? Long.MIN_VALUE : Integer.MIN_VALUE;
    }

    private static long maxVal(PrimitiveKind kind) {
        return kind == PrimitiveKind.LONG ? Long.MAX_VALUE : Integer.MAX_VALUE;
    }
}
