package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.BinaryOperator}.
 */
@Axioms(value = java.util.function.BinaryOperator.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface BinaryOperatorAxioms {
    @NonNull
    java.lang.Object maxBy(java.util.Comparator p0);

    @NonNull
    java.lang.Object minBy(java.util.Comparator p0);
}
