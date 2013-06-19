package org.babblelang.tests;

public class BabbleControlTestCase extends BabbleTestBase {
    public void testBooleanIf() throws Exception {
        assertEquals(1, interpret("if 1 < 2 ( 1 ) else (2) "));
        assertEquals(2, interpret("if 1 > 2 ( 1 ) else (2) "));
        assertEquals(null, interpret("if 1 > 2 ( 1 2 )"));
    }

    public void testNumberIf() throws Exception {
        assertEquals(1, interpret("if 1 ( 1 ) else (2) "));
        assertEquals(2, interpret("if 0 ( 1 ) else (2) "));
        assertEquals(1, interpret("if 1.5 + 1.5 ( 1 ) else (2) "));
        assertEquals(2, interpret("if 1.5 - 1.5 ( 1 ) else (2) "));
    }

    public void testObjectIf() throws Exception {
        assertEquals(1, interpret("if \"coucou\" ( 1 ) else (2) "));
        assertEquals(2, interpret("if null ( 1 ) else (2) "));
    }

    public void testFaultyIf() throws Exception {
        // TODO : fix ambiguous if statement without else
        // assertEquals(null, interpret("if 1 > 2 ( 3 + 3 + 3)"));
    }

    public void testWhile() throws Exception {
        assertEquals(10, interpret("def i = 0 ; while(i<10) ( i = i + 1 ) ; i"));
    }
}
