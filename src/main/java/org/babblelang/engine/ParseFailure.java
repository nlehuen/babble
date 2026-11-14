package org.babblelang.engine;

import org.babblelang.runtime.BabbleException;
import javax.script.ScriptException;

/**
 * A source text the lexer or the parser rejected.
 */
final class ParseFailure extends RuntimeException {
    private final int line;
    private final int column;
    private final boolean lexical;
    private final boolean atEnd;

    /**
     * @param column from 1
     * @param atEnd  whether the text only fails for stopping where it does : more text
     *               could complete it
     */
    ParseFailure(String message, int line, int column, boolean lexical, boolean atEnd) {
        super(message);
        this.line = line;
        this.column = column;
        this.lexical = lexical;
        this.atEnd = atEnd;
    }

    boolean atEnd() {
        return atEnd;
    }

    ScriptException toScriptException(String sourceName) {
        BabbleException error = new BabbleException(getMessage());
        if (lexical) {
            // The message already says where.
            return new ScriptException(error);
        }
        return new ScriptException(error.toString(), sourceName, line, column);
    }
}
