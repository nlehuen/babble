package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.DoublePredicate}.
 */
@Axioms(value = java.util.function.DoublePredicate.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoublePredicateAxioms {
    @NonNull
    java.lang.Object and(java.util.function.DoublePredicate p0);

    @NonNull
    java.lang.Object negate();

    @NonNull
    java.lang.Object or(java.util.function.DoublePredicate p0);
}
