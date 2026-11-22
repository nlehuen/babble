package org.babblelang.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Fixed-size, read-only {@link Iterable} view over an array of arguments ({@code BIP021-D6}).
 * Backed directly by an {@code Object[]} or a primitive array ({@code int[]}, {@code double[]}, …)
 * without allocating a {@code List}.
 */
public abstract class ArgumentsView implements Iterable<Object> {
    public static final ArgumentsView EMPTY = new ObjectView(Arguments.NONE);

    /**
     * Sentinel wrapping an already-packed rest {@link Iterable} when passed through the generic
     * {@link Callable#call} convention so a variadic callee can distinguish a packed rest from
     * raw positional elements supplied via a fixed-arity function type ({@code BIP021-D5}).
     */
    public record PackedRest(Iterable<?> iterable) {
    }

    public abstract int size();

    public abstract Object get(int index);

    @Override
    public Iterator<Object> iterator() {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < size();
            }

            @Override
            public Object next() {
                if (index >= size()) {
                    throw new NoSuchElementException();
                }
                return get(index++);
            }
        };
    }

    public static ArgumentsView of(Object... elements) {
        return elements.length == 0 ? EMPTY : new ObjectView(elements);
    }

    public static ArgumentsView single(Object element) {
        return new ObjectView(new Object[]{element});
    }

    public static ArgumentsView ofBytes(byte... elements) {
        return elements.length == 0 ? EMPTY : new ByteView(elements);
    }

    public static ArgumentsView ofShorts(short... elements) {
        return elements.length == 0 ? EMPTY : new ShortView(elements);
    }

    public static ArgumentsView ofInts(int... elements) {
        return elements.length == 0 ? EMPTY : new IntView(elements);
    }

    public static ArgumentsView ofLongs(long... elements) {
        return elements.length == 0 ? EMPTY : new LongView(elements);
    }

    public static ArgumentsView ofFloats(float... elements) {
        return elements.length == 0 ? EMPTY : new FloatView(elements);
    }

    public static ArgumentsView ofDoubles(double... elements) {
        return elements.length == 0 ? EMPTY : new DoubleView(elements);
    }

    public static ArgumentsView ofBooleans(boolean... elements) {
        return elements.length == 0 ? EMPTY : new BooleanView(elements);
    }

    public static ArgumentsView ofChars(char... elements) {
        return elements.length == 0 ? EMPTY : new CharView(elements);
    }

    /** Returns a zero-copy {@link ArgumentsView} over any JVM array (primitive or reference). */
    public static ArgumentsView fromArray(Object array) {
        return switch (array) {
            case Object[] a -> of(a);
            case int[] a -> ofInts(a);
            case double[] a -> ofDoubles(a);
            case long[] a -> ofLongs(a);
            case boolean[] a -> ofBooleans(a);
            case byte[] a -> ofBytes(a);
            case short[] a -> ofShorts(a);
            case float[] a -> ofFloats(a);
            case char[] a -> ofChars(a);
            default -> throw new BabbleException("Not a sequence : " + Types.describe(array));
        };
    }

    /** Chains several {@link Iterable}s without copying, repeatable across multiple iterations. */
    public static Iterable<Object> concat(Iterable<?>... parts) {
        return new ChainedIterable(parts);
    }

    private static final class ObjectView extends ArgumentsView {
        private final Object[] data;

        ObjectView(Object[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class ByteView extends ArgumentsView {
        private final byte[] data;

        ByteView(byte[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class ShortView extends ArgumentsView {
        private final short[] data;

        ShortView(short[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class IntView extends ArgumentsView {
        private final int[] data;

        IntView(int[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class LongView extends ArgumentsView {
        private final long[] data;

        LongView(long[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class FloatView extends ArgumentsView {
        private final float[] data;

        FloatView(float[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class DoubleView extends ArgumentsView {
        private final double[] data;

        DoubleView(double[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class BooleanView extends ArgumentsView {
        private final boolean[] data;

        BooleanView(boolean[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class CharView extends ArgumentsView {
        private final char[] data;

        CharView(char[] data) {
            this.data = data;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public Object get(int index) {
            return data[index];
        }
    }

    private static final class ChainedIterable implements Iterable<Object> {
        private final Iterable<?>[] parts;

        ChainedIterable(Iterable<?>[] parts) {
            this.parts = parts;
        }

        @Override
        public Iterator<Object> iterator() {
            return new Iterator<>() {
                private int partIndex;
                private Iterator<?> current;

                private void advance() {
                    while ((current == null || !current.hasNext()) && partIndex < parts.length) {
                        current = parts[partIndex++].iterator();
                    }
                }

                @Override
                public boolean hasNext() {
                    advance();
                    return current != null && current.hasNext();
                }

                @Override
                public Object next() {
                    advance();
                    if (current == null || !current.hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return current.next();
                }
            };
        }
    }
}
