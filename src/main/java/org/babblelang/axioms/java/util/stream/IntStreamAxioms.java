package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.IntStream}.
 */
@Axioms(value = java.util.stream.IntStream.class,
        inherited = false)
interface IntStreamAxioms {
    @NonNull
    java.lang.Object asDoubleStream();

    @NonNull
    java.lang.Object asLongStream();

    @NonNull
    java.lang.Object average();

    @NonNull
    java.lang.Object boxed();

    @NonNull
    java.lang.Object builder();

    @NonNull
    java.lang.Object collect(java.util.function.Supplier p0, java.util.function.ObjIntConsumer p1, java.util.function.BiConsumer p2);

    @NonNull
    java.lang.Object concat(java.util.stream.IntStream p0, java.util.stream.IntStream p1);

    @NonNull
    java.lang.Object distinct();

    @NonNull
    java.lang.Object dropWhile(java.util.function.IntPredicate p0);

    @NonNull
    java.lang.Object empty();

    @NonNull
    java.lang.Object filter(java.util.function.IntPredicate p0);

    @NonNull
    java.lang.Object findAny();

    @NonNull
    java.lang.Object findFirst();

    @NonNull
    java.lang.Object flatMap(java.util.function.IntFunction p0);

    @NonNull
    java.lang.Object generate(java.util.function.IntSupplier p0);

    @NonNull
    java.lang.Object iterate(int p0, java.util.function.IntPredicate p1, java.util.function.IntUnaryOperator p2);

    @NonNull
    java.lang.Object iterate(int p0, java.util.function.IntUnaryOperator p1);

    @NonNull
    java.lang.Object limit(long p0);

    @NonNull
    java.lang.Object map(java.util.function.IntUnaryOperator p0);

    @NonNull
    java.lang.Object mapToDouble(java.util.function.IntToDoubleFunction p0);

    @NonNull
    java.lang.Object mapToLong(java.util.function.IntToLongFunction p0);

    @NonNull
    java.lang.Object mapToObj(java.util.function.IntFunction p0);

    @NonNull
    java.lang.Object max();

    @NonNull
    java.lang.Object min();

    @NonNull
    java.lang.Object of(int[] p0);

    @NonNull
    java.lang.Object of(int p0);

    @NonNull
    java.lang.Object peek(java.util.function.IntConsumer p0);

    @NonNull
    java.lang.Object range(int p0, int p1);

    @NonNull
    java.lang.Object rangeClosed(int p0, int p1);

    @NonNull
    java.lang.Object reduce(java.util.function.IntBinaryOperator p0);

    @NonNull
    java.lang.Object skip(long p0);

    @NonNull
    java.lang.Object sorted();

    @NonNull
    java.lang.Object summaryStatistics();

    @NonNull
    java.lang.Object takeWhile(java.util.function.IntPredicate p0);

    @NonNull
    java.lang.Object toArray();
}
