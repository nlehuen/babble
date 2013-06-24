package org.babblelang.tests;

import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.parser.BabbleParser;
import org.junit.Assert;

public class AssertFunction extends org.babblelang.engine.impl.natives.AssertFunction {
    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        String message = (String) resolver.get("message");
        boolean test = (Boolean) resolver.get("test");
        if (message == null) {
            Assert.assertTrue("Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText(), test);
        } else {
            Assert.assertTrue(message, test);
        }
        return true;
    }
}
