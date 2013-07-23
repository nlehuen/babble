package org.hodor.tests;

import org.hodor.engine.BabbleScriptEngineFactory;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.script.Bindings;
import javax.script.ScriptException;
import java.io.FileNotFoundException;
import java.io.FileReader;

@RunWith(JUnit4.class)
public abstract class HodorTestBase {
    protected Bindings buildBindings() {
        Bindings b = BabbleScriptEngineFactory.INSTANCE.getScriptEngine().createBindings();
        b.put("HODORRRR?", new AssertFunction());
        return b;
    }

    protected Object interpretFile(String path) throws FileNotFoundException, ScriptException {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(new FileReader(path), buildBindings());
    }

    protected Object interpret(String script) throws ScriptException {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(script, buildBindings());
    }
}
