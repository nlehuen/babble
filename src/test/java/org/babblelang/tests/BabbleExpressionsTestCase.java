package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Test;

public class BabbleExpressionsTestCase extends BabbleTestBase {
    @Test
    public void testIntegerLiteral() throws Exception {
        Assert.assertEquals(1, interpret("1"));
    }

    @Test
    public void testNumberLiteral() throws Exception {
        Assert.assertEquals(1.5, interpret("1.5"));
    }

    @Test
    public void testMul() throws Exception {
        Assert.assertEquals(144, interpret("12 * 12"));
        Assert.assertEquals(6.0, interpret("12 * 0.5"));
        Assert.assertEquals(0.25, interpret("0.5 * 0.5"));
    }

    @Test
    public void testDiv() throws Exception {
        Assert.assertEquals(12.0, interpret("144 / 12"));
        Assert.assertEquals(144.0, interpret("12 / (1 / 12)"));
    }

    @Test
    public void testAdd() throws Exception {
        Assert.assertEquals(23, interpret("  12 + 11 "));
        Assert.assertEquals(23.5, interpret("  12 + 11.5 "));
        Assert.assertEquals(24.0, interpret("  12.5 + 11.5 "));
    }

    @Test
    public void testMinus() throws Exception {
        Assert.assertEquals(1, interpret("12 - 11"));
        Assert.assertEquals(1.5, interpret("12.5 - 11"));
        Assert.assertEquals(0.5, interpret("12 - 11.5"));
    }

    @Test
    public void testPrecedence() throws Exception {
        Assert.assertEquals(155, interpret("  12 * 12 + 11 "));
        Assert.assertEquals(155, interpret("  11 + 12 * 12 "));
    }

    @Test
    public void testParen() throws Exception {
        Assert.assertEquals(155, interpret("  (12 * 12) + 11 "));
        Assert.assertEquals(276, interpret("  12 * (12 + 11) "));
    }

    @Test
    public void testDef() throws Exception {
        Assert.assertEquals(315, interpret("  def a = (12 * 12) + 11 ; a + a + 5"));
    }

    @Test
    public void testStringLiteral() throws Exception {
        Assert.assertEquals("ab6cd", interpret("  \"ab\" + 6 + \"cd\" "));
        Assert.assertEquals("ab2", interpret("  \"ab\" + 2"));
        Assert.assertEquals("ab\"2", interpret("  \"ab\\\"\" + 2"));
        Assert.assertEquals("ab\\2", interpret("  \"ab\\\\\" + 2"));
    }

    @Test
    public void testBooleanExpression() throws Exception {
        Assert.assertEquals(true, interpret(" true "));
        Assert.assertEquals(false, interpret(" false "));
        Assert.assertEquals(false, interpret(" not true "));
        Assert.assertEquals(true, interpret(" not false "));

        Assert.assertEquals(false, interpret(" false and false "));
        Assert.assertEquals(false, interpret(" false and true "));
        Assert.assertEquals(false, interpret(" true and false "));
        Assert.assertEquals(true, interpret(" true and true "));

        Assert.assertEquals(false, interpret(" false or false "));
        Assert.assertEquals(true, interpret(" false or true "));
        Assert.assertEquals(true, interpret(" true or false "));
        Assert.assertEquals(true, interpret(" true or true "));

        Assert.assertEquals(false, interpret(" not true or not true "));
        Assert.assertEquals(true, interpret(" not false or not true "));
        Assert.assertEquals(true, interpret(" not true or not false "));
        Assert.assertEquals(true, interpret(" not false or not false "));
    }

    @Test
    public void testBooleanShortcuts() throws Exception {
        Assert.assertEquals(1, interpret(" def a = 1 ; a == 0 and a=2 ; a "));
        Assert.assertEquals(2, interpret(" def a = 1 ; a == 0 or a=2 ; a "));
        Assert.assertEquals(2, interpret(" def a = 1 ; a == 1 and a=2 ; a "));
        Assert.assertEquals(1, interpret(" def a = 1 ; a == 1 or a=2 ; a "));
    }

    @Test
    public void testComp() throws Exception {
        Assert.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a < b"));
        Assert.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a <= b"));
        Assert.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a == b"));
        Assert.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a >= b"));
        Assert.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a > b"));

        Assert.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b < a"));
        Assert.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b <= a"));
        Assert.assertEquals(true, interpret("  def a = 1 ; def b = 1 ; b == a"));
        Assert.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b >= a"));
        Assert.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b > a"));

        Assert.assertEquals(true, interpret("  \"a\" == \"a\""));
        Assert.assertEquals(false, interpret("  \"b\" >  \"c\""));
        Assert.assertEquals(true, interpret("  \"b\" >  \"a\""));
    }
}
