package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * "==" uses Java's equals() for everything but null and numbers.
 */
public class BabbleEqualityTestCase extends BabbleTestBase {
    @Test
    public void testJavaObjectsCompareByEquals() throws Exception {
        Assertions.assertEquals(true, interpret("def a = java.util.ArrayList() ; def b = java.util.ArrayList() ; a.add(1) ; b.add(1) ; a == b"));
        Assertions.assertEquals(false, interpret("def a = java.util.ArrayList() ; def b = java.util.ArrayList() ; a.add(1) ; b.add(2) ; a == b"));
        Assertions.assertEquals(true, interpret("def a = java.util.ArrayList() ; a.add(1) ; a.subList(0, 1) == a.subList(0, 1)"));
    }

    @Test
    public void testNumbersStillCompareAcrossTypes() throws Exception {
        Assertions.assertEquals(true, interpret("1 == 1.0"));
        Assertions.assertEquals(true, interpret("java.lang.Long.valueOf(\"3\") == 3"));
    }

    @Test
    public void testFunctionsCompareByIdentity() throws Exception {
        Assertions.assertEquals(true, interpret("def f = () -> ( 1 ) ; f == f"));
        Assertions.assertEquals(false, interpret("def mk = () -> ( () -> ( 1 ) ) ; mk() == mk()"));
    }

    @Test
    public void testSelectingAMethodTwiceGivesEqualValues() throws Exception {
        Assertions.assertEquals(true, interpret("def mk = () -> ( def m = () -> ( 1 ) ; object ( def v = 1 ) ) ; def o = mk() ; o.m == o.m"));
        Assertions.assertEquals(false, interpret("def mk = () -> ( def m = () -> ( 1 ) ; object ( def v = 1 ) ) ; mk().m == mk().m"));
    }
}
