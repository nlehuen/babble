package org.babblelang.tests;

import org.babblelang.engine.optimizer.SimpleBinaryOpsOptimizer;
import org.junit.Test;

public class SimpleBinaryOpsOptimizerTest extends OptimizerTestBase {
    @Test
    public void testPlus() {
        assertEquivalent("1", "1 + 0", new SimpleBinaryOpsOptimizer());
        assertEquivalent("1", "0 + 1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("1 + 1", "0 + 0 + 1 + 0 + 1 + 0 + 0", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testMinus() {
        assertEquivalent("1", "1 - 0", new SimpleBinaryOpsOptimizer());
        assertEquivalent("1", "1 - 0 - 0 - 0", new SimpleBinaryOpsOptimizer());
        assertEquivalent("0 - 1", "0 - 1", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testMul() {
        assertEquivalent("2", "1 * 2", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2", "2 * 1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2 * 2", "1 * 1 * 2 * 1 * 2 * 1 *1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("0", "1 * ( 1 * 2 * 0 * 2 ) * 1 *1", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testDiv() {
        assertEquivalent("1 / 2", "1 / 2", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2", "2 / 1", new SimpleBinaryOpsOptimizer());
        assertEquivalent("2", "2 / 1 / 1 / 1", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testNot() {
        assertEquivalent("not true", "not not not true", new SimpleBinaryOpsOptimizer());
    }

    @Test
    public void testAllTogether() {
        assertEquivalent("2", "(2 * 1 + 0 - 0 ) / ( (1 + 0 * 1 ) / 1 )", new SimpleBinaryOpsOptimizer());
    }
}
