package org.babblelang.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Runtime view of Babble instances across both modes : {@link InterpretedInstance} for
 * the interpreter, and plain compiled classes whose {@code $babble$*} metadata hooks are
 * cached once per class via {@link ClassValue} (BIP020-D18).
 */
public final class BabbleObjects {
    private static final CallSite TO_STRING = new CallSite("<toString>", -1, "toString", new String[0]);

    private record CompiledInfo(
            MethodHandle babbleClass,
            MethodHandle runtime,
            MethodHandle field,
            MethodHandle setField,
            MethodHandle isDefined,
            MethodHandle lookup
    ) {
    }

    private static final ClassValue<CompiledInfo> COMPILED = new ClassValue<>() {
        @Override
        protected CompiledInfo computeValue(Class<?> type) {
            if (type.isPrimitive() || type.isArray() || type.isInterface() || !Modifier.isPublic(type.getModifiers())) {
                return null;
            }
            Field clsField;
            try {
                clsField = type.getDeclaredField("$babble$class");
            } catch (NoSuchFieldException e) {
                return null;
            }
            if (!Modifier.isPublic(clsField.getModifiers()) || clsField.getType() != BabbleClass.class) {
                return null;
            }
            MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
            try {
                MethodHandle babbleClass = publicLookup.findGetter(type, "$babble$class", BabbleClass.class)
                        .asType(MethodType.methodType(BabbleClass.class, Object.class));
                MethodHandle runtime = publicLookup.findGetter(type, "$babble$rt", ScriptRuntime.class)
                        .asType(MethodType.methodType(ScriptRuntime.class, Object.class));
                MethodHandle field = publicLookup.findVirtual(type, "$babble$field", MethodType.methodType(Object.class, int.class))
                        .asType(MethodType.methodType(Object.class, Object.class, int.class));
                MethodHandle setField = publicLookup.findVirtual(type, "$babble$setField", MethodType.methodType(void.class, int.class, Object.class))
                        .asType(MethodType.methodType(void.class, Object.class, int.class, Object.class));
                MethodHandle isDefined = findOptionalVirtual(publicLookup, type, "$babble$isDefined",
                        MethodType.methodType(boolean.class, int.class),
                        MethodType.methodType(boolean.class, Object.class, int.class));
                MethodHandle lookup = findOptionalVirtual(publicLookup, type, "$babble$lookup",
                        MethodType.methodType(MethodHandles.Lookup.class),
                        MethodType.methodType(MethodHandles.Lookup.class, Object.class));
                return new CompiledInfo(babbleClass, runtime, field, setField, isDefined, lookup);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Malformed compiled Babble class : " + type.getName(), e);
            }
        }
    };

    private BabbleObjects() {
    }

