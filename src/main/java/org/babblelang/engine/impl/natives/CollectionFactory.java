package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The core collection factories (BIP011-D1, D10), what {@code list}, {@code set},
 * {@code sortedset}, {@code map}, {@code sortedmap} and {@code entry} are as values. Each makes
 * a deterministic {@code java.util} class : an {@code ArrayList}, a {@code LinkedHashSet}, a
 * {@code TreeSet}, a {@code LinkedHashMap}, a {@code TreeMap}, an
 * {@code AbstractMap.SimpleImmutableEntry}, from elements (entries for maps), or from one of
 * the options {@code capacity:}, {@code from:} and, for the sorted ones, {@code comparator:}.
 * The type checker has checked the arguments.
 */
public final class CollectionFactory implements Callable {
    private final String name;

    private CollectionFactory(String name) {
        this.name = name;
    }

    public static final CollectionFactory LIST = new CollectionFactory("list");
    public static final CollectionFactory SET = new CollectionFactory("set");
    public static final CollectionFactory SORTEDSET = new CollectionFactory("sortedset");
    public static final CollectionFactory MAP = new CollectionFactory("map");
    public static final CollectionFactory SORTEDMAP = new CollectionFactory("sortedmap");
    public static final CollectionFactory ENTRY = new CollectionFactory("entry");

    /** The factory of a core collection name, or null. */
    public static CollectionFactory of(String name) {
        return switch (name) {
            case "list" -> LIST;
            case "set" -> SET;
            case "sortedset" -> SORTEDSET;
            case "map" -> MAP;
            case "sortedmap" -> SORTEDMAP;
            case "entry" -> ENTRY;
            default -> null;
        };
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        Object capacity = null;
        Object from = null;
        Object comparator = null;
        Object key = null;
        Object value = null;
        List<Object> elements = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String option = names != null && i < names.length ? names[i] : null;
            if (option == null) {
                elements.add(args[i]);
                continue;
            }
            switch (option) {
                case "capacity" -> capacity = args[i];
                case "from" -> from = Values.unwrap(args[i]);
                case "comparator" -> comparator = args[i];
                case "key" -> key = args[i];
                case "value" -> value = args[i];
                default -> throw new BabbleException("No such parameter : " + option);
            }
        }
        try {
            return make(rt, elements, capacity, from, comparator, key, value);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw JavaTypes.wrapInvocationException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object make(ScriptRuntime rt, List<Object> elements, Object capacity, Object from, Object comparator,
                        Object key, Object value) {
        int size = capacity != null ? (Integer) capacity : -1;
        Comparator<Object> order = comparator != null
                ? (Comparator<Object>) Values.toJava(comparator, Comparator.class, rt) : null;
        switch (name) {
            case "list" -> {
                if (from instanceof Collection<?> c) {
                    return new ArrayList<>(c);
                }
                List<Object> list = size >= 0 || capacity != null ? new ArrayList<>(size) : new ArrayList<>(elements.size());
                addAll(list, elements, from);
                return list;
            }
            case "set" -> {
                LinkedHashSet<Object> set = LinkedHashSet.newLinkedHashSet(capacity != null ? checked(size) : elements.size());
                addAll(set, elements, from);
                return set;
            }
            case "sortedset" -> {
                TreeSet<Object> set = order != null ? new TreeSet<>(order) : new TreeSet<>();
                addAll(set, elements, from);
                return set;
            }
            case "map", "sortedmap" -> {
                Map<Object, Object> map = name.equals("map")
                        ? LinkedHashMap.newLinkedHashMap(capacity != null ? checked(size) : elements.size())
                        : order != null ? new TreeMap<>(order) : new TreeMap<>();
                if (from instanceof Map<?, ?> source) {
                    map.putAll(source);
                }
                for (Object element : elements) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) Values.unwrap(element);
                    map.put(entry.getKey(), entry.getValue());
                }
                return map;
            }
            default -> {
                if (elements.size() == 2) {
                    key = elements.get(0);
                    value = elements.get(1);
                }
                return new AbstractMap.SimpleImmutableEntry<>(key, value);
            }
        }
    }

    private static int checked(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal capacity: " + capacity);
        }
        return capacity;
    }

    private static void addAll(Collection<Object> collection, List<Object> elements, Object from) {
        collection.addAll(elements);
        if (from instanceof Iterable<?> source) {
            for (Object element : source) {
                collection.add(element);
            }
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
