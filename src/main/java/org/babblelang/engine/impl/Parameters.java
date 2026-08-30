package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;
import org.babblelang.parser.BabbleParser;

import java.util.LinkedHashMap;

public class Parameters extends LinkedHashMap<String, Object> {
    void bind(Interpreter interpreter, BabbleParser.ParametersDeclarationContext parametersDeclarationContext, Namespace scope) {
        int count = 0;
        for (BabbleParser.ParameterDeclarationContext parameter : parametersDeclarationContext.parameterDeclaration()) {
            String pos = "$" + (count++);
            String name = parameter.ID().getText();
            Object value;
            if (this.containsKey(name)) {
                value = this.get(name);
            } else if (this.containsKey(pos)) {
                value = this.get(pos);
            } else if (parameter.defaultValue != null) {
                value = interpreter.evaluateIn(scope, parameter.defaultValue);
            } else {
                throw new BabbleException("Missing parameter : " + name);
            }
            scope.define(name, false).set(value);
        }
    }

    public Object[] valuesArray() {
        return values().toArray();
    }
}
