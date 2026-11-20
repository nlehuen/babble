package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.Foldable;
import org.babblelang.effects.Idempotent;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.Math} methods it reaches (BIP019-P2) ;
 * and which of them the optimizer may call at compile time ({@link Foldable}), or treat as
 * idempotent.
 */
@Axioms(value = java.lang.Math.class, inherited = false)
interface MathAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double IEEEremainder(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    double abs(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    float abs(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    int abs(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    long abs(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double acos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int addExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long addExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double asin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double atan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double atan2(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double cbrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    double ceil(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double copySign(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float copySign(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double cos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double cosh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double exp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double expm1(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    double floor(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int floorDiv(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long floorDiv(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long floorDiv(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int floorMod(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int floorMod(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long floorMod(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double fma(double p0, double p1, double p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float fma(float p0, float p1, float p2);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double hypot(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double log(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double log10(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double log1p(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double max(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    float max(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    int max(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    long max(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double min(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    float min(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    int min(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    long min(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int multiplyExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long multiplyExact(long p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long multiplyExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int negateExact(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long negateExact(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double nextDown(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float nextDown(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double nextUp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float nextUp(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double pow(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable @Idempotent
    double rint(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    long round(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    int round(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double scalb(double p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float scalb(float p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double signum(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    float signum(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double sin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double sinh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double sqrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int subtractExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long subtractExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double tan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double tanh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double toDegrees(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int toIntExact(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW}) @Foldable
    double toRadians(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double ulp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float ulp(float p0);
}
