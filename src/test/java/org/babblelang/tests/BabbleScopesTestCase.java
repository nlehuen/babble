package org.babblelang.tests;

public class BabbleScopesTestCase extends BabbleTestBase {
    public void testPackageScope() throws Exception {
        assertEquals(2, interpret("package test ( def value = 1 ) test.value + 1"));
    }

    public void testIncr() throws Exception {
        assertEquals(2, interpret("def i = 0 ( i = i + 1 ) ( def i = i + 1 ) i = i + 1 i"));
    }

    public void testBlockScope() throws Exception {
        assertEquals(6, interpret("package test ( def value = 1 package test2 ( def value = 3 ) value = value + 1 ) test.value + test.test2.value + 1"));
    }

    public void testBlockResult() throws Exception {
        assertEquals(6, interpret("( 1 + 2 + 3 )"));
    }
}
