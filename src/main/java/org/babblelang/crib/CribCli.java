package org.babblelang.crib;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line front end for {@code java -jar babble.jar -crib [options]} ({@code BIP041} §5.1).
 */
public final class CribCli {

    public static final String USAGE =
            """
            Usage : java -jar babble.jar -crib [options]
              Starts a fast, isolated HTTP .batest.md runner for adversarial testing (BIP041).
              --host <addr>                    bind address (default : 127.0.0.1)
              --port <n>                       listen port, 0 for ephemeral (default : 7331)
              --workers <n>                    warm worker JVM pool size (default : 1)
              --timeout <ms>                   default per-(case, mode) timeout in ms (default : 5000)
              --max-timeout <ms>               maximum client-requested timeout in ms (default : 60000)
              --max-requests-per-worker <n>    recycle a worker after N trials, 0 disables (default : 200)
              --max-body-bytes <n>             maximum POST /crib payload size (default : 1048576)
              --worker-xmx <size>              worker JVM -Xmx heap cap (default : 512m)
              --worker-metaspace <size>        worker JVM -XX:MaxMetaspaceSize cap (default : 256m)
              --no-wasm                        disable optional wasm/wasm-O1/wasm-O2 modes
              -help                            this text\
            """;

    private static final int FAILED = 1;
    private static final int USAGE_ERROR = 2;

    private CribCli() {}

    /**
     * Parses {@code -crib} options into a {@link CribConfig}, returning {@code null} on {@code
     * -help}.
     */
    public static CribConfig parseArgs(String[] args) {
        String host = CribConfig.DEFAULT_HOST;
        int port = CribConfig.DEFAULT_PORT;
        int workers = CribConfig.DEFAULT_WORKERS;
        long timeoutMs = CribConfig.DEFAULT_TIMEOUT_MS;
        long maxTimeoutMs = CribConfig.DEFAULT_MAX_TIMEOUT_MS;
        int maxRequestsPerWorker = CribConfig.DEFAULT_MAX_REQUESTS_PER_WORKER;
        int maxBodyBytes = CribConfig.DEFAULT_MAX_BODY_BYTES;
        String workerXmx = CribConfig.DEFAULT_WORKER_XMX;
        String workerMetaspace = CribConfig.DEFAULT_WORKER_METASPACE;
        boolean wasmEnabled = true;

        for (int i = 0; i < args.length; i++) {
            String opt = args[i];
            switch (opt) {
                case "-help", "--help", "-h" -> {
                    return null;
                }
                case "--no-wasm", "-no-wasm" -> wasmEnabled = false;
                case "--host", "-host" -> host = requireValue(args, ++i, opt);
                case "--port", "-port" -> port = parseInt(requireValue(args, ++i, opt), opt);
                case "--workers", "-workers" ->
                        workers = parseInt(requireValue(args, ++i, opt), opt);
                case "--timeout", "-timeout" ->
                        timeoutMs = parseLong(requireValue(args, ++i, opt), opt);
                case "--max-timeout", "-max-timeout" ->
                        maxTimeoutMs = parseLong(requireValue(args, ++i, opt), opt);
                case "--max-requests-per-worker", "-max-requests-per-worker" ->
                        maxRequestsPerWorker = parseInt(requireValue(args, ++i, opt), opt);
                case "--max-body-bytes", "-max-body-bytes" ->
                        maxBodyBytes = parseInt(requireValue(args, ++i, opt), opt);
                case "--worker-xmx", "-worker-xmx" -> workerXmx = requireValue(args, ++i, opt);
                case "--worker-metaspace", "-worker-metaspace" ->
                        workerMetaspace = requireValue(args, ++i, opt);
                default -> throw new IllegalArgumentException("Unknown option : " + opt);
            }
        }
        return new CribConfig(
                host,
                port,
                workers,
                timeoutMs,
                maxTimeoutMs,
                maxRequestsPerWorker,
                maxBodyBytes,
                workerXmx,
                workerMetaspace,
                wasmEnabled,
                Math.max(8, workers * 8));
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        CribConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("Error : " + e.getMessage());
            err.println(USAGE);
            err.flush();
            return USAGE_ERROR;
        }
        if (config == null) {
            out.println(USAGE);
            out.flush();
            return 0;
        }

        if (!config.isLoopbackHost()) {
            err.println(
                    "Warning : binding -crib to non-loopback address "
                            + config.host()
                            + " exposes arbitrary Babble/JVM execution over the network");
            err.flush();
        }

        try (CribServer server = new CribServer(config)) {
            server.start();
            String wasmStatus =
                    !config.wasmEnabled()
                            ? "disabled"
                            : (server.isWasmAvailable() ? "available" : "unavailable");
            out.println(
                    "Crib server listening on http://"
                            + server.host()
                            + ":"
                            + server.port()
                            + " (workers="
                            + config.workers()
                            + ", timeout="
                            + config.defaultTimeoutMs()
                            + "ms, wasm="
                            + wasmStatus
                            + ")");
            out.flush();

            CountDownLatch stopLatch = new CountDownLatch(1);
            Thread shutdown =
                    new Thread(
                            () -> {
                                server.close();
                                stopLatch.countDown();
                            },
                            "crib-cli-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                stopLatch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM already shutting down.
                }
            }
            return 0;
        } catch (IOException e) {
            err.println("Error : Failed to start -crib server : " + e.getMessage());
            err.flush();
            return FAILED;
        }
    }

    private static String requireValue(String[] args, int index, String opt) {
        if (index >= args.length) {
            throw new IllegalArgumentException(opt + " requires a value");
        }
        return args[index];
    }

    private static int parseInt(String raw, String opt) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + opt + " : " + raw);
        }
    }

    private static long parseLong(String raw, String opt) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + opt + " : " + raw);
        }
    }
}
