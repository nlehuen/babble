package org.babblelang.runtime;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.babblelang.engine.impl.natives.java.JavaClass;

/**
 * Runtime factories for Babble's core collection constructors ({@code list}, {@code set}, {@code
 * sortedset}, {@code map}, {@code sortedmap}, {@code entry}) ({@code BIP011-D10}).
 */
public final class CoreCollections {
    private static final class CoreConstructor implements Callable, HasMembers {
        private final String kind;
        private final JavaClass javaClass;

        private CoreConstructor(String kind, Class<?> javaInterface) {
            this.kind = kind;
            this.javaClass = JavaClass.of(javaInterface);
        }

        @Override
        public Object call(
                ScriptRuntime runtime, Object[] arguments, String[] names, CallSite site) {
            return construct(kind, arguments, names, runtime);
        }

        @Override
        public Object getMember(String name, boolean own) {
            return Members.select(javaClass, name, kind);
        }

        @Override
        public void setMember(String name, Object value, boolean own) {
            Members.assign(javaClass, value, name);
        }
    }

    private static final Map<String, Callable> CONSTRUCTORS =
            Map.of(
                    "list", new CoreConstructor("list", List.class),
                    "set", new CoreConstructor("set", Set.class),
                    "sortedset", new CoreConstructor("sortedset", SortedSet.class),
                    "map", new CoreConstructor("map", Map.class),
                    "sortedmap", new CoreConstructor("sortedmap", SortedMap.class),
                    "entry", new CoreConstructor("entry", Map.Entry.class));

    private CoreCollections() {}

    /**
     * First-class callable constructor for a core collection keyword ({@code list}, {@code set},
     * etc.).
     */
    public static Callable constructor(String kind) {
        Callable c = CONSTRUCTORS.get(kind);
        if (c == null) {
            throw new BabbleException("Unknown core collection : " + kind);
        }
        return c;
    }

    public static List<Object> listEmpty() {
        return new ArrayList<>();
    }

    public static List<Object> listOf(Object... elements) {
        ArrayList<Object> list = new ArrayList<>(elements.length);
        Collections.addAll(list, elements);
        return list;
    }

    public static List<Object> listCapacity(int capacity) {
        if (capacity < 0) {
            throw new BabbleException(
                    "java.lang.IllegalArgumentException : Illegal Capacity: " + capacity);
        }
        return new ArrayList<>(capacity);
    }

    public static List<Object> listFrom(Iterable<?> from) {
        if (from instanceof Collection<?> col) {
            return new ArrayList<>(col);
        }
        ArrayList<Object> list = new ArrayList<>();
        for (Object e : from) {
            list.add(e);
        }
        return list;
    }

    /** Fresh {@link ArrayList} tail slice starting at {@code start} ({@code BIP038-D7}). */
    public static List<Object> listSlice(List<?> list, int start) {
        int size = list.size();
        if (start >= size) {
            return new ArrayList<>(0);
        }
        ArrayList<Object> result = new ArrayList<>(size - start);
        for (int i = start; i < size; i++) {
            result.add(list.get(i));
        }
        return result;
    }

    public static Set<Object> setEmpty() {
        return new LinkedHashSet<>();
    }

    public static Set<Object> setOf(Object... elements) {
        Set<Object> set = LinkedHashSet.newLinkedHashSet(elements.length);
        Collections.addAll(set, elements);
        return set;
    }

    public static Set<Object> setCapacity(int capacity) {
        if (capacity < 0) {
            throw new BabbleException(
                    "java.lang.IllegalArgumentException : Illegal Capacity: " + capacity);
        }
        return LinkedHashSet.newLinkedHashSet(capacity);
    }

    public static Set<Object> setFrom(Iterable<?> from) {
        if (from instanceof Collection<?> col) {
            return new LinkedHashSet<>(col);
        }
        Set<Object> set = new LinkedHashSet<>();
        for (Object e : from) {
            set.add(e);
        }
        return set;
    }

