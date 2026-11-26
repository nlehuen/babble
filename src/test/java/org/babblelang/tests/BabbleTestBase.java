package org.babblelang.tests;

import java.io.FileNotFoundException;
import java.io.FileReader;
import javax.script.Bindings;
import javax.script.ScriptException;
import org.babblelang.engine.BabbleScriptEngineFactory;

public abstract class BabbleTestBase {
    private Bindings buildBindings() {
        Bindings b = BabbleScriptEngineFactory.INSTANCE.getScriptEngine().createBindings();
        b.put("assert", new AssertFunction());
        return b;
    }

    void interpretFile(String path) throws FileNotFoundException, ScriptException {
        Bindings bindings = buildBindings();
        // The name of the file is what its relative imports are resolved against.
        bindings.put(javax.script.ScriptEngine.FILENAME, path);
        BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(new FileReader(path), bindings);
    }

    Object interpret(String script) throws ScriptException {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(script, buildBindings());
    }
}
