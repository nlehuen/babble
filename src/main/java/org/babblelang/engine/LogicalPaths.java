package org.babblelang.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Module paths that are not files : "/"-separated, relative to a root, with "." and ".."
 * resolved and no way above the root. What {@link MapModuleSource}, {@link ClassPathModuleSource}
 * and a rooted {@link FileModuleSource} use for keys and display names.
 */
final class LogicalPaths {
    private LogicalPaths() {
    }

    /**
     * The path an import names, from the importing module's path : relative to its
     * directory, or to the root when the name starts with "/" or there is no importer. Null
     * when the name goes above the root.
     */
    static String resolve(String name, String importer) {
        String base = name.startsWith("/") || importer == null ? "" : directory(importer);
        return normalize(base + name);
    }

    /** "a/./b/../c.ba" as "a/c.ba", without a leading "/", or null when it goes above the root or names nothing. */
    static String normalize(String path) {
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
