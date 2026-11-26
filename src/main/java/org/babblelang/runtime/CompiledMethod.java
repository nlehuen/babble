package org.babblelang.runtime;

import java.lang.invoke.MethodHandle;

/**
 * A method of a compiled class (compiler phase 2) : a JVM instance method of the class the compiler
 * generates for a class or object expression. There is one per method literal, shared by every
 * instance and every class value of the expression : what varies is reached through the receiver,
 * its fields and its class value.
 *
 * <p>Like a {@link CompiledFunction}, it may have a direct entry point taking the arguments spread
 * : {@code (Script$N$k$C thiz, CompiledMethod self, ScriptRuntime rt, Arguments.Binder binder,
 * Object p0, ...)}.
 */
public final class CompiledMethod implements BabbleFunction {
    private final MethodBody body;
    private final MethodHandle direct;
    private final MethodHandle spec;
    private final String[] params;
    private final int required;
    private final int optionalParamBits;
    private final int restIndex;
    private final String name;

    public CompiledMethod(
            MethodBody body, MethodHandle direct, String[] params, int required, String name) {
        this(body, direct, null, params, required, 0, -1, name);
    }

    public CompiledMethod(
            MethodBody body,
            MethodHandle direct,
            MethodHandle spec,
            String[] params,
            int required,
            String name) {
        this(body, direct, spec, params, required, 0, -1, name);
    }

    public CompiledMethod(
            MethodBody body,
            MethodHandle direct,
            MethodHandle spec,
            String[] params,
            int required,
            int optionalParamBits,
            String name) {
        this(body, direct, spec, params, required, optionalParamBits, -1, name);
    }

    public CompiledMethod(
            MethodBody body,
            MethodHandle direct,
            MethodHandle spec,
            String[] params,
            int required,
            int optionalParamBits,
            int restIndex,
            String name) {
        this.body = body;
        this.direct = direct;
        this.spec = spec;
        this.params = params;
        this.required = required;
        this.optionalParamBits = optionalParamBits;
        this.restIndex = restIndex;
        this.name = name;
    }

    @Override
    public Object invoke(
            Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return body.invoke(thiz, this, rt, args, names, site);
    }

    /** The direct entry point, or null. */
    public MethodHandle direct() {
        return direct;
    }

    /** The specialized primitive entry point, or null. */
    public MethodHandle spec() {
        return spec;
    }

    /**
     * Bitmask of parameter indices (for methods with {@code <= 32} parameters) that have defaults,
     * or 0.
     */
    public int optionalParamBits() {
        return optionalParamBits;
    }

    /** Index of the variadic rest parameter, or {@code -1} if none. */
    public int restIndex() {
        return restIndex;
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
        return restIndex >= 0 ? Integer.MAX_VALUE : params.length;
    }

    @Override
    public String toString() {
        return "function " + name;
    }
}
