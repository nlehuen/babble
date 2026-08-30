package org.babblelang.engine.impl;

/**
 * Control-flow signal for the "return" expression : thrown by the interpreter and
 * caught by {@link Function#call} to unwind the enclosing function body.
 * <p>
 * It deliberately does not extend {@link org.babblelang.engine.BabbleException} : it is
 * not an error, and must not be caught by anything that handles language-level failures.
 * The stack trace is not filled in, as this is a jump rather than an exception.
 */
class ReturnException extends RuntimeException {
    private final transient Object value;

    ReturnException(Object value) {
        super(null, null, false, false);
        this.value = value;
    }

    Object getValue() {
        return value;
    }
}
