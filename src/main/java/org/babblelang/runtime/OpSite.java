package org.babblelang.runtime;

import java.lang.invoke.MethodHandles;

/**
 * Static description of a binary operator in the source : the text of both operands. It is only
 * read when the operator fails, so that error messages quote the operands exactly as they were
 * written.
 */
public record OpSite(String leftText, String rightText) {
    /** {@code CONSTANT_Dynamic} bootstrap materializing an {@link OpSite} lazily. */
    public static OpSite constant(
            MethodHandles.Lookup lookup,
            String name,
            Class<OpSite> type,
            String leftText,
            String rightText) {
        return new OpSite(leftText, rightText);
    }
}
