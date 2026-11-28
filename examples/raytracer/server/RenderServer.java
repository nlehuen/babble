package org.babblelang.raytracer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.FileModuleSource;
import org.babblelang.engine.ModuleId;
import org.babblelang.engine.ModuleSource;
import org.babblelang.engine.ModuleText;
import org.babblelang.engine.impl.natives.java.JavaObject;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaValue;
import org.babblelang.runtime.LogicalPaths;
import org.babblelang.runtime.Module;
import org.babblelang.runtime.ScriptRuntime;

/**
 * Dedicated warm-JVM HTTP render server for fast iteration on Babble raytracer scenes.
 *
 * <p>Embeds a single warm {@link BabbleScriptEngine} rooted at {@code examples/raytracer} with
 * compiler-enforced hermetic sandboxing ({@code allowAxioms(false)}, {@code allowTopLevelIO(false)},
 * and blocked {@code io/} imports for client modules). All {@code core/} and {@code io/jvm/}
 * modules stay compiled and C2-JITted across requests (with automatic SHA-256 hot-reload if any
 * file on disk changes).
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /health} — JSON server status & counters
 *   <li>{@code POST /warmup} — pre-compiles core modules and runs a tiny warmup render
 *   <li>{@code POST /render?<options>} — compiles & verifies a hermetic Babble client module
 *       exporting {@code export def scene = fn (): Scene | Hittable | HittableList -> (...)}
 *       (zero {@code io}, zero {@code axiom}, zero top-level/static-block {@code io}), and returns
 *       {@code image/png} (single view, multi-angle turntable contact sheet, or 4-panel reference
 *       diff strip) or {@code application/json}
 * </ul>
 */
public final class RenderServer implements Closeable {

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PENDING_REQUESTS = 8;
    private static final String BRIDGE_PATH = "io/jvm/render_bridge.ba";

    private static final CallSite SITE_PREPARE =
            new CallSite("<render-server>", 1, "render_bridge.prepareTarget", new String[] {"target"});
    private static final CallSite SITE_RENDER =
            new CallSite("<render-server>", 1, "render_bridge.renderTarget", new String[] {"target"});
    private static final CallSite SITE_WARMUP =
            new CallSite("<render-server>", 1, "render_bridge.warmupBridge", new String[] {"threads"});

    public record Config(
            String host,
            int port,
            Path root,
            int optLevel,
            int defaultThreads,
            boolean warmupOnStart) {
        public static final String DEFAULT_HOST = "127.0.0.1";
        public static final int DEFAULT_PORT = 7332;
        public static final Path DEFAULT_ROOT = Path.of("examples/raytracer");
        public static final int DEFAULT_OPT = 2;
    }

    private final Config config;
    private final Path realRoot;
    private final SandboxModuleSource moduleSource;
    private final BabbleScriptEngine engine;
    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final Semaphore queuePermits = new Semaphore(MAX_PENDING_REQUESTS, true);
    private final Object renderLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong rendersServed = new AtomicLong(0);
    private final long startedAtMs = System.currentTimeMillis();

