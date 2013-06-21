package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Test;

public class BabbleNativesTestCase extends BabbleTestBase {
    @Test
    public void testGlobalScope() throws Exception {
        Assert.assertEquals("ok", interpret("if STDOUT then ( \"ok\" ) else ( \"ko\") "));
        Assert.assertEquals("ok", interpret("if print then ( \"ok\" ) else ( \"ko\") "));
        Assert.assertEquals("ok", interpret("if println then ( \"ok\" ) else ( \"ko\") "));
    }

    @Test
    public void testNativeCallScope() throws Exception {
        Assert.assertEquals(null, interpret("println (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }
}
