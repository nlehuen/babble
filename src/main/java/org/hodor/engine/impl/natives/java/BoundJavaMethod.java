package org.hodor.engine.impl.natives.java;

import org.hodor.engine.impl.Callable;
import org.hodor.engine.impl.Interpreter;
import org.hodor.engine.impl.Namespace;
import org.hodor.engine.impl.Scope;
import org.hodor.parser.HodorParser;

public class BoundJavaMethod implements Callable {
    private final JavaMethod method;
    private final Object _this;

    public BoundJavaMethod(JavaMethod method, Object _this) {
        this.method = method;
        this._this = _this;
    }

    public Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace result = method.bindParameters(interpreter, callSite, parent, parameters);
        result.get("this").set(_this);
        return result;
    }

    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        return method.call(interpreter, callSite, scope);
    }
}
