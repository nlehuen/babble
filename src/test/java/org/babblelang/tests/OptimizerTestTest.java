package org.babblelang.tests;

import org.junit.Test;

/**
 * Tests for the optimizer testing framework.
 */
public class OptimizerTestTest extends OptimizerTestBase {
    @Test
    public void testEquivalence() {
        assertEquivalent("1", "1");
        assertEquivalent("1 + 1", "1+1");
        assertEquivalent("0 + 1", "0+1");
        assertEquivalent("0 + 0", "1+2", new StupidOptimizer());
        assertEquivalent("\"coucou\" + 0", "\"coucou\"+2", new StupidOptimizer());
    }

    @Test(expected = org.junit.ComparisonFailure.class)
    public void testDifference1() {
        assertEquivalent("1 + 0", "1+1");
    }

    @Test(expected = org.junit.ComparisonFailure.class)
    public void testDifference2() {
        assertEquivalent("1 + 0", "1+1", new StupidOptimizer());
    }
}
