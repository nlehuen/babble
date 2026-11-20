package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.Double} methods it reaches (BIP019-P2).
 */
@Axioms(value = java.lang.Double.class, inherited = false)
interface DoubleAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compare(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long doubleToLongBits(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long doubleToRawLongBits(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isFinite(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isInfinite();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isInfinite(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isNaN();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isNaN(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double longBitsToDouble(long p0);
}
