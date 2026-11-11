package org.babblelang.engine.impl;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * A script run by the interpreter.
 */
public class BabbleCompiledScript extends CompiledScript implements BabbleScriptEngine.Script {
    private final BabbleScriptEngine engine;
    private final BabbleParser.FileContext file;
    private final ScopeAnalysis scopes;
    private final String sourceName;

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) {
        this.engine = engine;
        this.file = file;
        this.scopes = scopes;
        this.sourceName = sourceName;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        try {
            return run(engine.createRuntime(context));
        } finally {
            BabbleScriptEngine.flush(context);
        }
    }

    @Override
    public String[] exports() {
        return scopes.exports();
    }

    @Override
    public Object run(ScriptRuntime runtime) throws ScriptException {
        Interpreter interpreter = new Interpreter(runtime, scopes, sourceName);
        try {
            return interpreter.visitFile(file);
        } catch (Exception e) {
            throw new ScriptException(e.toString(), sourceName, interpreter.getLast().getStart().getLine());
        }
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
