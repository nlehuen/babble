package org.babblelang.engine;

import org.babblelang.runtime.ScriptRuntime;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * A script ready to run, in either mode. An eval normally gets fresh namespaces ; a
 * {@link BabbleSession} runs each of its inputs in the namespace the previous ones left.
 */
public abstract class BabbleScript extends CompiledScript {
    private final BabbleScriptEngine engine;

    protected BabbleScript(BabbleScriptEngine engine) {
        this.engine = engine;
    }

    /** Runs the script in the root namespace of a runtime. */
    public abstract Object eval(ScriptRuntime runtime) throws ScriptException;

    /** The names the script exports : what a module importing it can read. */
    public abstract String[] exports();

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        return eval(engine.createRuntime(context));
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
