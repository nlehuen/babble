package org.babblelang;

import org.babblelang.format.Formatter;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The command line of Babble's formatter ({@link Formatter}) :
 * <pre>
 * babblefmt                       formats the standard input to the standard output
 * babblefmt file.ba directory     prints the files, formatted ; directories stand for their .ba files
 * babblefmt -w ...                rewrites the files that change
 * babblefmt -l ...                lists the files that would change
 * babblefmt -check ...            lists them, and fails if there are any
 * </pre>
 */
public final class Babblefmt {
    static final String USAGE = """
            Usage : babblefmt [-w] [-l] [-check] [file.ba | directory]...
              Formats Babble source files. Without a file, formats the standard input.
              A directory stands for the .ba files in it, at any depth.
              Without an option, the formatted text goes to the standard output.
              -w        rewrite the files whose formatting changes
              -l        list the files whose formatting would change
              -check    list them, and exit with status 1 if there are any
              -help     this text""";

    /** The exit statuses : a file that can't be formatted or, with -check, isn't ; a command line that can't be used. */
    static final int FAILED = 1;
    static final int USAGE_ERROR = 2;

    private Babblefmt() {
    }

    public static void main(String[] args) throws IOException {
        PrintWriter out = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
        int status = run(args, new InputStreamReader(System.in, StandardCharsets.UTF_8), out,
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        out.flush();
        if (status != 0) {
            System.exit(status);
        }
    }

    /** @return the exit status */
    static int run(String[] args, Reader in, PrintWriter out, PrintWriter err) throws IOException {
        boolean write = false;
        boolean list = false;
        boolean check = false;
        int first = 0;
        for (; first < args.length && args[first].startsWith("-") && !args[first].equals("--"); first++) {
            switch (args[first]) {
                case "-w", "--write" -> write = true;
                case "-l", "--list" -> list = true;
                case "-check", "--check" -> check = true;
                case "-help", "--help", "-h" -> {
                    out.println(USAGE);
                    out.flush();
                    return 0;
                }
                default -> {
                    err.println("Unknown option : " + args[first]);
                    err.println(USAGE);
                    err.flush();
                    return USAGE_ERROR;
                }
            }
        }
        if (first < args.length && args[first].equals("--")) {
            first++;
        }
        if (first == args.length) {
            if (write || list || check) {
                err.println("-w, -l and -check need files");
                err.println(USAGE);
                err.flush();
                return USAGE_ERROR;
            }
            StringWriter source = new StringWriter();
            in.transferTo(source);
            String formatted = format(source.toString(), "<standard input>", err);
            if (formatted == null) {
                return FAILED;
            }
            out.print(formatted);
            out.flush();
            return 0;
        }

        List<Path> files = new ArrayList<>();
        for (int i = first; i < args.length; i++) {
            Path path = Path.of(args[i]);
            if (Files.isDirectory(path)) {
                try (Stream<Path> found = Files.walk(path)) {
                    found.filter(file -> file.getFileName().toString().endsWith(".ba") && Files.isRegularFile(file))
                            .sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
        }
        int status = 0;
        for (Path file : files) {
            String source;
            try {
                source = Files.readString(file);
            } catch (IOException e) {
                err.println("Cannot read " + file + " : " + (e instanceof NoSuchFileException ? "no such file" : e));
                status = FAILED;
                continue;
            }
            String formatted = format(source, file.toString(), err);
            if (formatted == null) {
                status = FAILED;
                continue;
            }
            boolean changes = !formatted.equals(source);
            if (changes && (list || check)) {
                out.println(file);
                if (check) {
                    status = FAILED;
                }
            }
            if (write) {
                if (changes) {
                    Files.writeString(file, formatted);
                }
            } else if (!list && !check) {
                out.print(formatted);
            }
        }
        out.flush();
        err.flush();
        return status;
    }

    /** @return the formatted text, or null, the error being reported, when the text can't be formatted */
    private static String format(String source, String name, PrintWriter err) {
        try {
            return Formatter.format(source, name);
        } catch (ScriptException e) {
            // "Syntax error : unexpected ')' in file.ba at line number 3 at column number 7"
            err.println("Error : " + Repl.withoutClassNames(e.getMessage()));
        } catch (IllegalStateException e) {
            err.println("Error : " + e.getMessage());
        }
        return null;
    }
}
