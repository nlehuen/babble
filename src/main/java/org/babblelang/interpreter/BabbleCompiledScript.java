package org.babblelang.interpreter;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

public class BabbleCompiledScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final BabbleParser.FileContext file;

    private class Compiler extends BabbleBaseVisitor<Void> {
    }

    private class Runner extends BabbleBaseVisitor<Object> {
    }

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file) {
        this.engine = engine;
        this.file = file;
        new Compiler().visit(file);
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        return new Runner().visit(file);
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
