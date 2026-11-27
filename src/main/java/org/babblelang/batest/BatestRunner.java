package org.babblelang.batest;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.runtime.BabbleException;

/**
 * Executes a single {@link BatestFile.BatestCase} on a fresh Babble runtime and produces a
 * leak-free normalized {@link CaseOutcome} ({@code BIP009} §4, {@code BIP029-I2}).
 */
public final class BatestRunner {
    private static final String BABBLE_EXCEPTION_PREFIX = BabbleException.class.getName() + ": ";
    private static final String SCRIPT_EXCEPTION_PREFIX = ScriptException.class.getName() + ": ";
    private static final Pattern TRAILING_LOCATION =
            Pattern.compile(
                    " in \\S+? at line number \\d+(?:(?:, column| at column number| column) \\d+)?$");
    private static final Pattern LEXICAL_LINE =
            Pattern.compile("^Lexical error at line \\d+:\\d+ : ");
    private static final Pattern ASSERTION_LINE =
            Pattern.compile("^Assertion failed at line -?\\d+ : ");
    private static final Pattern REASON_LINE =
            Pattern.compile(" because of the (assignment|call) at line \\d+\\)");
    private static final Pattern TYPE_ID_PAIR =
            Pattern.compile(" \\(expected T\\d+, got T\\d+\\)$");

    /**
     * The operating system's reason, in its locale, that {@code FileInputStream} and friends append
     * to the path.
     */
    private static final Pattern OS_FILE_ERROR =
            Pattern.compile("^(java\\.io\\.FileNotFoundException : .*) \\([^()]*\\)$");

    /** JDK 22+ words a reflective call on a receiver of the wrong class differently from JDK 21. */
    private static final Pattern WRONG_RECEIVER =
            Pattern.compile("object of type \\S+ is not an instance of \\S+$");

    public enum Kind {
        OK,
        USER_ERROR,
        INTERNAL_CRASH
    }

    public record CaseOutcome(
            Kind kind, String text, String stdout, String stderr, String error, Throwable crash) {
        public CaseOutcome {
            text = text != null ? text : "";
            stdout = stdout != null ? stdout : "";
            stderr = stderr != null ? stderr : "";
        }

        /** Legacy 3-argument constructor for unit tests. */
        public CaseOutcome(Kind kind, String text, Throwable crash) {
            this(kind, text, "", "", kind == Kind.USER_ERROR ? text : null, crash);
        }
    }

    public record RunResult(
            CaseOutcome outcome,
            List<IrSnapshot> capturedIr,
            int activeOptLevel,
            boolean compiledMode) {
        public RunResult {
            capturedIr = capturedIr != null ? List.copyOf(capturedIr) : List.of();
        }
    }

    private BatestRunner() {}

    public static CaseOutcome run(BatestFile file, BatestFile.BatestCase testCase) {
        return runWithIr(file, testCase, null, null).outcome();
    }

    public static CaseOutcome run(
            BatestFile file, BatestFile.BatestCase testCase, BabbleScriptEngine.Mode mode) {
        return runWithIr(file, testCase, mode, null).outcome();
    }

    public static RunResult runWithIr(BatestFile file, BatestFile.BatestCase testCase) {
        return runWithIr(file, testCase, null, null);
    }

    public static RunResult runWithIr(
            BatestFile file,
            BatestFile.BatestCase testCase,
            BabbleScriptEngine.Mode mode,
            Integer optLevelOverride) {
        return runWithIr(file, testCase, mode, optLevelOverride, null);
    }

