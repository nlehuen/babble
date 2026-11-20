package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.Class}.
 */
@Axioms(value = java.lang.Class.class,
        inherited = false)
interface ClassAxioms {
    @NonNull
    java.lang.Object forName(java.lang.Module p0, java.lang.String p1);

    @NonNull
    java.lang.Object forName(java.lang.String p0, boolean p1, java.lang.ClassLoader p2);

    @NonNull
    java.lang.Object forName(java.lang.String p0);

    @NonNull
    java.lang.Object getCanonicalName();

    @NonNull
    java.lang.Object getName();

    @NonNull
    java.lang.Object getSimpleName();
}
