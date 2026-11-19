package org.babblelang.tests;

import org.babblelang.runtime.BabbleFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleFunctionsTestCase extends BabbleTestBase {
    @Test
    public void testFunctionLiteral() throws Exception {
        Assertions.assertTrue(interpret("def add = fn (a:int, b:int):int -> ( a + b )") instanceof BabbleFunction);
    }

    @Test
    public void testFunctionCall() throws Exception {
        Assertions.assertEquals(2, interpret("def add = fn (a:int, b:int):int -> ( a + b ) ; add(1,1)"));
        Assertions.assertEquals("ab", interpret("def add = fn (a:string, b:string):string -> ( a + b ) ; add(\"a\",\"b\")"));
    }

    @Test
    public void testParameterPassing() throws Exception {
        Assertions.assertEquals("ab", interpret("def add = fn (a:string, b:string) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assertions.assertEquals("ba", interpret("def add = fn (a:string, b:string) -> ( a + b ) ; add(b:\"a\",a:\"b\")"));

        try {
            Assertions.assertEquals("ba", interpret("def add = fn (a:string, b:string) -> ( a + b ) ; add(b:\"a\")"));
            Assertions.fail("Should report missing parameter");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Missing parameter : a in <input> at line number 1 at column number 50", e.getMessage());
        }
    }

    @Test
    public void testCallableError() {
        try {
            Assertions.assertEquals(null, interpret("def add = fn (a:string, b:string) -> ( a + b ) ; add(a:\"a\",b:\"b\")(\"foobar\")"));
            Assertions.fail("Should report not callable expression");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: add(a:\"a\",b:\"b\") is not callable in <input> at line number 1 at column number 50", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithDefaults() throws Exception {
        Assertions.assertEquals("ab", interpret("def add = fn (a=\"hello\", b:string) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assertions.assertEquals("hellob", interpret("def add = fn (a=\"hello\", b:string) -> ( a + b ) ; add(b:\"b\")"));
        Assertions.assertEquals("ahello", interpret("def add = fn (a:string, b=\"hello\") -> ( a + b ) ; add(\"a\")"));
        Assertions.assertEquals("hello world", interpret("def add = fn (a=\"hello\", b=\" world\") -> ( a + b ) ; add()"));

        try {
            Assertions.assertEquals("ba", interpret("def add = fn (a=\"b\", b:string) -> ( a + b ) ; add(a:\"a\")"));
            Assertions.fail("Should report \"Missing parameter : b\"");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Missing parameter : b in <input> at line number 1 at column number 47", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithFunctionDefaults() throws Exception {
        Assertions.assertEquals(1, interpret("def apply = fn (a:int, f:fn (v:int):int = fn (v:int) -> (v)) -> ( f(a) ) ; apply(1)"));
        Assertions.assertEquals(2, interpret("def apply = fn (a:int, f:fn (v:int):int = fn (v:int) -> (v+1)) -> ( f(a) ) ; apply(1)"));
    }

    @Test
    public void testParameterDefaultsUseTheClosureScope() throws Exception {
        // Default values belong to the function's closure, not to the call site.
        Assertions.assertEquals(1, interpret("def x = 1 ; def f = fn (p = x) -> ( p ) ; def g = fn () -> ( def x = 2 ; f() ) ; g()"));

        // The call site does not even need to declare the name.
        Assertions.assertEquals(1, interpret("def make = fn () -> ( def x = 1 ; fn (p = x) -> ( p ) ) ; def f = make() ; f()"));

        // A default value may still refer to the parameters bound before it.
        Assertions.assertEquals(3, interpret("def add = fn (a:int, b = a + 1) -> ( a + b ) ; add(1)"));
    }

    @Test
    public void testReturn() throws Exception {
        // A return must abandon the rest of the body, not just evaluate its operand.
        Assertions.assertEquals("pos", interpret("def f = fn (n:int):string -> ( if n > 0 then ( return \"pos\" ) ; \"neg\" ) ; f(5)"));
        Assertions.assertEquals("neg", interpret("def f = fn (n:int):string -> ( if n > 0 then ( return \"pos\" ) ; \"neg\" ) ; f(0)"));

        // As the last statement of a block, it is equivalent to the bare expression.
        Assertions.assertEquals(3, interpret("def f = fn (a:int, b:int) -> ( return a + b ) ; f(1,2)"));

        // It unwinds out of a loop...
        Assertions.assertEquals(3, interpret("def f = fn ():int -> ( def i = 0 ; while i < 10 then ( i = i + 1 ; if i == 3 then ( return i ) ) ; 0 - 1 ) ; f()"));

        // ... and out of arbitrarily nested blocks, but only up to the innermost function.
        Assertions.assertEquals(1, interpret("def f = fn ():int -> ( ( ( return 1 ) ) ; 2 ) ; f()"));
        Assertions.assertEquals(2, interpret("def outer = fn ():int -> ( def inner = fn ():int -> ( return 1 ) ; inner() + 1 ) ; outer()"));

        // The returned value may be null.
        Assertions.assertNull(interpret("def f = fn ():any? -> ( return null ; 1 ) ; f()"));
    }

    @Test
    public void testReturnOutsideOfFunction() {
        try {
            interpret("def x = 1 ; return x ; 2");
            Assertions.fail("Should report a return with no enclosing function");
        } catch (ScriptException e) {
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: return outside of a function in <input> at line number 1", e.getMessage());
        }
        // Rejected before running, so even when it would never run.
        assertRejected("if false then ( return 1 ) ; 2", "return outside of a function", 1);
        assertRejected("def o = object ( def v = ( return 2 ) )", "return in a class body", 1);
    }

    @Test
    public void testReturnInsideObjectBodyIsRejected() throws Exception {
        // An object body is a constructor : a return there has nothing to return from.
        assertRejected("def g = fn () -> ( object ( def v = ( return 2 ) ) ; 3 ) ; g()", "return in a class body", 1);
    }

    @Test
    public void testReturnInAParameterDefault() throws Exception {
        assertRejected("def h = fn (a:int, b = ( return 99 )) -> ( a ) ; 1", "return in a parameter default", 1);
        // A function literal nested in a default is a function of its own.
        Assertions.assertEquals(1, interpret("def k = fn (a:int, cb = fn () -> ( return 1 )) -> ( cb() ) ; k(0)"));
    }

    @Test
    public void testReturnInTheMiddleOfAnExpression() throws Exception {
        Assertions.assertEquals(2, interpret("def g = fn (a:int, b:int) -> ( a + b ) ; def f = fn ():int -> ( g(1, return 2) ) ; f()"));
        Assertions.assertEquals(5, interpret("def f = fn ():int -> ( 1 + ( return 5 ) ) ; f()"));
        Assertions.assertEquals(7, interpret("def f = fn ():int -> ( while true then ( return 7 ) ; 0 ) ; f()"));
        Assertions.assertEquals(8, interpret("def f = fn ():int -> ( while return 8 then ( 1 ) ; 0 ) ; f()"));
    }

    private void assertRejected(String script, String message, int line) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(script));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: " + message + " in <input> at line number " + line, e.getMessage());
    }

    @Test
    public void testBindingErrorsAreReportedAtTheCall() {
        ScriptException missing = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int) -> (\n  a\n)\n\nf()"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Missing parameter : a in <input> at line number 5 at column number 1", missing.getMessage());

        // Even after a default value on another line was evaluated.
        ScriptException leftover = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int,\n b = 1 +\n 1) -> (\n  a\n)\n\nf(1, c: 2)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such parameter : c in <input> at line number 7 at column number 1", leftover.getMessage());

        // An error inside a default value is reported where it is.
        ScriptException inDefault = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int,\n b:int = a + (nosuchname as int)) -> (\n  a\n)\n\nf(1)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such name : nosuchname in <input> at line number 2", inDefault.getMessage());
    }

    @Test
    public void testErrorsAreReportedAtTheirLine() {
        // An operator's line is the line where its left operand starts.
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:any) -> (\n  def b = 1\n  a +\n    \"x\" -\n 2\n)\nf(1)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not a number : a in <input> at line number 3 at column number 3", e.getMessage());

        ScriptException deeper = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int) -> (\n  def b = 1\n  b.x\n)\n\nf(1)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Not a scope : b in <input> at line number 3 at column number 3", deeper.getMessage());
    }

    @Test
    public void testNamedArgumentsUseUpTheirPosition() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int, b:int) -> ( a ) ; f(1, a: 2)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Missing parameter : b in <input> at line number 1 at column number 38", e.getMessage());
    }

    @Test
    public void testFunctionScope() throws Exception {
        Assertions.assertEquals(102, interpret("def a = 99 ; def add = fn (a:int, b:int):int -> ( a = a + 1 ; a + b ) ; add(1,1) + a"));
    }

    @Test
    public void testClosureScope() throws Exception {
        Assertions.assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = fn (a:int):fn (b:int):int -> ( fn (b:int):int -> ( a + b + c) ) ; def plus1 = adder(1) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testClosureMemory() throws Exception {
        Assertions.assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = fn (a:int,d:int):fn (b:int):int -> ( fn (b:int):int -> ( a + b + c ) ) ; def plus1 = adder(1,0) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testRecursion() throws Exception {
        Assertions.assertEquals(120, interpret("def fac = fn (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * recurse(n-1) ) ) ; fac(5)"));
    }

    @Test
    public void testRecursionInAMethodKeepsTheReceiver() throws Exception {
        // "recurse" used to evaluate to the raw function rather than to the bound method,
        // so the recursive call lost its receiver and "this" was undefined inside it.
        Assertions.assertEquals(6, interpret(
                "def o = object (\n"
                        + "  def total = 0\n"
                        + "  def down = fn mut (n:int):int -> (\n"
                        + "    if n < 1 then ( this.total ) else ( this.total = this.total + n ; recurse(n - 1) )\n"
                        + "  )\n"
                        + ")\n"
                        + "o.down(3)\n"));
    }

    @Test
    public void testMutualRecursion() throws Exception {
        Assertions.assertEquals(720, interpret("def fac = fn (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * fac2(n-1) ) ) ; def fac2 = fn (n:int):int -> ( if(n<=1) then ( 1 ) else ( n * fac(n-1) ) ) ; fac(6)"));
    }

    @Test
    public void testUnknownNamedParameter() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int) -> ( a ) ; f(a:1, zzz:99)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such parameter : zzz in <input> at line number 1 at column number 31", e.getMessage());

        // A typo is most dangerous when the real parameter has a default : the call
        // used to succeed quietly, using the default and dropping the supplied value.
        ScriptException typo = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (verbose = false) -> ( verbose ) ; f(verbsoe:true)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such parameter : verbsoe in <input> at line number 1 at column number 47", typo.getMessage());
    }

    @Test
    public void testSeveralUnknownNamedParameters() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int) -> ( a ) ; f(a:1, yyy:2, zzz:3)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such parameter : yyy, zzz in <input> at line number 1 at column number 31", e.getMessage());
    }

    @Test
    public void testTooManyPositionalParameters() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn (a:int) -> ( a ) ; f(1, 2)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Too many parameters : 1 declared, 2 given in <input> at line number 1 at column number 31", e.getMessage());

        ScriptException none = Assertions.assertThrows(ScriptException.class,
                () -> interpret("def f = fn () -> ( 1 ) ; f(1)"));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: Too many parameters : 0 declared, 1 given in <input> at line number 1 at column number 26", none.getMessage());
    }

    @Test
    public void testWellFormedCallsStillBind() throws Exception {
        // The new check must not reject any call that was already valid.
        Assertions.assertEquals(3, interpret("def f = fn (a:int, b:int) -> ( a + b ) ; f(1, 2)"));
        Assertions.assertEquals(3, interpret("def f = fn (a:int, b:int) -> ( a + b ) ; f(a:1, b:2)"));
        Assertions.assertEquals(3, interpret("def f = fn (a:int, b:int) -> ( a + b ) ; f(b:2, a:1)"));
        Assertions.assertEquals(3, interpret("def f = fn (a:int, b = 2) -> ( a + b ) ; f(1)"));
        Assertions.assertEquals(3, interpret("def f = fn (a = 1, b = 2) -> ( a + b ) ; f()"));
    }
}
