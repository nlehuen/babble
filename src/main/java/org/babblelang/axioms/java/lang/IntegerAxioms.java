package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.Integer} methods it reaches (BIP019-P2).
 */
@Axioms(value = java.lang.Integer.class, inherited = false)
interface IntegerAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int bitCount(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compare(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compareUnsigned(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double doubleValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int highestOneBit(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int intValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long longValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int lowestOneBit(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int max(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int min(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int numberOfLeadingZeros(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int numberOfTrailingZeros(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int reverse(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int reverseBytes(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int rotateLeft(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int rotateRight(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int signum(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int sum(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Integer valueOf(java.lang.String p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Integer valueOf(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Integer valueOf(int p0);
}
