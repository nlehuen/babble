package org.babblelang.tests;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Persistent Node.js (V8) worker for fast WebAssembly 3.0 test execution ({@code BIP025-D10}).
 *
 * <p>Starts a single {@code node} subprocess per JVM that communicates over newline-delimited JSON
 * on stdin/stdout, compiling and executing {@code wasm-bare} and {@code wasm-js} modules with
 * {@code { builtins: ["js-string"] }} in ~1-3 ms per case with zero Maven dependencies.
 */
public final class NodeWasmRunner {
    public record RunOutcome(boolean ok, String stdout, String stderr, String error, String resultJson) {
    }

    private static final String WORKER_JS = """
            const readline = require('readline');
            const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
            const textDecoder = new TextDecoder('utf-16le');

            async function handleRequest(req) {
              const bytes = Buffer.from(req.wasmBase64, 'base64');
              const stdoutChunks = [];
              const stderrChunks = [];
              const args = Array.isArray(req.args) ? req.args : [];
              try {
                if (req.mjsSource) {
                  // Execute generated app.mjs loader via dynamic data URL import
                  const b64 = Buffer.from(req.mjsSource, 'utf8').toString('base64');
                  const mod = await import('data:text/javascript;base64,' + b64);
                  const res = await mod.runWasmBytes(bytes, {
                    args,
                    stdout: (s) => stdoutChunks.push(String(s)),
                    stderr: (s) => stderrChunks.push(String(s)),
                    host: req.hostEval ? eval('(' + req.hostEval + ')') : undefined,
                  });
                  return {
                    ok: true,
                    stdout: stdoutChunks.join(''),
                    stderr: stderrChunks.join(''),
                    error: null,
                    resultJson: res === undefined ? 'null' : JSON.stringify(res),
                  };
                }
                if (req.babbleBare) {
                  const wasmMod = await WebAssembly.compile(bytes);
                  const instance = await WebAssembly.instantiate(wasmMod, {});
                  const status = instance.exports._babble_run();
                  const readStr = (lenFn, charFn) => {
                    const n = lenFn();
                    if (n < 0) return null;
                    let s = '';
                    for (let i = 0; i < n; i++) s += String.fromCharCode(charFn(i));
                    return s;
                  };
                  if (status !== 0) {
                    const err = readStr(instance.exports._babble_error_len, instance.exports._babble_error_char);
                    return { ok: false, stdout: '', stderr: '', error: err || 'Uncaught Babble exception', resultJson: 'null' };
                  }
                  const res = readStr(instance.exports._babble_result_len, instance.exports._babble_result_char);
                  return { ok: true, stdout: '', stderr: '', error: null, resultJson: JSON.stringify(res) };
                }
                const compileOpts = req.jsStringBuiltins ? { builtins: ['js-string'] } : undefined;
                const wasmMod = compileOpts
                  ? await WebAssembly.compile(bytes, compileOpts)
                  : await WebAssembly.compile(bytes);
                const imports = {};
                if (req.babbleEnv) {
                  imports['babble:env'] = {
                    write_stdout: (s) => stdoutChunks.push(String(s)),
                    write_stderr: (s) => stderrChunks.push(String(s)),
                    now_ms: () => BigInt(Date.now()),
                    args_count: () => args.length,
                    arg_at: (i) => args[i] ?? '',
                  };
                }
                const instance = await WebAssembly.instantiate(wasmMod, imports);
                const exportName = req.exportName || '_start';
                const fn = instance.exports[exportName];
                if (typeof fn !== 'function') {
                  throw new Error('Missing Wasm export: ' + exportName);
                }
                const callArgs = (req.callArgs || []).map(a =>
                  (a && typeof a === 'object' && a.__bigint !== undefined) ? BigInt(a.__bigint) : a
                );
                const ret = fn(...callArgs);
                const encodedRet = typeof ret === 'bigint' ? ret.toString() : ret;
                return {
                  ok: true,
                  stdout: stdoutChunks.join(''),
                  stderr: stderrChunks.join(''),
                  error: null,
                  resultJson: encodedRet === undefined ? 'null' : JSON.stringify(encodedRet),
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
                  resultJson: 'null',
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
                  resp = { ok: false, stdout: '', stderr: '', error: String(e), resultJson: 'null' };
                }
                process.stdout.write(JSON.stringify(resp) + '\\n');
              }
            })();
            """;

    private static volatile Boolean nodeAvailable;
    private static Process worker;
    private static BufferedWriter workerIn;
    private static BufferedReader workerOut;

    private NodeWasmRunner() {
    }

