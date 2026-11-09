package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime side of declared types (T9, docs/design/type-system.md) : in {@code error}
 * mode, wherever a value meets a declared type (an argument, a result, a definition or an
 * assignment), both modes check it here, and fail the same way :
 * {@code Type error : expected int, got string}.
 * <p>
 * The analysis describes each declared type with a string, {@code kind|display|payload} :
 * <ul>
 * <li>{@code int}, {@code double}, {@code bool}, {@code string} : the built-in types ;</li>
 * <li>{@code fn} : a function type, which any callable value satisfies ;</li>
 * <li>{@code java}, with the Java class's name ;</li>
 * <li>{@code babble}, with the id of the class or interface expression (nominal).</li>
 * </ul>
 * {@code display} is how the type reads in the message. Types that need no check
 * ({@code dynamic}) have no descriptor.
 */
public final class Types {
    private static final Map<String, Type> PARSED = new ConcurrentHashMap<>();

    private Types() {
    }

    /** A declared type, ready to check values against. */
    public abstract static sealed class Type permits Builtin, FunctionType, JavaClassType, NominalType {
        private final String display;

        Type(String display) {
            this.display = display;
        }

        /** Whether a value has the type. */
        abstract boolean accepts(Object value);

        /** The value to store : the value itself, or an int widened to a double. */
        Object convert(Object value) {
            return value;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    static final class Builtin extends Type {
        private final Class<?> javaClass;

        Builtin(String display, Class<?> javaClass) {
            super(display);
            this.javaClass = javaClass;
        }

        @Override
        boolean accepts(Object value) {
            // T2 : numbers and booleans are never null, strings may be.
            if (value == null) {
                return javaClass == String.class;
            }
            return javaClass.isInstance(value) || javaClass == Double.class && value instanceof Integer;
        }

        @Override
        Object convert(Object value) {
            return javaClass == Double.class && value instanceof Integer i ? (Object) i.doubleValue() : value;
        }
    }

    static final class FunctionType extends Type {
        FunctionType(String display) {
            super(display);
        }

        @Override
        boolean accepts(Object value) {
            return value == null || value instanceof Callable;
        }
    }

    static final class JavaClassType extends Type {
        private final Class<?> javaClass;

        JavaClassType(String display, Class<?> javaClass) {
            super(display);
            this.javaClass = javaClass;
        }

        @Override
        boolean accepts(Object value) {
            Object java = value instanceof JavaValue wrapper ? wrapper.javaValue() : value;
            return java == null || javaClass.isInstance(java)
                    || java instanceof BabbleInstance instance && instance.babbleClass().isJava(javaClass)
                    || Values.isBabbleCallable(java) && JavaTypes.isFunctionalInterface(javaClass);
        }
    }

    static final class NominalType extends Type {
        private final String id;

        NominalType(String display, String id) {
            super(display);
            this.id = id;
        }

        @Override
        boolean accepts(Object value) {
            return value == null || value instanceof BabbleInstance instance && instance.babbleClass().conformsTo(id);
        }
    }

    /** The type a descriptor describes, parsed once. */
    public static Type parse(String descriptor) {
        return PARSED.computeIfAbsent(descriptor, Types::doParse);
    }

    private static Type doParse(String descriptor) {
        String[] parts = descriptor.split("\\|", 3);
        String display = parts[1];
        return switch (parts[0]) {
            case "int" -> new Builtin(display, Integer.class);
            case "double" -> new Builtin(display, Double.class);
            case "bool" -> new Builtin(display, Boolean.class);
            case "string" -> new Builtin(display, String.class);
            case "fn" -> new FunctionType(display);
            case "java" -> new JavaClassType(display, javaClass(parts[2]));
            case "babble" -> new NominalType(display, parts[2]);
            default -> throw new IllegalArgumentException("Bad type descriptor : " + descriptor);
        };
    }

    private static Class<?> javaClass(String name) {
        try {
            return Class.forName(name, false, Types.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown class in a type descriptor : " + name, e);
        }
    }

    /** Checks a value against a declared type, and returns what to store. */
    public static Object check(Object value, Type type) {
        if (!type.accepts(value)) {
            throw new BabbleException("Type error : expected " + type + ", got " + describe(value));
        }
        return type.convert(value);
    }

    /** The same, by descriptor : no check for a null one. */
    public static Object check(Object value, String descriptor) {
        return descriptor == null ? value : check(value, parse(descriptor));
    }

    /** What a value is, in the words of the type checker. */
    public static String describe(Object value) {
        if (value instanceof JavaValue wrapper) {
            value = wrapper.javaValue();
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof Integer) {
            return "int";
        }
        if (value instanceof Double) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof BabbleInstance instance) {
            return instance.babbleClass().name();
        }
        if (value instanceof BabbleType type) {
            return type.toString();
        }
        if (value instanceof Callable) {
            return "function";
        }
        return value.getClass().getName();
    }
}
