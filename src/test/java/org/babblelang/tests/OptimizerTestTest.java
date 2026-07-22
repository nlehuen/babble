package org.babblelang.tests;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquivalent("0 + 0 + 0 + 0", "0-0+0-0", new StupidOptimizer());
        assertEquivalent("\"coucou\" + 0", "\"coucou\"+2", new StupidOptimizer());
        assertEquivalent("\"coucou\" + 0", "\"coucou\"-2", new StupidOptimizer());
    }

    @Test
    public void testDifference1() {
        assertThrows(AssertionFailedError.class, () -> assertEquivalent("1 + 0", "1+1"));
    }

    @Test
    public void testDifference2() {
        assertThrows(AssertionFailedError.class, () -> assertEquivalent("1 + 0", "1+1", new StupidOptimizer()));
    }
}
