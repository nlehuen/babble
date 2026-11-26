package org.babblelang.repl;

import java.io.IOException;
import java.nio.file.Path;
import org.babblelang.Repl;
import org.babblelang.engine.BabbleSession;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * The line editor of the interactive mode on a terminal (docs/design/bip004_repl.md §9), with JLine
 * : editing, a history kept between sessions, and completion. A multi-line input is edited as one
 * buffer ({@link BabbleLineParser}), and comes back whole : the loop sees it complete at once.
 * Ctrl-C drops the input being typed, Ctrl-D on an empty line leaves.
 */
public final class TerminalInput implements LineSource {
    /** Inputs kept in the history file. */
    static final int HISTORY_SIZE = 1000;

    private final Terminal terminal;
    private final LineReader reader;

    /** On the process's terminal. */
    public static TerminalInput open(BabbleSession session, Path history) throws IOException {
        return new TerminalInput(
                session, TerminalBuilder.builder().system(true).dumb(true).build(), history);
    }

    /**
     * @param history the file inputs are kept in, or null for none
     */
    public TerminalInput(BabbleSession session, Terminal terminal, Path history) {
        this.terminal = terminal;
        LineReaderBuilder builder =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("babble")
                        .parser(new BabbleLineParser(session))
                        .completer((reader, line, candidates) -> {
                            if (line instanceof BabbleLineParser.Line parsed) {
                                int shared = parsed.completion().shared();
                                for (String candidate : parsed.completion().candidates()) {
                                    // Not complete : no space is added, a call or a member may
                                    // follow.
                                    String display =
                                            shared > 0 && shared < candidate.length()
                                                    ? candidate.substring(shared)
                                                    : candidate;
                                    candidates.add(
                                            new Candidate(
                                                    candidate, display, null, null, null, null,
                                                    false));
                                }
                            }
                        })
                        .variable(LineReader.SECONDARY_PROMPT_PATTERN, Repl.CONTINUATION)
                        .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                        .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_SIZE)
                        // "!" is Babble's, not a history expansion ; Tab completes, also at the
                        // start of a line.
                        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                        .option(LineReader.Option.INSERT_TAB, false)
                        .option(LineReader.Option.HISTORY_IGNORE_DUPS, true);
        if (history != null) {
            builder.variable(LineReader.HISTORY_FILE, history);
        }
        this.reader = builder.build();
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new Cancelled();
        } catch (EndOfFileException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            reader.getHistory().save();
        } finally {
            terminal.close();
        }
    }
}
