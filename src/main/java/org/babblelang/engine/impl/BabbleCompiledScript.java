package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.impl.natives.PrintFunction;
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
        Scope scope = new Scope();
        scope.define("print", new PrintFunction(false));
        scope.define("println", new PrintFunction(true));
        scope.define("STDOUT", System.out);
        return new Interpreter(scope).run(file.statement());
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
