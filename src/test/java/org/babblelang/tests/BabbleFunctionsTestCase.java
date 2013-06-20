package org.babblelang.tests;

import org.babblelang.engine.impl.Function;

public class BabbleFunctionsTestCase extends BabbleTestBase {
    public void testFunctionLiteral() throws Exception {
        assertTrue(interpret("def add = (a:int, b:int):int -> ( a + b )") instanceof Function);
    }

    public void testFunctionCall() throws Exception {
        assertEquals(2, interpret("def add = (a:int, b:int):int -> ( a + b ) add(1,1)"));
        assertEquals("ab", interpret("def add = (a, b) -> ( a + b ) add(\"a\",\"b\")"));
    }

    public void testParameterPassing() throws Exception {
        assertEquals("ab", interpret("def add = (a, b) -> ( a + b ) add(a:\"a\",b:\"b\")"));
        assertEquals("ba", interpret("def add = (a, b) -> ( a + b ) add(b:\"a\",a:\"b\")"));

        try {
            assertEquals("ba", interpret("def add = (a, b) -> ( a + b ) add(b:\"a\")"));
            fail("Should report missing parameter");
        } catch (IllegalArgumentException e) {
            assertEquals("Missing parameter : a", e.getMessage());
        }
    }

    public void testFunctionScope() throws Exception {
        assertEquals(102, interpret("def a = 99 ; def add = (a:int, b:int):int -> ( a = a + 1 ; a + b ) ; add(1,1) + a"));
    }

    public void testClosureScope() throws Exception {
        assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = (a:int):(b:int):int -> ( (b:int):int -> ( a + b + c) ) ; def plus1 = adder(1) ; plus1(1) + plus1(2)"));
    }

    public void testClosureMemory() throws Exception {
        assertEquals(2005, interpret("def a = 99 ; def c = 1000; def adder = (a:int,d:int):(b:int):int -> ( (b:int):int -> ( a + b + c ) ) ; def plus1 = adder(1,0) ; plus1(1) + plus1(2)"));
    }

    public void testRecursion() throws Exception {
        assertEquals(120, interpret("def fac = (n:int):int -> ( if(n<=1) ( 1 ) else ( n * recurse(n-1) ) ) ; fac(5)"));
    }

    public void testMutualRecursion() throws Exception {
        assertEquals(720, interpret("def fac = (n:int):int -> ( if(n<=1) ( 1 ) else ( n * fac2(n-1) ) ) ; def fac2 = (n:int):int -> ( if(n<=1) ( 1 ) else ( n * fac(n-1) ) ) ; fac(6)"));
    }
}
