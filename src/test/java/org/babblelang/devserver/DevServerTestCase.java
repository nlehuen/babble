package org.babblelang.devserver;

import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
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
import org.babblelang.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit and end-to-end HTTP/CLI tests for {@code -dev-server}. */
public class DevServerTestCase {

    @TempDir Path tempDir;

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * {@code SimpleFileServer}'s {@code OutputFilter} runs in {@code Filter.afterHandler} on the
     * server worker thread after the response body is flushed to the client, so wait briefly for
     * the log line to land.
     */
    private static void awaitLogContains(StringWriter log, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (log.toString().contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        Assertions.assertTrue(
                log.toString().contains(expected),
                "Expected log to contain <" + expected + ">, actual: <" + log + ">");
    }

    @Test
    public void testMimeTypesMapping() {
        Assertions.assertEquals("text/html; charset=utf-8", MimeTypes.forFileName("index.html"));
        Assertions.assertEquals("text/html; charset=utf-8", MimeTypes.forFileName("page.HTM"));
        Assertions.assertEquals("text/javascript; charset=utf-8", MimeTypes.forFileName("app.js"));
        Assertions.assertEquals(
                "text/javascript; charset=utf-8", MimeTypes.forFileName("/dir/raytracer_wasm.mjs"));
        Assertions.assertEquals(
                "text/javascript; charset=utf-8", MimeTypes.forFileName("MODULE.MJS"));
        Assertions.assertEquals("application/wasm", MimeTypes.forFileName("raytracer_wasm.wasm"));
        Assertions.assertEquals("application/wasm", MimeTypes.forFileName("/sub/APP.WASM"));
        Assertions.assertEquals("text/css; charset=utf-8", MimeTypes.forFileName("styles.css"));
        Assertions.assertEquals(
                "application/json; charset=utf-8", MimeTypes.forFileName("manifest.json"));
        Assertions.assertEquals("image/svg+xml", MimeTypes.forFileName("icon.svg"));
        Assertions.assertEquals("image/png", MimeTypes.forPath(Path.of("assets/scene.png")));
        Assertions.assertEquals("text/plain; charset=utf-8", MimeTypes.forFileName("main.ba"));

        // Edge cases
        Assertions.assertNull(MimeTypes.forFileName(null));
        Assertions.assertNull(MimeTypes.forFileName(""));
        Assertions.assertNull(MimeTypes.forFileName("/dir/"));
        Assertions.assertNull(MimeTypes.forFileName(".gitignore"));
        Assertions.assertNull(MimeTypes.forFileName("trailing."));
        Assertions.assertNull(MimeTypes.forFileName("noextension"));
        Assertions.assertNull(MimeTypes.forFileName("archive.unknownext"));

        // resolveOverride upgrades bare text/html (e.g., index.html served for "/") to UTF-8
        Assertions.assertEquals(
                "text/html; charset=utf-8", MimeTypes.resolveOverride("/", "text/html"));
        Assertions.assertNull(MimeTypes.resolveOverride("/", "text/html; charset=UTF-8"));
        Assertions.assertEquals(
                "application/wasm",
                MimeTypes.resolveOverride("/app.wasm", "application/octet-stream"));
    }

    @Test
    public void testParseArgsAndConfigValidation() {
        DevServerConfig def = DevServerCli.parseArgs(new String[0]);
        Assertions.assertEquals("127.0.0.1", def.bindAddress());
        Assertions.assertEquals(8000, def.port());
        Assertions.assertEquals(Path.of("."), def.directory());
        Assertions.assertEquals(OutputLevel.INFO, def.outputLevel());
        Assertions.assertTrue(def.isLoopbackAddress());

        DevServerConfig jdkStyle =
                DevServerCli.parseArgs(
                        new String[] {
                            "-b", "0.0.0.0", "-p", "9000", "-d", "examples/raytracer", "-o", "none"
                        });
        Assertions.assertEquals("0.0.0.0", jdkStyle.bindAddress());
        Assertions.assertEquals(9000, jdkStyle.port());
        Assertions.assertEquals(Path.of("examples/raytracer"), jdkStyle.directory());
        Assertions.assertEquals(OutputLevel.NONE, jdkStyle.outputLevel());
        Assertions.assertFalse(jdkStyle.isLoopbackAddress());

        DevServerConfig longAndPositional =
                DevServerCli.parseArgs(
                        new String[] {
                            "--bind-address",
                            "::1",
                            "--port",
                            "0",
                            "--output",
                            "VERBOSE",
                            "examples/raytracer"
                        });
        Assertions.assertEquals("::1", longAndPositional.bindAddress());
        Assertions.assertEquals(0, longAndPositional.port());
        Assertions.assertEquals(Path.of("examples/raytracer"), longAndPositional.directory());
        Assertions.assertEquals(OutputLevel.VERBOSE, longAndPositional.outputLevel());
        Assertions.assertTrue(longAndPositional.isLoopbackAddress());

        Assertions.assertNull(DevServerCli.parseArgs(new String[] {"-h"}));
        Assertions.assertNull(DevServerCli.parseArgs(new String[] {"-?"}));
        Assertions.assertNull(DevServerCli.parseArgs(new String[] {"-help"}));
        Assertions.assertNull(DevServerCli.parseArgs(new String[] {"--help"}));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"--unknown"}));
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> DevServerCli.parseArgs(new String[] {"-p"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-p", "notanumber"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-p", "70000"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-p", "-1"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-o", "loud"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-d", "a", "b"}));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> DevServerCli.parseArgs(new String[] {"-b", "   "}));
    }

    @Test
    public void testServesWebAndWasmMimeTypesAndHttpSemantics() throws Exception {
        byte[] wasmBytes = new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        Files.writeString(tempDir.resolve("demo.html"), "<!doctype html><h1>Babble — Wasm</h1>");
        Files.writeString(tempDir.resolve("classic.js"), "console.log('classic');");
        Files.writeString(
                tempDir.resolve("loader.mjs"), "export function runBabble() { return 42; }");
        Files.write(tempDir.resolve("app.wasm"), wasmBytes);
        Files.write(tempDir.resolve("UPPER.WASM"), wasmBytes);
        Files.writeString(tempDir.resolve("my module.mjs"), "export const ok = true;");
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("index.html"), "<!doctype html><p>Index</p>");

        StringWriter logOut = new StringWriter();
        DevServerConfig cfg = new DevServerConfig("127.0.0.1", 0, tempDir, OutputLevel.INFO);
        HttpClient client = newClient();

        try (DevServer server = new DevServer(cfg, new PrintWriter(logOut, true))) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            Assertions.assertEquals(base + "/", server.url());

            // .html
            HttpResponse<String> html =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/demo.html")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, html.statusCode());
            Assertions.assertEquals(
                    "text/html; charset=utf-8",
                    html.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertEquals(
                    "no-cache", html.headers().firstValue("Cache-Control").orElse(""));
            Assertions.assertTrue(html.body().contains("Babble — Wasm"));

            // .js
            HttpResponse<String> js =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/classic.js")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, js.statusCode());
            Assertions.assertEquals(
                    "text/javascript; charset=utf-8",
                    js.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertEquals("console.log('classic');", js.body());

            // .mjs
            HttpResponse<String> mjs =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/loader.mjs")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, mjs.statusCode());
            Assertions.assertEquals(
                    "text/javascript; charset=utf-8",
                    mjs.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertTrue(mjs.body().contains("export function runBabble"));

            // .wasm (with query string)
            HttpResponse<byte[]> wasm =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/app.wasm?v=1"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(200, wasm.statusCode());
            Assertions.assertEquals(
                    "application/wasm", wasm.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertArrayEquals(wasmBytes, wasm.body());

            // Uppercase .WASM and space-encoded .mjs
            HttpResponse<byte[]> upperWasm =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/UPPER.WASM")).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(200, upperWasm.statusCode());
            Assertions.assertEquals(
                    "application/wasm", upperWasm.headers().firstValue("Content-Type").orElse(""));

            HttpResponse<String> spacedMjs =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/my%20module.mjs"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, spacedMjs.statusCode());
            Assertions.assertEquals(
                    "text/javascript; charset=utf-8",
                    spacedMjs.headers().firstValue("Content-Type").orElse(""));

            // HEAD request on .wasm preserves Content-Type and Content-Length with empty body
            HttpResponse<byte[]> headWasm =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/app.wasm"))
                                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(200, headWasm.statusCode());
            Assertions.assertEquals(
                    "application/wasm", headWasm.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertEquals(
                    String.valueOf(wasmBytes.length),
                    headWasm.headers().firstValue("Content-Length").orElse(""));
            Assertions.assertEquals(0, headWasm.body().length);

            // Directory with index.html
            HttpResponse<String> subRedirect =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/sub")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(301, subRedirect.statusCode());
            Assertions.assertEquals(
                    "/sub/", subRedirect.headers().firstValue("Location").orElse(""));

            HttpResponse<String> subIndex =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/sub/")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, subIndex.statusCode());
            Assertions.assertEquals(
                    "text/html; charset=utf-8",
                    subIndex.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertTrue(subIndex.body().contains("<p>Index</p>"));

            // Root directory listing
            HttpResponse<String> rootListing =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, rootListing.statusCode());
            Assertions.assertTrue(
                    rootListing
                            .headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .startsWith("text/html"));
            Assertions.assertTrue(rootListing.body().contains("demo.html"));

            // 404 keeps error content type (not overridden to application/wasm)
            HttpResponse<String> missing =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/missing.wasm"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(404, missing.statusCode());
            Assertions.assertNotEquals(
                    "application/wasm", missing.headers().firstValue("Content-Type").orElse(""));

            // 405 on POST
            HttpResponse<String> post =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/app.wasm"))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(405, post.statusCode());

            // OutputFilter logged requests to our PrintWriter
            awaitLogContains(logOut, "GET /app.wasm?v=1");
        }
    }

    @Test
    public void testAcceptsRelativeDirectoryAndServesRaytracerCanvas() throws Exception {
        Path rel = Path.of("examples/raytracer");
        DevServerConfig cfg = new DevServerConfig("127.0.0.1", 0, rel, OutputLevel.NONE);
        HttpClient client = newClient();
        try (DevServer server = new DevServer(cfg)) {
            server.start();
            HttpResponse<String> resp =
                    client.send(
                            HttpRequest.newBuilder(URI.create(server.url() + "wasm_canvas.html"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, resp.statusCode());
            Assertions.assertEquals(
                    "text/html; charset=utf-8",
                    resp.headers().firstValue("Content-Type").orElse(""));
            Assertions.assertTrue(resp.body().contains("raytracer_wasm.mjs"));
        }
    }

    @Test
    public void testRejectsNonDirectoryRoot() throws Exception {
        Path file = tempDir.resolve("not_a_dir.txt");
        Files.writeString(file, "hello");
        DevServerConfig fileCfg = new DevServerConfig("127.0.0.1", 0, file, OutputLevel.NONE);
        Assertions.assertThrows(IOException.class, () -> new DevServer(fileCfg));

        Path missing = tempDir.resolve("does_not_exist");
        DevServerConfig missingCfg = new DevServerConfig("127.0.0.1", 0, missing, OutputLevel.NONE);
        Assertions.assertThrows(IOException.class, () -> new DevServer(missingCfg));
    }

    @Test
    public void testOutputLevelsTraversalAndExtensionNamedDirectory() throws Exception {
        Files.write(
                tempDir.resolve("mod.wasm"),
                new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        Path wasmDir = Files.createDirectory(tempDir.resolve("dir.wasm"));
        Files.writeString(wasmDir.resolve("note.txt"), "inside");

        HttpClient client = newClient();

        // OutputLevel.NONE produces no log output
        StringWriter noneLog = new StringWriter();
        DevServerConfig noneCfg = new DevServerConfig("127.0.0.1", 0, tempDir, OutputLevel.NONE);
        try (DevServer server = new DevServer(noneCfg, new PrintWriter(noneLog, true))) {
            server.start();
            HttpResponse<byte[]> resp =
                    client.send(
                            HttpRequest.newBuilder(URI.create(server.url() + "mod.wasm"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(200, resp.statusCode());
            Assertions.assertEquals("", noneLog.toString());

            // Directory named "dir.wasm/" keeps HTML directory listing MIME type
            HttpResponse<String> dirResp =
                    client.send(
                            HttpRequest.newBuilder(URI.create(server.url() + "dir.wasm/"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertEquals(200, dirResp.statusCode());
            Assertions.assertTrue(
                    dirResp.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .startsWith("text/html"));

            // Traversal attempt never returns 200
            HttpResponse<String> traversal =
                    client.send(
                            HttpRequest.newBuilder(URI.create(server.url() + "%2e%2e/pom.xml"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Assertions.assertNotEquals(200, traversal.statusCode());
        }

        // OutputLevel.VERBOSE logs overridden response headers
        StringWriter verboseLog = new StringWriter();
        DevServerConfig verboseCfg =
                new DevServerConfig("127.0.0.1", 0, tempDir, OutputLevel.VERBOSE);
        try (DevServer server = new DevServer(verboseCfg, new PrintWriter(verboseLog, true))) {
            server.start();
            HttpResponse<byte[]> resp =
                    client.send(
                            HttpRequest.newBuilder(URI.create(server.url() + "mod.wasm"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            Assertions.assertEquals(200, resp.statusCode());
            awaitLogContains(verboseLog, "application/wasm");

            // Binding a second server to the same port fails cleanly with exit status 1
            StringWriter cliOut = new StringWriter();
            StringWriter cliErr = new StringWriter();
            int status =
                    DevServerCli.run(
                            new String[] {
                                "-b",
                                "127.0.0.1",
                                "-p",
                                String.valueOf(server.port()),
                                "-d",
                                tempDir.toString()
                            },
                            new PrintWriter(cliOut),
                            new PrintWriter(cliErr));
            Assertions.assertEquals(1, status);
            Assertions.assertTrue(
                    cliErr.toString().contains("Failed to start -dev-server"), cliErr.toString());
        }
    }

    @Test
    public void testMainCliIntegration() throws Exception {
        // -dev-server -help
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int helpStatus =
                Main.run(
                        new String[] {"-dev-server", "-help"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(out),
                        new PrintWriter(err),
                        false);
        Assertions.assertEquals(0, helpStatus);
        Assertions.assertTrue(out.toString().contains("-dev-server"), out.toString());
        Assertions.assertTrue(out.toString().contains(".wasm"), out.toString());
        Assertions.assertEquals("", err.toString());

        // --dev-server -h
        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        Assertions.assertEquals(
                0,
                Main.run(
                        new String[] {"--dev-server", "-h"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(out2),
                        new PrintWriter(err2),
                        false));
        Assertions.assertEquals(out.toString(), out2.toString());

        // Unknown option -> exit 2
        StringWriter out3 = new StringWriter();
        StringWriter err3 = new StringWriter();
        Assertions.assertEquals(
                2,
                Main.run(
                        new String[] {"-dev-server", "--bogus-flag"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(out3),
                        new PrintWriter(err3),
                        false));
        Assertions.assertTrue(err3.toString().contains("Unknown option"), err3.toString());

        // Missing directory -> exit 2
        StringWriter out4 = new StringWriter();
        StringWriter err4 = new StringWriter();
        Assertions.assertEquals(
                2,
                Main.run(
                        new String[] {
                            "-dev-server", "-d", tempDir.resolve("missing_dir").toString()
                        },
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(out4),
                        new PrintWriter(err4),
                        false));
        Assertions.assertTrue(err4.toString().contains("Not a directory"), err4.toString());

        // Main -help mentions -dev-server
        StringWriter mainOut = new StringWriter();
        StringWriter mainErr = new StringWriter();
        Assertions.assertEquals(
                0,
                Main.run(
                        new String[] {"-help"},
                        new BufferedReader(new StringReader("")),
                        new PrintWriter(mainOut),
                        new PrintWriter(mainErr),
                        false));
        Assertions.assertTrue(mainOut.toString().contains("-dev-server"), mainOut.toString());
    }
}
