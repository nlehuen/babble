package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

public class Function extends BabbleBaseVisitor<Object> implements Callable {
    private final BabbleParser.FunctionLiteralContext definition;
    private final Scope closure;

    public Function(BabbleParser.FunctionLiteralContext definition, Scope scope) {
        this.definition = definition;
        this.closure = scope;
    }

    public BabbleParser.FunctionLiteralContext getDefinition() {
        return definition;
    }

    @Override
    public Scope bindParameters(Parameters parameters) {
        Scope scope = closure.enter(null);

        int count = 0;
        for (BabbleParser.ParameterDeclarationContext parameter : definition.functionType().parametersDeclaration().parameterDeclaration()) {
            String pos = "$" + (count++);
            String name = parameter.ID().getText();
            Object value;
            if (parameters.containsKey(name)) {
                value = parameters.get(name);
            } else if (parameters.containsKey(pos)) {
                value = parameters.get(pos);
            } else {
                throw new IllegalArgumentException("Missing parameter : " + name);
            }
            checkType(parameter, value);
            scope.define(name, value);
        }
        return scope;
    }

    @Override
    public Object call(Interpreter interpreter, Scope scope) {
        return interpreter.visit(definition.block());
    }

    private void checkType(BabbleParser.ParameterDeclarationContext parameter, Object value) {
        // TODO : implement type checks
        if (false) {
            throw new IllegalArgumentException("Parameter type mismatch : " + parameter.ID().getText() + " is expected to be of type " + parameter.type().getText() + ", got " + value.getClass().getCanonicalName());
        }
    }
}
