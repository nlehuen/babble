package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.FileModuleSource;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.ModuleCompiler;

import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@link ModuleCompiler} of the full Babble jar (docs/design/bip006_module-sources-and-aot.md
 * §5.4, M5c) : a compiled program run with Babble on its class path imports the modules its
 * jar doesn't hold from the files under its root, compiled as the engine compiles them. One engine
 * per root, so that a module is compiled once, and the modules it imports statically with it.
 */
public final class FileModuleCompiler implements ModuleCompiler {
    private final Map<Path, Engine> engines = new HashMap<>();

    private record Engine(BabbleScriptEngine engine, FileModuleSource source) {
    }

    @Override
    public Compiled compile(Path root, String key) {
        Engine engine;
        synchronized (engines) {
            engine = engines.get(root);
            if (engine == null) {
                engine = newEngine(root);
                engines.put(root, engine);
            }
        }
        try {
            if (engine.source().resolve(key, null) == null) {
                throw new BabbleException("no such module under " + root);
            }
        } catch (NoSuchFileException e) {
            throw new BabbleException("no such file : " + root.resolve(key));
        } catch (IOException e) {
            throw new BabbleException(e.toString());
        }
        try {
            BabbleScriptEngine.ProgramModule module = engine.engine().compileProgram(engine.source(), key).get(0);
            return new Compiled(module.id().key(), module.id().displayName(), module.script().getScriptClass(), module.exports());
        } catch (ScriptException e) {
            // Without the class names it starts with, as the command line shows it.
            throw new BabbleException(e.getMessage().replaceFirst("^(?:[\\w.$]+(?:Exception|Error): )+", ""));
        }
    }

    private static Engine newEngine(Path root) {
        FileModuleSource source;
        try {
            source = FileModuleSource.rooted(root);
        } catch (IOException e) {
            throw new BabbleException("no such directory : " + root);
        }
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        SimpleScriptContext context = new SimpleScriptContext();
        context.setWriter(new PrintWriter(System.out, true));
        context.setErrorWriter(new PrintWriter(System.err, true));
        engine.setContext(context);
        engine.put(BabbleScriptEngine.MODE, "compile");
        return new Engine(engine, source);
    }
}
