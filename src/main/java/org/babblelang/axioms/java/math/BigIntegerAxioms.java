package org.babblelang.axioms.java.math;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.math.BigInteger}.
 */
@Axioms(value = java.math.BigInteger.class,
        inherited = false)
interface BigIntegerAxioms {
    @NonNull
    java.lang.Object abs();

    @NonNull
    java.lang.Object add(java.math.BigInteger p0);

    @NonNull
    java.lang.Object and(java.math.BigInteger p0);

    @NonNull
    java.lang.Object divide(java.math.BigInteger p0);

    @NonNull
    java.lang.Object gcd(java.math.BigInteger p0);

    @NonNull
    java.lang.Object max(java.math.BigInteger p0);

    @NonNull
    java.lang.Object min(java.math.BigInteger p0);

    @NonNull
    java.lang.Object mod(java.math.BigInteger p0);

    @NonNull
    java.lang.Object multiply(java.math.BigInteger p0);

    @NonNull
    java.lang.Object negate();

    @NonNull
    java.lang.Object not();

    @NonNull
    java.lang.Object or(java.math.BigInteger p0);

    @NonNull
    java.lang.Object pow(int p0);

    @NonNull
    java.lang.Object remainder(java.math.BigInteger p0);

    @NonNull
    java.lang.Object shiftLeft(int p0);

    @NonNull
    java.lang.Object shiftRight(int p0);

    @NonNull
    java.lang.Object subtract(java.math.BigInteger p0);

    @NonNull
    java.lang.Object toString(int p0);

    @NonNull
    java.lang.Object valueOf(long p0);

    @NonNull
    java.lang.Object xor(java.math.BigInteger p0);
}
