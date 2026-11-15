package org.babblelang.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Modules in files, the default source. A relative name is resolved against the directory
 * of the importing module, as its display name gives it, else against the working directory.
 * A module's key is its real path, so every spelling of a file, through {@code ..} or a
 * symbolic link, is one module ; its display name is the path as resolved ; its version, a
 * hash of its content.
 * <p>
 * {@link #rooted} gives a source confined to a directory, whose keys and display names are
 * paths relative to it ({@code lib/vec3.ba}), the same on every machine.
 */
public final class FileModuleSource implements ModuleSource {
    public static final FileModuleSource INSTANCE = new FileModuleSource(null);

    private static final String SCHEME = "file:";

    /** The real path of the directory modules are confined to, or null. */
    private final Path root;

    private FileModuleSource(Path root) {
        this.root = root;
    }

    /**
     * The modules under a directory (docs/design/module-sources-and-aot.md §4.2), for a host
     * that confines imports, and for ahead-of-time compilation. Names are resolved as by
     * {@link MapModuleSource} : relative to the importing module's directory, or from the root
     * when they start with "/" ; an import that would leave the directory, through ".." or a
     * symbolic link, finds nothing. Keys and display names are "/"-separated paths relative to
     * the root.
     */
    public static FileModuleSource rooted(Path root) throws IOException {
        return new FileModuleSource(root.toRealPath());
    }

    @Override
    public ModuleId resolve(String name, ModuleId importer) throws IOException {
        if (root != null) {
            return resolveUnderRoot(name, importer);
        }
        Path path;
        try {
            path = Path.of(name);
            if (!path.isAbsolute() && importer != null) {
                Path directory = Path.of(importer.displayName()).getParent();
                if (directory != null) {
                    path = directory.resolve(path);
                }
            }
        } catch (InvalidPathException e) {
            throw new IOException(e.getMessage(), e);
        }
        path = path.normalize();
        return new ModuleId(SCHEME + path.toRealPath(), path.toString());
    }

    /**
     * A script named after a file need not be one, as when a host evaluates a string under a
     * file's name : its imports are still resolved from that file's directory.
     */
    private ModuleId resolveUnderRoot(String name, ModuleId importer) throws IOException {
        String path = LogicalPaths.resolve(name, importer != null ? importer.key() : null);
        if (path == null) {
            return null;
        }
        Path real = root.resolve(path).toRealPath();
        if (!real.startsWith(root)) {
            // A symbolic link out of the root.
            return null;
        }
        String relative = relative(real);
        return new ModuleId(relative, relative);
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    @Override
    public ModuleId identify(String sourceName) {
        if (root != null) {
            return identifyUnderRoot(sourceName);
        }
        Path path;
        try {
            path = Path.of(sourceName).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        try {
            return new ModuleId(SCHEME + path.toRealPath(), path.toString());
        } catch (IOException e) {
            return new ModuleId(SCHEME + path.toAbsolutePath(), path.toString());
        }
    }

    /** A script's name : a path under the root, absolute or relative to it, whether it exists or not. */
    private ModuleId identifyUnderRoot(String sourceName) {
        String path;
        try {
            Path given = Path.of(sourceName);
            if (given.isAbsolute()) {
                Path normalized = given.normalize();
                Path real = Files.exists(normalized) ? normalized.toRealPath() : normalized;
                if (!real.startsWith(root)) {
                    return null;
                }
                path = LogicalPaths.normalize(relative(real));
            } else {
                path = LogicalPaths.normalize(sourceName);
            }
        } catch (InvalidPathException | IOException e) {
            return null;
        }
        return path != null ? new ModuleId(path, path) : null;
    }

    @Override
    public ModuleText read(ModuleId module) throws IOException {
        String text = Files.readString(file(module));
        return new ModuleText(text, hash(text));
    }

    private Path file(ModuleId module) throws IOException {
        if (root != null) {
            return root.resolve(module.key());
        }
        if (!module.key().startsWith(SCHEME)) {
            throw new IOException("Not a file module : " + module.key());
        }
        return Path.of(module.key().substring(SCHEME.length()));
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }

    @Override
    public String toString() {
        return root != null ? "files under " + root : "files";
    }
}
