package org.babblelang.tests.batest;

import java.util.List;
import java.util.Map;

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
     * @param expected normalized expected stdout or normalized primary error message
     */
    public record BatestCase(
            int index,
            int line,
            List<String> inputs,
            boolean session,
            String expected
    ) {
        public String code() {
            return inputs.get(0);
        }
    }
}
