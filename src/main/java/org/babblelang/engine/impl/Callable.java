package org.babblelang.engine.impl;

public interface Callable {
    Scope bindParameters(Interpreter interpreter, Scope parent, Parameters parameters);

    Object call(Interpreter interpreter, Scope scope);
}
