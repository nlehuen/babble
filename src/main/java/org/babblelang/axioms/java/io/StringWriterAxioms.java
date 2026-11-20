package org.babblelang.axioms.java.io;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.io.StringWriter}.
 */
@Axioms(value = java.io.StringWriter.class,
        inherited = false)
interface StringWriterAxioms {
    @NonNull
    java.lang.Object getBuffer();
}
