package org.babblelang.runtime;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;

/**
 * Runtime adaptation helpers for spread arguments ({@code ...e}) and variadic rest packing
 * ({@code BIP021-D6}, {@code BIP021-D7}). Shared by the interpreter and compiled bytecode.
 */
public final class Spreads {
    private Spreads() {
    }

    /** Adapts an {@link Iterable} or JVM array to an {@link Iterable} without copying. */
    public static Iterable<?> asIterable(Object source) {
        Object unwrapped = Values.unwrap(source);
        if (unwrapped instanceof Iterable<?> it) {
            return it;
        }
        return ArgumentsView.fromArray(unwrapped);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a {@link List}. Returns a {@link List} as-is,
     * views a JVM array without copying when {@code borrowed} is true, and copies into a fresh
     * {@link ArrayList} otherwise.
     */
    public static List<?> asList(Object source, boolean borrowed) {
        Object unwrapped = Values.unwrap(source);
        if (unwrapped instanceof List<?> list) {
            return list;
        }
        if (borrowed && unwrapped != null && unwrapped.getClass().isArray()) {
            return new ArrayListView(ArgumentsView.fromArray(unwrapped));
        }
        Iterable<?> it = asIterable(unwrapped);
        ArrayList<Object> copy = it instanceof Collection<?> c ? new ArrayList<>(c.size())
                : it instanceof ArgumentsView v ? new ArrayList<>(v.size()) : new ArrayList<>();
        for (Object elem : it) {
            copy.add(elem);
        }
        return copy;
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a JVM array of {@code componentType}.
     * Returns an array whose component type matches {@code componentType} as-is, and copies
     * (with primitive unboxing/widening via {@link Values#toJava}) otherwise.
     */
    public static Object asArray(Object source, Class<?> componentType) {
        Object unwrapped = Values.unwrap(source);
        if (unwrapped != null && unwrapped.getClass().isArray()) {
            Class<?> srcComp = unwrapped.getClass().getComponentType();
            if (srcComp == componentType) {
                return unwrapped;
            }
        }
        Iterable<?> it = asIterable(unwrapped);
        List<Object> elems;
        if (it instanceof List<?> list) {
            elems = (List<Object>) list;
        } else {
            elems = it instanceof Collection<?> c ? new ArrayList<>(c.size())
                    : it instanceof ArgumentsView v ? new ArrayList<>(v.size()) : new ArrayList<>();
            for (Object elem : it) {
                elems.add(elem);
            }
        }
        int n = elems.size();
        Object result = Array.newInstance(componentType, n);
        for (int i = 0; i < n; i++) {
            Array.set(result, i, Values.toJava(elems.get(i), componentType, null));
        }
        return result;
    }

    /**
     * Packs variadic rest arguments where each entry in {@code parts} is either a plain element
     * or a spread sequence ({@code isSpread[i] == true}). Avoids copying whenever possible.
     */
    public static Iterable<?> packRest(Object[] parts, boolean[] isSpread) {
        int n = parts.length;
        if (n == 0) {
            return ArgumentsView.EMPTY;
        }
        if (n == 1 && isSpread[0]) {
            return asIterable(parts[0]);
        }
        boolean anySpread = false;
        for (boolean s : isSpread) {
            if (s) {
                anySpread = true;
                break;
            }
        }
        if (!anySpread) {
            return ArgumentsView.of(parts);
        }
        List<Iterable<?>> segments = new ArrayList<>(n);
        int i = 0;
        while (i < n) {
            if (isSpread[i]) {
                segments.add(asIterable(parts[i++]));
            } else {
                int start = i;
                while (i < n && !isSpread[i]) {
                    i++;
                }
                Object[] slice = new Object[i - start];
                System.arraycopy(parts, start, slice, 0, slice.length);
                segments.add(ArgumentsView.of(slice));
            }
        }
        return segments.size() == 1 ? segments.get(0) : ArgumentsView.concat(segments.toArray(new Iterable<?>[0]));
    }

    /**
     * Collects raw positional arguments {@code args[restIndex .. args.length)} for a variadic
     * callee invoked through {@link Callable#call}, flattening any {@link ArgumentsView.PackedRest}
     * segments without copying.
     */
    public static Iterable<?> collectRest(Object[] args, int restIndex) {
        return collectRest(args, restIndex, args.length);
    }

    public static Iterable<?> collectRest(Object[] args, int restIndex, int endIndex) {
        int end = Math.min(endIndex, args.length);
        int count = 0;
        for (int i = restIndex; i < end; i++) {
            if (args[i] != Arguments.MISSING) {
                count++;
            }
        }
        if (count <= 0) {
            return ArgumentsView.EMPTY;
        }
        if (count == 1) {
            for (int i = restIndex; i < end; i++) {
                if (args[i] != Arguments.MISSING) {
                    return args[i] instanceof ArgumentsView.PackedRest pr ? pr.iterable() : ArgumentsView.single(args[i]);
                }
            }
        }
        boolean[] isSpread = new boolean[count];
        Object[] parts = new Object[count];
        int k = 0;
        for (int i = restIndex; i < end; i++) {
            Object v = args[i];
            if (v == Arguments.MISSING) {
                continue;
            }
            if (v instanceof ArgumentsView.PackedRest pr) {
                parts[k] = pr.iterable();
                isSpread[k] = true;
            } else {
                parts[k] = v;
            }
            k++;
        }
        return packRest(parts, isSpread);
    }

    /** Wraps a packed rest {@link Iterable} in {@link ArgumentsView.PackedRest} for {@link Callable#call}. */
    public static Object packedRest(Iterable<?> iterable) {
        return new ArgumentsView.PackedRest(iterable);
    }

    private static final class ArrayListView extends AbstractList<Object> implements RandomAccess {
        private final ArgumentsView view;

        ArrayListView(ArgumentsView view) {
            this.view = view;
        }

        @Override
        public Object get(int index) {
            return view.get(index);
        }

        @Override
        public int size() {
            return view.size();
        }
    }
}
