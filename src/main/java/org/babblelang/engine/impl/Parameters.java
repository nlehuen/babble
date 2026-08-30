package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class Parameters extends LinkedHashMap<String, Object> {
    void bind(Interpreter interpreter, BabbleParser.ParametersDeclarationContext parametersDeclarationContext, Namespace scope) {
        int count = 0;
        Set<String> consumed = new HashSet<>();
        for (BabbleParser.ParameterDeclarationContext parameter : parametersDeclarationContext.parameterDeclaration()) {
            String pos = "$" + (count++);
            String name = parameter.ID().getText();
            Object value;
            if (this.containsKey(name)) {
                value = this.get(name);
                consumed.add(name);
            } else if (this.containsKey(pos)) {
                value = this.get(pos);
                consumed.add(pos);
            } else if (parameter.defaultValue != null) {
                value = interpreter.evaluateIn(scope, parameter.defaultValue);
            } else {
                throw new BabbleException("Missing parameter : " + name);
            }
            scope.define(name, false).set(value);
        }
        rejectUnconsumed(consumed, count);
    }

    /**
     * Arguments the caller supplied that no declared parameter claimed. A leftover
     * name is almost always a typo, and a leftover position means the call passed
     * more arguments than the function takes; either way, silently dropping them
     * hides the mistake.
     */
    private void rejectUnconsumed(Set<String> consumed, int declared) {
        List<String> unknownNames = new ArrayList<>();
        int extraPositional = 0;
        for (String key : keySet()) {
            if (consumed.contains(key)) {
                continue;
            }
            // Positional arguments are keyed "$0", "$1", ... ; an ID can never start
            // with '$', so these cannot collide with a caller-supplied name.
            if (key.startsWith("$")) {
                extraPositional++;
            } else {
                unknownNames.add(key);
            }
        }
        if (!unknownNames.isEmpty()) {
            throw new BabbleException("No such parameter : " + String.join(", ", unknownNames));
        }
        if (extraPositional > 0) {
            throw new BabbleException("Too many parameters : " + declared + " declared, " + size() + " given");
        }
    }

    public Object[] valuesArray() {
        return values().toArray();
    }
}
