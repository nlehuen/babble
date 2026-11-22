package org.babblelang.runtime;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;

/**
 * Runtime adaptation helpers for spread arguments ({@code ...e}) and variadic rest packing
 * ({@code BIP021-D6}, {@code BIP021-D7}). Shared by the interpreter and compiled bytecode.
 */
public final class Spreads {
    private Spreads() {
    }

    /** Adapts an {@link Iterable} or JVM array to a read-only {@link Iterable} without copying. */
    public static Iterable<?> asIterable(Object source) {
        Object unwrapped = Values.unwrap(source);
        if (BabbleObjects.isInstance(unwrapped)) {
            unwrapped = BabbleObjects.face(unwrapped);
        }
        if (unwrapped instanceof ArgumentsView || unwrapped instanceof UnmodifiableIterable
                || unwrapped instanceof SamAdaptingIterable) {
            return (Iterable<?>) unwrapped;
        }
        if (unwrapped instanceof List<?> list) {
            return Collections.unmodifiableList(list);
        }
        if (unwrapped instanceof Iterable<?> it) {
            return new UnmodifiableIterable(it);
        }
        return ArgumentsView.fromArray(unwrapped);
    }

    /** Adapts an {@link Iterable} or JVM array to a read-only {@link Iterable}, converting Babble callables via SAM+markers. */
    public static Iterable<?> asIterable(Object source, Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        Iterable<?> base = asIterable(source);
        return samInterface == null ? base : new SamAdaptingIterable(base, samInterface, markers, rt);
    }

