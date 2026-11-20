package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.IntConsumer}.
 */
@Axioms(value = java.util.function.IntConsumer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntConsumerAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.IntConsumer p0);
}
