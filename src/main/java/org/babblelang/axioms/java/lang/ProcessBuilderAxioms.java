package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.ProcessBuilder}.
 */
@Axioms(value = java.lang.ProcessBuilder.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ProcessBuilderAxioms {
    @NonNull
    java.lang.Object start();
}
