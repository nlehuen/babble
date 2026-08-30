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
        Namespace namespace = closure.enter(null);
        namespace.define("$recurse", true).set(this);
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
