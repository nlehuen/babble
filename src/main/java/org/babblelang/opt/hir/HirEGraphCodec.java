package org.babblelang.opt.hir;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;

/**
 * Shared HIR-to-{@link EGraph} codec, cost model, saturation driver, and idempotent-call
 * eligibility helpers ({@code BIP027-D1}, {@code BIP039-I0}), designed for reuse across {@link
 * HirEGraphSimplifyPass} and future {@code -O2} dominator-scoped AEGraph passes.
 */
final class HirEGraphCodec {
    private static final int EGRAPH_MAX_ITERS = 6;
    private static final int EGRAPH_MAX_NODES = 96;

    private HirEGraphCodec() {}

    static boolean isEGraphCandidateRoot(HirExpr expr, HirModule module) {
        if (expr instanceof HirExpr.Unary u) {
            return u.meta().wideningRank() < 0
                    && u.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(u, module);
        }
        if (expr instanceof HirExpr.Binary b) {
            return b.meta().wideningRank() < 0
                    && b.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(b, module);
        }
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            return isIdempotentBabbleEligible(c, module)
                    && HirPurity.isPureNothrowTerminating(c.args().get(0), module);
        }
        if (expr instanceof HirExpr.JavaMethodCall c) {
            return isIdempotentJavaEligible(c, module)
                    && (c.target().isStatic()
                            ? HirPurity.isPureNothrowTerminating(c.args().get(0), module)
                            : HirPurity.isPureNothrowTerminating(c.receiver(), module));
        }
        return false;
    }

    static final class LowerContext {
        final EGraph graph = new EGraph();
        final HirModule module;
        final Map<String, HirExpr> leaves = new HashMap<>();
        final Map<String, HirExpr> callTemplates = new HashMap<>();
        int nextOpaque = 0;

        LowerContext(HirModule module) {
            this.module = module;
        }

        private static boolean isLowerableComparison(HirExpr.Binary b) {
            if (!b.op().isComparison()) {
                return false;
            }
            if (b.left().meta().wideningRank() >= 0 || b.right().meta().wideningRank() >= 0) {
                return false;
            }
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rrk = b.right().meta().kind();
            if (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ) {
                if (lk.isPrimitive() && lk == rrk) {
                    return true;
                }
                return b.left().meta().stringType() && b.right().meta().stringType();
            }
            return lk == rrk && (HirPurity.isNumericKind(lk) || lk == PrimitiveKind.CHAR);
        }

        private static ENode.Op toComparisonOp(HirExpr.BinaryOp op) {
            return switch (op) {
                case EQ -> ENode.Op.EQ;
                case NEQ -> ENode.Op.NEQ;
                case LT -> ENode.Op.LT;
                case LTE -> ENode.Op.LTE;
                case GT -> ENode.Op.GT;
                case GTE -> ENode.Op.GTE;
                default -> throw new IllegalStateException();
            };
        }

        int lower(HirExpr raw) {
            HirExpr expr = HirRewriter.unwrapTransparent(raw);
            if (expr instanceof HirExpr.Const c
                    && c.meta().wideningRank() < 0
                    && c.meta().checkedType() == null) {
                return graph.add(ENode.constVal(c.value(), c.meta().kind()));
            }
            if (expr instanceof HirExpr.VarRead v
                    && v.meta().wideningRank() < 0
                    && v.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(v, module)) {
                String key = "v#" + v.var().id();
                leaves.putIfAbsent(key, v);
                int id = graph.add(ENode.opaque(key, v.meta().kind()));
                if (v.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            if (expr instanceof HirExpr.Unary u
                    && u.meta().wideningRank() < 0
                    && u.meta().checkedType() == null) {
                if (u.op() == HirExpr.UnaryOp.NOT
                        && u.operand().meta().kind() == PrimitiveKind.BOOL
                        && u.operand().meta().wideningRank() < 0) {
                    return graph.add(
                            ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, lower(u.operand())));
                }
                if (u.op() == HirExpr.UnaryOp.NEGATE
                        && HirPurity.isIntegralKind(u.meta().kind())
                        && u.operand().meta().kind() == u.meta().kind()
                        && u.operand().meta().wideningRank() < 0) {
                    return graph.add(
                            ENode.unary(ENode.Op.NEG, u.meta().kind(), lower(u.operand())));
                }
            }
            if (expr instanceof HirExpr.Binary b
                    && b.meta().wideningRank() < 0
                    && b.meta().checkedType() == null) {
                PrimitiveKind rk = b.meta().kind();
                PrimitiveKind lk = b.left().meta().kind();
                PrimitiveKind rrk = b.right().meta().kind();
                if (HirPurity.isIntegralKind(rk)
                        && lk == rk
                        && rrk == rk
                        && b.left().meta().wideningRank() < 0
                        && b.right().meta().wideningRank() < 0) {
                    ENode.Op op =
                            switch (b.op()) {
                                case ADD -> ENode.Op.ADD;
                                case SUB -> ENode.Op.SUB;
                                case MUL -> ENode.Op.MUL;
                                case FLOOR_DIV -> ENode.Op.FLOOR_DIV;
                                case MOD -> ENode.Op.MOD;
                                default -> null;
                            };
                    if (op != null) {
                        return graph.add(ENode.binary(op, rk, lower(b.left()), lower(b.right())));
                    }
                }
                if (isLowerableComparison(b)) {
                    return graph.add(
                            ENode.binary(
                                    toComparisonOp(b.op()),
                                    PrimitiveKind.BOOL,
                                    lower(b.left()),
                                    lower(b.right())));
                }
                if (b.op() == HirExpr.BinaryOp.ADD
                        && b.left().meta().stringType()
                        && b.right().meta().stringType()
                        && b.left().meta().wideningRank() < 0
                        && b.right().meta().wideningRank() < 0) {
                    return graph.add(
                            ENode.binary(
                                    ENode.Op.STR_ADD,
                                    PrimitiveKind.NONE,
                                    lower(b.left()),
                                    lower(b.right())));
                }
            }
            if (expr instanceof HirExpr.DirectBabbleCall c
                    && isAlgebraicUnaryBabbleEligible(c, module)) {
                String sym =
                        c.externalTarget() != null
                                ? "babble-ext#" + c.externalTarget().canonicalKey()
                                : "babble#" + c.targetFunctionId();
                callTemplates.putIfAbsent(sym, c);
                graph.registerCallProps(
                        sym,
                        new EGraph.CallProps(
                                HirPurity.isIdempotentCall(c, module),
                                false,
                                false,
                                false,
                                HirPurity.isMonotoneCall(c, module),
                                HirPurity.isInjectiveCall(c, module)));
                int id = graph.add(ENode.call(sym, c.meta().kind(), lower(c.args().get(0))));
                if (c.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            if (expr instanceof HirExpr.JavaMethodCall c
                    && isAlgebraicUnaryJavaEligible(c, module)) {
                Method m = c.target().method();
                String sym =
                        (c.target().isStatic() ? "jstatic#" : "jinst#")
                                + m.getDeclaringClass().getName()
                                + "."
                                + m.getName()
                                + ":"
                                + m.getParameterCount();
                callTemplates.putIfAbsent(sym, c);
                graph.registerCallProps(
                        sym,
                        new EGraph.CallProps(
                                HirPurity.isIdempotentCall(c, module),
                                false,
                                false,
                                false,
                                HirPurity.isMonotoneCall(c, module),
                                HirPurity.isInjectiveCall(c, module)));
                int child = c.target().isStatic() ? lower(c.args().get(0)) : lower(c.receiver());
                int id = graph.add(ENode.call(sym, c.meta().kind(), child));
                if (c.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            String key = "p#" + (nextOpaque++);
            leaves.put(key, raw);
            int id = graph.add(ENode.opaque(key, raw.meta().kind()));
            if (raw.meta().stringType()) {
                graph.markReflexiveEq(id);
            }
            return id;
        }

        int treeCost(HirExpr raw) {
            HirExpr expr = HirRewriter.unwrapTransparent(raw);
            if (expr instanceof HirExpr.Const) {
                return EGraph.defaultCost(ENode.constVal(null, PrimitiveKind.NONE));
            }
            if (expr instanceof HirExpr.Unary u
                    && u.meta().wideningRank() < 0
                    && u.meta().checkedType() == null
                    && ((u.op() == HirExpr.UnaryOp.NOT
                                    && u.operand().meta().kind() == PrimitiveKind.BOOL)
                            || (u.op() == HirExpr.UnaryOp.NEGATE
                                    && HirPurity.isIntegralKind(u.meta().kind())))) {
                return 3 + treeCost(u.operand());
            }
            if (expr instanceof HirExpr.Binary b
                    && b.meta().wideningRank() < 0
                    && b.meta().checkedType() == null) {
                PrimitiveKind rk = b.meta().kind();
                if ((HirPurity.isIntegralKind(rk)
                                && b.left().meta().kind() == rk
                                && b.right().meta().kind() == rk
                                && (b.op() == HirExpr.BinaryOp.ADD
                                        || b.op() == HirExpr.BinaryOp.SUB
                                        || b.op() == HirExpr.BinaryOp.MUL
                                        || b.op() == HirExpr.BinaryOp.FLOOR_DIV
                                        || b.op() == HirExpr.BinaryOp.MOD))
                        || isLowerableComparison(b)
                        || (b.op() == HirExpr.BinaryOp.ADD
                                && b.left().meta().stringType()
                                && b.right().meta().stringType())) {
                    return 4 + treeCost(b.left()) + treeCost(b.right());
                }
            }
            if (expr instanceof HirExpr.DirectBabbleCall c
                    && isAlgebraicUnaryBabbleEligible(c, module)) {
                return 10 + treeCost(c.args().get(0));
            }
            if (expr instanceof HirExpr.JavaMethodCall c
                    && isAlgebraicUnaryJavaEligible(c, module)) {
                return 10 + treeCost(c.target().isStatic() ? c.args().get(0) : c.receiver());
            }
            return 2;
        }

        HirExpr rebuild(EGraph.ExtractedTree tree, ExprMeta rootMeta) {
            ENode n = tree.node();
            return switch (n.op()) {
                case CONST -> {
                    boolean isStr = n.literal() instanceof String;
                    ExprMeta m =
                            isStr
                                    ? new ExprMeta(
                                            rootMeta.line(),
                                            PrimitiveKind.NONE,
                                            -1,
                                            null,
                                            rootMeta.effects(),
                                            true,
                                            true)
                                    : rootMeta.withKind(n.kind()).withWideningRank(-1);
                    yield new HirExpr.Const(n.literal(), m);
                }
                case OPAQUE -> leaves.get(n.symbol());
                case NOT -> {
                    HirExpr op = rebuild(tree.children().get(0), rootMeta);
                    yield new HirExpr.Unary(
                            HirExpr.UnaryOp.NOT,
                            op,
                            "",
                            rootMeta.withKind(PrimitiveKind.BOOL).withWideningRank(-1));
                }
                case NEG -> {
                    HirExpr op = rebuild(tree.children().get(0), rootMeta);
                    yield new HirExpr.Unary(
                            HirExpr.UnaryOp.NEGATE,
                            op,
                            "",
                            rootMeta.withKind(n.kind()).withWideningRank(-1));
                }
                case ADD, SUB, MUL, FLOOR_DIV, MOD, EQ, NEQ, LT, LTE, GT, GTE -> {
                    HirExpr l = rebuild(tree.children().get(0), rootMeta);
                    HirExpr r = rebuild(tree.children().get(1), rootMeta);
                    HirExpr.BinaryOp bop =
                            switch (n.op()) {
                                case ADD -> HirExpr.BinaryOp.ADD;
                                case SUB -> HirExpr.BinaryOp.SUB;
                                case MUL -> HirExpr.BinaryOp.MUL;
                                case FLOOR_DIV -> HirExpr.BinaryOp.FLOOR_DIV;
                                case MOD -> HirExpr.BinaryOp.MOD;
                                case EQ -> HirExpr.BinaryOp.EQ;
                                case NEQ -> HirExpr.BinaryOp.NEQ;
                                case LT -> HirExpr.BinaryOp.LT;
                                case LTE -> HirExpr.BinaryOp.LTE;
                                case GT -> HirExpr.BinaryOp.GT;
                                case GTE -> HirExpr.BinaryOp.GTE;
                                default -> throw new IllegalStateException();
                            };
                    yield new HirExpr.Binary(
                            bop, l, r, "", "", rootMeta.withKind(n.kind()).withWideningRank(-1));
                }
                case STR_ADD -> {
                    HirExpr l = rebuild(tree.children().get(0), rootMeta);
                    HirExpr r = rebuild(tree.children().get(1), rootMeta);
                    ExprMeta sm =
                            new ExprMeta(
                                    rootMeta.line(),
                                    PrimitiveKind.NONE,
                                    -1,
                                    null,
                                    rootMeta.effects(),
                                    true,
                                    true);
                    yield new HirExpr.Binary(HirExpr.BinaryOp.ADD, l, r, "", "", sm);
                }
                case CALL -> {
                    HirExpr tmpl = callTemplates.get(n.symbol());
                    HirExpr arg = rebuild(tree.children().get(0), rootMeta);
                    if (tmpl instanceof HirExpr.DirectBabbleCall dc) {
                        yield new HirExpr.DirectBabbleCall(
                                dc.callee(),
                                dc.targetFunctionId(),
                                dc.externalTarget(),
                                List.of(arg),
                                dc.binding(),
                                dc.meta());
                    }
                    HirExpr.JavaMethodCall jc = (HirExpr.JavaMethodCall) tmpl;
                    if (jc.target().isStatic()) {
                        yield new HirExpr.JavaMethodCall(
                                jc.receiver(),
                                jc.receiverText(),
                                jc.selectorLine(),
                                jc.pureStaticReceiver(),
                                jc.target(),
                                List.of(arg),
                                jc.meta());
                    }
                    yield new HirExpr.JavaMethodCall(
                            arg,
                            jc.receiverText(),
                            jc.selectorLine(),
                            jc.pureStaticReceiver(),
                            jc.target(),
                            jc.args(),
                            jc.meta());
                }
                default -> throw new IllegalStateException("Unexpected extracted op: " + n.op());
            };
        }
    }

    static HirExpr tryEGraphSimplify(HirExpr root, HirModule module) {
        LowerContext lc = new LowerContext(module);
        int origCost = lc.treeCost(root);
        int rootId = lc.lower(root);
        lc.graph.saturate(AlgebraicRules.optimizerRules(), EGRAPH_MAX_ITERS, EGRAPH_MAX_NODES);
        EGraph.ExtractedTree best = lc.graph.extract(rootId, EGraph::defaultCost);
        int newCost = extractedCost(best);
        boolean canonicalizedConstRight =
                newCost == origCost
                        && root instanceof HirExpr.Binary b
                        && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.MUL)
                        && HirPurity.isIntegralKind(b.meta().kind())
                        && HirRewriter.unwrapTransparent(b.left()) instanceof HirExpr.Const
                        && !(HirRewriter.unwrapTransparent(b.right()) instanceof HirExpr.Const)
                        && best.children().size() == 2
                        && best.children().get(1).node().isConst()
                        && !best.children().get(0).node().isConst();
        if (newCost < origCost || canonicalizedConstRight) {
            return lc.rebuild(best, root.meta());
        }
        return null;
    }

    private static int extractedCost(EGraph.ExtractedTree tree) {
        int sum = EGraph.defaultCost(tree.node());
        for (EGraph.ExtractedTree kid : tree.children()) {
            sum += extractedCost(kid);
        }
        return sum;
    }

    static boolean isIdempotentBabbleEligible(HirExpr.DirectBabbleCall c, HirModule module) {
        return isUnaryBabbleCallShape(c, module) && HirPurity.isIdempotentCall(c, module);
    }

    static boolean isAlgebraicUnaryBabbleEligible(HirExpr.DirectBabbleCall c, HirModule module) {
        if (!isUnaryBabbleCallShape(c, module)) {
            return false;
        }
        if (HirPurity.isIdempotentCall(c, module)) {
            return true;
        }
        return (HirPurity.isMonotoneCall(c, module) || HirPurity.isInjectiveCall(c, module))
                && HirPurity.isPureNothrowTerminating(c, module);
    }

    private static boolean isUnaryBabbleCallShape(HirExpr.DirectBabbleCall c, HirModule module) {
        if (c.args().size() != 1
                || c.meta().checkedType() != null
                || c.meta().wideningRank() >= 0
                || (c.binding() != null && !c.binding().isIdentityPositional())) {
            return false;
        }
        return HirPurity.isPureNothrowTerminating(c.callee(), module)
                || (c.callee() instanceof HirExpr.NameRead nr
                        && HirPurity.isDefinitelyInitializedTopLevelFn(nr.name(), module));
    }

    static boolean sameDirectCallTarget(HirExpr.DirectBabbleCall a, HirExpr.DirectBabbleCall b) {
        if (a.externalTarget() != null || b.externalTarget() != null) {
            return a.externalTarget() != null
                    && b.externalTarget() != null
                    && a.externalTarget().canonicalKey().equals(b.externalTarget().canonicalKey());
        }
        return a.targetFunctionId() == b.targetFunctionId();
    }

    static boolean isIdempotentJavaEligible(HirExpr.JavaMethodCall c, HirModule module) {
        return isUnaryJavaCallShape(c, true) && HirPurity.isIdempotentCall(c, module);
    }

    static boolean isAlgebraicUnaryJavaEligible(HirExpr.JavaMethodCall c, HirModule module) {
        if (isIdempotentJavaEligible(c, module)) {
            return true;
        }
        return isUnaryJavaCallShape(c, false)
                && (HirPurity.isMonotoneCall(c, module) || HirPurity.isInjectiveCall(c, module))
                && c.meta().isNothrow()
                && c.meta().isTerminating();
    }

    private static boolean isUnaryJavaCallShape(
            HirExpr.JavaMethodCall c, boolean allowWhitelistedStrCheck) {
        if (c.meta().wideningRank() >= 0) {
            return false;
        }
        boolean isWhitelistedStr =
                allowWhitelistedStrCheck
                        && c.target().method().getDeclaringClass() == String.class
                        && BuiltinAxioms.current().isFoldable(c.target().method());
        if (c.meta().checkedType() != null
                && !(isWhitelistedStr && HirPurity.isStringCheckedType(c.meta().checkedType()))) {
            return false;
        }
        return (c.target().isStatic() && c.pureStaticReceiver() && c.args().size() == 1)
                || (!c.target().isStatic() && c.args().isEmpty());
    }
}
