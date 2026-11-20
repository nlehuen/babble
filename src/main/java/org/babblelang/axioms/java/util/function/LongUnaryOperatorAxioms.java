package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.LongUnaryOperator}.
 */
@Axioms(value = java.util.function.LongUnaryOperator.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongUnaryOperatorAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.LongUnaryOperator p0);

    @NonNull
    java.lang.Object compose(java.util.function.LongUnaryOperator p0);

    @NonNull
    java.lang.Object identity();
}
