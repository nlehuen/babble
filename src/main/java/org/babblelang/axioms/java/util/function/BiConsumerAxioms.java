package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.BiConsumer}.
 */
@Axioms(value = java.util.function.BiConsumer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface BiConsumerAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.BiConsumer p0);
}
