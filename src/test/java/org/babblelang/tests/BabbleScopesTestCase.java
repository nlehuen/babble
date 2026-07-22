package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleScopesTestCase extends BabbleTestBase {
    @Test
    public void testPackageScope() throws Exception {
        Assertions.assertEquals(2, interpret("package test ( def value = 1 ) ; test.value + 1"));
        Assertions.assertEquals(6, interpret("package test ( def value = 1 ; package test2 ( def value = 3 ) ; value = value + 1 ) ; test.value + test.test2.value + 1"));
    }

    @Test
    public void testIncr() throws Exception {
        Assertions.assertEquals(2, interpret("def i = 0 ; ( i = i + 1 ) ; i = i + 1 ; i"));
    }

    @Test
    public void testPrecedence() throws Exception {
        Assertions.assertEquals(2, interpret("def value = 1; value = value + 1 ; value"));
        Assertions.assertEquals(2, interpret("package test (def value = 1) ; def value = test.value + 1 ; value"));
        Assertions.assertEquals(1, interpret("package test (def value = 1) ; def value ; value = test.value ; value"));
    }

    @Test
    public void testDef() throws Exception {
        Assertions.assertEquals(null, interpret("def i"));
        Assertions.assertEquals(null, interpret("def i ; i"));
        Assertions.assertEquals(1, interpret("def i ; i = 1"));
        Assertions.assertEquals(1, interpret("def i ; i = 1 ; i"));
    }

    @Test
    public void testAssign() throws Exception {
        Assertions.assertEquals(0, interpret("def i = 0 ; i"));
        Assertions.assertEquals(1, interpret("def i = 0 ; i = 1"));
        Assertions.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1"));
        Assertions.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1 ; i"));
    }

    @Disabled("Block scope is not implemented yet")
    @Test
    public void testBlockScope() throws Exception {
        try {
            Assertions.assertEquals(2, interpret("def a = 1 ; (def b = 2) ; a ; b"));
            Assertions.fail("Should report \"No such key : b\"");
        } catch (IllegalArgumentException e) {
            Assertions.assertEquals("No such key : b", e.getMessage());
        }

        try {
            Assertions.assertEquals("ba", interpret("def a = 1 ; (def b = 2) ; a = 1 ; b = 5"));
            Assertions.fail("Should report \"No such key : b\"");
        } catch (IllegalArgumentException e) {
            Assertions.assertEquals("No such key : b", e.getMessage());
        }

        try {
            Assertions.assertEquals("ba", interpret("def a = 1 ; def a = 2"));
            Assertions.fail("Should report \"Key already defined : a\"");
        } catch (IllegalArgumentException e) {
            Assertions.assertEquals("Key already defined : a", e.getMessage());
        }
    }

    @Test
    public void testBlockResult() throws Exception {
        Assertions.assertEquals(6, interpret("( 1 + 2 + 3 )"));
        Assertions.assertEquals(3, interpret("( 1 ; 2 ; 3 )"));
        Assertions.assertEquals(3, interpret("( 1 \n 2 \n 3 )"));
    }

    @Test
    public void testNewlineEndsStatement() throws Exception {
        // under the old grammar this parsed as the call 1(a + 1)
        Assertions.assertEquals(2, interpret("def a = 1 \n ( a + 1 )"));
    }

    @Test
    public void testJuxtapositionIsRejected() throws Exception {
        try {
            interpret("( 1  2 3 )");
            Assertions.fail("Statements on the same line require a ';' separator");
        } catch (ScriptException e) {
            // expected : juxtaposition without ';' or newline is a parse error
        }
    }
}
