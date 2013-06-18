package org.babblelang.tests;

public class BabbleBasicTestCase extends BabbleTestBase {
    public void testParser() throws Exception {
        parse("C:\\local\\Work\\babble\\src\\test\\babble\\Test1.ba");
    }

    public void testIntegerLiteral() throws Exception {
        assertEquals(1.0, interpret("1"));
    }

    public void testNumberLiteral() throws Exception {
        assertEquals(1.5, interpret("1.5"));
    }

    public void testMul() throws Exception {
        assertEquals(144.0, interpret("12 * 12"));
    }

    public void testDiv() throws Exception {
        assertEquals(12.0, interpret("144 / 12"));
    }

    public void testAdd() throws Exception {
        assertEquals(23.0, interpret("  12 + 11 "));
    }

    public void testMinus() throws Exception {
        assertEquals(1.0, interpret("12 - 11"));
    }

    public void testPrecedence() throws Exception {
        assertEquals(155.0, interpret("  12 * 12 + 11 "));
        assertEquals(155.0, interpret("  11 + 12 * 12 "));
    }

    public void testParen() throws Exception {
        assertEquals(155.0, interpret("  (12 * 12) + 11 "));
        assertEquals(276.0, interpret("  12 * (12 + 11) "));
    }

    public void testDef() throws Exception {
        assertEquals(315.0, interpret("  def a = (12 * 12) + 11 ;;;;; a + a + 5"));
    }
}
