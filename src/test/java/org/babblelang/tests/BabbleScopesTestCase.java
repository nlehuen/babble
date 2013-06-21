package org.babblelang.tests;

public class BabbleScopesTestCase extends BabbleTestBase {
    public void testPackageScope() throws Exception {
        assertEquals(2, interpret("package test ( def value = 1 ) ; test.value + 1"));
        assertEquals(6, interpret("package test ( def value = 1 ; package test2 ( def value = 3 ) ; value = value + 1 ) ; test.value + test.test2.value + 1"));
    }

    public void testIncr() throws Exception {
        assertEquals(2, interpret("def i = 0 ; ( i = i + 1 ) ; i = i + 1 ; i"));
    }

    public void testDef() throws Exception {
        assertEquals(null, interpret("def i"));
        assertEquals(null, interpret("def i ; i"));
        assertEquals(1, interpret("def i ; i = 1"));
        assertEquals(1, interpret("def i ; i = 1 ; i"));
    }

    public void testAssign() throws Exception {
        assertEquals(0, interpret("def i = 0 ; i"));
        assertEquals(1, interpret("def i = 0 ; i = 1"));
        assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1"));
        assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1 ; i"));
    }

    public void testBlockScope() throws Exception {
        try {
            assertEquals("ba", interpret("def a = 1 ; (def b = 2) ; a ; b"));
            fail("Should report bad identifier");
        } catch (IllegalArgumentException e) {
            assertEquals("No such key : b", e.getMessage());
        }

        try {
            assertEquals("ba", interpret("def a = 1 ; (def b = 2) ; a = 1 ; b = 5"));
            fail("Should report bad identifier");
        } catch (IllegalArgumentException e) {
            assertEquals("No such key : b", e.getMessage());
        }

        try {
            assertEquals("ba", interpret("def a = 1 ; def a = 2"));
            fail("Should report double definition");
        } catch (IllegalArgumentException e) {
            assertEquals("Key already defined : a", e.getMessage());
        }
    }

    public void testBlockResult() throws Exception {
        assertEquals(6, interpret("( 1 + 2 + 3 )"));
    }
}
