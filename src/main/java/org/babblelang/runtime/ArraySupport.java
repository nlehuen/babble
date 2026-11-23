package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime support for {@code array of T} (BIP020-I3) : allocation, {@code size}/{@code get}/{@code set},
 * content equality/hashing/printing, interpreter Babble-element store checking via a weak side table,
 * and dynamic array member dispatch. Depends only on {@code org.babblelang.runtime} and {@code java.base}.
 */
public final class ArraySupport {
    private static final String[] GET_PARAMS = {"index"};
    private static final String[] SET_PARAMS = {"index", "value"};
    private static final String[] EQUALS_PARAMS = {"other"};
    private static final Pattern COMPILED_ARRAY_CLASS = Pattern.compile("(\\[+)L(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*);");
    private static final Pattern COMPILED_BABBLE_CLASS = Pattern.compile("(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*)");

    private record BabbleElementTag(String typeId, String displayName, Set<String> conformIds, int nestedDims) {
    }

    private static final WeakHashMap<Object, BabbleElementTag> ELEMENT_TAGS = new WeakHashMap<>();

    private ArraySupport() {
    }

    // ---------------------------------------------------------------- creation

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

    /** Creates an interpreted Babble-element array and registers its element tag in the side table. */
    public static Object newBabbleArray(String typeId, String displayName, Set<String> conformIds,
                                        int nestedDims, int length) {
        Class<?> comp = InterpretedInstance.class;
        for (int i = 0; i < nestedDims; i++) {
            comp = comp.arrayType();
        }
        Object array = Array.newInstance(comp, length);
        synchronized (ELEMENT_TAGS) {
            ELEMENT_TAGS.put(array, new BabbleElementTag(typeId, displayName,
                    conformIds == null ? Set.of(typeId) : conformIds, nestedDims));
        }
        return array;
    }

    /** Creates an array from explicit {@code elements}, converting Java interface elements if needed. */
    public static Object of(Class<?> componentType, Object[] elements, ScriptRuntime rt) {
        Object array = newArray(componentType, elements.length);
        for (int i = 0; i < elements.length; i++) {
            set(array, i, elements[i], rt);
        }
        return array;
    }

    /** Creates an interpreted Babble-element array from explicit {@code elements}. */
    public static Object ofBabble(String typeId, String displayName, Set<String> conformIds,
                                  int nestedDims, Object[] elements) {
        Object array = newBabbleArray(typeId, displayName, conformIds, nestedDims, elements.length);
        for (int i = 0; i < elements.length; i++) {
            set(array, i, elements[i], null);
        }
        return array;
    }

    /** Copies elements from an array or {@link Iterable} into a new array of {@code componentType}. */
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
        if (Values.toJava(raw, Iterable.class, rt) instanceof Iterable<?> it) {
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
        throw new BabbleException("Type error : expected iterable or array, got " + Types.describe(raw));
    }

    private static Object widenBoxedNumeric(Class<?> componentType, Object value) {
        if (!(value instanceof Number n) || componentType == null || componentType.isInstance(value)) {
            return value;
        }
        if (componentType == Short.class) return n.shortValue();
        if (componentType == Integer.class) return n.intValue();
        if (componentType == Long.class) return n.longValue();
        if (componentType == Float.class) return n.floatValue();
        if (componentType == Double.class) return n.doubleValue();
        return value;
    }

