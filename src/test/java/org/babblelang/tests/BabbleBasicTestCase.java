package org.babblelang.tests;

public class BabbleBasicTestCase extends BabbleTestBase {
    public void test1() throws Exception {
        parse("C:\\local\\Work\\babble\\src\\test\\babble\\Test1.ba").toStringTree();
    }
}