    /** Name-based {@link #asIterable(Object, Class, Class[], ScriptRuntime)} for non-public Java interfaces. */
    public static Iterable<?> asIterableByName(Object source, String samName, String[] markerNames, ScriptRuntime rt) {
        Class<?> sam = loadClass(samName);
        Class<?>[] markers = loadClasses(markerNames);
        return asIterable(source, sam, markers, rt);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a {@link List}. When {@code borrowed} is true,
     * returns a {@link List} as-is and views a JVM array without copying; otherwise copies into a
     * fresh {@link ArrayList}.
     */
    public static List<?> asList(Object source, boolean borrowed) {
        return asList(source, borrowed, null, null, null);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a {@link List}, eagerly adapting callable elements
     * to {@code samInterface} (+ optional {@code markers}) when {@code samInterface != null}.
     */
    public static List<?> asList(Object source, boolean borrowed,
                                 Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        Object unwrapped = Values.unwrap(source);
        if (BabbleObjects.isInstance(unwrapped)) {
            unwrapped = BabbleObjects.face(unwrapped);
        }
        if (samInterface == null) {
            if (borrowed && unwrapped instanceof List<?> list) {
                return list;
            }
            if (borrowed && unwrapped != null && unwrapped.getClass().isArray()) {
                return new ArrayListView(ArgumentsView.fromArray(unwrapped));
            }
        }
        Iterable<?> it = asIterable(unwrapped);
        ArrayList<Object> copy = it instanceof Collection<?> c ? new ArrayList<>(c.size())
                : it instanceof ArgumentsView v ? new ArrayList<>(v.size()) : new ArrayList<>();
        for (Object elem : it) {
            copy.add(samInterface != null ? adaptSamElem(elem, samInterface, markers, rt) : elem);
        }
        return copy;
    }

    /** Name-based {@link #asList(Object, boolean, Class, Class[], ScriptRuntime)} for non-public Java interfaces. */
    public static List<?> asListByName(Object source, boolean borrowed,
                                       String samName, String[] markerNames, ScriptRuntime rt) {
        return asList(source, borrowed, loadClass(samName), loadClasses(markerNames), rt);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a fresh JVM array of {@code componentType},
     * copying arrays directly when {@code srcComp == componentType} and unboxing/widening via
     * {@link Values#toJava} otherwise.
     */
    public static Object asArray(Object source, Class<?> componentType) {
        return asArray(source, componentType, null, null, null);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a fresh JVM array of {@code componentType},
     * propagating {@code rt} to {@link Values#toJava} for Babble callable adapters and widening
     * boxed numeric elements when storing into boxed numeric wrapper arrays.
     */
    public static Object asArray(Object source, Class<?> componentType, ScriptRuntime rt) {
        return asArray(source, componentType, null, null, rt);
    }

    /**
     * Adapts an {@link Iterable} or JVM array to a fresh JVM array of {@code componentType},
     * optionally adapting Babble callables to {@code samInterface} (+ {@code markers}).
     */
    public static Object asArray(Object source, Class<?> componentType,
                                 Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        Object unwrapped = Values.unwrap(source);
        if (BabbleObjects.isInstance(unwrapped)) {
            unwrapped = BabbleObjects.face(unwrapped);
        }
        if (samInterface == null && unwrapped != null && unwrapped.getClass().isArray()) {
            Class<?> srcComp = unwrapped.getClass().getComponentType();
            if (srcComp == componentType) {
                int len = Array.getLength(unwrapped);
                Object copy = Array.newInstance(componentType, len);
                System.arraycopy(unwrapped, 0, copy, 0, len);
                return copy;
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
        Class<?> box = JavaTypes.box(componentType);
        for (int i = 0; i < n; i++) {
            Object val;
            if (samInterface != null) {
                val = adaptSamElem(elems.get(i), samInterface, markers, rt);
            } else {
                val = Values.toJava(elems.get(i), componentType, rt);
                if (val != null && JavaTypes.isWidening(val.getClass(), box)) {
                    val = JavaTypes.widen(val, box);
                }
            }
            Array.set(result, i, val);
        }
        return result;
    }

    /** Name-based {@link #asArray(Object, Class, Class, Class[], ScriptRuntime)} for non-public Java interfaces. */
    public static Object asArrayByName(Object source, Class<?> componentType,
                                       String samName, String[] markerNames, ScriptRuntime rt) {
        return asArray(source, componentType, loadClass(samName), loadClasses(markerNames), rt);
    }

    /** Name-based {@link #asArrayByName} when {@code componentType} itself is non-public. */
    public static Object asArrayByNames(Object source, String componentName,
                                        String samName, String[] markerNames, ScriptRuntime rt) {
        return asArray(source, loadClass(componentName), loadClass(samName), loadClasses(markerNames), rt);
    }

    private static Object adaptSamElem(Object elem, Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        Object unwrapped = Values.unwrap(elem);
        if (BabbleObjects.isInstance(unwrapped)) {
            unwrapped = BabbleObjects.face(unwrapped);
        }
        return Values.toJavaSam(unwrapped, samInterface, markers, rt);
    }

    private static Class<?> loadClass(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, Spreads.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown Java interface : " + name, e);
        }
    }

    private static Class<?>[] loadClasses(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = loadClass(names[i]);
        }
        return out;
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
                    return args[i] instanceof ArgumentsView.PackedRest pr
                            ? asIterable(pr.iterable())
                            : ArgumentsView.single(args[i]);
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

    private static final class UnmodifiableIterable implements Iterable<Object> {
        private final Iterable<?> delegate;

        UnmodifiableIterable(Iterable<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Object> iterator() {
            Iterator<?> it = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object next() {
                    return it.next();
                }
            };
        }
    }

    private static final class SamAdaptingIterable implements Iterable<Object> {
        private final Iterable<?> delegate;
        private final Class<?> samInterface;
        private final Class<?>[] markers;
        private final ScriptRuntime rt;

        SamAdaptingIterable(Iterable<?> delegate, Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
            this.delegate = delegate;
            this.samInterface = samInterface;
            this.markers = markers;
            this.rt = rt;
        }

        @Override
        public Iterator<Object> iterator() {
            Iterator<?> it = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object next() {
                    return adaptSamElem(it.next(), samInterface, markers, rt);
                }
            };
        }
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
