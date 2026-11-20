package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.NonNull;
import org.babblelang.axioms.Nullable;

import static org.babblelang.effects.Effect.*;

/**
 * What the analysis assumes about {@code java.lang.Long}.
 */
@Axioms(value = java.lang.Long.class,
        inherited = false,
        nullMarked = Axioms.NullMarked.TARGET)
interface LongAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object bitCount(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compare(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object compareUnsigned(long p0, long p1);

    @NonNull
    java.lang.Object decode(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object doubleValue();

    @NonNull
    java.lang.Object getLong(java.lang.String p0, java.lang.Long p1);

    @NonNull
    java.lang.Object getLong(java.lang.String p0, long p1);

    @Nullable
    java.lang.Object getLong(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object highestOneBit(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object intValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object longValue();

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object lowestOneBit(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object max(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object min(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object numberOfLeadingZeros(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object numberOfTrailingZeros(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object reverse(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object reverseBytes(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object rotateLeft(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object rotateRight(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object signum(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object sum(long p0, long p1);

    @NonNull
    java.lang.Object toBinaryString(long p0);

    @NonNull
    java.lang.Object toHexString(long p0);

    @NonNull
    java.lang.Object toOctalString(long p0);

    @NonNull
    java.lang.Object toString(long p0, int p1);

    @NonNull
    java.lang.Object toString(long p0);

    @NonNull
    java.lang.Object toUnsignedString(long p0, int p1);

    @NonNull
    java.lang.Object toUnsignedString(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(java.lang.String p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(java.lang.String p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @NonNull
    java.lang.Object valueOf(long p0);
}
