package org.babblelang.tests;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.egraph.PipelineRules;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared zero-dependency pure-expression E-graph ({@link EGraph}) and
 * declarative {@link AlgebraicRules} ({@code BIP027-D1}).
 */
public class EGraphTestCase {

    @Test
    public void testIntegralIdentitiesAndAcConstantFolding() {
        EGraph g = new EGraph();
        int x = g.add(ENode.opaque("x", PrimitiveKind.INT));
        int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
        int one = g.add(ENode.constVal(1, PrimitiveKind.INT));
        int two = g.add(ENode.constVal(2, PrimitiveKind.INT));
        int three = g.add(ENode.constVal(3, PrimitiveKind.INT));

        // ((x + 0) * 1) + (x * 0) + (x - x) == x
        int xPlus0 = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, x, zero));
        int times1 = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, xPlus0, one));
        int times0 = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, x, zero));
        int xSubX = g.add(ENode.binary(ENode.Op.SUB, PrimitiveKind.INT, x, x));
        int sum1 = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, times1, times0));
        int root = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, sum1, xSubX));

        // (1 + x) + (2 + 3) == x + 6
        int onePlusX = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, one, x));
        int twoPlusThree = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, two, three));
        int acSum = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, onePlusX, twoPlusThree));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 128);

        Assertions.assertTrue(g.areEquivalent(root, x));
        EGraph.ExtractedTree extracted = g.extract(root, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.OPAQUE, extracted.node().op());
        Assertions.assertEquals("x", extracted.node().symbol());

        EGraph.ExtractedTree acExtracted = g.extract(acSum, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.ADD, acExtracted.node().op());
        Assertions.assertEquals("x", acExtracted.children().get(0).node().symbol());
        Assertions.assertEquals(6, acExtracted.children().get(1).node().literal());
    }

    @Test
    public void testStrictIeee754FloatingPointSemantics() {
        EGraph g = new EGraph();
        int x = g.add(ENode.opaque("x", PrimitiveKind.DOUBLE));
        int zero = g.add(ENode.constVal(0.0, PrimitiveKind.DOUBLE));
        int one = g.add(ENode.constVal(1.0, PrimitiveKind.DOUBLE));

        int addZero = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.DOUBLE, x, zero));
        int mulZero = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.DOUBLE, x, zero));
        int mulOne = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.DOUBLE, x, one));
        int subSelf = g.add(ENode.binary(ENode.Op.SUB, PrimitiveKind.DOUBLE, x, x));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 128);

        Assertions.assertFalse(g.areEquivalent(addZero, x), "-0.0 + 0.0 is +0.0, not -0.0");
        Assertions.assertFalse(g.areEquivalent(mulZero, zero), "NaN * 0.0 is NaN, not 0.0");
        Assertions.assertFalse(
                g.areEquivalent(mulOne, x),
                "double * 1.0 must not be rewritten by optimizer rules");
        Assertions.assertFalse(g.areEquivalent(subSelf, zero), "Inf - Inf is NaN, not 0.0");
    }

    @Test
    public void testStringAssociativityIsNotCommutative() {
        EGraph g = new EGraph();
        int s = g.add(ENode.opaque("s", PrimitiveKind.NONE));
        int a = g.add(ENode.constVal("a", PrimitiveKind.NONE));
        int b = g.add(ENode.constVal("b", PrimitiveKind.NONE));

        int sPlusA = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, s, a));
        int expr = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, sPlusA, b));
        int swapped = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, b, s));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 128);

        EGraph.ExtractedTree extracted = g.extract(expr, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.STR_ADD, extracted.node().op());
        Assertions.assertEquals("s", extracted.children().get(0).node().symbol());
        Assertions.assertEquals("ab", extracted.children().get(1).node().literal());
        Assertions.assertFalse(g.areEquivalent(sPlusA, swapped));
    }

    @Test
    public void testCallAlgebraicPropertiesAndVerifierRules() {
        EGraph g = new EGraph();
        g.registerCallProps("abs", new EGraph.CallProps(true, false, false, false));
        g.registerCallProps("flip", new EGraph.CallProps(false, false, false, true));
        g.registerCallProps("max", new EGraph.CallProps(false, true, true, false));

        int x = g.add(ENode.opaque("x", PrimitiveKind.INT));
        int y = g.add(ENode.opaque("y", PrimitiveKind.INT));
        int z = g.add(ENode.opaque("z", PrimitiveKind.INT));

        int abs1 = g.add(ENode.call("abs", PrimitiveKind.INT, x));
        int abs2 = g.add(ENode.call("abs", PrimitiveKind.INT, abs1));

        int flip1 = g.add(ENode.call("flip", PrimitiveKind.INT, x));
        int flip2 = g.add(ENode.call("flip", PrimitiveKind.INT, flip1));

        int maxXY = g.add(ENode.call("max", PrimitiveKind.INT, x, y));
        int maxXY_Z = g.add(ENode.call("max", PrimitiveKind.INT, maxXY, z));
        int maxZY = g.add(ENode.call("max", PrimitiveKind.INT, z, y));
        int maxX_ZY = g.add(ENode.call("max", PrimitiveKind.INT, x, maxZY));

        int xPlusY = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, x, y));
        int distribLhs = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, z, xPlusY));
        int zx = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, z, x));
        int zy = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, z, y));
        int distribRhs = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, zx, zy));

        g.saturate(AlgebraicRules.verifierRules(), 8, 256);

        Assertions.assertTrue(g.areEquivalent(abs2, abs1));
        Assertions.assertTrue(g.areEquivalent(flip2, x));
        Assertions.assertTrue(g.areEquivalent(maxXY_Z, maxX_ZY));
        Assertions.assertTrue(g.areEquivalent(distribLhs, distribRhs));
    }

    @Test
    public void testBooleanIdentitiesAndNodeCap() {
        EGraph g = new EGraph();
        int b = g.add(ENode.opaque("b", PrimitiveKind.BOOL));
        int t = g.add(ENode.constVal(true, PrimitiveKind.BOOL));
        int f = g.add(ENode.constVal(false, PrimitiveKind.BOOL));

        int eqTrue = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, b, t));
        int eqFalse = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, b, f));
        int notNotB =
                g.add(
                        ENode.unary(
                                ENode.Op.NOT,
                                PrimitiveKind.BOOL,
                                g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, b))));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 32);

        Assertions.assertTrue(g.areEquivalent(eqTrue, b));
        Assertions.assertTrue(g.areEquivalent(notNotB, b));
        EGraph.ExtractedTree negExtracted = g.extract(eqFalse, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.NOT, negExtracted.node().op());
        Assertions.assertTrue(g.totalNodes() <= 32);
    }

    @Test
    public void testVerifierIteMinMaxClampAbsAndIeeeSoundness() {
        // 1. Integral min(a, b) is commutative & associative
        {
            EGraph g = new EGraph();
            int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
            int b = g.add(ENode.opaque("b", PrimitiveKind.INT));
            int c = g.add(ENode.opaque("c", PrimitiveKind.INT));
            int minAB =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, a, b)),
                                    a,
                                    b));
            int minBA =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, b, a)),
                                    b,
                                    a));
            int minAB_C =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, minAB, c)),
                                    minAB,
                                    c));
            int minBC =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, b, c)),
                                    b,
                                    c));
            int minA_BC =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, a, minBC)),
                                    a,
                                    minBC));
            g.saturate(AlgebraicRules.verifierRules(), 6, 128);
            Assertions.assertTrue(g.areEquivalent(minAB, minBA));
            Assertions.assertTrue(g.areEquivalent(minAB_C, minA_BC));
        }

        // 2. clamp(x) = min(max(x, 0), 100) is idempotent
        {
            EGraph g = new EGraph();
            int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int hundred = g.add(ENode.constVal(100, PrimitiveKind.INT));
            int lo1 =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, a, zero)),
                                    zero,
                                    a));
            int clamp1 =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.GT, PrimitiveKind.BOOL, lo1, hundred)),
                                    hundred,
                                    lo1));
            int lo2 =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.LT, PrimitiveKind.BOOL, clamp1, zero)),
                                    zero,
                                    clamp1));
            int clamp2 =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.GT, PrimitiveKind.BOOL, lo2, hundred)),
                                    hundred,
                                    lo2));
            g.saturate(AlgebraicRules.verifierRules(), 8, 256);
            Assertions.assertTrue(g.areEquivalent(clamp1, clamp2));
        }

        // 3. abs(a - b) == abs(b - a)
        {
            EGraph g = new EGraph();
            int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
            int b = g.add(ENode.opaque("b", PrimitiveKind.INT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int aSubB = g.add(ENode.binary(ENode.Op.SUB, PrimitiveKind.INT, a, b));
            int bSubA = g.add(ENode.binary(ENode.Op.SUB, PrimitiveKind.INT, b, a));
            int absAB =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.LT, PrimitiveKind.BOOL, aSubB, zero)),
                                    g.add(ENode.unary(ENode.Op.NEG, PrimitiveKind.INT, aSubB)),
                                    aSubB));
            int absBA =
                    g.add(
                            ENode.ite(
                                    PrimitiveKind.INT,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.LT, PrimitiveKind.BOOL, bSubA, zero)),
                                    g.add(ENode.unary(ENode.Op.NEG, PrimitiveKind.INT, bSubA)),
                                    bSubA));
            g.saturate(AlgebraicRules.verifierRules(), 6, 128);
            Assertions.assertTrue(g.areEquivalent(absAB, absBA));
        }

        // 4. IEEE-754 double `if a < b then (a) else (b)` is NOT commutative (NaN / signed zero)
        {
            EGraph gd = new EGraph();
            int da = gd.add(ENode.opaque("da", PrimitiveKind.DOUBLE));
            int db = gd.add(ENode.opaque("db", PrimitiveKind.DOUBLE));
            int dMinAB =
                    gd.add(
                            ENode.ite(
                                    PrimitiveKind.DOUBLE,
                                    gd.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, da, db)),
                                    da,
                                    db));
            int dMinBA =
                    gd.add(
                            ENode.ite(
                                    PrimitiveKind.DOUBLE,
                                    gd.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, db, da)),
                                    db,
                                    da));
            gd.saturate(AlgebraicRules.verifierRules(), 6, 128);
            Assertions.assertFalse(gd.areEquivalent(dMinAB, dMinBA));
        }
    }

    @Test
    public void testReflexiveEqualityAndSoundnessGuards() {
        EGraph g = new EGraph();
        int t = g.add(ENode.constVal(true, PrimitiveKind.BOOL));
        int f = g.add(ENode.constVal(false, PrimitiveKind.BOOL));

        // Reflexive exact primitives: bool, byte, short, int, long, char
        int b = g.add(ENode.opaque("b", PrimitiveKind.BOOL));
        int by = g.add(ENode.opaque("by", PrimitiveKind.BYTE));
        int sh = g.add(ENode.opaque("sh", PrimitiveKind.SHORT));
        int i = g.add(ENode.opaque("i", PrimitiveKind.INT));
        int l = g.add(ENode.opaque("l", PrimitiveKind.LONG));
        int ch = g.add(ENode.opaque("ch", PrimitiveKind.CHAR));
        int one = g.add(ENode.constVal(1, PrimitiveKind.INT));

        int bEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, b, b));
        int bNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, b, b));
        int byEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, by, by));
        int shNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, sh, sh));
        int iEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, i, i));
        int iNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, i, i));
        int lEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, l, l));
        int chNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, ch, ch));

        // Algebraically equivalent integral expressions: (i + 1) == (1 + i) -> true
        int iPlus1 = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, i, one));
        int onePlusI = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, one, i));
        int commEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, iPlus1, onePlusI));
        int commNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, iPlus1, onePlusI));

        // Guaranteed strings: marked opaque string & STR_ADD reassociation
        int s = g.add(ENode.opaque("s", PrimitiveKind.NONE));
        g.markReflexiveEq(s);
        int sEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, s, s));
        int sNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, s, s));
        int sa = g.add(ENode.constVal("a", PrimitiveKind.NONE));
        int sb = g.add(ENode.constVal("b", PrimitiveKind.NONE));
        int sab = g.add(ENode.constVal("ab", PrimitiveKind.NONE));
        int sAddAB1 =
                g.add(
                        ENode.binary(
                                ENode.Op.STR_ADD,
                                PrimitiveKind.NONE,
                                g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, s, sa)),
                                sb));
        int sAddAB2 = g.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, s, sab));
        int strAssocEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, sAddAB1, sAddAB2));

        // Babble == / != uses Float.compare / Double.compare total order (NaN == NaN is true)
        int fl = g.add(ENode.opaque("fl", PrimitiveKind.FLOAT));
        int db = g.add(ENode.opaque("db", PrimitiveKind.DOUBLE));
        int flEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, fl, fl));
        int flNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, fl, fl));
        int dbEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, db, db));
        int dbNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, db, db));

        // Soundness guard: unmarked any/Object must NOT rewrite
        int any = g.add(ENode.opaque("any", PrimitiveKind.NONE));
        int anyEq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, any, any));
        int anyNeq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, any, any));
        int anyEqTrue = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, any, t));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 256);

        Assertions.assertTrue(g.areEquivalent(bEq, t));
        Assertions.assertTrue(g.areEquivalent(bNeq, f));
        Assertions.assertTrue(g.areEquivalent(byEq, t));
        Assertions.assertTrue(g.areEquivalent(shNeq, f));
        Assertions.assertTrue(g.areEquivalent(iEq, t));
        Assertions.assertTrue(g.areEquivalent(iNeq, f));
        Assertions.assertTrue(g.areEquivalent(lEq, t));
        Assertions.assertTrue(g.areEquivalent(chNeq, f));
        Assertions.assertTrue(g.areEquivalent(commEq, t));
        Assertions.assertTrue(g.areEquivalent(commNeq, f));
        Assertions.assertTrue(g.areEquivalent(sEq, t));
        Assertions.assertTrue(g.areEquivalent(sNeq, f));
        Assertions.assertTrue(g.areEquivalent(strAssocEq, t));
        Assertions.assertTrue(g.areEquivalent(flEq, t));
        Assertions.assertTrue(g.areEquivalent(flNeq, f));
        Assertions.assertTrue(g.areEquivalent(dbEq, t));
        Assertions.assertTrue(g.areEquivalent(dbNeq, f));

        Assertions.assertFalse(
                g.areEquivalent(anyEq, t),
                "any may hold an object with non-reflexive or throwing equals");
        Assertions.assertFalse(
                g.areEquivalent(anyNeq, f),
                "any may hold an object with non-reflexive or throwing equals");
        Assertions.assertFalse(
                g.areEquivalent(anyEqTrue, any), "any == true must not collapse to any");
    }

    @Test
    public void testNegatedComparisons() {
        EGraph g = new EGraph();
        int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
        int b = g.add(ENode.opaque("b", PrimitiveKind.INT));
        int d1 = g.add(ENode.opaque("d1", PrimitiveKind.DOUBLE));
        int d2 = g.add(ENode.opaque("d2", PrimitiveKind.DOUBLE));

        int eq = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, a, b));
        int neq = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, a, b));
        int lt = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, a, b));
        int lte = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, a, b));
        int gt = g.add(ENode.binary(ENode.Op.GT, PrimitiveKind.BOOL, a, b));
        int gte = g.add(ENode.binary(ENode.Op.GTE, PrimitiveKind.BOOL, a, b));
        int dLt = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, d1, d2));
        int dGte = g.add(ENode.binary(ENode.Op.GTE, PrimitiveKind.BOOL, d1, d2));

        int notEq = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, eq));
        int notNeq = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, neq));
        int notLt = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, lt));
        int notLte = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, lte));
        int notGt = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, gt));
        int notGte = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, gte));
        int notDLt = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, dLt));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 128);

        Assertions.assertTrue(g.areEquivalent(notEq, neq));
        Assertions.assertTrue(g.areEquivalent(notNeq, eq));
        Assertions.assertTrue(g.areEquivalent(notLt, gte));
        Assertions.assertTrue(g.areEquivalent(notLte, gt));
        Assertions.assertTrue(g.areEquivalent(notGt, lte));
        Assertions.assertTrue(g.areEquivalent(notGte, lt));
        Assertions.assertTrue(g.areEquivalent(notDLt, dGte));
        Assertions.assertEquals(ENode.Op.NEQ, g.extract(notEq, EGraph::defaultCost).node().op());
        Assertions.assertEquals(ENode.Op.GTE, g.extract(notLt, EGraph::defaultCost).node().op());
    }

    @Test
    public void testIntegralModAndFloorDivAlgebraicRules() {
        // 1. Optimizer rules: unit divisors, idempotent mod, non-zero zero/self, overflow-guarded
        // factor cancel
        {
            EGraph g = new EGraph();
            int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
            int b = g.add(ENode.opaque("b", PrimitiveKind.INT));
            int shortX = g.add(ENode.opaque("sx", PrimitiveKind.SHORT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int one = g.add(ENode.constVal(1, PrimitiveKind.INT));
            int negOne = g.add(ENode.constVal(-1, PrimitiveKind.INT));
            int three = g.add(ENode.constVal(3, PrimitiveKind.INT));
            int four = g.add(ENode.constVal(4, PrimitiveKind.INT));
            int five = g.add(ENode.constVal(5, PrimitiveKind.INT));

            int aMod1 = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, a, one));
            int aModNeg1 = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, a, negOne));
            int aModB = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, a, b));
            int aModBModB = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, aModB, b));
            int zeroMod5 = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, zero, five));
            int fiveMod5 = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, five, five));
            int aDiv1 = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, a, one));
            int aDivNeg1 = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, a, negOne));
            int negA = g.add(ENode.unary(ENode.Op.NEG, PrimitiveKind.INT, a));
            int zeroDiv5 = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, zero, five));
            int fiveDiv5 = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, five, five));

            // Overflow-guarded factor cancellation: (shortX * 3) // 3 == shortX, but (a * 4) // 4
            // is NOT cancelled
            int sxMul3 = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, shortX, three));
            int sxCancel =
                    g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, sxMul3, three));
            int aMul4 = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, a, four));
            int aNoCancel = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, aMul4, four));

            // Non-zero guard negative tests: unknown b could be 0
            int zeroModB = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, zero, b));
            int bDivB = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, b, b));

            // Division identity must NOT fire under optimizerRules when b is unknown (could throw
            // on b == 0)
            int aDivB = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, a, b));
            int bTimesQ = g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, b, aDivB));
            int recon = g.add(ENode.binary(ENode.Op.ADD, PrimitiveKind.INT, bTimesQ, aModB));

            g.saturate(AlgebraicRules.optimizerRules(), 6, 256);

            Assertions.assertTrue(g.areEquivalent(aMod1, zero));
            Assertions.assertTrue(g.areEquivalent(aModNeg1, zero));
            Assertions.assertTrue(g.areEquivalent(aModBModB, aModB));
            Assertions.assertTrue(g.areEquivalent(zeroMod5, zero));
            Assertions.assertTrue(g.areEquivalent(fiveMod5, zero));
            Assertions.assertTrue(g.areEquivalent(aDiv1, a));
            Assertions.assertTrue(g.areEquivalent(aDivNeg1, negA));
            Assertions.assertTrue(g.areEquivalent(zeroDiv5, zero));
            Assertions.assertTrue(g.areEquivalent(fiveDiv5, one));
            Assertions.assertTrue(g.areEquivalent(sxCancel, shortX));

            Assertions.assertFalse(
                    g.areEquivalent(aNoCancel, a), "int * 4 may overflow; must not cancel // 4");
            Assertions.assertFalse(
                    g.areEquivalent(zeroModB, zero), "0 mod b may throw when b == 0");
            Assertions.assertFalse(g.areEquivalent(bDivB, one), "b // b may throw when b == 0");
            Assertions.assertFalse(g.areEquivalent(recon, a), "division identity is verifier-only");
        }

        // 2. Verifier rules: division identity b * (a // b) + (a mod b) == a
        {
            EGraph g = new EGraph();
            int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
            int b = g.add(ENode.constVal(5, PrimitiveKind.INT));
            int q = g.add(ENode.binary(ENode.Op.FLOOR_DIV, PrimitiveKind.INT, a, b));
            int r = g.add(ENode.binary(ENode.Op.MOD, PrimitiveKind.INT, a, b));
            int recon =
                    g.add(
                            ENode.binary(
                                    ENode.Op.ADD,
                                    PrimitiveKind.INT,
                                    g.add(ENode.binary(ENode.Op.MUL, PrimitiveKind.INT, b, q)),
                                    r));

            g.saturate(AlgebraicRules.verifierRules(), 6, 256);

            Assertions.assertTrue(g.areEquivalent(recon, a));
        }
    }

    @Test
    public void testInjectiveAndMonotoneComparisonCancellation() {
        EGraph g = new EGraph();
        g.registerCallProps("encode", new EGraph.CallProps(false, false, false, false, true, true));
        g.registerCallProps("tag", new EGraph.CallProps(false, false, false, false, false, true));
        g.registerCallProps("clamp", new EGraph.CallProps(true, false, false, false, true, false));

        int a = g.add(ENode.opaque("a", PrimitiveKind.INT));
        int b = g.add(ENode.opaque("b", PrimitiveKind.INT));
        int ea = g.add(ENode.call("encode", PrimitiveKind.DOUBLE, a));
        int eb = g.add(ENode.call("encode", PrimitiveKind.DOUBLE, b));
        int eqEnc = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, ea, eb));
        int ltEnc = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, ea, eb));
        int lteEnc = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, ea, eb));

        int ta = g.add(ENode.call("tag", PrimitiveKind.NONE, a));
        int tb = g.add(ENode.call("tag", PrimitiveKind.NONE, b));
        int neqTag = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, ta, tb));
        int ltTag = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, ta, tb));

        int ca = g.add(ENode.call("clamp", PrimitiveKind.INT, a));
        int cb = g.add(ENode.call("clamp", PrimitiveKind.INT, b));
        int eqClamp = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, ca, cb));
        int lteClamp = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, ca, cb));

        int eqAB = g.add(ENode.binary(ENode.Op.EQ, PrimitiveKind.BOOL, a, b));
        int neqAB = g.add(ENode.binary(ENode.Op.NEQ, PrimitiveKind.BOOL, a, b));
        int ltAB = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, a, b));
        int lteAB = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, a, b));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 128);

        Assertions.assertTrue(g.areEquivalent(eqEnc, eqAB));
        Assertions.assertTrue(g.areEquivalent(ltEnc, ltAB));
        Assertions.assertTrue(g.areEquivalent(lteEnc, lteAB));
        Assertions.assertTrue(g.areEquivalent(neqTag, neqAB));
        Assertions.assertFalse(g.areEquivalent(ltTag, ltAB), "injective alone must not cancel <");
        Assertions.assertFalse(g.areEquivalent(eqClamp, eqAB), "monotone alone must not cancel ==");
        Assertions.assertFalse(
                g.areEquivalent(lteClamp, lteAB), "monotone alone must not cancel <=");

        EGraph.ExtractedTree bestLt = g.extract(ltEnc, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.LT, bestLt.node().op());
        Assertions.assertEquals("a", bestLt.children().get(0).node().symbol());
        Assertions.assertEquals("b", bestLt.children().get(1).node().symbol());
    }

    @Test
    public void testChildWeightedCostModelExtraction() {
        EGraph g = new EGraph();
        int src = g.add(ENode.pipeSrc("src#0", PrimitiveKind.INT));
        int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
        int fCall = g.add(ENode.call("f", PrimitiveKind.INT, e0));
        int pPred =
                g.add(
                        ENode.binary(
                                ENode.Op.GT,
                                PrimitiveKind.BOOL,
                                e0,
                                g.add(ENode.constVal(0, PrimitiveKind.INT))));

        int mapFirst = g.add(ENode.pipeMap("$e1", PrimitiveKind.INT, src, fCall));
        int filterAfterMap = g.add(ENode.pipeFilter(false, mapFirst, pPred));

        int filterFirst = g.add(ENode.pipeFilter(false, src, pPred));
        int mapAfterFilter = g.add(ENode.pipeMap("$e1", PrimitiveKind.INT, filterFirst, fCall));

        g.union(filterAfterMap, mapAfterFilter);

        EGraph.CostModel weighted =
                (node, cc) ->
                        switch (node.op()) {
                            case PIPE_FILTER -> 20 + 2 * cc[0] + cc[1];
                            case PIPE_MAP -> 10 + cc[0] + cc[1];
                            default -> {
                                int s = EGraph.defaultCost(node);
                                for (int c : cc) s += c;
                                yield s;
                            }
                        };
        EGraph.ExtractedTree best = g.extract(filterAfterMap, weighted);
        Assertions.assertEquals(ENode.Op.PIPE_MAP, best.node().op());
        Assertions.assertEquals(ENode.Op.PIPE_FILTER, best.children().get(0).node().op());
    }

    @Test
    public void testPipelineRulesR1ThroughR8AndSoundnessBailouts() {
        // R1 (Stream fusion) + R2 (Filter fusion) + R3 (Cost-based filter reordering)
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "expensive", new EGraph.CallProps(false, false, false, false, false, false));
            int src =
                    g.add(ENode.pipeSrc(PipelineRules.srcSpec("src#0", "$e0"), PrimitiveKind.INT));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int cheapPred = g.add(ENode.binary(ENode.Op.GT, PrimitiveKind.BOOL, e0, zero));
            int expPred = g.add(ENode.call("expensive", PrimitiveKind.BOOL, e0));

            int fExp = g.add(ENode.pipeFilter(false, src, expPred));
            int mat = g.add(ENode.pipeMaterialize("tmp#0", fExp));
            int fCheap = g.add(ENode.pipeFilter(false, mat, cheapPred));
            int root = g.add(ENode.pipeForEach("sink#0", fCheap));

            g.saturate(PipelineRules.allRules(), 8, 192);
            EGraph.ExtractedTree best = g.extract(root, PipelineRules.costModel());
            Assertions.assertEquals(ENode.Op.PIPE_FOR_EACH, best.node().op());
            EGraph.ExtractedTree stream = best.children().get(0);
            Assertions.assertEquals(ENode.Op.PIPE_FILTER, stream.node().op());
            Assertions.assertEquals(ENode.Op.PIPE_SRC, stream.children().get(0).node().op());
            EGraph.ExtractedTree pred = stream.children().get(1);
            Assertions.assertEquals(ENode.Op.AND, pred.node().op());
            Assertions.assertEquals(
                    ENode.Op.GT,
                    pred.children().get(0).node().op(),
                    "R3 + CostModel must place cheaper predicate on left of AND");
            Assertions.assertEquals(ENode.Op.CALL, pred.children().get(1).node().op());
        }

        // R3 soundness bailout: narrow-tagged filter must NOT reorder ahead of its guard
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "expensiveGuard",
                    new EGraph.CallProps(false, false, false, false, false, false));
            int src =
                    g.add(ENode.pipeSrc(PipelineRules.srcSpec("src#0", "$e0"), PrimitiveKind.INT));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int guardPred = g.add(ENode.call("expensiveGuard", PrimitiveKind.BOOL, e0));
            int narrowedPred = g.add(ENode.binary(ENode.Op.GT, PrimitiveKind.BOOL, e0, zero));

            int fGuard = g.add(ENode.pipeFilter(false, src, guardPred));
            int fNarrow = g.add(ENode.pipeFilter(true, fGuard, narrowedPred));
            int root = g.add(ENode.pipeForEach("sink#0", fNarrow));

            g.saturate(PipelineRules.allRules(), 8, 192);
            EGraph.ExtractedTree best = g.extract(root, PipelineRules.costModel());
            EGraph.ExtractedTree filter = best.children().get(0);
            Assertions.assertEquals(ENode.Op.PIPE_FILTER, filter.node().op());
            Assertions.assertEquals(PipelineRules.NARROW_TAG, filter.node().symbol());
            EGraph.ExtractedTree pred = filter.children().get(1);
            Assertions.assertEquals(ENode.Op.AND, pred.node().op());
            Assertions.assertEquals(
                    ENode.Op.CALL,
                    pred.children().get(0).node().op(),
                    "Guard must stay on left of AND when inner/outer filter is narrow-tagged");
        }

        // R4 Case 4a (binder-independent map-filter push-down) & uncancelled Case 4b
        // non-duplication
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "enc", new EGraph.CallProps(false, false, false, false, true, true));
            int src =
                    g.add(ENode.pipeSrc(PipelineRules.srcSpec("src#0", "$e0"), PrimitiveKind.INT));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int e1 = g.add(ENode.opaque("$e1", PrimitiveKind.DOUBLE));
            int encE0 = g.add(ENode.call("enc", PrimitiveKind.DOUBLE, e0));
            int map = g.add(ENode.pipeMap("$e1", PrimitiveKind.DOUBLE, src, encE0));

            int indepPred =
                    g.add(
                            ENode.binary(
                                    ENode.Op.GT,
                                    PrimitiveKind.BOOL,
                                    e0,
                                    g.add(ENode.constVal(0, PrimitiveKind.INT))));
            int rootIndep =
                    g.add(
                            ENode.pipeForEach(
                                    "sink#0", g.add(ENode.pipeFilter(false, map, indepPred))));

            int uncancelledPred =
                    g.add(
                            ENode.binary(
                                    ENode.Op.LTE,
                                    PrimitiveKind.BOOL,
                                    e1,
                                    g.add(ENode.constVal(505.0, PrimitiveKind.DOUBLE))));
            int rootUncancelled =
                    g.add(
                            ENode.pipeForEach(
                                    "sink#1",
                                    g.add(ENode.pipeFilter(false, map, uncancelledPred))));

            g.saturate(PipelineRules.allRules(), 8, 192);
            EGraph.ExtractedTree bestIndep = g.extract(rootIndep, PipelineRules.costModel());
            Assertions.assertEquals(ENode.Op.PIPE_MAP, bestIndep.children().get(0).node().op());
            Assertions.assertEquals(
                    ENode.Op.PIPE_FILTER,
                    bestIndep.children().get(0).children().get(0).node().op());

            EGraph.ExtractedTree bestUncanc = g.extract(rootUncancelled, PipelineRules.costModel());
            Assertions.assertEquals(
                    ENode.Op.PIPE_FILTER,
                    bestUncanc.children().get(0).node().op(),
                    "Uncancelled Case 4b on opaque PIPE_SRC must not duplicate enc(x)");
        }

        // R5 (injective distinct push-down) + negative bailouts (non-injective, non-reflexive,
        // non-binder argument)
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "enc", new EGraph.CallProps(false, false, false, false, true, true));
            g.registerCallProps(
                    "clamp", new EGraph.CallProps(true, false, false, false, true, false));
            int src =
                    g.add(ENode.pipeSrc(PipelineRules.srcSpec("src#0", "$e0"), PrimitiveKind.INT));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int encE0 = g.add(ENode.call("enc", PrimitiveKind.DOUBLE, e0));
            int clampE0 = g.add(ENode.call("clamp", PrimitiveKind.INT, e0));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int encMod =
                    g.add(
                            ENode.call(
                                    "enc",
                                    PrimitiveKind.DOUBLE,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.MUL, PrimitiveKind.INT, e0, zero))));

            int r5Ok =
                    g.add(
                            ENode.pipeDistinct(
                                    "seen#0",
                                    g.add(ENode.pipeMap("$e1", PrimitiveKind.DOUBLE, src, encE0))));
            int r5NonInj =
                    g.add(
                            ENode.pipeDistinct(
                                    "seen#1",
                                    g.add(ENode.pipeMap("$e2", PrimitiveKind.INT, src, clampE0))));
            int r5NonBinderArg =
                    g.add(
                            ENode.pipeDistinct(
                                    "seen#2",
                                    g.add(
                                            ENode.pipeMap(
                                                    "$e3", PrimitiveKind.DOUBLE, src, encMod))));

            int anySrc =
                    g.add(ENode.pipeSrc(PipelineRules.srcSpec("src#1", "$a0"), PrimitiveKind.NONE));
            int a0 = g.add(ENode.opaque("$a0", PrimitiveKind.NONE));
            int r5Any =
                    g.add(
                            ENode.pipeDistinct(
                                    "seen#3",
                                    g.add(
                                            ENode.pipeMap(
                                                    "$a1",
                                                    PrimitiveKind.DOUBLE,
                                                    anySrc,
                                                    g.add(
                                                            ENode.call(
                                                                    "enc",
                                                                    PrimitiveKind.DOUBLE,
                                                                    a0))))));

            g.saturate(PipelineRules.allRules(), 8, 192);
            EGraph.ExtractedTree bestOk = g.extract(r5Ok, PipelineRules.costModel());
            Assertions.assertEquals(ENode.Op.PIPE_MAP, bestOk.node().op());
            Assertions.assertEquals(ENode.Op.PIPE_DISTINCT, bestOk.children().get(0).node().op());

            Assertions.assertEquals(
                    ENode.Op.PIPE_DISTINCT,
                    g.extract(r5NonInj, PipelineRules.costModel()).node().op(),
                    "non-injective map must not push distinct below map");
            Assertions.assertEquals(
                    ENode.Op.PIPE_DISTINCT,
                    g.extract(r5NonBinderArg, PipelineRules.costModel()).node().op(),
                    "injective call on derived expression must not push distinct below map");
            Assertions.assertEquals(
                    ENode.Op.PIPE_DISTINCT,
                    g.extract(r5Any, PipelineRules.costModel()).node().op(),
                    "non-reflexive any input must not push distinct below map");
        }

        // R6 (Range bound clamping: incl/excl, flipped, strict const overflow guard)
        {
            EGraph g = new EGraph();
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
            int hundred = g.add(ENode.constVal(100, PrimitiveKind.INT));
            int ten = g.add(ENode.constVal(10, PrimitiveKind.INT));
            int fifty = g.add(ENode.constVal(50, PrimitiveKind.INT));
            int range =
                    g.add(
                            ENode.pipeRange(
                                    PipelineRules.rangeSpec(false, true, "$e0"),
                                    PrimitiveKind.INT,
                                    zero,
                                    hundred));
            // Both `x >= 10` and `50 > x` (flipped strict const -> x <= 49) in one AND filter
            int pLo = g.add(ENode.binary(ENode.Op.GTE, PrimitiveKind.BOOL, e0, ten));
            int pHi = g.add(ENode.binary(ENode.Op.GT, PrimitiveKind.BOOL, fifty, e0));
            int both = g.add(ENode.binary(ENode.Op.AND, PrimitiveKind.BOOL, pLo, pHi));
            int clamped = g.add(ENode.pipeFilter(false, range, both));

            // Overflow bailout: `x < Integer.MIN_VALUE` must NOT adjust MIN_VALUE - 1
            int minVal = g.add(ENode.constVal(Integer.MIN_VALUE, PrimitiveKind.INT));
            int overflowPred = g.add(ENode.binary(ENode.Op.LT, PrimitiveKind.BOOL, e0, minVal));
            int noClamp = g.add(ENode.pipeFilter(false, range, overflowPred));

            g.saturate(PipelineRules.allRules(), 8, 192);
            EGraph.ExtractedTree bestClamped = g.extract(clamped, PipelineRules.costModel());
            Assertions.assertEquals(ENode.Op.PIPE_RANGE, bestClamped.node().op());
            Assertions.assertEquals(10, bestClamped.children().get(0).node().literal());
            Assertions.assertEquals(49, bestClamped.children().get(1).node().literal());

            Assertions.assertEquals(
                    ENode.Op.PIPE_FILTER,
                    g.extract(noClamp, PipelineRules.costModel()).node().op(),
                    "x < MIN_VALUE must not underflow MIN_VALUE - 1");
        }

        // R7 (monotone early exit PIPE_TAKE_WHILE + O(1) whole-range PIPE_GUARD_RANGE)
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "mono", new EGraph.CallProps(false, false, false, false, true, false));
            g.registerCallProps(
                    "nonMono", new EGraph.CallProps(false, false, false, false, false, true));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int lo = g.add(ENode.opaque("lo", PrimitiveKind.INT));
            int hi = g.add(ENode.opaque("hi", PrimitiveKind.INT));
            int lim = g.add(ENode.opaque("lim", PrimitiveKind.DOUBLE));
            int monoE0 = g.add(ENode.call("mono", PrimitiveKind.DOUBLE, e0));
            int nonMonoE0 = g.add(ENode.call("nonMono", PrimitiveKind.DOUBLE, e0));

            int ascRange =
                    g.add(
                            ENode.pipeRange(
                                    PipelineRules.rangeSpec(false, true, "$e0"),
                                    PrimitiveKind.INT,
                                    lo,
                                    hi));
            int descRange =
                    g.add(
                            ENode.pipeRange(
                                    PipelineRules.rangeSpec(true, true, "$e0"),
                                    PrimitiveKind.INT,
                                    hi,
                                    lo));

            int ascTake =
                    g.add(
                            ENode.pipeFilter(
                                    false,
                                    ascRange,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.LTE,
                                                    PrimitiveKind.BOOL,
                                                    monoE0,
                                                    lim))));
            int descTake =
                    g.add(
                            ENode.pipeFilter(
                                    false,
                                    descRange,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.GTE,
                                                    PrimitiveKind.BOOL,
                                                    monoE0,
                                                    lim))));
            int ascGuard =
                    g.add(
                            ENode.pipeFilter(
                                    false,
                                    ascRange,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.GTE,
                                                    PrimitiveKind.BOOL,
                                                    monoE0,
                                                    lim))));
            int nonMonoFilter =
                    g.add(
                            ENode.pipeFilter(
                                    false,
                                    ascRange,
                                    g.add(
                                            ENode.binary(
                                                    ENode.Op.LTE,
                                                    PrimitiveKind.BOOL,
                                                    nonMonoE0,
                                                    lim))));

            g.saturate(PipelineRules.allRules(), 8, 192);
            Assertions.assertEquals(
                    ENode.Op.PIPE_TAKE_WHILE,
                    g.extract(ascTake, PipelineRules.costModel()).node().op());
            Assertions.assertEquals(
                    ENode.Op.PIPE_TAKE_WHILE,
                    g.extract(descTake, PipelineRules.costModel()).node().op());
            EGraph.ExtractedTree bestGuard = g.extract(ascGuard, PipelineRules.costModel());
            Assertions.assertEquals(ENode.Op.PIPE_GUARD_RANGE, bestGuard.node().op());
            Assertions.assertEquals(ENode.Op.AND, bestGuard.children().get(2).node().op());
            Assertions.assertEquals(
                    ENode.Op.PIPE_FILTER,
                    g.extract(nonMonoFilter, PipelineRules.costModel()).node().op(),
                    "non-monotone function must not trigger R7");
        }

        // R8 (associative pairwise fold) + non-associative bailout
        {
            EGraph g = new EGraph();
            g.registerCallProps(
                    "combine", new EGraph.CallProps(false, true, false, false, false, false));
            g.registerCallProps(
                    "subOp", new EGraph.CallProps(false, false, false, false, false, false));
            int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
            int range =
                    g.add(
                            ENode.pipeRange(
                                    PipelineRules.rangeSpec(false, false, "$e0"),
                                    PrimitiveKind.INT,
                                    g.add(ENode.constVal(0, PrimitiveKind.INT)),
                                    g.add(ENode.opaque("n", PrimitiveKind.INT))));
            int assocFold =
                    g.add(
                            ENode.pipeFold(
                                    PipelineRules.foldSpec("combine", "acc"),
                                    PrimitiveKind.INT,
                                    range,
                                    e0));
            int nonAssocFold =
                    g.add(
                            ENode.pipeFold(
                                    PipelineRules.foldSpec("subOp", "acc"),
                                    PrimitiveKind.INT,
                                    range,
                                    e0));

            g.saturate(PipelineRules.allRules(), 8, 192);
            Assertions.assertEquals(
                    ENode.Op.PIPE_FOLD_PAIRWISE,
                    g.extract(assocFold, PipelineRules.costModel()).node().op());
            Assertions.assertEquals(
                    ENode.Op.PIPE_FOLD,
                    g.extract(nonAssocFold, PipelineRules.costModel()).node().op());
        }
    }

    @Test
    public void testJointFiveStepPipelineSaturation() {
        // BIP042 §2.5: PIPE_MATERIALIZE -> R1 -> R4(4b) -> AlgebraicRules(mono+inj) -> R6 -> $min
        EGraph g = new EGraph();
        g.registerCallProps("encode", new EGraph.CallProps(false, false, false, false, true, true));

        int zero = g.add(ENode.constVal(0, PrimitiveKind.INT));
        int million = g.add(ENode.constVal(1_000_000, PrimitiveKind.INT));
        int fifty = g.add(ENode.constVal(50, PrimitiveKind.INT));
        int e0 = g.add(ENode.opaque("$e0", PrimitiveKind.INT));
        int e1 = g.add(ENode.opaque("$e1", PrimitiveKind.DOUBLE));

        int range =
                g.add(
                        ENode.pipeRange(
                                PipelineRules.rangeSpec(false, true, "$e0"),
                                PrimitiveKind.INT,
                                zero,
                                million));
        int encE0 = g.add(ENode.call("encode", PrimitiveKind.DOUBLE, e0));
        int mapped = g.add(ENode.pipeMap("$e1", PrimitiveKind.DOUBLE, range, encE0));
        int mat = g.add(ENode.pipeMaterialize("scores", mapped));
        int encFifty = g.add(ENode.call("encode", PrimitiveKind.DOUBLE, fifty));
        int pred = g.add(ENode.binary(ENode.Op.LTE, PrimitiveKind.BOOL, e1, encFifty));
        int filtered = g.add(ENode.pipeFilter(false, mat, pred));
        int root = g.add(ENode.pipeForEach("sink#0", filtered));

        g.saturate(PipelineRules.allRules(), 8, 192);
        EGraph.ExtractedTree best = g.extract(root, PipelineRules.costModel());

        Assertions.assertEquals(ENode.Op.PIPE_FOR_EACH, best.node().op());
        EGraph.ExtractedTree bestMap = best.children().get(0);
        Assertions.assertEquals(ENode.Op.PIPE_MAP, bestMap.node().op());
        Assertions.assertEquals("$e1", bestMap.node().symbol());
        EGraph.ExtractedTree bestRange = bestMap.children().get(0);
        Assertions.assertEquals(ENode.Op.PIPE_RANGE, bestRange.node().op());
        Assertions.assertEquals(0, bestRange.children().get(0).node().literal());
        Assertions.assertEquals(50, bestRange.children().get(1).node().literal());
    }
}
