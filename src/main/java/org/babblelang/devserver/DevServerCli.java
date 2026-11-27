package org.babblelang.devserver;

import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/** Command-line front end for {@code java -jar babble.jar -dev-server [options] [directory]}. */
public final class DevServerCli {

    public static final String USAGE =
            """
            Usage : java -jar babble.jar -dev-server [options] [directory]
              Starts a simple HTTP dev server (like java -m jdk.httpserver) with proper MIME types
              for .html, .js, .mjs, .wasm, and other web assets.
              -b, --bind-address <addr>   address to bind to (default : 127.0.0.1 ; use 0.0.0.0 or :: for all)
              -p, --port <n>              port to listen on, 0 for ephemeral (default : 8000)
              -d, --directory <path>      directory to serve, relative or absolute (default : current directory)
              -o, --output <level>        output level : none|info|verbose (default : info)
              -h, -help                   this text\
            """;

    private static final int FAILED = 1;
    private static final int USAGE_ERROR = 2;

    private DevServerCli() {}

    /**
     * Parses {@code -dev-server} options into a {@link DevServerConfig}, returning {@code null} on
     * {@code -help}.
     */
    public static DevServerConfig parseArgs(String[] args) {
        String bindAddress = DevServerConfig.DEFAULT_BIND_ADDRESS;
        int port = DevServerConfig.DEFAULT_PORT;
        Path directory = null;
        OutputLevel outputLevel = DevServerConfig.DEFAULT_OUTPUT_LEVEL;
        boolean positionalOnly = false;

        for (int i = 0; i < args.length; i++) {
            String opt = args[i];
            if (!positionalOnly && opt.equals("--")) {
                positionalOnly = true;
                continue;
            }
            if (!positionalOnly && opt.startsWith("-")) {
                switch (opt) {
                    case "-h", "-?", "-help", "--help" -> {
                        return null;
                    }
                    case "-b", "--bind-address", "-bind-address", "-host", "--host" ->
                            bindAddress = requireValue(args, ++i, opt);
                    case "-p", "-port", "--port" ->
                            port = parsePort(requireValue(args, ++i, opt), opt);
                    case "-d", "--directory", "-directory", "-dir", "--dir", "-root", "--root" -> {
                        if (directory != null) {
                            throw new IllegalArgumentException(
                                    "Directory already specified : " + opt);
                        }
                        directory = Path.of(requireValue(args, ++i, opt));
                    }
                    case "-o", "-output", "--output" ->
                            outputLevel = parseOutputLevel(requireValue(args, ++i, opt), opt);
                    default -> throw new IllegalArgumentException("Unknown option : " + opt);
                }
            } else {
                if (directory != null) {
                    throw new IllegalArgumentException("Directory already specified : " + opt);
                }
                directory = Path.of(opt);
            }
        }
        return new DevServerConfig(
                bindAddress,
                port,
                directory != null ? directory : DevServerConfig.DEFAULT_DIRECTORY,
                outputLevel);
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        DevServerConfig config;
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

        Path resolvedDir = config.directory().toAbsolutePath().normalize();
        if (!Files.isDirectory(resolvedDir)) {
            err.println("Error : Not a directory : " + config.directory());
            err.flush();
            return USAGE_ERROR;
        }

        if (!config.isLoopbackAddress()) {
            err.println(
                    "Warning : binding -dev-server to non-loopback address "
                            + config.bindAddress()
                            + " exposes files in "
                            + resolvedDir
                            + " over the network");
            err.flush();
        }

        try (DevServer server = new DevServer(config, out)) {
            server.start();
            if (config.isLoopbackAddress()) {
                out.println(
                        "Binding to loopback. For all interfaces use \"-b 0.0.0.0\" or \"-b ::\".");
            }
            out.println(
                    "Serving "
                            + server.rootDirectory()
                            + " and subdirectories on "
                            + server.bindAddress()
                            + " port "
                            + server.port());
            out.println("URL " + server.url());
            out.flush();

            CountDownLatch stopLatch = new CountDownLatch(1);
            Thread shutdown =
                    new Thread(
                            () -> {
                                server.close();
                                stopLatch.countDown();
                            },
                            "dev-server-cli-shutdown");
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
            err.println("Error : Failed to start -dev-server : " + e.getMessage());
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

    private static int parsePort(String raw, String opt) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + opt + " : " + raw);
        }
    }

    private static OutputLevel parseOutputLevel(String raw, String opt) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> OutputLevel.NONE;
            case "info" -> OutputLevel.INFO;
            case "verbose" -> OutputLevel.VERBOSE;
            default ->
                    throw new IllegalArgumentException(
                            "Invalid " + opt + " (expected none, info, or verbose) : " + raw);
        };
    }
}
