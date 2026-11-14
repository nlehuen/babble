package org.babblelang.engine.impl;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.BabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.ScriptException;

/**
 * A script run by the interpreter.
 */
public class BabbleCompiledScript extends BabbleScript {
    private final BabbleParser.FileContext file;
    private final ScopeAnalysis scopes;
    private final String sourceName;

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) {
        super(engine);
        this.file = file;
        this.scopes = scopes;
        this.sourceName = sourceName;
    }

    @Override
    public String[] exports() {
        return scopes.exports();
    }

    @Override
    public Object eval(ScriptRuntime runtime) throws ScriptException {
        Interpreter interpreter = new Interpreter(runtime, scopes, sourceName);
        try {
            return interpreter.visitFile(file);
        } catch (Exception e) {
            throw new ScriptException(e.toString(), sourceName, interpreter.getLast().getStart().getLine());
        } finally {
            BabbleScriptEngine.flush(runtime.writer());
        }
    }
}
