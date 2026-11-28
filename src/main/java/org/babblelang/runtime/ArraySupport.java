package org.babblelang.runtime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import org.babblelang.engine.impl.natives.java.JavaObject;

/**
 * Runtime support for {@code array of T} (BIP020-I3) : allocation, {@code size}/{@code get}/{@code
 * set}, content equality/hashing/printing, interpreter Babble-element store checking via a weak
 * side table, and dynamic array member dispatch. Depends only on {@code org.babblelang.runtime} and
 * {@code java.base}.
 */
public final class ArraySupport {
    private static final String[] GET_PARAMS = {"index"};
    private static final String[] SET_PARAMS = {"index", "value"};
    private static final String[] EQUALS_PARAMS = {"other"};
    private static final Pattern COMPILED_ARRAY_CLASS =
            Pattern.compile("(\\[+)L(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*);");
    private static final Pattern COMPILED_BABBLE_CLASS =
            Pattern.compile("(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*)");

    private record BabbleElementTag(
            String typeId, String displayName, Set<String> conformIds, int nestedDims) {}

    private static final WeakHashMap<Object, BabbleElementTag> ELEMENT_TAGS = new WeakHashMap<>();

    private ArraySupport() {}

    // ---------------------------------------------------------------- creation

    /** Returns whether {@code value} (after unwrapping Java wrappers) is a JVM array. */
    public static boolean isArray(Object value) {
        Object raw = Values.unwrap(value);
        return raw != null && raw.getClass().isArray();
    }

    /** Creates an array of {@code length} default elements for {@code componentType}. */
    public static Object newArray(Class<?> componentType, int length) {
        if (componentType == byte.class) return new byte[length];
        if (componentType == short.class) return new short[length];
        if (componentType == int.class) return new int[length];
        if (componentType == long.class) return new long[length];
        if (componentType == float.class) return new float[length];
        if (componentType == double.class) return new double[length];
        if (componentType == boolean.class) return new boolean[length];
        if (componentType == char.class) return new char[length];
        return Array.newInstance(componentType == null ? Object.class : componentType, length);
    }

    /**
     * Creates an interpreted Babble-element array and registers its element tag in the side table.
     */
    public static Object newBabbleArray(
            String typeId, String displayName, Set<String> conformIds, int nestedDims, int length) {
        Class<?> comp = DynamicInstance.class;
        for (int i = 0; i < nestedDims; i++) {
            comp = comp.arrayType();
        }
        Object array = Array.newInstance(comp, length);
        synchronized (ELEMENT_TAGS) {
            ELEMENT_TAGS.put(
                    array,
                    new BabbleElementTag(
                            typeId,
                            displayName,
                            conformIds == null ? Set.of(typeId) : conformIds,
                            nestedDims));
        }
        return array;
    }

    /**
     * Creates an array from explicit {@code elements}, converting Java interface elements if
     * needed.
     */
    public static Object of(Class<?> componentType, Object[] elements, ScriptRuntime rt) {
        Object array = newArray(componentType, elements.length);
        for (int i = 0; i < elements.length; i++) {
            set(array, i, elements[i], rt);
        }
        return array;
    }

    /** Creates an interpreted Babble-element array from explicit {@code elements}. */
    public static Object ofBabble(
            String typeId,
            String displayName,
            Set<String> conformIds,
            int nestedDims,
            Object[] elements) {
        Object array = newBabbleArray(typeId, displayName, conformIds, nestedDims, elements.length);
        for (int i = 0; i < elements.length; i++) {
            set(array, i, elements[i], null);
        }
        return array;
    }

