package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.BiFunction}.
 */
@Axioms(value = java.util.function.BiFunction.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface BiFunctionAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.Function p0);
}
