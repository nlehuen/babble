package org.babblelang;

import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.repl.LineSource;
import org.babblelang.repl.PlainInput;
import org.babblelang.runtime.ArraySupport;

import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Babble's interactive mode (docs/design/bip004_repl.md) : reads an input, evaluates it in a
 * {@link BabbleSession}, prints its value, and starts again. An input spans several lines
 * when it stops in the middle of something, an open parenthesis for instance.
 * <p>
 * Lines come from a {@link LineSource} : plain lines, or a line editor on a terminal, with
 * history and completion (§9). {@link Main} starts it, on its own or after a script.
 */
public final class Repl {
    public static final String PROMPT = ">> ";
    public static final String CONTINUATION = ".. ";

    private static final String HELP = """
            Type an expression or a definition : its value is printed, unless it is null.
            An input continues on the next line while it is incomplete : inside
            parentheses, or after an operator, a comma, "=", "->", "then" or "else".
              :help          this text
              :type expr     the type of an expression, without running it
              :load file.ba  runs a file, as if it was typed : what it defines stays
              :cancel        drop the input being typed (or Ctrl-C)
              :quit          leave (or end of input : Ctrl-D)
            On a terminal, Up and Down recall earlier inputs, kept in ~/.babble_history,
            and Tab completes names, members and commands.
            Defining a name again replaces it for what you type next ; functions defined
            before keep using the old definition. To change a variable they see, assign
            it : x = 2.""";

    private final BabbleSession session;
    private final LineSource in;
    private final PrintWriter out;
    private final boolean prompts;

    /**
     * @param session whose context should write to {@code out}
     * @param prompts whether to print the banner and the prompts : not when the input is
     *                piped in
     */
    public Repl(BabbleSession session, BufferedReader in, PrintWriter out, boolean prompts) {
        this(session, new PlainInput(in, out, prompts), out, prompts);
    }

    /**
     * @param banner whether to print the banner : the source shows the prompts, if any
     */
    public Repl(BabbleSession session, LineSource in, PrintWriter out, boolean banner) {
        this.session = session;
        this.in = in;
        this.out = out;
        this.prompts = banner;
    }

    /** Reads and evaluates inputs until {@code :quit} or the end of the input. */
    public void run() throws IOException {
        if (prompts) {
            out.println("Babble. :help for help, :quit to leave.");
        }
        StringBuilder input = new StringBuilder();
        while (true) {
            out.flush();
            String line;
            try {
                line = in.readLine(input.isEmpty() ? PROMPT : CONTINUATION);
            } catch (LineSource.Cancelled e) {
                input.setLength(0);
                continue;
            }
            if (line == null) {
                if (prompts) {
                    out.println();
                }
                break;
            }
            String command = line.strip();
            if (command.startsWith(":")) {
                if (!command(command, input)) {
                    break;
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

    /** Runs a command. @return false to leave */
    private boolean command(String command, StringBuilder input) {
        int space = command.indexOf(' ');
        String name = space < 0 ? command : command.substring(0, space);
        String argument = space < 0 ? "" : command.substring(space + 1).strip();
        switch (name) {
            case ":quit", ":q" -> {
                return false;
            }
            case ":cancel" -> input.setLength(0);
            case ":help" -> out.println(HELP);
            case ":type", ":t" -> type(argument);
            case ":load", ":l" -> load(argument);
            default -> out.println("Unknown command : " + command + " (:help lists them)");
        }
        out.flush();
        return true;
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

    /** {@code :type expr} : the static type of an expression, which does not run. */
    private void type(String source) {
        if (BabbleSession.classify(source) != BabbleSession.Input.COMPLETE) {
            out.println(":type needs a complete expression : :type 1 + 2");
            return;
        }
        try {
            String type = session.typeOf(source);
            out.println(type != null ? type : "No type : " + source);
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            out.println("Error : " + message(e));
        } catch (RuntimeException e) {
            out.println("Error : " + e);
        }
    }

    /**
     * {@code :load file.ba} : runs a file as an input of the session, as {@code -keep-alive}
     * runs a script. Its value is not shown, and its errors name it.
     */
    private void load(String argument) {
        String file = unquote(argument);
        if (file.isEmpty()) {
            out.println(":load needs a file : :load script.ba");
            return;
        }
        String source;
        try {
            source = Files.readString(Path.of(file));
        } catch (IOException | RuntimeException e) {
            out.println("Cannot read " + file + " : " + (e instanceof NoSuchFileException ? "no such file" : e));
            return;
        }
        if (BabbleSession.classify(source) == BabbleSession.Input.EMPTY) {
            return;
        }
        try {
            session.eval(source, file);
        } catch (TypeCheckException e) {
            // Its diagnostics are already written.
        } catch (ScriptException e) {
            out.println("Error : " + withoutClassNames(e.getMessage()));
        } catch (StackOverflowError e) {
            out.println("Error : Stack overflow in " + file);
        } catch (RuntimeException e) {
            out.println("Error : " + e);
        }
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** A value as the session shows it : a string between quotes, arrays as {@code [a, b]}, anything else as {@code "" + value} gives it. */
    static String show(Object value) {
        if (value instanceof String string) {
            return "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return ArraySupport.format(value);
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
