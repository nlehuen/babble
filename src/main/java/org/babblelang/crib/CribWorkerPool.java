package org.babblelang.crib;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a bounded pool of warm {@link CribWorkerMain} JVM subprocesses ({@code BIP041-D1}).
 *
 * <p>Guarantees that user Babble code never runs inside the coordinator JVM: normal trials execute
 * in-process inside a warm pre-JITted worker (~10–50 ms), while hung, crashed, OOM'd, {@code
 * System.exit}'d, or poison-flagged workers are forcibly killed ({@link Process#destroyForcibly()})
 * and transparently replaced.
 */
public final class CribWorkerPool implements Closeable {

    private static final long WORKER_START_TIMEOUT_MS = 30_000L;

    public record HealthStats(
            int configuredWorkers,
            int idleWorkers,
            int busyWorkers,
            boolean wasmAvailable,
            boolean asmVerifyAvailable,
            long trialsServed,
            long workersRecycled,
            long uptimeMs) {}

    private static final class WorkerHandle {
        final Process process;
        final BufferedWriter in;
        final BufferedReader out;
        final ExecutorService readerExec;
        final boolean asmVerifyAvailable;
        final boolean wasmAvailable;
        int requestsServed;

        WorkerHandle(CribConfig config, Set<Process> liveProcesses) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(resolveJavaBinary());
            cmd.add("-Xmx" + config.workerXmx());
            cmd.add("-XX:MaxMetaspaceSize=" + config.workerMetaspace());
            // A warm worker would otherwise throw message-less preallocated exceptions from hot
            // sites, a spurious difference for cross-mode error parity.
            cmd.add("-XX:-OmitStackTraceInFastThrow");
            cmd.add("-Djava.awt.headless=true");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(CribWorkerMain.class.getName());
            if (!config.wasmEnabled()) {
                cmd.add("--no-wasm");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            this.process = pb.start();
            liveProcesses.add(this.process);
            this.in =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.out =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            this.readerExec =
                    Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "crib-worker-io");
                        t.setDaemon(true);
                        return t;
                    });

            try {
                Future<String> readyFuture = readerExec.submit(out::readLine);
                String readyLine = readyFuture.get(WORKER_START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (readyLine == null) {
                    throw new IOException(
                            "Worker JVM exited before signaling ready (exit="
                                    + safeExitCode(process)
                                    + ")");
                }
                Map<String, Object> ready = CribProtocol.parseObject(readyLine);
                if (!Boolean.TRUE.equals(ready.get("ready"))) {
                    throw new IOException("Unexpected worker ready payload : " + readyLine);
                }
                this.asmVerifyAvailable = Boolean.TRUE.equals(ready.get("asmVerifyAvailable"));
                this.wasmAvailable = Boolean.TRUE.equals(ready.get("wasmAvailable"));
            } catch (Exception e) {
                destroy(liveProcesses);
                if (e instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Failed to initialize worker JVM : " + e.getMessage(), e);
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void destroy(Set<Process> liveProcesses) {
            liveProcesses.remove(process);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            readerExec.shutdownNow();
        }
    }

    private final CribConfig config;
    private final ArrayBlockingQueue<WorkerHandle> idlePool;
    private final Set<Process> liveProcesses = ConcurrentHashMap.newKeySet();
    private final ExecutorService respawnExec =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "crib-worker-respawn");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger busyCount = new AtomicInteger(0);
    private final AtomicLong trialsServed = new AtomicLong(0);
    private final AtomicLong workersRecycled = new AtomicLong(0);
    private final long startedAtNanos = System.nanoTime();
    private final Thread shutdownHook;

    private volatile boolean asmVerifyAvailable;
    private volatile boolean wasmAvailable;

    public CribWorkerPool(CribConfig config) throws IOException {
        this.config = config;
        this.idlePool = new ArrayBlockingQueue<>(config.workers());
        this.shutdownHook =
                new Thread(
                        () -> {
                            for (Process p : liveProcesses) {
                                if (p.isAlive()) {
                                    p.destroyForcibly();
                                }
                            }
                        },
                        "crib-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            for (int i = 0; i < config.workers(); i++) {
                WorkerHandle w = new WorkerHandle(config, liveProcesses);
                this.asmVerifyAvailable = w.asmVerifyAvailable;
                this.wasmAvailable = w.wasmAvailable;
                idlePool.add(w);
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public boolean isWasmAvailable() {
        return config.wasmEnabled() && wasmAvailable;
    }

    public boolean isAsmVerifyAvailable() {
        return asmVerifyAvailable;
    }

    public HealthStats health() {
        return new HealthStats(
                config.workers(),
                idlePool.size(),
                busyCount.get(),
                isWasmAvailable(),
                asmVerifyAvailable,
                trialsServed.get(),
                workersRecycled.get(),
                Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L));
    }

    /** Executes one trial on an isolated warm worker JVM with hard timeout and poison recovery. */
    public CribProtocol.TrialResponse evaluate(CribProtocol.TrialRequest req)
            throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("CribWorkerPool is closed");
        }
        long startNanos = System.nanoTime();
        WorkerHandle worker = borrowWorker(req.fresh());
        busyCount.incrementAndGet();
        boolean returnHealthy = false;
        boolean recycled = req.fresh();
        String recycleReason = req.fresh() ? "fresh worker requested" : null;
        try {
            if (!worker.isAlive()) {
                worker.destroy(liveProcesses);
                workersRecycled.incrementAndGet();
                recycled = true;
                worker = new WorkerHandle(config, liveProcesses);
            }

            String encoded = CribProtocol.encodeRequest(req);
            worker.in.write(encoded);
            worker.in.write('\n');
            worker.in.flush();

            long outerDeadlineMs = computeOuterDeadlineMs(req);
            Future<String> readFuture = worker.readerExec.submit(worker.out::readLine);
            String respLine;
            try {
                respLine = readFuture.get(outerDeadlineMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                readFuture.cancel(true);
                worker.destroy(liveProcesses);
                workersRecycled.incrementAndGet();
                trialsServed.incrementAndGet();
                long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                return syntheticFailure(
                        req,
                        CribProtocol.STATUS_TIMEOUT,
                        "Coordinator watchdog timed out after " + outerDeadlineMs + "ms",
                        elapsedMs);
            } catch (ExecutionException ee) {
                worker.destroy(liveProcesses);
                workersRecycled.incrementAndGet();
                trialsServed.incrementAndGet();
                long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                return syntheticFailure(
                        req,
                        CribProtocol.STATUS_WORKER_CRASHED,
                        "Worker I/O failure : " + ee.getCause(),
                        elapsedMs);
            }

            if (respLine == null) {
                int exit = safeExitCode(worker.process);
                worker.destroy(liveProcesses);
                workersRecycled.incrementAndGet();
                trialsServed.incrementAndGet();
                long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                return syntheticFailure(
                        req,
                        CribProtocol.STATUS_WORKER_CRASHED,
                        "Worker JVM terminated unexpectedly (exit=" + exit + ")",
                        elapsedMs);
            }

            CribProtocol.TrialResponse resp = CribProtocol.decodeResponse(respLine);
            worker.requestsServed++;
            trialsServed.incrementAndGet();

            boolean mustRecycle =
                    resp.workerRecycled()
                            || resp.poisonReason() != null
                            || !worker.isAlive()
                            || (config.maxRequestsPerWorker() > 0
                                    && worker.requestsServed >= config.maxRequestsPerWorker());

            if (mustRecycle) {
                String finalReason =
                        resp.poisonReason() != null
                                ? resp.poisonReason()
                                : (config.maxRequestsPerWorker() > 0
                                                && worker.requestsServed
                                                        >= config.maxRequestsPerWorker()
                                        ? "maxRequestsPerWorker ("
                                                + config.maxRequestsPerWorker()
                                                + ") reached"
                                        : recycleReason);
                worker.destroy(liveProcesses);
                workersRecycled.incrementAndGet();
                long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                return resp.withWorkerStatus(true, finalReason, elapsedMs);
            }

            returnHealthy = true;
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            return resp.withWorkerStatus(recycled, recycleReason, elapsedMs);
        } finally {
            busyCount.decrementAndGet();
            if (returnHealthy && !closed.get() && worker.isAlive()) {
                if (!idlePool.offer(worker)) {
                    worker.destroy(liveProcesses);
                }
            } else {
                worker.destroy(liveProcesses);
                if (!closed.get()) {
                    scheduleReplacement();
                }
            }
        }
    }

    /** Drains all idle workers and replaces them with fresh warm worker JVMs. */
    public int recycleAll() throws IOException {
        List<WorkerHandle> drained = new ArrayList<>();
        idlePool.drainTo(drained);
        int count = 0;
        for (WorkerHandle w : drained) {
            w.destroy(liveProcesses);
            workersRecycled.incrementAndGet();
            count++;
            if (!closed.get()) {
                idlePool.offer(new WorkerHandle(config, liveProcesses));
            }
        }
        return count;
    }

    private WorkerHandle borrowWorker(boolean fresh) throws IOException, InterruptedException {
        WorkerHandle w = idlePool.take();
        if (fresh && w.requestsServed > 0) {
            w.destroy(liveProcesses);
            workersRecycled.incrementAndGet();
            return new WorkerHandle(config, liveProcesses);
        }
        if (!w.isAlive()) {
            w.destroy(liveProcesses);
            workersRecycled.incrementAndGet();
            return new WorkerHandle(config, liveProcesses);
        }
        return w;
    }

    private void scheduleReplacement() {
        respawnExec.submit(() -> {
            if (closed.get()) {
                return;
            }
            try {
                WorkerHandle replacement = new WorkerHandle(config, liveProcesses);
                if (closed.get() || !idlePool.offer(replacement)) {
                    replacement.destroy(liveProcesses);
                }
            } catch (IOException ignored) {
                // Will be retried lazily if needed.
            }
        });
    }

    private long computeOuterDeadlineMs(CribProtocol.TrialRequest req) {
        int estimatedCases = countCaseHeadings(req.batestContent());
        int modeCount = Math.max(1, req.modes().size());
        long perCaseBudget = req.timeoutMs() * modeCount;
        long total = perCaseBudget * Math.max(1, estimatedCases) + 5_000L;
        long cap = Math.max(config.maxTimeoutMs() * modeCount + 10_000L, perCaseBudget + 5_000L);
        return Math.min(total, cap);
    }

    private static int countCaseHeadings(String content) {
        if (content == null || content.isEmpty()) {
            return 1;
        }
        int count = 0;
        int idx = 0;
        while (idx < content.length()) {
            if (content.startsWith("## ", idx) || content.startsWith("##\t", idx)) {
                count++;
            }
            int nl = content.indexOf('\n', idx);
            if (nl < 0) {
                break;
            }
            idx = nl + 1;
        }
        return Math.max(1, count);
    }

    private static CribProtocol.TrialResponse syntheticFailure(
            CribProtocol.TrialRequest req, String status, String reason, long elapsedMs) {
        List<String> labels = req.modes().stream().map(CribMode::label).toList();
        Map<String, CribProtocol.ModeVerdict> modes = new LinkedHashMap<>();
        for (String label : labels) {
            modes.put(
                    label,
                    new CribProtocol.ModeVerdict(
                            status, null, "", "", reason, reason, List.of(), null, elapsedMs));
        }
        boolean isTimeout = CribProtocol.STATUS_TIMEOUT.equals(status);
        CribProtocol.CaseVerdict syntheticCase =
                new CribProtocol.CaseVerdict("<worker>", 0, 0, false, true, null, modes);
        CribProtocol.TrialSummary summary =
                new CribProtocol.TrialSummary(1, 0, 1, 0, isTimeout ? 0 : 1, isTimeout ? 1 : 0);
        return new CribProtocol.TrialResponse(
                false,
                elapsedMs,
                labels,
                req.verify(),
                req.parity(),
                req.strict(),
                true,
                reason,
                null,
                summary,
                List.of(syntheticCase));
    }

    private static int safeExitCode(Process p) {
        try {
            if (!p.waitFor(200, TimeUnit.MILLISECONDS)) {
                return -1;
            }
            return p.exitValue();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String resolveJavaBinary() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() -> {
                    boolean isWindows =
                            System.getProperty("os.name", "")
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains("win");
                    return Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    isWindows ? "java.exe" : "java")
                            .toString();
                });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down.
        }
        respawnExec.shutdownNow();
        List<WorkerHandle> drained = new ArrayList<>();
        idlePool.drainTo(drained);
        for (WorkerHandle w : drained) {
            w.destroy(liveProcesses);
        }
        for (Process p : liveProcesses) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        liveProcesses.clear();
    }
}
