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

    public R call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        return (R) interpreter.visit(definition.functionBlock);
    }
}
