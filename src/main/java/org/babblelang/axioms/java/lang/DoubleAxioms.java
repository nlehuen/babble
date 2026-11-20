package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.NonNull;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.lang.Double}.
 */
@Axioms(value = java.lang.Double.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface DoubleAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compare(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object doubleToLongBits(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object doubleToRawLongBits(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isFinite(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isInfinite();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isInfinite(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isNaN();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object isNaN(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object longBitsToDouble(long p0);

    @NonNull
    java.lang.Object toHexString(double p0);

    @NonNull
    java.lang.Object toString(double p0);

    @NonNull
    java.lang.Object valueOf(java.lang.String p0);

    @NonNull
    java.lang.Object valueOf(double p0);
}
