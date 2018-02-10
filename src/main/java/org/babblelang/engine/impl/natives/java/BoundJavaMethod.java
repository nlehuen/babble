package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.*;
import org.babblelang.parser.BabbleParser;

public class BoundJavaMethod implements Callable {
    private final JavaMethod method;
    private final Object thiz;

    BoundJavaMethod(JavaMethod method, Object thiz) {
        this.method = method;
        this.thiz = thiz;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace result = method.bindParameters(interpreter, callSite, parent, parameters);
        result.get("this").set(thiz);
        return result;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        return method.call(interpreter, callSite, scope);
    }
}
