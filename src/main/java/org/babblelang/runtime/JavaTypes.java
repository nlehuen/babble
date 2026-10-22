package org.babblelang.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facts about Java types that the interop needs.
 */
public final class JavaTypes {
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            void.class, Void.class);

    /** Widening primitive conversions (JLS 5.1.2), expressed over the wrapper types. */
    private static final Map<Class<?>, List<Class<?>>> WIDENINGS = Map.of(
            Byte.class, List.of(Short.class, Integer.class, Long.class, Float.class, Double.class),
            Short.class, List.of(Integer.class, Long.class, Float.class, Double.class),
            Character.class, List.of(Integer.class, Long.class, Float.class, Double.class),
            Integer.class, List.of(Long.class, Float.class, Double.class),
            Long.class, List.of(Float.class, Double.class),
            Float.class, List.of(Double.class));

    private static final Map<Class<?>, Method> FUNCTIONAL_METHODS = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private JavaTypes() {
    }

    public static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? BOXES.get(type) : type;
    }

    public static boolean isWidening(Class<?> from, Class<?> to) {
        return WIDENINGS.getOrDefault(from, List.of()).contains(to);
    }

    public static String describe(Object value) {
        if (value instanceof JavaValue wrapper) {
            value = wrapper.javaValue();
        }
        // An instance by its Babble class : its JVM class depends on the mode.
        if (value instanceof BabbleInstance instance) {
            return instance.babbleClass().name();
        }
        return value.getClass().getCanonicalName();
    }

    /**
     * The public methods of Object, which an interface may redeclare without them being
     * abstract methods an implementation has to provide.
     */
    public static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * The abstract methods of an interface that an implementation has to provide :
     * neither default, nor static, nor a public method of Object.
     */
    public static List<Method> abstractMethods(Class<?> type) {
        List<Method> result = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !isObjectMethod(method)) {
                result.add(method);
            }
        }
        return result;
    }

    /** The single abstract method of a functional interface, or null. */
    public static Method functionalMethod(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        Method method = FUNCTIONAL_METHODS.computeIfAbsent(type, t -> {
            List<Method> methods = abstractMethods(t);
            // The same method can be inherited through several superinterfaces.
            Method single = null;
            for (Method m : methods) {
                if (single == null) {
                    single = m;
                } else if (!(single.getName().equals(m.getName()) && single.getParameterCount() == m.getParameterCount())) {
                    return NONE;
                }
            }
            return single == null ? NONE : single;
        });
        return method == NONE ? null : method;
    }

    public static boolean isFunctionalInterface(Class<?> type) {
        return functionalMethod(type) != null;
    }

    /**
     * Converts a value returned by Babble code to what a Java method declares it returns.
     *
     * @param description what is being returned from, for the error message
     */
    public static Object convertResult(Object result, Class<?> type, String description, ScriptRuntime rt) {
        if (type == void.class) {
            return null;
        }
        Object value = Values.toJava(result, type, rt);
        if (type.isPrimitive()) {
            Class<?> boxed = box(type);
            if (boxed.isInstance(value)) {
                return value;
            }
            if (value != null && isWidening(value.getClass(), boxed)) {
                return widen(value, boxed);
            }
        } else if (value == null || type.isInstance(value)) {
            return value;
        }
        throw new org.babblelang.engine.BabbleException("Type error : " + description + " must return " + type.getSimpleName()
                + ", got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static Object widen(Object value, Class<?> to) {
        if (value instanceof Character c) {
            value = (int) c;
        }
        Number n = (Number) value;
        if (to == Short.class) {
            return n.shortValue();
        } else if (to == Integer.class) {
            return n.intValue();
        } else if (to == Long.class) {
            return n.longValue();
        } else if (to == Float.class) {
            return n.floatValue();
        } else {
            return n.doubleValue();
        }
    }

    /** {@code Appendable.append(CharSequence, int, int)} */
    public static String signature(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getSimpleName()).append('.').append(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(parameters[i].getSimpleName());
        }
        return result.append(')').toString();
    }
}
