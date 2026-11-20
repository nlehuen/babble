package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Short}.
 */
@Axioms(value = java.lang.Short.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ShortAxioms {
    @NonNull
    java.lang.Object decode(java.lang.String p0);

    @NonNull
    java.lang.Object toString(short p0);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0, int p1);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0);

    @NonNull
    java.lang.Object valueOf(short p0);
}
