package org.babblelang;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.engine.impl.natives.java.JavaObject;
import org.babblelang.repl.TerminalInput;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Babble's command line, and the main class of its jar (docs/design/bip004_repl.md) :
 * <pre>
 * java -jar babble.jar                              the interactive mode
 * java -jar babble.jar script.ba [arguments...]     runs a script
 * java -jar babble.jar -keep-alive script.ba ...    runs it, then continues in the interactive mode
 * java -jar babble.jar -compile main.ba [-o app.jar] [-root dir]
 *                                                   compiles a program into a jar (babblec)
 * java -jar babble.jar -format [-w] [-l] [-check] [file.ba | directory]...
 *                                                   formats source files (babblefmt, {@link Babblefmt})
 * </pre>
 * A script finds its arguments in {@code args}, a {@code java.util.List} of strings. The
 * mode is the engine's : {@code -Dbabble.mode=interpret} (scripts are compiled by
 * default).
 */
public final class Main {
    static final String USAGE = """
            Usage : java -jar babble.jar [-keep-alive] [-O0|-O1|-O2] [script.ba [arguments...]]
              Without a script, starts the interactive mode.
              With one, runs it. Its arguments are in the list "args" : args.size(), args.get(0).
              -keep-alive   after the script, continue in the interactive mode, with what it defined
              -O0|-O1|-O2   compiler optimization level
              -help         this text
            Or : java -jar babble.jar -compile main.ba [-o app.jar] [-root dir] [-O0|-O1|-O2]
              Compiles a program, main.ba and the modules it imports, into a jar that runs on a plain JRE :
              java -jar app.jar [arguments...]. Modules are under -root, by default main.ba's directory ;
              the jar is main.jar there unless -o names it.
            Or : java -jar babble.jar -format [-w] [-l] [-check] [file.ba | directory]...
              Formats source files : see java -jar babble.jar -format -help.""";

