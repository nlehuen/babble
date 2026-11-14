package org.babblelang;

import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.runtime.Namespace;
import org.babblelang.runtime.JavaValue;

import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Babble's interactive mode (docs/design/repl.md) : reads an input, evaluates it in a
 * {@link BabbleSession}, prints its value, and starts again. An input spans several lines
 * when it stops in the middle of something, an open parenthesis for instance.
 * <p>
 * {@link Main} starts it, on its own or after a script.
 */
public final class Repl {
    static final String PROMPT = ">> ";
    static final String CONTINUATION = ".. ";

    private static final String HELP = """
            Type an expression or a definition : its value is printed, unless it is null.
            An input continues on the next line while it is incomplete : inside
            parentheses, or after an operator, a comma, "=", "->", "then" or "else".
              :help     this text
              :cancel   drop the input being typed
              :quit     leave (or end of input : Ctrl-D)
            Defining a name again replaces it for what you type next ; functions defined
            before keep using the old definition. To change a variable they see, assign
            it : x = 2.""";

    private final BabbleSession session;
    private final BufferedReader in;
    private final PrintWriter out;
    private final boolean prompts;

    /**
     * @param session whose context should write to {@code out}
     * @param prompts whether to print the banner and the prompts : not when the input is
     *                piped in
     */
    public Repl(BabbleSession session, BufferedReader in, PrintWriter out, boolean prompts) {
        this.session = session;
        this.in = in;
        this.out = out;
        this.prompts = prompts;
    }

    /** Reads and evaluates inputs until {@code :quit} or the end of the input. */
    public void run() throws IOException {
        if (prompts) {
            out.println("Babble. :help for help, :quit to leave.");
        }
        StringBuilder input = new StringBuilder();
        while (true) {
            if (prompts) {
                out.print(input.isEmpty() ? PROMPT : CONTINUATION);
                out.flush();
            }
            String line = in.readLine();
            if (line == null) {
                if (prompts) {
                    out.println();
                }
                break;
            }
            String command = line.strip();
            if (command.startsWith(":")) {
                if (command.equals(":quit") || command.equals(":q")) {
                    break;
                }
                if (command.equals(":cancel")) {
                    input.setLength(0);
                } else if (command.equals(":help")) {
                    out.println(HELP);
                } else {
                    out.println("Unknown command : " + command + " (:help lists them)");
                }
                continue;
            }
            input.append(line).append('\n');
            switch (BabbleSession.classify(input.toString())) {
                case EMPTY -> input.setLength(0);
                case INCOMPLETE -> {
                    // Keep reading.
                }
                case COMPLETE -> {
                    String source = input.toString();
                    input.setLength(0);
                    evaluate(source);
                }
            }
        }
        out.flush();
    }

    private void evaluate(String source) {
        try {
            Object value = session.eval(source);
            if (value != null) {
                out.println(show(value));
            }
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            out.println("Error : " + message(e));
        } catch (StackOverflowError e) {
            out.println("Error : Stack overflow");
        } catch (RuntimeException e) {
            out.println("Error : " + e);
        }
        out.flush();
    }

    /** A value as the session shows it : a string between quotes, anything else as {@code "" + value} gives it. */
    static String show(Object value) {
        if (value instanceof String string) {
            return "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (value instanceof Namespace) {
            return "package";
        }
        if (value instanceof JavaValue wrapper) {
            return String.valueOf(wrapper.javaValue());
        }
        return String.valueOf(value);
    }

    /**
     * The error, without the exception's class name, and with its line only : every input
     * is its own source, so its name says nothing.
     */
    static String message(ScriptException e) {
        String message = e.getMessage();
        if (e.getFileName() != null) {
            int suffix = message.lastIndexOf(" in " + e.getFileName());
            if (suffix >= 0) {
                message = message.substring(0, suffix);
            }
        }
        message = withoutClassNames(message);
        return e.getLineNumber() > 1 ? message + " (line " + e.getLineNumber() + ")" : message;
    }

    /** An exception's message without the class names it starts with. */
    static String withoutClassNames(String message) {
        return message.replaceFirst("^(?:[\\w.$]+(?:Exception|Error): )+", "");
    }
}
