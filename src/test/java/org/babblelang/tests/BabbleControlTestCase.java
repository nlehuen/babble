package org.babblelang.tests;

public class BabbleControlTestCase extends BabbleTestBase {
    public void testIf() throws Exception {
        assertEquals(1, interpret("if(1 < 2) ( 1 ) else (2) "));
        assertEquals(2, interpret("if(1 > 2) ( 1 ) else (2) "));
        // TODO : fix if statement without else
        // assertEquals(null, interpret("if(1 > 2) ( 1 )"));
    }

    public void testWhile() throws Exception {
        assertEquals(10, interpret("def i = 0 while(i<10) ( i = i + 1 ) i"));
    }
}
