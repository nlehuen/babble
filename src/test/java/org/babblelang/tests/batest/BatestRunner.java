package org.babblelang.tests.batest;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.runtime.BabbleException;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Executes a single {@link BatestFile.BatestCase} on a fresh Babble runtime and produces
 * a leak-free normalized {@link CaseOutcome} (BIP009 §4).
 */
public final class BatestRunner {
    private static final String BABBLE_EXCEPTION_PREFIX = BabbleException.class.getName() + ": ";
    private static final String SCRIPT_EXCEPTION_PREFIX = ScriptException.class.getName() + ": ";
    private static final Pattern TRAILING_LOCATION = Pattern.compile(
            " in \\S+? at line number \\d+(?:(?:, column| at column number| column) \\d+)?$");
    private static final Pattern LEXICAL_LINE = Pattern.compile("^Lexical error at line \\d+:\\d+ : ");
    private static final Pattern ASSERTION_LINE = Pattern.compile("^Assertion failed at line -?\\d+ : ");
    private static final Pattern REASON_LINE = Pattern.compile(" because of the (assignment|call) at line \\d+\\)");

    public enum Kind {
        OK,
        USER_ERROR,
        INTERNAL_CRASH
    }

    public record CaseOutcome(Kind kind, String text, Throwable crash) {
    }

    private BatestRunner() {
    }

    public static CaseOutcome run(BatestFile file, BatestFile.BatestCase testCase) {
        return run(file, testCase, null);
    }

    public static CaseOutcome run(BatestFile file, BatestFile.BatestCase testCase, BabbleScriptEngine.Mode mode) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        if (mode != null) {
            engine.put(BabbleScriptEngine.MODE, mode.name().toLowerCase(Locale.ROOT));
        }
        engine.put(BabbleScriptEngine.MODULE_SOURCE, new MapModuleSource(file.modules()));

        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        context.setWriter(out);
        context.setErrorWriter(err);

        try {
            if (testCase.session()) {
                BabbleSession session = engine.newSession(context);
                if (!file.preamble().isEmpty()) {
                    session.eval(file.preamble());
                }
                for (String input : testCase.inputs()) {
                    session.eval(input);
                }
            } else {
                String source = file.preamble().isEmpty()
                        ? testCase.code()
                        : file.preamble() + "\n" + testCase.code();
                engine.eval(source, context);
            }
            List<String> warnings = extractNormalizedWarnings(err.toString());
            return new CaseOutcome(Kind.OK, combine(warnings, out.toString(), null), null);
        } catch (Throwable t) {
            List<String> warnings = extractNormalizedWarnings(err.toString());
            return classifyFailure(t, warnings, out.toString());
        }
    }

    static CaseOutcome classifyFailure(Throwable t, List<String> warnings, String stdout) {
        if (t instanceof StackOverflowError || t.getCause() instanceof StackOverflowError) {
            return new CaseOutcome(Kind.USER_ERROR, combine(warnings, stdout, "Stack overflow"), null);
        }
        if (t instanceof BootstrapMethodError bme) {
            BabbleException babble = findCause(bme, BabbleException.class);
            if (babble != null) {
                String normalized = normalizeMessage(babble.getMessage() != null ? babble.getMessage() : babble.toString());
                return new CaseOutcome(Kind.USER_ERROR, combine(warnings, stdout, normalized), null);
            }
            return new CaseOutcome(Kind.INTERNAL_CRASH, bme.toString(), bme);
        }
        if (t instanceof BabbleException babble) {
            String normalized = normalizeMessage(babble.getMessage() != null ? babble.getMessage() : babble.toString());
            return new CaseOutcome(Kind.USER_ERROR, combine(warnings, stdout, normalized), null);
        }
        if (t instanceof ScriptException se) {
            Throwable cause = se.getCause();
            if (cause != null && !(cause instanceof BabbleException)) {
                return new CaseOutcome(Kind.INTERNAL_CRASH, se.toString(), cause);
            }
            String msg = se.getMessage();
            boolean isUserError = se instanceof TypeCheckException
                    || cause instanceof BabbleException
                    || (msg != null && msg.startsWith(BABBLE_EXCEPTION_PREFIX));
            if (!isUserError) {
                return new CaseOutcome(Kind.INTERNAL_CRASH, se.toString(), se);
            }
            String normalized = normalizeMessage(msg);
            return new CaseOutcome(Kind.USER_ERROR, combine(warnings, stdout, normalized), null);
        }
        return new CaseOutcome(Kind.INTERNAL_CRASH, t.toString(), t);
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable cur = t;
        while (cur != null) {
            if (type.isInstance(cur)) {
                return type.cast(cur);
            }
            cur = cur.getCause();
        }
        return null;
    }

    static List<String> extractNormalizedWarnings(String errorText) {
        if (errorText == null || errorText.isEmpty()) {
            return List.of();
        }
        String normalized = errorText.replace("\r\n", "\n").replace('\r', '\n');
        List<String> warnings = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            if (line.startsWith("Warning : ")) {
                warnings.add(stripTrailingLocation(line).strip());
            }
        }
        return warnings;
    }

    /**
     * Applies BIP009-D5 normalization rules to a raw Babble error string.
     */
    public static String normalizeMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        boolean stripped;
        do {
            stripped = false;
            if (text.startsWith(SCRIPT_EXCEPTION_PREFIX)) {
                text = text.substring(SCRIPT_EXCEPTION_PREFIX.length());
                stripped = true;
            }
            if (text.startsWith(BABBLE_EXCEPTION_PREFIX)) {
                text = text.substring(BABBLE_EXCEPTION_PREFIX.length());
                stripped = true;
            }
        } while (stripped);

        text = stripTrailingLocation(text);
        text = LEXICAL_LINE.matcher(text).replaceFirst("Lexical error : ");
        text = ASSERTION_LINE.matcher(text).replaceFirst("Assertion failed : ");
        text = REASON_LINE.matcher(text).replaceAll(" because of the $1)");
        return text.strip();
    }

    private static String stripTrailingLocation(String text) {
        String current = text.strip();
        while (true) {
            String next = TRAILING_LOCATION.matcher(current).replaceFirst("");
            if (next.equals(current)) {
                return current;
            }
            current = next.stripTrailing();
        }
    }

    private static String combine(List<String> warnings, String stdout, String errorLine) {
        StringBuilder sb = new StringBuilder();
        for (String warning : warnings) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(warning);
        }
        String normalizedOut = stdout == null ? "" : stdout.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalizedOut.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(normalizedOut.stripTrailing());
        }
        if (errorLine != null && !errorLine.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(errorLine);
        }
        return sb.toString().strip();
    }
}
