package org.babblelang.axioms.java.util.stream;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.util.stream.DoubleStream}.
 */
@Axioms(value = java.util.stream.DoubleStream.class,
        inherited = false)
interface DoubleStreamAxioms {
    @NonNull
    java.lang.Object average();

    @NonNull
    java.lang.Object boxed();

    @NonNull
    java.lang.Object builder();

    @NonNull
    java.lang.Object collect(java.util.function.Supplier p0, java.util.function.ObjDoubleConsumer p1, java.util.function.BiConsumer p2);

    @NonNull
    java.lang.Object concat(java.util.stream.DoubleStream p0, java.util.stream.DoubleStream p1);

    @NonNull
    java.lang.Object distinct();

    @NonNull
    java.lang.Object dropWhile(java.util.function.DoublePredicate p0);

    @NonNull
    java.lang.Object empty();

    @NonNull
    java.lang.Object filter(java.util.function.DoublePredicate p0);

    @NonNull
    java.lang.Object findAny();

    @NonNull
    java.lang.Object findFirst();

    @NonNull
    java.lang.Object flatMap(java.util.function.DoubleFunction p0);

    @NonNull
    java.lang.Object generate(java.util.function.DoubleSupplier p0);

    @NonNull
    java.lang.Object iterate(double p0, java.util.function.DoublePredicate p1, java.util.function.DoubleUnaryOperator p2);

    @NonNull
    java.lang.Object iterate(double p0, java.util.function.DoubleUnaryOperator p1);

    @NonNull
    java.lang.Object limit(long p0);

    @NonNull
    java.lang.Object map(java.util.function.DoubleUnaryOperator p0);

    @NonNull
    java.lang.Object mapToInt(java.util.function.DoubleToIntFunction p0);

    @NonNull
    java.lang.Object mapToLong(java.util.function.DoubleToLongFunction p0);

    @NonNull
    java.lang.Object mapToObj(java.util.function.DoubleFunction p0);

    @NonNull
    java.lang.Object max();

    @NonNull
    java.lang.Object min();

    @NonNull
    java.lang.Object of(double[] p0);

    @NonNull
    java.lang.Object of(double p0);

    @NonNull
    java.lang.Object peek(java.util.function.DoubleConsumer p0);

    @NonNull
    java.lang.Object reduce(java.util.function.DoubleBinaryOperator p0);

    @NonNull
    java.lang.Object skip(long p0);

    @NonNull
    java.lang.Object sorted();

    @NonNull
    java.lang.Object summaryStatistics();

    @NonNull
    java.lang.Object takeWhile(java.util.function.DoublePredicate p0);

    @NonNull
    java.lang.Object toArray();
}
