package org.babblelang.crib;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight loopback HTTP server serving {@code POST /crib?<options>}, {@code GET /health}, and
 * {@code POST /recycle} over JDK's built-in {@code jdk.httpserver} ({@code BIP041-D1}–{@code D5}).
 */
public final class CribServer implements Closeable {

    private final CribConfig config;
    private final CribWorkerPool pool;
    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final Semaphore pendingPermits;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CribServer(CribConfig config) throws IOException {
        this.config = config;
        this.pool = new CribWorkerPool(config);
        this.pendingPermits = new Semaphore(config.maxPendingRequests(), true);
        int threads = Math.max(4, config.workers() * 2 + 2);
        this.httpExecutor =
                Executors.newFixedThreadPool(
                        threads,
                        r -> {
                            Thread t = new Thread(r, "crib-http");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            this.httpServer =
                    HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            this.httpServer.setExecutor(httpExecutor);
            this.httpServer.createContext("/crib", this::handleCrib);
            this.httpServer.createContext("/health", this::handleHealth);
            this.httpServer.createContext("/recycle", this::handleRecycle);
        } catch (IOException e) {
            pool.close();
            httpExecutor.shutdownNow();
            throw e;
        }
    }

    public void start() {
        httpServer.start();
    }

    public String host() {
        return config.host();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public CribWorkerPool pool() {
        return pool;
    }

    public boolean isWasmAvailable() {
        return pool.isWasmAvailable();
    }

    // ------------------------------------------------------------------ Handlers

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed : expected GET", false);
                return;
            }
            CribWorkerPool.HealthStats stats = pool.health();
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"status\":\"ok\",\"host\":");
            CribProtocol.appendJsonString(sb, config.host());
            sb.append(",\"port\":").append(port());
            sb.append(",\"workers\":{\"configured\":").append(stats.configuredWorkers());
            sb.append(",\"idle\":").append(stats.idleWorkers());
            sb.append(",\"busy\":").append(stats.busyWorkers());
            sb.append("},\"wasmAvailable\":").append(stats.wasmAvailable());
            sb.append(",\"asmVerifyAvailable\":").append(stats.asmVerifyAvailable());
            sb.append(",\"trialsServed\":").append(stats.trialsServed());
            sb.append(",\"workersRecycled\":").append(stats.workersRecycled());
            sb.append(",\"uptimeMs\":").append(stats.uptimeMs());
            sb.append('}');
            sendBody(exchange, 200, "application/json; charset=utf-8", sb.toString());
        } finally {
            exchange.close();
        }
    }

    private void handleRecycle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed : expected POST", false);
                return;
            }
            if (closed.get()) {
                sendError(exchange, 503, "Server is shutting down", false);
                return;
            }
            int recycled = pool.recycleAll();
            sendBody(
                    exchange,
                    200,
                    "application/json; charset=utf-8",
                    "{\"ok\":true,\"recycled\":" + recycled + "}");
        } catch (IOException e) {
            sendError(exchange, 500, "Recycle failed : " + e.getMessage(), false);
        } finally {
            exchange.close();
        }
    }

    private void handleCrib(HttpExchange exchange) throws IOException {
        boolean textFormat = false;
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed : expected POST", false);
                return;
            }
            if (closed.get()) {
                sendError(exchange, 503, "Server is shutting down", false);
                return;
            }

            ParsedQuery query;
            try {
                query = parseQuery(exchange.getRequestURI().getRawQuery());
                textFormat = query.textFormat();
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, e.getMessage(), textFormat);
                return;
            }

            if (query.modes().stream().anyMatch(CribMode::isWasm) && !pool.isWasmAvailable()) {
                sendError(
                        exchange,
                        400,
                        "Wasm execution is unavailable (disabled via --no-wasm or Node.js not found)",
                        textFormat);
                return;
            }

            String payload;
            try {
                payload = readBodyCapped(exchange.getRequestBody(), config.maxBodyBytes());
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, e.getMessage(), textFormat);
                return;
            }
            if (payload.isBlank()) {
                sendError(
                        exchange,
                        400,
                        "Request body must contain a .batest.md payload",
                        textFormat);
                return;
            }

            if (!pendingPermits.tryAcquire()) {
                sendError(exchange, 503, "Server busy : request queue saturated", textFormat);
                return;
            }
            try {
                if (closed.get()) {
                    sendError(exchange, 503, "Server is shutting down", textFormat);
                    return;
                }
                CribProtocol.TrialRequest req =
                        new CribProtocol.TrialRequest(
                                payload,
                                query.sourceName(),
                                query.modes(),
                                query.verify(),
                                query.parity(),
                                query.strict(),
                                query.checkIr(),
                                query.timeoutMs(),
                                query.filter(),
                                query.fresh());
                CribProtocol.TrialResponse resp = pool.evaluate(req);
                int status = resp.parseError() != null ? 400 : 200;
                if (textFormat) {
                    sendBody(
                            exchange,
                            status,
                            "text/plain; charset=utf-8",
                            CribProtocol.formatText(resp));
                } else {
                    sendBody(
                            exchange,
                            status,
                            "application/json; charset=utf-8",
                            CribProtocol.encodeResponse(resp));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendError(exchange, 503, "Request interrupted", textFormat);
            } catch (IllegalStateException ise) {
                sendError(exchange, 503, ise.getMessage(), textFormat);
            } finally {
                pendingPermits.release();
            }
        } catch (IOException e) {
            try {
                sendError(exchange, 500, "Internal server error : " + e.getMessage(), textFormat);
            } catch (IOException ignored) {
                // Client may have disconnected.
            }
        } finally {
            exchange.close();
        }
    }

    // ------------------------------------------------------------------ Query & body parsing

    private record ParsedQuery(
            List<CribMode> modes,
            boolean verify,
            boolean parity,
            boolean strict,
            boolean checkIr,
            long timeoutMs,
            String filter,
            boolean fresh,
            boolean textFormat,
            String sourceName) {}

    private ParsedQuery parseQuery(String rawQuery) {
        String modesRaw = null;
        boolean verify = true;
        boolean parity = true;
        boolean strict = false;
        boolean checkIr = true;
        long timeoutMs = config.defaultTimeoutMs();
        String filter = "";
        boolean fresh = false;
        boolean textFormat = false;
        String sourceName = "crib.batest.md";

        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = decodeUrl(eq >= 0 ? pair.substring(0, eq) : pair).trim();
                String val = eq >= 0 ? decodeUrl(pair.substring(eq + 1)).trim() : null;

                if (val == null) {
                    // Support bare mode shorthands like POST /crib?O1 or POST /crib?interpret,O2
                    modesRaw = modesRaw == null ? key : modesRaw + "," + key;
                    continue;
                }

                switch (key.toLowerCase(Locale.ROOT)) {
                    case "modes", "mode" ->
                            modesRaw = modesRaw == null ? val : modesRaw + "," + val;
                    case "verify" -> verify = parseBooleanStrict("verify", val);
                    case "parity" -> parity = parseBooleanStrict("parity", val);
                    case "strict" -> strict = parseBooleanStrict("strict", val);
                    case "ir" -> checkIr = parseBooleanStrict("ir", val);
                    case "fresh" -> fresh = parseBooleanStrict("fresh", val);
                    case "timeout" -> {
                        long parsed;
                        try {
                            parsed = Long.parseLong(val);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid timeout : '" + val + "'");
                        }
                        if (parsed <= 0) {
                            throw new IllegalArgumentException(
                                    "timeout must be > 0 ms : " + parsed);
                        }
                        timeoutMs = Math.max(50L, Math.min(config.maxTimeoutMs(), parsed));
                    }
                    case "filter" -> filter = val;
                    case "name" -> {
                        if (!val.isBlank()) {
                            sourceName = val;
                        }
                    }
                    case "format" -> {
                        String fmt = val.toLowerCase(Locale.ROOT);
                        if ("json".equals(fmt)) {
                            textFormat = false;
                        } else if ("text".equals(fmt)) {
                            textFormat = true;
                        } else {
                            throw new IllegalArgumentException(
                                    "Unknown format : '" + val + "' (expected json or text)");
                        }
                    }
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown query parameter : '" + key + "'");
                }
            }
        }

        List<CribMode> modes = CribMode.parseList(modesRaw, pool.isWasmAvailable());
        return new ParsedQuery(
                modes,
                verify,
                parity,
                strict,
                checkIr,
                timeoutMs,
                filter,
                fresh,
                textFormat,
                sourceName);
    }

    private static boolean parseBooleanStrict(String name, String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Invalid boolean for '" + name + "' : '" + value + "' (expected true or false)");
    }

    private static String decodeUrl(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String msg) {
            super(msg);
        }
    }

    private static String readBodyCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new PayloadTooLargeException(
                        "Payload exceeds maximum body size (" + maxBytes + " bytes)");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void sendError(HttpExchange exchange, int status, String message, boolean text)
            throws IOException {
        if (text) {
            sendBody(
                    exchange,
                    status,
                    "text/plain; charset=utf-8",
                    "CRIB ERROR : " + message + "\n");
        } else {
            StringBuilder sb = new StringBuilder(message.length() + 48);
            sb.append("{\"ok\":false,\"parseError\":");
            CribProtocol.appendJsonString(sb, message);
            sb.append('}');
            sendBody(exchange, status, "application/json; charset=utf-8", sb.toString());
        }
    }

    private static void sendBody(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        httpServer.stop(0);
        httpExecutor.shutdownNow();
        pool.close();
    }
}
