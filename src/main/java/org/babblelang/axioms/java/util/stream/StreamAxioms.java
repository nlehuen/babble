package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.Stream}.
 */
@Axioms(value = java.util.stream.Stream.class,
        inherited = false)
interface StreamAxioms {
    @NonNull
    java.lang.Object builder();

    @NonNull
    java.lang.Object collect(java.util.function.Supplier p0, java.util.function.BiConsumer p1, java.util.function.BiConsumer p2);

    @NonNull
    java.lang.Object collect(java.util.stream.Collector p0);

    @NonNull
    java.lang.Object concat(java.util.stream.Stream p0, java.util.stream.Stream p1);

    @NonNull
    java.lang.Object distinct();

    @NonNull
    java.lang.Object dropWhile(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object empty();

    @NonNull
    java.lang.Object filter(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object findAny();

    @NonNull
    java.lang.Object findFirst();

    @NonNull
    java.lang.Object flatMap(java.util.function.Function p0);

    @NonNull
    java.lang.Object generate(java.util.function.Supplier p0);

    @NonNull
    java.lang.Object iterate(java.lang.Object p0, java.util.function.Predicate p1, java.util.function.UnaryOperator p2);

    @NonNull
    java.lang.Object iterate(java.lang.Object p0, java.util.function.UnaryOperator p1);

    @NonNull
    java.lang.Object limit(long p0);

    @NonNull
    java.lang.Object map(java.util.function.Function p0);

    @NonNull
    java.lang.Object mapToDouble(java.util.function.ToDoubleFunction p0);

    @NonNull
    java.lang.Object mapToInt(java.util.function.ToIntFunction p0);

    @NonNull
    java.lang.Object mapToLong(java.util.function.ToLongFunction p0);

    @NonNull
    java.lang.Object max(java.util.Comparator p0);

    @NonNull
    java.lang.Object min(java.util.Comparator p0);

    @NonNull
    java.lang.Object of(java.lang.Object[] p0);

    @NonNull
    java.lang.Object of(java.lang.Object p0);

    @NonNull
    java.lang.Object peek(java.util.function.Consumer p0);

    @NonNull
    java.lang.Object reduce(java.util.function.BinaryOperator p0);

    @NonNull
    java.lang.Object skip(long p0);

    @NonNull
    java.lang.Object sorted();

    @NonNull
    java.lang.Object sorted(java.util.Comparator p0);

    @NonNull
    java.lang.Object takeWhile(java.util.function.Predicate p0);

    @NonNull
    java.lang.Object toArray();

    @NonNull
    java.lang.Object toArray(java.util.function.IntFunction p0);

    @NonNull
    java.lang.Object toList();
}
