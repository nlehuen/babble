package org.babblelang.engine;

/**
 * Thrown cooperatively by the interpreter when the executing thread is interrupted (e.g. by a
 * {@code -crib} timeout watchdog, {@code BIP041-D7}).
 *
 * <p>Extends {@link Error} with stack-trace suppression so user {@code try}/{@code catch} clauses
 * cannot swallow execution timeouts and normal execution pays zero allocation cost.
 */
public final class ExecutionInterruptedError extends Error {
    public ExecutionInterruptedError() {
        super("Execution interrupted", null, false, false);
    }
}
