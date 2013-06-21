package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Test;

public class ScriptTestCase extends BabbleTestBase {
    @Test
    public void testLaunchScript() throws Exception {
        Assert.assertEquals("ok:4:120:720", interpretFile("src/test/babble/Test1.ba"));
    }

    @Test
    public void testLaunchScriptFr() throws Exception {
        Assert.assertEquals("ok:4:120:720", interpretFile("src/test/babble/Test1-fr.ba"));
    }
}
