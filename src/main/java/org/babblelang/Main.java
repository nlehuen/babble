package org.babblelang;

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
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.engine.impl.natives.java.JavaObject;
import org.babblelang.repl.TerminalInput;

/**
 * Babble's command line, and the main class of its jar (docs/design/bip004_repl.md) :
 *
 * <pre>
 * java -jar babble.jar                              the interactive mode
 * java -jar babble.jar script.ba [arguments...]     runs a script
 * java -jar babble.jar -keep-alive script.ba ...    runs it, then continues in the interactive mode
 * java -jar babble.jar -compile main.ba [-o app.jar] [-root dir]
 *                                                   compiles a program into a jar (babblec)
 * java -jar babble.jar -format [-w] [-l] [-check] [file.ba | directory]...
 *                                                   formats source files (babblefmt, {@link Babblefmt})
 * java -jar babble.jar -crib [options]              starts the isolated HTTP .batest.md runner (BIP041)
 * java -jar babble.jar -dev-server [options] [dir]  starts a simple HTTP dev server with proper MIME types
 * </pre>
 *
 * A script finds its arguments in {@code args}, a {@code java.util.List} of strings. The mode is
 * the engine's : {@code -Dbabble.mode=interpret} (scripts are compiled by default).
 */
public final class Main {
    static final String USAGE =
            """
            Usage : java -jar babble.jar [-keep-alive] [-O0|-O1|-O2] [script.ba [arguments...]]
              Without a script, starts the interactive mode.
              With one, runs it. Its arguments are in the list "args" : args.size(), args.get(0).
              -keep-alive   after the script, continue in the interactive mode, with what it defined
              -O0|-O1|-O2   compiler optimization level
              -help         this text
            Or : java -jar babble.jar -compile main.ba [-target jvm|wasm-js|wasm-bare] [-o app.jar|app.wasm] [-root dir] [-O0|-O1|-O2] [-dbg] [-wasm-opt]
              Compiles a program, main.ba and the modules it imports, into a jar that runs on a plain JRE
              (java -jar app.jar [arguments...]), or a WebAssembly 3.0 module (-target wasm-js writes app.wasm
              and a Node.js/browser app.mjs loader ; -target wasm-bare writes a standalone app.wasm).
              Modules are under -root, by default main.ba's directory ; -dbg keeps runtime LocalVariableTable
              debug info in the jar ; -wasm-opt runs Binaryen wasm-opt -O3.
            Or : java -jar babble.jar -format [-w] [-l] [-check] [file.ba | directory]...
              Formats source files : see java -jar babble.jar -format -help.
            Or : java -jar babble.jar -axioms [-O0|-O1|-O2] <class-or-package>
              Prints what Babble assumes (curated or inferred) about a Java class or package.
            Or : java -jar babble.jar -crib [options]
              Starts a fast, isolated HTTP .batest.md runner : see java -jar babble.jar -crib -help.
            Or : java -jar babble.jar -dev-server [options] [directory]
              Starts a simple HTTP dev server (.html, .js, .mjs, .wasm, ...) : see java -jar babble.jar -dev-server -help.\
            """;

    /** The exit statuses : a script that failed, and a command line that can't be used. */
    static final int FAILED = 1;

    static final int USAGE_ERROR = 2;

    private Main() {}

    public static void main(String[] args) throws IOException {
        int status =
                run(
                        args,
                        new BufferedReader(new InputStreamReader(System.in)),
                        new PrintWriter(System.out, true),
                        new PrintWriter(System.err, true),
                        isTerminal());
        if (status != 0) {
            // Not on success : threads the script started may still be working.
            System.exit(status);
        }
    }

