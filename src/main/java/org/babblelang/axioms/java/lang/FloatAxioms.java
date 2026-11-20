package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.Float} methods it reaches (BIP019-P2).
 */
@Axioms(value = java.lang.Float.class, inherited = false)
interface FloatAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int compare(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int floatToIntBits(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int floatToRawIntBits(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float intBitsToFloat(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isFinite(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isInfinite();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isInfinite(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isNaN();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    boolean isNaN(float p0);
}
