package org.babblelang.axioms.java.lang;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;

import static org.babblelang.effects.Effect.*;

/**
 * What the bytecode analyzer assumes about {@code java.lang.StrictMath} methods it reaches (BIP019-P2).
 */
@Axioms(value = java.lang.StrictMath.class, inherited = false)
interface StrictMathAxioms {
    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double IEEEremainder(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double abs(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float abs(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int abs(int p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long abs(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double acos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int addExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long addExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double asin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double atan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double atan2(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double cbrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double ceil(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double copySign(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float copySign(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double cos(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double cosh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double exp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double expm1(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
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

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double hypot(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double log(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double log10(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double log1p(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double max(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float max(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int max(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long max(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double min(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float min(float p0, float p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int min(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
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

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double pow(double p0, double p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double rint(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    long round(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    int round(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double scalb(double p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float scalb(float p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double signum(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float signum(float p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double sin(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double sinh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double sqrt(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int subtractExact(int p0, int p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    long subtractExact(long p0, long p1);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double tan(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double tanh(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double toDegrees(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC})
    int toIntExact(long p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double toRadians(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    double ulp(double p0);

    @BytecodeSeed({PURE, HERMETIC, DETERMINISTIC, NOTHROW})
    float ulp(float p0);
}
