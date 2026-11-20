package org.babblelang.axioms.java.util.function;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.function.Predicate}.
 */
@Axioms(value = java.util.function.Predicate.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface PredicateAxioms {
    @NonNull
    java.lang.Object and(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object isEqual(java.lang.Object p0);

    @NonNull
    java.lang.Object negate();

    @NonNull
    java.lang.Object not(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object or(java.util.function.Predicate p0);
}
