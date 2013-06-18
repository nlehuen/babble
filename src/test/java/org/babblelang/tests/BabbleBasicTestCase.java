package org.babblelang.tests;

public class BabbleBasicTestCase extends BabbleTestBase {
    public void testParser() throws Exception {
        parse("C:\\local\\Work\\babble\\src\\test\\babble\\Test1.ba");
    }

    public void testEngine() throws Exception {
        assertEquals(null, interpret("C:\\local\\Work\\babble\\src\\test\\babble\\Test1.ba"));
    }
}
