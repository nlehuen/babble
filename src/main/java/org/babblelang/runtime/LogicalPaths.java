package org.babblelang.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Module paths that are not files : "/"-separated, relative to a root, with "." and ".." resolved
 * and no way above the root. What the engine's {@code MapModuleSource}, {@code
 * ClassPathModuleSource} and rooted {@code FileModuleSource} use for keys and display names, and
 * what a compiled program resolves its imports with (docs/design/bip006_module-sources-and-aot.md
 * §5.4).
 */
public final class LogicalPaths {
    private LogicalPaths() {}

    /**
     * The path an import names, from the importing module's path : relative to its directory, or to
     * the root when the name starts with "/" or there is no importer. Null when the name goes above
     * the root.
     */
    public static String resolve(String name, String importer) {
        String base = name.startsWith("/") || importer == null ? "" : directory(importer);
        return normalize(base + name);
    }

    /**
     * "a/./b/../c.ba" as "a/c.ba", without a leading "/", or null when it goes above the root or
     * names nothing.
     */
    public static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else if (!part.isEmpty() && !part.equals(".")) {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static String directory(String path) {
        return path.substring(0, path.lastIndexOf('/') + 1);
    }
}
