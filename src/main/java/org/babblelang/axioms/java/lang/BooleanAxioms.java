package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Boolean}.
 */
@Axioms(value = java.lang.Boolean.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface BooleanAxioms {
    @NonNull
    java.lang.Object toString(boolean p0);

    @NonNull
    java.lang.Object valueOf(boolean p0);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0);
}
