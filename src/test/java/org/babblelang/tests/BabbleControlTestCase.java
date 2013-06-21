package org.babblelang.tests;

public class BabbleControlTestCase extends BabbleTestBase {
    public void testBooleanIf() throws Exception {
        assertEquals(1, interpret("if 1 < 2 then ( 1 ) else ( 2 ) "));
        assertEquals(2, interpret("if 1 > 2 then (1) else (2) "));
        assertEquals(null, interpret("if 1 > 2 then ( 1 ; 2 )"));
    }

    public void testNumberIf() throws Exception {
        assertEquals(1, interpret("if 1 then ( 1 ) else (2) "));
        assertEquals(2, interpret("if 0 then ( 1 ) else (2) "));
        assertEquals(1, interpret("if 1.5 + 1.5 then ( 1 ) else (2) "));
        assertEquals(2, interpret("if 1.5 - 1.5 then ( 1 ) else (2) "));
    }

    public void testObjectIf() throws Exception {
        assertEquals(1, interpret("if \"coucou\" then ( 1 ) else (2) "));
        assertEquals(2, interpret("if null then ( 1 ) else (2) "));
    }

    public void testIfWithoutElse() throws Exception {
        assertEquals(null, interpret("if 1 > 2 then ( 3 + 3 + 3 )"));
        assertEquals(9, interpret("if 1 < 2 then ( 3 + 3 + 3 )"));
        assertEquals(3, interpret("if 1 < 2 then ( 1 ; 2 ; 3 )"));
    }

    public void testWhile() throws Exception {
        assertEquals(10, interpret("def i = 0 ; while i<10 then ( i = i + 1 ) ; i"));
    }
}
