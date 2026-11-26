package org.babblelang.engine.impl;

/**
 * The value of a self tail call in the interpreter (docs/design/bip024_tail-calls.md) : the
 * arguments of the next activation, which {@link Function#invoke} runs in a loop instead of nesting
 * a call. The analysis only marks calls whose value goes unchanged to the function's result, so
 * nothing else ever sees one.
 */
record TailCall(Object[] args, String[] names) {}
