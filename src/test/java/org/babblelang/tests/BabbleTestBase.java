package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.script.Bindings;
import java.io.FileReader;

@RunWith(JUnit4.class)
public abstract class BabbleTestBase {
    protected Bindings buildBindings() {
        Bindings b = BabbleScriptEngineFactory.INSTANCE.getScriptEngine().createBindings();
        b.put("assert", new AssertFunction());
        return b;
    }

    protected Object interpretFile(String path) throws Exception {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(new FileReader(path), buildBindings());
    }

    protected Object interpret(String script) throws Exception {
        return BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(script, buildBindings());
    }
}