    /**
     * Whether a person is typing, at a terminal. Since JDK 22, {@code System.console()} also
     * returns a console when the input is piped in : {@code Console.isTerminal()} tells, where it
     * exists (the build targets JDK 21, hence the reflection).
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
     * @param console whether a person is typing : the interactive mode then shows its prompts, and
     *     edits lines with history and completion (docs/design/bip004_repl.md §9)
     * @return the exit status
     */
    public static int run(
            String[] args, BufferedReader in, PrintWriter out, PrintWriter err, boolean console)
            throws IOException {
        if (args.length > 0 && (args[0].equals("-compile") || args[0].equals("--compile"))) {
            return compile(args, out, err);
        }
        if (args.length > 0 && (args[0].equals("-format") || args[0].equals("--format"))) {
            return Babblefmt.run(Arrays.copyOfRange(args, 1, args.length), in, out, err);
        }
        if (args.length > 0 && (args[0].equals("-axioms") || args[0].equals("--axioms"))) {
            return dumpAxioms(args, out, err);
        }
        if (args.length > 0 && (args[0].equals("-crib") || args[0].equals("--crib"))) {
            return org.babblelang.crib.CribCli.run(
                    Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        if (args.length > 0 && (args[0].equals("-dev-server") || args[0].equals("--dev-server"))) {
            return org.babblelang.devserver.DevServerCli.run(
                    Arrays.copyOfRange(args, 1, args.length), out, err);
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
        List<String> scriptArguments =
                script == null ? List.of() : Arrays.asList(args).subList(first + 1, args.length);
        boolean interactive = script == null || keepAlive;

        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
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
                err.println(
                        "Cannot read "
                                + script
                                + " : "
                                + (e instanceof NoSuchFileException ? "no such file" : e));
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
     * The interactive mode : with the line editor on a terminal, unless {@code
     * -Dbabble.lineEditing=false} ; with plain lines otherwise, or if the terminal can't be used.
     */
    private static void interact(
            BabbleSession session, BufferedReader in, PrintWriter out, boolean console)
            throws IOException {
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

    /**
     * Where the interactive mode keeps its inputs : {@code -Dbabble.history=file}, else {@code
     * ~/.babble_history}.
     */
    static Path historyFile() {
        String file = System.getProperty("babble.history");
        return file != null
                ? Path.of(file)
                : Path.of(System.getProperty("user.home"), ".babble_history");
    }

    /**
     * {@code -compile main.ba [-target jvm|wasm-js|wasm-bare] [-o app.jar|app.wasm] [-root dir]
     * [-O0|-O1|-O2] [-dbg] [-wasm-opt]} : babblec.
     */
    private static int compile(String[] args, PrintWriter out, PrintWriter err) throws IOException {
        Path main = null;
        Path output = null;
        Path root = null;
        Integer optLevel = null;
        org.babblelang.analysis.CompilationTarget target =
                org.babblelang.analysis.CompilationTarget.JVM;
        boolean debug = false;
        boolean wasmOpt = false;
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if ((option.equals("-o") || option.equals("-root")) && i + 1 < args.length) {
                Path value = Path.of(args[++i]);
                if (option.equals("-o")) {
                    output = value;
                } else {
                    root = value;
                }
            } else if ((option.equals("-target") || option.equals("--target"))
                    && i + 1 < args.length) {
                String tVal = args[++i];
                try {
                    target = org.babblelang.analysis.CompilationTarget.parse(tVal);
                } catch (IllegalArgumentException e) {
                    err.println("Error : " + e.getMessage());
                    err.flush();
                    return USAGE_ERROR;
                }
            } else if (option.equals("-dbg") || option.equals("--dbg")) {
                debug = true;
            } else if (option.equals("-wasm-opt") || option.equals("--wasm-opt")) {
                wasmOpt = true;
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
        if (output == null) {
            String name = main.getFileName().toString();
            String stem = name.endsWith(".ba") ? name.substring(0, name.length() - 3) : name;
            output = directory.resolve(stem + (target.isWasm() ? ".wasm" : ".jar"));
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
            if (target.isWasm()) {
                int count =
                        org.babblelang.compiler.Babblec.compileWasm(
                                main, root, output, target, context, optLevel, wasmOpt);
                out.println(
                        "Wrote " + output + " : " + count + (count == 1 ? " module" : " modules"));
            } else {
                org.babblelang.runtime.ProgramIndex index =
                        org.babblelang.compiler.Babblec.compile(
                                main, root, output, context, optLevel, debug);
                out.println(
                        "Wrote "
                                + output
                                + " : "
                                + index.modules().size()
                                + (index.modules().size() == 1 ? " module" : " modules"));
            }
            out.flush();
            return 0;
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            err.println("Error : " + Repl.withoutClassNames(e.getMessage()));
        } catch (IOException e) {
            if (wasmOpt) {
                err.println("Error : " + e.getMessage());
            } else {
                throw e;
            }
        }
        err.flush();
        return FAILED;
    }

    /** Runs a script as the first input of a session, and reports its error, if any. */
    private static int runScript(
            BabbleSession session, String source, String name, PrintWriter errors) {
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

    /**
     * {@code -axioms [-O0|-O1|-O2] <class-or-package>} : dumps assumed/inferred axioms (BIP019
     * §5.1).
     */
    private static int dumpAxioms(String[] args, PrintWriter out, PrintWriter err) {
        String target = null;
        for (int i = 1; i < args.length; i++) {
            String opt = args[i];
            if (opt.equals("-O0") || opt.equals("-O1") || opt.equals("-O2")) {
                continue;
            }
            if (opt.equals("-help") || opt.equals("--help") || opt.equals("-h")) {
                out.println(USAGE);
                out.flush();
                return 0;
            }
            if (!opt.startsWith("-") && target == null) {
                target = opt;
            } else {
                err.println("Unknown option : " + opt);
                err.println(USAGE);
                err.flush();
                return USAGE_ERROR;
            }
        }
        if (target == null) {
            err.println("-axioms needs a class or package name");
            err.println(USAGE);
            err.flush();
            return USAGE_ERROR;
        }
        if (!org.babblelang.analysis.AxiomDumper.dumpTarget(
                target, org.babblelang.analysis.BuiltinAxioms.standard(), out)) {
            err.println("Unknown class or package : " + target);
            err.flush();
            return USAGE_ERROR;
        }
        return 0;
    }
}