    /** The exit statuses : a script that failed, and a command line that can't be used. */
    static final int FAILED = 1;
    static final int USAGE_ERROR = 2;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int status = run(args, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out, true),
                new PrintWriter(System.err, true), isTerminal());
        if (status != 0) {
            // Not on success : threads the script started may still be working.
            System.exit(status);
        }
    }

    /**
     * Whether a person is typing, at a terminal. Since JDK 22, {@code System.console()} also
     * returns a console when the input is piped in : {@code Console.isTerminal()} tells, where
     * it exists (the build targets JDK 21, hence the reflection).
     */
    static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // JDK 21 : a console is a terminal.
            return true;
        }
    }

    /**
     * @param console whether a person is typing : the interactive mode then shows its prompts,
     *                and edits lines with history and completion (docs/design/bip004_repl.md §9)
     * @return the exit status
     */
    static int run(String[] args, BufferedReader in, PrintWriter out, PrintWriter err, boolean console) throws IOException {
        if (args.length > 0 && (args[0].equals("-compile") || args[0].equals("--compile"))) {
            return compile(args, out, err);
        }
        if (args.length > 0 && (args[0].equals("-format") || args[0].equals("--format"))) {
            return Babblefmt.run(Arrays.copyOfRange(args, 1, args.length), in, out, err);
        }
        boolean keepAlive = false;
        Integer optLevel = null;
        int first = 0;
        // Options come before the script : what follows it is the script's.
        for (; first < args.length && args[first].startsWith("-"); first++) {
            switch (args[first]) {
                case "-keep-alive", "--keep-alive" -> keepAlive = true;
                case "-O0" -> optLevel = 0;
                case "-O1" -> optLevel = 1;
                case "-O2" -> optLevel = 2;
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
        String script = first < args.length ? args[first] : null;
        List<String> scriptArguments = script == null ? List.of() : Arrays.asList(args).subList(first + 1, args.length);
        boolean interactive = script == null || keepAlive;

        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        if (optLevel != null) {
            engine.put(org.babblelang.compiler.BabbleCompiler.OPT_LEVEL, optLevel);
        }
        ScriptContext context = new SimpleScriptContext();
        Bindings bindings = engine.createBindings();
        bindings.put("args", JavaObject.of(new ArrayList<>(scriptArguments)));
        context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        context.setWriter(out);
        // A script's errors and warnings go to the error stream ; the interactive mode
        // shows everything in one place.
        PrintWriter errors = interactive ? out : err;
        context.setErrorWriter(errors);
        BabbleSession session = engine.newSession(context);

        int status = 0;
        if (script != null) {
            String source;
            try {
                source = Files.readString(Path.of(script));
            } catch (IOException e) {
                err.println("Cannot read " + script + " : " + (e instanceof NoSuchFileException ? "no such file" : e));
                err.flush();
                return USAGE_ERROR;
            }
            status = runScript(session, source, script, errors);
            out.flush();
            errors.flush();
        }
        if (interactive) {
            interact(session, in, out, console);
            return 0;
        }
        return status;
    }

    /**
     * The interactive mode : with the line editor on a terminal, unless
     * {@code -Dbabble.lineEditing=false} ; with plain lines otherwise, or if the terminal can't
     * be used.
     */
    private static void interact(BabbleSession session, BufferedReader in, PrintWriter out, boolean console) throws IOException {
        if (console && !"false".equals(System.getProperty("babble.lineEditing"))) {
            TerminalInput terminal = openTerminal(session);
            if (terminal != null) {
                try (terminal) {
                    new Repl(session, terminal, out, true).run();
                }
                return;
            }
        }
        new Repl(session, in, out, console).run();
    }

    private static TerminalInput openTerminal(BabbleSession session) {
        try {
            return TerminalInput.open(session, historyFile());
        } catch (IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Where the interactive mode keeps its inputs : {@code -Dbabble.history=file}, else {@code ~/.babble_history}. */
    static Path historyFile() {
        String file = System.getProperty("babble.history");
        return file != null ? Path.of(file) : Path.of(System.getProperty("user.home"), ".babble_history");
    }

    /** {@code -compile main.ba [-o app.jar] [-root dir] [-O0|-O1|-O2]} : babblec. */
    private static int compile(String[] args, PrintWriter out, PrintWriter err) throws IOException {
        Path main = null;
        Path jar = null;
        Path root = null;
        Integer optLevel = null;
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if ((option.equals("-o") || option.equals("-root")) && i + 1 < args.length) {
                Path value = Path.of(args[++i]);
                if (option.equals("-o")) {
                    jar = value;
                } else {
                    root = value;
                }
            } else if (option.equals("-O0")) {
                optLevel = 0;
            } else if (option.equals("-O1")) {
                optLevel = 1;
            } else if (option.equals("-O2")) {
                optLevel = 2;
            } else if (!option.startsWith("-") && main == null) {
                main = Path.of(option);
            } else {
                err.println("Unknown option : " + option);
                err.println(USAGE);
                err.flush();
                return USAGE_ERROR;
            }
        }
        if (main == null) {
            err.println("-compile needs a script");
            err.println(USAGE);
            err.flush();
            return USAGE_ERROR;
        }
        Path directory = main.toAbsolutePath().normalize().getParent();
        if (root == null) {
            root = directory;
        }
        if (jar == null) {
            String name = main.getFileName().toString();
            jar = directory.resolve((name.endsWith(".ba") ? name.substring(0, name.length() - 3) : name) + ".jar");
        }
        if (!Files.isRegularFile(main)) {
            err.println("Cannot read " + main + " : no such file");
            err.flush();
            return USAGE_ERROR;
        }
        ScriptContext context = new SimpleScriptContext();
        context.setWriter(out);
        context.setErrorWriter(err);
        try {
            org.babblelang.runtime.ProgramIndex index = org.babblelang.compiler.Babblec.compile(main, root, jar, context, optLevel);
            out.println("Wrote " + jar + " : " + index.modules().size() + (index.modules().size() == 1 ? " module" : " modules"));
            out.flush();
            return 0;
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            err.println("Error : " + Repl.withoutClassNames(e.getMessage()));
        }
        err.flush();
        return FAILED;
    }

    /** Runs a script as the first input of a session, and reports its error, if any. */
    private static int runScript(BabbleSession session, String source, String name, PrintWriter errors) {
        if (BabbleSession.classify(source) == BabbleSession.Input.EMPTY) {
            return 0;
        }
        try {
            session.eval(source, name);
            return 0;
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            errors.println("Error : " + Repl.withoutClassNames(e.getMessage()));
        } catch (StackOverflowError e) {
            errors.println("Error : Stack overflow in " + name);
        }
        return FAILED;
    }
}
