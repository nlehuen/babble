package org.babblelang.runtime;

import java.lang.invoke.MethodHandles;

/**
 * Static description of a call in the source : what natives such as {@code assert} and the "is not
 * callable" check need, without holding on to the parse tree.
 *
 * @param sourceName the script name
 * @param line the line where the call expression starts
 * @param calleeText the source text of the callee
 * @param argTexts the source text of each argument expression, in call order
 */
public record CallSite(String sourceName, int line, String calleeText, String[] argTexts) {
    /** {@code CONSTANT_Dynamic} bootstrap materializing a {@link CallSite} lazily. */
    public static CallSite constant(
            MethodHandles.Lookup lookup,
            String name,
            Class<CallSite> type,
            String sourceName,
            int line,
            String calleeText,
            String packedArgTexts) {
        return new CallSite(
                sourceName, line, calleeText, BabbleConstants.unpackStrings(packedArgTexts));
    }
}
