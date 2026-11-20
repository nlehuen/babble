package org.babblelang.repl;

import java.io.IOException;

/**
 * Where the interactive mode reads what is typed (docs/design/bip004_repl.md §9) : plain lines
 * ({@link PlainInput}), or a line editor on a terminal ({@link TerminalInput}), which hands
 * over a whole multi-line input at once.
 */
public interface LineSource extends AutoCloseable {
    /**
     * Shows the prompt, if this source shows prompts, and reads a line.
     *
     * @return the line, without its end, or null at the end of the input
     * @throws Cancelled when the user drops what they were typing (Ctrl-C)
     */
    String readLine(String prompt) throws IOException;

    @Override
    default void close() throws IOException {
    }

    /** What is being typed is dropped : the loop forgets the unfinished input. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled", null, false, false);
        }
    }
}
