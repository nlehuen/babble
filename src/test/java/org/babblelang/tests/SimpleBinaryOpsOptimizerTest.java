package org.babblelang.tests;

import org.junit.Test;

public class SimpleBinaryOpsOptimizerTest extends OptimizerTestBase {
    @Test
    public void testPlus() {
        assertEquivalent("1", "1 + 0", new SimpleBinaryOpsOptimizer());
        assertEquivalent("1", "0 + 1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("1 + 1", "0 + 0 + 1 + 0 + 1 + 0 + 0", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testMul() {
        assertEquivalent("2", "1 * 2", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2", "2 * 1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2 * 2", "1 * 1 * 2 * 1 * 2 * 1 *1", new SimpleBinaryOpsOptimizer());
    }
}