    public static SortedSet<Object> sortedSetEmpty() {
        return new TreeSet<>();
    }

    public static SortedSet<Object> sortedSetOf(Object... elements) {
        boolean needsAdapter = false;
        for (Object e : elements) {
            if (needsBabbleComparable(e)) {
                needsAdapter = true;
                break;
            }
        }
        TreeSet<Object> set =
                needsAdapter ? new TreeSet<>(CoreCollections::compareBabble) : new TreeSet<>();
        Collections.addAll(set, elements);
        return set;
    }

    public static SortedSet<Object> sortedSetComparator(Object comparator, ScriptRuntime rt) {
        Comparator<Object> cmp = toComparator(comparator, rt);
        return cmp == null ? new TreeSet<>() : new TreeSet<>(cmp);
    }

    public static SortedSet<Object> sortedSetFrom(
            Iterable<?> from, Object comparator, ScriptRuntime rt) {
        Comparator<Object> cmp = toComparator(comparator, rt);
        if (cmp == null && from instanceof SortedSet<?> ss && ss.comparator() != null) {
            cmp = CoreCollections::compareBabble;
        }
        TreeSet<Object> set = cmp == null ? new TreeSet<>() : new TreeSet<>(cmp);
        for (Object e : from) {
            if (cmp == null && needsBabbleComparable(e)) {
                TreeSet<Object> adapted = new TreeSet<>(CoreCollections::compareBabble);
                adapted.addAll(set);
                set = adapted;
                cmp = CoreCollections::compareBabble;
            }
            set.add(e);
        }
        return set;
    }

    public static Map<Object, Object> mapEmpty() {
        return new LinkedHashMap<>();
    }

