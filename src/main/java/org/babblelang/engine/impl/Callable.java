package org.babblelang.engine.impl;

import java.util.LinkedHashMap;

public interface Callable {
    Scope bindParameters(Interpreter interpreter, Scope parent, Parameters parameters);

    Object call(Interpreter interpreter, Scope scope);

    class Parameters extends LinkedHashMap<String, Object> {
    }
}
