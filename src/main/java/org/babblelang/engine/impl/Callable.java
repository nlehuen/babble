package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleParser;

public interface Callable<R> {
    Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters);

    R call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope<Object> scope);
}
