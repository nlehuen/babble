package org.babblelang.runtime;

/**
 * Static factory methods for constructing {@link Range} instances, separated from the {@link Range}
 * interface so runtime type checks ({@code x instanceof Range}) do not pull all concrete range
 * implementations and iterators into tree-shaken AOT jars.
 */
public final class Ranges {
    private Ranges() {}

    public static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive) {
        return IntRange.ofByte(start, end, inclusive);
    }

    public static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive, int step) {
        return IntRange.ofByte(start, end, inclusive, step);
    }

    public static IntRange<Short> ofShort(short start, short end, boolean inclusive) {
        return IntRange.ofShort(start, end, inclusive);
    }

    public static IntRange<Short> ofShort(short start, short end, boolean inclusive, int step) {
        return IntRange.ofShort(start, end, inclusive, step);
    }

    public static IntRange<Integer> ofInt(int start, int end, boolean inclusive) {
        return IntRange.ofInt(start, end, inclusive);
    }

    public static IntRange<Integer> ofInt(int start, int end, boolean inclusive, int step) {
        return IntRange.ofInt(start, end, inclusive, step);
    }

    public static LongRange ofLong(long start, long end, boolean inclusive) {
        return LongRange.of(start, end, inclusive);
    }

    public static LongRange ofLong(long start, long end, boolean inclusive, long step) {
        return LongRange.of(start, end, inclusive, step);
    }

    public static CharRange ofChar(char start, char end, boolean inclusive) {
        return CharRange.of(start, end, inclusive);
    }

    public static CharRange ofChar(char start, char end, boolean inclusive, int step) {
        return CharRange.of(start, end, inclusive, step);
    }

    public static DoubleRange<Float> ofFloat(float start, float end, boolean inclusive) {
        return DoubleRange.ofFloat(start, end, inclusive);
    }

    public static DoubleRange<Double> ofDouble(double start, double end, boolean inclusive) {
        return DoubleRange.ofDouble(start, end, inclusive);
    }

    public static <T extends Comparable<? super T>> ComparableRange<T> ofComparable(
            T start, T end, boolean inclusive) {
        return new ComparableRange<>(start, end, inclusive);
    }

    /** Dynamic range creation (used by the interpreter and dynamic fallbacks). */
    @SuppressWarnings("unchecked")
    public static Range<?> of(
            Object start, Object end, boolean inclusive, String leftText, String rightText) {
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
            if ((na instanceof Byte || na instanceof Short)
                    && (nb instanceof Byte || nb instanceof Short)) {
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
            return new ComparableRange<>(
                    (Comparable<Object>) start, (Comparable<Object>) end, inclusive);
        }
        throw new BabbleException("Not comparable : " + leftText + " and " + rightText);
    }

    private static boolean isSubInt(Number n) {
        return n instanceof Byte || n instanceof Short || n instanceof Integer;
    }
}
