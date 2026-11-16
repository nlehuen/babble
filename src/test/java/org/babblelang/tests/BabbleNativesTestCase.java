package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleNativesTestCase extends BabbleTestBase {
    @Test
    public void testGlobalScope() throws Exception {
        Assertions.assertEquals("ok", interpret("if STDOUT then ( \"ok\" ) else ( \"ko\") "));
        Assertions.assertEquals("ok", interpret("if print then ( \"ok\" ) else ( \"ko\") "));
        Assertions.assertEquals("ok", interpret("if println then ( \"ok\" ) else ( \"ko\") "));
    }

    @Test
    public void testNativeCallScope() throws Exception {
        Assertions.assertEquals(null, interpret("println (\"Hello\", \", world ! 1+1=\", 1 + 1)"));
    }

    // These exercise the built-in AssertFunction through "suppose", its French alias.
    // "assert" itself is shadowed by the binding BabbleTestBase installs, so it would
    // test the JUnit-flavoured subclass instead of the engine's own implementation.

    @Test
    public void testBuiltinAssertPasses() throws Exception {
        Assertions.assertEquals(true, interpret(" suppose(1 == 1) "));
        Assertions.assertEquals(true, interpret(" suppose(1 == 1, \"one is one\") "));
    }

    @Test
    public void testBuiltinAssertFails() {
        // Must fail even though the JVM may be running without -ea.
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(" suppose(1 == 2) "));
        // ANTLR's getText() concatenates tokens, so the reported expression has no spaces.
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Assertion failed at line 1 : 1==2 in <input> at line number 1", e.getMessage());
    }

    @Test
    public void testBuiltinAssertFailsWithMessage() {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(" suppose(1 == 2, \"one is not two\") "));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: one is not two in <input> at line number 1", e.getMessage());
    }

    @Test
    public void testBuiltinAssertArity() {
        if (isStrict()) {
            assertError("Missing parameter : c", " suppose() ");
            assertError("Too many parameters : 2 declared, 3 given", " suppose(true, \"a\", \"b\") ");
            return;
        }
        ScriptException none = Assertions.assertThrows(ScriptException.class, () -> interpret(" suppose() "));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: assert() called without parameters in <input> at line number 1", none.getMessage());

        ScriptException tooMany = Assertions.assertThrows(ScriptException.class, () -> interpret(" suppose(true, \"a\", \"b\") "));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: assert() called with too many parameters in <input> at line number 1", tooMany.getMessage());
    }

    @Test
    public void testBuiltinAssertRejectsNonBoolean() {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(" suppose(1) "));
        String expected = isStrict() ? "Type error : expected bool, got int" : "asserts don't rely on truth values";
        Assertions.assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    public void testNumberConversions() throws Exception {
        Assertions.assertEquals(2, interpret(" int(2.7) "));
        Assertions.assertEquals(-2, interpret(" int(-2.7) "));
        Assertions.assertEquals(42, interpret(" int(\"42\") "));
        Assertions.assertEquals(3.0, interpret(" double(3) "));
        Assertions.assertEquals(-0.5, interpret(" double(\"-0.5\") "));
    }

    @Test
    public void testNumberConversionErrors() {
        assertError("Out of the int range : 1/0 = Infinity", " int(1 / 0) ");
        assertError("Out of the int range : x = 3.0E9", " def x = 3.0E9 ; int(x) ");
        assertError("Not an int : \"2.5\"", " int(\"2.5\") ");
        assertError("Not a double : \"x\"", " double(\"x\") ");
        assertError("Not a number : true", " int(true) ");
        assertError(isStrict() ? "Missing parameter : value" : "int() takes 1 argument, 0 given", " def f = int ; f = f ; f() ");
    }

    private void assertError(String message, String source) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(source), source);
        Assertions.assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
