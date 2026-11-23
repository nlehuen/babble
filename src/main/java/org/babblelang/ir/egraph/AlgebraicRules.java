package org.babblelang.ir.egraph;

import org.babblelang.analysis.PrimitiveKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared declarative algebraic rewrite rules for {@link EGraph} ({@code BIP027-D1}).
 *
 * <p>Strictly enforces IEEE-754 floating-point semantics: never applies {@code x * 0.0 -> 0.0},
 * {@code x + 0.0 -> x}, {@code x - x -> 0.0}, {@code x == x -> true}, or floating-point
 * reassociation.
 */
public final class AlgebraicRules {
    private AlgebraicRules() {
    }

    private static final List<EGraph.RewriteRule> OPTIMIZER_RULES = List.of(
            AlgebraicRules::integralAddRules,
            AlgebraicRules::integralSubRules,
            AlgebraicRules::integralMulRules,
            AlgebraicRules::booleanRules,
            AlgebraicRules::stringAssocRules,
            AlgebraicRules::callAlgebraicRules
    );

    private static final List<EGraph.RewriteRule> VERIFIER_RULES;

    static {
        List<EGraph.RewriteRule> all = new ArrayList<>(OPTIMIZER_RULES);
        all.add(AlgebraicRules::floatingCommutativityRules);
        all.add(AlgebraicRules::integralDistributivityAndNegRules);
        all.add(AlgebraicRules::booleanLogicAndEqRules);
        all.add(AlgebraicRules::callInvolutionRule);
        VERIFIER_RULES = List.copyOf(all);
    }

    /** Sound simplification rules for the HIR optimizer ({@code HirEGraphSimplifyPass}). */
    public static List<EGraph.RewriteRule> optimizerRules() {
        return OPTIMIZER_RULES;
    }

    /** Extended equivalence rules for algebraic law verification ({@code EffectChecker} in BIP027-P6). */
    public static List<EGraph.RewriteRule> verifierRules() {
        return VERIFIER_RULES;
    }

    private static ENode integralConst(PrimitiveKind kind, long value) {
        Object boxed = kind == PrimitiveKind.LONG ? Long.valueOf(value) : Integer.valueOf((int) value);
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

    // ---------------------------------------------------------------- boolean & string rules

    private static void booleanRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.NOT && n.kind() == PrimitiveKind.BOOL) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.NOT && inner.kind() == PrimitiveKind.BOOL) {
                    out.union(id, inner.child(0));
                }
            }
            return;
        }
        if ((n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ) && n.kind() == PrimitiveKind.BOOL) {
            int a = n.child(0);
            int b = n.child(1);
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

    private static void stringAssocRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() != ENode.Op.STR_ADD) {
            return;
        }
        int a = n.child(0);
        int b = n.child(1);
        // String concatenation is associative (never commutative!).
        for (ENode left : g.nodesIn(a)) {
            if (left.op() == ENode.Op.STR_ADD) {
                int yz = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, left.child(1), b));
                out.addAndUnion(id, ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, left.child(0), yz));
            }
        }
        for (ENode right : g.nodesIn(b)) {
            if (right.op() == ENode.Op.STR_ADD) {
                int xy = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, a, right.child(0)));
                out.addAndUnion(id, ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, xy, right.child(1)));
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
                if (inner.op() == ENode.Op.CALL && n.symbol().equals(inner.symbol()) && inner.arity() == 1) {
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
                if (left.op() == ENode.Op.CALL && n.symbol().equals(left.symbol()) && left.arity() == 2) {
                    int yz = g.add(ENode.call(n.symbol(), n.kind(), left.child(1), b));
                    out.addAndUnion(id, ENode.call(n.symbol(), n.kind(), left.child(0), yz));
                }
            }
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == ENode.Op.CALL && n.symbol().equals(right.symbol()) && right.arity() == 2) {
                    int xy = g.add(ENode.call(n.symbol(), n.kind(), a, right.child(0)));
                    out.addAndUnion(id, ENode.call(n.symbol(), n.kind(), xy, right.child(1)));
                }
            }
        }
    }

    // ---------------------------------------------------------------- extended verifier rules (BIP027-P6)

    private static void floatingCommutativityRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!n.isFloating()) {
            return;
        }
        if ((n.op() == ENode.Op.ADD || n.op() == ENode.Op.MUL) && g.find(n.child(0)) != g.find(n.child(1))) {
            out.addAndUnion(id, ENode.binary(n.op(), n.kind(), n.child(1), n.child(0)));
        }
        if (n.op() == ENode.Op.NEG) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.NEG && inner.kind() == n.kind()) {
                    out.union(id, inner.child(0));
                }
            }
        }
    }

    private static void integralDistributivityAndNegRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (!n.isIntegral()) {
            return;
        }
        if (n.op() == ENode.Op.SUB) {
            int negB = g.add(ENode.unary(ENode.Op.NEG, n.kind(), n.child(1)));
            out.addAndUnion(id, ENode.binary(ENode.Op.ADD, n.kind(), n.child(0), negB));
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
        }
    }

    private static void booleanLogicAndEqRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.AND || n.op() == ENode.Op.OR) {
            int a = n.child(0);
            int b = n.child(1);
            if (g.find(a) == g.find(b)) {
                out.union(id, a);
            } else {
                out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, b, a));
            }
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == n.op()) {
                    int yz = g.add(ENode.binary(n.op(), PrimitiveKind.BOOL, left.child(1), b));
                    out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, left.child(0), yz));
                }
            }
        }
        if ((n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ) && g.find(n.child(0)) != g.find(n.child(1))) {
            out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, n.child(1), n.child(0)));
        }
    }

    private static void callInvolutionRule(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
        if (n.op() == ENode.Op.CALL && n.arity() == 1 && g.callProps(n.symbol()).involution()) {
            for (ENode inner : g.nodesIn(n.child(0))) {
                if (inner.op() == ENode.Op.CALL && n.symbol().equals(inner.symbol()) && inner.arity() == 1) {
                    out.union(id, inner.child(0));
                }
            }
        }
    }
}
