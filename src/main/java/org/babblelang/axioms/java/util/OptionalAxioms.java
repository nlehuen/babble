package org.babblelang.axioms.java.util;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;
import org.babblelang.axioms.Parametric;

/**
 * What the analysis assumes about {@code java.util.Optional}.
 */
@Axioms(value = java.util.Optional.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface OptionalAxioms {
    @NonNull
    java.lang.Object empty();

    @NonNull
    java.lang.Object filter(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object flatMap(java.util.function.Function p0);

    @Parametric
    java.lang.Object get();

    @NonNull
    java.lang.Object map(java.util.function.Function p0);

    @NonNull
    java.lang.Object of(@NonNull java.lang.Object p0);

    @NonNull
    java.lang.Object ofNullable(@Nullable java.lang.Object p0);

    @NonNull
    java.lang.Object or(java.util.function.Supplier p0);

    @Parametric
    java.lang.Object orElse(@Parametric java.lang.Object p0);

    @Parametric
    java.lang.Object orElseThrow();

    @Parametric
    java.lang.Object orElseThrow(java.util.function.Supplier p0);

    @NonNull
    java.lang.Object stream();
}
