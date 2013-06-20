package org.babblelang.tests;

import junit.framework.Assert;
import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Scope;

import java.util.Iterator;

public class AssertFunction implements Callable {
    public Scope bindParameters(Interpreter interpreter, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);
        Assert.assertTrue("assert() called without parameters", parameters.size() > 0);
        Assert.assertTrue("assert() called with too many parameters", parameters.size() <= 2);
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
            Assert.assertTrue(test);
        } else {
            Assert.assertTrue(message, test);
        }
        return true;
    }
}
