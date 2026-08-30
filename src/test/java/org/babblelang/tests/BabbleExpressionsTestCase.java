package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleExpressionsTestCase extends BabbleTestBase {
    @Test
    public void testIntegerLiteral() throws Exception {
        Assertions.assertEquals(1, interpret("1"));
    }

    @Test
    public void testNumberLiteral() throws Exception {
        Assertions.assertEquals(1.5, interpret("1.5"));
    }

    @Test
    public void testMul() throws Exception {
        Assertions.assertEquals(144, interpret("12 * 12"));
        Assertions.assertEquals(6.0, interpret("12 * 0.5"));
        Assertions.assertEquals(0.25, interpret("0.5 * 0.5"));
    }

    @Test
    public void testDiv() throws Exception {
        Assertions.assertEquals(12.0, interpret("144 / 12"));
        Assertions.assertEquals(144.0, interpret("12 / (1 / 12)"));
    }

    @Test
    public void testAdd() throws Exception {
        Assertions.assertEquals(23, interpret("  12 + 11 "));
        Assertions.assertEquals(23.5, interpret("  12 + 11.5 "));
        Assertions.assertEquals(24.0, interpret("  12.5 + 11.5 "));
    }

    @Test
    public void testWrongTypeAdd() {
        try {
            Assertions.assertEquals(null, interpret("  12 + \"to\" + \"to\" "));
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Not a number : \"to\" in <input> at line number 1", e.getMessage());
        }
        try {
            Assertions.assertEquals(null, interpret("  null + 12 "));
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Not a number : null in <input> at line number 1", e.getMessage());
        }
    }


    @Test
    public void testMinus() throws Exception {
        Assertions.assertEquals(1, interpret("12 - 11"));
        Assertions.assertEquals(1.5, interpret("12.5 - 11"));
        Assertions.assertEquals(0.5, interpret("12 - 11.5"));
    }

    @Test
    public void testPrecedence() throws Exception {
        Assertions.assertEquals(155, interpret("  12 * 12 + 11 "));
        Assertions.assertEquals(155, interpret("  11 + 12 * 12 "));
    }

    @Test
    public void testParen() throws Exception {
        Assertions.assertEquals(155, interpret("  (12 * 12) + 11 "));
        Assertions.assertEquals(276, interpret("  12 * (12 + 11) "));
    }

    @Test
    public void testDef() throws Exception {
        Assertions.assertEquals(315, interpret("  def a = (12 * 12) + 11 ; a + a + 5"));
    }

    @Test
    public void testStringLiteral() throws Exception {
        Assertions.assertEquals("ab6cd", interpret("  \"ab\" + 6 + \"cd\" "));
        Assertions.assertEquals("ab2", interpret("  \"ab\" + 2"));
        Assertions.assertEquals("ab\"2", interpret("  \"ab\\\"\" + 2"));
        Assertions.assertEquals("ab\\2", interpret("  \"ab\\\\\" + 2"));
    }

    @Test
    public void testBooleanExpression() throws Exception {
        Assertions.assertEquals(true, interpret(" true "));
        Assertions.assertEquals(false, interpret(" false "));
        Assertions.assertEquals(false, interpret(" not true "));
        Assertions.assertEquals(true, interpret(" not false "));

        Assertions.assertEquals(false, interpret(" false and false "));
        Assertions.assertEquals(false, interpret(" false and true "));
        Assertions.assertEquals(false, interpret(" true and false "));
        Assertions.assertEquals(true, interpret(" true and true "));

        Assertions.assertEquals(false, interpret(" false or false "));
        Assertions.assertEquals(true, interpret(" false or true "));
        Assertions.assertEquals(true, interpret(" true or false "));
        Assertions.assertEquals(true, interpret(" true or true "));

        Assertions.assertEquals(false, interpret(" not true or not true "));
        Assertions.assertEquals(true, interpret(" not false or not true "));
        Assertions.assertEquals(true, interpret(" not true or not false "));
        Assertions.assertEquals(true, interpret(" not false or not false "));
    }

    @Test
    public void testBooleanShortcuts() throws Exception {
        Assertions.assertEquals(1, interpret(" def a = 1 ; a == 0 and a=2 ; a "));
        Assertions.assertEquals(2, interpret(" def a = 1 ; a == 0 or a=2 ; a "));
        Assertions.assertEquals(2, interpret(" def a = 1 ; a == 1 and a=2 ; a "));
        Assertions.assertEquals(1, interpret(" def a = 1 ; a == 1 or a=2 ; a "));
    }

    @Test
    public void testComp() throws Exception {
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a < b"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a <= b"));
        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a == b"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 1 ; a == b"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; a != b"));
        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 1 ; a != b"));
        Assertions.assertEquals(false, interpret("  def a = \"1\" ; def b = \"2\" ; a == b"));
        Assertions.assertEquals(true, interpret("  def a = \"1\" ; def b = \"1\" ; a == b"));
        Assertions.assertEquals(true, interpret("  def a = \"1\" ; def b = \"2\" ; a != b"));
        Assertions.assertEquals(false, interpret("  def a = \"1\" ; def b = \"1\" ; a != b"));
        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a >= b"));
        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; a > b"));

        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b < a"));
        Assertions.assertEquals(false, interpret("  def a = 1 ; def b = 2 ; b <= a"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 1 ; b == a"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b >= a"));
        Assertions.assertEquals(true, interpret("  def a = 1 ; def b = 2 ; b > a"));

        Assertions.assertEquals(true, interpret("  \"a\" == \"a\""));
        Assertions.assertEquals(false, interpret("  \"b\" >  \"c\""));
        Assertions.assertEquals(true, interpret("  \"b\" >  \"a\""));
    }

    @Test
    public void testMixedNumberComp() throws Exception {
        Assertions.assertEquals(true, interpret(" 1 == 1.0 "));
        Assertions.assertEquals(false, interpret(" 1 <> 1.0 "));
        Assertions.assertEquals(true, interpret(" 1 < 1.5 "));
        Assertions.assertEquals(true, interpret(" 1.5 > 1 "));
        Assertions.assertEquals(true, interpret(" 2 >= 2.0 "));
        Assertions.assertEquals(true, interpret(" 2.0 <= 2 "));

        // "/" always yields a double, so this only holds if Integer and Double compare numerically.
        Assertions.assertEquals(true, interpret(" 2 == 4 / 2 "));
    }

    @Test
    public void testEqualityAcrossUnrelatedTypes() throws Exception {
        // Equality is total : incomparable operands are simply not equal.
        Assertions.assertEquals(false, interpret(" 1 == \"x\" "));
        Assertions.assertEquals(true, interpret(" 1 <> \"x\" "));
        Assertions.assertEquals(false, interpret(" \"x\" == 1 "));
        Assertions.assertEquals(false, interpret(" 1 == true "));
        Assertions.assertEquals(true, interpret(" 1 <> true "));

        Assertions.assertEquals(true, interpret(" null == null "));
        Assertions.assertEquals(false, interpret(" null <> null "));
        Assertions.assertEquals(false, interpret(" null == 1 "));
        Assertions.assertEquals(true, interpret(" 1 <> null "));
    }

    @Test
    public void testOrderingAcrossUnrelatedTypes() {
        // Ordering, unlike equality, stays a type error rather than inventing an answer.
        try {
            interpret(" 1 < \"x\" ");
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Not comparable : 1 and \"x\" in <input> at line number 1", e.getMessage());
        }
        try {
            interpret(" null > 1 ");
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Not comparable : null and 1 in <input> at line number 1", e.getMessage());
        }
    }
}
