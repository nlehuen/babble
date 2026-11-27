package org.babblelang.crib;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Isolated warm worker JVM entrypoint managed by {@link CribWorkerPool} ({@code BIP041-D1}, {@code
 * BIP041-D7}).
 *
 * <p>Binds its NDJSON protocol channel to the original {@link FileDescriptor#in} and {@link
 * FileDescriptor#out} before redirecting {@code System.in}, {@code System.out}, and {@code
 * System.err} so user Babble code invoking {@code java.lang.System.out.println(...)} can never
 * corrupt IPC framing. Pre-warms ANTLR, {@code BuiltinAxioms}, and the HIR compiler, captures a
 * JVM-global baseline snapshot, and audits post-trial invariants ({@code System.getProperties()},
 * {@code Locale}, {@code TimeZone}, {@code System.out}/{@code System.err} identities, heap
 * headroom) after every trial.
 */
public final class CribWorkerMain {

    private static final String WARMUP_BATEST =
            """
            ## Warmup
            ```babble
            def f = fn (x: int): int -> ( x + 1 )
            assert(f(1) == 2)
            ```
            """;

    private CribWorkerMain() {}

    public static void main(String[] args) throws Exception {
        // Detach IPC channel onto raw FileDescriptors so System.out/System.err writes from
        // adversarial Babble code cannot corrupt framed JSON messages.
        BufferedReader protocolIn =
                new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(FileDescriptor.in), StandardCharsets.UTF_8));
        BufferedWriter protocolOut =
                new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8));

        PrintStream sinkOut =
                new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream sinkErr =
                new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        System.setIn(InputStream.nullInputStream());
        System.setOut(sinkOut);
        System.setErr(sinkErr);

        boolean wasmEnabled = true;
        for (String arg : args) {
            if ("--no-wasm".equals(arg)) {
                wasmEnabled = false;
            }
        }

        WasmExecWorker wasmWorker =
                wasmEnabled && WasmExecWorker.isNodeAvailable() ? new WasmExecWorker() : null;
        CribEvaluator evaluator = new CribEvaluator(wasmWorker);

        try {
            // Pre-warm parser, axioms, and all 4 JVM execution modes before signaling ready.
            evaluator.evaluate(
                    new CribProtocol.TrialRequest(
                            WARMUP_BATEST,
                            "warmup.batest.md",
                            CribMode.DEFAULT_JVM_MODES,
                            CribEvaluator.isAsmVerifyAvailable(),
                            true,
                            false,
                            false,
                            5_000L,
                            "",
                            false));

            Baseline baseline = Baseline.capture(sinkOut, sinkErr);

            // Signal readiness to CribWorkerPool.
            protocolOut.write(
                    "{\"ready\":true,\"asmVerifyAvailable\":"
                            + CribEvaluator.isAsmVerifyAvailable()
                            + ",\"wasmAvailable\":"
                            + (wasmWorker != null)
                            + "}\n");
            protocolOut.flush();

            String line;
            while ((line = protocolIn.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                CribProtocol.TrialResponse response;
                String poisonReason = null;
                try {
                    CribProtocol.TrialRequest req = CribProtocol.decodeRequest(line);
                    CribEvaluator.EvalResult eval = evaluator.evaluate(req);
                    response = eval.response();
                    poisonReason = eval.poisonReason();
                    String envPoison = baseline.audit();
                    if (poisonReason == null && envPoison != null) {
                        poisonReason = envPoison;
                    }
                    if (poisonReason != null) {
                        response =
                                response.withWorkerStatus(true, poisonReason, response.elapsedMs());
                    }
                } catch (Throwable t) {
                    poisonReason = "Worker exception : " + t;
                    response =
                            new CribProtocol.TrialResponse(
                                    false,
                                    0L,
                                    List.of(),
                                    true,
                                    true,
                                    false,
                                    true,
                                    poisonReason,
                                    null,
                                    new CribProtocol.TrialSummary(0, 0, 1, 0, 1, 0),
                                    List.of());
                }

                protocolOut.write(CribProtocol.encodeResponse(response));
                protocolOut.write('\n');
                protocolOut.flush();

                if (poisonReason != null) {
                    // Exit promptly after delivering the response so the coordinator spawns a fresh
                    // worker and any uninterruptible spinning thread is terminated cleanly.
                    break;
                }
            }
        } finally {
            if (wasmWorker != null) {
                wasmWorker.close();
            }
        }
        // Force exit so any daemon or leaked user thread cannot linger after IPC closes.
        Runtime.getRuntime().halt(0);
    }

    /**
     * Startup snapshot of JVM-global mutable state audited after every trial ({@code BIP041-D7}).
     */
    private record Baseline(
            Map<String, String> systemProperties,
            Locale defaultLocale,
            TimeZone defaultTimeZone,
            PrintStream expectedOut,
            PrintStream expectedErr) {

        static Baseline capture(PrintStream out, PrintStream err) {
            // Initialize Locale and TimeZone before snapshotting System properties:
            // OpenJDK's first TimeZone.getDefault() call lazily sets "user.timezone".
            Locale locale = Locale.getDefault();
            TimeZone tz = TimeZone.getDefault();
            return new Baseline(snapshotProperties(), locale, tz, out, err);
        }

        private static Map<String, String> snapshotProperties() {
            Properties props = System.getProperties();
            Map<String, String> copy = new TreeMap<>();
            synchronized (props) {
                for (Map.Entry<Object, Object> e : props.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return copy;
        }

        String audit() {
            if (System.out != expectedOut) {
                return "System.out stream was replaced by trial";
            }
            if (System.err != expectedErr) {
                return "System.err stream was replaced by trial";
            }
            if (!defaultLocale.equals(Locale.getDefault())) {
                return "Locale.getDefault() was mutated to " + Locale.getDefault();
            }
            if (!defaultTimeZone.equals(TimeZone.getDefault())) {
                return "TimeZone.getDefault() was mutated to " + TimeZone.getDefault().getID();
            }
            Map<String, String> currentProps = snapshotProperties();
            if (!systemProperties.equals(currentProps)) {
                return "System properties were mutated (" + diffPropertyKey(currentProps) + ")";
            }
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            if (max > 0 && used > (max * 9) / 10) {
                return "Worker heap usage exceeded 90% watermark";
            }
            return null;
        }

        private String diffPropertyKey(Map<String, String> current) {
            for (Map.Entry<String, String> e : current.entrySet()) {
                if (!systemProperties.containsKey(e.getKey())) {
                    return "added " + e.getKey();
                }
                if (!e.getValue().equals(systemProperties.get(e.getKey()))) {
                    return "modified " + e.getKey();
                }
            }
            for (String key : systemProperties.keySet()) {
                if (!current.containsKey(key)) {
                    return "removed " + key;
                }
            }
            return "changed";
        }
    }
}
