package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BabbleControlTestCase extends BabbleTestBase {
    @Test
    public void testBooleanIf() throws Exception {
        Assertions.assertEquals(1, interpret("if 1 < 2 then ( 1 ) else ( 2 ) "));
        Assertions.assertEquals(2, interpret("if 1 > 2 then (1) else (2) "));
        Assertions.assertEquals(null, interpret("if 1 > 2 then ( 1 ; 2 )"));
    }

    @Test
    public void testNumberIf() throws Exception {
        Assertions.assertEquals(1, interpret("if 1 then ( 1 ) else (2) "));
        Assertions.assertEquals(2, interpret("if 0 then ( 1 ) else (2) "));
        Assertions.assertEquals(1, interpret("if 1.5 + 1.5 then ( 1 ) else (2) "));
        Assertions.assertEquals(2, interpret("if 1.5 - 1.5 then ( 1 ) else (2) "));
    }

    @Test
    public void testObjectIf() throws Exception {
        Assertions.assertEquals(1, interpret("if \"coucou\" then ( 1 ) else (2) "));
        Assertions.assertEquals(2, interpret("if null then ( 1 ) else (2) "));
    }

    @Test
    public void testIfWithoutElse() throws Exception {
        Assertions.assertEquals(null, interpret("if 1 > 2 then ( 3 + 3 + 3 )"));
        Assertions.assertEquals(9, interpret("if 1 < 2 then ( 3 + 3 + 3 )"));
        Assertions.assertEquals(3, interpret("if 1 < 2 then ( 1 ; 2 ; 3 )"));
    }

    @Test
    public void testWhile() throws Exception {
        Assertions.assertEquals(10, interpret("def i = 0 ; while i<10 then ( i = i + 1 ) ; i"));
    }
}
