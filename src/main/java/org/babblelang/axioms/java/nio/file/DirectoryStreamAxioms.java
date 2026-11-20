package org.babblelang.axioms.java.nio.file;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.nio.file.DirectoryStream}.
 */
@Axioms(value = java.nio.file.DirectoryStream.class,
        inherited = false)
interface DirectoryStreamAxioms {
    @NonNull
    java.lang.Object iterator();
}
