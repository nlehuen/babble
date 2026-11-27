package org.babblelang.devserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Request;
import com.sun.net.httpserver.SimpleFileServer;
import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight static HTTP development server built directly on JDK 21's {@link SimpleFileServer}
 * ({@code jdk.httpserver}), adding deterministic MIME types for {@code .html}, {@code .js}, {@code
 * .mjs}, {@code .wasm}, and common web assets.
 */
public final class DevServer implements Closeable {

    private final DevServerConfig config;
    private final Path rootDirectory;
    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DevServer(DevServerConfig config) throws IOException {
        this(config, null);
    }

    public DevServer(DevServerConfig config, PrintWriter output) throws IOException {
        this.config = Objects.requireNonNull(config, "config must not be null");
        Path normalized = config.directory().toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("Not a directory : " + config.directory());
        }
        if (!Files.isReadable(normalized)) {
            throw new IOException("Directory is not readable : " + config.directory());
        }
        this.rootDirectory = normalized.toRealPath();

        HttpHandler fileHandler = SimpleFileServer.createFileHandler(this.rootDirectory);
        HttpHandler handler = exchange -> fileHandler.handle(new MimeOverrideExchange(exchange));

        Filter[] filters;
        if (config.outputLevel() == OutputLevel.NONE || output == null) {
            filters = new Filter[0];
        } else {
            filters =
                    new Filter[] {
                        SimpleFileServer.createOutputFilter(
                                new PrintWriterOutputStream(output), config.outputLevel())
                    };
        }

        this.httpExecutor =
                Executors.newFixedThreadPool(
                        4,
                        r -> {
                            Thread t = new Thread(r, "dev-server-http");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            this.httpServer =
                    HttpServer.create(
                            new InetSocketAddress(config.bindAddress(), config.port()),
                            0,
                            "/",
                            handler,
                            filters);
            this.httpServer.setExecutor(httpExecutor);
        } catch (IOException e) {
            httpExecutor.shutdownNow();
            throw e;
        }
    }

    public void start() {
        httpServer.start();
    }

    public String bindAddress() {
        return config.bindAddress();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public String url() {
        String host = config.bindAddress();
        String formattedHost =
                host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + formattedHost + ":" + port() + "/";
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            httpServer.stop(0);
            httpExecutor.shutdownNow();
        }
    }

    /**
     * Delegating {@link HttpExchange} that overrides {@code Content-Type} (and adds {@code
     * Cache-Control: no-cache}) on {@code 200 OK} responses right before headers are committed to
     * the wire, so {@link SimpleFileServer}'s file handling and {@code OutputFilter} logging stay
     * intact while modern web and WebAssembly MIME types are guaranteed on every OS.
     */
    private static final class MimeOverrideExchange extends HttpExchange {

        private final HttpExchange delegate;

        MimeOverrideExchange(HttpExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            if (rCode == 200) {
                Headers headers = delegate.getResponseHeaders();
                String existing = headers.getFirst("Content-Type");
                String override =
                        MimeTypes.resolveOverride(delegate.getRequestURI().getPath(), existing);
                if (override != null) {
                    headers.set("Content-Type", override);
                }
                if (!headers.containsKey("Cache-Control")) {
                    headers.set("Cache-Control", "no-cache");
                }
            }
            delegate.sendResponseHeaders(rCode, responseLength);
        }

        @Override
        public Headers getRequestHeaders() {
            return delegate.getRequestHeaders();
        }

        @Override
        public Headers getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public URI getRequestURI() {
            return delegate.getRequestURI();
        }

        @Override
        public String getRequestMethod() {
            return delegate.getRequestMethod();
        }

        @Override
        public HttpContext getHttpContext() {
            return delegate.getHttpContext();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getRequestBody() {
            return delegate.getRequestBody();
        }

        @Override
        public OutputStream getResponseBody() {
            return delegate.getResponseBody();
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return delegate.getRemoteAddress();
        }

        @Override
        public int getResponseCode() {
            return delegate.getResponseCode();
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public String getProtocol() {
            return delegate.getProtocol();
        }

        @Override
        public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            delegate.setAttribute(name, value);
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
            delegate.setStreams(i, o);
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return delegate.getPrincipal();
        }

        @Override
        public Request with(String headerName, List<String> headerValues) {
            return delegate.with(headerName, headerValues);
        }
    }

    private static final class PrintWriterOutputStream extends OutputStream {

        private final PrintWriter writer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        PrintWriterOutputStream(PrintWriter writer) {
            this.writer = writer;
        }

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            if (buffer.size() > 0) {
                writer.print(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            }
            writer.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