    public RenderServer(Config config) throws IOException {
        this.config = config;
        this.realRoot = config.root().toRealPath();
        this.moduleSource = new SandboxModuleSource(FileModuleSource.rooted(realRoot));

        this.engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        this.engine.put(BabbleScriptEngine.MODE, BabbleScriptEngine.Mode.COMPILE);
        this.engine.put(BabbleCompiler.OPT_LEVEL, config.optLevel());
        this.engine.put(BabbleScriptEngine.MODULE_SOURCE, moduleSource);
        this.engine.allowAxioms(false);
        this.engine.allowTopLevelIO(false);

        this.httpExecutor =
                Executors.newFixedThreadPool(
                        4,
                        r -> {
                            Thread t = new Thread(r, "render-server-http");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            this.httpServer =
                    HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            this.httpServer.setExecutor(httpExecutor);
            this.httpServer.createContext("/health", this::handleHealth);
            this.httpServer.createContext("/warmup", this::handleWarmup);
            this.httpServer.createContext("/render", this::handleRender);
        } catch (IOException e) {
            httpExecutor.shutdownNow();
            throw e;
        }
    }

    public void start() {
        httpServer.start();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public String host() {
        return config.host();
    }

    public String url() {
        return "http://" + config.host() + ":" + port();
    }

    /** Pre-compiles core modules + bridge and executes a tiny 16x16 render to warm HotSpot JIT. */
    public long warmup() throws ScriptException {
        long t0 = System.nanoTime();
        synchronized (renderLock) {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            ScriptContext ctx = newContext(out, err);
            BabbleSession session = engine.newSession(ctx);
            Module bridge = loadBridge(session);
            ScriptRuntime rt = engine.createRuntime(ctx);
            Callable fn = asCallable(bridge.getMember("warmupBridge", true));
            fn.call(rt, new Object[] {config.defaultThreads()}, null, SITE_WARMUP);
        }
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            httpServer.stop(0);
            httpExecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ HTTP Handlers

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed : expected GET");
                return;
            }
            String json =
                    "{\"status\":\"ok\",\"host\":"
                            + jsonString(config.host())
                            + ",\"port\":"
                            + port()
                            + ",\"root\":"
                            + jsonString(realRoot.toString())
                            + ",\"optLevel\":"
                            + config.optLevel()
                            + ",\"defaultThreads\":"
                            + config.defaultThreads()
                            + ",\"rendersServed\":"
                            + rendersServed.get()
                            + ",\"uptimeMs\":"
                            + (System.currentTimeMillis() - startedAtMs)
                            + "}";
            sendBytes(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private void handleWarmup(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed : expected POST");
                return;
            }
            long ms = warmup();
            String json = "{\"status\":\"ok\",\"warmupMs\":" + ms + "}";
            sendBytes(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            sendText(exchange, 500, "Warmup failed : " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleRender(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed : expected POST");
                return;
            }
            if (closed.get()) {
                sendText(exchange, 503, "Server is shutting down");
                return;
            }

            RenderOptions opts;
            try {
                opts = parseQuery(exchange.getRequestURI().getRawQuery(), config.defaultThreads());
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, "Error : " + e.getMessage());
                return;
            }

            String source;
            try {
                source = readBodyCapped(exchange.getRequestBody(), MAX_BODY_BYTES);
            } catch (PayloadTooLargeException e) {
                sendText(exchange, 413, "Error : " + e.getMessage());
                return;
            }
            if (source.isBlank()) {
                sendText(exchange, 400, "Error : Request body must contain Babble scene source");
                return;
            }

            if (!queuePermits.tryAcquire()) {
                sendText(exchange, 503, "Error : Render queue saturated");
                return;
            }
            try {
                executeRender(exchange, source, opts);
            } finally {
                queuePermits.release();
            }
        } catch (IOException e) {
            try {
                sendText(exchange, 500, "Internal error : " + e.getMessage());
            } catch (IOException ignored) {
                // Client may have disconnected.
            }
        } finally {
            exchange.close();
        }
    }

    private void executeRender(HttpExchange exchange, String source, RenderOptions opts)
            throws IOException {
        long totalStart = System.nanoTime();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        BufferedImage resultImage;
        long compileMs;
        long renderMs;
        int viewCount;
        ImageTools.ScoreResult score = null;

        synchronized (renderLock) {
            ScriptContext ctx = newContext(out, err);
            BabbleSession session = engine.newSession(ctx);
            ScriptRuntime rt = engine.createRuntime(ctx);

            String clientPath;
            try {
                clientPath = resolveVirtualSourceName(source, opts.sourceName());
            } catch (IllegalArgumentException e) {
                sendText(exchange, 400, "Error : " + e.getMessage());
                return;
            }
            long c0 = System.nanoTime();
            Module bridge;
            Object preparedTarget;
            moduleSource.setClientModule(clientPath, source);
            try {
                String harness = buildHermeticHarness(clientPath);
                Object rawTarget = session.eval(harness, "__harness__.ba");
                bridge = loadBridge(session);
                Callable prepareFn = asCallable(bridge.getMember("prepareTarget", true));
                preparedTarget =
                        prepareFn.call(rt, new Object[] {rawTarget}, null, SITE_PREPARE);
            } catch (ScriptException e) {
                String diag = formatDiagnostics(err.toString(), e.getMessage());
                sendText(exchange, 400, diag);
                return;
            } catch (RuntimeException e) {
                String diag = formatDiagnostics(err.toString(), e.getMessage());
                sendText(exchange, 400, diag);
                return;
            } finally {
                moduleSource.clearClientModule();
            }
            compileMs = (System.nanoTime() - c0) / 1_000_000L;

            long r0 = System.nanoTime();
            try {
                Callable renderFn = asCallable(bridge.getMember("renderTarget", true));
                if (!opts.turntable().isEmpty()) {
                    List<ImageTools.Tile> tiles = new ArrayList<>(opts.turntable().size());
                    for (TurntableView view : opts.turntable()) {
                        BufferedImage tileImg =
                                invokeRender(renderFn, rt, preparedTarget, opts, view);
                        tiles.add(new ImageTools.Tile(view.label(), tileImg));
                    }
                    resultImage = ImageTools.composeTurntable(tiles);
                    viewCount = tiles.size();
                } else {
                    resultImage = invokeRender(renderFn, rt, preparedTarget, opts, null);
                    viewCount = 1;
                }
            } catch (RuntimeException e) {
                String diag = formatDiagnostics(err.toString(), e.getMessage());
                sendText(exchange, 400, diag);
                return;
            }
            renderMs = (System.nanoTime() - r0) / 1_000_000L;
        }

        if (opts.refPath() != null) {
            Path resolvedRef = resolveReferencePath(opts.refPath());
            if (resolvedRef == null || !Files.isRegularFile(resolvedRef)) {
                sendText(exchange, 400, "Error : Reference image not found : " + opts.refPath());
                return;
            }
            score = ImageTools.scoreAgainstReference(resolvedRef, resultImage, opts.diff());
            if (opts.diff() && score.diffStrip() != null) {
                resultImage = score.diffStrip();
            }
        }

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000L;
        rendersServed.incrementAndGet();

        exchange.getResponseHeaders().set("X-Compile-Ms", String.valueOf(compileMs));
        exchange.getResponseHeaders().set("X-Render-Ms", String.valueOf(renderMs));
        exchange.getResponseHeaders().set("X-Total-Ms", String.valueOf(totalMs));
        exchange.getResponseHeaders().set("X-Width", String.valueOf(resultImage.getWidth()));
        exchange.getResponseHeaders().set("X-Height", String.valueOf(resultImage.getHeight()));
        exchange.getResponseHeaders().set("X-Views", String.valueOf(viewCount));
        if (score != null) {
            exchange.getResponseHeaders()
                    .set("X-Score-Composite", String.format(Locale.ROOT, "%.2f", score.compositeScore()));
            exchange.getResponseHeaders()
                    .set("X-Score-IoU", String.format(Locale.ROOT, "%.2f", score.silhouetteIoU()));
            exchange.getResponseHeaders()
                    .set("X-Score-FgColor", String.format(Locale.ROOT, "%.2f", score.fgColorScore()));
            exchange.getResponseHeaders()
                    .set("X-Score-SSIM", String.format(Locale.ROOT, "%.2f", score.ssimScore()));
            exchange.getResponseHeaders()
                    .set("X-Score-FullColor", String.format(Locale.ROOT, "%.2f", score.fullColorScore()));
            exchange.getResponseHeaders()
                    .set("X-Score-PSNR", String.format(Locale.ROOT, "%.2f", score.psnrDb()));
        }

        if ("json".equals(opts.format())) {
            String json = encodeJsonResult(resultImage, viewCount, compileMs, renderMs, totalMs, score);
            sendBytes(
                    exchange,
                    200,
                    "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] pngBytes = ImageTools.encodePng(resultImage);
            sendBytes(exchange, 200, "image/png", pngBytes);
        }
    }

    // ------------------------------------------------------------------ Babble invocation helpers

    private ScriptContext newContext(StringWriter out, StringWriter err) {
        SimpleScriptContext ctx = new SimpleScriptContext();
        Bindings bindings = engine.createBindings();
        bindings.put("args", JavaObject.of(new ArrayList<String>()));
        ctx.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        ctx.setWriter(out);
        ctx.setErrorWriter(err);
        return ctx;
    }

    private Module loadBridge(BabbleSession session) throws ScriptException {
        Object mod = session.eval("import(\"/" + BRIDGE_PATH + "\")", "__bridge__.ba");
        if (!(mod instanceof Module m)) {
            throw new IllegalStateException("Expected Module for " + BRIDGE_PATH);
        }
        return m;
    }

    /**
     * Generates a 4-line typed Babble harness that statically imports the client module and binds
     * its {@code export def scene} to a hermetic (non-{@code io}) 0-argument function type
     * returning {@code core.Scene | core.Hittable | core.HittableList}. Combined with {@code
     * engine.allowAxioms(false)}, {@code engine.allowTopLevelIO(false)}, and {@link
     * SandboxModuleSource}, Babble's compiler rejects any {@code io} (top-level, static block,
     * inside {@code scene}, or inside returned geometry/material callbacks) before execution.
     */
    private static String buildHermeticHarness(String clientPath) {
        return "def __core = import(\"/core/core.ba\")\n"
                + "def __client = import(\"/"
                + clientPath
                + "\")\n"
                + "def __scene: fn volatile (): __core.Scene | __core.Hittable | __core.HittableList = __client.scene\n"
                + "__scene()\n";
    }

    private static BufferedImage invokeRender(
            Callable renderFn,
            ScriptRuntime rt,
            Object target,
            RenderOptions opts,
            TurntableView view) {
        boolean hasLookFrom = view == null && opts.hasLookFrom();
        boolean hasOrbit = view != null || opts.hasOrbit();
        double yaw = view != null ? view.yawDeg() : opts.yawDeg();
        double pitch = view != null ? view.pitchDeg() : opts.pitchDeg();
        double dist =
                (view != null && view.dist() > 0.0)
                        ? view.dist()
                        : opts.orbitDist();

        Object[] args = {
            target,
            opts.width(),
            opts.aspectRatio(),
            opts.spp(),
            opts.maxDepth(),
            opts.hasSeed(),
            opts.seed(),
            opts.threads(),
            opts.vfov(),
            hasLookFrom,
            opts.fromX(),
            opts.fromY(),
            opts.fromZ(),
            opts.hasLookAt(),
            opts.atX(),
            opts.atY(),
            opts.atZ(),
            hasOrbit,
            yaw,
            pitch,
            dist,
            opts.bgMode(),
            opts.bgR(),
            opts.bgG(),
            opts.bgB()
        };
        Object raw = renderFn.call(rt, args, null, SITE_RENDER);
        if (raw instanceof JavaValue jv) {
            raw = jv.javaValue();
        }
        if (!(raw instanceof BufferedImage img)) {
            throw new IllegalStateException(
                    "Expected BufferedImage from renderTarget, got "
                            + (raw == null ? "null" : raw.getClass().getName()));
        }
        return img;
    }

    private static Callable asCallable(Object obj) {
        if (obj instanceof Callable c) {
            return c;
        }
        throw new IllegalStateException("Expected Callable, got " + obj);
    }

    private static String resolveVirtualSourceName(String source, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            String normalized = LogicalPaths.normalize(explicitName.trim());
            if (normalized == null
                    || !normalized.endsWith(".ba")
                    || normalized.startsWith("io/")
                    || normalized.equals("__bridge__.ba")
                    || normalized.equals("__harness__.ba")) {
                throw new IllegalArgumentException(
                        "Invalid client sourceName (must be a .ba path outside io/) : "
                                + explicitName);
            }
            return normalized;
        }
        if (source.contains("import(\"../core/")) {
            return "scenes/__client__.ba";
        }
        return "__client__.ba";
    }

    private Path resolveReferencePath(String ref) {
        Path direct = realRoot.resolve(ref).normalize();
        if (direct.startsWith(realRoot) && Files.isRegularFile(direct)) {
            try {
                Path real = direct.toRealPath();
                if (real.startsWith(realRoot)) {
                    return real;
                }
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Sandboxed {@link ModuleSource} serving one virtual client module in memory (keyed by fixed
     * logical path and SHA-256 content version for cache reuse without unbounded cache growth) and
     * preventing client modules from importing anything under {@code io/}.
     */
    private static final class SandboxModuleSource implements ModuleSource {
        private final FileModuleSource delegate;
        private volatile String clientPath;
        private volatile ModuleText clientText;

        SandboxModuleSource(FileModuleSource delegate) {
            this.delegate = delegate;
        }

        void setClientModule(String path, String source) {
            this.clientPath = path;
            this.clientText = new ModuleText(source, sha256(source));
        }

        void clearClientModule() {
            this.clientPath = null;
            this.clientText = null;
        }

        @Override
        public ModuleId resolve(String name, ModuleId importer) throws IOException {
            String cp = clientPath;
            String logical = LogicalPaths.resolve(name, importer != null ? importer.key() : null);
            if (cp != null && cp.equals(logical)) {
                return new ModuleId(cp, cp);
            }
            ModuleId resolved = delegate.resolve(name, importer);
            if (resolved != null && resolved.key().startsWith("io/")) {
                boolean trustedImporter =
                        importer != null
                                && ("__bridge__.ba".equals(importer.key())
                                        || importer.key().startsWith("io/"));
                if (!trustedImporter) {
                    throw new IOException(
                            "Import under io/ is forbidden in hermetic client modules : "
                                    + resolved.key());
                }
            }
            return resolved;
        }

        @Override
        public ModuleId identify(String sourceName) {
            String cp = clientPath;
            if (cp != null && cp.equals(sourceName)) {
                return new ModuleId(cp, cp);
            }
            return delegate.identify(sourceName);
        }

        @Override
        public ModuleText read(ModuleId module) throws IOException {
            String cp = clientPath;
            ModuleText ct = clientText;
            if (cp != null && ct != null && cp.equals(module.key())) {
                return ct;
            }
            return delegate.read(module);
        }

        @Override
        public String version(ModuleId module) throws IOException {
            String cp = clientPath;
            ModuleText ct = clientText;
            if (cp != null && ct != null && cp.equals(module.key())) {
                return ct.version();
            }
            return delegate.version(module);
        }

        private static String sha256(String text) {
            try {
                return HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    // ------------------------------------------------------------------ Query parsing

    private record TurntableView(String label, double yawDeg, double pitchDeg, double dist) {}

    private record RenderOptions(
            int width,
            double aspectRatio,
            int spp,
            int maxDepth,
            boolean hasSeed,
            int seed,
            int threads,
            double vfov,
            boolean hasLookFrom,
            double fromX,
            double fromY,
            double fromZ,
            boolean hasLookAt,
            double atX,
            double atY,
            double atZ,
            boolean hasOrbit,
            double yawDeg,
            double pitchDeg,
            double orbitDist,
            int bgMode,
            double bgR,
            double bgG,
            double bgB,
            List<TurntableView> turntable,
            String refPath,
            boolean diff,
            String format,
            String sourceName) {}

    private static RenderOptions parseQuery(String rawQuery, int defaultThreads) {
        int width = 0;
        double aspect = 0.0;
        int spp = 0;
        int maxDepth = 0;
        boolean hasSeed = false;
        int seed = 42;
        int threads = defaultThreads;
        double vfov = 0.0;
        boolean hasLookFrom = false;
        double fromX = 0.0;
        double fromY = 0.0;
        double fromZ = 10.0;
        boolean hasLookAt = false;
        double atX = 0.0;
        double atY = 0.0;
        double atZ = 0.0;
        boolean hasOrbit = false;
        double yaw = 0.0;
        double pitch = 0.0;
        double dist = 0.0;
        int bgMode = 0;
        double bgR = 1.0;
        double bgG = 1.0;
        double bgB = 1.0;
        List<TurntableView> turntable = List.of();
        String refPath = null;
        boolean diff = false;
        String format = "png";
        String sourceName = null;

        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k =
                        URLDecoder.decode(
                                eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
                String v =
                        eq >= 0
                                ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                                : "true";
                switch (k) {
                    case "width", "w" -> width = parsePositiveInt(v, k);
                    case "aspect" -> aspect = parseAspect(v);
                    case "spp", "samples" -> spp = parsePositiveInt(v, k);
                    case "depth", "maxDepth" -> maxDepth = parsePositiveInt(v, k);
                    case "seed" -> {
                        hasSeed = true;
                        seed = parseInt(v, k);
                    }
                    case "threads", "t" -> threads = parsePositiveInt(v, k);
                    case "vfov", "fov" -> vfov = parsePositiveDouble(v, k);
                    case "lookFrom", "from" -> {
                        double[] vec = parseVec3(v, k);
                        hasLookFrom = true;
                        fromX = vec[0];
                        fromY = vec[1];
                        fromZ = vec[2];
                    }
                    case "lookAt", "at" -> {
                        double[] vec = parseVec3(v, k);
                        hasLookAt = true;
                        atX = vec[0];
                        atY = vec[1];
                        atZ = vec[2];
                    }
                    case "yaw" -> {
                        hasOrbit = true;
                        yaw = parseDouble(v, k);
                    }
                    case "pitch" -> {
                        hasOrbit = true;
                        pitch = parseDouble(v, k);
                    }
                    case "dist", "distance" -> {
                        hasOrbit = true;
                        dist = parsePositiveDouble(v, k);
                    }
                    case "bg", "background" -> {
                        String lower = v.trim().toLowerCase(Locale.ROOT);
                        switch (lower) {
                            case "sky" -> bgMode = 1;
                            case "white" -> {
                                bgMode = 2;
                                bgR = 1.0;
                                bgG = 1.0;
                                bgB = 1.0;
                            }
                            case "black" -> {
                                bgMode = 2;
                                bgR = 0.0;
                                bgG = 0.0;
                                bgB = 0.0;
                            }
                            default -> {
                                double[] rgb = parseVec3(v, k);
                                bgMode = 2;
                                bgR = rgb[0];
                                bgG = rgb[1];
                                bgB = rgb[2];
                            }
                        }
                    }
                    case "turntable" -> turntable = parseTurntable(v);
                    case "ref", "reference" -> refPath = v;
                    case "diff" -> diff = parseBool(v, k);
                    case "format" -> {
                        format = v.trim().toLowerCase(Locale.ROOT);
                        if (!format.equals("png") && !format.equals("json")) {
                            throw new IllegalArgumentException(
                                    "Invalid format (expected png or json) : " + v);
                        }
                    }
                    case "source", "sourceName" -> sourceName = v;
                    default -> throw new IllegalArgumentException("Unknown query option : " + k);
                }
            }
        }
        return new RenderOptions(
                width, aspect, spp, maxDepth, hasSeed, seed, threads, vfov, hasLookFrom, fromX,
                fromY, fromZ, hasLookAt, atX, atY, atZ, hasOrbit, yaw, pitch, dist, bgMode, bgR,
                bgG, bgB, turntable, refPath, diff, format, sourceName);
    }

    private static List<TurntableView> parseTurntable(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "true".equalsIgnoreCase(trimmed) || "6".equals(trimmed)) {
            return List.of(
                    new TurntableView("Front (0°)", 0.0, 0.0, 0.0),
                    new TurntableView("Right (+30°)", 30.0, 0.0, 0.0),
                    new TurntableView("Left (-30°)", -30.0, 0.0, 0.0),
                    new TurntableView("Top 3/4 (+25°, +35°)", 25.0, 35.0, 0.0),
                    new TurntableView("Side (+75°)", 75.0, 5.0, 0.0),
                    new TurntableView("Back (180°)", 180.0, 0.0, 0.0));
        }
        List<TurntableView> views = new ArrayList<>();
        for (String token : trimmed.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            switch (t) {
                case "front" -> views.add(new TurntableView("Front (0°)", 0.0, 0.0, 0.0));
                case "right30" -> views.add(new TurntableView("Right (+30°)", 30.0, 0.0, 0.0));
                case "left30" -> views.add(new TurntableView("Left (-30°)", -30.0, 0.0, 0.0));
                case "top", "top35" ->
                        views.add(new TurntableView("Top 3/4 (+25°, +35°)", 25.0, 35.0, 0.0));
                case "side", "side75" ->
                        views.add(new TurntableView("Side (+75°)", 75.0, 5.0, 0.0));
                case "side90" -> views.add(new TurntableView("Profile (+90°)", 90.0, 0.0, 0.0));
                case "left90" -> views.add(new TurntableView("Profile (-90°)", -90.0, 0.0, 0.0));
                case "back" -> views.add(new TurntableView("Back (180°)", 180.0, 0.0, 0.0));
                default -> {
                    // Format: yaw or yaw:pitch
                    int colon = t.indexOf(':');
                    if (colon >= 0) {
                        double y = parseDouble(t.substring(0, colon), "turntable yaw");
                        double p = parseDouble(t.substring(colon + 1), "turntable pitch");
                        views.add(
                                new TurntableView(
                                        String.format(Locale.ROOT, "Yaw %.0f° Pitch %.0f°", y, p),
                                        y,
                                        p,
                                        0.0));
                    } else {
                        double y = parseDouble(t, "turntable view");
                        views.add(
                                new TurntableView(
                                        String.format(Locale.ROOT, "Yaw %.0f°", y), y, 0.0, 0.0));
                    }
                }
            }
        }
        if (views.isEmpty()) {
            throw new IllegalArgumentException("Empty turntable specification : " + raw);
        }
        return views;
    }

    private static double parseAspect(String raw) {
        int slash = raw.indexOf('/');
        if (slash > 0 && slash < raw.length() - 1) {
            double num = parsePositiveDouble(raw.substring(0, slash), "aspect");
            double den = parsePositiveDouble(raw.substring(slash + 1), "aspect");
            return num / den;
        }
        return parsePositiveDouble(raw, "aspect");
    }

    private static double[] parseVec3(String raw, String name) {
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Invalid " + name + " (expected x,y,z) : " + raw);
        }
        return new double[] {
            parseDouble(parts[0], name),
            parseDouble(parts[1], name),
            parseDouble(parts[2], name)
        };
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + name + " : " + raw);
        }
    }

    private static int parsePositiveInt(String raw, String name) {
        int v = parseInt(raw, name);
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0 : " + raw);
        }
        return v;
    }

    private static double parseDouble(String raw, String name) {
        try {
            double v = Double.parseDouble(raw.trim());
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Non-finite number for " + name + " : " + raw);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number for " + name + " : " + raw);
        }
    }

    private static double parsePositiveDouble(String raw, String name) {
        double v = parseDouble(raw, name);
        if (v <= 0.0) {
            throw new IllegalArgumentException(name + " must be > 0 : " + raw);
        }
        return v;
    }

    private static boolean parseBool(String raw, String name) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean for " + name + " : " + raw);
        };
    }

    // ------------------------------------------------------------------ IO & JSON helpers

    private static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    private static String readBodyCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new PayloadTooLargeException(
                        "Request body exceeds limit of " + maxBytes + " bytes");
            }
            baos.write(buf, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static String formatDiagnostics(String stderrText, String exceptionMessage) {
        String trimmedErr = stderrText != null ? stderrText.trim() : "";
        String trimmedMsg = exceptionMessage != null ? exceptionMessage.trim() : "";
        if (!trimmedErr.isEmpty() && !trimmedMsg.isEmpty() && !trimmedErr.contains(trimmedMsg)) {
            return trimmedErr + "\n" + trimmedMsg + "\n";
        }
        if (!trimmedErr.isEmpty()) {
            return trimmedErr + "\n";
        }
        return (trimmedMsg.isEmpty() ? "Render failed" : trimmedMsg) + "\n";
    }

    private static String encodeJsonResult(
            BufferedImage img,
            int views,
            long compileMs,
            long renderMs,
            long totalMs,
            ImageTools.ScoreResult score) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ok\":true,\"width\":").append(img.getWidth());
        sb.append(",\"height\":").append(img.getHeight());
        sb.append(",\"views\":").append(views);
        sb.append(",\"compileMs\":").append(compileMs);
        sb.append(",\"renderMs\":").append(renderMs);
        sb.append(",\"totalMs\":").append(totalMs);
        if (score != null) {
            sb.append(",\"score\":{");
            sb.append("\"composite\":").append(score.compositeScore());
            sb.append(",\"silhouetteIoU\":").append(score.silhouetteIoU());
            sb.append(",\"fgColor\":").append(score.fgColorScore());
            sb.append(",\"ssim\":").append(score.ssimScore());
            sb.append(",\"fullColor\":").append(score.fullColorScore());
            sb.append(",\"fgRmse\":").append(score.fgRmse());
            sb.append(",\"fullRmse\":").append(score.fullRmse());
            sb.append(",\"psnrDb\":").append(score.psnrDb());
            sb.append(",\"fgTp\":").append(score.fgTp());
            sb.append(",\"fgFn\":").append(score.fgFn());
            sb.append(",\"fgFp\":").append(score.fgFp());
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void sendText(HttpExchange exchange, int status, String text)
            throws IOException {
        sendBytes(
                exchange,
                status,
                "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(
            HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ------------------------------------------------------------------ CLI Entrypoint

    private static final String USAGE =
            """
            Usage : ./examples/raytracer/render_server.sh [options]
              Dedicated warm-JVM HTTP render server embedding Babble for fast scene iteration.
              --host <addr>       bind address (default : 127.0.0.1)
              --port <n>          listen port, 0 for ephemeral (default : 7332)
              --root <dir>        raytracer module root (default : examples/raytracer)
              --opt <0|1|2>       Babble compiler optimization level (default : 2)
              --threads <n>       default render worker threads (default : min(16, cores))
              --no-warmup         skip startup JIT/module warmup
              -h, --help          this text\
            """;

    public static void main(String[] args) throws Exception {
        String host = Config.DEFAULT_HOST;
        int port = Config.DEFAULT_PORT;
        Path root = Config.DEFAULT_ROOT;
        int opt = Config.DEFAULT_OPT;
        int threads = Math.min(16, Math.max(1, Runtime.getRuntime().availableProcessors()));
        boolean warmup = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "-help", "--help" -> {
                    System.out.println(USAGE);
                    return;
                }
                case "--host", "-b" -> host = requireArg(args, ++i, args[i - 1]);
                case "--port", "-p" -> port = parseInt(requireArg(args, ++i, args[i - 1]), "--port");
                case "--root", "-d" -> root = Path.of(requireArg(args, ++i, args[i - 1]));
                case "--opt" -> opt = parseInt(requireArg(args, ++i, args[i - 1]), "--opt");
                case "--threads", "-t" ->
                        threads = parsePositiveInt(requireArg(args, ++i, args[i - 1]), "--threads");
                case "--no-warmup" -> warmup = false;
                default -> {
                    System.err.println("Error : Unknown option : " + args[i]);
                    System.err.println(USAGE);
                    System.exit(2);
                }
            }
        }

        Config cfg = new Config(host, port, root, opt, threads, warmup);
        try (RenderServer server = new RenderServer(cfg)) {
            if (warmup) {
                long warmMs = server.warmup();
                System.out.println("Warmed up Babble raytracer modules & JIT in " + warmMs + " ms");
            }
            server.start();
            System.out.println(
                    "RenderServer listening on "
                            + server.url()
                            + " (root="
                            + server.realRoot
                            + ", -O"
                            + cfg.optLevel()
                            + ", threads="
                            + cfg.defaultThreads()
                            + ")");
            System.out.flush();

            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        server.close();
                                        latch.countDown();
                                    },
                                    "render-server-shutdown"));
            latch.await();
        }
    }

    private static String requireArg(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[idx];
    }
}