    private static MethodHandle findOptionalVirtual(MethodHandles.Lookup publicLookup, Class<?> type,
                                                      String name, MethodType exactType, MethodType adaptedType)
            throws IllegalAccessException {
        try {
            return publicLookup.findVirtual(type, name, exactType).asType(adaptedType);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static CompiledInfo compiledInfo(Object value) {
        return value != null && !(value instanceof InterpretedInstance) ? COMPILED.get(value.getClass()) : null;
    }

    private static BabbleClass babbleClass(Object instance, CompiledInfo info) {
        try {
            return (BabbleClass) info.babbleClass.invokeExact(instance);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static ScriptRuntime runtime(Object instance, CompiledInfo info) {
        try {
            return (ScriptRuntime) info.runtime.invokeExact(instance);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static Object field(CompiledInfo info, Object instance, int index) {
        try {
            return info.field.invokeExact(instance, index);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void setField(CompiledInfo info, Object instance, int index, Object value) {
        try {
            info.setField.invokeExact(instance, index, value);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static BabbleFunction method(Object instance, CompiledInfo info, BabbleClass babbleClass, String name) {
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index >= 0) {
            return shape.isMethod(index) && field(info, instance, index) instanceof BabbleFunction function ? function : null;
        }
        return babbleClass.defaultMethod(name);
    }

    /** Whether {@code value} is an instance of a Babble class, record, or object (either mode). */
    public static boolean isInstance(Object value) {
        return value instanceof InterpretedInstance || isCompiledInstance(value);
    }

    /** Whether {@code value} is a plain compiled Babble instance. */
    public static boolean isCompiledInstance(Object value) {
        return compiledInfo(value) != null;
    }

    /** The {@link BabbleClass} that created {@code value}, or null if {@code value} is not a Babble instance. */
    public static BabbleClass babbleClass(Object value) {
        if (value instanceof InterpretedInstance interpreted) {
            return interpreted.babbleClass();
        }
        CompiledInfo info = compiledInfo(value);
        return info != null ? babbleClass(value, info) : null;
    }

    /** The {@link ScriptRuntime} of the eval that created {@code value}, or null. */
    public static ScriptRuntime runtime(Object value) {
        if (value instanceof InterpretedInstance interpreted) {
            return interpreted.runtime();
        }
        CompiledInfo info = compiledInfo(value);
        return info != null ? runtime(value, info) : null;
    }

    public static Object getMember(Object instance, String name, boolean own) {
        if (instance instanceof InterpretedInstance interpreted) {
            return interpreted.getMember(name, own);
        }
        CompiledInfo info = compiledInfo(instance);
        if (info == null) {
            throw Members.noSuchMember(name);
        }
        BabbleClass babbleClass = babbleClass(instance, info);
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index < 0) {
            BabbleFunction inherited = babbleClass.defaultMethod(name);
            if (inherited != null) {
                return new BoundMethod(inherited, instance);
            }
            if (!babbleClass.javaDefaultMethods(name).isEmpty()) {
                return new JavaDefaultCall(instance, babbleClass.javaDefaultMethods(name), babbleClass.name() + "." + name);
            }
            throw Members.noSuchMember(name);
        }
        if (shape.isPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        Object value = field(info, instance, index);
        if (value == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        if (shape.isMethod(index)) {
            return new BoundMethod((BabbleFunction) value, instance);
        }
        return value;
    }

    public static void setMember(Object instance, String name, Object value, boolean own) {
        if (instance instanceof InterpretedInstance interpreted) {
            interpreted.setMember(name, value, own);
            return;
        }
        CompiledInfo info = compiledInfo(instance);
        if (info == null) {
            throw Members.noSuchMember(name);
        }
        BabbleClass babbleClass = babbleClass(instance, info);
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index < 0) {
            if (babbleClass.defaultMethod(name) != null || !babbleClass.javaDefaultMethods(name).isEmpty()) {
                throw Members.cannotAssignMethod(name);
            }
            throw Members.noSuchMember(name);
        }
        if (shape.isPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        if (shape.isMethod(index)) {
            throw Members.cannotAssignMethod(name);
        }
        if (shape.isValue()) {
            throw Members.readOnly(name);
        }
        if (field(info, instance, index) == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        setField(info, instance, index, Types.check(value, shape.memberType(index)));
    }

    static BabbleFunction method(Object instance, String name) {
        if (instance instanceof InterpretedInstance interpreted) {
            return interpreted.method(name);
        }
        CompiledInfo info = compiledInfo(instance);
        return info != null ? method(instance, info, babbleClass(instance, info), name) : null;
    }

    /** Whether a compiled primitive member has been defined. */
    public static boolean isMemberDefined(Object instance, int index) {
        CompiledInfo info = compiledInfo(instance);
        if (info != null && info.isDefined != null) {
            try {
                return (boolean) info.isDefined.invokeExact(instance, index);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        return true;
    }

    static MethodHandles.Lookup javaDefaultLookup(Object instance) {
        CompiledInfo info = compiledInfo(instance);
        if (info != null && info.lookup != null) {
            try {
                return (MethodHandles.Lookup) info.lookup.invokeExact(instance);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        return null;
    }

    /** What Java sees of a Babble instance : itself if compiled and implementing all interfaces, else a proxy. */
    public static Object face(Object instance) {
        if (instance instanceof InterpretedInstance interpreted) {
            return interpreted.face();
        }
        BabbleClass cls = babbleClass(instance);
        if (cls == null) {
            return instance;
        }
        Class<?>[] interfaces = cls.javaInterfaces();
        if (interfaces.length == 0) {
            return instance;
        }
        for (Class<?> iface : interfaces) {
            if (!iface.isInstance(instance)) {
                return Faces.create(instance);
            }
        }
        return instance;
    }

    /** Generated {@code toString()} target for plain compiled classes. */
    public static String toString(Object instance) {
        if (instance instanceof InterpretedInstance interpreted) {
            return interpreted.toString();
        }
        CompiledInfo info = compiledInfo(instance);
        if (info == null) {
            return String.valueOf(instance);
        }
        BabbleClass babbleClass = babbleClass(instance, info);
        BabbleFunction toString = method(instance, info, babbleClass, "toString");
        if (toString != null && toString.minArity() == 0) {
            return String.valueOf(toString.invoke(instance, runtime(instance, info), Arguments.NONE, null, TO_STRING));
        }
        ClassShape shape = babbleClass.shape();
        if (!shape.isValue()) {
            return babbleClass.name() + "@" + Integer.toHexString(System.identityHashCode(instance));
        }
        StringBuilder result = new StringBuilder(babbleClass.name()).append('(');
        boolean first = true;
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!shape.isMethod(i) && !shape.isPrivate(i)) {
                if (!first) {
                    result.append(", ");
                }
                result.append(shape.member(i)).append(": ").append(field(info, instance, i));
                first = false;
            }
        }
        return result.append(')').toString();
    }
}