    public static RunResult runWithIr(
            BatestFile file,
            BatestFile.BatestCase testCase,
            BabbleScriptEngine.Mode mode,
            Integer optLevelOverride,
            Boolean verifyOverride) {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        if (mode != null) {
            engine.put(BabbleScriptEngine.MODE, mode.name().toLowerCase(Locale.ROOT));
        }
        if (optLevelOverride != null) {
            engine.put(BabbleCompiler.OPT_LEVEL, optLevelOverride);
        }
        if (verifyOverride != null) {
            engine.put(BabbleCompiler.VERIFY, verifyOverride);
        }

        Map<String, String> effectiveModules = file.modules();
        if (!testCase.caseModules().isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(file.modules());
            merged.putAll(testCase.caseModules());
            effectiveModules = merged;
        }
        engine.put(BabbleScriptEngine.MODULE_SOURCE, new MapModuleSource(effectiveModules));

        boolean isCompile = engine.getMode() == BabbleScriptEngine.Mode.COMPILE;
        int activeOpt = isCompile ? BabbleCompiler.resolveOptLevel(engine) : -1;
        List<IrSnapshot> capturedIr = new ArrayList<>();
        if (isCompile && !testCase.irExpectations().isEmpty()) {
            engine.put(
                    BabbleCompiler.IR_OBSERVER,
                    new CapturingIrObserver(testCase.irExpectations(), activeOpt, capturedIr));
        }

        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        context.setWriter(out);
        context.setErrorWriter(err);

        try {
            if (testCase.session()) {
                BabbleSession session = engine.newSession(context);
                if (!file.preamble().isEmpty()
                        && BabbleSession.classify(file.preamble()) != BabbleSession.Input.EMPTY) {
                    session.eval(file.preamble());
                }
                for (String input : testCase.inputs()) {
                    session.eval(input);
                }
            } else {
                String source =
                        file.preamble().isEmpty()
                                ? testCase.code()
                                : file.preamble() + "\n" + testCase.code();
                engine.eval(source, context);
            }
            List<String> warnings = extractNormalizedWarnings(err.toString());
            String normOut = normalizeStdout(out.toString());
            String normErr = String.join("\n", warnings);
            CaseOutcome outcome =
                    new CaseOutcome(
                            Kind.OK,
                            combine(warnings, out.toString(), null),
                            normOut,
                            normErr,
                            null,
                            null);
            return new RunResult(outcome, capturedIr, activeOpt, isCompile);
        } catch (org.babblelang.engine.ExecutionInterruptedError interrupted) {
            throw interrupted;
        } catch (Throwable t) {
            List<String> warnings = extractNormalizedWarnings(err.toString());
            CaseOutcome outcome = classifyFailure(t, warnings, out.toString());
            return new RunResult(outcome, capturedIr, activeOpt, isCompile);
        }
    }

