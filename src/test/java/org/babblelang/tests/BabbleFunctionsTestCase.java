package org.babblelang.tests;

import org.babblelang.engine.impl.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleFunctionsTestCase extends BabbleTestBase {
    @Test
    public void testFunctionLiteral() throws Exception {
        Assertions.assertTrue(interpret("def add = (a:int, b:int):int -> ( a + b )") instanceof Function);
    }

    @Test
    public void testFunctionCall() throws Exception {
        Assertions.assertEquals(2, interpret("def add = (a:int, b:int):int -> ( a + b ) ; add(1,1)"));
        Assertions.assertEquals("ab", interpret("def add = (a, b) -> ( a + b ) ; add(\"a\",\"b\")"));
    }

    @Test
    public void testParameterPassing() throws Exception {
        Assertions.assertEquals("ab", interpret("def add = (a, b) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assertions.assertEquals("ba", interpret("def add = (a, b) -> ( a + b ) ; add(b:\"a\",a:\"b\")"));

        try {
            Assertions.assertEquals("ba", interpret("def add = (a, b) -> ( a + b ) ; add(b:\"a\")"));
            Assertions.fail("Should report missing parameter");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Missing parameter : a in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testCallableError() {
        try {
            Assertions.assertEquals(null, interpret("def add = (a, b) -> ( a + b ) ; add(a:\"a\",b:\"b\")(\"foobar\")"));
            Assertions.fail("Should report not callable expression");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: add(a:\"a\",b:\"b\") is not callable in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithDefaults() throws Exception {
        Assertions.assertEquals("ab", interpret("def add = (a=\"hello\", b) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assertions.assertEquals("hellob", interpret("def add = (a=\"hello\", b) -> ( a + b ) ; add(b:\"b\")"));
        Assertions.assertEquals("ahello", interpret("def add = (a, b=\"hello\") -> ( a + b ) ; add(\"a\")"));
        Assertions.assertEquals("hello world", interpret("def add = (a=\"hello\", b=\" world\") -> ( a + b ) ; add()"));

        try {
            Assertions.assertEquals("ba", interpret("def add = (a=\"b\", b) -> ( a + b ) ; add(a:\"a\")"));
            Assertions.fail("Should report \"Missing parameter : b\"");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: Missing parameter : b in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithFunctionDefaults() throws Exception {
        Assertions.assertEquals(1, interpret("def apply = (a, f=(v) -> (v)) -> ( f(a) ) ; apply(1)"));
        Assertions.assertEquals(2, interpret("def apply = (a, f=(v) -> (v+1)) -> ( f(a) ) ; apply(1)"));
    }

    @Test
    public void testParameterDefaultsUseTheClosureScope() throws Exception {
        // Default values belong to the function's closure, not to the call site.
        Assertions.assertEquals(1, interpret("def x = 1 ; def f = (p = x) -> ( p ) ; def g = () -> ( def x = 2 ; f() ) ; g()"));

        // The call site does not even need to declare the name.
        Assertions.assertEquals(1, interpret("def make = () -> ( def x = 1 ; (p = x) -> ( p ) ) ; def f = make() ; f()"));

        // A default value may still refer to the parameters bound before it.
        Assertions.assertEquals(3, interpret("def add = (a, b = a + 1) -> ( a + b ) ; add(1)"));
    }

    @Test
    public void testReturn() throws Exception {
        // A return must abandon the rest of the body, not just evaluate its operand.
        Assertions.assertEquals("pos", interpret("def f = (n) -> ( if n > 0 then ( return \"pos\" ) ; \"neg\" ) ; f(5)"));
        Assertions.assertEquals("neg", interpret("def f = (n) -> ( if n > 0 then ( return \"pos\" ) ; \"neg\" ) ; f(0)"));

        // As the last statement of a block, it is equivalent to the bare expression.
        Assertions.assertEquals(3, interpret("def f = (a, b) -> ( return a + b ) ; f(1,2)"));

        // It unwinds out of a loop...
        Assertions.assertEquals(3, interpret("def f = () -> ( def i = 0 ; while i < 10 then ( i = i + 1 ; if i == 3 then ( return i ) ) ; 0 - 1 ) ; f()"));

        // ... and out of arbitrarily nested blocks, but only up to the innermost function.
        Assertions.assertEquals(1, interpret("def f = () -> ( ( ( return 1 ) ) ; 2 ) ; f()"));
        Assertions.assertEquals(2, interpret("def outer = () -> ( def inner = () -> ( return 1 ) ; inner() + 1 ) ; outer()"));

        // The returned value may be null.
        Assertions.assertNull(interpret("def f = () -> ( return null ; 1 ) ; f()"));
    }

    @Test
    public void testReturnOutsideOfFunction() {
        try {
            interpret("def x = 1 ; return x ; 2");
            Assertions.fail("Should report a return with no enclosing function");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.engine.BabbleException: return outside of a function in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testFunctionScope() throws Exception {
        Assertions.assertEquals(102, interpret("def a = 99 ; def add = (a:int, b:int):int -> ( a = a + 1 ; a + b ) ; add(1,1) + a"));
    }

    @Test
    public void testClosureScope() throws Exception {
        Assertions.assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = (a:int):(b:int):int -> ( (b:int):int -> ( a + b + c) ) ; def plus1 = adder(1) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testClosureMemory() throws Exception {
        Assertions.assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = (a:int,d:int):(b:int):int -> ( (b:int):int -> ( a + b + c ) ) ; def plus1 = adder(1,0) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testRecursion() throws Exception {
        Assertions.assertEquals(120, interpret("def fac = (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * recurse(n-1) ) ) ; fac(5)"));
    }

    @Test
    public void testMutualRecursion() throws Exception {
        Assertions.assertEquals(720, interpret("def fac = (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * fac2(n-1) ) ) ; def fac2 = (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * fac(n-1) ) ) ; fac(6)"));
    }

    @Test
    public void testUnknownNamedParameter() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = (a) -> ( a ) ; f(a:1, zzz:99)"));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: No such parameter : zzz in <input> at line number 1", e.getMessage());

        // A typo is most dangerous when the real parameter has a default : the call
        // used to succeed quietly, using the default and dropping the supplied value.
        ScriptException typo = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = (verbose = false) -> ( verbose ) ; f(verbsoe:true)"));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: No such parameter : verbsoe in <input> at line number 1", typo.getMessage());
    }

    @Test
    public void testSeveralUnknownNamedParameters() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = (a) -> ( a ) ; f(a:1, yyy:2, zzz:3)"));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: No such parameter : yyy, zzz in <input> at line number 1", e.getMessage());
    }

    @Test
    public void testTooManyPositionalParameters() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = (a) -> ( a ) ; f(1, 2)"));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: Too many parameters : 1 declared, 2 given in <input> at line number 1", e.getMessage());

        ScriptException none = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = () -> ( 1 ) ; f(1)"));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: Too many parameters : 0 declared, 1 given in <input> at line number 1", none.getMessage());
    }

    @Test
    public void testWellFormedCallsStillBind() throws Exception {
        // The new check must not reject any call that was already valid.
        Assertions.assertEquals(3, interpret("def f = (a, b) -> ( a + b ) ; f(1, 2)"));
        Assertions.assertEquals(3, interpret("def f = (a, b) -> ( a + b ) ; f(a:1, b:2)"));
        Assertions.assertEquals(3, interpret("def f = (a, b) -> ( a + b ) ; f(b:2, a:1)"));
        Assertions.assertEquals(3, interpret("def f = (a, b = 2) -> ( a + b ) ; f(1)"));
        Assertions.assertEquals(3, interpret("def f = (a = 1, b = 2) -> ( a + b ) ; f()"));
    }
}
