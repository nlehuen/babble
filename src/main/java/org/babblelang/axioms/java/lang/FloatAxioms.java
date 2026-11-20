package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.NonNull;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.lang.Float}.
 */
@Axioms(value = java.lang.Float.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface FloatAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compare(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object floatToIntBits(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object floatToRawIntBits(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object intBitsToFloat(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isFinite(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isInfinite();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isInfinite(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isNaN();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isNaN(float p0);

    @NonNull
    java.lang.Object toHexString(float p0);

    @NonNull
    java.lang.Object toString(float p0);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0);

    @NonNull
    java.lang.Object valueOf(float p0);
}
