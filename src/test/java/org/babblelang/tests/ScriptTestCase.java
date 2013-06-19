package org.babblelang.tests;

public class ScriptTestCase extends BabbleTestBase {
    public void testLaunchScript() throws Exception {
        assertEquals("ok:4:120:720", interpretFile("src/test/babble/Test1.ba"));
    }

    public void testLaunchScriptFr() throws Exception {
        assertEquals("ok:4:120:720", interpretFile("src/test/babble/Test1-fr.ba"));
    }
}
