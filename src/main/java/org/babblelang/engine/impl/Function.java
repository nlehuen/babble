package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public class Function<R> implements Callable<R> {
    private final BabbleParser.FunctionLiteralContext definition;
    private final Namespace closure;

    public Function(BabbleParser.FunctionLiteralContext definition, Namespace namespace) {
        this.definition = definition;
        this.closure = namespace;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        return bindParameters(interpreter, parameters, this);
    }

    /**
     * Binds the arguments in a fresh scope over the closure, with "$recurse" resolving to
     * the callable the call actually went through. That is this function, except when it
     * was reached as a method : a {@link BoundMethod} has to recurse as itself, or the
     * recursive call would go to the raw function and lose the receiver, leaving "this"
     * undefined inside it.
     */
    Namespace bindParameters(Interpreter interpreter, Parameters parameters, Callable<?> self) {
        Namespace namespace = closure.enter(null);
        namespace.define("$recurse", true).set(self);
        parameters.bind(interpreter, definition.parametersDeclaration(), namespace);
        return namespace;
    }

    // The interpreter is untyped, so the result type is the caller's assertion.
    @SuppressWarnings("unchecked")
    public R call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope<Object> scope) {
        try {
            return (R) interpreter.visit(definition.functionBlock);
        } catch (ReturnException re) {
            // A "return" anywhere in the body unwinds up to here, which is its target.
            return (R) re.getValue();
        }
    }
}
