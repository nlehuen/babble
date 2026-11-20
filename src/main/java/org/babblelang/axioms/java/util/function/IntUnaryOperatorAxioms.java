package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.IntUnaryOperator}.
 */
@Axioms(value = java.util.function.IntUnaryOperator.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntUnaryOperatorAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.IntUnaryOperator p0);

    @NonNull
    java.lang.Object compose(java.util.function.IntUnaryOperator p0);

    @NonNull
    java.lang.Object identity();
}
