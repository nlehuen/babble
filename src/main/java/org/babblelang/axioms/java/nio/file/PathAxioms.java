package org.babblelang.axioms.java.nio.file;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.nio.file.Path}.
 */
@Axioms(value = java.nio.file.Path.class,
        inherited = false)
interface PathAxioms {
    @NonNull
    java.lang.Object iterator();

    @NonNull
    java.lang.Object of(java.lang.String p0, java.lang.String[] p1);

    @NonNull
    java.lang.Object of(java.net.URI p0);
}