    public static Map<Object, Object> mapOf(Object... entries) {
        Map<Object, Object> map = LinkedHashMap.newLinkedHashMap(entries.length);
        for (Object item : entries) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    public static Map<Object, Object> mapCapacity(int capacity) {
        if (capacity < 0) {
            throw new BabbleException(
                    "java.lang.IllegalArgumentException : Illegal Capacity: " + capacity);
        }
        return LinkedHashMap.newLinkedHashMap(capacity);
    }

    public static Map<Object, Object> mapFrom(Map<?, ?> from) {
        return new LinkedHashMap<>(from);
    }

    /**
     * Functional-but-in-place ({@code O10}) helper for {@code list(from: move(xs))} when {@code xs}
     * is statically unique: reuses {@code xs} directly if it is an exact {@link ArrayList},
     * otherwise falls back to {@link #listFrom(Iterable)}.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> listReuseOrCopy(Iterable<?> from) {
        if (from != null && from.getClass() == ArrayList.class) {
            return (List<Object>) from;
        }
        return listFrom(from);
    }

    /**
     * Functional-but-in-place ({@code O10}) helper for {@code set(from: move(s))} when {@code s} is
     * statically unique: reuses {@code s} directly if it is an exact {@link LinkedHashSet},
     * otherwise falls back to {@link #setFrom(Iterable)}.
     */
    @SuppressWarnings("unchecked")
    public static Set<Object> setReuseOrCopy(Iterable<?> from) {
        if (from != null && from.getClass() == LinkedHashSet.class) {
            return (Set<Object>) from;
        }
        return setFrom(from);
    }

    /**
     * Functional-but-in-place ({@code O10}) helper for {@code map(from: move(m))} when {@code m} is
     * statically unique: reuses {@code m} directly if it is an exact {@link LinkedHashMap},
     * otherwise falls back to {@link #mapFrom(Map)}.
     */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> mapReuseOrCopy(Map<?, ?> from) {
        if (from != null && from.getClass() == LinkedHashMap.class) {
            return (Map<Object, Object>) from;
        }
        return mapFrom(from);
    }

    public static SortedMap<Object, Object> sortedMapEmpty() {
        return new TreeMap<>();
    }

    public static SortedMap<Object, Object> sortedMapOf(Object... entries) {
        boolean needsAdapter = false;
        for (Object item : entries) {
            if (needsBabbleComparable(((Map.Entry<?, ?>) item).getKey())) {
                needsAdapter = true;
                break;
            }
        }
        TreeMap<Object, Object> map =
                needsAdapter ? new TreeMap<>(CoreCollections::compareBabble) : new TreeMap<>();
        for (Object item : entries) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    public static SortedMap<Object, Object> sortedMapComparator(
            Object comparator, ScriptRuntime rt) {
        Comparator<Object> cmp = toComparator(comparator, rt);
        return cmp == null ? new TreeMap<>() : new TreeMap<>(cmp);
    }

    public static SortedMap<Object, Object> sortedMapFrom(
            Map<?, ?> from, Object comparator, ScriptRuntime rt) {
        Comparator<Object> cmp = toComparator(comparator, rt);
        TreeMap<Object, Object> map = cmp == null ? new TreeMap<>() : new TreeMap<>(cmp);
        for (Map.Entry<?, ?> entry : from.entrySet()) {
            if (cmp == null && needsBabbleComparable(entry.getKey())) {
                TreeMap<Object, Object> adapted = new TreeMap<>(CoreCollections::compareBabble);
                adapted.putAll(map);
                map = adapted;
                cmp = CoreCollections::compareBabble;
            }
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static boolean needsBabbleComparable(Object o) {
        return BabbleObjects.isInstance(o) && !(o instanceof Comparable<?>);
    }

    @SuppressWarnings("unchecked")
    private static int compareBabble(Object a, Object b) {
        Object ca = BabbleObjects.face(a);
        Object cb = BabbleObjects.face(b);
        return ((Comparable<Object>) ca).compareTo(cb);
    }

    public static Map.Entry<Object, Object> entry(Object key, Object value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    public static Object construct(String kind, Object[] args, String[] names, ScriptRuntime rt) {
        int count = args.length;
        String n0 = names != null && count > 0 ? names[0] : null;
        String n1 = names != null && count > 1 ? names[1] : null;
        if ("entry".equals(kind)) {
            if (count == 2) {
                Object key = "value".equals(n0) && "key".equals(n1) ? args[1] : args[0];
                Object val = "value".equals(n0) && "key".equals(n1) ? args[0] : args[1];
                return entry(key, val);
            }
            throw new BabbleException(
                    "Wrong number of arguments for entry : expected 2, got " + count);
        }
        if (count == 0) {
            return switch (kind) {
                case "list" -> listEmpty();
                case "set" -> setEmpty();
                case "sortedset" -> sortedSetEmpty();
                case "map" -> mapEmpty();
                case "sortedmap" -> sortedMapEmpty();
                default -> throw new BabbleException("Unknown collection : " + kind);
            };
        }
        if (count == 1 && "capacity".equals(n0)) {
            int cap = ((Number) args[0]).intValue();
            return switch (kind) {
                case "list" -> listCapacity(cap);
                case "set" -> setCapacity(cap);
                case "map" -> mapCapacity(cap);
                default -> throw new BabbleException("Unknown parameter capacity for " + kind);
            };
        }
        if (count == 1 && "comparator".equals(n0)) {
            return switch (kind) {
                case "sortedset" -> sortedSetComparator(args[0], rt);
                case "sortedmap" -> sortedMapComparator(args[0], rt);
                default -> throw new BabbleException("Unknown parameter comparator for " + kind);
            };
        }
        if (count == 1 && "from".equals(n0)) {
            return switch (kind) {
                case "list" -> listFrom(toIterable(args[0]));
                case "set" -> setFrom(toIterable(args[0]));
                case "sortedset" -> sortedSetFrom(toIterable(args[0]), null, rt);
                case "map" -> mapFrom(toMap(args[0]));
                case "sortedmap" -> sortedMapFrom(toMap(args[0]), null, rt);
                default -> throw new BabbleException("Unknown parameter from for " + kind);
            };
        }
        if (count == 2
                && (("from".equals(n0) && "comparator".equals(n1))
                        || ("comparator".equals(n0) && "from".equals(n1)))) {
            Object from = "from".equals(n0) ? args[0] : args[1];
            Object comp = "comparator".equals(n0) ? args[0] : args[1];
            return switch (kind) {
                case "sortedset" -> sortedSetFrom(toIterable(from), comp, rt);
                case "sortedmap" -> sortedMapFrom(toMap(from), comp, rt);
                default -> throw new BabbleException("Unknown parameters for " + kind);
            };
        }
        return switch (kind) {
            case "list" -> listOf(args);
            case "set" -> setOf(args);
            case "sortedset" -> sortedSetOf(args);
            case "map" -> mapOf(args);
            case "sortedmap" -> sortedMapOf(args);
            default -> throw new BabbleException("Unknown collection : " + kind);
        };
    }

    /**
     * Converts any runtime iterable or iterator (Java {@link Iterable}, Java {@link Iterator}, or
     * Babble custom {@code iterator()}/{@code hasNext()}/{@code next()} object) to a {@link
     * Iterator} for {@code DYNAMIC_UNION} {@code for} loops ({@code BIP032}).
     */
    public static java.util.Iterator<?> toJavaIterator(
            Object iterable, ScriptRuntime rt, CallSite site) {
        Object raw = Values.unwrap(iterable);
        if (BabbleObjects.isInstance(raw)) {
            Object face = BabbleObjects.face(raw);
            if (face instanceof java.util.Iterator<?> it) {
                return it;
            }
            if (face instanceof Iterable<?> it) {
                return it.iterator();
            }
        }
        if (raw instanceof java.util.Iterator<?> it) {
            return it;
        }
        if (raw instanceof Iterable<?> it) {
            return it.iterator();
        }
        Object iterObj = iterable;
        if (Members.hasMember(iterable, "iterator")) {
            Callable iterFn =
                    Calls.callable(Members.select(iterable, "iterator", "iterator"), site);
            iterObj = iterFn.call(rt, Arguments.NONE, null, site);
            Object unwrapped = Values.unwrap(iterObj);
            if (unwrapped instanceof java.util.Iterator<?> it) {
                return it;
            }
        }
        final Object babbleIter = iterObj;
        return new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                Callable fn =
                        Calls.callable(Members.select(babbleIter, "hasNext", "hasNext"), site);
                return Truth.test(fn.call(rt, Arguments.NONE, null, site));
            }

            @Override
            public Object next() {
                Callable fn = Calls.callable(Members.select(babbleIter, "next", "next"), site);
                return fn.call(rt, Arguments.NONE, null, site);
            }
        };
    }

    private static Iterable<?> toIterable(Object obj) {
        Object u = Values.unwrap(obj);
        if (BabbleObjects.isInstance(u)) {
            u = BabbleObjects.face(u);
        }
        return (Iterable<?>) u;
    }

    private static Map<?, ?> toMap(Object obj) {
        Object u = Values.unwrap(obj);
        if (BabbleObjects.isInstance(u)) {
            u = BabbleObjects.face(u);
        }
        return (Map<?, ?>) u;
    }

    @SuppressWarnings("unchecked")
    private static Comparator<Object> toComparator(Object comparator, ScriptRuntime rt) {
        if (comparator == null) {
            return null;
        }
        if (comparator instanceof Comparator<?> cmp) {
            return (Comparator<Object>) cmp;
        }
        if (comparator instanceof Callable callable) {
            return (Comparator<Object>) Faces.function(callable, Comparator.class, rt);
        }
        throw new BabbleException(
                "Type error : expected java.util.Comparator, got "
                        + JavaTypes.describe(comparator));
    }
}
