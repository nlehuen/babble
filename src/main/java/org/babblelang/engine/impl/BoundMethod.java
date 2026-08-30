package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public class BoundMethod<R> implements Callable<R> {
    private final Function<R> function;
    private final BabbleObject object;

    public BoundMethod(Function<R> function, BabbleObject babbleObject) {
        this.function = function;
        this.object = babbleObject;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        // Binding through "this" rather than the raw function keeps "recurse" bound to the
        // receiver, so a method that recurses still sees "this".
        Namespace result = function.bindParameters(interpreter, parameters, this);
        result.define("this", true).set(object);
        return result;
    }

    public R call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope<Object> scope) {
        return function.call(interpreter, callSite, scope);
    }
}


