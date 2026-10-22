package org.babblelang.runtime;

import java.lang.invoke.MethodHandle;

/**
 * A method of a compiled class (compiler phase 2) : a JVM instance method of the class
 * the compiler generates for a class or object expression. There is one per method
 * literal, shared by every instance and every class value of the expression : what
 * varies is reached through the receiver, its fields and its class value.
 * <p>
 * Like a {@link CompiledFunction}, it may have a direct entry point taking the arguments
 * spread : {@code (Script$N$k$C thiz, CompiledMethod self, ScriptRuntime rt,
 * Arguments.Binder binder, Object p0, ...)}.
 */
public final class CompiledMethod implements BabbleFunction {
    private final MethodBody body;
    private final MethodHandle direct;
    private final String[] params;
    private final int required;
    private final String name;

    public CompiledMethod(MethodBody body, MethodHandle direct, String[] params, int required, String name) {
        this.body = body;
        this.direct = direct;
        this.params = params;
        this.required = required;
        this.name = name;
    }

    @Override
    public Object invoke(Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return body.invoke(thiz, this, rt, args, names, site);
    }

    /** The direct entry point, or null. */
    public MethodHandle direct() {
        return direct;
    }

    public String[] params() {
        return params;
    }

    @Override
    public int minArity() {
        return required;
    }

    @Override
    public int maxArity() {
        return params.length;
    }

    @Override
    public String toString() {
        return "function " + name;
    }
}
