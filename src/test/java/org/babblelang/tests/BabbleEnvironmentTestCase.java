package org.babblelang.tests;

public class BabbleEnvironmentTestCase extends BabbleTestBase {
    public void testAssert() throws Exception {
        assertEquals(true, interpret("assert(1<2)"));

        try {
            assertEquals(false, interpret("assert(1>2)"));
            fail("Should fail assertion");
        } catch (junit.framework.AssertionFailedError e) {
            assertEquals("null", e.getMessage());
        }

        try {
            assertEquals(false, interpret("assert(1>2, \"Something is wrong\")"));
            fail("Should fail assertion");
        } catch (junit.framework.AssertionFailedError e) {
            assertEquals("Something is wrong", e.getMessage());
        }
    }
}
