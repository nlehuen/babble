package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.Long} methods it reaches (BIP019-P2).
 */
@Axioms(value = java.lang.Long.class, inherited = false)
interface LongAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int bitCount(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compare(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compareUnsigned(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double doubleValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long highestOneBit(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int intValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long longValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long lowestOneBit(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long max(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long min(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int numberOfLeadingZeros(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int numberOfTrailingZeros(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long reverse(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long reverseBytes(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long rotateLeft(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long rotateRight(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int signum(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long sum(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Long valueOf(java.lang.String p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Long valueOf(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Long valueOf(long p0);
}
