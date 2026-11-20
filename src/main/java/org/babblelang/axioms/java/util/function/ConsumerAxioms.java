package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.Consumer}.
 */
@Axioms(value = java.util.function.Consumer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface ConsumerAxioms {
    @NonNull
    java.lang.Object andThen(java.util.function.Consumer p0);
}
