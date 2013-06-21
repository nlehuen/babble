package org.babblelang.tests;

public class BabbleNativesTestCase extends BabbleTestBase {
    public void testGlobalScope() throws Exception {
        assertEquals("ok", interpret("if STDOUT then ( \"ok\" ) else ( \"ko\") "));
        assertEquals("ok", interpret("if print then ( \"ok\" ) else ( \"ko\") "));
        assertEquals("ok", interpret("if println then ( \"ok\" ) else ( \"ko\") "));
    }

    public void testNativeCallScope() throws Exception {
        assertEquals(null, interpret("println (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }
}
