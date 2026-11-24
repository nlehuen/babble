package org.babblelang.ir.egraph;

import org.babblelang.analysis.PrimitiveKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared declarative algebraic rewrite rules for {@link EGraph} ({@code BIP027-D1}).
 *
 * <p>Strictly enforces IEEE-754 floating-point arithmetic semantics: never applies
 * {@code x * 0.0 -> 0.0}, {@code x + 0.0 -> x}, {@code x - x -> 0.0}, or floating-point
 * reassociation. Reflexive equality ({@code a == a -> true}, {@code a != a -> false}) applies to
 * all 8 Babble primitives (including {@code float}/{@code double}, since Babble {@code ==}/{@code !=}
 * uses {@code Float.compare}/{@code Double.compare}) and guaranteed {@code string} expressions,
 * never arbitrary {@code any}/{@code Object} references.
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

    public static final String SYM_MIN = "$min";
    public static final String SYM_MAX = "$max";
    public static final String SYM_ABS = "$abs";

    private static final List<EGraph.RewriteRule> VERIFIER_RULES;

    static {
        List<EGraph.RewriteRule> all = new ArrayList<>(OPTIMIZER_RULES);
        all.add(AlgebraicRules::floatingCommutativityRules);
        all.add(AlgebraicRules::integralDistributivityAndNegRules);
        all.add(AlgebraicRules::booleanLogicAndEqRules);
        all.add(AlgebraicRules::iteAndOrderRules);
        all.add(AlgebraicRules::callInvolutionRule);
        VERIFIER_RULES = List.copyOf(all);
    }

    /** Sound simplification rules for the HIR optimizer ({@code HirEGraphSimplifyPass}). */
    public static List<EGraph.RewriteRule> optimizerRules() {
        return OPTIMIZER_RULES;
    }

    /** Extended equivalence rules for algebraic law verification ({@code EffectChecker} in BIP027-I6). */
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
                } else if (isComparisonOp(inner.op())) {
                    out.addAndUnion(id, ENode.binary(negateComparison(inner.op()), PrimitiveKind.BOOL,
                            inner.child(0), inner.child(1)));
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

    // ---------------------------------------------------------------- extended verifier rules (BIP027-I6)

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
        if (n.op() == ENode.Op.MUL) {
            int a = n.child(0);
            int b = n.child(1);
            for (ENode left : g.nodesIn(a)) {
                if (left.op() == ENode.Op.NEG && left.kind() == n.kind()) {
                    for (ENode right : g.nodesIn(b)) {
                        if (right.op() == ENode.Op.NEG && right.kind() == n.kind()) {
                            out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), right.child(0)));
                        }
                    }
                    int negB = g.add(ENode.unary(ENode.Op.NEG, n.kind(), b));
                    out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), negB));
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
                            out.addAndUnion(id, ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), right.child(0)));
                        }
                    }
                    int innerMul = g.add(ENode.binary(ENode.Op.MUL, n.kind(), left.child(0), b));
                    out.addAndUnion(id, ENode.unary(ENode.Op.NEG, n.kind(), innerMul));
                }
            }
        }
    }

    private static void booleanLogicAndEqRules(EGraph g, int id, ENode n, EGraph.MatchRecorder out) {
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
                    out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, left.child(0), yz));
                }
            }
            for (ENode right : g.nodesIn(b)) {
                if (right.op() == n.op()) {
                    int xy = g.add(ENode.binary(n.op(), PrimitiveKind.BOOL, a, right.child(0)));
                    out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, xy, right.child(1)));
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
        if ((n.op() == ENode.Op.EQ || n.op() == ENode.Op.NEQ) && g.find(n.child(0)) != g.find(n.child(1))) {
            out.addAndUnion(id, ENode.binary(n.op(), PrimitiveKind.BOOL, n.child(1), n.child(0)));
        }
        if (n.op() == ENode.Op.LT || n.op() == ENode.Op.LTE || n.op() == ENode.Op.GT || n.op() == ENode.Op.GTE) {
            ENode.Op flipped = switch (n.op()) {
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
        return op == ENode.Op.LT || op == ENode.Op.LTE || op == ENode.Op.GT || op == ENode.Op.GTE
                || op == ENode.Op.EQ || op == ENode.Op.NEQ;
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

    private static boolean classHasNegOf(EGraph g, int classId, int targetClassId, PrimitiveKind kind) {
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
                        } else if (n.isIntegral() && hasIntegralValue(g, b, 0L)
                                && g.find(e) == g.find(a) && classHasNegOf(g, t, a, n.kind())) {
                            out.addAndUnion(id, ENode.call(SYM_ABS, n.kind(), a));
                        }
                    } else if (cn.op() == ENode.Op.GT || cn.op() == ENode.Op.GTE) {
                        if (g.find(t) == g.find(a) && g.find(e) == g.find(b)) {
                            out.addAndUnion(id, ENode.call(SYM_MAX, n.kind(), a, b));
                        } else if (g.find(t) == g.find(b) && g.find(e) == g.find(a)) {
                            out.addAndUnion(id, ENode.call(SYM_MIN, n.kind(), a, b));
                        } else if (n.isIntegral() && hasIntegralValue(g, b, 0L)
                                && g.find(t) == g.find(a) && classHasNegOf(g, e, a, n.kind())) {
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
                out.addAndUnion(id, integralConst(n.kind(), isMin ? Math.min(la, lb) : Math.max(la, lb)));
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
                if (right.op() == ENode.Op.CALL && sym.equals(right.symbol()) && right.arity() == 2) {
                    int xy = g.add(ENode.call(sym, n.kind(), a, right.child(0)));
                    out.addAndUnion(id, ENode.call(sym, n.kind(), xy, right.child(1)));
                }
            }
            // Constant-bound lattice distributivity: max(min(x, c1), c2) == min(max(x, c2), max(c1, c2))
            if (lb != null && n.isIntegral()) {
                String dual = isMin ? SYM_MAX : SYM_MIN;
                for (ENode left : g.nodesIn(a)) {
                    if (left.op() == ENode.Op.CALL && dual.equals(left.symbol()) && left.arity() == 2) {
                        ENode lc1 = g.constantOf(left.child(1));
                        Long l1 = lc1 != null ? lc1.asIntegralLong() : null;
                        if (l1 != null) {
                            int xOpB = g.add(ENode.call(sym, n.kind(), left.child(0), b));
                            int c1OpB = g.add(integralConst(n.kind(), isMin ? Math.min(l1, lb) : Math.max(l1, lb)));
                            out.addAndUnion(id, ENode.call(dual, n.kind(), xOpB, c1OpB));
                        }
                    }
                }
            }
        } else if (SYM_ABS.equals(sym) && n.arity() == 1) {
            int a = n.child(0);
            for (ENode inner : g.nodesIn(a)) {
                if (inner.op() == ENode.Op.CALL && SYM_ABS.equals(inner.symbol()) && inner.arity() == 1) {
                    out.union(id, a);
                } else if (n.isIntegral() && inner.op() == ENode.Op.NEG && inner.kind() == n.kind()) {
                    out.addAndUnion(id, ENode.call(SYM_ABS, n.kind(), inner.child(0)));
                }
            }
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