    /**
     * Copies elements from an array or {@link Iterable} into a new array of {@code componentType}.
     */
    public static Object from(Class<?> componentType, Object source, ScriptRuntime rt) {
        Object raw = Values.unwrap(source);
        if (raw == null) {
            throw new NullPointerException("Cannot copy array from null");
        }
        if (raw.getClass().isArray()) {
            int len = Array.getLength(raw);
            Object dst = newArray(componentType, len);
            if (dst.getClass() == raw.getClass() && componentType.isPrimitive()) {
                System.arraycopy(raw, 0, dst, 0, len);
                return dst;
            }
            for (int i = 0; i < len; i++) {
                set(dst, i, widenBoxedNumeric(componentType, get(raw, i)), rt);
            }
            return dst;
        }
        if (Faces.toJava(raw, Iterable.class, rt) instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            for (Object e : it) {
                items.add(Values.fromJava(e));
            }
            Object dst = newArray(componentType, items.size());
            for (int i = 0; i < items.size(); i++) {
                set(dst, i, widenBoxedNumeric(componentType, items.get(i)), rt);
            }
            return dst;
        }
        throw new BabbleException(
                "Type error : expected iterable or array, got " + Types.describe(raw));
    }

    private static Object widenBoxedNumeric(Class<?> componentType, Object value) {
        if (!(value instanceof Number n)
                || componentType == null
                || componentType.isInstance(value)) {
            return value;
        }
        if (componentType == Short.class) return n.shortValue();
        if (componentType == Integer.class) return n.intValue();
        if (componentType == Long.class) return n.longValue();
        if (componentType == Float.class) return n.floatValue();
        if (componentType == Double.class) return n.doubleValue();
        return value;
    }

    /**
     * Copies elements from an array or {@link Iterable} into a new interpreted Babble-element
     * array.
     */
    public static Object fromBabble(
            String typeId,
            String displayName,
            Set<String> conformIds,
            int nestedDims,
            Object source,
            ScriptRuntime rt) {
        Object raw = Values.unwrap(source);
        if (raw == null) {
            throw new NullPointerException("Cannot copy array from null");
        }
        if (raw.getClass().isArray()) {
            int len = Array.getLength(raw);
            Object dst = newBabbleArray(typeId, displayName, conformIds, nestedDims, len);
            for (int i = 0; i < len; i++) {
                set(dst, i, get(raw, i), rt);
            }
            return dst;
        }
        if (Faces.toJava(raw, Iterable.class, rt) instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            for (Object e : it) {
                items.add(Values.fromJava(e));
            }
            Object dst = newBabbleArray(typeId, displayName, conformIds, nestedDims, items.size());
            for (int i = 0; i < items.size(); i++) {
                set(dst, i, items.get(i), rt);
            }
            return dst;
        }
        throw new BabbleException(
                "Type error : expected iterable or array, got " + Types.describe(raw));
    }

    // ---------------------------------------------------------------- element access

    public static int size(Object array) {
        return Array.getLength(Values.unwrap(array));
    }

