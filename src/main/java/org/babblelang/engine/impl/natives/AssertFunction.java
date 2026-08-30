package org.babblelang.engine.impl.natives;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.*;
import org.babblelang.parser.BabbleParser;

import java.util.Iterator;

public class AssertFunction implements Callable<Boolean> {
    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        if (parameters.isEmpty()) {
            throw new BabbleException("assert() called without parameters");
        }
        if (parameters.size() > 2) {
            throw new BabbleException("assert() called with too many parameters");
        }
        Iterator<Object> it = parameters.values().iterator();
        Object test = it.next();
        Object message = null;
        if (it.hasNext()) {
            message = it.next();
        }
        if (!(test instanceof Boolean)) {
            throw new BabbleException("Expression " + callSite.callParameters().callParameter(0).expression().getText() + " : asserts don't rely on truth values, please make sure that the test has a boolean result");
        }
        namespace.define("test", true).set(test);
        namespace.define("message", true).set(message);
        return namespace;
    }

    // A failing assert throws unconditionally : relying on the Java "assert" statement
    // made this a no-op whenever the host JVM ran without -ea, which is the default.
    public Boolean call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope<Object> scope) {
        String message = (String) scope.get("message").get();
        boolean test = (Boolean) scope.get("test").get();
        if (!test) {
            throw new BabbleException(message != null
                    ? message
                    : "Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText());
        }
        return true;
    }
}
