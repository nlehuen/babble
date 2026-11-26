package org.babblelang.engine;

import java.io.IOException;

/**
 * Where the text of imported modules comes from (docs/design/bip006_module-sources-and-aot.md §4) :
 * the engine attribute {@link BabbleScriptEngine#MODULE_SOURCE}, by default the file system ({@link
 * FileModuleSource}). A host can serve modules from memory, a database or a jar.
 */
public interface ModuleSource {
    /**
     * The module an import names, from the module importing it.
     *
     * @param name the name as the import gives it, ending in {@code .ba}
     * @param importer the importing module, or null for a script the source doesn't know : then the
     *     name is resolved as from nowhere in particular (for files, the working directory)
     * @return the module, or null when there is none ; a source may also throw, with a message
     *     saying why (for files, {@link java.nio.file.NoSuchFileException})
     */
    ModuleId resolve(String name, ModuleId importer) throws IOException;

    /**
     * The identity of a script the host evaluates, from its source name ({@link
     * javax.script.ScriptEngine#FILENAME}) : what its imports are resolved from. Null when the
     * source doesn't know it, and its imports are then resolved from nowhere in particular. By
     * default, what {@link #resolve} gives for the name.
     */
    default ModuleId identify(String sourceName) {
        try {
            return resolve(sourceName, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The text of a module this source resolved, and its current version. */
    ModuleText read(ModuleId module) throws IOException;

    /**
     * The current version of a module : what tells whether a compilation of it is stale. A source
     * can make it cheaper than {@link #read}.
     */
    default String version(ModuleId module) throws IOException {
        return read(module).version();
    }
}
