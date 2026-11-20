package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Byte}.
 */
@Axioms(value = java.lang.Byte.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ByteAxioms {
    @NonNull
    java.lang.Object decode(java.lang.String p0);

    @NonNull
    java.lang.Object toString(byte p0);

    @NonNull
    java.lang.Object valueOf(byte p0);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0, int p1);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0);
}
