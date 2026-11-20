package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Thread}.
 */
@Axioms(value = java.lang.Thread.class,
        inherited = false)
interface ThreadAxioms {
    @NonNull
    java.lang.Object currentThread();

    @NonNull
    java.lang.Object getName();

    @NonNull
    java.lang.Object getStackTrace();
}
