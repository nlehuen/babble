package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.Foldable;
import org.babblelang.effects.Idempotent;

import static org.babblelang.effects.Effect.*;

/**
 * The bytecode analyzer's seeds, the methods the optimizer may fold, and the idempotent ones.
 */
@Axioms(value = java.lang.Math.class,
        inherited = false)
interface MathAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object IEEEremainder(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object abs(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object abs(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object abs(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object abs(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object acos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object addExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object addExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object asin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object atan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object atan2(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object cbrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object ceil(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object copySign(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object copySign(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object cos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object cosh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object exp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object expm1(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object floor(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorDiv(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorDiv(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorDiv(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorMod(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorMod(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object floorMod(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object fma(double p0, double p1, double p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object fma(float p0, float p1, float p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object hypot(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object log(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object log10(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object log1p(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object max(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object max(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object max(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object max(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object min(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object min(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object min(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object min(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object multiplyExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object multiplyExact(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object multiplyExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object negateExact(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object negateExact(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object nextDown(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object nextDown(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object nextUp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object nextUp(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object pow(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    java.lang.Object rint(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object round(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object round(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object scalb(double p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object scalb(float p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object signum(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object signum(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object sin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object sinh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object sqrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object subtractExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object subtractExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object tan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object tanh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object toDegrees(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    java.lang.Object toIntExact(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    java.lang.Object toRadians(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object ulp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    java.lang.Object ulp(float p0);
}
