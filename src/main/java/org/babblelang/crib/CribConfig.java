package org.babblelang.crib;

/**
 * Immutable configuration for the {@code -crib} HTTP server and its warm worker JVM pool ({@code
 * BIP041-D1}, {@code BIP041-D5}).
 */
public record CribConfig(
        String host,
        int port,
        int workers,
        long defaultTimeoutMs,
        long maxTimeoutMs,
        int maxRequestsPerWorker,
        int maxBodyBytes,
        String workerXmx,
        String workerMetaspace,
        boolean wasmEnabled,
        int maxPendingRequests) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7331;
    public static final int DEFAULT_WORKERS = 1;
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    public static final long DEFAULT_MAX_TIMEOUT_MS = 60_000L;
    public static final int DEFAULT_MAX_REQUESTS_PER_WORKER = 200;
    public static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;
    public static final String DEFAULT_WORKER_XMX = "512m";
    public static final String DEFAULT_WORKER_METASPACE = "256m";

    public CribConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535 : " + port);
        }
        if (workers < 1 || workers > 64) {
            throw new IllegalArgumentException("workers must be in 1..64 : " + workers);
        }
        if (defaultTimeoutMs < 50) {
            throw new IllegalArgumentException("timeout must be >= 50 ms : " + defaultTimeoutMs);
        }
        if (maxTimeoutMs < defaultTimeoutMs) {
            throw new IllegalArgumentException(
                    "maxTimeoutMs ("
                            + maxTimeoutMs
                            + ") must be >= timeout ("
                            + defaultTimeoutMs
                            + ")");
        }
        if (maxRequestsPerWorker < 0) {
            throw new IllegalArgumentException(
                    "maxRequestsPerWorker must be >= 0 : " + maxRequestsPerWorker);
        }
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1024 : " + maxBodyBytes);
        }
        if (workerXmx == null || workerXmx.isBlank()) {
            throw new IllegalArgumentException("workerXmx must not be blank");
        }
        if (workerMetaspace == null || workerMetaspace.isBlank()) {
            throw new IllegalArgumentException("workerMetaspace must not be blank");
        }
        if (maxPendingRequests < 1) {
            throw new IllegalArgumentException(
                    "maxPendingRequests must be >= 1 : " + maxPendingRequests);
        }
    }

    public static CribConfig defaults() {
        return new CribConfig(
                DEFAULT_HOST,
                DEFAULT_PORT,
                DEFAULT_WORKERS,
                DEFAULT_TIMEOUT_MS,
                DEFAULT_MAX_TIMEOUT_MS,
                DEFAULT_MAX_REQUESTS_PER_WORKER,
                DEFAULT_MAX_BODY_BYTES,
                DEFAULT_WORKER_XMX,
                DEFAULT_WORKER_METASPACE,
                true,
                Math.max(8, DEFAULT_WORKERS * 8));
    }

    public boolean isLoopbackHost() {
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("127.0.0.1")
                || h.equals("localhost")
                || h.equals("::1")
                || h.equals("[::1]");
    }
}
