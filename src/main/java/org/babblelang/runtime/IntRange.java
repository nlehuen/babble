package org.babblelang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Immutable discrete range record backing {@code range of byte}, {@code range of short}, and {@code
 * range of int} ({@code BIP031-D6}).
 */
public record IntRange<T extends Number>(
        int startInt, int endInt, boolean inclusive, int step, boolean descending, byte elemKind)
        implements Range<T>, Iterable<T> {

    public static final byte KIND_BYTE = 0;
    public static final byte KIND_SHORT = 1;
    public static final byte KIND_INT = 2;

    public IntRange {
        Range.validateStep(step);
    }

    public static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive) {
        return new IntRange<>(start, end, inclusive, 1, false, KIND_BYTE);
    }

    public static IntRange<Byte> ofByte(byte start, byte end, boolean inclusive, int step) {
        return new IntRange<>(start, end, inclusive, step, false, KIND_BYTE);
    }

    public static IntRange<Short> ofShort(short start, short end, boolean inclusive) {
        return new IntRange<>(start, end, inclusive, 1, false, KIND_SHORT);
    }

    public static IntRange<Short> ofShort(short start, short end, boolean inclusive, int step) {
        return new IntRange<>(start, end, inclusive, step, false, KIND_SHORT);
    }

    public static IntRange<Integer> ofInt(int start, int end, boolean inclusive) {
        return new IntRange<>(start, end, inclusive, 1, false, KIND_INT);
    }

    public static IntRange<Integer> ofInt(int start, int end, boolean inclusive, int step) {
        return new IntRange<>(start, end, inclusive, step, false, KIND_INT);
    }

    @SuppressWarnings("unchecked")
    private T box(int v) {
        return switch (elemKind) {
            case KIND_BYTE -> (T) Byte.valueOf((byte) v);
            case KIND_SHORT -> (T) Short.valueOf((short) v);
            default -> (T) Integer.valueOf(v);
        };
    }

    @Override
    public T start() {
        return box(startInt);
    }

    @Override
    public T end() {
        return box(endInt);
    }

    @Override
    public boolean isEmpty() {
        return inclusive ? startInt > endInt : startInt >= endInt;
    }

    public boolean containsInt(int value) {
        if (value < startInt) {
            return false;
        }
        if (inclusive ? value > endInt : value >= endInt) {
            return false;
        }
        if (step == 1) {
            return true;
        }
        return (((long) value - (long) startInt) % (long) step) == 0L;
    }

    @Override
    public boolean contains(Object value) {
        return switch (elemKind) {
            case KIND_BYTE -> value instanceof Byte b && containsInt(b);
            case KIND_SHORT -> value instanceof Short s && containsInt(s);
            default -> value instanceof Integer i && containsInt(i);
        };
    }

    public IntRange<T> step(int k) {
        return new IntRange<>(startInt, endInt, inclusive, k, descending, elemKind);
    }

    public IntRange<T> reversed() {
        return new IntRange<>(startInt, endInt, inclusive, step, !descending, elemKind);
    }

    @Override
    public Iterator<T> iterator() {
        if (isEmpty()) {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public T next() {
                    throw new NoSuchElementException();
                }
            };
        }
        int last = Range.lastIntElement(startInt, endInt, inclusive, step);
        int first = descending ? last : startInt;
        int stop = descending ? startInt : last;
        int delta = descending ? -step : step;
        return new Iterator<>() {
            private int current = first;
            private boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext) {
                    throw new NoSuchElementException();
                }
                int value = current;
                if (value == stop) {
                    hasNext = false;
                } else {
                    current = value + delta;
                }
                return box(value);
            }
        };
    }

    @Override
    public String toString() {
        String base = startInt + (inclusive ? ".." : "..<") + endInt;
        if (step == 1 && !descending) {
            return base;
        }
        StringBuilder sb = new StringBuilder("(").append(base).append(')');
        if (step != 1) {
            sb.append(".step(").append(step).append(')');
        }
        if (descending) {
            sb.append(".reversed()");
        }
        return sb.toString();
    }
}
