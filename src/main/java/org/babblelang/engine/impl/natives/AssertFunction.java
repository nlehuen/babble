package org.babblelang.engine.impl.natives;

import org.babblelang.engine.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

public class AssertFunction implements Callable {
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length == 0) {
            throw new BabbleException("assert() called without parameters");
        }
        if (args.length > 2) {
            throw new BabbleException("assert() called with too many parameters");
        }
        Object test = args[0];
        String message = args.length > 1 ? (String) args[1] : null;
        if (!(test instanceof Boolean bool)) {
            throw new BabbleException("Expression " + site.argTexts()[0] + " : asserts don't rely on truth values, please make sure that the test has a boolean result");
        }
        if (!bool) {
            fail(message != null
                    ? message
                    : "Assertion failed at line " + site.line() + " : " + site.argTexts()[0]);
        }
        return true;
    }

    // A failing assert throws unconditionally : relying on the Java "assert" statement
    // made this a no-op whenever the host JVM ran without -ea, which is the default.
    protected void fail(String message) {
        throw new BabbleException(message);
    }
}
