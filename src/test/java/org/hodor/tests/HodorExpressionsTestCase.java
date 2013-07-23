package org.hodor.tests;

import org.junit.Assert;
import org.junit.Test;

import javax.script.ScriptException;

public class HodorExpressionsTestCase extends HodorTestBase {
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
    public void testWrongTypeAdd() throws Exception {
        try {
            Assert.assertEquals(null, interpret("  12 + \"to\" + \"to\" "));
            Assert.fail("Should report type error");
        } catch (ScriptException e) {
            Assert.assertEquals("org.hodor.engine.BabbleException: Not a number : \"to\" in <input> at line number 1", e.getMessage());
        }
        try {
            Assert.assertEquals(null, interpret("  HODOR + 12 "));
            Assert.fail("Should report type error");
        } catch (ScriptException e) {
            Assert.assertEquals("org.hodor.engine.BabbleException: Not a number : HODOR in <input> at line number 1", e.getMessage());
        }
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
        Assert.assertEquals(315, interpret("  hodor: a = (12 * 12) + 11 ; a + a + 5"));
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
        Assert.assertEquals(true, interpret(" HOdor "));
        Assert.assertEquals(false, interpret(" hoDOR "));
        Assert.assertEquals(false, interpret(" HOD HOdor "));
        Assert.assertEquals(true, interpret(" HOD hoDOR "));

        Assert.assertEquals(false, interpret(" hoDOR hod hoDOR "));
        Assert.assertEquals(false, interpret(" hoDOR hod hoDOR "));
        Assert.assertEquals(false, interpret(" HOdor hod hoDOR "));
        Assert.assertEquals(true, interpret(" HOdor hod HOdor "));

        Assert.assertEquals(false, interpret(" hoDOR or hoDOR "));
        Assert.assertEquals(true, interpret(" hoDOR or HOdor "));
        Assert.assertEquals(true, interpret(" HOdor or hoDOR "));
        Assert.assertEquals(true, interpret(" HOdor or HOdor "));

        Assert.assertEquals(false, interpret(" HOD HOdor or HOD HOdor "));
        Assert.assertEquals(true, interpret(" HOD hoDOR or HOD HOdor "));
        Assert.assertEquals(true, interpret(" HOD HOdor or HOD hoDOR "));
        Assert.assertEquals(true, interpret(" HOD hoDOR or HOD hoDOR "));
    }

    @Test
    public void testBooleanShortcuts() throws Exception {
        Assert.assertEquals(1, interpret(" hodor: a = 1 ; a == 0 hod a=2 ; a "));
        Assert.assertEquals(2, interpret(" hodor: a = 1 ; a == 0 or a=2 ; a "));
        Assert.assertEquals(2, interpret(" hodor: a = 1 ; a == 1 hod a=2 ; a "));
        Assert.assertEquals(1, interpret(" hodor: a = 1 ; a == 1 or a=2 ; a "));
    }

    @Test
    public void testComp() throws Exception {
        Assert.assertEquals(true, interpret("  hodor: a = 1 ; hodor: b = 2 ; a < b"));
        Assert.assertEquals(true, interpret("  hodor: a = 1 ; hodor: b = 2 ; a <= b"));
        Assert.assertEquals(false, interpret("  hodor: a = 1 ; hodor: b = 2 ; a == b"));
        Assert.assertEquals(false, interpret("  hodor: a = 1 ; hodor: b = 2 ; a >= b"));
        Assert.assertEquals(false, interpret("  hodor: a = 1 ; hodor: b = 2 ; a > b"));

        Assert.assertEquals(false, interpret("  hodor: a = 1 ; hodor: b = 2 ; b < a"));
        Assert.assertEquals(false, interpret("  hodor: a = 1 ; hodor: b = 2 ; b <= a"));
        Assert.assertEquals(true, interpret("  hodor: a = 1 ; hodor: b = 1 ; b == a"));
        Assert.assertEquals(true, interpret("  hodor: a = 1 ; hodor: b = 2 ; b >= a"));
        Assert.assertEquals(true, interpret("  hodor: a = 1 ; hodor: b = 2 ; b > a"));

        Assert.assertEquals(true, interpret("  \"a\" == \"a\""));
        Assert.assertEquals(false, interpret("  \"b\" >  \"c\""));
        Assert.assertEquals(true, interpret("  \"b\" >  \"a\""));
    }
}
