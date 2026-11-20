package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Runtime}.
 */
@Axioms(value = java.lang.Runtime.class,
        inherited = false)
interface RuntimeAxioms {
    @NonNull
    java.lang.Object getRuntime();
}
