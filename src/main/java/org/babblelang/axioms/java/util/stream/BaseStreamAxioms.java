package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.BaseStream}.
 */
@Axioms(value = java.util.stream.BaseStream.class,
        nullMarked = Axioms.NullMarked.SUBTYPES)
interface BaseStreamAxioms {
    @NonNull
    java.lang.Object onClose(java.lang.Runnable p0);

    @NonNull
    java.lang.Object parallel();

    @NonNull
    java.lang.Object sequential();

    @NonNull
    java.lang.Object unordered();
}
