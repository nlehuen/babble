package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public class BoundMethod implements Callable {
    private final Function function;
    private final BabbleObject object;

    public BoundMethod(Function function, BabbleObject babbleObject) {
        this.function = function;
        this.object = babbleObject;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope result = function.bindParameters(interpreter, callSite, parent, parameters);
        result.define("this", true).set(object);
        return result;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        return function.call(interpreter, callSite, resolver);
    }
}


