package org.babblelang.runtime;

/**
 * First-class immutable range ({@code range of T}, {@code BIP031-D2}/{@code D6}).
 *
 * <p>Covers all ordered types for containment and interval queries ({@link #start()}, {@link
 * #end()}, {@link #inclusive()}, {@link #isEmpty()}, {@link #contains(Object)}); discrete scalar
 * ranges additionally implement {@link Iterable} with positive {@code step(k)} and {@code
 * reversed()} progressions. Static factory methods live on {@link Ranges} so runtime type checks do
 * not pull concrete range implementations into tree-shaken AOT jars.
 */
public interface Range<T> {

    /** Lower bound as written (unchanged by {@code reversed()}). */
    T start();

    /** Upper bound as written. */
    T end();

    /** {@code true} for inclusive {@code lo..hi}, {@code false} for half-open {@code lo..<hi}. */
    boolean inclusive();

    /** {@code true} when no element lies in this range. */
    boolean isEmpty();

    /**
     * Whether {@code value} is an element of this range. Returns {@code false} for values of
     * another type (keeping covariance sound).
     */
    boolean contains(Object value);

    // ---------------------------------------------------------------- progression helpers

    /** Validates that an int progression step is strictly positive. */
    static int validateStep(int step) {
        if (step <= 0) {
            throw new BabbleException("Range step must be positive : " + step);
        }
        return step;
    }

    /** Validates that a long progression step is strictly positive. */
    static long validateStep(long step) {
        if (step <= 0L) {
            throw new BabbleException("Range step must be positive : " + step);
        }
        return step;
    }

    /**
     * Overflow-safe last included element of a non-empty {@code int} progression ({@code
     * byte}/{@code short}/{@code int}). Precondition: {@code !isEmpty()} and {@code step > 0}.
     */
    static int lastIntElement(int start, int end, boolean inclusive, int step) {
        long effEnd = inclusive ? (long) end : (long) end - 1L;
        if (step == 1) {
            return (int) effEnd;
        }
        long diff = effEnd - (long) start;
        return (int) (effEnd - (diff % (long) step));
    }

    /**
     * Overflow-safe last included element of a non-empty {@code long} progression using unsigned
     * 64-bit remainder. Precondition: {@code !isEmpty()} and {@code step > 0}.
     */
    static long lastLongElement(long start, long end, boolean inclusive, long step) {
        long effEnd = inclusive ? end : end - 1L;
        if (step == 1L) {
            return effEnd;
        }
        long udiff = effEnd - start;
        return effEnd - Long.remainderUnsigned(udiff, step);
    }

    /**
     * Overflow-safe last included element of a non-empty {@code char} progression. Precondition:
     * {@code !isEmpty()} and {@code step > 0}.
     */
    static char lastCharElement(char start, char end, boolean inclusive, int step) {
        int effEnd = inclusive ? (int) end : (int) end - 1;
        if (step == 1) {
            return (char) effEnd;
        }
        int diff = effEnd - (int) start;
        return (char) (effEnd - (diff % step));
    }
}
