package org.babblelang.analysis;

import java.util.EnumSet;
import java.util.List;
import org.babblelang.effects.Effect;
import org.babblelang.ir.egraph.ENode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the ANTLR-free {@link AlgebraicVerifier} and {@link SizeChangeTermination}
 * cores ({@code BIP027-I6}).
 */
public class AlgebraicAndTerminationTestCase {

    // ---------------------------------------------------------------- AlgebraicVerifier (E-graph +
    // RelSignDomain)

    @Test
    public void testEGraphPureLawsAndIeee754Guards() {
        AlgebraicVerifier.Term a = new AlgebraicVerifier.Param(0, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term b = new AlgebraicVerifier.Param(1, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term sum =
                new AlgebraicVerifier.Binary(ENode.Op.ADD, a, b, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term sqSum =
                new AlgebraicVerifier.Binary(ENode.Op.MUL, sum, sum, Type.INT, PrimitiveKind.INT);

        List<Type.Parameter> twoInts =
                List.of(
                        new Type.Parameter("a", Type.INT, false),
                        new Type.Parameter("b", Type.INT, false));
        EnumSet<Effect> out = EnumSet.noneOf(Effect.class);
        AlgebraicVerifier.verifyPureProperties(sqSum, twoInts, Type.INT, Type::equals, out);
        Assertions.assertTrue(out.contains(Effect.COMMUTATIVE));
        Assertions.assertFalse(out.contains(Effect.ASSOCIATIVE));

        // Integral min(a, b) = ite(a < b, a, b) is commutative, associative, and monotone.
        AlgebraicVerifier.Term lt =
                new AlgebraicVerifier.Binary(ENode.Op.LT, a, b, Type.BOOL, PrimitiveKind.BOOL);
        AlgebraicVerifier.Term minInt =
                new AlgebraicVerifier.Ite(lt, a, b, Type.INT, PrimitiveKind.INT);
        EnumSet<Effect> minOut = EnumSet.noneOf(Effect.class);
        AlgebraicVerifier.verifyPureProperties(minInt, twoInts, Type.INT, Type::equals, minOut);
        Assertions.assertTrue(minOut.contains(Effect.COMMUTATIVE));
        Assertions.assertTrue(minOut.contains(Effect.ASSOCIATIVE));
        Assertions.assertTrue(minOut.contains(Effect.MONOTONE));

        // IEEE-754 double min(a, b) must NOT be verified commutative or associative.
        AlgebraicVerifier.Term da =
                new AlgebraicVerifier.Param(0, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term db =
                new AlgebraicVerifier.Param(1, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term dMin =
                new AlgebraicVerifier.Ite(
                        new AlgebraicVerifier.Binary(
                                ENode.Op.LT, da, db, Type.BOOL, PrimitiveKind.BOOL),
                        da,
                        db,
                        Type.DOUBLE,
                        PrimitiveKind.DOUBLE);
        List<Type.Parameter> twoDoubles =
                List.of(
                        new Type.Parameter("a", Type.DOUBLE, false),
                        new Type.Parameter("b", Type.DOUBLE, false));
        EnumSet<Effect> dOut = EnumSet.noneOf(Effect.class);
        AlgebraicVerifier.verifyPureProperties(dMin, twoDoubles, Type.DOUBLE, Type::equals, dOut);
        Assertions.assertFalse(dOut.contains(Effect.COMMUTATIVE));
        Assertions.assertFalse(dOut.contains(Effect.ASSOCIATIVE));
    }

    @Test
    public void testRelSignDomainMultiParamMonotoneAndTriangularInjective() {
        // Multi-param monotone: (a: double, b: double) -> a + b * 2.0
        AlgebraicVerifier.Term da =
                new AlgebraicVerifier.Param(0, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term db =
                new AlgebraicVerifier.Param(1, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term two =
                new AlgebraicVerifier.Const(2.0, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term addScaled =
                new AlgebraicVerifier.Binary(
                        ENode.Op.ADD,
                        da,
                        new AlgebraicVerifier.Binary(
                                ENode.Op.MUL, db, two, Type.DOUBLE, PrimitiveKind.DOUBLE),
                        Type.DOUBLE,
                        PrimitiveKind.DOUBLE);
        List<Type.Parameter> twoDoubles =
                List.of(
                        new Type.Parameter("a", Type.DOUBLE, false),
                        new Type.Parameter("b", Type.DOUBLE, false));
        Assertions.assertTrue(AlgebraicVerifier.isMonotone(addScaled, twoDoubles, Type.DOUBLE));

        // Anti-monotone in second param: a - b is NOT jointly monotone.
        AlgebraicVerifier.Term subD =
                new AlgebraicVerifier.Binary(
                        ENode.Op.SUB, da, db, Type.DOUBLE, PrimitiveKind.DOUBLE);
        Assertions.assertFalse(AlgebraicVerifier.isMonotone(subD, twoDoubles, Type.DOUBLE));

        // Triangular multi-param injective record constructor: Pair(x, x + y)
        AlgebraicVerifier.Term ix = new AlgebraicVerifier.Param(0, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term iy = new AlgebraicVerifier.Param(1, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term xPlusY =
                new AlgebraicVerifier.Binary(ENode.Op.ADD, ix, iy, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term pair =
                new AlgebraicVerifier.Ctor("Pair", List.of(ix, xPlusY), true, Type.ANY);
        List<Type.Parameter> twoInts =
                List.of(
                        new Type.Parameter("x", Type.INT, false),
                        new Type.Parameter("y", Type.INT, false));
        Assertions.assertTrue(AlgebraicVerifier.isInjective(pair, twoInts, Type.ANY));

        // Non-injective when second param is dropped: Pair(x, x * 2)
        AlgebraicVerifier.Term xTimes2 =
                new AlgebraicVerifier.Binary(
                        ENode.Op.MUL,
                        ix,
                        new AlgebraicVerifier.Const(2, Type.INT, PrimitiveKind.INT),
                        Type.INT,
                        PrimitiveKind.INT);
        AlgebraicVerifier.Term badPair =
                new AlgebraicVerifier.Ctor("Pair", List.of(ix, xTimes2), true, Type.ANY);
        Assertions.assertFalse(AlgebraicVerifier.isInjective(badPair, twoInts, Type.ANY));
    }

    @Test
    public void testMutFieldTransitionViaEGraph() {
        // `this.v = if d > this.v then (d) else (this.v)` -> mut idempotent AND mut commutative!
        AlgebraicVerifier.Term d = new AlgebraicVerifier.Param(0, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term s = new AlgebraicVerifier.Param(1, Type.INT, PrimitiveKind.INT);
        AlgebraicVerifier.Term maxDS =
                new AlgebraicVerifier.Ite(
                        new AlgebraicVerifier.Binary(
                                ENode.Op.GT, d, s, Type.BOOL, PrimitiveKind.BOOL),
                        d,
                        s,
                        Type.INT,
                        PrimitiveKind.INT);
        AlgebraicVerifier.FieldTransition ft =
                new AlgebraicVerifier.FieldTransition(
                        1, -1, 1, List.of(PrimitiveKind.INT), PrimitiveKind.INT, maxDS);
        EnumSet<Effect> out = EnumSet.noneOf(Effect.class);
        AlgebraicVerifier.verifyMutProperties(ft, out);
        Assertions.assertTrue(out.contains(Effect.IDEMPOTENT));
        Assertions.assertTrue(out.contains(Effect.COMMUTATIVE));

        // `this.v += d` on double -> neither idempotent nor commutative (IEEE-754
        // non-associativity).
        AlgebraicVerifier.Term dd =
                new AlgebraicVerifier.Param(0, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.Term ds =
                new AlgebraicVerifier.Param(1, Type.DOUBLE, PrimitiveKind.DOUBLE);
        AlgebraicVerifier.FieldTransition dAdd =
                new AlgebraicVerifier.FieldTransition(
                        1,
                        -1,
                        1,
                        List.of(PrimitiveKind.DOUBLE),
                        PrimitiveKind.DOUBLE,
                        new AlgebraicVerifier.Binary(
                                ENode.Op.ADD, ds, dd, Type.DOUBLE, PrimitiveKind.DOUBLE));
        EnumSet<Effect> dOut = EnumSet.noneOf(Effect.class);
        AlgebraicVerifier.verifyMutProperties(dAdd, dOut);
        Assertions.assertFalse(dOut.contains(Effect.IDEMPOTENT));
        Assertions.assertFalse(dOut.contains(Effect.COMMUTATIVE));
    }

    // ---------------------------------------------------------------- SizeChangeTermination
    // (polynomial SCP)

    @Test
    public void testScpLexicographicAckermannSwapFanOutAndFanIn() {
        // 1. Ackermann: (m-1, 1), (m, n-1), (m-1, ack(...))
        List<SizeChangeTermination.CallGraph> ack =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN))),
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.SAME),
                                        new SizeChangeTermination.Edge(
                                                1, 1, SizeChangeTermination.Relation.DOWN))),
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN))));
        Assertions.assertTrue(SizeChangeTermination.isTerminating(new int[] {2}, ack));

        // 2. Permuting / swapping parameters: p(x, y) -> p(y, x - 1)
        List<SizeChangeTermination.CallGraph> swap =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 1, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                1, 0, SizeChangeTermination.Relation.SAME))));
        Assertions.assertTrue(SizeChangeTermination.isTerminating(new int[] {2}, swap));

