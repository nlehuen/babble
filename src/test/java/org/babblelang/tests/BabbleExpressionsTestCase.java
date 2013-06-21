package org.babblelang.tests;

public class BabbleExpressionsTestCase extends BabbleTestBase {
    public void testIntegerLiteral() throws Exception {
        assertEquals(1, interpret("1"));
    }

    public void testNumberLiteral() throws Exception {
        assertEquals(1.5, interpret("1.5"));
    }

    public void testMul() throws Exception {
        assertEquals(144, interpret("12 * 12"));
        assertEquals(6.0, interpret("12 * 0.5"));
        assertEquals(0.25, interpret("0.5 * 0.5"));
    }

    public void testDiv() throws Exception {
        assertEquals(12.0, interpret("144 / 12"));
        assertEquals(144.0, interpret("12 / (1 / 12)"));
    }

    public void testAdd() throws Exception {
        assertEquals(23, interpret("  12 + 11 "));
        assertEquals(23.5, interpret("  12 + 11.5 "));
        assertEquals(24.0, interpret("  12.5 + 11.5 "));
    }

    public void testMinus() throws Exception {
        assertEquals(1, interpret("12 - 11"));
        assertEquals(1.5, interpret("12.5 - 11"));
        assertEquals(0.5, interpret("12 - 11.5"));
    }

    public void testPrecedence() throws Exception {
        assertEquals(155, interpret("  12 * 12 + 11 "));
        assertEquals(155, interpret("  11 + 12 * 12 "));
    }

    public void testParen() throws Exception {
        assertEquals(155, interpret("  (12 * 12) + 11 "));
        assertEquals(276, interpret("  12 * (12 + 11) "));
    }

    public void testDef() throws Exception {
        assertEquals(315, interpret("  def a = (12 * 12) + 11 ; a + a + 5"));
    }

    public void testStringLiteral() throws Exception {
        assertEquals("ab6cd", interpret("  \"ab\" + 6 + \"cd\" "));
        assertEquals("ab2", interpret("  \"ab\" + 2"));
        assertEquals("ab\"2", interpret("  \"ab\\\"\" + 2"));
        assertEquals("ab\\2", interpret("  \"ab\\\\\" + 2"));
    }

    public void testBooleanExpression() throws Exception {
        assertEquals(true, interpret(" true "));
        assertEquals(false, interpret(" false "));
        assertEquals(false, interpret(" not true "));
        assertEquals(true, interpret(" not false "));

        assertEquals(false, interpret(" false and false "));
        assertEquals(false, interpret(" false and true "));
        assertEquals(false, interpret(" true and false "));
        assertEquals(true, interpret(" true and true "));

        assertEquals(false, interpret(" false or false "));
        assertEquals(true, interpret(" false or true "));
        assertEquals(true, interpret(" true or false "));
        assertEquals(true, interpret(" true or true "));

        assertEquals(false, interpret(" not true or not true "));
        assertEquals(true, interpret(" not false or not true "));
        assertEquals(true, interpret(" not true or not false "));
        assertEquals(true, interpret(" not false or not false "));
    }

    public void testBooleanShortcuts() throws Exception {
        assertEquals(1, interpret(" def a = 1 ; a == 0 and a=2 ; a "));
        assertEquals(2, interpret(" def a = 1 ; a == 0 or a=2 ; a "));
        assertEquals(2, interpret(" def a = 1 ; a == 1 and a=2 ; a "));
        assertEquals(1, interpret(" def a = 1 ; a == 1 or a=2 ; a "));
    }

    public void testComp() throws Exception {
        assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a < b"));
        assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a <= b"));
        assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a == b"));
        assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a >= b"));
        assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a > b"));

        assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b < a"));
        assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b <= a"));
        assertEquals(true, interpret("  def a = 1 ; def b = 1 ; b == a"));
        assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b >= a"));
        assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b > a"));

        assertEquals(true, interpret("  \"a\" == \"a\""));
        assertEquals(false, interpret("  \"b\" >  \"c\""));
        assertEquals(true, interpret("  \"b\" >  \"a\""));
    }
}
