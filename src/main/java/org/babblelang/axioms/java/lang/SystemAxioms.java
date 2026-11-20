package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;

/**
 * What the analysis assumes about {@code java.lang.System}.
 */
@Axioms(value = java.lang.System.class,
        inherited = false)
interface SystemAxioms {
    @NonNull
    java.lang.Object getProperty(@NonNull java.lang.String p0, @NonNull java.lang.String p1);

    @Nullable
    java.lang.Object getProperty(@NonNull java.lang.String p0);

    @NonNull
    java.lang.Object getenv();

    @Nullable
    java.lang.Object getenv(@NonNull java.lang.String p0);

    @NonNull
    java.lang.Object lineSeparator();
}
