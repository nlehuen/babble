package org.babblelang.analysis;

/**
 * An error found before the script runs, with the line it was found at.
 */
public class AnalysisException extends RuntimeException {
    private final int line;

    public AnalysisException(String message, int line) {
        super(message);
        this.line = line;
    }

    public int getLine() {
        return line;
    }
}
