package org.babblelang.engine.impl;

/**
 * Common zero-stacktrace base class for interpreter-internal non-local jumps ({@code return},
 * {@code break}, {@code continue}). User {@code catch} clauses bypass {@code ControlFlowSignal}
 * completely while {@code finally} blocks still execute as the stack unwinds ({@code BIP028-D4}).
 */
abstract class ControlFlowSignal extends RuntimeException {
    ControlFlowSignal() {
        super(null, null, false, false);
    }
}
