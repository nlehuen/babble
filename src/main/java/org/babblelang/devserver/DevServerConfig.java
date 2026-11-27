package org.babblelang.devserver;

import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable configuration for the {@code -dev-server} static HTTP server, mirroring {@code java -m
 * jdk.httpserver} with extra MIME types for web and WebAssembly assets.
 */
public record DevServerConfig(
        String bindAddress, int port, Path directory, OutputLevel outputLevel) {

    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8000;
    public static final Path DEFAULT_DIRECTORY = Path.of(".");
    public static final OutputLevel DEFAULT_OUTPUT_LEVEL = OutputLevel.INFO;

    public DevServerConfig {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535 : " + port);
        }
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(outputLevel, "outputLevel must not be null");
    }

    public static DevServerConfig defaults() {
        return new DevServerConfig(
                DEFAULT_BIND_ADDRESS, DEFAULT_PORT, DEFAULT_DIRECTORY, DEFAULT_OUTPUT_LEVEL);
    }

    public boolean isLoopbackAddress() {
        String h = bindAddress.trim().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1")
                || h.equals("localhost")
                || h.equals("::1")
                || h.equals("[::1]");
    }
}
