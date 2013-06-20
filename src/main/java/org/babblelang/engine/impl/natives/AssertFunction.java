package org.babblelang.engine.impl.natives;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Scope;

import java.util.Iterator;

public class AssertFunction implements Callable {
    public Scope bindParameters(Interpreter interpreter, Scope parent, Parameters parameters) {
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
            throw new IllegalArgumentException("Asserts don't rely on truth values, please make sure that the test has a boolean result");
        }
        scope.define("test", test);
        scope.define("message", message);
        return scope;
    }

    public Object call(Interpreter interpreter, Scope scope) {
        String message = (String) scope.get("message");
        boolean test = (Boolean) scope.get("test");
        if (message == null) {
            assert test;
        } else {
            assert test : message;
        }
        return true;
    }
}
