package org.babblelang.runtime;

/**
 * Where {@code import("x.ba")} gets modules from (docs/design/module-sources-and-aot.md §3).
 * The engine implements it by compiling the module's file ; a compiled program, by finding
 * the module's class. One instance serves one eval, and the runtimes of the modules it
 * imports : a module is loaded once per eval.
 */
public interface Modules {
    /**
     * @param name     the name the import gives, ending in {@link Module#EXTENSION}
     * @param importer the source name of the importing file, which relative names are
     *                 resolved against, or null
     * @param rt       the runtime of the importing file
     */
    Module load(String name, String importer, ScriptRuntime rt);
}
