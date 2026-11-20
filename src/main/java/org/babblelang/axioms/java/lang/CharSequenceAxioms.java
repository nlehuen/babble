package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.CharSequence}.
 */
@Axioms(value = java.lang.CharSequence.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface CharSequenceAxioms {
    @NonNull
    java.lang.Object subSequence(int p0, int p1);
}
