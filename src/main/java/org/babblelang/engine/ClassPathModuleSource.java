package org.babblelang.engine;

import org.babblelang.runtime.LogicalPaths;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Modules shipped as resources, for libraries in jars (docs/design/module-sources-and-aot.md
 * §4.2) : {@code lib/shapes.ba} is the resource {@code <prefix>/lib/shapes.ba} of a class
 * loader. Paths are resolved as by {@link MapModuleSource} ; a module's key is
 * {@code classpath:} and its path, its display name the path, its version a hash of its text.
 */
public final class ClassPathModuleSource implements ModuleSource {
    private static final String SCHEME = "classpath:";

    private final ClassLoader loader;
    private final String prefix;

    /**
     * @param prefix the resource directory the module paths are relative to, "" for the root
     */
    public ClassPathModuleSource(ClassLoader loader, String prefix) {
        this.loader = loader;
        String normalized = LogicalPaths.normalize(prefix);
        this.prefix = normalized == null ? "" : normalized + "/";
    }

    @Override
    public ModuleId resolve(String name, ModuleId importer) {
        String path = LogicalPaths.resolve(name, importer != null ? importer.displayName() : null);
        return path != null && resource(path) != null ? new ModuleId(SCHEME + path, path) : null;
    }

    @Override
    public ModuleId identify(String sourceName) {
        String path = LogicalPaths.normalize(sourceName);
        return path != null ? new ModuleId(SCHEME + path, path) : null;
    }

    private URL resource(String path) {
        return loader.getResource(prefix + path);
    }

    @Override
    public ModuleText read(ModuleId module) throws IOException {
        if (!module.key().startsWith(SCHEME)) {
            throw new IOException("Not a class path module : " + module.key());
        }
        URL url = resource(module.key().substring(SCHEME.length()));
        if (url == null) {
            throw new IOException("No such module : " + module.displayName());
        }
        try (InputStream in = url.openStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ModuleText(text, FileModuleSource.hash(text));
        }
    }

    @Override
    public String toString() {
        return "modules in the class path under " + (prefix.isEmpty() ? "/" : prefix);
    }
}
