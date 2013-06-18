package org.babblelang.tests;

public class BabbleScopesTestCase extends BabbleTestBase {
    public void testPackageScope() throws Exception {
        assertEquals(2, interpret("package test ( def value = 1 ) test.value + 1"));
    }
}
