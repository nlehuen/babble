package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleScopesTestCase extends BabbleTestBase {
    @Test
    public void testIncr() throws Exception {
        Assertions.assertEquals(2, interpret("def i = 0 ; ( i = i + 1 ) ; i = i + 1 ; i"));
    }

    @Test
    public void testPrecedence() throws Exception {
        Assertions.assertEquals(2, interpret("def value = 1; value = value + 1 ; value"));
        Assertions.assertEquals(2, interpret("def o = object (def value = 1) ; def value = o.value + 1 ; value"));
        Assertions.assertEquals(1, interpret("def o = object (def value = 1) ; def value : any? ; value = o.value ; value"));
    }

    @Test
    public void testDef() throws Exception {
        Assertions.assertEquals(null, interpret("def i : any?"));
        Assertions.assertEquals(null, interpret("def i : any? ; i"));
        Assertions.assertEquals(1, interpret("def i : any? ; i = 1"));
        Assertions.assertEquals(1, interpret("def i : any? ; i = 1 ; i"));
        assertStaticFails("def i : any", "Type error : expected any, got null", 1);
    }

    @Test
    public void testAssign() throws Exception {
        Assertions.assertEquals(0, interpret("def i = 0 ; i"));
        Assertions.assertEquals(1, interpret("def i = 0 ; i = 1"));
        Assertions.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1"));
        Assertions.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1 ; i"));
    }

    @Test
    public void testBlockScope() {
        assertFails("def a = 1 ; (def b = 2) ; a ; b", "No such name : b");
        assertFails("def a = 1 ; (def b = 2) ; a = 1 ; b = 5", "No such name : b");
        // Rejected before anything runs.
        assertFails("def a = 1 ; def a = 2", "Name already defined : a");
    }

    @Test
    public void testBranchesAndLoopBodiesAreScopes() {
        assertFails("def i = 1 ; if i > 0 then ( def y = 1 ) ; y", "No such name : y");
        assertFails("def i = 0 ; while i < 3 then ( def x = i * 2 ; i = i + 1 ) ; x", "No such name : x");
    }

    @Test
    public void testBlockDefinitionsShadowOuterOnes() throws Exception {
        Assertions.assertEquals(3, interpret("def a = 1 ; def b = ( def a = 2 ; a + 1 ) ; b"));
        Assertions.assertEquals(1, interpret("def a = 1 ; ( def a = 2 ) ; a"));
        // An assignment still reaches the nearest declared name.
        Assertions.assertEquals(5, interpret("def a = 1 ; ( a = 5 ) ; a"));
    }

    @Test
    public void testLoopBodyIsAFreshScopeOnEachIteration() throws Exception {
        // Used to fail on the second iteration with "Name already defined : x".
        Assertions.assertEquals(4, interpret("def i = 0 ; def s = 0 ; while i < 3 then ( def x = i * 2 ; s = s + x ; i = i + 1 ) ; s - 2"));
        // Closures created in different iterations capture different variables.
        Assertions.assertEquals(3, interpret(
                "def Box = class (def f : () : int) ()\n"
                        + "def l : java.util.List(Box) = java.util.ArrayList() ; def i = 0\n"
                        + "while i < 3 then ( def x = i ; l.add(Box(() -> ( x ))) ; i = i + 1 )\n"
                        + "(l.get(0) as Box).f() + (l.get(1) as Box).f() + (l.get(2) as Box).f()"));
    }

    @Test
    public void testNamesAreHoistedToTheStartOfTheirScope() {
        // The local "a" shadows the outer one from the start of the function body.
        assertFails("def a = 1 ; def f = () -> ( def b = a ; def a = 2 ; b + a ) ; f()", "Name used before definition : a");
        assertFails("def f = () -> ( x = 1 ; def x = 2 ) ; f()", "Name used before definition : x");
        // At the top level, an implicit of the same name is shadowed too.
        assertFails("println(1) ; def println = (x : any) -> ( x )", "Name used before definition : println");
    }

    @Test
    public void testRedefinitionsAreRejectedBeforeRunning() {
        assertFails("def f = (a : int) -> ( def a = 2 ) ; 1", "Name already defined : a");
        assertFails("def f = (a : int, a : int) -> ( a ) ; 1", "Name already defined : a");
        assertFails("def i = 0 ; def f = (v : int) -> ( i < 1 ) ; while f(def x = 1) then ( i = 1 )", "Definition in a while test : x");
        // A block in the test is a scope of its own, entered on every evaluation.
        Assertions.assertDoesNotThrow(() -> interpret("def i = 0 ; while ( def x = i ; x < 2 ) then ( i = i + 1 )"));
    }

    @Test
    public void testDefinitionNestedInAnExpressionBelongsToTheEnclosingScope() throws Exception {
        Assertions.assertEquals(2, interpret("def f = (v : int) -> ( v ) ; f(def x = 1) ; x + 1"));
        Assertions.assertEquals(1, interpret("if def x = 1 then ( x ) else ( 0 )"));
    }

    @Test
    public void testDefinitionClashingWithABindingFailsAtTheStart() {
        // "assert" is an ENGINE_SCOPE binding installed by BabbleTestBase.
        assertFails("def x = 1\ndef assert = 2", "Name already defined : assert");
    }

    @Test
    public void testSelectingFromANonScope() {
        // A raw ClassCastException used to escape the interpreter here.
        assertStaticFails("def s = \"hi\" ; s.nope", "No such name in java.lang.String : nope", 16);
        assertStaticFails("def b = true ; b.value", "Not a scope : b", 16);
        assertStaticFails("def n = 1 ; n.value", "Not a scope : n", 13);
        // null is not a scope either, and used to surface as a NullPointerException.
        assertStaticFails("def n : any? = null ; n.value", "Not a scope : n", 23);
        assertStaticFails("def f = () -> ( 1 ) ; f().value", "Not a scope : f()", 23);
        assertStaticFails("def f = (x : any) : any -> ( x ) ; f(1).value", "Not a scope : f(1)", 36);
    }

    @Test
    public void testAssigningToAMemberOfANonScope() {
        assertStaticFails("def s = \"hi\" ; s.length = 1", "Not a scope : s", 16);
    }

    @Test
    public void testSelectingFromAScopeStillWorks() throws Exception {
        Assertions.assertEquals(1, interpret("def o = object ( def value = 1 ) ; o.value"));
    }

    private void assertFails(String script, String message) {
        try {
            interpret(script);
            Assertions.fail("Should report \"" + message + "\"");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: " + message
                    + " in <input> at line number 1", e.getMessage());
        }
    }

    private void assertStaticFails(String script, String message, int column) {
        try {
            interpret(script);
            Assertions.fail("Should report \"" + message + "\"");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: " + message
                    + " in <input> at line number 1 at column number " + column, e.getMessage());
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
