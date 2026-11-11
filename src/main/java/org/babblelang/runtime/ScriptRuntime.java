package org.babblelang.runtime;

import org.babblelang.engine.impl.Namespace;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The per-eval context handed to every call : the script root, the ScriptContext whose
 * writer receives the output of {@code print}, the engine, and the modules the eval has
 * imported so far, by real path. A module runs on a runtime of its own, with its own
 * root, that shares the modules of the eval importing it.
 */
public record ScriptRuntime(Namespace root, ScriptContext context, ScriptEngine engine, Map<Path, Module> modules) {
    public ScriptRuntime(Namespace root, ScriptContext context, ScriptEngine engine) {
        this(root, context, engine, new HashMap<>());
    }

    public Writer writer() {
        return context.getWriter();
    }
}
