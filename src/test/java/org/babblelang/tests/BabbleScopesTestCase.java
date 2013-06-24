package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class BabbleScopesTestCase extends BabbleTestBase {
    @Test
    public void testPackageScope() throws Exception {
        Assert.assertEquals(2, interpret("package test ( def value = 1 ) ; test.value + 1"));
        Assert.assertEquals(6, interpret("package test ( def value = 1 ; package test2 ( def value = 3 ) ; value = value + 1 ) ; test.value + test.test2.value + 1"));
    }

    @Test
    public void testIncr() throws Exception {
        Assert.assertEquals(2, interpret("def i = 0 ; ( i = i + 1 ) ; i = i + 1 ; i"));
    }

    @Test
    public void testPrecedence() throws Exception {
        Assert.assertEquals(2, interpret("def value = 1; value = value + 1 ; value"));
        Assert.assertEquals(2, interpret("package test (def value = 1) ; def value = test.value + 1 ; value"));
        Assert.assertEquals(1, interpret("package test (def value = 1) ; def value ; value = test.value ; value"));
    }

    @Test
    public void testDef() throws Exception {
        Assert.assertEquals(null, interpret("def i"));
        Assert.assertEquals(null, interpret("def i ; i"));
        Assert.assertEquals(1, interpret("def i ; i = 1"));
        Assert.assertEquals(1, interpret("def i ; i = 1 ; i"));
    }

    @Test
    public void testAssign() throws Exception {
        Assert.assertEquals(0, interpret("def i = 0 ; i"));
        Assert.assertEquals(1, interpret("def i = 0 ; i = 1"));
        Assert.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1"));
        Assert.assertEquals(2, interpret("def i = 0 ; i = 1 ; i = i + 1 ; i"));
    }

    @Ignore("Block scope is not implemented yet")
    @Test
    public void testBlockScope() throws Exception {
        try {
            Assert.assertEquals(2, interpret("def a = 1 ; (def b = 2) ; a ; b"));
            Assert.fail("Should report \"No such key : b\"");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("No such key : b", e.getMessage());
        }

        try {
            Assert.assertEquals("ba", interpret("def a = 1 ; (def b = 2) ; a = 1 ; b = 5"));
            Assert.fail("Should report \"No such key : b\"");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("No such key : b", e.getMessage());
        }

        try {
            Assert.assertEquals("ba", interpret("def a = 1 ; def a = 2"));
            Assert.fail("Should report \"Key already defined : a\"");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Key already defined : a", e.getMessage());
        }
    }

    @Test
    public void testBlockResult() throws Exception {
        Assert.assertEquals(6, interpret("( 1 + 2 + 3 )"));
        Assert.assertEquals(3, interpret("( 1 ; 2 ; 3 )"));
        Assert.assertEquals(3, interpret("( 1  2 3 )"));
    }
}
