package org.babblelang.tests;

import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;
import org.opentest4j.AssertionFailedError;

public class AssertFunction extends org.babblelang.engine.impl.natives.AssertFunction {
    public Boolean call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        String message = (String) scope.get("message").get();
        boolean test = (Boolean) scope.get("test").get();
        if (!test) {
            // Thrown directly rather than via Assertions.assertTrue, which would append
            // its own "==> expected: <true> but was: <false>" to the reported message.
            throw new AssertionFailedError(message != null
                    ? message
                    : "Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText());
        }
        return true;
    }
}
