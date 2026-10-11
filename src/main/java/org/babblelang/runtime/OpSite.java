package org.babblelang.runtime;

/**
 * Static description of a binary operator in the source : the text of both operands.
 * It is only read when the operator fails, so that error messages quote the operands
 * exactly as they were written.
 */
public record OpSite(String leftText, String rightText) {
}
