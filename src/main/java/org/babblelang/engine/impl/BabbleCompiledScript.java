package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

public class BabbleCompiledScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final BabbleParser.FileContext file;

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file) {
        this.engine = engine;
        this.file = file;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        return new StatementRunner(new Scope()).run(file.statement());
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
