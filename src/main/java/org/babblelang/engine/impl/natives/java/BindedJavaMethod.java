package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

public class BindedJavaMethod implements Callable {
    private final JavaMethod method;
    private final Object _this;

    public BindedJavaMethod(JavaMethod method, Object _this) {
        this.method = method;
        this._this = _this;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope result = method.bindParameters(interpreter, callSite, parent, parameters);
        result.assign("this", _this);
        return result;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        return method.call(interpreter, callSite, resolver);
    }
}
