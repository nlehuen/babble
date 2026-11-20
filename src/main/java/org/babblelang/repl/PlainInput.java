package org.babblelang.repl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Plain lines, as from a pipe or a test : no editing, no history. The prompts are printed
 * only when asked, not when the input is piped in.
 */
public final class PlainInput implements LineSource {
    private final BufferedReader in;
    private final PrintWriter out;
    private final boolean prompts;

    public PlainInput(BufferedReader in, PrintWriter out, boolean prompts) {
        this.in = in;
        this.out = out;
        this.prompts = prompts;
    }

    @Override
    public String readLine(String prompt) throws IOException {
        if (prompts) {
            out.print(prompt);
            out.flush();
        }
        return in.readLine();
    }
}
