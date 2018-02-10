package org.babblelang.tests;

import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;
import org.junit.Assert;

public class AssertFunction extends org.babblelang.engine.impl.natives.AssertFunction {
    public Boolean call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        String message = (String) scope.get("message").get();
        boolean test = (Boolean) scope.get("test").get();
        if (message == null) {
            Assert.assertTrue("Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText(), test);
        } else {
            Assert.assertTrue(message, test);
        }
        return true;
    }
}
