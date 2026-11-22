package org.babblelang.tests.batest;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed representation of a {@code .batest} specification file (BIP009).
 */
public record BatestFile(
        String relativePath,
        String preamble,
        Map<String, String> modules,
        List<BatestCase> cases
) {
    /**
     * A single test case inside a {@code .batest} file.
     *
     * @param index    1-based index of the case within the file
     * @param line     1-based line number of the opening {@code {{{{{} marker
     * @param inputs   single snippet when {@code session} is false, or sequential session inputs when true
     * @param session  true when {@code ----------} splits the case into multiple {@code BabbleSession} inputs
     * @param expected normalized expected stdout or normalized primary error message; a line starting with
     *                 {@link #REGEX_PREFIX} is a regular expression the whole corresponding actual line must match
     */
    public record BatestCase(
            int index,
            int line,
            List<String> inputs,
            boolean session,
            String expected
    ) {
        /** Marks an expected line as a regular expression, for output that varies with the JDK. */
        public static final String REGEX_PREFIX = "~~ ";

        public String code() {
            return inputs.get(0);
        }

        /**
         * Whether {@code actual} meets this case's expectation : the same lines, each one equal to its expected
         * line or, for an expected line starting with {@link #REGEX_PREFIX}, matching it entirely.
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
