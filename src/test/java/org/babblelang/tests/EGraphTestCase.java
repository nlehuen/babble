package org.babblelang.tests;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared zero-dependency pure-expression E-graph ({@link EGraph})
 * and declarative {@link AlgebraicRules} ({@code BIP027-D1}).
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
        Assertions.assertFalse(g.areEquivalent(mulOne, x), "double * 1.0 must not be rewritten by optimizer rules");
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
        int notNotB = g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL,
                g.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, b))));

        g.saturate(AlgebraicRules.optimizerRules(), 6, 32);

        Assertions.assertTrue(g.areEquivalent(eqTrue, b));
        Assertions.assertTrue(g.areEquivalent(notNotB, b));
        EGraph.ExtractedTree negExtracted = g.extract(eqFalse, EGraph::defaultCost);
        Assertions.assertEquals(ENode.Op.NOT, negExtracted.node().op());
        Assertions.assertTrue(g.totalNodes() <= 32);
    }
}
