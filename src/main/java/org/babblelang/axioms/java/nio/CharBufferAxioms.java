package org.babblelang.axioms.java.nio;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.nio.CharBuffer}.
 */
@Axioms(value = java.nio.CharBuffer.class,
        inherited = false)
interface CharBufferAxioms {
    @NonNull
    java.lang.Object wrap(char[] p0, int p1, int p2);

    @NonNull
    java.lang.Object wrap(char[] p0);

    @NonNull
    java.lang.Object wrap(java.lang.CharSequence p0, int p1, int p2);

    @NonNull
    java.lang.Object wrap(java.lang.CharSequence p0);
}
