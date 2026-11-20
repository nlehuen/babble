package org.babblelang.opt.hir;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

import java.util.List;

/**
 * {@code O2}/{@code O3} — Algebraic Identities, {@code idempotent} Collapse, and
 * {@code associative}/{@code commutative} Canonicalization (BIP014-P2).
 *
 * <p>Strictly enforces IEEE-754 discipline: never applies {@code x * 0.0 -> 0.0},
 * {@code x + 0.0 -> x}, {@code x == x -> true}, or floating-point reassociation.
 */
public final class AlgebraicSimplifyPass implements HirPass {
    @Override
    public String name() {
        return "AlgebraicSimplify";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        HirModule updated = HirRewriter.rewriteModuleBodies(module,
                stmts -> HirRewriter.rewriteList(stmts, e -> simplify(e, module, rewrites)));
        return new Result(updated, rewrites[0]);
    }

    private HirExpr simplify(HirExpr expr, HirModule module, int[] rewrites) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> simplify(child, module, rewrites));

        if (cur instanceof HirExpr.Unary u) {
            HirExpr s = simplifyUnary(u);
            if (s != null) {
                rewrites[0]++;
                return s;
            }
        }
        if (cur instanceof HirExpr.Binary b) {
            HirExpr s = simplifyBinary(b, module);
            if (s != null) {
                rewrites[0]++;
                return s;
            }
        }
        if (cur instanceof HirExpr.DirectBabbleCall c) {
            HirExpr s = simplifyIdempotentBabble(c, module);
            if (s != null) {
                rewrites[0]++;
                return s;
            }
        }
        if (cur instanceof HirExpr.JavaMethodCall c) {
            HirExpr s = simplifyIdempotentJava(c, module);
            if (s != null) {
                rewrites[0]++;
                return s;
            }
        }
        return cur;
    }

    private static HirExpr simplifyUnary(HirExpr.Unary u) {
        // not (not b) -> b (only when b is BOOL so Truth.test conversion isn't dropped!)
        if (u.op() == HirExpr.UnaryOp.NOT && u.meta().wideningRank() < 0) {
            HirExpr inner = ConstFoldPass.unwrapTransparent(u.operand());
            if (inner instanceof HirExpr.Unary innerNot && innerNot.op() == HirExpr.UnaryOp.NOT) {
                HirExpr b = ConstFoldPass.unwrapTransparent(innerNot.operand());
                if (b.meta().kind() == ScopeAnalysis.PrimitiveKind.BOOL && b.meta().wideningRank() < 0) {
                    return b;
                }
            }
        }
        return null;
    }

    private static HirExpr simplifyBinary(HirExpr.Binary b, HirModule module) {
        if (b.meta().checkedType() != null) {
            return null;
        }
        ScopeAnalysis.PrimitiveKind rk = b.meta().kind();
        ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
        ScopeAnalysis.PrimitiveKind rrk = b.right().meta().kind();

        // Boolean comparison identities: b == true -> b, b == false -> not b, etc.
        if (lk == ScopeAnalysis.PrimitiveKind.BOOL && rrk == ScopeAnalysis.PrimitiveKind.BOOL
                && b.meta().wideningRank() < 0
                && (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)) {
            HirExpr l = ConstFoldPass.unwrapTransparent(b.left());
            HirExpr r = ConstFoldPass.unwrapTransparent(b.right());
            if (r instanceof HirExpr.Const rc && rc.value() instanceof Boolean rv && b.left().meta().wideningRank() < 0) {
                boolean wantSame = (b.op() == HirExpr.BinaryOp.EQ) == rv;
                return wantSame ? b.left() : new HirExpr.Unary(HirExpr.UnaryOp.NOT, b.left(), b.leftText(), b.meta());
            }
            if (l instanceof HirExpr.Const lc && lc.value() instanceof Boolean lv && b.right().meta().wideningRank() < 0) {
                boolean wantSame = (b.op() == HirExpr.BinaryOp.EQ) == lv;
                return wantSame ? b.right() : new HirExpr.Unary(HirExpr.UnaryOp.NOT, b.right(), b.rightText(), b.meta());
            }
        }

        // Strict IEEE-754 discipline: arithmetic identities & reassociation ONLY for INT / LONG!
        if (HirPurity.isIntegralKind(rk) && lk == rk && rrk == rk && b.meta().wideningRank() < 0
                && b.left().meta().wideningRank() < 0 && b.right().meta().wideningRank() < 0) {
            HirExpr l = ConstFoldPass.unwrapTransparent(b.left());
            HirExpr r = ConstFoldPass.unwrapTransparent(b.right());
            Long lVal = asIntegral(l);
            Long rVal = asIntegral(r);

            switch (b.op()) {
                case ADD -> {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) return b.right();
                    // Commutative canonicalization: c + x -> x + c (when x is pure)
                    if (lVal != null && rVal == null && HirPurity.isPureNothrowTerminating(b.right(), module)) {
                        return new HirExpr.Binary(HirExpr.BinaryOp.ADD, b.right(), b.left(), b.rightText(), b.leftText(), b.meta());
                    }
                    // Associative reassociation: (x + c1) + c2 -> x + (c1 + c2)
                    if (rVal != null && l instanceof HirExpr.Binary lb && lb.op() == HirExpr.BinaryOp.ADD
                            && lb.meta().kind() == rk && lb.left().meta().kind() == rk && lb.right().meta().kind() == rk
                            && lb.meta().wideningRank() < 0) {
                        Long c1 = asIntegral(ConstFoldPass.unwrapTransparent(lb.right()));
                        if (c1 != null) {
                            HirExpr.Const combined = makeIntegralConst(rk, c1 + rVal, b.meta());
                            return new HirExpr.Binary(HirExpr.BinaryOp.ADD, lb.left(), combined, lb.leftText(), combined.value().toString(), b.meta());
                        }
                    }
                }
                case SUB -> {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) {
                        return new HirExpr.Unary(HirExpr.UnaryOp.NEGATE, b.right(), b.rightText(), b.meta());
                    }
                    // x - x -> 0 (for pure non-throwing local VarRead)
                    if (l instanceof HirExpr.VarRead lv && r instanceof HirExpr.VarRead rv
                            && lv.var().id() == rv.var().id()
                            && HirPurity.isPureNothrowTerminating(lv, module)) {
                        return makeIntegralConst(rk, 0L, b.meta());
                    }
                }
                case MUL -> {
                    if (rVal != null && rVal == 1L) return b.left();
                    if (lVal != null && lVal == 1L) return b.right();
                    if (rVal != null && rVal == 0L && HirPurity.isPureNothrowTerminating(b.left(), module)) {
                        return makeIntegralConst(rk, 0L, b.meta());
                    }
                    if (lVal != null && lVal == 0L && HirPurity.isPureNothrowTerminating(b.right(), module)) {
                        return makeIntegralConst(rk, 0L, b.meta());
                    }
                    // Commutative canonicalization: c * x -> x * c (when x is pure)
                    if (lVal != null && rVal == null && HirPurity.isPureNothrowTerminating(b.right(), module)) {
                        return new HirExpr.Binary(HirExpr.BinaryOp.MUL, b.right(), b.left(), b.rightText(), b.leftText(), b.meta());
                    }
                    // Associative reassociation: (x * c1) * c2 -> x * (c1 * c2)
                    if (rVal != null && l instanceof HirExpr.Binary lb && lb.op() == HirExpr.BinaryOp.MUL
                            && lb.meta().kind() == rk && lb.left().meta().kind() == rk && lb.right().meta().kind() == rk
                            && lb.meta().wideningRank() < 0) {
                        Long c1 = asIntegral(ConstFoldPass.unwrapTransparent(lb.right()));
                        if (c1 != null) {
                            HirExpr.Const combined = makeIntegralConst(rk, c1 * rVal, b.meta());
                            return new HirExpr.Binary(HirExpr.BinaryOp.MUL, lb.left(), combined, lb.leftText(), combined.value().toString(), b.meta());
                        }
                    }
                }
                default -> {
                }
            }
        }

        // String + associative reassociation: (s + "a") + "b" -> s + "ab"
        if (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType()
                && b.meta().wideningRank() < 0) {
            HirExpr l = ConstFoldPass.unwrapTransparent(b.left());
            HirExpr r = ConstFoldPass.unwrapTransparent(b.right());
            if (r instanceof HirExpr.Const rc && rc.value() instanceof String rs
                    && l instanceof HirExpr.Binary lb && lb.op() == HirExpr.BinaryOp.ADD
                    && lb.left().meta().stringType() && lb.right().meta().stringType()
                    && ConstFoldPass.unwrapTransparent(lb.right()) instanceof HirExpr.Const lrc
                    && lrc.value() instanceof String lrs
                    && lrs.length() + rs.length() <= 4096) {
                HirExpr.Const merged = new HirExpr.Const(lrs + rs,
                        new ExprMeta(b.meta().line(), ScopeAnalysis.PrimitiveKind.NONE, -1, null, b.meta().effects(), true, true));
                return new HirExpr.Binary(HirExpr.BinaryOp.ADD, lb.left(), merged, lb.leftText(), "\"...\"", b.meta());
            }
        }

        return null;
    }

    private static HirExpr simplifyIdempotentBabble(HirExpr.DirectBabbleCall outer, HirModule module) {
        if (outer.args().size() != 1 || outer.meta().checkedType() != null || outer.meta().wideningRank() >= 0) {
            return null;
        }
        if (!HirPurity.isIdempotentCall(outer, module)) {
            return null;
        }
        boolean calleePure = HirPurity.isPureNothrowTerminating(outer.callee(), module)
                || (outer.callee() instanceof HirExpr.NameRead nr
                && HirPurity.isDefinitelyInitializedTopLevelFn(nr.name(), module));
        if (!calleePure) {
            return null;
        }
        HirExpr arg = ConstFoldPass.unwrapTransparent(outer.args().get(0));
        if (arg instanceof HirExpr.DirectBabbleCall inner
                && inner.targetFunctionId() == outer.targetFunctionId()
                && inner.args().size() == 1
                && inner.meta().wideningRank() < 0
                && inner.meta().checkedType() == null) {
            return inner;
        }
        return null;
    }

    private static HirExpr simplifyIdempotentJava(HirExpr.JavaMethodCall outer, HirModule module) {
        if (outer.meta().wideningRank() >= 0) {
            return null;
        }
        if (!HirPurity.isIdempotentCall(outer, module)) {
            return null;
        }
        boolean isWhitelistedStr = HirPurity.isWhitelistedPureString(outer.target().method());
        if (outer.meta().checkedType() != null && !(isWhitelistedStr && HirPurity.isStringCheckedType(outer.meta().checkedType()))) {
            return null;
        }
        if (outer.target().isStatic() && outer.pureStaticReceiver() && outer.args().size() == 1) {
            HirExpr arg = ConstFoldPass.unwrapTransparent(outer.args().get(0));
            if (arg instanceof HirExpr.JavaMethodCall inner
                    && inner.target().isStatic()
                    && inner.target().method().equals(outer.target().method())
                    && inner.args().size() == 1
                    && inner.meta().wideningRank() < 0
                    && inner.meta().checkedType() == null) {
                return inner;
            }
        }
        if (!outer.target().isStatic() && outer.args().isEmpty()) {
            HirExpr recv = ConstFoldPass.unwrapTransparent(outer.receiver());
            if (recv instanceof HirExpr.JavaMethodCall inner
                    && !inner.target().isStatic()
                    && inner.target().method().equals(outer.target().method())
                    && inner.args().isEmpty()
                    && inner.meta().wideningRank() < 0
                    && (inner.meta().checkedType() == null
                    || (isWhitelistedStr && HirPurity.isStringCheckedType(inner.meta().checkedType())))) {
                return inner;
            }
        }
        return null;
    }

    private static Long asIntegral(HirExpr expr) {
        if (expr instanceof HirExpr.Const c && c.value() instanceof Number n) {
            if (c.meta().kind() == ScopeAnalysis.PrimitiveKind.INT) {
                return (long) n.intValue();
            }
            if (c.meta().kind() == ScopeAnalysis.PrimitiveKind.LONG) {
                return n.longValue();
            }
        }
        return null;
    }

    private static HirExpr.Const makeIntegralConst(ScopeAnalysis.PrimitiveKind kind, long value, ExprMeta meta) {
        Object boxed;
        if (kind == ScopeAnalysis.PrimitiveKind.LONG) {
            boxed = Long.valueOf(value);
        } else {
            boxed = Integer.valueOf((int) value);
        }
        return new HirExpr.Const(boxed, meta.withKind(kind).withWideningRank(-1));
    }
}
