package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Test;

public class BabbleEnvironmentTestCase extends BabbleTestBase {
    @Test
    public void testAssert() throws Exception {
        Assert.assertEquals(true, interpret("assert(1<2)"));

        try {
            Assert.assertEquals(false, interpret("assert(1>2)"));
            Assert.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assert.assertEquals("Assertion failed at line 1 : 1>2", e.getMessage());
        }

        try {
            Assert.assertEquals(false, interpret("assert(1>2, \"Something is wrong\")"));
            Assert.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assert.assertEquals("Something is wrong", e.getMessage());
        }
    }
}