    /** Copies elements from an array or {@link Iterable} into a new interpreted Babble-element array. */
    public static Object fromBabble(String typeId, String displayName, Set<String> conformIds,
                                    int nestedDims, Object source, ScriptRuntime rt) {
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
        if (Values.toJava(raw, Iterable.class, rt) instanceof Iterable<?> it) {
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
        throw new BabbleException("Type error : expected iterable or array, got " + Types.describe(raw));
    }

    // ---------------------------------------------------------------- element access

    public static int size(Object array) {
        return Array.getLength(Values.unwrap(array));
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
        if (stored != null && comp != Object.class && comp != InterpretedInstance.class && !comp.isInstance(stored)) {
            stored = Values.toJava(stored, comp, rt);
        }
        try {
            oa[index] = stored;
        } catch (ArrayStoreException e) {
            if (stored instanceof InterpretedInstance inst) {
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
                throw new ArrayStoreException(actual != null ? actual.name() : value.getClass().getName());
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
            if (valTag.nestedDims() != tag.nestedDims() - 1 || !valTag.conformIds().contains(tag.typeId())) {
                String prefix = "array of ".repeat(valTag.nestedDims() + 1);
                throw new ArrayStoreException(prefix + valTag.displayName());
            }
        }
    }

    // ---------------------------------------------------------------- equality, hash, formatting

    /** Deep structural equality matching {@code Arrays.deepEquals} with Babble's {@code ==} on elements. */
    public static boolean deepEquals(Object a, Object b) {
        Object ua = Values.unwrap(a);
        Object ub = Values.unwrap(b);
        if (ua == ub) {
            return true;
        }
        if (ua == null || ub == null) {
            return false;
        }
        if (ua instanceof Object[] oa && ub instanceof Object[] ob) {
            if (oa.length != ob.length) {
                return false;
            }
            for (int i = 0; i < oa.length; i++) {
                if (!Operators.equal(oa[i], ob[i])) {
                    return false;
                }
            }
            return true;
        }
        if (ua instanceof int[] ia && ub instanceof int[] ib) return Arrays.equals(ia, ib);
        if (ua instanceof double[] da && ub instanceof double[] db) return Arrays.equals(da, db);
        if (ua instanceof long[] la && ub instanceof long[] lb) return Arrays.equals(la, lb);
        if (ua instanceof boolean[] ba && ub instanceof boolean[] bb) return Arrays.equals(ba, bb);
        if (ua instanceof byte[] ya && ub instanceof byte[] yb) return Arrays.equals(ya, yb);
        if (ua instanceof short[] sa && ub instanceof short[] sb) return Arrays.equals(sa, sb);
        if (ua instanceof float[] fa && ub instanceof float[] fb) return Arrays.equals(fa, fb);
        if (ua instanceof char[] ca && ub instanceof char[] cb) return Arrays.equals(ca, cb);
        return false;
    }

    /** Content hash consistent with {@link #deepEquals}. */
    public static int deepHashCode(Object a) {
        Object ua = Values.unwrap(a);
        if (ua == null) {
            return 0;
        }
        if (ua instanceof Object[] oa) {
            int hash = 1;
            for (Object e : oa) {
                hash = 31 * hash + Values.hash(e);
            }
            return hash;
        }
        if (ua instanceof int[] ia) return Arrays.hashCode(ia);
        if (ua instanceof double[] da) return Arrays.hashCode(da);
        if (ua instanceof long[] la) return Arrays.hashCode(la);
        if (ua instanceof boolean[] ba) return Arrays.hashCode(ba);
        if (ua instanceof byte[] ya) return Arrays.hashCode(ya);
        if (ua instanceof short[] sa) return Arrays.hashCode(sa);
        if (ua instanceof float[] fa) return Arrays.hashCode(fa);
        if (ua instanceof char[] ca) return Arrays.hashCode(ca);
        return Values.hash(ua);
    }

    /** Formats an array as {@code [a, b]}, matching {@code Arrays.deepToString} semantics. */
    public static String deepToString(Object a) {
        Object ua = Values.unwrap(a);
        if (ua == null) {
            return "null";
        }
        if (ua instanceof int[] ia) return Arrays.toString(ia);
        if (ua instanceof double[] da) return Arrays.toString(da);
        if (ua instanceof long[] la) return Arrays.toString(la);
        if (ua instanceof boolean[] ba) return Arrays.toString(ba);
        if (ua instanceof byte[] ya) return Arrays.toString(ya);
        if (ua instanceof short[] sa) return Arrays.toString(sa);
        if (ua instanceof float[] fa) return Arrays.toString(fa);
        if (ua instanceof char[] ca) return Arrays.toString(ca);
        if (ua instanceof Object[] oa) {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            StringBuilder sb = new StringBuilder();
            appendDeep(oa, sb, seen);
            return sb.toString();
        }
        return String.valueOf(ua);
    }

    private static void appendDeep(Object[] oa, StringBuilder sb, Set<Object> seen) {
        if (!seen.add(oa)) {
            sb.append("[...]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < oa.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object elem = Values.fromJava(oa[i]);
            if (elem == null) {
                sb.append("null");
            } else if (elem instanceof Object[] nested) {
                appendDeep(nested, sb, seen);
            } else if (elem.getClass().isArray()) {
                sb.append(deepToString(elem));
            } else {
                sb.append(elem);
            }
        }
        sb.append(']');
        seen.remove(oa);
    }

    /** Formats any Babble value for display, printing arrays by contents. */
    public static String format(Object value) {
        Object raw = Values.unwrap(value);
        return raw != null && raw.getClass().isArray() ? deepToString(raw) : String.valueOf(raw);
    }

    /** Formats a host Java {@link Throwable} consistently across modes (stripping Helpful NPE details). */
    public static String formatJavaException(Throwable t) {
        String name = t.getClass().getName();
        String msg = t instanceof NullPointerException ? null : t.getMessage();
        return msg == null ? name : name + " : " + msg;
    }

    /** Whether the top stack frame points to internal Babble compiler/interpreter code (not user throw). */
    private static boolean isInternalEngineFrame(StackTraceElement top) {
        String cls = top.getClassName();
        if (cls.equals("org.babblelang.engine.impl.Interpreter") && "visitThrowExpression".equals(top.getMethodName())) {
            return false;
        }
        if (cls.equals("org.babblelang.compiler.ScriptClassLoader")
                || cls.equals("org.babblelang.compiler.CompositeClassLoader")) {
            return false;
        }
        return cls.startsWith("org.babblelang.engine.impl.")
                && !cls.startsWith("org.babblelang.engine.impl.natives.")
                || cls.startsWith("org.babblelang.analysis.")
                || cls.startsWith("org.babblelang.compiler.")
                || cls.startsWith("org.babblelang.ir.")
                || cls.startsWith("org.babblelang.opt.");
    }

    /** Normalizes uncaught runtime exceptions for script boundaries (BIP028-D3). */
    public static String formatThrowable(Throwable t) {
        if (t instanceof BabbleException) {
            return t.toString();
        }
        if (t instanceof ThrownException te) {
            return BabbleException.class.getName() + ": " + format(te.value());
        }
        if (t instanceof ArrayStoreException) {
            String raw = t.toString();
            Matcher arrMatcher = COMPILED_ARRAY_CLASS.matcher(raw);
            StringBuilder sb = new StringBuilder();
            while (arrMatcher.find()) {
                String prefix = "array of ".repeat(arrMatcher.group(1).length());
                arrMatcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + arrMatcher.group(2)));
            }
            arrMatcher.appendTail(sb);
            return COMPILED_BABBLE_CLASS.matcher(sb.toString()).replaceAll("$1");
        }
        if (t instanceof ArrayIndexOutOfBoundsException || t instanceof NegativeArraySizeException) {
            return t.toString();
        }
        StackTraceElement[] stack = t.getStackTrace();
        if (stack.length > 0 && isInternalEngineFrame(stack[0])) {
            return t.toString();
        }
        return BabbleException.class.getName() + ": " + formatJavaException(t);
    }

    // ---------------------------------------------------------------- dynamic member dispatch

    /** Selects a Babble array method ({@code size}, {@code get}, {@code set}, {@code equals}, {@code hashCode}, {@code toString}) or falls back to {@link JavaObject#member}. */
    public static Object member(Object array, String name) {
        if ("length".equals(name)) {
            throw new BabbleException("No such name in " + Types.describe(array) + " : length (use size())");
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
