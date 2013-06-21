package org.babblelang.tests;

import org.junit.Assert;
import org.junit.Test;

public class BabbleControlTestCase extends BabbleTestBase {
    @Test
    public void testBooleanIf() throws Exception {
        Assert.assertEquals(1, interpret("if 1 < 2 then ( 1 ) else ( 2 ) "));
        Assert.assertEquals(2, interpret("if 1 > 2 then (1) else (2) "));
        Assert.assertEquals(null, interpret("if 1 > 2 then ( 1 ; 2 )"));
    }

    @Test
    public void testNumberIf() throws Exception {
        Assert.assertEquals(1, interpret("if 1 then ( 1 ) else (2) "));
        Assert.assertEquals(2, interpret("if 0 then ( 1 ) else (2) "));
        Assert.assertEquals(1, interpret("if 1.5 + 1.5 then ( 1 ) else (2) "));
        Assert.assertEquals(2, interpret("if 1.5 - 1.5 then ( 1 ) else (2) "));
    }

    @Test
    public void testObjectIf() throws Exception {
        Assert.assertEquals(1, interpret("if \"coucou\" then ( 1 ) else (2) "));
        Assert.assertEquals(2, interpret("if null then ( 1 ) else (2) "));
    }

    @Test
    public void testIfWithoutElse() throws Exception {
        Assert.assertEquals(null, interpret("if 1 > 2 then ( 3 + 3 + 3 )"));
        Assert.assertEquals(9, interpret("if 1 < 2 then ( 3 + 3 + 3 )"));
        Assert.assertEquals(3, interpret("if 1 < 2 then ( 1 ; 2 ; 3 )"));
    }

    @Test
    public void testWhile() throws Exception {
        Assert.assertEquals(10, interpret("def i = 0 ; while i<10 then ( i = i + 1 ) ; i"));
    }
}
