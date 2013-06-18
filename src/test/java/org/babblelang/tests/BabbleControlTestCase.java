package org.babblelang.tests;

public class BabbleControlTestCase extends BabbleTestBase {
    public void testIf() throws Exception {
        assertEquals(1, interpret("if(1 < 2) ( 1 ) else (2) "));
        assertEquals(2, interpret("if(1 > 2) ( 1 ) else (2) "));
        // TODO : fix if statement without else
        // assertEquals(null, interpret("if(1 > 2) ( 1 )"));
    }
}
