package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.DoubleUnaryOperator}.
 */
@Axioms(value = java.util.function.DoubleUnaryOperator.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoubleUnaryOperatorAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.DoubleUnaryOperator p0);

    @NonNull
    java.lang.Object compose(java.util.function.DoubleUnaryOperator p0);

    @NonNull
    java.lang.Object identity();
}
