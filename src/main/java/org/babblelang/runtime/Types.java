package org.babblelang.runtime;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime side of declared types (T9, docs/design/bip003_type-system.md) : wherever a value
 * meets a declared type (an argument, a result, a definition or an assignment), both
 * modes check it here, and fail the same way : {@code Type error : expected int, got string}.
 * <p>
 * The analysis describes each declared type with a string, {@code kind|display|payload} :
 * <ul>
 * <li>{@code int}, {@code double}, {@code bool}, {@code string} : the built-in types ;</li>
 * <li>{@code fn} : a function type, which any callable value satisfies ;</li>
 * <li>{@code java}, with the Java class's name ;</li>
 * <li>{@code babble}, with the id of the class or interface expression (nominal) ;</li>
 * <li>{@code union} and {@code inter}, with their members' encoded descriptors (BIP022).</li>
 * </ul>
 * {@code display} is how the type reads in the message. Types that need no check
 * ({@code any}) have no descriptor.
 */
public final class Types {
    private static final Map<String, Type> PARSED = new ConcurrentHashMap<>();

    private Types() {
    }

    /** A declared type, ready to check values against. */
    public abstract static sealed class Type permits Builtin, FunctionType, JavaClassType, NominalType, NullType, AnyType, UnionType, IntersectionType {
        private final String display;

        Type(String display) {
            this.display = display;
        }

        /** Whether a value has the type (allowing numeric widening and SAM conversion, for casts/annotations). */
        abstract boolean accepts(Object value);

        /** Exact runtime type test for {@code is} (no numeric widening, no SAM conversion). */
        boolean isInstance(Object value) {
            return accepts(value);
        }

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
            return value != null && (javaClass.isInstance(value) || JavaTypes.isWidening(value.getClass(), javaClass));
        }

        @Override
        boolean isInstance(Object value) {
            Object java = value instanceof JavaValue wrapper ? wrapper.javaValue() : value;
            return java != null && javaClass.isInstance(java);
        }

