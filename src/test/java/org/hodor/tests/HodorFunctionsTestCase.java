package org.hodor.tests;

import org.hodor.engine.impl.Function;
import org.junit.Assert;
import org.junit.Test;

import javax.script.ScriptException;

public class HodorFunctionsTestCase extends HodorTestBase {
    @Test
    public void testFunctionLiteral() throws Exception {
        Assert.assertTrue(interpret("hodor: add = (a:int, b:int):int -> ( a + b )") instanceof Function);
    }

    @Test
    public void testFunctionCall() throws Exception {
        Assert.assertEquals(2, interpret("hodor: add = (a:int, b:int):int -> ( a + b ) ; add(1,1)"));
        Assert.assertEquals("ab", interpret("hodor: add = (a, b) -> ( a + b ) ; add(\"a\",\"b\")"));
    }

    @Test
    public void testParameterPassing() throws Exception {
        Assert.assertEquals("ab", interpret("hodor: add = (a, b) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assert.assertEquals("ba", interpret("hodor: add = (a, b) -> ( a + b ) ; add(b:\"a\",a:\"b\")"));

        try {
            Assert.assertEquals("ba", interpret("hodor: add = (a, b) -> ( a + b ) ; add(b:\"a\")"));
            Assert.fail("Should report missing parameter");
        } catch (ScriptException e) {
            Assert.assertEquals("org.hodor.engine.BabbleException: Missing parameter : a in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testCallableError() throws Exception {
        try {
            Assert.assertEquals(null, interpret("hodor: add = (a, b) -> ( a + b ) ; add(a:\"a\",b:\"b\")(\"foobar\")"));
            Assert.fail("Should report not callable expression");
        } catch (ScriptException e) {
            Assert.assertEquals("org.hodor.engine.BabbleException: add(a:\"a\",b:\"b\") is not callable in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithDefaults() throws Exception {
        Assert.assertEquals("ab", interpret("hodor: add = (a=\"hello\", b) -> ( a + b ) ; add(a:\"a\",b:\"b\")"));
        Assert.assertEquals("hellob", interpret("hodor: add = (a=\"hello\", b) -> ( a + b ) ; add(b:\"b\")"));
        Assert.assertEquals("ahello", interpret("hodor: add = (a, b=\"hello\") -> ( a + b ) ; add(\"a\")"));
        Assert.assertEquals("hello world", interpret("hodor: add = (a=\"hello\", b=\" world\") -> ( a + b ) ; add()"));

        try {
            Assert.assertEquals("ba", interpret("hodor: add = (a=\"b\", b) -> ( a + b ) ; add(a:\"a\")"));
            Assert.fail("Should report \"Missing parameter : b\"");
        } catch (ScriptException e) {
            Assert.assertEquals("org.hodor.engine.BabbleException: Missing parameter : b in <input> at line number 1", e.getMessage());
        }
    }

    @Test
    public void testParameterPassingWithFunctionDefaults() throws Exception {
        Assert.assertEquals(1, interpret("hodor: apply = (a, f=(v) -> (v)) -> ( f(a) ) ; apply(1)"));
        Assert.assertEquals(2, interpret("hodor: apply = (a, f=(v) -> (v+1)) -> ( f(a) ) ; apply(1)"));
    }

    @Test
    public void testFunctionScope() throws Exception {
        Assert.assertEquals(102, interpret("hodor: a = 99 ; hodor: add = (a:int, b:int):int -> ( a = a + 1 ; a + b ) ; add(1,1) + a"));
    }

    @Test
    public void testClosureScope() throws Exception {
        Assert.assertEquals(2005, interpret("hodor: a = 99 ; hodor: c = 1000; hodor: adder = (a:int):(b:int):int -> ( (b:int):int -> ( a + b + c) ) ; hodor: plus1 = adder(1) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testClosureMemory() throws Exception {
        Assert.assertEquals(2005, interpret("hodor: a = 99 ; hodor: c = 1000; hodor: adder = (a:int,d:int):(b:int):int -> ( (b:int):int -> ( a + b + c ) ) ; hodor: plus1 = adder(1,0) ; plus1(1) + plus1(2)"));
    }

    @Test
    public void testRecursion() throws Exception {
        Assert.assertEquals(120, interpret("hodor: fac = (n:int):int -> ( hodor?(n<=1) hodor; ( 1 ) ,hodor; ( n * HOOOODOR(n-1) ) ) ; fac(5)"));
    }

    @Test
    public void testMutualRecursion() throws Exception {
        Assert.assertEquals(720, interpret("hodor: fac = (n:int):int -> ( hodor?(n<=1) hodor; ( 1 ) ,hodor; ( n * fac2(n-1) ) ) ; hodor: fac2 = (n:int):int -> ( hodor?(n<=1) hodor; ( 1 ) ,hodor; ( n * fac(n-1) ) ) ; fac(6)"));
    }
}
