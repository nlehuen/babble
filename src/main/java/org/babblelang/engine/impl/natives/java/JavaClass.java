package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.Scope;
import org.babblelang.runtime.Slot;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaType;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Overloads;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Java class as a Babble value : calling it constructs an instance, and its members are its
 * public member classes, methods and static fields, looked up in that order. A static
 * final field is a constant ; another static field is read each time it is selected. Its
 * instances also have its public instance fields, after its methods ({@link #value}).
 */
public class JavaClass implements Scope<Object>, Callable, JavaType {
    private static final Map<Class<?>, JavaClass> CLASSES = new ConcurrentHashMap<>();

    private final Class<?> clazz;
    /** The members that never change : classes, methods and static final fields' values. */
    private final Map<String, Slot<Object>> members = new ConcurrentHashMap<>();
    /** The fields read when selected : static ones that aren't final, and instance ones. */
    private final Map<String, Field> fields = new ConcurrentHashMap<>();
    private final Overloads.Cache<Constructor<?>> constructors = new Overloads.Cache<>();

    JavaClass(Class<?> clazz) {
        this.clazz = clazz;
    }

    /** The Babble value of a Java class, as {@code classof} returns it. */
    public static JavaClass of(Class<?> clazz) {
        return CLASSES.computeIfAbsent(clazz, JavaClass::new);
    }

    @Override
    public Class<?> javaClass() {
        return clazz;
    }

    /**
     * Constructs an instance. Calling a functional interface with one Babble function is
     * an explicit conversion : {@code java.util.concurrent.Callable(() -> ( 1 ))}.
     */
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (clazz.isInterface() && args.length == 1 && Values.isBabbleCallable(args[0]) && JavaTypes.isFunctionalInterface(clazz)) {
            return Values.toJava(args[0], clazz, rt);
        }
        Constructor<?> constructor = constructors.find(args);
        if (constructor == null) {
            constructor = Overloads.resolveConstructor(clazz, args);
            constructors.put(args, constructor);
        }
        Object[] java = Values.toJava(args, constructor.getParameterTypes(), rt);
        try {
            return new JavaObject(this, constructor.newInstance(java));
        } catch (Throwable t) {
            throw JavaTypes.wrapInvocationException(t);
        }
    }

    /** Values of the same Java class are equal, whichever path produced them. */
    @Override
    public boolean equals(Object o) {
        return o instanceof JavaClass other && other.clazz == clazz;
    }

    @Override
    public int hashCode() {
        return clazz.hashCode();
    }

    @Override
    public String toString() {
        return clazz.getName();
    }

    public Slot<Object> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java class");
    }

    /** Whether the class has a member of that name, as {@link #get} would find it ; never throws. */
    public boolean isDeclared(String key) {
        return clazz.isArray() && "length".equals(key)
                || members.containsKey(key) || fields.containsKey(key) || lookup(key) != null;
    }

    public Slot<Object> get(String key) {
        Slot<Object> member = members.get(key);
        if (member != null) {
            return member;
        }
        Object value = value(null, key);
        member = members.get(key);
        if (member != null) {
            return member;
        }
        Slot<Object> slot = new Slot<>(key, true);
        slot.set(value);
        return slot;
    }

    /**
     * A member of the class, or with a receiver, of an instance of it : as {@link #get}, but
     * a field is read from the receiver when it is an instance field.
     */
    public Object value(Object receiver, String key) {
        if (clazz.isArray() && "length".equals(key)) {
            if (receiver == null) {
                throw new BabbleException("Not a static field : " + clazz.getCanonicalName() + ".length");
            }
            return Array.getLength(receiver);
        }
        Slot<Object> member = members.get(key);
        if (member != null) {
            return member.get();
        }
        Field field = fields.get(key);
        if (field == null) {
            Object found = find(key);
            if (!(found instanceof Field read)) {
                Slot<Object> slot = new Slot<>(key, true);
                slot.set(found);
                members.putIfAbsent(key, slot);
                return found;
            }
            fields.putIfAbsent(key, read);
            field = read;
        }
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (!isStatic && receiver == null) {
            throw new BabbleException("Not a static field : " + clazz.getCanonicalName() + "." + key);
        }
        return read(field, isStatic ? null : receiver);
    }

    /**
     * {@code receiver.key = value}, or {@code Class.key = value} without a receiver : to a
     * public field that isn't final. A Babble function isn't converted to the functional
     * interface a field holds : that takes the interface's class, {@code Runnable(f)}.
     */
    public void assign(Object receiver, String key, Object value) {
        if (clazz.isArray() && "length".equals(key)) {
            String description = clazz.getCanonicalName() + ".length";
            if (receiver == null) {
                throw new BabbleException("Not a static field : " + description);
            }
            throw new BabbleException("Cannot assign final field : " + description);
        }
        Field field = field(clazz, key);
        String description = clazz.getCanonicalName() + "." + key;
        if (field == null) {
            throw new BabbleException("No such field in " + clazz.getCanonicalName() + " : " + key);
        }
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (!isStatic && receiver == null) {
            throw new BabbleException("Not a static field : " + description);
        }
        if (Modifier.isFinal(field.getModifiers())) {
            throw new BabbleException("Cannot assign final field : " + description);
        }
        Class<?> type = field.getType();
        if (Values.isBabbleCallable(value) && JavaTypes.isFunctionalInterface(type)) {
            throw new BabbleException("Cannot assign a function to " + description + " : convert it first, as "
                    + type.getSimpleName() + "(f)");
        }
        try {
            field.set(isStatic ? null : receiver, Values.toJava(value, type, null));
        } catch (IllegalArgumentException e) {
            throw new BabbleException("Cannot assign " + JavaTypes.describe(value) + " to " + description
                    + " : expected " + type.getSimpleName());
        } catch (IllegalAccessException e) {
            throw new BabbleException(e);
        }
    }

    /**
     * Whether a member selected once is the same value every time, as classes, methods and
     * static final fields are : what {@code invokedynamic} sites may keep.
     */
    public boolean isConstant(String key) {
        return !(clazz.isArray() && "length".equals(key)) && !fields.containsKey(key);
    }

    /** A member's value, or a static field that isn't final, to read when selected. */
    private Object find(String key) {
        Object found = lookup(key);
        if (found == null) {
            throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + key);
        }
        if (found instanceof Field field && Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            return read(field, null);
        }
        return found;
    }

    /**
     * What a name is in the class, without reading anything : its public member class, a
     * {@link JavaMethod}, or its field. Null if it has no such member.
     */
    private Object lookup(String key) {
        for (Class<?> memberClass : clazz.getClasses()) {
            if (memberClass.getSimpleName().equals(key) && Modifier.isPublic(memberClass.getModifiers())) {
                return JavaClass.of(memberClass);
            }
        }
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(key) && Modifier.isPublic(method.getModifiers())) {
                return new JavaMethod(clazz, key);
            }
        }
        return field(clazz, key);
    }

    /** A public field, static or not, declared or inherited, that Babble can reach. Null if none. */
    static Field field(Class<?> clazz, String name) {
        try {
            Field field = clazz.getField(name);
            if (JavaTypes.isAccessible(field.getDeclaringClass())) {
                return field;
            }
        } catch (NoSuchFieldException e) {
            // None : no member of that name.
        }
        return null;
    }

    private static Object read(Field field, Object receiver) {
        try {
            return Values.fromJava(field.get(receiver));
        } catch (IllegalAccessException e) {
            throw new BabbleException(e);
        }
    }
}
