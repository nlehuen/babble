package org.hodor.engine.impl;

import org.hodor.engine.BabbleScriptEngine;
import org.hodor.parser.HodorParser;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Map;

public class HodorCompiledScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final HodorParser.FileContext file;

    public HodorCompiledScript(BabbleScriptEngine engine, HodorParser.FileContext file) {
        this.engine = engine;
        this.file = file;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        Namespace namespace = engine.getImplicits().enter(null);

        // TODO : handle ScriptContext.GLOBAL_SCOPE
        for (Map.Entry<String, Object> binding : context.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            namespace.define(binding.getKey(), false).set(binding.getValue());
        }

        Interpreter interpreter = new Interpreter(namespace);
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
