package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BabbleEnvironmentTestCase extends BabbleTestBase {
    @Test
    public void testAssert() throws Exception {
        Assertions.assertEquals(true, interpret("assert(1<2)"));

        try {
            Assertions.assertEquals(false, interpret("assert(1>2)"));
            Assertions.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assertions.assertEquals("Assertion failed at line 1 : 1>2", e.getMessage());
        }

        try {
            Assertions.assertEquals(false, interpret("assert(1>2, \"Something is wrong\")"));
            Assertions.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assertions.assertEquals("Something is wrong", e.getMessage());
        }
    }
}
