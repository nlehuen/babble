package org.babblelang.runtime;


import java.lang.reflect.Proxy;

/**
 * Value-level operations : hashing consistent with {@code ==}, identity, {@code is}, and
 * the conversions of values crossing the boundary with Java.
 */
public final class Values {
    private Values() {
    }

    /**
     * The hash consistent with {@code ==} : integral numbers and doubles with an integral
     * value hash alike, so {@code hash(1) == hash(1.0)}.
     */
    public static int hash(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return Long.hashCode(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return hash(number.doubleValue());
        }
        Object unwrapped = unwrap(value);
        if (unwrapped != null && unwrapped.getClass().isArray()) {
            return ArraySupport.deepHashCode(unwrapped);
        }
        return value.hashCode();
    }

    /** Numeric hash matching {@link #hash(Object)} for unboxed doubles in compiled records. */
    public static int hash(double d) {
        long l = (long) d;
        if (l == d && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
            return Long.hashCode(l);
        }
        return Double.hashCode(d);
    }

    /**
     * Identity, except for numbers, booleans, characters and strings, which have no
     * meaningful identity : for them it is {@code ==}.
     */
    public static boolean same(Object a, Object b) {
        if (a instanceof JavaValue wrapper) {
            a = wrapper.javaValue();
        }
        if (b instanceof JavaValue wrapper) {
            b = wrapper.javaValue();
        }
        if (a == null || b == null) {
            return a == b;
        }
        if (isPlain(a) && isPlain(b)) {
            return Operators.equal(a, b);
        }
        return a == b;
    }

    private static boolean isPlain(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof String;
    }

    /** {@code value is type} : type is a class value, an interface value or a Java class. */
    public static Object is(Object value, Object type, String typeText) {
        if (type instanceof BabbleClass babbleClass) {
            return BabbleObjects.babbleClass(value) == babbleClass;
        }
        if (type instanceof BabbleInterface babbleInterface) {
            BabbleClass cls = BabbleObjects.babbleClass(value);
            return cls != null && cls.implementsType(babbleInterface);
        }
        if (type instanceof JavaType javaType) {
            Class<?> java = javaType.javaClass();
            BabbleClass cls = BabbleObjects.babbleClass(value);
            if (cls != null) {
                return cls.isJava(java);
            }
            if (value instanceof JavaValue wrapper) {
                value = wrapper.javaValue();
            }
            return JavaTypes.box(java).isInstance(value);
        }
        throw new BabbleException("Not a type : " + typeText);
    }

    /** {@code value is type} for a built-in type name. */
    public static Object isBuiltin(Object value, String type) {
        if (value instanceof JavaValue wrapper) {
            value = wrapper.javaValue();
        }
        return switch (type) {
            case "byte" -> value instanceof Byte;
            case "short" -> value instanceof Short;
            case "int" -> value instanceof Integer;
            case "long" -> value instanceof Long;
            case "float" -> value instanceof Float;
            case "double" -> value instanceof Double;
            case "bool" -> value instanceof Boolean;
            case "char" -> value instanceof Character;
            case "string" -> value instanceof String;
            case "any" -> value != null;
            case "list" -> value instanceof java.util.List;
            case "set" -> value instanceof java.util.Set;
            case "sortedset" -> value instanceof java.util.SortedSet;
            case "map" -> value instanceof java.util.Map;
            case "sortedmap" -> value instanceof java.util.SortedMap;
            case "entry" -> value instanceof java.util.Map.Entry;
            case "range" -> value instanceof Range;
            case "exception" -> value instanceof BabbleException;
            default -> throw new BabbleException("Not a type : " + type);
        };
    }

    /** Converts a widened numeric value to {@code double} when static inference widened it. */
    public static Object toDouble(Object value) {
        return value instanceof Number n && !(value instanceof Double) ? n.doubleValue() : value;
    }

    /** Converts a widened numeric value to {@code short}, {@code int}, {@code long}, {@code float}, or {@code double}. */
    public static Object widenNumeric(Object value, int targetRank) {
        if (!(value instanceof Number n)) {
            return value;
        }
        return switch (targetRank) {
            case 1 -> value instanceof Short ? value : n.shortValue();
            case 2 -> value instanceof Integer ? value : n.intValue();
            case 3 -> value instanceof Long ? value : n.longValue();
            case 4 -> value instanceof Float ? value : n.floatValue();
            case 5 -> value instanceof Double ? value : n.doubleValue();
            default -> value;
        };
    }

