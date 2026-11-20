package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.StreamSupport}.
 */
@Axioms(value = java.util.stream.StreamSupport.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface StreamSupportAxioms {
    @NonNull
    java.lang.Object doubleStream(java.util.Spliterator.OfDouble p0, boolean p1);

    @NonNull
    java.lang.Object doubleStream(java.util.function.Supplier p0, int p1, boolean p2);

    @NonNull
    java.lang.Object intStream(java.util.Spliterator.OfInt p0, boolean p1);

    @NonNull
    java.lang.Object intStream(java.util.function.Supplier p0, int p1, boolean p2);

    @NonNull
    java.lang.Object longStream(java.util.Spliterator.OfLong p0, boolean p1);

    @NonNull
    java.lang.Object longStream(java.util.function.Supplier p0, int p1, boolean p2);

    @NonNull
    java.lang.Object stream(java.util.Spliterator p0, boolean p1);

    @NonNull
    java.lang.Object stream(java.util.function.Supplier p0, int p1, boolean p2);
}
