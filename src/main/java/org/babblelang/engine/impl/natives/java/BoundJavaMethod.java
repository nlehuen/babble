package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

public class BoundJavaMethod implements Callable {
    private final JavaMethod method;
    private final Object thiz;

    BoundJavaMethod(JavaMethod method, Object thiz) {
        this.method = method;
        this.thiz = thiz;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return method.invoke(thiz, args, rt);
    }
}
