package org.babblelang.runtime;

import java.lang.reflect.Modifier;

/**
 * Immutable range record backing {@code range of string} and {@code range of T} for any Java {@link
 * Comparable} type ({@code BIP031-D6}). Not iterable.
 */
public record ComparableRange<T extends Comparable<? super T>>(T start, T end, boolean inclusive)
        implements Range<T> {

    public ComparableRange {
        if (start == null) {
            throw new BabbleException("Range bound cannot be null : start");
        }
        if (end == null) {
            throw new BabbleException("Range bound cannot be null : end");
        }
    }

    @Override
    public boolean isEmpty() {
        int cmp = start.compareTo(end);
        return inclusive ? cmp > 0 : cmp >= 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object value) {
        if (value == null) {
            return false;
        }
        if (Modifier.isFinal(start.getClass().getModifiers())
                && !start.getClass().isInstance(value)) {
            return false;
        }
        try {
            Comparable<Object> s = (Comparable<Object>) start;
            if (s.compareTo(value) > 0) {
                return false;
            }
            Comparable<Object> e = (Comparable<Object>) end;
            int cmp = e.compareTo(value);
            return inclusive ? cmp >= 0 : cmp > 0;
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return start + (inclusive ? ".." : "..<") + end;
    }
}