    /**
     * Returns {@code true} when {@code array} contains an element equal to {@code value} under
     * Babble {@code ==}.
     */
    public static boolean contains(Object array, Object value) {
        Object raw = Values.unwrap(array);
        if (raw == null) {
            return false;
        }
        if (raw instanceof int[] a) {
            if (!(value instanceof Number)) return false;
            for (int e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof double[] a) {
            if (!(value instanceof Number)) return false;
            for (double e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof long[] a) {
            if (!(value instanceof Number)) return false;
            for (long e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof boolean[] a) {
            if (!(value instanceof Boolean b)) return false;
            boolean bv = b;
            for (boolean e : a) {
                if (e == bv) return true;
            }
            return false;
        }
        if (raw instanceof byte[] a) {
            if (!(value instanceof Number)) return false;
            for (byte e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof short[] a) {
            if (!(value instanceof Number)) return false;
            for (short e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof float[] a) {
            if (!(value instanceof Number)) return false;
            for (float e : a) {
                if (Operators.equal(e, value)) return true;
            }
            return false;
        }
        if (raw instanceof char[] a) {
            if (!(value instanceof Character c)) return false;
            char cv = c;
            for (char e : a) {
                if (e == cv) return true;
            }
            return false;
        }
        Object[] oa = (Object[]) raw;
        for (Object e : oa) {
            if (Operators.equal(Values.fromJava(e), value)) return true;
        }
        return false;
    }

    /** Returns a fresh tail copy of {@code array} starting at {@code start} ({@code BIP038-D7}). */
    public static Object slice(Object array, int start) {
        Object raw = Values.unwrap(array);
        if (raw instanceof int[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof double[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof long[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof boolean[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof byte[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof short[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof float[] a) return Arrays.copyOfRange(a, start, a.length);
        if (raw instanceof char[] a) return Arrays.copyOfRange(a, start, a.length);
        Object[] oa = (Object[]) raw;
        Object[] copy = Arrays.copyOfRange(oa, start, oa.length);
        synchronized (ELEMENT_TAGS) {
            BabbleElementTag tag = ELEMENT_TAGS.get(oa);
            if (tag != null) {
                ELEMENT_TAGS.put(copy, tag);
            }
        }
        return copy;
    }

    public static Object get(Object array, int index) {
        Object raw = Values.unwrap(array);
        if (raw instanceof int[] a) return a[index];
        if (raw instanceof double[] a) return a[index];
        if (raw instanceof long[] a) return a[index];
        if (raw instanceof boolean[] a) return a[index];
        if (raw instanceof byte[] a) return a[index];
        if (raw instanceof short[] a) return a[index];
        if (raw instanceof float[] a) return a[index];
        if (raw instanceof char[] a) return a[index];
        return Values.fromJava(((Object[]) raw)[index]);
    }

    public static Object set(Object array, int index, Object value, ScriptRuntime rt) {
        Object raw = Values.unwrap(array);
        if (raw instanceof int[] a) {
            a[index] = ((Number) value).intValue();
            return null;
        }
        if (raw instanceof double[] a) {
            a[index] = ((Number) value).doubleValue();
            return null;
        }
        if (raw instanceof long[] a) {
            a[index] = ((Number) value).longValue();
            return null;
        }
        if (raw instanceof boolean[] a) {
            a[index] = (Boolean) value;
            return null;
        }
        if (raw instanceof byte[] a) {
            a[index] = ((Number) value).byteValue();
            return null;
        }
        if (raw instanceof short[] a) {
            a[index] = ((Number) value).shortValue();
            return null;
        }
        if (raw instanceof float[] a) {
            a[index] = ((Number) value).floatValue();
            return null;
        }
        if (raw instanceof char[] a) {
            a[index] = (Character) value;
            return null;
        }
        Object[] oa = (Object[]) raw;
        checkBabbleStore(oa, value);
        Object stored = Values.unwrap(value);
        Class<?> comp = oa.getClass().getComponentType();
        if (stored != null
                && comp != Object.class
                && comp != DynamicInstance.class
                && !comp.isInstance(stored)) {
            stored = Faces.toJava(stored, comp, rt);
        }
        try {
            oa[index] = stored;
        } catch (ArrayStoreException e) {
            if (stored instanceof DynamicInstance inst) {
                throw new ArrayStoreException(inst.babbleClass().name());
            }
            throw e;
        }
        return null;
    }

    private static void checkBabbleStore(Object array, Object value) {
        if (value == null) {
            return;
        }
        BabbleElementTag tag;
        synchronized (ELEMENT_TAGS) {
            tag = ELEMENT_TAGS.get(array);
        }
        if (tag == null) {
            return;
        }
        if (tag.nestedDims() == 0) {
            BabbleClass actual = BabbleObjects.babbleClass(value);
            if (actual == null || !actual.conformsTo(tag.typeId())) {
                throw new ArrayStoreException(
                        actual != null ? actual.name() : value.getClass().getName());
            }
            return;
        }
        Object rawVal = Values.unwrap(value);
        if (rawVal == null || !rawVal.getClass().isArray()) {
            throw new ArrayStoreException(value.getClass().getName());
        }
        BabbleElementTag valTag;
        synchronized (ELEMENT_TAGS) {
            valTag = ELEMENT_TAGS.get(rawVal);
        }
        if (valTag != null) {
            if (valTag.nestedDims() != tag.nestedDims() - 1
                    || !valTag.conformIds().contains(tag.typeId())) {
                String prefix = "array of ".repeat(valTag.nestedDims() + 1);
                throw new ArrayStoreException(prefix + valTag.displayName());
            }
        }
    }

    // ---------------------------------------------------------------- equality, hash, formatting

    /**
     * Deep structural equality matching {@code Arrays.deepEquals} with Babble's {@code ==} on
     * elements.
     */
    public static boolean deepEquals(Object a, Object b) {
        return ErrorFormat.deepEquals(a, b);
    }

    /** Content hash consistent with {@link #deepEquals}. */
    public static int deepHashCode(Object a) {
        return ErrorFormat.deepHashCode(a);
    }

    /** Formats an array as {@code [a, b]}, matching {@code Arrays.deepToString} semantics. */
    public static String deepToString(Object a) {
        return ErrorFormat.deepToString(a);
    }

    /** Formats any Babble value for display, printing arrays by contents. */
    public static String format(Object value) {
        return ErrorFormat.format(value);
    }

    /**
     * Formats a host Java {@link Throwable} consistently across modes (stripping Helpful NPE
     * details).
     */
    public static String formatJavaException(Throwable t) {
        return ErrorFormat.formatJavaException(t);
    }

    /** Normalizes uncaught runtime exceptions for script boundaries (BIP028-D3). */
    public static String formatThrowable(Throwable t) {
        return ErrorFormat.formatThrowable(t);
    }

    // ---------------------------------------------------------------- dynamic member dispatch

    /**
     * Selects a Babble array method ({@code size}, {@code get}, {@code set}, {@code equals}, {@code
     * hashCode}, {@code toString}) or falls back to {@link JavaObject#member}.
     */
    public static Object member(Object array, String name) {
        if ("length".equals(name)) {
            throw new BabbleException(
                    "No such name in " + Types.describe(array) + " : length (use size())");
        }
        return switch (name) {
            case "size" -> new ArrayMethod(array, "size", 0);
            case "get" -> new ArrayMethod(array, "get", 1);
            case "set" -> new ArrayMethod(array, "set", 2);
            case "equals" -> new ArrayMethod(array, "equals", 3);
            case "hashCode" -> new ArrayMethod(array, "hashCode", 4);
            case "toString" -> new ArrayMethod(array, "toString", 5);
            default -> JavaObject.member(array, name);
        };
    }

    private static final class ArrayMethod implements Callable {
        private final Object receiver;
        private final String name;
        private final int op;

        private ArrayMethod(Object receiver, String name, int op) {
            this.receiver = receiver;
            this.name = name;
            this.op = op;
        }

        @Override
        public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
            return switch (op) {
                case 0 -> {
                    checkArity(args, names, 0);
                    yield size(receiver);
                }
                case 1 -> {
                    Arguments.Binder b = Arguments.binder(GET_PARAMS, args, names);
                    Object idx = b.take(0);
                    if (idx == Arguments.MISSING) throw b.missing(0);
                    b.rejectLeftovers();
                    yield get(receiver, ((Number) idx).intValue());
                }
                case 2 -> {
                    Arguments.Binder b = Arguments.binder(SET_PARAMS, args, names);
                    Object idx = b.take(0);
                    if (idx == Arguments.MISSING) throw b.missing(0);
                    Object val = b.take(1);
                    if (val == Arguments.MISSING) throw b.missing(1);
                    b.rejectLeftovers();
                    yield set(receiver, ((Number) idx).intValue(), val, rt);
                }
                case 3 -> {
                    Arguments.Binder b = Arguments.binder(EQUALS_PARAMS, args, names);
                    Object other = b.take(0);
                    if (other == Arguments.MISSING) throw b.missing(0);
                    b.rejectLeftovers();
                    yield deepEquals(receiver, other);
                }
                case 4 -> {
                    checkArity(args, names, 0);
                    yield deepHashCode(receiver);
                }
                case 5 -> {
                    checkArity(args, names, 0);
                    yield deepToString(receiver);
                }
                default -> throw new IllegalStateException("Unknown array method : " + name);
            };
        }

        private static void checkArity(Object[] args, String[] names, int expected) {
            if (args.length > expected || names != null) {
                Arguments.binder(new String[expected], args, names).rejectLeftovers();
            }
        }
    }
}
