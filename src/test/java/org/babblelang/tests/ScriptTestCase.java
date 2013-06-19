package org.babblelang.tests;

public class ScriptTestCase extends BabbleTestBase {
    public void testLaunchScript() throws Exception {
        assertEquals("ok:4:120", interpretFile("src/test/babble/Test1.ba"));
    }
}
