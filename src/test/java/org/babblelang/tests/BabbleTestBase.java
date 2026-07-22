package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;

import javax.script.Bindings;
import javax.script.ScriptException;
import java.io.FileNotFoundException;
import java.io.FileReader;

public abstract class BabbleTestBase {
    private Bindings buildBindings() {
        Bindings b = BabbleScriptEngineFactory.INSTANCE.getScriptEngine().createBindings();
        b.put("assert", new AssertFunction());
        return b;
    }

    void interpretFile(String path) throws FileNotFoundException, ScriptException {
        BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(new FileReader(path), buildBindings());
    }

    Object interpret(String script) throws ScriptException {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(script, buildBindings());
    }
}
