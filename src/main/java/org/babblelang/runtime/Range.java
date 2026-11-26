package org.babblelang.runtime;

/**
 * First-class immutable range ({@code range of T}, {@code BIP031-D2}/{@code D6}).
 * <p>
 * Covers all ordered types for containment and interval queries ({@link #start()}, {@link #end()},
 * {@link #inclusive()}, {@link #isEmpty()}, {@link #contains(Object)}); discrete scalar ranges
 * ({@link IntRange}, {@link LongRange}, {@link CharRange}) additionally implement {@link Iterable}
 * with positive {@code step(k)} and {@code reversed()} progressions.
 */
public sealed interface Range<T>
        permits IntRange, LongRange, CharRange, DoubleRange, ComparableRange {

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

    // ---------------------------------------------------------------- factories

    static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive) {
        return IntRange.ofByte(start, end, inclusive);
    }

    static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive, int step) {
        return IntRange.ofByte(start, end, inclusive, step);
    }

    static IntRange<Short> ofShort(short start, short end, boolean inclusive) {
        return IntRange.ofShort(start, end, inclusive);
    }

    static IntRange<Short> ofShort(short start, short end, boolean inclusive, int step) {
        return IntRange.ofShort(start, end, inclusive, step);
    }

    static IntRange<Integer> ofInt(int start, int end, boolean inclusive) {
        return IntRange.ofInt(start, end, inclusive);
    }

    static IntRange<Integer> ofInt(int start, int end, boolean inclusive, int step) {
        return IntRange.ofInt(start, end, inclusive, step);
    }

    static LongRange ofLong(long start, long end, boolean inclusive) {
        return LongRange.of(start, end, inclusive);
    }

    static LongRange ofLong(long start, long end, boolean inclusive, long step) {
        return LongRange.of(start, end, inclusive, step);
    }

    static CharRange ofChar(char start, char end, boolean inclusive) {
        return CharRange.of(start, end, inclusive);
    }

    static CharRange ofChar(char start, char end, boolean inclusive, int step) {
        return CharRange.of(start, end, inclusive, step);
    }

    static DoubleRange<Float> ofFloat(float start, float end, boolean inclusive) {
        return DoubleRange.ofFloat(start, end, inclusive);
    }

    static DoubleRange<Double> ofDouble(double start, double end, boolean inclusive) {
        return DoubleRange.ofDouble(start, end, inclusive);
    }

    static <T extends Comparable<? super T>> ComparableRange<T> ofComparable(T start, T end, boolean inclusive) {
        return new ComparableRange<>(start, end, inclusive);
    }

    /**
     * Dynamic range creation (used by the interpreter and dynamic fallbacks).
     */
    @SuppressWarnings("unchecked")
    static Range<?> of(Object start, Object end, boolean inclusive, String leftText, String rightText) {
        if (start == null) {
            throw new BabbleException("Range bound cannot be null : " + leftText);
        }
        if (end == null) {
            throw new BabbleException("Range bound cannot be null : " + rightText);
        }
        if (start instanceof Number na && end instanceof Number nb) {
            if (na instanceof Byte && nb instanceof Byte) {
                return ofByte(na.byteValue(), nb.byteValue(), inclusive);
            }
            if ((na instanceof Byte || na instanceof Short) && (nb instanceof Byte || nb instanceof Short)) {
                return ofShort(na.shortValue(), nb.shortValue(), inclusive);
            }
            if (isSubInt(na) && isSubInt(nb)) {
                return ofInt(na.intValue(), nb.intValue(), inclusive);
            }
            if ((isSubInt(na) || na instanceof Long) && (isSubInt(nb) || nb instanceof Long)) {
                return ofLong(na.longValue(), nb.longValue(), inclusive);
            }
            if ((isSubInt(na) || na instanceof Long || na instanceof Float)
                    && (isSubInt(nb) || nb instanceof Long || nb instanceof Float)) {
                return ofFloat(na.floatValue(), nb.floatValue(), inclusive);
            }
            return ofDouble(na.doubleValue(), nb.doubleValue(), inclusive);
        }
        if (start instanceof Character ca && end instanceof Character cb) {
            return ofChar(ca, cb, inclusive);
        }
        if (start instanceof Comparable<?> && start.getClass().isInstance(end)) {
            return new ComparableRange<>((Comparable<Object>) start, (Comparable<Object>) end, inclusive);
        }
        throw new BabbleException("Not comparable : " + leftText + " and " + rightText);
    }

    private static boolean isSubInt(Number n) {
        return n instanceof Byte || n instanceof Short || n instanceof Integer;
    }

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
     * Overflow-safe last included element of a non-empty {@code int} progression
     * ({@code byte}/{@code short}/{@code int}). Precondition: {@code !isEmpty()} and {@code step > 0}.
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
     * Overflow-safe last included element of a non-empty {@code long} progression using
     * unsigned 64-bit remainder. Precondition: {@code !isEmpty()} and {@code step > 0}.
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
     * Overflow-safe last included element of a non-empty {@code char} progression.
     * Precondition: {@code !isEmpty()} and {@code step > 0}.
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
