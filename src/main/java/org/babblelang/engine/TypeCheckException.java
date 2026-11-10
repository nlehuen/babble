package org.babblelang.engine;

import javax.script.ScriptException;

/**
 * A script the type checker rejected, in error mode. It carries the first diagnostic ;
 * all of them were written to the context's error writer before it was thrown.
 */
public class TypeCheckException extends ScriptException {
    public TypeCheckException(String message, String fileName, int lineNumber, int columnNumber) {
        super(message, fileName, lineNumber, columnNumber);
    }
}