        // 3. Fan-out (Criterion 2 max-ranking): f(x, y) -> f(x - 1, x - 1) and f(y - 1, y - 1)
        List<SizeChangeTermination.CallGraph> fanOut =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                0, 1, SizeChangeTermination.Relation.DOWN))),
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                1, 0, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                1, 1, SizeChangeTermination.Relation.DOWN))));
        Assertions.assertTrue(SizeChangeTermination.isTerminating(new int[] {2}, fanOut));

        // 4. Fan-in (Criterion 3 min-ranking): g(x, y) -> g(min-of-x-y - 1, ?) modeled as (0->0
        // DOWN, 1->0 DOWN)
        List<SizeChangeTermination.CallGraph> fanIn =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                1, 0, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                0, 1, SizeChangeTermination.Relation.DOWN),
                                        new SizeChangeTermination.Edge(
                                                1, 1, SizeChangeTermination.Relation.DOWN))));
        Assertions.assertTrue(SizeChangeTermination.isTerminating(new int[] {2}, fanIn));

        // 5. Mutual recursion: even(n) -> odd(n - 1), odd(n) -> even(n - 1)
        List<SizeChangeTermination.CallGraph> mutual =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                1,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN))),
                        SizeChangeTermination.CallGraph.of(
                                1,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.DOWN))));
        Assertions.assertTrue(SizeChangeTermination.isTerminating(new int[] {1, 1}, mutual));
    }

    @Test
    public void testScpRejectsNonTerminatingCycles() {
        // 1. Identity self-loop: f(n) -> f(n)
        List<SizeChangeTermination.CallGraph> idLoop =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 0, SizeChangeTermination.Relation.SAME))));
        Assertions.assertFalse(SizeChangeTermination.isTerminating(new int[] {1}, idLoop));

        // 2. Non-decreasing swap: p(x, y) -> p(y, x)
        List<SizeChangeTermination.CallGraph> swapLoop =
                List.of(
                        SizeChangeTermination.CallGraph.of(
                                0,
                                0,
                                List.of(
                                        new SizeChangeTermination.Edge(
                                                0, 1, SizeChangeTermination.Relation.SAME),
                                        new SizeChangeTermination.Edge(
                                                1, 0, SizeChangeTermination.Relation.SAME))));
        Assertions.assertFalse(SizeChangeTermination.isTerminating(new int[] {2}, swapLoop));

        // 3. Unguarded call (empty edge graph)
        List<SizeChangeTermination.CallGraph> empty =
                List.of(SizeChangeTermination.CallGraph.of(0, 0, List.of()));
        Assertions.assertFalse(SizeChangeTermination.isTerminating(new int[] {1}, empty));
    }
}
