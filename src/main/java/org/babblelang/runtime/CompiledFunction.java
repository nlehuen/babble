package org.babblelang.runtime;

import org.babblelang.engine.impl.Namespace;

/**
 * The value of a compiled function literal : its code, a static method of the script
 * class, and the namespace it closes over.
 */
public final class CompiledFunction implements BabbleFunction {
    private final FunctionBody body;
    private final Namespace closure;
    private final String[] params;
    private final int required;
    private final String name;

    public CompiledFunction(FunctionBody body, Namespace closure, String[] params, int required, String name) {
        this.body = body;
        this.closure = closure;
        this.params = params;
        this.required = required;
        this.name = name;
    }

    /** What compiled code calls to create the value of a function literal. */
    public static CompiledFunction create(FunctionBody body, Namespace closure, String[] params, int required, String name) {
        return new CompiledFunction(body, closure, params, required, name);
    }

    @Override
    public int minArity() {
        return required;
    }

    @Override
    public int maxArity() {
        return params.length;
    }

    public Namespace closure() {
        return closure;
    }

    public String[] params() {
        return params;
    }

    public String name() {
        return name;
    }

    @Override
    public Object invoke(Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return body.invoke(this, thiz, rt, args, names, site);
    }

    /**
     * Creates the namespace of a call, as the first thing a compiled function body does.
     */
    public Namespace prologue(Object thiz, String[] declared) {
        return Frames.enter(closure, recurseTarget(thiz), thiz, declared);
    }

    @Override
    public String toString() {
        return name != null ? "function " + name : "function";
    }
}
