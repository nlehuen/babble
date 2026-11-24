package org.babblelang.tests.batest;

import org.babblelang.ir.IrTarget;
import org.babblelang.ir.IrTier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsed representation of a {@code .batest} (v1, {@code BIP009}) or {@code .batest.md}
 * (v2, {@code BIP029}) specification file.
 */
public record BatestFile(
        String relativePath,
        Format format,
        String preamble,
        Map<String, String> modules,
        List<BatestCase> cases
) {
    public enum Format {
        V1,
        V2
    }

    /** Legacy constructor for v1 tests. */
    public BatestFile(String relativePath, String preamble, Map<String, String> modules, List<BatestCase> cases) {
        this(relativePath, Format.V1, preamble, modules, cases);
    }

    /** Expected normalized text for one behavioral channel ({@code stdout}, {@code stderr}, or {@code error}). */
    public record TextExpectation(String text, boolean regex) {
        public static final TextExpectation EMPTY = new TextExpectation("", false);

        public TextExpectation {
            text = text != null ? text : "";
        }

        /** Matches {@code actual} literally or with {@code Pattern.MULTILINE | Pattern.DOTALL} ({@code BIP029-D4}). */
        public boolean matches(String actual) {
            String value = actual != null ? actual : "";
            if (!regex) {
                return text.equals(value);
            }
            return Pattern.compile(text, Pattern.MULTILINE | Pattern.DOTALL).matcher(value).matches();
        }
    }

    public enum IrMatchMode {
        EXACT,
        REGEX,
        CHECK
    }

    /**
     * An opt-in IR golden/check expectation attached to a {@code .batest.md} case ({@code BIP029-D4}–{@code D8}).
     *
     * @param tier          IR tier ({@code hir} or {@code mir})
     * @param optLevels     optimization levels where this block applies (e.g. {@code {0}}, {@code {1}}, {@code {0, 1}})
     * @param target        canonical target slice ({@code fn=f}, {@code fn=Vec3.dot}, {@code record=Vec3}, etc.)
     * @param stage         pipeline stage ({@code before}, {@code final}, or {@code after:<PassName>})
     * @param modulePath    normalized virtual module path for {@code in=<mod>}, or {@code null} for the main script
     * @param matchMode     {@link IrMatchMode#EXACT}, {@link IrMatchMode#REGEX}, or {@link IrMatchMode#CHECK}
     * @param expected      expected canonical IR text, regex pattern, or {@code check} predicate lines
     * @param headerLine    1-based line number of the opening fence header
     * @param bodyStartLine 1-based first line number of the block body (immediately after the opening fence)
     * @param bodyEndLine   1-based line number of the closing fence (exclusive end of body lines)
     */
    public record IrExpectation(
            IrTier tier,
            Set<Integer> optLevels,
            IrTarget target,
            String stage,
            String modulePath,
            IrMatchMode matchMode,
            String expected,
            int headerLine,
            int bodyStartLine,
            int bodyEndLine
    ) {
        public IrExpectation {
            Objects.requireNonNull(tier, "tier");
            optLevels = Set.copyOf(optLevels);
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(matchMode, "matchMode");
            expected = expected != null ? expected : "";
        }

        public boolean appliesTo(int optLevel) {
            return optLevels.contains(optLevel);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(tier.label()).append(' ');
            List<Integer> sorted = optLevels.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("-O").append(sorted.get(i));
            }
            sb.append(' ').append(target.format());
            if (!"final".equals(stage)) {
                sb.append(" stage=").append(stage);
            }
            if (modulePath != null) {
                sb.append(" in=").append(modulePath);
            }
            if (matchMode == IrMatchMode.REGEX) {
                sb.append(" regex");
            } else if (matchMode == IrMatchMode.CHECK) {
                sb.append(" check");
            }
            return sb.toString();
        }
    }

    /**
     * A single test case inside a {@code .batest} or {@code .batest.md} file.
     */
    public record BatestCase(
            int index,
            int line,
            String name,
            Format format,
            List<String> inputs,
            boolean session,
            Map<String, String> caseModules,
            String expected,
            TextExpectation expectedStdout,
            TextExpectation expectedStderr,
            TextExpectation expectedError,
            List<IrExpectation> irExpectations
    ) {
        /** Legacy v1 marker for regex lines (retired in v2 in favor of the {@code regex} block flag). */
        public static final String REGEX_PREFIX = "~~ ";

        public BatestCase {
            name = name != null ? name : "";
            format = format != null ? format : Format.V1;
            inputs = List.copyOf(inputs);
            caseModules = caseModules != null ? Map.copyOf(caseModules) : Map.of();
            expected = expected != null ? expected : "";
            expectedStdout = expectedStdout != null ? expectedStdout : TextExpectation.EMPTY;
            expectedStderr = expectedStderr != null ? expectedStderr : TextExpectation.EMPTY;
            irExpectations = irExpectations != null ? List.copyOf(irExpectations) : List.of();
        }

        /** Legacy v1 5-argument constructor. */
        public BatestCase(int index, int line, List<String> inputs, boolean session, String expected) {
            this(index, line, "", Format.V1, inputs, session, Map.of(), expected,
                    TextExpectation.EMPTY, TextExpectation.EMPTY, null, List.of());
        }

        public String code() {
            return inputs.get(0);
        }

        public String displayName() {
            if (!name.isEmpty()) {
                return name + " (line " + line + ")";
            }
            return "case #" + index + " (line " + line + ")";
        }

        /**
         * Legacy v1 expectation matcher (used for {@link Format#V1} cases).
         */
        public boolean matches(String actual) {
            if (expected.equals(actual)) {
                return true;
            }
            if (!expected.contains(REGEX_PREFIX)) {
                return false;
            }
            String[] expectedLines = expected.split("\n", -1);
            String[] actualLines = actual.split("\n", -1);
            if (expectedLines.length != actualLines.length) {
                return false;
            }
            for (int i = 0; i < expectedLines.length; i++) {
                if (!lineMatches(expectedLines[i], actualLines[i])) {
                    return false;
                }
            }
            return true;
        }

        private static boolean lineMatches(String expectedLine, String actualLine) {
            if (expectedLine.startsWith(REGEX_PREFIX)) {
                return Pattern.compile(expectedLine.substring(REGEX_PREFIX.length())).matcher(actualLine).matches();
            }
            return expectedLine.equals(actualLine);
        }
    }
}
