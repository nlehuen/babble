package org.babblelang.engine.impl.natives;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.util.Iterator;

public class AssertFunction implements Callable {
    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);
        assert parameters.size() > 0 : "assert() called without parameters";
        assert parameters.size() <= 2 : "assert() called with too many parameters";
        Iterator<Object> it = parameters.values().iterator();
        Object test = it.next();
        Object message = null;
        if (it.hasNext()) {
            message = it.next();
        }
        if (!(test instanceof Boolean)) {
            throw new IllegalArgumentException("Line " + callSite.getStart().getLine() + ", expression " + callSite.callParameters().callParameter(0).expression().getText() + " : asserts don't rely on truth values, please make sure that the test has a boolean result");
        }
        scope.define("test", test);
        scope.define("message", message);
        return scope;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        String message = (String) resolver.get("message");
        boolean test = (Boolean) resolver.get("test");
        if (message == null) {
            assert test : "Assertion failed at line " + callSite.getStart().getLine() + " : " + callSite.callParameters().callParameter(0).expression().getText();
        } else {
            assert test : message;
        }
        return true;
    }
}
