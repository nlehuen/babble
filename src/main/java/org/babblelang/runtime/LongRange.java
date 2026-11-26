package org.babblelang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Immutable discrete range record backing {@code range of long} ({@code BIP031-D6}). */
public record LongRange(
        long startLong, long endLong, boolean inclusive, long step, boolean descending)
        implements Range<Long>, Iterable<Long> {

    public LongRange {
        Range.validateStep(step);
    }

    public static LongRange of(long start, long end, boolean inclusive) {
        return new LongRange(start, end, inclusive, 1L, false);
    }

    public static LongRange of(long start, long end, boolean inclusive, long step) {
        return new LongRange(start, end, inclusive, step, false);
    }

    @Override
    public Long start() {
        return startLong;
    }

    @Override
    public Long end() {
        return endLong;
    }

    @Override
    public boolean isEmpty() {
        return inclusive ? startLong > endLong : startLong >= endLong;
    }

    public boolean containsLong(long value) {
        if (value < startLong) {
            return false;
        }
        if (inclusive ? value > endLong : value >= endLong) {
            return false;
        }
        if (step == 1L) {
            return true;
        }
        return Long.remainderUnsigned(value - startLong, step) == 0L;
    }

    @Override
    public boolean contains(Object value) {
        return value instanceof Long l && containsLong(l);
    }

    public LongRange step(long k) {
        return new LongRange(startLong, endLong, inclusive, k, descending);
    }

    public LongRange reversed() {
        return new LongRange(startLong, endLong, inclusive, step, !descending);
    }

    @Override
    public Iterator<Long> iterator() {
        if (isEmpty()) {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Long next() {
                    throw new NoSuchElementException();
                }
            };
        }
        long last = Range.lastLongElement(startLong, endLong, inclusive, step);
        long first = descending ? last : startLong;
        long stop = descending ? startLong : last;
        long delta = descending ? -step : step;
        return new Iterator<>() {
            private long current = first;
            private boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public Long next() {
                if (!hasNext) {
                    throw new NoSuchElementException();
                }
                long value = current;
                if (value == stop) {
                    hasNext = false;
                } else {
                    current = value + delta;
                }
                return value;
            }
        };
    }

    @Override
    public String toString() {
        String base = startLong + (inclusive ? ".." : "..<") + endLong;
        if (step == 1L && !descending) {
            return base;
        }
        StringBuilder sb = new StringBuilder("(").append(base).append(')');
        if (step != 1L) {
            sb.append(".step(").append(step).append(')');
        }
        if (descending) {
            sb.append(".reversed()");
        }
        return sb.toString();
    }
}
