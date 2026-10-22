package org.babblelang.runtime;

import org.babblelang.engine.impl.Namespace;

import java.lang.invoke.MethodHandle;

/**
 * The value of a compiled function literal : its code, a static method of the script
 * class, and the namespace it closes over.
 * <p>
 * Besides the body, which takes the arguments as an array, the function may have a
 * <em>direct</em> entry point (compiler phase 3) : the same code with one JVM parameter
 * per Babble parameter, {@code (CompiledFunction self, Object thiz, ScriptRuntime rt,
 * Arguments.Binder binder, Object p0, ...)}. A call site that passes arguments by position
 * calls it without building an array or binding names ({@link Bootstrap}). Every value
 * of the same function literal shares the same handle, which call sites compare.
 */
public final class CompiledFunction implements BabbleFunction {
    private final FunctionBody body;
    private final MethodHandle direct;
    private final Namespace closure;
    private final Object[] captured;
    private final String[] params;
    private final int required;
    private final String name;

    /**
     * @param closure  the nearest enclosing namespace, for names resolved at runtime
     * @param direct   the direct entry point, or null
     * @param captured the cells of the variables of enclosing methods the function uses
     */
    public CompiledFunction(FunctionBody body, MethodHandle direct, Namespace closure, Object[] captured, String[] params, int required, String name) {
        this.body = body;
        this.direct = direct;
        this.closure = closure;
        this.captured = captured;
        this.params = params;
        this.required = required;
        this.name = name;
    }

    /** What compiled code calls to create the value of a function literal. */
    public static CompiledFunction create(FunctionBody body, MethodHandle direct, Namespace closure, Object[] captured, String[] params, int required, String name) {
        return new CompiledFunction(body, direct, closure, captured, params, required, name);
    }

    /** The direct entry point, or null when the function has too many parameters for one. */
    public MethodHandle direct() {
        return direct;
    }

    public Object[] captured() {
        return captured;
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
