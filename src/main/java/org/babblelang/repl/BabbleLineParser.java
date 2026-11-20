package org.babblelang.repl;

import org.babblelang.engine.BabbleSession;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EOFError;
import org.jline.reader.Parser;

import java.util.List;

/**
 * How the line editor reads Babble (docs/design/bip004_repl.md §9, {@code BIP004-D3}) : Enter
 * accepts an input only when it is complete, as {@link BabbleSession#classify} tells, so an
 * unfinished input goes on in the same buffer, on a new line after the {@code ..} prompt, and
 * is recalled and edited whole. Commands are always complete. For completion, the word is what
 * {@link Completions} completes : a chain of names, a command, or a file name after {@code :load}.
 */
public final class BabbleLineParser implements Parser {
    private final BabbleSession session;

    /** @param session what completion looks names up in, or null for no completion */
    public BabbleLineParser(BabbleSession session) {
        this.session = session;
    }

    @Override
    public CompletingParsedLine parse(String line, int cursor, ParseContext context) {
        if (context == ParseContext.ACCEPT_LINE) {
            if (!line.strip().startsWith(":") && BabbleSession.classify(line) == BabbleSession.Input.INCOMPLETE) {
                throw new EOFError(-1, -1, "incomplete input");
            }
            return new Line(line, cursor, new Completions.Completion(cursor, List.of()));
        }
        int at = Math.min(cursor, line.length());
        Completions.Completion completion = session != null ? Completions.at(session, line, at)
                : new Completions.Completion(at, List.of());
        return new Line(line, at, completion);
    }

    /** A line, with what completes the word before the cursor. */
    record Line(String line, int cursor, Completions.Completion completion) implements CompletingParsedLine {
        @Override
        public String word() {
            return line.substring(completion.start(), cursor);
        }

        @Override
        public int wordCursor() {
            return cursor - completion.start();
        }

        @Override
        public int wordIndex() {
            return 0;
        }

        @Override
        public List<String> words() {
            return List.of(word());
        }

        /** Candidates go in as they are : names and paths need no quoting. */
        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            return candidate;
        }

        @Override
        public int rawWordCursor() {
            return wordCursor();
        }

        @Override
        public int rawWordLength() {
            return word().length();
        }
    }
}
