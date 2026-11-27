package org.babblelang.crib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.Main;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end HTTP, CLI, parity, Wasm, and adversarial isolation tests for {@code -crib} ({@code
 * BIP041-I1}–{@code P4}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CribServerTestCase {

    private CribServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll
    void startServer() throws IOException {
        CribConfig config =
                new CribConfig(
                        "127.0.0.1", 0, 1, 5_000L, 30_000L, 100, 65_536, "384m", "192m", true, 16);
        server = new CribServer(config);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> postCrib(String query, String body) throws Exception {
        String url =
                query == null || query.isEmpty() ? baseUrl + "/crib" : baseUrl + "/crib?" + query;
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "text/markdown; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postEmpty(String path) throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    public void testHealthEndpoint() throws Exception {
        HttpResponse<String> resp = get("/health");
        Assertions.assertEquals(200, resp.statusCode());
        Map<String, Object> json = CribProtocol.parseObject(resp.body());
        Assertions.assertEquals("ok", json.get("status"));
        Assertions.assertEquals("127.0.0.1", json.get("host"));
        Assertions.assertEquals((long) server.port(), ((Number) json.get("port")).longValue());
        Assertions.assertEquals(Boolean.TRUE, json.get("asmVerifyAvailable"));
    }

    @Test
    public void testFourModePassingTrialWithModulesSessionsAndHirGoldens() throws Exception {
        String batest =
                """
                # Multi-feature crib probe

                ```babble
                ;; babble module=lib/math.ba
                export def square = fn (x: int): int -> ( x * x )
                ```

                ## Module call and stdout

                ```babble
                def m = import("lib/math.ba")
                println(m.square(7))
                ```

                ```stdout
                49
                ```

                ## Expected type error

                ```babble
                def bad: int = "nope"
                ```

                ```error
                Type error : expected int, got string
                ```

                ## Session state across inputs

                ```babble
                ;; babble session
                def a = 10
                ```

                ```babble
                ;; babble session
                println(a + 5)
                ```

                ```stdout
                15
                ```

                ## HIR constant folding golden

                ```babble
                def f = fn (): int -> ( 2 + 3 * 4 )
                assert(f() == 14)
                ```

                ```hir
                ;; hir -O1,-O2 fn=f
                fn f () -> int {nothrow unique}
                  14:int
                ```
                """;

        HttpResponse<String> http = postCrib("modes=interpret,O0,O1,O2&verify=true", batest);
        Assertions.assertEquals(200, http.statusCode(), http.body());
        CribProtocol.TrialResponse resp = CribProtocol.decodeResponse(http.body());
        Assertions.assertTrue(resp.ok(), http.body());
        Assertions.assertEquals(4, resp.summary().cases());
        Assertions.assertEquals(4, resp.summary().passed());
        Assertions.assertEquals(0, resp.summary().failed());
        Assertions.assertEquals(0, resp.summary().parityDivergences());
        for (CribProtocol.CaseVerdict cv : resp.cases()) {
            Assertions.assertTrue(cv.passed(), cv.toString());
            Assertions.assertTrue(cv.parityOk(), cv.toString());
            Assertions.assertEquals(
                    List.of("interpret", "O0", "O1", "O2"), List.copyOf(cv.modes().keySet()));
        }
    }

    @Test
    public void testQueryOptionsFormatsFilterStrictAndHttpErrorCodes() throws Exception {
        String bigBody = "def x = " + "1 + ".repeat(140) + "1\nassert(x > 0)\n";
        String bigBatest = "## Big probe\n```babble\n" + bigBody + "```\n";

        // Default strict=false accepts > 400-char probe
        HttpResponse<String> relaxed = postCrib("modes=O1", bigBatest);
        Assertions.assertEquals(200, relaxed.statusCode(), relaxed.body());
        Assertions.assertTrue(CribProtocol.decodeResponse(relaxed.body()).ok());

        // strict=true rejects > 400-char probe with HTTP 400
        HttpResponse<String> strict = postCrib("modes=O1&strict=true", bigBatest);
        Assertions.assertEquals(400, strict.statusCode(), strict.body());
        Assertions.assertNotNull(CribProtocol.decodeResponse(strict.body()).parseError());

        // Text format + filter + bare mode shorthand (?O0,O2)
        String twoCases =
                """
                ## Alpha
                ```babble
                println("a")
                ```
                ```stdout
                a
                ```

                ## Beta
                ```babble
                println("b")
                ```
                ```stdout
                WRONG
                ```
                """;
        HttpResponse<String> filteredText = postCrib("O0,O2&filter=@Alpha&format=text", twoCases);
        Assertions.assertEquals(200, filteredText.statusCode(), filteredText.body());
        Assertions.assertTrue(filteredText.body().startsWith("CRIB PASS :"), filteredText.body());
        Assertions.assertTrue(filteredText.body().contains("[PASS] Alpha"), filteredText.body());
        Assertions.assertFalse(filteredText.body().contains("Beta"), filteredText.body());

        HttpResponse<String> failedText = postCrib("modes=O1&filter=@Beta&format=text", twoCases);
        Assertions.assertEquals(200, failedText.statusCode(), failedText.body());
        Assertions.assertTrue(failedText.body().startsWith("CRIB FAIL :"), failedText.body());
        Assertions.assertTrue(failedText.body().contains("stdout mismatch"), failedText.body());

        // Error codes: 405, 400 (unknown param / bad syntax / empty body), 413 (body too large)
        Assertions.assertEquals(405, get("/crib").statusCode());
        Assertions.assertEquals(405, postEmpty("/health").statusCode());
        Assertions.assertEquals(400, postCrib("bogus=1", twoCases).statusCode());
        Assertions.assertEquals(400, postCrib("modes=O1", "   \n").statusCode());
        Assertions.assertEquals(400, postCrib("modes=O1", "not a valid batest").statusCode());
        Assertions.assertEquals(413, postCrib("modes=O1", "a".repeat(70_000)).statusCode());
    }

    @Test
    public void testParityDiffDetectsDivergenceAcrossModes() {
        Map<String, CribProtocol.ModeVerdict> diverging = new LinkedHashMap<>();
        diverging.put(
                "interpret",
                new CribProtocol.ModeVerdict(
                        CribProtocol.STATUS_PASS,
                        "OK",
                        "1, 2",
                        "",
                        null,
                        null,
                        List.of(),
                        null,
                        2));
        diverging.put(
                "O0",
                new CribProtocol.ModeVerdict(
                        CribProtocol.STATUS_PASS,
                        "OK",
                        "1, 2",
                        "",
                        null,
                        null,
                        List.of(),
                        null,
                        3));
        diverging.put(
                "O1",
                new CribProtocol.ModeVerdict(
                        CribProtocol.STATUS_PASS,
                        "OK",
                        "99, 2",
                        "",
                        null,
                        null,
                        List.of(),
                        null,
                        2));
        String diff = CribEvaluator.computeParityDiff(diverging);
        Assertions.assertNotNull(diff);
        Assertions.assertTrue(diff.contains("stdout differs"), diff);
        Assertions.assertTrue(diff.contains("interpret,O0"), diff);
        Assertions.assertTrue(diff.contains("O1"), diff);

        Map<String, CribProtocol.ModeVerdict> agreeing = new LinkedHashMap<>();
        agreeing.put("interpret", diverging.get("interpret"));
        agreeing.put("O0", diverging.get("O0"));
        agreeing.put(
                "wasm-O1",
                new CribProtocol.ModeVerdict(
                        CribProtocol.STATUS_SKIPPED_NON_PORTABLE,
                        null,
                        "",
                        "",
                        null,
                        null,
                        List.of(),
                        null,
                        1));
        Assertions.assertNull(CribEvaluator.computeParityDiff(agreeing));
    }

    @Test
    public void testAdversarialIsolationSystemOutAndPropertiesAndLocaleAndExit() throws Exception {
        // 1. Direct java.lang.System.out writes must never corrupt worker IPC framing.
        String sysOutProbe =
                """
                ## Direct System.out noise
                ```babble
                java.lang.System.out.println("GARBAGE\\n{\\"fake\\":true}")
                java.lang.System.err.println("NOISE")
                println("clean")
                ```
                ```stdout
                clean
                ```
                """;
        HttpResponse<String> r1 = postCrib("modes=interpret,O1", sysOutProbe);
        Assertions.assertEquals(200, r1.statusCode(), r1.body());
        CribProtocol.TrialResponse t1 = CribProtocol.decodeResponse(r1.body());
        Assertions.assertTrue(t1.ok(), r1.body());
        Assertions.assertFalse(t1.workerRecycled(), "Normal System.out print should not poison");

        // 2. System.setProperty mutation triggers poison audit + worker recycle; next trial sees
        // clean state.
        String propPoison =
                """
                ## Mutate System property
                ```babble
                java.lang.System.setProperty("babble.crib.poison.test", "dirty")
                assert(java.lang.System.getProperty("babble.crib.poison.test") == "dirty")
                ```
                """;
        HttpResponse<String> r2 = postCrib("modes=O1", propPoison);
        Assertions.assertEquals(200, r2.statusCode(), r2.body());
        CribProtocol.TrialResponse t2 = CribProtocol.decodeResponse(r2.body());
        Assertions.assertTrue(t2.ok(), r2.body());
        Assertions.assertTrue(
                t2.workerRecycled(), "Mutating System properties must recycle worker");
        Assertions.assertNotNull(t2.poisonReason());
        Assertions.assertTrue(t2.poisonReason().contains("System properties"), t2.poisonReason());

        String verifyCleanProp =
                """
                ## Verify property isolation
                ```babble
                assert(java.lang.System.getProperty("babble.crib.poison.test") == null)
                ```
                """;
        HttpResponse<String> r3 = postCrib("modes=O1", verifyCleanProp);
        Assertions.assertEquals(200, r3.statusCode(), r3.body());
        Assertions.assertTrue(CribProtocol.decodeResponse(r3.body()).ok(), r3.body());

        // 3. Locale mutation triggers poison audit + recycle.
        String localePoison =
                """
                ## Mutate default Locale
                ```babble
                def cur = java.util.Locale.getDefault()
                def target = if cur == java.util.Locale.JAPAN then (java.util.Locale.FRANCE) else (java.util.Locale.JAPAN)
                java.util.Locale.setDefault(target)
                ```
                """;
        HttpResponse<String> r4 = postCrib("modes=O1", localePoison);
        Assertions.assertEquals(200, r4.statusCode(), r4.body());
        CribProtocol.TrialResponse t4 = CribProtocol.decodeResponse(r4.body());
        Assertions.assertTrue(t4.ok(), r4.body());
        Assertions.assertTrue(
                t4.workerRecycled(), "Mutating Locale must recycle worker : " + r4.body());
        Assertions.assertTrue(t4.poisonReason().contains("Locale"), t4.poisonReason());

        // 4. System.exit(42) kills worker; coordinator recovers cleanly and serves next trial.
        String exitProbe =
                """
                ## Hostile System.exit
                ```babble
                java.lang.System.exit(42)
                ```
                """;
        HttpResponse<String> r5 = postCrib("modes=O1", exitProbe);
        Assertions.assertEquals(200, r5.statusCode(), r5.body());
        CribProtocol.TrialResponse t5 = CribProtocol.decodeResponse(r5.body());
        Assertions.assertFalse(t5.ok());
        Assertions.assertTrue(t5.workerRecycled());
        Assertions.assertEquals(
                CribProtocol.STATUS_WORKER_CRASHED, t5.cases().get(0).modes().get("O1").status());

        // Immediately following clean trial succeeds on respawned worker.
        HttpResponse<String> r6 = postCrib("modes=interpret,O0,O1,O2", verifyCleanProp);
        Assertions.assertEquals(200, r6.statusCode(), r6.body());
        Assertions.assertTrue(CribProtocol.decodeResponse(r6.body()).ok(), r6.body());
    }

    @Test
    public void testAdversarialTimeoutsStackOverflowAndLeakedThread() throws Exception {
        // 1. Interpreted infinite loop inside catch java.lang.Throwable aborts cooperatively!
        String interpLoop =
                """
                ## Interpreted infinite loop with catch-all
                ```babble
                var x = 0
                try (forever while true then (x += 1)) catch e: java.lang.Throwable then (0)
                ```
                """;
        HttpResponse<String> r1 = postCrib("modes=interpret&timeout=500", interpLoop);
        Assertions.assertEquals(200, r1.statusCode(), r1.body());
        CribProtocol.TrialResponse t1 = CribProtocol.decodeResponse(r1.body());
        Assertions.assertFalse(t1.ok());
        Assertions.assertEquals(
                CribProtocol.STATUS_TIMEOUT,
                t1.cases().get(0).modes().get("interpret").status(),
                r1.body());
        Assertions.assertFalse(
                t1.workerRecycled(),
                "Cooperative interpreter interruption should not require worker kill : "
                        + r1.body());

        // 2. Compiled uninterruptible infinite loop triggers timeout + worker kill/recycle.
        String compiledLoop =
                """
                ## Compiled infinite loop
                ```babble
                var x = 0
                forever while true then (x += 1)
                ```
                """;
        HttpResponse<String> r2 = postCrib("modes=O1&timeout=200", compiledLoop);
        Assertions.assertEquals(200, r2.statusCode(), r2.body());
        CribProtocol.TrialResponse t2 = CribProtocol.decodeResponse(r2.body());
        Assertions.assertFalse(t2.ok());
        Assertions.assertEquals(
                CribProtocol.STATUS_TIMEOUT, t2.cases().get(0).modes().get("O1").status());
        Assertions.assertTrue(t2.workerRecycled(), "Uninterruptible compiled loop must recycle");

        // 3. StackOverflowError is classified cleanly as USER_ERROR without poisoning worker.
        String stackOverflow =
                """
                ## Stack overflow
                ```babble
                def f = fn (n: int): int -> ( 1 + f(n + 1) )
                f(0)
                ```
                ```error
                Stack overflow
                ```
                """;
        HttpResponse<String> r3 = postCrib("modes=interpret,O1", stackOverflow);
        Assertions.assertEquals(200, r3.statusCode(), r3.body());
        CribProtocol.TrialResponse t3 = CribProtocol.decodeResponse(r3.body());
        Assertions.assertTrue(t3.ok(), r3.body());

        // 4. Stray background thread spawned by trial triggers poison + recycle.
        String strayThread =
                """
                ## Leaked background thread
                ```babble
                def t = java.lang.Thread(java.lang.Runnable(fn io () -> (
                    try (java.lang.Thread.sleep(60000L)) catch e: java.lang.Throwable then ()
                )))
                t.setDaemon(true)
                t.start()
                ```
                """;
        HttpResponse<String> r4 = postCrib("modes=O1", strayThread);
        Assertions.assertEquals(200, r4.statusCode(), r4.body());
        CribProtocol.TrialResponse t4 = CribProtocol.decodeResponse(r4.body());
        Assertions.assertTrue(t4.workerRecycled(), "Stray thread must recycle worker");
        Assertions.assertTrue(t4.poisonReason().contains("stray"), t4.poisonReason());

        // Next clean trial still passes.
        HttpResponse<String> r5 =
                postCrib("modes=O1", "## After stray\n```babble\nassert(2 + 2 == 4)\n```\n");
        Assertions.assertEquals(200, r5.statusCode());
        Assertions.assertTrue(CribProtocol.decodeResponse(r5.body()).ok());
    }

    @Test
    public void testRecycleFreshAndRealRepoBatestCompatibility() throws Exception {
        HttpResponse<String> recycleResp = postEmpty("/recycle");
        Assertions.assertEquals(200, recycleResp.statusCode(), recycleResp.body());
        Map<String, Object> recycleJson = CribProtocol.parseObject(recycleResp.body());
        Assertions.assertEquals(Boolean.TRUE, recycleJson.get("ok"));

        // Run a real repository .batest.md file with strict=true across all 4 JVM modes.
        Path realFile = Path.of("src/test/batest/types/type_aliases.batest.md");
        Assertions.assertTrue(Files.isRegularFile(realFile));
        String content = Files.readString(realFile, StandardCharsets.UTF_8);
        HttpResponse<String> http =
                postCrib("modes=interpret,O0,O1,O2&strict=true&fresh=true", content);
        Assertions.assertEquals(200, http.statusCode(), http.body());
        CribProtocol.TrialResponse resp = CribProtocol.decodeResponse(http.body());
        Assertions.assertTrue(resp.ok(), http.body());
        Assertions.assertTrue(resp.cases().size() > 0);
        Assertions.assertEquals(0, resp.summary().parityDivergences());
    }

    @Test
    public void testOptionalWasmModesAndWasmTimeoutRecovery() throws Exception {
        Assumptions.assumeTrue(server.isWasmAvailable(), "Node.js required for Wasm crib tests");

        String portableAndNonPortable =
                """
                ## Portable arithmetic
                ```babble
                def fib = fn (n: int): int -> (
                    if n <= 1 then (n) else (fib(n - 1) + fib(n - 2))
                )
                println(fib(8))
                ```
                ```stdout
                21
                ```

                ## JVM-only java.lang.System
                ```babble
                assert(java.lang.System.lineSeparator().length() > 0)
                ```
                """;
        HttpResponse<String> http =
                postCrib("modes=interpret,O1,O2,wasm-O1,wasm-O2", portableAndNonPortable);
        Assertions.assertEquals(200, http.statusCode(), http.body());
        CribProtocol.TrialResponse resp = CribProtocol.decodeResponse(http.body());
        Assertions.assertTrue(resp.ok(), http.body());
        Assertions.assertEquals(
                CribProtocol.STATUS_PASS, resp.cases().get(0).modes().get("wasm-O1").status());
        Assertions.assertEquals(
                CribProtocol.STATUS_PASS, resp.cases().get(0).modes().get("wasm-O2").status());
        Assertions.assertEquals(
                CribProtocol.STATUS_SKIPPED_NON_PORTABLE,
                resp.cases().get(1).modes().get("wasm-O1").status());

        // Wasm infinite loop times out cleanly and next Wasm trial succeeds.
        String wasmLoop =
                "## Wasm infinite loop\n```babble\nvar x = 0\nforever while true then (x += 1)\n```\n";
        HttpResponse<String> timeoutHttp = postCrib("modes=wasm-O1&timeout=250", wasmLoop);
        Assertions.assertEquals(200, timeoutHttp.statusCode(), timeoutHttp.body());
        CribProtocol.TrialResponse timeoutResp = CribProtocol.decodeResponse(timeoutHttp.body());
        Assertions.assertFalse(timeoutResp.ok());
        Assertions.assertEquals(
                CribProtocol.STATUS_TIMEOUT,
                timeoutResp.cases().get(0).modes().get("wasm-O1").status());

        HttpResponse<String> afterWasmTimeout =
                postCrib(
                        "modes=wasm-O1",
                        "## After wasm timeout\n```babble\nprintln(42)\n```\n```stdout\n42\n```\n");
        Assertions.assertEquals(200, afterWasmTimeout.statusCode(), afterWasmTimeout.body());
        Assertions.assertTrue(CribProtocol.decodeResponse(afterWasmTimeout.body()).ok());
    }

    @Test
    public void testClassLoaderAndTypesCacheStabilityAcrossManyTrials() throws Exception {
        // Exercises JavaClass (ClassValue) and Types.parse (ephemeral T<N> filter) across 25
        // rapid warm trials creating distinct Babble classes and checking classof/as casts.
        for (int i = 0; i < 25; i++) {
            String probe =
                    "## Soak "
                            + i
                            + "\n```babble\n"
                            + "def Box"
                            + i
                            + " = record (v: int) ()\n"
                            + "def b: any = Box"
                            + i
                            + "("
                            + i
                            + ")\n"
                            + "def c = b as Box"
                            + i
                            + "\n"
                            + "assert(c.v == "
                            + i
                            + ")\n"
                            + "assert(classof(c) != null)\n"
                            + "```\n";
            HttpResponse<String> http = postCrib("modes=O1", probe);
            Assertions.assertEquals(200, http.statusCode(), http.body());
            Assertions.assertTrue(CribProtocol.decodeResponse(http.body()).ok(), http.body());
        }
    }

    @Test
    public void testCliParsingAndMainHelpDispatch() throws Exception {
        CribConfig parsed =
                CribCli.parseArgs(
                        new String[] {
                            "--host",
                            "127.0.0.1",
                            "--port",
                            "8080",
                            "--workers",
                            "2",
                            "--timeout",
                            "3000",
                            "--max-timeout",
                            "15000",
                            "--max-requests-per-worker",
                            "50",
                            "--max-body-bytes",
                            "65536",
                            "--worker-xmx",
                            "256m",
                            "--worker-metaspace",
                            "128m",
                            "--no-wasm"
                        });
        Assertions.assertNotNull(parsed);
        Assertions.assertEquals(8080, parsed.port());
        Assertions.assertEquals(2, parsed.workers());
        Assertions.assertEquals(3000L, parsed.defaultTimeoutMs());
        Assertions.assertFalse(parsed.wasmEnabled());

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int helpCode =
                Main.run(
                        new String[] {"-crib", "-help"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        false);
        Assertions.assertEquals(0, helpCode);
        Assertions.assertTrue(out.toString().contains("POST /crib"), out.toString());

        int badCode =
                Main.run(
                        new String[] {"-crib", "--bogus-flag"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(new StringWriter(), true),
                        new PrintWriter(err, true),
                        false);
        Assertions.assertEquals(2, badCode);
        Assertions.assertTrue(err.toString().contains("Unknown option : --bogus-flag"));
    }
}
