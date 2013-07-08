package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Map;

public class BabbleCompiledScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final BabbleParser.FileContext file;

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file) {
        this.engine = engine;
        this.file = file;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        Scope scope = engine.getImplicits().enter(null);

        // TODO : handle ScriptContext.GLOBAL_SCOPE
        for (Map.Entry<String, Object> binding : context.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            scope.define(binding.getKey(), false).set(binding.getValue());
        }

        Interpreter interpreter = new Interpreter(scope);
        try {
            return interpreter.visit(file);
        } catch (Exception e) {
            throw new ScriptException(e.toString(), "<input>", interpreter.getLast().getStart().getLine());
        }
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
