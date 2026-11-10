package org.babblelang;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.engine.impl.natives.java.JavaObject;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.BufferedReader;
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
 * Babble's command line, and the main class of its jar (docs/design/repl.md) :
 * <pre>
 * java -jar babble.jar                              the interactive mode
 * java -jar babble.jar script.ba [arguments...]     runs a script
 * java -jar babble.jar -keep-alive script.ba ...    runs it, then continues in the interactive mode
 * </pre>
 * A script finds its arguments in {@code args}, a {@code java.util.List} of strings. The
 * mode and the type checker are the engine's : {@code -Dbabble.mode=compile},
 * {@code -Dbabble.typecheck=warn|error}.
 */
public final class Main {
    static final String USAGE = """
            Usage : java -jar babble.jar [-keep-alive] [script.ba [arguments...]]
              Without a script, starts the interactive mode.
              With one, runs it. Its arguments are in the list "args" : args.size(), args.get(0).
              -keep-alive   after the script, continue in the interactive mode, with what it defined
              -help         this text""";

    /** The exit statuses : a script that failed, and a command line that can't be used. */
    static final int FAILED = 1;
    static final int USAGE_ERROR = 2;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int status = run(args, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out, true),
                new PrintWriter(System.err, true), System.console() != null);
        if (status != 0) {
            // Not on success : threads the script started may still be working.
            System.exit(status);
        }
    }

    /**
     * @param console whether a person is typing : the interactive mode then shows its prompts
     * @return the exit status
     */
    static int run(String[] args, BufferedReader in, PrintWriter out, PrintWriter err, boolean console) throws IOException {
        boolean keepAlive = false;
        int first = 0;
        // Options come before the script : what follows it is the script's.
        for (; first < args.length && args[first].startsWith("-"); first++) {
            switch (args[first]) {
                case "-keep-alive", "--keep-alive" -> keepAlive = true;
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
            new Repl(session, in, out, console).run();
            return 0;
        }
        return status;
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
