package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.LongStream}.
 */
@Axioms(value = java.util.stream.LongStream.class,
        inherited = false)
interface LongStreamAxioms {
    @NonNull
    java.lang.Object asDoubleStream();

    @NonNull
    java.lang.Object average();

    @NonNull
    java.lang.Object boxed();

    @NonNull
    java.lang.Object builder();

    @NonNull
    java.lang.Object collect(java.util.function.Supplier p0, java.util.function.ObjLongConsumer p1, java.util.function.BiConsumer p2);

    @NonNull
    java.lang.Object concat(java.util.stream.LongStream p0, java.util.stream.LongStream p1);

    @NonNull
    java.lang.Object distinct();

    @NonNull
    java.lang.Object dropWhile(java.util.function.LongPredicate p0);

    @NonNull
    java.lang.Object empty();

    @NonNull
    java.lang.Object filter(java.util.function.LongPredicate p0);

    @NonNull
    java.lang.Object findAny();

    @NonNull
    java.lang.Object findFirst();

    @NonNull
    java.lang.Object flatMap(java.util.function.LongFunction p0);

    @NonNull
    java.lang.Object generate(java.util.function.LongSupplier p0);

    @NonNull
    java.lang.Object iterate(long p0, java.util.function.LongPredicate p1, java.util.function.LongUnaryOperator p2);

    @NonNull
    java.lang.Object iterate(long p0, java.util.function.LongUnaryOperator p1);

    @NonNull
    java.lang.Object limit(long p0);

    @NonNull
    java.lang.Object map(java.util.function.LongUnaryOperator p0);

    @NonNull
    java.lang.Object mapToDouble(java.util.function.LongToDoubleFunction p0);

    @NonNull
    java.lang.Object mapToInt(java.util.function.LongToIntFunction p0);

    @NonNull
    java.lang.Object mapToObj(java.util.function.LongFunction p0);

    @NonNull
    java.lang.Object max();

    @NonNull
    java.lang.Object min();

    @NonNull
    java.lang.Object of(long[] p0);

    @NonNull
    java.lang.Object of(long p0);

    @NonNull
    java.lang.Object peek(java.util.function.LongConsumer p0);

    @NonNull
    java.lang.Object range(long p0, long p1);

    @NonNull
    java.lang.Object rangeClosed(long p0, long p1);

    @NonNull
    java.lang.Object reduce(java.util.function.LongBinaryOperator p0);

    @NonNull
    java.lang.Object skip(long p0);

    @NonNull
    java.lang.Object sorted();

    @NonNull
    java.lang.Object summaryStatistics();

    @NonNull
    java.lang.Object takeWhile(java.util.function.LongPredicate p0);

    @NonNull
    java.lang.Object toArray();
}
