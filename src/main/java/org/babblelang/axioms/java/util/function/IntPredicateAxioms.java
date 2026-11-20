package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.IntPredicate}.
 */
@Axioms(value = java.util.function.IntPredicate.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntPredicateAxioms {
    @NonNull
    java.lang.Object and(java.util.function.IntPredicate p0);

    @NonNull
    java.lang.Object negate();

    @NonNull
    java.lang.Object or(java.util.function.IntPredicate p0);
}
