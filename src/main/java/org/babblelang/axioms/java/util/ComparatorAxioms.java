package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.Comparator}.
 */
@Axioms(value = java.util.Comparator.class,
        nullMarked = Axioms.NullMarked.SUBTYPES)
interface ComparatorAxioms {
    @NonNull
    java.lang.Object comparing(java.util.function.Function p0, java.util.Comparator p1);

    @NonNull
    java.lang.Object comparing(java.util.function.Function p0);

    @NonNull
    java.lang.Object comparingDouble(java.util.function.ToDoubleFunction p0);

    @NonNull
    java.lang.Object comparingInt(java.util.function.ToIntFunction p0);

    @NonNull
    java.lang.Object comparingLong(java.util.function.ToLongFunction p0);

    @NonNull
    java.lang.Object naturalOrder();

    @NonNull
    java.lang.Object nullsFirst(java.util.Comparator p0);

    @NonNull
    java.lang.Object nullsLast(java.util.Comparator p0);

    @NonNull
    java.lang.Object reverseOrder();

    @NonNull
    java.lang.Object reversed();

    @NonNull
    java.lang.Object thenComparing(java.util.Comparator p0);

    @NonNull
    java.lang.Object thenComparing(java.util.function.Function p0, java.util.Comparator p1);

    @NonNull
    java.lang.Object thenComparing(java.util.function.Function p0);

    @NonNull
    java.lang.Object thenComparingDouble(java.util.function.ToDoubleFunction p0);

    @NonNull
    java.lang.Object thenComparingInt(java.util.function.ToIntFunction p0);

    @NonNull
    java.lang.Object thenComparingLong(java.util.function.ToLongFunction p0);
}
