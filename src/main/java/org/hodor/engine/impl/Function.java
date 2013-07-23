package org.hodor.engine.impl;

import org.hodor.parser.HodorParser;

public class Function implements Callable {
    private final HodorParser.FunctionLiteralContext definition;
    private final Namespace closure;

    public Function(HodorParser.FunctionLiteralContext definition, Namespace namespace) {
        this.definition = definition;
        this.closure = namespace;
    }

    public Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = closure.enter(null);
        namespace.define("$recurse", true).set(this);
        parameters.bind(interpreter, definition.parametersDeclaration(), namespace);
        return namespace;
    }

    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        return interpreter.visit(definition.functionBlock);
    }
}
