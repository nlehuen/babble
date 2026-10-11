package org.babblelang.runtime;

import org.babblelang.engine.impl.Namespace;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import java.io.Writer;

/**
 * The per-eval context handed to every call : the script root, the ScriptContext whose
 * writer receives the output of {@code print}, and the engine.
 */
public record ScriptRuntime(Namespace root, ScriptContext context, ScriptEngine engine) {
    public Writer writer() {
        return context.getWriter();
    }
}
