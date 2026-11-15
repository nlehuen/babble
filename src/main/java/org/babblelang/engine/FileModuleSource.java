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
 */
public final class FileModuleSource implements ModuleSource {
    public static final FileModuleSource INSTANCE = new FileModuleSource();

    private static final String SCHEME = "file:";

    private FileModuleSource() {
    }

    @Override
    public ModuleId resolve(String name, ModuleId importer) throws IOException {
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
    @Override
    public ModuleId identify(String sourceName) {
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

    @Override
    public ModuleText read(ModuleId module) throws IOException {
        String text = Files.readString(file(module));
        return new ModuleText(text, hash(text));
    }

    private static Path file(ModuleId module) throws IOException {
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
        return "files";
    }
}
