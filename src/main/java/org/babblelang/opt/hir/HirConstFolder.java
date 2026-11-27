package org.babblelang.opt.hir;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.runtime.Truth;

/**
 * Shared deterministic constant folding, zero-divisor throw folding, range deforestation, and
 * scalar constant coercion helpers for HIR optimization passes ({@link HirEGraphSimplifyPass} and
 * future {@code -O2} SCCP/AEGraph passes; {@code BIP039-I0}).
 */
final class HirConstFolder {
    private static final LoweringTargets.JavaConstructorTarget ARITH_EXN_CTOR = arithExnCtor();

    private static final PrimitiveKind[] WIDENING_KINDS = {
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT,
        PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
    };

    private HirConstFolder() {}

    private static LoweringTargets.JavaConstructorTarget arithExnCtor() {
        try {
            return new LoweringTargets.JavaConstructorTarget(
                    ArithmeticException.class.getConstructor(String.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing ArithmeticException(String)", e);
        }
    }

    static boolean constPatternEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (na instanceof Double
                    || na instanceof Float
                    || nb instanceof Double
                    || nb instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
            }
            return na.longValue() == nb.longValue();
        }
        return a.equals(b);
    }

    static Integer compareConstScalars(Object a, Object b) {
        if (a instanceof Character ca && b instanceof Character cb) {
            return Character.compare(ca, cb);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.compareTo(sb);
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (na instanceof Double
                    || na instanceof Float
                    || nb instanceof Double
                    || nb instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        return null;
    }

    static HirExpr tryFoldUnary(HirExpr.Unary u) {
        HirExpr op = HirRewriter.unwrapTransparent(u.operand());
        if (!(op instanceof HirExpr.Const c)) {
            return null;
        }
        if (u.op() == HirExpr.UnaryOp.NOT) {
            return new HirExpr.Const(
                    !Truth.test(c.value()),
                    u.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }
        if (!(c.value() instanceof Number n)) {
            return null;
        }
        PrimitiveKind rk = u.meta().kind();
        Object negated =
                switch (rk) {
                    case INT -> -n.intValue();
                    case LONG -> -n.longValue();
                    case FLOAT -> -n.floatValue();
                    case DOUBLE -> -n.doubleValue();
                    default -> null;
                };
        return negated != null ? applyWidening(negated, rk, u.meta()) : null;
    }

    static HirExpr tryFoldBinary(HirExpr.Binary b, HirModule module) {
        HirExpr l = HirRewriter.unwrapTransparent(b.left());
        HirExpr r = HirRewriter.unwrapTransparent(b.right());
        // Zero-divisor throw folding: a mod 0 / a // 0 -> (eval a if impure; throw
        // ArithmeticException("/ by zero"))
        if ((b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)
                && (b.meta().kind() == PrimitiveKind.INT || b.meta().kind() == PrimitiveKind.LONG)
                && HirPurity.isNumericKind(b.left().meta().kind())
                && HirPurity.isNumericKind(b.right().meta().kind())
                && r instanceof HirExpr.Const rc0
                && rc0.value() instanceof Number rn0
                && rn0.longValue() == 0L) {
            int ln = b.meta().line();
            ExprMeta strMeta =
                    new ExprMeta(ln, PrimitiveKind.NONE, -1, null, b.meta().effects(), true, true);
            HirExpr msg = new HirExpr.Const("/ by zero", strMeta);
            HirExpr ctorCall =
                    new HirExpr.JavaConstructorCall(
                            null,
                            "java.lang.ArithmeticException",
                            true,
                            ARITH_EXN_CTOR,
                            List.of(msg),
                            ExprMeta.of(ln, PrimitiveKind.NONE));
            HirExpr throwExpr =
                    new HirExpr.Throw(
                            ctorCall,
                            true,
                            b.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
            if (HirPurity.isPureNothrowTerminating(b.left(), module)) {
                return throwExpr;
            }
            return new HirExpr.Block(
                    List.of(b.left(), throwExpr),
                    false,
                    new String[0],
                    List.of(),
                    -1,
                    b.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }
        if (!(l instanceof HirExpr.Const lc)
                || !(r instanceof HirExpr.Const rc)
                || b.meta().checkedType() != null) {
            return null;
        }
        if (b.op().isComparison() && HirPurity.canLowerComparison(b)) {
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rk = b.right().meta().kind();
            int cmp;
            if (lk == PrimitiveKind.BOOL
                    && rk == PrimitiveKind.BOOL
                    && lc.value() instanceof Boolean lb
                    && rc.value() instanceof Boolean rb) {
                boolean eq = lb.booleanValue() == rb.booleanValue();
                boolean res = b.op() == HirExpr.BinaryOp.EQ ? eq : !eq;
                return new HirExpr.Const(
                        res, b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            } else if (lk == PrimitiveKind.CHAR
                    && rk == PrimitiveKind.CHAR
                    && lc.value() instanceof Character lch
                    && rc.value() instanceof Character rch) {
                cmp = Character.compare(lch, rch);
            } else if (lc.value() instanceof Number ln && rc.value() instanceof Number rn) {
                PrimitiveKind common = lk.numericRank() >= rk.numericRank() ? lk : rk;
                cmp =
                        switch (common) {
                            case BYTE, SHORT, INT -> Integer.compare(ln.intValue(), rn.intValue());
                            case LONG -> Long.compare(ln.longValue(), rn.longValue());
                            case FLOAT -> Float.compare(ln.floatValue(), rn.floatValue());
                            case DOUBLE -> Double.compare(ln.doubleValue(), rn.doubleValue());
                            default -> throw new IllegalStateException();
                        };
            } else {
                return null;
            }
            boolean res =
                    switch (b.op()) {
                        case LT -> cmp < 0;
                        case LTE -> cmp <= 0;
                        case EQ -> cmp == 0;
                        case NEQ -> cmp != 0;
                        case GTE -> cmp >= 0;
                        case GT -> cmp > 0;
                        default -> throw new IllegalStateException();
                    };
            return new HirExpr.Const(
                    res, b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }

        if (b.op() == HirExpr.BinaryOp.ADD
                && b.left().meta().stringType()
                && b.right().meta().stringType()
                && lc.value() instanceof String ls
                && rc.value() instanceof String rs) {
            if (ls.length() + rs.length() <= EGraph.MAX_FOLDED_STRING_LENGTH) {
                return new HirExpr.Const(
                        ls + rs,
                        new ExprMeta(
                                b.meta().line(),
                                PrimitiveKind.NONE,
                                -1,
                                null,
                                b.meta().effects(),
                                true,
                                true));
            }
            return null;
        }

        PrimitiveKind rk = b.meta().kind();
        if (!rk.isPrimitive()
                || !(lc.value() instanceof Number ln)
                || !(rc.value() instanceof Number rn)
                || !HirPurity.isNumericKind(b.left().meta().kind())
                || !HirPurity.isNumericKind(b.right().meta().kind())) {
            return null;
        }
        Object result =
                switch (rk) {
                    case INT ->
                            switch (b.op()) {
                                case ADD -> ln.intValue() + rn.intValue();
                                case SUB -> ln.intValue() - rn.intValue();
                                case MUL -> ln.intValue() * rn.intValue();
                                case FLOOR_DIV ->
                                        rn.intValue() != 0
                                                ? Math.floorDiv(ln.intValue(), rn.intValue())
                                                : null;
                                case MOD ->
                                        rn.intValue() != 0
                                                ? Math.floorMod(ln.intValue(), rn.intValue())
                                                : null;
                                default -> null;
                            };
                    case LONG ->
                            switch (b.op()) {
                                case ADD -> ln.longValue() + rn.longValue();
                                case SUB -> ln.longValue() - rn.longValue();
                                case MUL -> ln.longValue() * rn.longValue();
                                case FLOOR_DIV ->
                                        rn.longValue() != 0L
                                                ? Math.floorDiv(ln.longValue(), rn.longValue())
                                                : null;
                                case MOD ->
                                        rn.longValue() != 0L
                                                ? Math.floorMod(ln.longValue(), rn.longValue())
                                                : null;
                                default -> null;
                            };
                    case FLOAT ->
                            switch (b.op()) {
                                case ADD -> ln.floatValue() + rn.floatValue();
                                case SUB -> ln.floatValue() - rn.floatValue();
                                case MUL -> ln.floatValue() * rn.floatValue();
                                case DIV -> ln.floatValue() / rn.floatValue();
                                default -> null;
                            };
                    case DOUBLE ->
                            switch (b.op()) {
                                case ADD -> ln.doubleValue() + rn.doubleValue();
                                case SUB -> ln.doubleValue() - rn.doubleValue();
                                case MUL -> ln.doubleValue() * rn.doubleValue();
                                case DIV -> ln.doubleValue() / rn.doubleValue();
                                default -> null;
                            };
                    default -> null;
                };
        return result != null ? applyWidening(result, rk, b.meta()) : null;
    }

    static HirExpr tryFoldLogic(HirExpr.Logic l) {
        HirExpr left = HirRewriter.unwrapTransparent(l.left());
        if (!(left instanceof HirExpr.Const lc)) {
            return null;
        }
        boolean lv = Truth.test(lc.value());
        if (l.op() == HirExpr.LogicOp.AND) {
            if (!lv) {
                return new HirExpr.Const(
                        false, l.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            }
            return (l.right().meta().kind() == PrimitiveKind.BOOL && l.meta().wideningRank() < 0)
                    ? l.right()
                    : null;
        }
        if (lv) {
            return new HirExpr.Const(
                    true, l.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }
        return (l.right().meta().kind() == PrimitiveKind.BOOL && l.meta().wideningRank() < 0)
                ? l.right()
                : null;
    }

    static HirExpr tryFoldJavaCall(HirExpr.JavaMethodCall c, HirModule module) {
        HirExpr rangeFolded = tryFoldRangeCall(c, module);
        if (rangeFolded != null) {
            return rangeFolded;
        }
        Method m = c.target().method();
        if (c.target().isStatic()
                && c.pureStaticReceiver()
                && BuiltinAxioms.current().isFoldable(m)) {
            if (c.meta().checkedType() != null) {
                return null;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != c.args().size()) {
                return null;
            }
            Object[] jArgs = new Object[pts.length];
            for (int i = 0; i < pts.length; i++) {
                HirExpr arg = HirRewriter.unwrapTransparent(c.args().get(i));
                if (!(arg instanceof HirExpr.Const ac) || !(ac.value() instanceof Number n)) {
                    return null;
                }
                if (pts[i] == double.class) jArgs[i] = n.doubleValue();
                else if (pts[i] == float.class) jArgs[i] = n.floatValue();
                else if (pts[i] == int.class) jArgs[i] = n.intValue();
                else if (pts[i] == long.class) jArgs[i] = n.longValue();
                else return null;
            }
            try {
                Object res = m.invoke(null, jArgs);
                PrimitiveKind rk = PrimitiveKind.ofJavaClass(m.getReturnType());
                if (rk != PrimitiveKind.NONE && res != null) {
                    return applyWidening(res, rk, c.meta());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!c.target().isStatic()
                && BuiltinAxioms.current().isFoldable(m)
                && HirPurity.isStringCheckedType(c.meta().checkedType())
                && c.args().isEmpty()) {
            HirExpr recv = HirRewriter.unwrapTransparent(c.receiver());
            if (!(recv instanceof HirExpr.Const rc) || !(rc.value() instanceof String s)) {
                return null;
            }
            try {
                Object res = m.invoke(s);
                if (res instanceof Integer iVal) {
                    return applyWidening(iVal, PrimitiveKind.INT, c.meta());
                }
                if (res instanceof Boolean bVal) {
                    return new HirExpr.Const(
                            bVal, c.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
                }
                if (res instanceof String sVal) {
                    return new HirExpr.Const(
                            sVal,
                            new ExprMeta(
                                    c.meta().line(),
                                    PrimitiveKind.NONE,
                                    -1,
                                    null,
                                    c.meta().effects(),
                                    true,
                                    true));
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Deforests {@code .contains*}, {@code .start*}, {@code .end*}, {@code .inclusive}, and {@code
     * .isEmpty} on inline {@link HirExpr.Range} expressions (including {@code .step(constPos)} and
     * {@code .reversed()}).
     */
    private static HirExpr tryFoldRangeCall(HirExpr.JavaMethodCall c, HirModule module) {
        if (c.target().isStatic()
                || c.meta().checkedType() != null
                || c.meta().wideningRank() >= 0) {
            return null;
        }
        Class<?> owner = c.target().method().getDeclaringClass();
        if (owner != org.babblelang.runtime.Range.class
                && owner != org.babblelang.runtime.IntRange.class
                && owner != org.babblelang.runtime.LongRange.class
                && owner != org.babblelang.runtime.CharRange.class
                && owner != org.babblelang.runtime.DoubleRange.class) {
            return null;
        }
        // Unwrap .reversed() and at most one .step(constPos) to reach the underlying HirExpr.Range.
        long stepVal = 1L;
        boolean sawStep = false;
        HirExpr cur = HirRewriter.unwrapTransparent(c.receiver());
        while (cur instanceof HirExpr.JavaMethodCall inner && !inner.target().isStatic()) {
            String iname = inner.target().method().getName();
            if ("reversed".equals(iname) && inner.args().isEmpty()) {
                cur = HirRewriter.unwrapTransparent(inner.receiver());
                continue;
            }
            if ("step".equals(iname) && inner.args().size() == 1 && !sawStep) {
                HirExpr sArg = HirRewriter.unwrapTransparent(inner.args().get(0));
                if (!(sArg instanceof HirExpr.Const sc)
                        || !(sc.value() instanceof Number sn)
                        || sn.longValue() <= 0L) {
                    return null;
                }
                stepVal = sn.longValue();
                sawStep = true;
                cur = HirRewriter.unwrapTransparent(inner.receiver());
                continue;
            }
            break;
        }
        if (!(cur instanceof HirExpr.Range rng)) {
            return null;
        }
        PrimitiveKind ek = rng.elemKind();
        if (!ek.isPrimitive()) {
            return null;
        }
        HirExpr lo = HirRewriter.unwrapTransparent(rng.start());
        HirExpr hi = HirRewriter.unwrapTransparent(rng.end());
        if (!HirPurity.isPureNothrowTerminating(lo, module)
                || !HirPurity.isPureNothrowTerminating(hi, module)) {
            return null;
        }
        String name = c.target().method().getName();
        int ln = c.meta().line();
        ExprMeta boolMeta = ExprMeta.of(ln, PrimitiveKind.BOOL);
        if (c.args().isEmpty()) {
            if ("inclusive".equals(name)) {
                return new HirExpr.Const(rng.inclusive(), boolMeta);
            }
            if (("startInt".equals(name)
                            || "startLong".equals(name)
                            || "startChar".equals(name)
                            || "startDouble".equals(name))
                    && lo.meta().kind() == c.meta().kind()
                    && lo.meta().wideningRank() < 0) {
                return lo;
            }
            if (("endInt".equals(name)
                            || "endLong".equals(name)
                            || "endChar".equals(name)
                            || "endDouble".equals(name))
                    && hi.meta().kind() == c.meta().kind()
                    && hi.meta().wideningRank() < 0) {
                return hi;
            }
            if ("isEmpty".equals(name)
                    && lo instanceof HirExpr.Const lc
                    && hi instanceof HirExpr.Const hc) {
                boolean empty =
                        switch (ek) {
                            case BYTE, SHORT, INT ->
                                    rng.inclusive()
                                            ? ((Number) lc.value()).intValue()
                                                    > ((Number) hc.value()).intValue()
                                            : ((Number) lc.value()).intValue()
                                                    >= ((Number) hc.value()).intValue();
                            case LONG ->
                                    rng.inclusive()
                                            ? ((Number) lc.value()).longValue()
                                                    > ((Number) hc.value()).longValue()
                                            : ((Number) lc.value()).longValue()
                                                    >= ((Number) hc.value()).longValue();
                            case CHAR ->
                                    rng.inclusive()
                                            ? (Character) lc.value() > (Character) hc.value()
                                            : (Character) lc.value() >= (Character) hc.value();
                            case FLOAT, DOUBLE -> {
                                double a = ((Number) lc.value()).doubleValue();
                                double b = ((Number) hc.value()).doubleValue();
                                yield rng.inclusive() ? !(a <= b) : !(a < b);
                            }
                            default -> false;
                        };
                return new HirExpr.Const(empty, boolMeta);
            }
            return null;
        }
        if (c.args().size() == 1
                && ("containsInt".equals(name)
                        || "containsLong".equals(name)
                        || "containsChar".equals(name)
                        || "containsDouble".equals(name))) {
            HirExpr x = HirRewriter.unwrapTransparent(c.args().get(0));
            if (x.meta().kind() != ek
                    || x.meta().wideningRank() >= 0
                    || lo.meta().kind() != ek
                    || lo.meta().wideningRank() >= 0
                    || hi.meta().kind() != ek
                    || hi.meta().wideningRank() >= 0) {
                return null;
            }
            // Only duplicate x/lo if cheap pure leaf (Const or VarRead) so no expression is
            // duplicated!
            if (!isCheapPureLeaf(x, module)
                    || !isCheapPureLeaf(lo, module)
                    || !isCheapPureLeaf(hi, module)) {
                return null;
            }
            HirExpr loCheck = new HirExpr.Binary(HirExpr.BinaryOp.LTE, lo, x, "", "", boolMeta);
            HirExpr hiCheck =
                    new HirExpr.Binary(
                            rng.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT,
                            x,
                            hi,
                            "",
                            "",
                            boolMeta);
            HirExpr boundsCheck =
                    new HirExpr.Logic(HirExpr.LogicOp.AND, loCheck, hiCheck, boolMeta);
            if (stepVal == 1L) {
                return boundsCheck;
            }
            // Stepped contains deforestation: lo <= x and x < hi and (x - lo) mod step == 0 (when x
            // - lo cannot overflow)
            if (ek == PrimitiveKind.INT
                    && lo instanceof HirExpr.Const lc
                    && hi instanceof HirExpr.Const hc
                    && lc.value() instanceof Number lNum
                    && hc.value() instanceof Number hNum) {
                long span = (long) hNum.intValue() - (long) lNum.intValue();
                if (span >= 0L && span <= Integer.MAX_VALUE) {
                    ExprMeta intMeta = ExprMeta.of(ln, PrimitiveKind.INT);
                    HirExpr diff = new HirExpr.Binary(HirExpr.BinaryOp.SUB, x, lo, "", "", intMeta);
                    HirExpr stepC = new HirExpr.Const((int) stepVal, intMeta);
                    HirExpr rem =
                            new HirExpr.Binary(HirExpr.BinaryOp.MOD, diff, stepC, "", "", intMeta);
                    HirExpr zero = new HirExpr.Const(0, intMeta);
                    HirExpr stepCheck =
                            new HirExpr.Binary(HirExpr.BinaryOp.EQ, rem, zero, "", "", boolMeta);
                    return new HirExpr.Logic(HirExpr.LogicOp.AND, boundsCheck, stepCheck, boolMeta);
                }
            }
            if (ek == PrimitiveKind.LONG
                    && lo instanceof HirExpr.Const lc
                    && hi instanceof HirExpr.Const hc
                    && lc.value() instanceof Number lNum
                    && hc.value() instanceof Number hNum
                    && lNum.longValue() >= 0L
                    && hNum.longValue() >= lNum.longValue()) {
                ExprMeta longMeta = ExprMeta.of(ln, PrimitiveKind.LONG);
                HirExpr diff = new HirExpr.Binary(HirExpr.BinaryOp.SUB, x, lo, "", "", longMeta);
                HirExpr stepC = new HirExpr.Const(stepVal, longMeta);
                HirExpr rem =
                        new HirExpr.Binary(HirExpr.BinaryOp.MOD, diff, stepC, "", "", longMeta);
                HirExpr zero = new HirExpr.Const(0L, longMeta);
                HirExpr stepCheck =
                        new HirExpr.Binary(HirExpr.BinaryOp.EQ, rem, zero, "", "", boolMeta);
                return new HirExpr.Logic(HirExpr.LogicOp.AND, boundsCheck, stepCheck, boolMeta);
            }
        }
        return null;
    }

    private static boolean isCheapPureLeaf(HirExpr expr, HirModule module) {
        if (expr instanceof HirExpr.Const) {
            return true;
        }
        return expr instanceof HirExpr.VarRead v && HirPurity.isPureNothrowTerminating(v, module);
    }

    static boolean samePureScalarLeaf(HirExpr a, HirExpr b, HirModule module) {
        HirExpr ua = HirRewriter.unwrapTransparent(a);
        HirExpr ub = HirRewriter.unwrapTransparent(b);
        if (ua instanceof HirExpr.Const ca && ub instanceof HirExpr.Const cb) {
            return java.util.Objects.equals(ca.value(), cb.value())
                    && ca.meta().kind() == cb.meta().kind();
        }
        return ua instanceof HirExpr.VarRead va
                && ub instanceof HirExpr.VarRead vb
                && va.var().id() == vb.var().id()
                && HirPurity.isPureNothrowTerminating(va, module);
    }

    static Long asIntegral(HirExpr expr) {
        if (expr instanceof HirExpr.Const c && c.value() instanceof Number n) {
            if (c.meta().kind() == PrimitiveKind.INT) return (long) n.intValue();
            if (c.meta().kind() == PrimitiveKind.LONG) return n.longValue();
        }
        return null;
    }

    static HirExpr.Const coerceConstForVar(HirExpr.Const c, HirVar var, int defWideningRank) {
        PrimitiveKind vk = var.primitiveKind();
        Object val = c.value();
        PrimitiveKind ck = c.meta().kind();
        if (defWideningRank >= 0) {
            HirExpr.Const widened =
                    applyWidening(val, ck, c.meta().withWideningRank(defWideningRank));
            if (widened == null) return null;
            val = widened.value();
            ck = widened.meta().kind();
        }
        if (vk.isPrimitive()) {
            return convertConstToKind(val, ck, vk, c.meta().withWideningRank(-1));
        }
        if (val == null || val instanceof String) {
            return new HirExpr.Const(
                    val, c.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }
        return null;
    }

    static HirExpr.Const applyWidening(Object value, PrimitiveKind currentKind, ExprMeta meta) {
        int rank = meta.wideningRank();
        if (rank < 0) {
            return new HirExpr.Const(value, meta.withKind(currentKind).withWideningRank(-1));
        }
        if (!(value instanceof Number n) || rank >= WIDENING_KINDS.length) {
            return null;
        }
        PrimitiveKind target = WIDENING_KINDS[rank];
        return new HirExpr.Const(
                convertNumber(n, target), meta.withKind(target).withWideningRank(-1));
    }

    static HirExpr.Const convertConstToKind(
            Object val, PrimitiveKind from, PrimitiveKind to, ExprMeta meta) {
        if (from == to) {
            return applyWidening(val, to, meta);
        }
        if (to == PrimitiveKind.NONE && (val == null || val instanceof String)) {
            return new HirExpr.Const(val, meta.withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }
        if (val instanceof Number n && HirPurity.isNumericKind(to)) {
            return applyWidening(convertNumber(n, to), to, meta);
        }
        return null;
    }

    private static Number convertNumber(Number n, PrimitiveKind to) {
        return switch (to) {
            case BYTE -> n.byteValue();
            case SHORT -> n.shortValue();
            case INT -> n.intValue();
            case LONG -> n.longValue();
            case FLOAT -> n.floatValue();
            default -> n.doubleValue();
        };
    }

    /** Folds explicit {@link HirExpr.Widen} on constants and lossless widening chains. */
    static HirExpr tryFoldWiden(HirExpr.Widen w) {
        HirExpr op = HirRewriter.unwrapTransparent(w.operand());
        int rank = w.wideningRank();
        if (op instanceof HirExpr.Const c && c.value() instanceof Number) {
            return applyWidening(c.value(), c.meta().kind(), w.meta().withWideningRank(rank));
        }
        PrimitiveKind target = PrimitiveKind.ofNumericRank(rank);
        if (target != PrimitiveKind.NONE
                && op.meta().kind() == target
                && hasExactPrimitiveStack(op)) {
            return op;
        }
        if (op instanceof HirExpr.Widen inner && inner.operand().meta().kind().isPrimitive()) {
            int r1 = inner.wideningRank();
            if (r1 == rank || (r1 <= 3 && rank <= 3 && r1 < rank) || (r1 <= 2 && rank == 5)) {
                return new HirExpr.Widen(
                        inner.operand(), Math.max(r1, rank), w.meta().withWideningRank(-1));
            }
        }
        return null;
    }

    /** Folds explicit {@link HirExpr.Convert} on constants, identity, and box/unbox chains. */
    static HirExpr tryFoldConvert(HirExpr.Convert cv) {
        HirExpr op = HirRewriter.unwrapTransparent(cv.operand());
        PrimitiveKind to = cv.targetKind();
        PrimitiveKind from = op.meta().kind();
        if (from == to && hasExactPrimitiveStack(op)) {
            return op;
        }
        if (op instanceof HirExpr.Const c) {
            if (to.isPrimitive() && c.value() instanceof Number n && HirPurity.isNumericKind(to)) {
                return new HirExpr.Const(
                        convertNumber(n, to), cv.meta().withKind(to).withWideningRank(-1));
            }
            if (to == PrimitiveKind.BOOL && c.value() instanceof Boolean b) {
                return new HirExpr.Const(b, cv.meta().withKind(to).withWideningRank(-1));
            }
            if (to == PrimitiveKind.CHAR && c.value() instanceof Character ch) {
                return new HirExpr.Const(ch, cv.meta().withKind(to).withWideningRank(-1));
            }
        }
        // unbox[T](box(a)) -> a when a already has exact stack kind T
        if (op instanceof HirExpr.Convert inner
                && inner.targetKind() == PrimitiveKind.NONE
                && to.isPrimitive()
                && inner.operand().meta().kind() == to
                && hasExactPrimitiveStack(inner.operand())) {
            return inner.operand();
        }
        // Lossless primitive widening chain: convert[T2](convert[T1](a)) -> convert[T2](a)
        if (op instanceof HirExpr.Convert inner
                && inner.targetKind().isPrimitive()
                && to.isPrimitive()
                && inner.operand().meta().kind().isPrimitive()) {
            int r0 = inner.operand().meta().kind().numericRank();
            int r1 = inner.targetKind().numericRank();
            int r2 = to.numericRank();
            if (r0 >= 0 && r1 >= r0 && r2 >= r1 && (r1 <= 3 && r2 <= 3 || r1 <= 2 && r2 == 5)) {
                if (inner.operand().meta().kind() == to
                        && hasExactPrimitiveStack(inner.operand())) {
                    return inner.operand();
                }
                return new HirExpr.Convert(inner.operand(), to, cv.meta().withWideningRank(-1));
            }
        }
        return null;
    }

    private static boolean hasExactPrimitiveStack(HirExpr expr) {
        return expr instanceof HirExpr.Const
                || expr instanceof HirExpr.VarRead
                || expr instanceof HirExpr.Binary
                || expr instanceof HirExpr.Unary
                || expr instanceof HirExpr.Convert
                || expr instanceof HirExpr.Widen
                || expr instanceof HirExpr.If;
    }

    /** Prunes an {@link HirExpr.If} whose condition is a constant. */
    static HirExpr tryPruneIf(HirExpr.If i) {
        if (!(i.cond() instanceof HirExpr.Const condConst)) {
            return null;
        }
        return adaptIfBranch(
                Truth.test(condConst.value()) ? i.thenBranch() : i.elseBranch(), i.meta());
    }

    /** Adapts a chosen {@code If} branch to {@code ifMeta}, or returns {@code null} if unsafe. */
    static HirExpr adaptIfBranch(HirExpr chosen, ExprMeta ifMeta) {
        if (chosen == null) {
            if (ifMeta.kind() == PrimitiveKind.NONE && ifMeta.wideningRank() < 0) {
                return new HirExpr.Const(null, ifMeta);
            }
            return null;
        }
        HirExpr unwrapped = HirRewriter.unwrapTransparent(chosen);
        if (unwrapped instanceof HirExpr.Const c
                && chosen instanceof HirExpr.Block blk
                && !blk.opensNamespaceScope()
                && blk.declaredVars().isEmpty()
                && blk.innerWideningRank() < 0) {
            HirExpr.Const converted =
                    convertConstToKind(c.value(), c.meta().kind(), ifMeta.kind(), ifMeta);
            if (converted != null) {
                return converted;
            }
        }
        if (chosen.meta().kind() == ifMeta.kind()
                && chosen.meta().wideningRank() < 0
                && ifMeta.wideningRank() < 0) {
            return chosen;
        }
        if (chosen.meta().kind() == ifMeta.kind()
                && ifMeta.wideningRank() >= 0
                && chosen instanceof HirExpr.Block b
                && b.innerWideningRank() < 0
                && b.meta().wideningRank() < 0) {
            return new HirExpr.Block(
                    b.statements(),
                    b.opensNamespaceScope(),
                    b.declaredNames(),
                    b.declaredVars(),
                    -1,
                    b.meta().withWideningRank(ifMeta.wideningRank()));
        }
        return null;
    }

    enum ConstMatch {
        YES,
        NO,
        UNKNOWN
    }

    /** Folds or trims a {@link HirExpr.Match} whose scrutinee is a known constant {@code sc}. */
    static HirExpr tryFoldConstMatch(HirExpr.Const sc, HirExpr.Match m, HirModule module) {
        List<HirExpr.MatchArm> arms = m.arms();
        int firstSurviving = 0;
        for (int i = 0; i < arms.size(); i++) {
            HirExpr.MatchArm arm = arms.get(i);
            ConstMatch res = matchConstPattern(arm.pattern(), sc.value());
            if (res == ConstMatch.NO) {
                if (HirPurity.isPatternPureNothrowTerminating(arm.pattern(), module)) {
                    firstSurviving = i + 1;
                    continue;
                }
                break;
            }
            if (res == ConstMatch.YES && arm.guard() == null) {
                if (arm.boundVars().isEmpty()) {
                    return adaptMatchArmBody(arm.body(), arm.bodyWideningRank(), m.meta());
                }
                if (arm.pattern() instanceof HirPattern.Bind bp
                        && bp.inner() == null
                        && !bp.var().isCaptured()
                        && arm.boundVars().size() == 1
                        && bp.var().primitiveKind() == sc.meta().kind()) {
                    HirExpr.Const boundConst = coerceConstForVar(sc, bp.var(), -1);
                    if (boundConst != null) {
                        HirExpr.Def def =
                                new HirExpr.Def(
                                        bp.var().name(),
                                        new LoweringTargets.DefTarget(
                                                LoweringTargets.DefKind.LEXICAL, -1),
                                        bp.var(),
                                        boundConst,
                                        boundConst.meta());
                        return new HirExpr.Block(
                                List.of(def, arm.body()),
                                false,
                                new String[0],
                                arm.boundVars(),
                                arm.bodyWideningRank(),
                                m.meta());
                    }
                }
                break;
            }
            break;
        }
        if (firstSurviving == arms.size() && m.elseArm() != null) {
            HirExpr.MatchElse ea = m.elseArm();
            if (ea.var() == null) {
                return adaptMatchArmBody(ea.body(), ea.bodyWideningRank(), m.meta());
            }
            if (!ea.var().isCaptured() && ea.var().primitiveKind() == sc.meta().kind()) {
                HirExpr.Const boundConst = coerceConstForVar(sc, ea.var(), -1);
                if (boundConst != null) {
                    HirExpr.Def def =
                            new HirExpr.Def(
                                    ea.var().name(),
                                    new LoweringTargets.DefTarget(
                                            LoweringTargets.DefKind.LEXICAL, -1),
                                    ea.var(),
                                    boundConst,
                                    boundConst.meta());
                    return new HirExpr.Block(
                            List.of(def, ea.body()),
                            false,
                            new String[0],
                            List.of(ea.var()),
                            ea.bodyWideningRank(),
                            m.meta());
                }
            }
        }
        if (firstSurviving > 0) {
            return new HirExpr.Match(
                    m.scrutinee(),
                    m.scrutineeVar(),
                    new ArrayList<>(arms.subList(firstSurviving, arms.size())),
                    m.elseArm(),
                    m.exhaustive(),
                    m.meta());
        }
        return null;
    }

    static HirExpr adaptMatchArmBody(HirExpr body, int bodyWideningRank, ExprMeta matchMeta) {
        HirExpr unwrapped = HirRewriter.unwrapTransparent(body);
        if (unwrapped instanceof HirExpr.Const c && bodyWideningRank < 0) {
            HirExpr.Const converted =
                    convertConstToKind(c.value(), c.meta().kind(), matchMeta.kind(), matchMeta);
            if (converted != null) {
                return converted;
            }
        }
        if (bodyWideningRank < 0
                && body.meta().kind() == matchMeta.kind()
                && body.meta().wideningRank() < 0
                && matchMeta.wideningRank() < 0) {
            return body;
        }
        return new HirExpr.Block(
                List.of(body), false, new String[0], List.of(), bodyWideningRank, matchMeta);
    }

    static ConstMatch matchConstPattern(HirPattern pat, Object scrutVal) {
        return switch (pat) {
            case HirPattern.Wildcard ignored -> ConstMatch.YES;
            case HirPattern.Const pc -> {
                if (pc.isEnum()) {
                    yield ConstMatch.UNKNOWN;
                }
                yield constPatternEquals(scrutVal, pc.value()) ? ConstMatch.YES : ConstMatch.NO;
            }
            case HirPattern.Bind b ->
                    b.inner() == null ? ConstMatch.YES : matchConstPattern(b.inner(), scrutVal);
            case HirPattern.Or o -> {
                boolean anyUnknown = false;
                for (HirPattern alt : o.alternatives()) {
                    ConstMatch r = matchConstPattern(alt, scrutVal);
                    if (r == ConstMatch.YES) {
                        yield ConstMatch.YES;
                    }
                    if (r == ConstMatch.UNKNOWN) {
                        anyUnknown = true;
                    }
                }
                yield anyUnknown ? ConstMatch.UNKNOWN : ConstMatch.NO;
            }
            case HirPattern.Relational rel -> {
                HirExpr rhsU = HirRewriter.unwrapTransparent(rel.rhs());
                if (!(rhsU instanceof HirExpr.Const rc)) {
                    yield ConstMatch.UNKNOWN;
                }
                if (rel.op() == HirExpr.BinaryOp.EQ) {
                    yield constPatternEquals(scrutVal, rc.value()) ? ConstMatch.YES : ConstMatch.NO;
                }
                if (rel.op() == HirExpr.BinaryOp.NEQ) {
                    yield constPatternEquals(scrutVal, rc.value()) ? ConstMatch.NO : ConstMatch.YES;
                }
                if (scrutVal == null || rc.value() == null) {
                    yield ConstMatch.NO;
                }
                Integer cmp = compareConstScalars(scrutVal, rc.value());
                if (cmp == null) {
                    yield ConstMatch.UNKNOWN;
                }
                boolean ok =
                        switch (rel.op()) {
                            case LT -> cmp < 0;
                            case LTE -> cmp <= 0;
                            case GT -> cmp > 0;
                            case GTE -> cmp >= 0;
                            default -> false;
                        };
                yield ok ? ConstMatch.YES : ConstMatch.NO;
            }
            case HirPattern.RangeTest rt -> {
                if (scrutVal == null) {
                    yield ConstMatch.NO;
                }
                HirExpr loU = HirRewriter.unwrapTransparent(rt.lo());
                HirExpr hiU = HirRewriter.unwrapTransparent(rt.hi());
                if (!(loU instanceof HirExpr.Const loC) || !(hiU instanceof HirExpr.Const hiC)) {
                    yield ConstMatch.UNKNOWN;
                }
                long step = 1L;
                if (rt.step() != null) {
                    HirExpr stU = HirRewriter.unwrapTransparent(rt.step());
                    if (!(stU instanceof HirExpr.Const stC)
                            || !(stC.value() instanceof Number sn)
                            || sn.longValue() <= 0L) {
                        yield ConstMatch.UNKNOWN;
                    }
                    step = sn.longValue();
                }
                Integer cLo = compareConstScalars(scrutVal, loC.value());
                Integer cHi = compareConstScalars(scrutVal, hiC.value());
                if (cLo == null || cHi == null) {
                    yield ConstMatch.UNKNOWN;
                }
                boolean inBounds = cLo >= 0 && (rt.inclusive() ? cHi <= 0 : cHi < 0);
                if (!inBounds) {
                    yield ConstMatch.NO;
                }
                if (step == 1L) {
                    yield ConstMatch.YES;
                }
                if (scrutVal instanceof Number vn && loC.value() instanceof Number ln) {
                    yield ((vn.longValue() - ln.longValue()) % step == 0L)
                            ? ConstMatch.YES
                            : ConstMatch.NO;
                }
                if (scrutVal instanceof Character vc && loC.value() instanceof Character lc) {
                    yield (((long) vc - (long) lc) % step == 0L) ? ConstMatch.YES : ConstMatch.NO;
                }
                yield ConstMatch.UNKNOWN;
            }
            default -> ConstMatch.UNKNOWN;
        };
    }
}
