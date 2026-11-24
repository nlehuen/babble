package org.babblelang.ir.mir;

/**
 * Exception handler entry attached to a protected {@link MirBlock} ({@code BIP014-P3}, {@code BIP028}).
 *
 * <p>Handlers on a block are ordered innermost-to-outermost matching the JVM {@code exception_table}.
 * When an instruction in the protected block throws a matching {@link Throwable}, control transfers
 * to {@link #handlerBlockId()}, whose sole block parameter receives the thrown exception object.
 *
 * @param catchJvmType   JVM internal class name (e.g. {@code java/lang/IllegalArgumentException},
 *                       {@code org/babblelang/runtime/ThrownException}), or {@code null} for catch-all
 * @param handlerBlockId target handler {@link MirBlock#id()}
 * @param line           source line for diagnostics
 */
public record MirHandler(String catchJvmType, int handlerBlockId, int line) {
    public boolean isCatchAll() {
        return catchJvmType == null;
    }
}
