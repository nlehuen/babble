package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.LongConsumer}.
 */
@Axioms(value = java.util.function.LongConsumer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongConsumerAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.LongConsumer p0);
}
