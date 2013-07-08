package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public class Function implements Callable {
    private final BabbleParser.FunctionLiteralContext definition;
    private final Scope closure;

    public Function(BabbleParser.FunctionLiteralContext definition, Scope scope) {
        this.definition = definition;
        this.closure = scope;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = closure.enter(null);
        scope.define("$recurse", true).set(this);
        parameters.bind(interpreter, definition.parametersDeclaration(), scope);
        return scope;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        return interpreter.visit(definition.functionBlock);
    }
}
