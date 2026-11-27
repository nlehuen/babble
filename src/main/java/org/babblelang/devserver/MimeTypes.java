package org.babblelang.devserver;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit, OS-independent MIME types for {@code -dev-server}, ensuring modern web and WebAssembly
 * assets ({@code .html}, {@code .js}, {@code .mjs}, {@code .wasm}, ...) are always served with the
 * media types browsers require.
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXTENSION =
            Map.ofEntries(
                    // Core web and WebAssembly types
                    Map.entry("html", "text/html; charset=utf-8"),
                    Map.entry("htm", "text/html; charset=utf-8"),
                    Map.entry("js", "text/javascript; charset=utf-8"),
                    Map.entry("mjs", "text/javascript; charset=utf-8"),
                    Map.entry("cjs", "text/javascript; charset=utf-8"),
                    Map.entry("wasm", "application/wasm"),
                    // Common web assets
                    Map.entry("css", "text/css; charset=utf-8"),
                    Map.entry("json", "application/json; charset=utf-8"),
                    Map.entry("map", "application/json; charset=utf-8"),
                    Map.entry("svg", "image/svg+xml"),
                    Map.entry("png", "image/png"),
                    Map.entry("jpg", "image/jpeg"),
                    Map.entry("jpeg", "image/jpeg"),
                    Map.entry("gif", "image/gif"),
                    Map.entry("webp", "image/webp"),
                    Map.entry("ico", "image/x-icon"),
                    Map.entry("txt", "text/plain; charset=utf-8"),
                    Map.entry("md", "text/markdown; charset=utf-8"),
                    Map.entry("ba", "text/plain; charset=utf-8"),
                    Map.entry("wat", "text/plain; charset=utf-8"),
                    Map.entry("ppm", "text/plain; charset=utf-8"),
                    Map.entry("xml", "application/xml; charset=utf-8"),
                    Map.entry("woff", "font/woff"),
                    Map.entry("woff2", "font/woff2"),
                    Map.entry("ttf", "font/ttf"),
                    Map.entry("otf", "font/otf"),
                    Map.entry("mp3", "audio/mpeg"),
                    Map.entry("wav", "audio/wav"),
                    Map.entry("ogg", "audio/ogg"),
                    Map.entry("mp4", "video/mp4"),
                    Map.entry("webm", "video/webm"),
                    Map.entry("pdf", "application/pdf"));

    private MimeTypes() {}

    /**
     * Returns the explicit MIME type override for a file path, or {@code null} if {@link
     * com.sun.net.httpserver.SimpleFileServer}'s default media type should be kept.
     */
    public static String forPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        return forFileName(path.getFileName().toString());
    }

    /**
     * Returns the explicit MIME type override for a file name or URI path, or {@code null} if none
     * is defined.
     */
    public static String forFileName(String pathOrFileName) {
        if (pathOrFileName == null || pathOrFileName.isEmpty() || pathOrFileName.endsWith("/")) {
            return null;
        }
        int slash = pathOrFileName.lastIndexOf('/');
        String fileName = slash >= 0 ? pathOrFileName.substring(slash + 1) : pathOrFileName;
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.get(ext);
    }

    /**
     * Resolves the {@code Content-Type} to use for a {@code 200 OK} response served for {@code
     * requestPath}. Returns {@code null} when {@code existingContentType} should be kept as-is.
     */
    public static String resolveOverride(String requestPath, String existingContentType) {
        String byExt = forFileName(requestPath);
        if (byExt != null) {
            return byExt;
        }
        if (existingContentType != null && existingContentType.equalsIgnoreCase("text/html")) {
            return "text/html; charset=utf-8";
        }
        return null;
    }
}
