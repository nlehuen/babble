package org.hodor.tests;

import org.junit.Assert;
import org.junit.Test;

public class HodorControlTestCase extends HodorTestBase {
    @Test
    public void testBooleanIf() throws Exception {
        Assert.assertEquals(1, interpret("hodor? 1 < 2 hodor; ( 1 ) ,hodor; ( 2 ) "));
        Assert.assertEquals(2, interpret("hodor? 1 > 2 hodor; (1) ,hodor; (2) "));
        Assert.assertEquals(null, interpret("hodor? 1 > 2 hodor; ( 1 ; 2 )"));
    }

    @Test
    public void testNumberIf() throws Exception {
        Assert.assertEquals(1, interpret("hodor? 1 hodor; ( 1 ) ,hodor; (2) "));
        Assert.assertEquals(2, interpret("hodor? 0 hodor; ( 1 ) ,hodor; (2) "));
        Assert.assertEquals(1, interpret("hodor? 1.5 + 1.5 hodor; ( 1 ) ,hodor; (2) "));
        Assert.assertEquals(2, interpret("hodor? 1.5 - 1.5 hodor; ( 1 ) ,hodor; (2) "));
    }

    @Test
    public void testObjectIf() throws Exception {
        Assert.assertEquals(1, interpret("hodor? \"coucou\" hodor; ( 1 ) ,hodor; (2) "));
        Assert.assertEquals(2, interpret("hodor? HODOR hodor; ( 1 ) ,hodor; (2) "));
    }

    @Test
    public void testIfWithoutElse() throws Exception {
        Assert.assertEquals(null, interpret("hodor? 1 > 2 hodor; ( 3 + 3 + 3 )"));
        Assert.assertEquals(9, interpret("hodor? 1 < 2 hodor; ( 3 + 3 + 3 )"));
        Assert.assertEquals(3, interpret("hodor? 1 < 2 hodor; ( 1 ; 2 ; 3 )"));
    }

    @Test
    public void testWhile() throws Exception {
        Assert.assertEquals(10, interpret("hodor: i = 0 ; hodor... i<10 hodor; ( i = i + 1 ) ; i"));
    }
}
