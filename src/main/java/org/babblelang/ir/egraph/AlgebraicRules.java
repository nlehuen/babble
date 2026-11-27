package org.babblelang.ir.egraph;

import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Shared declarative algebraic rewrite rules for {@link EGraph} ({@code BIP027-D1}).
 *
 * <p>Strictly enforces IEEE-754 floating-point arithmetic semantics: never applies {@code x * 0.0
 * -> 0.0}, {@code x + 0.0 -> x}, {@code x - x -> 0.0}, or floating-point reassociation. Reflexive
 * equality ({@code a == a -> true}, {@code a != a -> false}) applies to all 8 Babble primitives
 * (including {@code float}/{@code double}, since Babble {@code ==}/{@code !=} uses {@code
 * Float.compare}/{@code Double.compare}) and guaranteed {@code string} expressions, never arbitrary
 * {@code any}/{@code Object} references.
 */
public final class AlgebraicRules {
    private AlgebraicRules() {}

    private static final List<EGraph.RewriteRule> OPTIMIZER_RULES =
            List.of(
                    AlgebraicRules::integralAddRules,
                    AlgebraicRules::integralSubRules,
                    AlgebraicRules::integralMulRules,
                    AlgebraicRules::integralModRules,
                    AlgebraicRules::booleanRules,
                    AlgebraicRules::stringAssocRules,
                    AlgebraicRules::callAlgebraicRules,
                    AlgebraicRules::injectiveAndMonotoneRules);

    public static final String SYM_MIN = "$min";
    public static final String SYM_MAX = "$max";
    public static final String SYM_ABS = "$abs";

    private static final List<EGraph.RewriteRule> VERIFIER_RULES;

    static {
        List<EGraph.RewriteRule> all = new ArrayList<>(OPTIMIZER_RULES);
        all.add(AlgebraicRules::floatingCommutativityRules);
        all.add(AlgebraicRules::integralDistributivityAndNegRules);
        all.add(AlgebraicRules::integralDivisionIdentityRule);
        all.add(AlgebraicRules::booleanLogicAndEqRules);
        all.add(AlgebraicRules::iteAndOrderRules);
        all.add(AlgebraicRules::callInvolutionRule);
        VERIFIER_RULES = List.copyOf(all);
    }

    /** Sound simplification rules for the HIR optimizer ({@code HirEGraphSimplifyPass}). */
    public static List<EGraph.RewriteRule> optimizerRules() {
        return OPTIMIZER_RULES;
    }

    /**
     * Extended equivalence rules for algebraic law verification ({@code EffectChecker} in
     * BIP027-I6).
     */
    public static List<EGraph.RewriteRule> verifierRules() {
        return VERIFIER_RULES;
    }

    private static ENode integralConst(PrimitiveKind kind, long value) {
        Object boxed =
                kind == PrimitiveKind.LONG
                        ? (Object) Long.valueOf(value)
                        : Integer.valueOf((int) value);
        return ENode.constVal(boxed, kind);
    }

    private static boolean hasIntegralValue(EGraph g, int classId, long expected) {
        ENode c = g.constantOf(classId);
        Long v = c != null ? c.asIntegralLong() : null;
        return v != null && v == expected;
    }

    // ---------------------------------------------------------------- INT / LONG arithmetic

