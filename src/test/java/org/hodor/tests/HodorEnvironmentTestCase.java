package org.hodor.tests;

import org.junit.Assert;
import org.junit.Test;

public class HodorEnvironmentTestCase extends HodorTestBase {
    @Test
    public void testAssert() throws Exception {
        Assert.assertEquals(true, interpret("HODORRRR?(1<2)"));

        try {
            Assert.assertEquals(false, interpret("HODORRRR?(1>2)"));
            Assert.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assert.assertEquals("HODOR HODORRRR! 1 : 1>2", e.getMessage());
        }

        try {
            Assert.assertEquals(false, interpret("HODORRRR?(1>2, \"Something is wrong\")"));
            Assert.fail("Should fail assertion");
        } catch (AssertionError e) {
            Assert.assertEquals("Something is wrong", e.getMessage());
        }
    }
}