    public static boolean isNodeAvailable() {
        Boolean cached = nodeAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (NodeWasmRunner.class) {
            if (nodeAvailable != null) {
                return nodeAvailable;
            }
            try {
                Process p = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
                int code = p.waitFor();
                nodeAvailable = (code == 0);
            } catch (Exception e) {
                nodeAvailable = false;
            }
            return nodeAvailable;
        }
    }

    public static RunOutcome runBare(byte[] wasmBytes, String exportName, List<Object> callArgs) {
        return runBare(wasmBytes, exportName, callArgs, false);
    }

    public static RunOutcome runBare(byte[] wasmBytes, String exportName, List<Object> callArgs, boolean jsStringBuiltins) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"wasmBase64\":\"").append(Base64.getEncoder().encodeToString(wasmBytes)).append('"');
        json.append(",\"exportName\":\"").append(escapeJson(exportName)).append('"');
        json.append(",\"jsStringBuiltins\":").append(jsStringBuiltins);
        json.append(",\"callArgs\":[");
        for (int i = 0; i < callArgs.size(); i++) {
            if (i > 0) json.append(',');
            Object arg = callArgs.get(i);
            if (arg instanceof Long l) {
                json.append("{\"__bigint\":\"").append(l).append("\"}");
            } else if (arg instanceof Number || arg instanceof Boolean) {
                json.append(arg);
            } else {
                json.append('"').append(escapeJson(String.valueOf(arg))).append('"');
            }
        }
        json.append("]}");
        return send(json.toString());
    }

    /** Executes a Babble-compiled {@code wasm-bare} module (zero imports) via {@code _babble_run}. */
    public static RunOutcome runBabbleBare(byte[] wasmBytes) {
        String json = "{\"wasmBase64\":\"" + Base64.getEncoder().encodeToString(wasmBytes) + "\",\"babbleBare\":true}";
        return send(json);
    }

    public static RunOutcome runJs(byte[] wasmBytes, String mjsSource, List<String> args, String hostEvalJs) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"wasmBase64\":\"").append(Base64.getEncoder().encodeToString(wasmBytes)).append('"');
        json.append(",\"mjsSource\":\"").append(escapeJson(mjsSource)).append('"');
        if (hostEvalJs != null) {
            json.append(",\"hostEval\":\"").append(escapeJson(hostEvalJs)).append('"');
        }
        json.append(",\"args\":[");
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(escapeJson(args.get(i))).append('"');
            }
        }
        json.append("]}");
        return send(json.toString());
    }

    private static synchronized RunOutcome send(String requestJson) {
        try {
            ensureWorker();
            workerIn.write(requestJson);
            workerIn.write('\n');
            workerIn.flush();
            String line = workerOut.readLine();
            if (line == null) {
                resetWorker();
                throw new IllegalStateException("NodeWasmRunner worker terminated unexpectedly");
            }
            return parseResponse(line);
        } catch (IOException e) {
            resetWorker();
            throw new RuntimeException("NodeWasmRunner I/O error : " + e.getMessage(), e);
        }
    }

    private static void ensureWorker() throws IOException {
        if (worker != null && worker.isAlive()) {
            return;
        }
        ProcessBuilder pb = new ProcessBuilder("node", "-e", WORKER_JS);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        worker = pb.start();
        workerIn = new BufferedWriter(new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
        workerOut = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = worker;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }));
    }

    private static void resetWorker() {
        if (worker != null && worker.isAlive()) {
            worker.destroyForcibly();
        }
        worker = null;
        workerIn = null;
        workerOut = null;
    }

    private static RunOutcome parseResponse(String json) {
        Map<String, Object> map = MiniJson.parseObject(json);
        boolean ok = Boolean.TRUE.equals(map.get("ok"));
        String stdout = map.get("stdout") instanceof String s ? s : "";
        String stderr = map.get("stderr") instanceof String s ? s : "";
        String error = map.get("error") instanceof String s ? s : null;
        String resultJson = map.get("resultJson") instanceof String s ? s : "null";
        return new RunOutcome(ok, stdout, stderr, error, resultJson);
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Zero-dependency minimal JSON object parser for NodeWasmRunner responses. */
    private static final class MiniJson {
        private final String s;
        private int pos;

        private MiniJson(String s) {
            this.s = s;
        }

        static Map<String, Object> parseObject(String s) {
            MiniJson p = new MiniJson(s);
            p.skipWs();
            return p.readObject();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                skipWs();
                Object val = readValue();
                map.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Unexpected char in JSON : " + c);
                }
            }
        }

        private Object readValue() {
            char c = peek();
            if (c == '"') return readString();
            if (c == 't') { pos += 4; return Boolean.TRUE; }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null; }
            int start = pos;
            while (pos < s.length() && "0123456789eE+-.".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) cp);
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            return s.charAt(pos);
        }

        private void expect(char expected) {
            if (s.charAt(pos++) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in JSON");
            }
        }
    }
}