    /** A function, a bound method or a class value : what converts to a Java functional interface. */
    public static boolean isBabbleCallable(Object value) {
        return value instanceof BabbleFunction || value instanceof BoundMethod
                || value instanceof OverloadSet
                || value instanceof BabbleClass babbleClass && babbleClass.shape().kind() != ClassShape.Kind.OBJECT;
    }

    /**
     * A value going to Java, where {@code type} is expected : wrappers are unwrapped, a
     * Babble instance becomes its face, and a Babble function becomes an implementation
     * of an expected functional interface.
     */
    public static Object toJava(Object value, Class<?> type, ScriptRuntime rt) {
        if (value instanceof JavaValue wrapper) {
            return wrapper.javaValue();
        }
        if (BabbleObjects.isInstance(value)) {
            return BabbleObjects.face(value);
        }
        if (type != null && !type.isInstance(value) && isBabbleCallable(value) && JavaTypes.isFunctionalInterface(type)) {
            return Faces.function((Callable) value, type, rt);
        }
        return value;
    }

    /** Eagerly adapts a Babble callable to a Java functional interface plus optional marker interfaces. */
    public static Object toJavaSam(Object value, Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        if (value != null && isBabbleCallable(value)) {
            return Faces.function((Callable) value, samInterface, markers, rt);
        }
        return value;
    }

    /** Name-based {@link #toJavaSam} for non-public Java interfaces where {@code LDC} cannot reference the class. */
    public static Object toJavaSamByName(Object value, String samName, String[] markerNames, ScriptRuntime rt) {
        if (value == null || !isBabbleCallable(value)) {
            return value;
        }
        ClassLoader cl = Values.class.getClassLoader();
        try {
            Class<?> sam = Class.forName(samName, false, cl);
            Class<?>[] markers = null;
            if (markerNames != null && markerNames.length > 0) {
                markers = new Class<?>[markerNames.length];
                for (int i = 0; i < markerNames.length; i++) {
                    markers[i] = Class.forName(markerNames[i], false, cl);
                }
            }
            return Faces.function((Callable) value, sam, markers, rt);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown Java interface : " + samName, e);
        }
    }

    /** Unwraps a {@link JavaValue} wrapper to its underlying Java object, if wrapped. */
    public static Object unwrap(Object value) {
        return value instanceof JavaValue wrapper ? wrapper.javaValue() : value;
    }

    public static Object[] toJava(Object[] values, Class<?>[] types, ScriptRuntime rt) {
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = toJava(values[i], types[i], rt);
        }
        return result;
    }

    /** A value coming back from Java : the face of a Babble instance is the instance again. */
    public static Object fromJava(Object value) {
        if (value instanceof Proxy && Proxy.getInvocationHandler(value) instanceof Faces.FaceHandler face) {
            return face.instance();
        }
        return value;
    }

    public static Object[] fromJava(Object[] values) {
        if (values == null) {
            return Arguments.NONE;
        }
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = fromJava(values[i]);
        }
        return result;
    }

    /** Adapts an array, {@link java.util.Iterator}, or {@link Iterable} to a {@link java.util.Iterator} for dynamic {@code for} loops. */
    public static java.util.Iterator<?> asIterator(Object value) {
        Object raw = unwrap(value);
        if (raw.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(raw);
            return new java.util.Iterator<Object>() {
                private int index = 0;

                @Override
                public boolean hasNext() {
                    return index < len;
                }

                @Override
                public Object next() {
                    return java.lang.reflect.Array.get(raw, index++);
                }
            };
        }
        if (raw instanceof java.util.Iterator<?> it) {
            return it;
        }
        return ((Iterable<?>) raw).iterator();
    }

    /** The arity of a Babble callable, as {min, max}, or null when it is not known. */
    static int[] arity(Callable callable) {
        if (callable instanceof BabbleFunction function && function.minArity() >= 0) {
            return new int[]{function.minArity(), function.maxArity()};
        }
        if (callable instanceof BoundMethod bound) {
            return arity(bound.function());
        }
        if (callable instanceof BabbleClass babbleClass) {
            return new int[]{babbleClass.shape().requiredParameters(), babbleClass.shape().parameters().length};
        }
        return null;
    }
}
