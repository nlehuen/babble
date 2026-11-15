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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Java class as a Babble value : calling it constructs an instance, and its members are its
 * public member classes, methods and static fields, looked up in that order. A static
 * final field is a constant ; another static field is read each time it is selected.
 */
public class JavaClass implements Scope<Object>, Callable, JavaType {
    private static final Map<Class<?>, JavaClass> CLASSES = new ConcurrentHashMap<>();

    private final Class<?> clazz;
    /** The members that never change : classes, methods and static final fields' values. */
    private final Map<String, Slot<Object>> members = new ConcurrentHashMap<>();
    /** The static fields that aren't final, read when selected. */
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
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BabbleException babble) {
                throw babble;
            }
            throw new BabbleException(e);
        } catch (Exception e) {
            throw new BabbleException(e);
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

    public boolean isDeclared(String key) {
        return get(key) != null;
    }

    public Slot<Object> get(String key) {
        Slot<Object> member = members.get(key);
        if (member != null) {
            return member;
        }
        Field field = fields.get(key);
        if (field == null) {
            Object found = find(key);
            if (found instanceof Field mutable) {
                fields.putIfAbsent(key, mutable);
                field = mutable;
            } else {
                Slot<Object> slot = new Slot<>(key, true);
                slot.set(found);
                member = members.putIfAbsent(key, slot);
                return member != null ? member : slot;
            }
        }
        Slot<Object> slot = new Slot<>(key, true);
        slot.set(read(field));
        return slot;
    }

    /**
     * Whether a member selected once is the same value every time, as classes, methods and
     * static final fields are : what {@code invokedynamic} sites may keep.
     */
    public boolean isConstant(String key) {
        return !fields.containsKey(key);
    }

    /** A member's value, or a static field that isn't final, to read when selected. */
    private Object find(String key) {
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
        Field field = staticField(clazz, key);
        if (field != null) {
            return Modifier.isFinal(field.getModifiers()) ? read(field) : field;
        }
        throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + key);
    }

    /** A public static field, declared or inherited, that Babble can read. Null if none. */
    static Field staticField(Class<?> clazz, String name) {
        try {
            Field field = clazz.getField(name);
            if (Modifier.isStatic(field.getModifiers()) && JavaTypes.isAccessible(field.getDeclaringClass())) {
                return field;
            }
        } catch (NoSuchFieldException e) {
            // None : no member of that name.
        }
        return null;
    }

    private static Object read(Field field) {
        try {
            return Values.fromJava(field.get(null));
        } catch (IllegalAccessException e) {
            throw new BabbleException(e);
        }
    }
}
