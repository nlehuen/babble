package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.ServiceLoader}.
 */
@Axioms(value = java.util.ServiceLoader.class,
        inherited = false)
interface ServiceLoaderAxioms {
    @NonNull
    java.lang.Object iterator();

    @NonNull
    java.lang.Object stream();
}
