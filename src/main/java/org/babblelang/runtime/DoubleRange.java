package org.babblelang.runtime;

/**
 * Immutable continuous range record backing {@code range of float} and {@code range of double}
 * ({@code BIP031-D6}). Not iterable.
 */
public record DoubleRange<T extends Number>(
        double startDouble, double endDouble, boolean inclusive, boolean isFloat)
        implements Range<T> {

    public static DoubleRange<Float> ofFloat(float start, float end, boolean inclusive) {
        return new DoubleRange<>(start, end, inclusive, true);
    }

    public static DoubleRange<Double> ofDouble(double start, double end, boolean inclusive) {
        return new DoubleRange<>(start, end, inclusive, false);
    }

    @SuppressWarnings("unchecked")
    private T box(double v) {
        return isFloat ? (T) Float.valueOf((float) v) : (T) Double.valueOf(v);
    }

    @Override
    public T start() {
        return box(startDouble);
    }

    @Override
    public T end() {
        return box(endDouble);
    }

    @Override
    public boolean isEmpty() {
        return inclusive ? !(startDouble <= endDouble) : !(startDouble < endDouble);
    }

    public boolean containsDouble(double value) {
        return inclusive
                ? (value >= startDouble && value <= endDouble)
                : (value >= startDouble && value < endDouble);
    }

    @Override
    public boolean contains(Object value) {
        if (isFloat) {
            return value instanceof Float f && containsDouble(f);
        }
        return value instanceof Double d && containsDouble(d);
    }

    @Override
    public String toString() {
        String s = isFloat ? Float.toString((float) startDouble) : Double.toString(startDouble);
        String e = isFloat ? Float.toString((float) endDouble) : Double.toString(endDouble);
        return s + (inclusive ? ".." : "..<") + e;
    }
}
