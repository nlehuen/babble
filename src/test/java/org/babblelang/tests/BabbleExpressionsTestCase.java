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
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not a number : \"to\" in <input> at line number 1 at column number 8", e.getMessage());
        }
        try {
            Assertions.assertEquals(null, interpret("  null + 12 "));
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not a number : null in <input> at line number 1 at column number 3", e.getMessage());
        }
        ScriptException staticFail = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def id = fn (x : any) : any -> ( x ) ; 12 + id(\"to\")"));
        Assertions.assertTrue(staticFail.getMessage().contains("Not a number : id(\"to\")"), staticFail.getMessage());
        ScriptException runtime = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def id = fn (x : any) : any -> ( x ) ; 12 + (id(\"to\") as int)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Type error : expected int, got string in <input> at line number 1", runtime.getMessage());
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
        Assertions.assertEquals(1, interpret(" def a = 1 ; a == 0 and (a=2) ; a "));
        Assertions.assertEquals(2, interpret(" def a = 1 ; a == 0 or (a=2) ; a "));
        Assertions.assertEquals(2, interpret(" def a = 1 ; a == 1 and (a=2) ; a "));
        Assertions.assertEquals(1, interpret(" def a = 1 ; a == 1 or (a=2) ; a "));
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
    public void testDivisionAlwaysGivesADouble() throws Exception {
        // Unlike +, - and *, "/" is a double division even between two ints, and never throws.
        Assertions.assertEquals(2.0, interpret(" 4 / 2 "));
        Assertions.assertEquals(3.5, interpret(" 7 / 2 "));
        Assertions.assertEquals(Double.POSITIVE_INFINITY, interpret(" 1 / 0 "));
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, interpret(" -1 / 0 "));
        Assertions.assertTrue(Double.isNaN((Double) interpret(" 0 / 0 ")));
        Assertions.assertEquals(4, interpret(" 2 * 2 "));
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
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not comparable : 1 and \"x\" in <input> at line number 1 at column number 2", e.getMessage());
        }
        try {
            interpret(" null > 1 ");
            Assertions.fail("Should report type error");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not comparable : null and 1 in <input> at line number 1 at column number 2", e.getMessage());
        }
        ScriptException staticFail = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def id = fn (x : any) : any -> ( x ) ; 1 < id(\"x\")"));
        Assertions.assertTrue(staticFail.getMessage().contains("Not comparable : 1 and id(\"x\")"), staticFail.getMessage());
        ScriptException runtime = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def id = fn (x : any) : any -> ( x ) ; 1 < (id(\"x\") as int)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Type error : expected int, got string in <input> at line number 1", runtime.getMessage());
    }

    @Test
    public void testBip012P1SyntaxAndAssignments() throws Exception {
        // Multiline comment on a single line must not be swallowed by COMMENT.
        Assertions.assertEquals(1, interpret(";;( ok ;;) def x = 1 ; x"));
        // Invalid assignment targets are rejected in BlockScopes.
        ScriptException litAssign = Assertions.assertThrows(ScriptException.class, () -> interpret("1 = 2"));
        Assertions.assertTrue(litAssign.getMessage().contains("Cannot assign to expression"), litAssign.getMessage());
        ScriptException callAssign = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn () -> ( 1 ) ; f() = 2"));
        Assertions.assertTrue(callAssign.getMessage().contains("Cannot assign to expression"), callAssign.getMessage());
    }
}