    /**
     * Evaluates behavioral expectations for either v1 ({@code .batest}) or v2 ({@code .batest.md})
     * cases.
     *
     * @return {@code null} if all behavioral expectations match, otherwise a descriptive mismatch
     *     message
     */
    public static String evaluateBehavioral(BatestFile.BatestCase testCase, CaseOutcome outcome) {
        if (outcome.kind() == Kind.INTERNAL_CRASH) {
            return "INTERNAL_CRASH";
        }
        // Independent stdout, stderr, and error/Kind checks (BIP029-D3, BIP029-D4).
        List<String> failures = new ArrayList<>();
        if (testCase.expectedError() == null) {
            if (outcome.kind() != Kind.OK) {
                failures.add(
                        "expected success (no error block) but failed with error: <"
                                + outcome.error()
                                + ">");
            }
        } else {
            if (outcome.kind() != Kind.USER_ERROR) {
                failures.add(
                        "expected error: <"
                                + testCase.expectedError().text()
                                + "> but succeeded (stdout: <"
                                + outcome.stdout()
                                + ">)");
            } else if (!testCase.expectedError().matches(outcome.error())) {
                failures.add(
                        "error mismatch ==> expected: <"
                                + testCase.expectedError().text()
                                + "> but was: <"
                                + outcome.error()
                                + ">");
            }
        }
        if (!testCase.expectedStdout().matches(outcome.stdout())) {
            failures.add(
                    "stdout mismatch ==> expected: <"
                            + testCase.expectedStdout().text()
                            + "> but was: <"
                            + outcome.stdout()
                            + ">");
        }
        if (!testCase.expectedStderr().matches(outcome.stderr())) {
            failures.add(
                    "stderr mismatch ==> expected: <"
                            + testCase.expectedStderr().text()
                            + "> but was: <"
                            + outcome.stderr()
                            + ">");
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }

    public static CaseOutcome classifyFailure(Throwable t, List<String> warnings, String stdout) {
        String normOut = normalizeStdout(stdout);
        String normErr = String.join("\n", warnings);
        if (t instanceof StackOverflowError || t.getCause() instanceof StackOverflowError) {
            return new CaseOutcome(
                    Kind.USER_ERROR,
                    combine(warnings, stdout, "Stack overflow"),
                    normOut,
                    normErr,
                    "Stack overflow",
                    null);
        }
        if (t instanceof BootstrapMethodError bme) {
            BabbleException babble = findCause(bme, BabbleException.class);
            if (babble != null) {
                String normalized =
                        normalizeMessage(
                                babble.getMessage() != null
                                        ? babble.getMessage()
                                        : babble.toString());
                return new CaseOutcome(
                        Kind.USER_ERROR,
                        combine(warnings, stdout, normalized),
                        normOut,
                        normErr,
                        normalized,
                        null);
            }
            return new CaseOutcome(
                    Kind.INTERNAL_CRASH, bme.toString(), normOut, normErr, null, bme);
        }
        if (t instanceof BabbleException babble) {
            String normalized =
                    normalizeMessage(
                            babble.getMessage() != null ? babble.getMessage() : babble.toString());
            return new CaseOutcome(
                    Kind.USER_ERROR,
                    combine(warnings, stdout, normalized),
                    normOut,
                    normErr,
                    normalized,
                    null);
        }
        if (t instanceof ScriptException se) {
            Throwable cause = se.getCause();
            if (cause != null && !(cause instanceof BabbleException)) {
                return new CaseOutcome(
                        Kind.INTERNAL_CRASH, se.toString(), normOut, normErr, null, cause);
            }
            String msg = se.getMessage();
            boolean isUserError =
                    se instanceof TypeCheckException
                            || cause instanceof BabbleException
                            || (msg != null
                                    && (msg.startsWith(BABBLE_EXCEPTION_PREFIX)
                                            || msg.startsWith(
                                                    "java.lang.ArrayIndexOutOfBoundsException")
                                            || msg.startsWith(
                                                    "java.lang.NegativeArraySizeException")
                                            || msg.startsWith("java.lang.ArrayStoreException")));
            if (!isUserError) {
                return new CaseOutcome(
                        Kind.INTERNAL_CRASH, se.toString(), normOut, normErr, null, se);
            }
            String normalized = normalizeMessage(msg);
            return new CaseOutcome(
                    Kind.USER_ERROR,
                    combine(warnings, stdout, normalized),
                    normOut,
                    normErr,
                    normalized,
                    null);
        }
        return new CaseOutcome(Kind.INTERNAL_CRASH, t.toString(), normOut, normErr, null, t);
    }

    private static final class CapturingIrObserver implements IrObserver {
        private final List<BatestFile.IrExpectation> expectations;
        private final int activeOpt;
        private final List<IrSnapshot> sink;

        CapturingIrObserver(
                List<BatestFile.IrExpectation> expectations, int activeOpt, List<IrSnapshot> sink) {
            this.expectations = expectations;
            this.activeOpt = activeOpt;
            this.sink = sink;
        }

        @Override
        public boolean wants(IrTier tier, int optLevel, String stage) {
            if (optLevel != activeOpt) {
                return false;
            }
            for (BatestFile.IrExpectation exp : expectations) {
                if (exp.tier() == tier && exp.appliesTo(optLevel) && exp.matchesStage(stage)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onSnapshot(IrSnapshot snapshot) {
            sink.add(snapshot);
        }
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

    public static List<String> extractNormalizedWarnings(String errorText) {
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

    public static String normalizeStdout(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return "";
        }
        return stdout.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /** Applies BIP009-D5 normalization rules to a raw Babble error string. */
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
        text = TYPE_ID_PAIR.matcher(text).replaceFirst(" (expected T#, got T#)");
        text = OS_FILE_ERROR.matcher(text).replaceFirst("$1 (<OS error>)");
        text =
                WRONG_RECEIVER
                        .matcher(text)
                        .replaceFirst("object is not an instance of declaring class");
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
        String normalizedOut =
                stdout == null ? "" : stdout.replace("\r\n", "\n").replace('\r', '\n');
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
