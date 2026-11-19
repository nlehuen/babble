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
            double d = number.doubleValue();
            long l = (long) d;
            if (l == d && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                return Long.hashCode(l);
            }
            return Double.hashCode(d);
        }
        return value.hashCode();
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
            return value instanceof BabbleInstance instance && instance.babbleClass() == babbleClass;
        }
        if (type instanceof BabbleInterface babbleInterface) {
            return value instanceof BabbleInstance instance && instance.babbleClass().implementsType(babbleInterface);
        }
        if (type instanceof JavaType javaType) {
            Class<?> java = javaType.javaClass();
            if (value instanceof BabbleInstance instance) {
                return instance.babbleClass().isJava(java);
            }
            if (value instanceof JavaValue wrapper) {
                value = wrapper.javaValue();
            }
            return JavaTypes.box(java).isInstance(value);
        }
        throw new BabbleException("Not a type : " + typeText);
    }

    /** {@code value is type} for a core type name : {@code byte}..{@code string}, {@code any}. */
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
            case "list" -> value instanceof java.util.List;
            case "set" -> value instanceof java.util.Set;
            case "sortedset" -> value instanceof java.util.SortedSet;
            case "map" -> value instanceof java.util.Map;
            case "sortedmap" -> value instanceof java.util.SortedMap;
            case "entry" -> value instanceof java.util.Map.Entry;
            case "string" -> value instanceof String;
            case "any" -> value != null;
            default -> throw new BabbleException("Not a type : " + type);
        };
    }

    /** Converts a number to {@code double} where static typing widened it ({@code int}, {@code long}...). */
    public static Object toDouble(Object value) {
        return value instanceof Integer i ? (Object) (double) i : widen(value, 1 << DOUBLE_RANK);
    }

    private static final int DOUBLE_RANK = 5;

    /**
     * The place of a boxed number in the widening order : {@code Byte} 0, {@code Short} 1,
     * {@code Integer} 2, {@code Long} 3, {@code Float} 4, {@code Double} 5 ; -1 for anything else.
     */
    public static int numericRank(Object value) {
        if (value instanceof Integer) {
            return 2;
        }
        if (value instanceof Double) {
            return 5;
        }
        if (value instanceof Long) {
            return 3;
        }
        if (value instanceof Float) {
            return 4;
        }
        if (value instanceof Short) {
            return 1;
        }
        if (value instanceof Byte) {
            return 0;
        }
        return -1;
    }

    /**
     * Widens a number to the narrowest of the numbers of {@code mask} (bits {@code 1 << rank})
     * at least as wide as it, unless its own is one of them : {@code byte} to {@code int},
     * {@code int} to {@code long} or {@code double}... Anything else is returned as it is.
     */
    public static Object widen(Object value, int mask) {
        if (mask == 0) {
            return value;
        }
        int rank = numericRank(value);
        if (rank < 0 || (mask & (1 << rank)) != 0) {
            return value;
        }
        for (int wider = rank + 1; wider <= DOUBLE_RANK; wider++) {
            if ((mask & (1 << wider)) != 0) {
                return convertNumber((Number) value, wider);
            }
        }
        return value;
    }

    /** A number as the number of a rank ({@link #numericRank}), converted as Java does. */
    public static Object convertNumber(Number value, int rank) {
        return switch (rank) {
            case 0 -> value.byteValue();
            case 1 -> value.shortValue();
            case 2 -> value.intValue();
            case 3 -> value.longValue();
            case 4 -> value.floatValue();
            default -> value.doubleValue();
        };
    }

    /** A function, a bound method or a class value : what converts to a Java functional interface. */
    public static boolean isBabbleCallable(Object value) {
        return value instanceof BabbleFunction || value instanceof BoundMethod
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
        if (value instanceof BabbleInstance instance) {
            return instance.face();
        }
        if (type != null && !type.isInstance(value) && isBabbleCallable(value) && JavaTypes.isFunctionalInterface(type)) {
            return Faces.function((Callable) value, type, rt);
        }
        return value;
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