        @Override
        Object convert(Object value) {
            return value != null && !javaClass.isInstance(value) && JavaTypes.isWidening(value.getClass(), javaClass)
                    ? JavaTypes.widen(value, javaClass) : value;
        }
    }

    static final class FunctionType extends Type {
        FunctionType(String display) {
            super(display);
        }

        @Override
        boolean accepts(Object value) {
            return value instanceof Callable;
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
            if (java == null) {
                return false;
            }
            BabbleClass cls = BabbleObjects.babbleClass(java);
            return javaClass.isInstance(java)
                    || cls != null && cls.isJava(javaClass)
                    || Values.isBabbleCallable(java) && JavaTypes.isFunctionalInterface(javaClass);
        }

        @Override
        boolean isInstance(Object value) {
            Object java = value instanceof JavaValue wrapper ? wrapper.javaValue() : value;
            if (java == null) {
                return false;
            }
            BabbleClass cls = BabbleObjects.babbleClass(java);
            return JavaTypes.box(javaClass).isInstance(java)
                    || cls != null && cls.isJava(javaClass);
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
            BabbleClass cls = BabbleObjects.babbleClass(value);
            return cls != null && cls.conformsTo(id);
        }
    }

    static final class NullType extends Type {
        NullType(String display) {
            super(display);
        }

        @Override
        boolean accepts(Object value) {
            return value == null;
        }
    }

    static final class AnyType extends Type {
        AnyType(String display) {
            super(display);
        }

        @Override
        boolean accepts(Object value) {
            return value != null;
        }
    }

    static final class UnionType extends Type {
        private final Type[] members;

        UnionType(String display, Type[] members) {
            super(display);
            this.members = members;
        }

        @Override
        boolean accepts(Object value) {
            for (Type member : members) {
                if (member.accepts(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        boolean isInstance(Object value) {
            for (Type member : members) {
                if (member.isInstance(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        Object convert(Object value) {
            Type fallback = null;
            for (Type member : members) {
                if (member.accepts(value)) {
                    Object converted = member.convert(value);
                    if (converted == value) {
                        return converted;
                    }
                    if (fallback == null) {
                        fallback = member;
                    }
                }
            }
            return fallback != null ? fallback.convert(value) : value;
        }
    }

    /** An intersection of interfaces (BIP022-D6) : a value has it when it has every member. */
    static final class IntersectionType extends Type {
        private final Type[] members;

        IntersectionType(String display, Type[] members) {
            super(display);
            this.members = members;
        }

        @Override
        boolean accepts(Object value) {
            for (Type member : members) {
                if (!member.accepts(value)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        boolean isInstance(Object value) {
            for (Type member : members) {
                if (!member.isInstance(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Encodes a sub-descriptor so it contains neither {@code ','} nor {@code '|'}. */
    public static String encodeComponent(String component) {
        return component.replace("%", "%25").replace(",", "%2C").replace("|", "%7C");
    }

    static String decodeComponent(String component) {
        StringBuilder result = new StringBuilder(component.length());
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            if (c == '%' && i + 2 < component.length()) {
                String hex = component.substring(i + 1, i + 3);
                switch (hex) {
                    case "25" -> { result.append('%'); i += 2; continue; }
                    case "2C" -> { result.append(','); i += 2; continue; }
                    case "7C" -> { result.append('|'); i += 2; continue; }
                    default -> {}
                }
            }
            result.append(c);
        }
        return result.toString();
    }

    /** The type a descriptor describes, parsed once. */
    public static Type parse(String descriptor) {
        Type cached = PARSED.get(descriptor);
        if (cached != null) {
            return cached;
        }
        Type parsed = doParse(descriptor);
        Type existing = PARSED.putIfAbsent(descriptor, parsed);
        return existing != null ? existing : parsed;
    }

    private static Type doParse(String descriptor) {
        int first = descriptor.indexOf('|');
        int last = descriptor.lastIndexOf('|');
        if (first < 0 || first == last) {
            throw new IllegalArgumentException("Bad type descriptor : " + descriptor);
        }
        String kind = descriptor.substring(0, first);
        String display = descriptor.substring(first + 1, last);
        String payload = descriptor.substring(last + 1);
        return switch (kind) {
            case "byte" -> new Builtin(display, Byte.class);
            case "short" -> new Builtin(display, Short.class);
            case "int" -> new Builtin(display, Integer.class);
            case "long" -> new Builtin(display, Long.class);
            case "float" -> new Builtin(display, Float.class);
            case "double" -> new Builtin(display, Double.class);
            case "bool" -> new Builtin(display, Boolean.class);
            case "char" -> new Builtin(display, Character.class);
            case "string" -> new Builtin(display, String.class);
            case "null" -> new NullType(display);
            case "any" -> new AnyType(display);
            case "fn" -> new FunctionType(display);
            case "java" -> new JavaClassType(display, javaClass(payload));
            case "babble" -> new NominalType(display, payload);
            case "union" -> {
                String[] encoded = payload.split(",", -1);
                Type[] members = new Type[encoded.length];
                for (int i = 0; i < encoded.length; i++) {
                    members[i] = parse(decodeComponent(encoded[i]));
                }
                yield new UnionType(display, members);
            }
            case "inter" -> {
                String[] encoded = payload.split(",", -1);
                Type[] members = new Type[encoded.length];
                for (int i = 0; i < encoded.length; i++) {
                    members[i] = parse(decodeComponent(encoded[i]));
                }
                yield new IntersectionType(display, members);
            }
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
            String got = describe(value);
            BabbleClass cls = type instanceof NominalType && got.equals(type.toString()) ? BabbleObjects.babbleClass(value) : null;
            if (type instanceof NominalType nominal && cls != null) {
                // Two classes of one name, as two modules may define : their ids tell which.
                got += " (expected " + nominal.id + ", got " + cls.shape().id() + ")";
            }
            throw new BabbleException("Type error : expected " + type + ", got " + got);
        }
        return type.convert(value);
    }

    /** The same, by descriptor : no check for a null one. */
    public static Object check(Object value, String descriptor) {
        return descriptor == null ? value : check(value, parse(descriptor));
    }

    /** Exact runtime type test for {@code is} : null descriptor means {@code any?}, matching everything. */
    public static boolean matches(Object value, String descriptor) {
        return descriptor == null || parse(descriptor).isInstance(value);
    }

    /** What a value is, in the words of the type checker. */
    public static String describe(Object value) {
        if (value instanceof JavaValue wrapper) {
            value = wrapper.javaValue();
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof Byte) {
            return "byte";
        }
        if (value instanceof Short) {
            return "short";
        }
        if (value instanceof Integer) {
            return "int";
        }
        if (value instanceof Long) {
            return "long";
        }
        if (value instanceof Float) {
            return "float";
        }
        if (value instanceof Double) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Character) {
            return "char";
        }
        if (value instanceof String) {
            return "string";
        }
        BabbleClass cls = BabbleObjects.babbleClass(value);
        if (cls != null) {
            return cls.name();
        }
        if (value instanceof BabbleType type) {
            return type.toString();
        }
        if (value instanceof Callable) {
            return "function";
        }
        if (value.getClass().isArray()) {
            return describeArray(value.getClass());
        }
        return value.getClass().getName();
    }

    private static final Map<Class<?>, String> ARRAY_COMPONENT_NAMES = Map.ofEntries(
            Map.entry(byte.class, "byte"),
            Map.entry(short.class, "short"),
            Map.entry(int.class, "int"),
            Map.entry(long.class, "long"),
            Map.entry(float.class, "float"),
            Map.entry(double.class, "double"),
            Map.entry(boolean.class, "bool"),
            Map.entry(char.class, "char"),
            Map.entry(Byte.class, "byte?"),
            Map.entry(Short.class, "short?"),
            Map.entry(Integer.class, "int?"),
            Map.entry(Long.class, "long?"),
            Map.entry(Float.class, "float?"),
            Map.entry(Double.class, "double?"),
            Map.entry(Boolean.class, "bool?"),
            Map.entry(Character.class, "char?"),
            Map.entry(String.class, "string"),
            Map.entry(Object.class, "any"),
            Map.entry(InterpretedInstance.class, "any")
    );

    private static String describeArray(Class<?> arrayClass) {
        Class<?> comp = arrayClass.getComponentType();
        if (comp.isArray()) {
            return "array of (" + describeArray(comp) + ")";
        }
        return "array of " + describeArrayComponent(comp);
    }

    private static String describeArrayComponent(Class<?> comp) {
        String named = ARRAY_COMPONENT_NAMES.get(comp);
        if (named != null) {
            return named;
        }
        if (BabbleObjects.isCompiledClass(comp)) {
            return "any";
        }
        String canonical = comp.getCanonicalName();
        return canonical != null ? canonical : comp.getName();
    }

    /**
     * Whether a non-null Java parameter of erased class {@code javaParam} on a {@code default}
     * Java interface method can match a Babble parameter with runtime check {@code descriptor}
     * (so e.g. {@code Collection.toArray(IntFunction)} stays on Java's {@code default} overload
     * when Babble implements {@code toArray(array of ...)}).
     */
    public static boolean canAcceptJavaParamClass(String descriptor, Class<?> javaParam) {
        return descriptor == null || javaParam == Object.class || canAcceptJavaType(parse(descriptor), javaParam);
    }

    private static boolean canAcceptJavaType(Type type, Class<?> javaParam) {
        Class<?> boxed = JavaTypes.box(javaParam);
        return switch (type) {
            case AnyType ignored -> true;
            case NullType ignored -> !javaParam.isPrimitive();
            case UnionType u -> java.util.Arrays.stream(u.members).anyMatch(m -> canAcceptJavaType(m, javaParam));
            case IntersectionType inter -> java.util.Arrays.stream(inter.members).allMatch(m -> canAcceptJavaType(m, javaParam));
            case Builtin b -> b.javaClass.isAssignableFrom(boxed) || JavaTypes.isWidening(boxed, b.javaClass);
            case FunctionType ignored -> Callable.class.isAssignableFrom(boxed) || boxed.isAssignableFrom(Callable.class);
            case NominalType ignored -> !javaParam.isPrimitive() && !javaParam.isArray() && boxed.isInterface();
            case JavaClassType j -> j.javaClass.isAssignableFrom(boxed) || boxed.isAssignableFrom(j.javaClass)
                    || (boxed.isInterface() && j.javaClass.isInterface());
        };
    }
}
