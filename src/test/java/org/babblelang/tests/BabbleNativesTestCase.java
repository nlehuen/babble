package org.babblelang.tests;

public class BabbleNativesTestCase extends BabbleTestBase {
    public void testGlobalScope() throws Exception {
        assertEquals("ok", interpret("if STDOUT ( \"ok\" ) else ( \"ko\") "));
        assertEquals("ok", interpret("if print ( \"ok\" ) else ( \"ko\") "));
        assertEquals("ok", interpret("if println ( \"ok\" ) else ( \"ko\") "));
    }

    public void testNativeCallScope() throws Exception {
        assertEquals(null, interpret("println (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }
}
