package org.babblelang.analysis;

/**
 * How the analysis gets the interfaces of the files a file imports statically
 * (docs/design/bip005_modules.md §8.1) : the engine implements it, with its compilation cache.
 */
public interface ModuleLoader {
    /**
     * Called once pass 1 of the file being analysed is done : its exported names are
     * known, its types not yet. A file importing it back, in a cycle, gets this interface.
     */
    void started(ModuleInterface self);

    /**
     * The interface of the file a static import names, analysed (and compiled) if needed.
     *
     * @param name the string literal of the import, as written
     * @param line where the import is, for errors
     * @throws AnalysisException when the file is missing, does not parse, or fails its own
     *                           analysis : an error of the importing file, at the import
     */
    ModuleInterface load(String name, int line);

    /**
     * The key of the module being analysed, or null when it is not one (a script a host
     * evaluates, an interactive input) : its types' ids derive from it, so that they are the
     * same every time the module is compiled.
     */
    default String moduleKey() {
        return null;
    }
}