    private static void integralAddRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.ADD || !n.isIntegral()) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        if (hasIntegralValue(g, b, 0L)) {
            out.union(id, a);
        }
        if (hasIntegralValue(g, a, 0L)) {
            out.union(id, b);
        }
        // Commutativity: a + b == b + a
        if (g.find(a) != g.find(b)) {
            out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), b, a));
        }
        // Associativity: (x + y) + z == x + (y + z)
        for (ENode left : g.nodesIn(a)) {
            if (left.op() == ENode.Op.ADD && left.kind() == n.kind()) {
                int yz = g.add(ENode.binary(ENode.Op.ADD, n.kind(), left.child(1), b));
                out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), left.child(0), yz));
            }
        }
        for (ENode right : g.nodesIn(b)) {
            if (right.op() == ENode.Op.ADD && right.kind() == n.kind()) {
                int xy = g.add(ENode.binary(ENode.Op.ADD, n.kind(), a, right.child(0)));
                out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), xy, right.child(1)));
            }
        }
    }

    private static void integralSubRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.NEG && n.isIntegral()) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.NEG && inner.kind() == n.kind()) {
                    out.union(id, inner.child(0));
                }
            }
            return;
        }
        if (n.op() != ENode.Op.SUB || !n.isIntegral()) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        if (hasIntegralValue(g, b, 0L)) {
            out.union(id, a);
        }
        if (hasIntegralValue(g, a, 0L)) {
            out.addAndUnion(id, ENode.unary(ENode.Op.NEG, n.kind(), b));
        }
        if (g.find(a) == g.find(b)) {
            out.addAndUnion(id, integralConst(n.kind(), 0L));
        }
    }

    private static void integralMulRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.MUL || !n.isIntegral()) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        if (hasIntegralValue(g, b, 1L)) {
            out.union(id, a);
        }
        if (hasIntegralValue(g, a, 1L)) {
            out.union(id, b);
        }
        if (hasIntegralValue(g, b, 0L) || hasIntegralValue(g, a, 0L)) {
            out.addAndUnion(id, integralConst(n.kind(), 0L));
        }
        // Commutativity: a * b == b * a
        if (g.find(a) != g.find(b)) {
            out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), b, a));
        }
        // Associativity: (x * y) * z == x * (y * z)
        for (ENode left : g.nodesIn(a)) {
            if (left.op() == ENode.Op.MUL && left.kind() == n.kind()) {
                int yz = g.add(ENode.binary(ENode.Op.MUL, n.kind(), left.child(1), b));
                out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), yz));
            }
        }
        for (ENode right : g.nodesIn(b)) {
            if (right.op() == ENode.Op.MUL && right.kind() == n.kind()) {
                int xy = g.add(ENode.binary(ENode.Op.MUL, n.kind(), a, right.child(0)));
                out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), xy, right.child(1)));
            }
        }
    }

    private static boolean isNonZeroIntegralConst(EGraph g, int classId) {
        ENode c = g.constantOf(classId);
        Long v = c != null ? c.asIntegralLong() : null;
        return v != null && v != 0L;
    }

    /**
     * Soundness guard for {@code (x * b) // b -> x}: returns {@code true} only when {@code x * b}
     * cannot overflow {@code resultKind} ({@code INT} or {@code LONG}).
     */
    private static boolean mulCannotOverflow(
            EGraph g, PrimitiveKind resultKind, int xClass, int bClass) {
        ENode cb = g.constantOf(bClass);
        Long bv = cb != null ? cb.asIntegralLong() : null;
        if (bv == null || bv == 0L) {
            return false;
        }
        if (bv == 1L) {
            return true;
        }
        ENode cx = g.constantOf(xClass);
        Long xv = cx != null ? cx.asIntegralLong() : null;
        if (xv != null) {
            if (resultKind == PrimitiveKind.INT) {
                long prod = (long) xv.intValue() * (long) bv.intValue();
                return prod >= Integer.MIN_VALUE && prod <= Integer.MAX_VALUE;
            }
            if (resultKind == PrimitiveKind.LONG) {
                try {
                    Math.multiplyExact(xv.longValue(), bv.longValue());
                    return true;
                } catch (ArithmeticException overflow) {
                    return false;
                }
            }
            return false;
        }
        // Narrower-origin factors: byte/short in int (|x * b| <= 32768^2 == 2^30 < 2^31),
        // or byte/short/int in long (|x * b| <= (2^31)^2 == 2^62 < 2^63).
        if (resultKind == PrimitiveKind.INT) {
            if (bv < Short.MIN_VALUE || bv > Short.MAX_VALUE) {
                return false;
            }
            for (ENode xn : g.nodesIn(xClass)) {
                if (xn.kind() == PrimitiveKind.BYTE || xn.kind() == PrimitiveKind.SHORT) {
                    return true;
                }
            }
            return false;
        }
        if (resultKind == PrimitiveKind.LONG) {
            if (bv < Integer.MIN_VALUE || bv > Integer.MAX_VALUE) {
                return false;
            }
            for (ENode xn : g.nodesIn(xClass)) {
                if (xn.kind() == PrimitiveKind.BYTE
                        || xn.kind() == PrimitiveKind.SHORT
                        || xn.kind() == PrimitiveKind.INT) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static void integralModRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!n.isIntegral()) {
            return;
        }
        if (n.op() == ENode.Op.MOD) {
            int a = n.child(0);
            int b = n.child(1);
            // Unit divisor: a mod 1 == 0, a mod -1 == 0 (never throws, no overflow)
            if (hasIntegralValue(g, b, 1L) || hasIntegralValue(g, b, -1L)) {
                out.addAndUnion(id, integralConst(n.kind(), 0L));
            }
            // Repeated-divisor idempotence: (x mod b) mod b == x mod b
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.MOD
                        && left.kind() == n.kind()
                        && g.find(left.child(1)) == g.find(b)) {
                    out.union(id, a);
                }
            }
            // Non-zero-guarded zero dividend and self mod: 0 mod b == 0, b mod b == 0
            if (isNonZeroIntegralConst(g, b)) {
                if (hasIntegralValue(g, a, 0L) || g.find(a) == g.find(b)) {
                    out.addAndUnion(id, integralConst(n.kind(), 0L));
                }
            }
            return;
        }
        if (n.op() == ENode.Op.FLOOR_DIV) {
            int a = n.child(0);
            int b = n.child(1);
            // Unit divisor: a // 1 == a, a // -1 == -a (wrapping MIN_VALUE // -1 == MIN_VALUE ==
            // -MIN_VALUE)
            if (hasIntegralValue(g, b, 1L)) {
                out.union(id, a);
            } else if (hasIntegralValue(g, b, -1L)) {
                out.addAndUnion(id, ENode.unary(ENode.Op.NEG, n.kind(), a));
            }
            // Non-zero-guarded zero dividend, self division, and overflow-guarded factor
            // cancellation
            if (isNonZeroIntegralConst(g, b)) {
                if (hasIntegralValue(g, a, 0L)) {
                    out.addAndUnion(id, integralConst(n.kind(), 0L));
                } else if (g.find(a) == g.find(b)) {
                    out.addAndUnion(id, integralConst(n.kind(), 1L));
                }
                int fb = g.find(b);
                for (ENode left : g.nodesIn(a)) {
                    if (left.op() == ENode.Op.MUL && left.kind() == n.kind()) {
                        if (g.find(left.child(1)) == fb
                                && mulCannotOverflow(g, n.kind(), left.child(0), b)) {
                            out.union(id, left.child(0));
                        } else if (g.find(left.child(0)) == fb
                                && mulCannotOverflow(g, n.kind(), left.child(1), b)) {
                            out.union(id, left.child(1));
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifier-only division identity: {@code b * (a // b) + (a mod b) == a} for non-zero {@code
     * b}.
     */
    private static void integralDivisionIdentityRule(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.ADD || !n.isIntegral()) {
            return;
        }
        int lhs = n.child(0);
        int rhs = n.child(1);
        for (ENode modNode : g.nodesIn(rhs)) {
            if (modNode.op() != ENode.Op.MOD || modNode.kind() != n.kind()) {
                continue;
            }
            int a = g.find(modNode.child(0));
            int b = g.find(modNode.child(1));
            if (!isNonZeroIntegralConst(g, b)) {
                continue;
            }
            for (ENode mulNode : g.nodesIn(lhs)) {
                if (mulNode.op() != ENode.Op.MUL || mulNode.kind() != n.kind()) {
                    continue;
                }
                int m0 = g.find(mulNode.child(0));
                int m1 = g.find(mulNode.child(1));
                int divClass = m0 == b ? m1 : (m1 == b ? m0 : -1);
                if (divClass < 0) {
                    continue;
                }
                for (ENode divNode : g.nodesIn(divClass)) {
                    if (divNode.op() == ENode.Op.FLOOR_DIV
                            && divNode.kind() == n.kind()
                            && g.find(divNode.child(0)) == a
                            && g.find(divNode.child(1)) == b) {
                        out.union(id, a);
                        return;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- boolean & string rules

    private static void booleanRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.NOT && n.kind() == PrimitiveKind.BOOL) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.NOT && inner.kind() == PrimitiveKind.BOOL) {
                    out.union(id, inner.child(0));
                } else if (isComparisonOp(inner.op())) {
                    out.addAndUnion(
                            id,
                            ENode.binary(
                                    negateComparison(inner.op()),
                                    PrimitiveKind.BOOL,
                                    inner.child(0),
                                    inner.child(1)));
                }
            }
            return;
        }
        if ((n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ) && n.kind() == PrimitiveKind.BOOL) {
            int a = n.child(0);
            int b = n.child(1);
            if (g.find(a) == g.find(b) && g.isReflexiveEq(a)) {
                out.addAndUnion(id, ENode.constVal(n.op() == ENode.Op.EQ, PrimitiveKind.BOOL));
                return;
            }
            if (isBoolClass(g, a) && isBoolClass(g, b)) {
                ENode cb = g.constantOf(b);
                Boolean bv = cb != null ? cb.asBool() : null;
                if (bv != null) {
                    boolean wantSame = (n.op() == ENode.Op.EQ) == bv;
                    if (wantSame) {
                        out.union(id, a);
                    } else {
                        out.addAndUnion(id, ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, a));
                    }
                }
                ENode ca = g.constantOf(a);
                Boolean av = ca != null ? ca.asBool() : null;
                if (av != null) {
                    boolean wantSame = (n.op() == ENode.Op.EQ) == av;
                    if (wantSame) {
                        out.union(id, b);
                    } else {
                        out.addAndUnion(id, ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, b));
                    }
                }
            }
        }
    }

    private static boolean isBoolClass(EGraph g, int classId) {
        for (ENode node : g.nodesIn(classId)) {
            if (node.kind() == PrimitiveKind.BOOL) {
                return true;
            }
        }
        return false;
    }

    private static void stringAssocRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.STR_ADD) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        // String concatenation is associative (never commutative!).
        for (ENode left : g.nodesIn(a)) {
            if (left.op() == ENode.Op.STR_ADD) {
                int yz =
                        g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, left.child(1), b));
                out.addAndUnion(
                        id, ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, left.child(0), yz));
            }
        }
        for (ENode right : g.nodesIn(b)) {
            if (right.op() == ENode.Op.STR_ADD) {
                int xy =
                        g.add(
                                ENode.binary(
                                        ENode.Op.STR_ADD, PrimitiveKind.NONE, a, right.child(0)));
                out.addAndUnion(
                        id, ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, xy, right.child(1)));
            }
        }
    }

    // ---------------------------------------------------------------- generic pure calls

    private static void callAlgebraicRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.CALL) {
            return;
        }
        EGraph.CallProps props = g.callProps(n.symbol());
        if (props.idempotent() && n.arity() == 1) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.CALL
                        && n.symbol().equals(inner.symbol())
                        && inner.arity() == 1) {
                    out.union(id, n.child(0));
                }
            }
        }
        if (props.commutative() && n.arity() == 2) {
            int a = n.child(0);
            int b = n.child(1);
            if (g.find(a) != g.find(b)) {
                out.addAndUnion(id, ENode.call(n.symbol(), n.kind(), b, a));
            }
        }
        if (props.associative() && n.arity() == 2) {
            int a = n.child(0);
            int b = n.child(1);
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.CALL
                        && n.symbol().equals(left.symbol())
                        && left.arity() == 2) {
                    int yz = g.add(ENode.call(n.symbol(), n.kind(), left.child(1), b));
                    out.addAndUnion(id, ENode.call(n.symbol(), n.kind(), left.child(0), yz));
                }
            }
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == ENode.Op.CALL
                        && n.symbol().equals(right.symbol())
                        && right.arity() == 2) {
                    int xy = g.add(ENode.call(n.symbol(), n.kind(), a, right.child(0)));
                    out.addAndUnion(id, ENode.call(n.symbol(), n.kind(), xy, right.child(1)));
                }
            }
        }
    }

    /**
     * Sound unary {@code injective} and {@code monotone + injective} comparison cancellation
     * ({@code BIP042-P1}):
     *
     * <ul>
     *   <li>{@code f(a) == f(b) -> a == b} and {@code f(a) != f(b) -> a != b} when {@code f} is
     *       {@code injective} and {@code a}, {@code b} share a primitive or guaranteed-{@code
     *       string} type.
     *   <li>{@code f(a) <op> f(b) -> a <op> b} for {@code <, <=, >, >=} when {@code f} is both
     *       {@code monotone} and {@code injective} and {@code a}, {@code b} share an ordered
     *       numeric or {@code char} primitive type.
     * </ul>
     */
    private static void injectiveAndMonotoneRules(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!isComparisonOp(n.op()) || n.kind() != PrimitiveKind.BOOL) {
            return;
        }
        boolean isEq = n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ;
        for (ENode left : g.nodesIn(n.child(0))) {
            if (left.op() != ENode.Op.CALL || left.arity() != 1) {
                continue;
            }
            EGraph.CallProps props = g.callProps(left.symbol());
            if (!props.injective() || (!isEq && !props.monotone())) {
                continue;
            }
            int a = left.child(0);
            PrimitiveKind ka = inferClassKind(g, a);
            if (ka == null) {
                continue;
            }
            for (ENode right : g.nodesIn(n.child(1))) {
                if (right.op() != ENode.Op.CALL
                        || right.arity() != 1
                        || !left.symbol().equals(right.symbol())) {
                    continue;
                }
                int b = right.child(0);
                PrimitiveKind kb = inferClassKind(g, b);
                if (kb != ka) {
                    continue;
                }
                boolean typeOk =
                        isEq
                                ? (ka.isPrimitive() || (g.isReflexiveEq(a) && g.isReflexiveEq(b)))
                                : (ka.isNumeric() || ka == PrimitiveKind.CHAR);
                if (typeOk) {
                    out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, a, b));
                }
            }
        }
    }

    private static PrimitiveKind inferClassKind(EGraph g, int classId) {
        PrimitiveKind found = null;
        for (ENode node : g.nodesIn(classId)) {
            if (found == null) {
                found = node.kind();
            } else if (found != node.kind()) {
                return null;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- extended verifier rules
    // (BIP027-I6)

    private static void floatingCommutativityRules(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!n.isFloating()) {
            return;
        }
        if ((n.op() == ENode.Op.ADD || n.op() == ENode.Op.MUL)
                && g.find(n.child(0)) != g.find(n.child(1))) {
            out.addAndUnion(id, ENode.binary(n.op(), n.kind(), n.child(1), n.child(0)));
        }
        if (n.op() == ENode.Op.NEG) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.NEG && inner.kind() == n.kind()) {
                    out.union(id, inner.child(0));
                }
            }
        }
        if (n.op() == ENode.Op.MUL) {
            int a = n.child(0);
            int b = n.child(1);
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.NEG && left.kind() == n.kind()) {
                    for (ENode right : g.nodesIn(b)) {
                        if (right.op() == ENode.Op.NEG && right.kind() == n.kind()) {
                            out.addAndUnion(
                                    id,
                                    ENode.binary(
                                            ENode.Op.MUL, n.kind(), left.child(0), right.child(0)));
                        }
                    }
                    int negB = g.add(ENode.unary(ENode.Op.NEG, n.kind(), b));
                    out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), negB));
                }
            }
        }
    }

    private static void integralDistributivityAndNegRules(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!n.isIntegral()) {
            return;
        }
        if (n.op() == ENode.Op.SUB) {
            int negB = g.add(ENode.unary(ENode.Op.NEG, n.kind(), n.child(1)));
            out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), n.child(0), negB));
            int bSubA = g.add(ENode.binary(ENode.Op.SUB, n.kind(), n.child(1), n.child(0)));
            out.addAndUnion(id, ENode.unary(ENode.Op.NEG, n.kind(), bSubA));
        }
        if (n.op() == ENode.Op.NEG) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.ADD && inner.kind() == n.kind()) {
                    int na = g.add(ENode.unary(ENode.Op.NEG, n.kind(), inner.child(0)));
                    int nb = g.add(ENode.unary(ENode.Op.NEG, n.kind(), inner.child(1)));
                    out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), na, nb));
                } else if (inner.op() == ENode.Op.MUL && inner.kind() == n.kind()) {
                    int na = g.add(ENode.unary(ENode.Op.NEG, n.kind(), inner.child(0)));
                    out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), na, inner.child(1)));
                }
            }
        }
        if (n.op() == ENode.Op.MUL) {
            int a = n.child(0);
            int b = n.child(1);
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == ENode.Op.ADD && right.kind() == n.kind()) {
                    int ab = g.add(ENode.binary(ENode.Op.MUL, n.kind(), a, right.child(0)));
                    int ac = g.add(ENode.binary(ENode.Op.MUL, n.kind(), a, right.child(1)));
                    out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), ab, ac));
                }
            }
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.NEG && left.kind() == n.kind()) {
                    for (ENode right : g.nodesIn(b)) {
                        if (right.op() == ENode.Op.NEG && right.kind() == n.kind()) {
                            out.addAndUnion(
                                    id,
                                    ENode.binary(
                                            ENode.Op.MUL, n.kind(), left.child(0), right.child(0)));
                        }
                    }
                    int innerMul = g.add(ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), b));
                    out.addAndUnion(id, ENode.unary(ENode.Op.NEG, n.kind(), innerMul));
                }
            }
        }
    }

    private static void booleanLogicAndEqRules(
            EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.AND || n.op() == ENode.Op.OR) {
            int a = n.child(0);
            int b = n.child(1);
            boolean isAnd = n.op() == ENode.Op.AND;
            ENode ca = g.constantOf(a);
            Boolean av = ca != null ? ca.asBool() : null;
            if (av != null) {
                if (av == isAnd) {
                    out.union(id, b);
                } else {
                    out.addAndUnion(id, ENode.constVal(!isAnd, PrimitiveKind.BOOL));
                }
            }
            ENode cb = g.constantOf(b);
            Boolean bv = cb != null ? cb.asBool() : null;
            if (bv != null) {
                if (bv == isAnd) {
                    out.union(id, a);
                } else {
                    out.addAndUnion(id, ENode.constVal(!isAnd, PrimitiveKind.BOOL));
                }
            }
            if (g.find(a) == g.find(b)) {
                out.union(id, a);
            } else {
                out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, b, a));
            }
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == n.op()) {
                    int yz = g.add(ENode.binary(n.op(), PrimitiveKind.BOOL, left.child(1), b));
                    out.addAndUnion(
                            id, ENode.binary(n.op(), PrimitiveKind.BOOL, left.child(0), yz));
                }
            }
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == n.op()) {
                    int xy = g.add(ENode.binary(n.op(), PrimitiveKind.BOOL, a, right.child(0)));
                    out.addAndUnion(
                            id, ENode.binary(n.op(), PrimitiveKind.BOOL, xy, right.child(1)));
                }
            }
        }
        if (n.op() == ENode.Op.NOT && n.kind() == PrimitiveKind.BOOL) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.AND || inner.op() == ENode.Op.OR) {
                    ENode.Op dual = inner.op() == ENode.Op.AND ? ENode.Op.OR : ENode.Op.AND;
                    int na = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, inner.child(0)));
                    int nb = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, inner.child(1)));
                    out.addAndUnion(id, ENode.binary(dual, PrimitiveKind.BOOL, na, nb));
                }
            }
        }
        if ((n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ)
                && g.find(n.child(0)) != g.find(n.child(1))) {
            out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, n.child(1), n.child(0)));
        }
        if (n.op() == ENode.Op.LT
                || n.op() == ENode.Op.LTE
                || n.op() == ENode.Op.GT
                || n.op() == ENode.Op.GTE) {
            ENode.Op flipped =
                    switch (n.op()) {
                        case LT -> ENode.Op.GT;
                        case LTE -> ENode.Op.GTE;
                        case GT -> ENode.Op.LT;
                        case GTE -> ENode.Op.LTE;
                        default -> throw new IllegalStateException();
                    };
            out.addAndUnion(id, ENode.binary(flipped, PrimitiveKind.BOOL, n.child(1), n.child(0)));
        }
    }

    private static boolean isComparisonOp(ENode.Op op) {
        return op == ENode.Op.LT
                || op == ENode.Op.LTE
                || op == ENode.Op.GT
                || op == ENode.Op.GTE
                || op == ENode.Op.EQ
                || op == ENode.Op.NEQ;
    }

    private static ENode.Op negateComparison(ENode.Op op) {
        return switch (op) {
            case LT -> ENode.Op.GTE;
            case LTE -> ENode.Op.GT;
            case GT -> ENode.Op.LTE;
            case GTE -> ENode.Op.LT;
            case EQ -> ENode.Op.NEQ;
            case NEQ -> ENode.Op.EQ;
            default -> throw new IllegalStateException();
        };
    }

    private static boolean classHasNegOf(
            EGraph g, int classId, int targetClassId, PrimitiveKind kind) {
        int t = g.find(targetClassId);
        for (ENode node : g.nodesIn(classId)) {
            if (node.op() == ENode.Op.NEG && node.kind() == kind && g.find(node.child(0)) == t) {
                return true;
            }
        }
        return false;
    }

    private static void iteAndOrderRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.ITE) {
            int c = n.child(0);
            int t = n.child(1);
            int e = n.child(2);
            ENode cc = g.constantOf(c);
            Boolean cv = cc != null ? cc.asBool() : null;
            if (cv != null) {
                out.union(id, cv ? t : e);
                return;
            }
            if (g.find(t) == g.find(e)) {
                out.union(id, t);
                return;
            }
            int fc = g.find(c);
            for (ENode cn : g.nodesIn(c)) {
                if (cn.op() == ENode.Op.NOT) {
                    out.addAndUnion(id, ENode.ite(n.kind(), cn.child(0), e, t));
                }
                if (n.isExactOrdered() && cn.arity() == 2) {
                    int a = cn.child(0);
                    int b = cn.child(1);
                    if (cn.op() == ENode.Op.LT || cn.op() == ENode.Op.LTE) {
                        if (g.find(t) == g.find(a) && g.find(e) == g.find(b)) {
                            out.addAndUnion(id, ENode.call(SYM_MIN, n.kind(), a, b));
                        } else if (g.find(t) == g.find(b) && g.find(e) == g.find(a)) {
                            out.addAndUnion(id, ENode.call(SYM_MAX, n.kind(), a, b));
                        } else if (n.isIntegral()
                                && hasIntegralValue(g, b, 0L)
                                && g.find(e) == g.find(a)
                                && classHasNegOf(g, t, a, n.kind())) {
                            out.addAndUnion(id, ENode.call(SYM_ABS, n.kind(), a));
                        }
                    } else if (cn.op() == ENode.Op.GT || cn.op() == ENode.Op.GTE) {
                        if (g.find(t) == g.find(a) && g.find(e) == g.find(b)) {
                            out.addAndUnion(id, ENode.call(SYM_MAX, n.kind(), a, b));
                        } else if (g.find(t) == g.find(b) && g.find(e) == g.find(a)) {
                            out.addAndUnion(id, ENode.call(SYM_MIN, n.kind(), a, b));
                        } else if (n.isIntegral()
                                && hasIntegralValue(g, b, 0L)
                                && g.find(t) == g.find(a)
                                && classHasNegOf(g, e, a, n.kind())) {
                            out.addAndUnion(id, ENode.call(SYM_ABS, n.kind(), a));
                        }
                    }
                }
            }
            for (ENode tn : g.nodesIn(t)) {
                if (tn.op() == ENode.Op.ITE && g.find(tn.child(0)) == fc) {
                    out.addAndUnion(id, ENode.ite(n.kind(), c, tn.child(1), e));
                }
            }
            for (ENode en : g.nodesIn(e)) {
                if (en.op() == ENode.Op.ITE && g.find(en.child(0)) == fc) {
                    out.addAndUnion(id, ENode.ite(n.kind(), c, t, en.child(2)));
                }
            }
            return;
        }
        if (n.op() != ENode.Op.CALL) {
            return;
        }
        String sym = n.symbol();
        if ((SYM_MIN.equals(sym) || SYM_MAX.equals(sym)) && n.arity() == 2) {
            int a = n.child(0);
            int b = n.child(1);
            boolean isMin = SYM_MIN.equals(sym);
            if (g.find(a) == g.find(b)) {
                out.union(id, a);
                return;
            }
            ENode ca = g.constantOf(a);
            ENode cb = g.constantOf(b);
            Long la = ca != null ? ca.asIntegralLong() : null;
            Long lb = cb != null ? cb.asIntegralLong() : null;
            if (la != null && lb != null && n.isIntegral()) {
                out.addAndUnion(
                        id, integralConst(n.kind(), isMin ? Math.min(la, lb) : Math.max(la, lb)));
                return;
            }
            out.addAndUnion(id, ENode.call(sym, n.kind(), b, a));
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.CALL && sym.equals(left.symbol()) && left.arity() == 2) {
                    int yz = g.add(ENode.call(sym, n.kind(), left.child(1), b));
                    out.addAndUnion(id, ENode.call(sym, n.kind(), left.child(0), yz));
                }
            }
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == ENode.Op.CALL
                        && sym.equals(right.symbol())
                        && right.arity() == 2) {
                    int xy = g.add(ENode.call(sym, n.kind(), a, right.child(0)));
                    out.addAndUnion(id, ENode.call(sym, n.kind(), xy, right.child(1)));
                }
            }
            // Constant-bound lattice distributivity: max(min(x, c1), c2) == min(max(x, c2), max(c1,
            // c2))
            if (lb != null && n.isIntegral()) {
                String dual = isMin ? SYM_MAX : SYM_MIN;
                for (ENode left : g.nodesIn(a)) {
                    if (left.op() == ENode.Op.CALL
                            && dual.equals(left.symbol())
                            && left.arity() == 2) {
                        ENode lc1 = g.constantOf(left.child(1));
                        Long l1 = lc1 != null ? lc1.asIntegralLong() : null;
                        if (l1 != null) {
                            int xOpB = g.add(ENode.call(sym, n.kind(), left.child(0), b));
                            int c1OpB =
                                    g.add(
                                            integralConst(
                                                    n.kind(),
                                                    isMin ? Math.min(l1, lb) : Math.max(l1, lb)));
                            out.addAndUnion(id, ENode.call(dual, n.kind(), xOpB, c1OpB));
                        }
                    }
                }
            }
        } else if (SYM_ABS.equals(sym) && n.arity() == 1) {
            int a = n.child(0);
            for (ENode inner : g.nodesIn(a)) {
                if (inner.op() == ENode.Op.CALL
                        && SYM_ABS.equals(inner.symbol())
                        && inner.arity() == 1) {
                    out.union(id, a);
                } else if (n.isIntegral()
                        && inner.op() == ENode.Op.NEG
                        && inner.kind() == n.kind()) {
                    out.addAndUnion(id, ENode.call(SYM_ABS, n.kind(), inner.child(0)));
                }
            }
        }
    }

    private static void callInvolutionRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.CALL && n.arity() == 1 && g.callProps(n.symbol()).involution()) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.CALL
                        && n.symbol().equals(inner.symbol())
                        && inner.arity() == 1) {
                    out.union(id, inner.child(0));
                }
            }
        }
    }
}
