package org.hodor.engine.impl;

import org.hodor.parser.HodorParser;

public class BoundMethod implements Callable {
    private final Function function;
    private final HodorObject object;

    public BoundMethod(Function function, HodorObject hodorObject) {
        this.function = function;
        this.object = hodorObject;
    }

    public Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace result = function.bindParameters(interpreter, callSite, parent, parameters);
        result.define("this", true).set(object);
        return result;
    }

    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        return function.call(interpreter, callSite, scope);
    }
}


