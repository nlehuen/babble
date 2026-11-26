package org.babblelang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Immutable discrete range record backing {@code range of char} ({@code BIP031-D6}).
 */
public record CharRange(char startChar, char endChar, boolean inclusive, int step, boolean descending)
        implements Range<Character>, Iterable<Character> {

    public CharRange {
        Range.validateStep(step);
    }

    public static CharRange of(char start, char end, boolean inclusive) {
        return new CharRange(start, end, inclusive, 1, false);
    }

    public static CharRange of(char start, char end, boolean inclusive, int step) {
        return new CharRange(start, end, inclusive, step, false);
    }

    @Override
    public Character start() {
        return startChar;
    }

    @Override
    public Character end() {
        return endChar;
    }

    @Override
    public boolean isEmpty() {
        return inclusive ? startChar > endChar : startChar >= endChar;
    }

    public boolean containsChar(char value) {
        if (value < startChar) {
            return false;
        }
        if (inclusive ? value > endChar : value >= endChar) {
            return false;
        }
        if (step == 1) {
            return true;
        }
        return ((value - startChar) % step) == 0;
    }

    @Override
    public boolean contains(Object value) {
        return value instanceof Character c && containsChar(c);
    }

    public CharRange step(int k) {
        return new CharRange(startChar, endChar, inclusive, k, descending);
    }

    public CharRange reversed() {
        return new CharRange(startChar, endChar, inclusive, step, !descending);
    }

    @Override
    public Iterator<Character> iterator() {
        if (isEmpty()) {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Character next() {
                    throw new NoSuchElementException();
                }
            };
        }
        char last = Range.lastCharElement(startChar, endChar, inclusive, step);
        int first = descending ? last : startChar;
        int stop = descending ? startChar : last;
        int delta = descending ? -step : step;
        return new Iterator<>() {
            private int current = first;
            private boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public Character next() {
                if (!hasNext) {
                    throw new NoSuchElementException();
                }
                int value = current;
                if (value == stop) {
                    hasNext = false;
                } else {
                    current = value + delta;
                }
                return (char) value;
            }
        };
    }

    @Override
    public String toString() {
        String base = startChar + (inclusive ? ".." : "..<") + endChar;
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
