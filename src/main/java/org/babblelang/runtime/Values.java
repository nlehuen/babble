package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

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

    public static Object[] toJava(Object[] values, Class<?>[] types, ScriptRuntime rt) {
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = toJava(values[i], types[i], rt);
        }
        return result;
    }

    /** A value coming back from Java : the face of a Babble instance is the instance again. */
    public static Object fromJava(Object value) {
        if (value != null && Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof Faces.FaceHandler face) {
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
