package org.hodor.tests;

import org.junit.Assert;
import org.junit.Test;

public class HodorNativesTestCase extends HodorTestBase {
    @Test
    public void testGlobalScope() throws Exception {
        Assert.assertEquals("ok", interpret("hodor? STDOUT hodor; ( \"ok\" ) ,hodor; ( \"ko\") "));
        Assert.assertEquals("ok", interpret("hodor? hodooor hodor; ( \"ok\" ) ,hodor; ( \"ko\") "));
        Assert.assertEquals("ok", interpret("hodor? hodOOOR hodor; ( \"ok\" ) ,hodor; ( \"ko\") "));
    }

    @Test
    public void testNativeCallScope() throws Exception {
        Assert.assertEquals(null, interpret("hodOOOR (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }
}
