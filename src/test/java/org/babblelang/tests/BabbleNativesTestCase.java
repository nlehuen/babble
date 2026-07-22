package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BabbleNativesTestCase extends BabbleTestBase {
    @Test
    public void testGlobalScope() throws Exception {
        Assertions.assertEquals("ok", interpret("if STDOUT then ( \"ok\" ) else ( \"ko\") "));
        Assertions.assertEquals("ok", interpret("if print then ( \"ok\" ) else ( \"ko\") "));
        Assertions.assertEquals("ok", interpret("if println then ( \"ok\" ) else ( \"ko\") "));
    }

    @Test
    public void testNativeCallScope() throws Exception {
        Assertions.assertEquals(null, interpret("println (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }
}
