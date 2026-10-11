package org.babblelang.runtime;

/**
 * Static description of a call in the source : what natives such as {@code assert} and
 * the "is not callable" check need, without holding on to the parse tree.
 *
 * @param sourceName the script name
 * @param line       the line where the call expression starts
 * @param calleeText the source text of the callee
 * @param argTexts   the source text of each argument expression, in call order
 */
public record CallSite(String sourceName, int line, String calleeText, String[] argTexts) {
}
