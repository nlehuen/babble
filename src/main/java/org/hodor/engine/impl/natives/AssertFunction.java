package org.hodor.engine.impl.natives;

import org.hodor.engine.BabbleException;
import org.hodor.engine.impl.Callable;
import org.hodor.engine.impl.Interpreter;
import org.hodor.engine.impl.Namespace;
import org.hodor.engine.impl.Scope;
import org.hodor.parser.HodorParser;

import java.util.Iterator;

public class AssertFunction implements Callable {
    public Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        assert parameters.size() > 0 : "assert() called without parameters";
        assert parameters.size() <= 2 : "assert() called with too many parameters";
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

    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        String message = (String) scope.get("message").get();
        boolean test = (Boolean) scope.get("test").get();
        if (message == null) {
            assert test : "Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText();
        } else {
            assert test : message;
        }
        return true;
    }
}
