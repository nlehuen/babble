package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.UnaryOperator}.
 */
@Axioms(value = java.util.function.UnaryOperator.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface UnaryOperatorAxioms {
    @NonNull
    java.lang.Object identity();
}
