package org.babblelang.engine;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modules kept in memory, by "/"-separated path : for tests, and for hosts that generate
 * modules or keep them elsewhere than in files (docs/design/module-sources-and-aot.md §4.2).
 * A relative name is resolved against the importing module's directory, a name starting with
 * "/" from the root ; nothing goes above the root. Paths are both keys and display names, and
 * a module's version is a hash of its text, so {@link #put} with a new text recompiles it.
 * <p>
 * A script evaluated under a source name ({@link javax.script.ScriptEngine#FILENAME}) such as
 * {@code app/main.ba} imports from {@code app/}, whether or not the map holds it.
 */
public final class MapModuleSource implements ModuleSource {
    private final Map<String, String> texts = new ConcurrentHashMap<>();

    public MapModuleSource() {
    }

    public MapModuleSource(Map<String, String> modules) {
        modules.forEach(this::put);
    }

    /** Adds a module, or replaces its text. */
    public MapModuleSource put(String path, String text) {
        texts.put(checked(path), text);
        return this;
    }

    public MapModuleSource remove(String path) {
        texts.remove(checked(path));
        return this;
    }

    private static String checked(String path) {
        String normalized = LogicalPaths.normalize(path);
        if (normalized == null) {
            throw new IllegalArgumentException("Not a module path : " + path);
        }
        return normalized;
    }

    @Override
    public ModuleId resolve(String name, ModuleId importer) {
        String path = LogicalPaths.resolve(name, importer != null ? importer.key() : null);
        return path != null && texts.containsKey(path) ? new ModuleId(path, path) : null;
    }

    @Override
    public ModuleId identify(String sourceName) {
        String path = LogicalPaths.normalize(sourceName);
        return path != null ? new ModuleId(path, path) : null;
    }

    @Override
    public ModuleText read(ModuleId module) throws IOException {
        String text = texts.get(module.key());
        if (text == null) {
            throw new IOException("No such module : " + module.key());
        }
        return new ModuleText(text, FileModuleSource.hash(text));
    }

    @Override
    public String toString() {
        return "modules in memory " + texts.keySet();
    }
}
