package org.babblelang.crib;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Isolated, timeout-enforced, bounded-cache Node.js (V8) subprocess runner for optional {@code
 * wasm-O1}/{@code wasm-O2} execution inside a {@code -crib} worker JVM ({@code BIP041-D8}).
 *
 * <p>Fixes two hazards of long-lived Wasm execution: caps the JS {@code mjsCache} at 64 entries
 * (FIFO/LRU eviction) and enforces a hard per-case wall-clock timeout via {@link
 * Process#destroyForcibly()}, transparently respawning a fresh Node subprocess on the next call.
 */
public final class WasmExecWorker implements Closeable {

    public record RunOutcome(
            boolean ok, boolean timedOut, String stdout, String stderr, String error) {}

    private static final String WORKER_JS =
            """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
            const mjsCache = new Map();
            const MAX_MJS_CACHE = 64;

            async function handleRequest(req) {
              const bytes = Buffer.from(req.wasmBase64, 'base64');
              const stdoutChunks = [];
              const stderrChunks = [];
              const args = Array.isArray(req.args) ? req.args : [];
              try {
                let mod = mjsCache.get(req.mjsSource);
                if (!mod) {
                  if (mjsCache.size >= MAX_MJS_CACHE) {
                    const oldest = mjsCache.keys().next().value;
                    mjsCache.delete(oldest);
                  }
                  const b64 = Buffer.from(req.mjsSource, 'utf8').toString('base64');
                  mod = await import('data:text/javascript;base64,' + b64);
                  mjsCache.set(req.mjsSource, mod);
                }
                await mod.runWasmBytes(bytes, {
                  args,
                  stdout: (s) => stdoutChunks.push(String(s)),
                  stderr: (s) => stderrChunks.push(String(s)),
                });
                return {
                  ok: true,
                  stdout: stdoutChunks.join(''),
                  stderr: stderrChunks.join(''),
                  error: null,
                };
              } catch (err) {
                const msg = (err && err.babbleMessage) ? err.babbleMessage
                  : (err && err.stack) ? err.stack
                  : (err && err.message) ? err.message : String(err);
                return {
                  ok: false,
                  stdout: stdoutChunks.join(''),
                  stderr: stderrChunks.join(''),
                  error: msg,
                };
              }
            }

            (async () => {
              for await (const line of rl) {
                if (!line.trim()) continue;
                let resp;
                try {
                  const req = JSON.parse(line);
                  resp = await handleRequest(req);
                } catch (e) {
                  resp = { ok: false, stdout: '', stderr: '', error: String(e) };
                }
                process.stdout.write(JSON.stringify(resp) + '\\n');
              }
            })();
            """;

    private static volatile Boolean nodeAvailable;
    private static volatile List<String> nodeBaseCmd;

    private final ExecutorService readerExec =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "crib-wasm-reader");
                t.setDaemon(true);
                return t;
            });
    private Process process;
    private BufferedWriter in;
    private BufferedReader out;

    public static boolean isNodeAvailable() {
        Boolean cached = nodeAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (WasmExecWorker.class) {
            if (nodeAvailable != null) {
                return nodeAvailable;
            }
            try {
                Process p = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
                boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                nodeAvailable = finished && p.exitValue() == 0;
                if (!finished) {
                    p.destroyForcibly();
                }
            } catch (Exception e) {
                nodeAvailable = false;
            }
            return nodeAvailable;
        }
    }

    private static List<String> resolveNodeCommand() {
        List<String> cached = nodeBaseCmd;
        if (cached != null) {
            return cached;
        }
        synchronized (WasmExecWorker.class) {
            if (nodeBaseCmd != null) {
                return nodeBaseCmd;
            }
            boolean supportsExnrefFlag = false;
            try {
                Process p =
                        new ProcessBuilder("node", "--experimental-wasm-exnref", "-e", "0")
                                .redirectErrorStream(true)
                                .start();
                boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                supportsExnrefFlag = finished && p.exitValue() == 0;
                if (!finished) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                supportsExnrefFlag = false;
            }
            nodeBaseCmd =
                    supportsExnrefFlag
                            ? List.of("node", "--experimental-wasm-exnref", "-e", WORKER_JS)
                            : List.of("node", "-e", WORKER_JS);
            return nodeBaseCmd;
        }
    }

    public synchronized RunOutcome runJs(
            byte[] wasmBytes, String mjsSource, List<String> args, long timeoutMs) {
        try {
            ensureAlive();
            StringBuilder json = new StringBuilder(512);
            json.append("{\"wasmBase64\":\"")
                    .append(Base64.getEncoder().encodeToString(wasmBytes))
                    .append("\",\"mjsSource\":");
            CribProtocol.appendJsonString(json, mjsSource);
            json.append(",\"args\":[");
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) json.append(',');
                    CribProtocol.appendJsonString(json, args.get(i));
                }
            }
            json.append("]}");

            in.write(json.toString());
            in.write('\n');
            in.flush();

            BufferedReader currentOut = out;
            Future<String> readFuture = readerExec.submit(currentOut::readLine);
            String line;
            try {
                line = readFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                readFuture.cancel(true);
                destroyProcess();
                return new RunOutcome(
                        false, true, "", "", "Timed out after " + timeoutMs + "ms in Wasm worker");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                readFuture.cancel(true);
                destroyProcess();
                return new RunOutcome(false, true, "", "", "Interrupted waiting for Wasm worker");
            } catch (ExecutionException ee) {
                destroyProcess();
                return new RunOutcome(
                        false, false, "", "", "Wasm worker I/O error : " + ee.getCause());
            }

            if (line == null) {
                destroyProcess();
                return new RunOutcome(false, false, "", "", "Wasm worker exited unexpectedly");
            }
            Map<String, Object> map = CribProtocol.parseObject(line);
            boolean ok = Boolean.TRUE.equals(map.get("ok"));
            String stdout = map.get("stdout") instanceof String s ? s : "";
            String stderr = map.get("stderr") instanceof String s ? s : "";
            String error = map.get("error") instanceof String s ? s : null;
            return new RunOutcome(ok, false, stdout, stderr, error);
        } catch (IOException e) {
            destroyProcess();
            return new RunOutcome(
                    false, false, "", "", "Wasm worker I/O error : " + e.getMessage());
        }
    }

    private void ensureAlive() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        destroyProcess();
        ProcessBuilder pb = new ProcessBuilder(resolveNodeCommand());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        this.process = pb.start();
        this.in =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.out =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private void destroyProcess() {
        if (process != null) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }
        in = null;
        out = null;
    }

    @Override
    public synchronized void close() {
        destroyProcess();
        readerExec.shutdownNow();
    }
}
