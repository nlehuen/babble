package org.hodor.engine.impl;

import org.hodor.engine.BabbleException;
import org.hodor.parser.HodorParser;

import java.util.LinkedHashMap;

public interface Callable {
    Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters);

    Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope);

    class Parameters extends LinkedHashMap<String, Object> {
        public void bind(Interpreter interpreter, HodorParser.ParametersDeclarationContext parametersDeclarationContext, Scope scope) {
            int count = 0;
            for (HodorParser.ParameterDeclarationContext parameter : parametersDeclarationContext.parameterDeclaration()) {
                String pos = "$" + (count++);
                String name = parameter.ID().getText();
                Object value;
                if (this.containsKey(name)) {
                    value = this.get(name);
                } else if (this.containsKey(pos)) {
                    value = this.get(pos);
                } else if (parameter.defaultValue != null) {
                    value = interpreter.visit(parameter.defaultValue);
                } else {
                    throw new BabbleException("Missing parameter : " + name);
                }
                scope.define(name, false).set(value);
            }
        }

        public Object[] valuesArray() {
            Object[] result = new Object[size()];
            int i = 0;
            for (Object o : values()) {
                result[i++] = o;
            }
            return result;
        }

        public Class[] typesArray() {
            Class[] result = new Class[size()];
            int i = 0;
            for (Object o : values()) {
                result[i++] = o.getClass();
            }
            return result;

        }
    }
}
