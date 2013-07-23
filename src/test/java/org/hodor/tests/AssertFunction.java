package org.hodor.tests;

import org.hodor.engine.impl.Interpreter;
import org.hodor.engine.impl.Scope;
import org.hodor.parser.HodorParser;
import org.junit.Assert;

public class AssertFunction extends org.hodor.engine.impl.natives.AssertFunction {
    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        String message = (String) scope.get("message").get();
        boolean test = (Boolean) scope.get("test").get();
        if (message == null) {
            Assert.assertTrue("HODOR HODORRRR! " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText(), test);
        } else {
            Assert.assertTrue(message, test);
        }
        return true;
    }
}
