package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.lang.Integer}.
 */
@Axioms(value = java.lang.Integer.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface IntegerAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object bitCount(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compare(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compareUnsigned(int p0, int p1);

    @NonNull
    java.lang.Object decode(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object doubleValue();

    @NonNull
    java.lang.Object getInteger(java.lang.String p0, java.lang.Integer p1);

    @NonNull
    java.lang.Object getInteger(java.lang.String p0, int p1);

    @Nullable
    java.lang.Object getInteger(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object highestOneBit(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object intValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object longValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lowestOneBit(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object max(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object min(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object numberOfLeadingZeros(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object numberOfTrailingZeros(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object reverse(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object reverseBytes(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object rotateLeft(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object rotateRight(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object signum(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object sum(int p0, int p1);

    @NonNull
    java.lang.Object toBinaryString(int p0);

    @NonNull
    java.lang.Object toHexString(int p0);

    @NonNull
    java.lang.Object toOctalString(int p0);

    @NonNull
    java.lang.Object toString(int p0, int p1);

    @NonNull
    java.lang.Object toString(int p0);

    @NonNull
    java.lang.Object toUnsignedString(int p0, int p1);

    @NonNull
    java.lang.Object toUnsignedString(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(java.lang.String p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(int p0);
}
