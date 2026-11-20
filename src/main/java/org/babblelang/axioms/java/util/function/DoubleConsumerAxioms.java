package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.DoubleConsumer}.
 */
@Axioms(value = java.util.function.DoubleConsumer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoubleConsumerAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.DoubleConsumer p0);
}
