package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

import java.util.Map;

public class Function extends BabbleBaseVisitor<Object> implements Callable {
    private final Scope scope;
    private final BabbleParser.FunctionLiteralContext definition;

    public Function(Scope scope, BabbleParser.FunctionLiteralContext definition) {
        this.scope = scope;
        this.definition = definition;
    }

    @Override
    public Object call(Map<String, Object> parameters) {
        Scope local = new Scope(scope);
        // TODO : check parameters
        local.putAll(parameters);
        return new StatementRunner(local).run(definition.block().statement());
    }
}
