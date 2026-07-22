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
}
