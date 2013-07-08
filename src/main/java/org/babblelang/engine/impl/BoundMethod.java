package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public class BoundMethod implements Callable {
    private final Function function;
    private final BabbleObject object;

    public BoundMethod(Function function, BabbleObject babbleObject) {
        this.function = function;
        this.object = babbleObject;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace result = function.bindParameters(interpreter, callSite, parent, parameters);
        result.define("this", true).set(object);
        return result;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        return function.call(interpreter, callSite, scope);
    }
}


