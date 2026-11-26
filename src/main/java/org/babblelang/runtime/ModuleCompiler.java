package org.babblelang.runtime;

import java.nio.file.Path;

/**
 * What a compiled program falls back on to import a module its jar doesn't hold
 * (docs/design/bip006_module-sources-and-aot.md §5.4, M5c) : a service the full Babble jar
 * provides, found through {@link java.util.ServiceLoader}, so that with Babble on the class path a
 * dynamic import compiles the module from its file, as the engine would. Without it, the import
 * fails.
 */
public interface ModuleCompiler {
    /**
     * Compiles a module, and the modules it imports statically.
     *
     * @param root the directory module keys are relative to : the program's root
     * @param key the module's key, a normalised "/"-separated path under {@code root}
     * @throws BabbleException when there is no such module, or it doesn't compile, saying why
     */
    Compiled compile(Path root, String key);

    /**
     * A compiled module : its script class has a static {@code run(ScriptRuntime, Namespace)}, as
     * the classes of a program's jar have.
     */
    record Compiled(String key, String displayName, Class<?> script, String[] exports) {}
}
