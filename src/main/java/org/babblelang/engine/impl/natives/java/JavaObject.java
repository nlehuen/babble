package org.babblelang.engine.impl.natives.java;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.JavaValue;
import org.babblelang.runtime.Scope;
import org.babblelang.runtime.Slot;

public class JavaObject implements Scope<Object>, JavaValue {
    private final JavaClass clazz;
    private final Object value;
    private final Map<String, Slot<Object>> methods = new ConcurrentHashMap<>();

    JavaObject(JavaClass clazz, Object value) {
        this.clazz = clazz;
        this.value = value;
    }

    /**
     * A Java object a host hands to a script, as if the script had constructed it : its methods can
     * be called. Its class must be public, as they are found through it.
     */
    public static JavaObject of(Object value) {
        return new JavaObject(JavaClass.of(value.getClass()), value);
    }

    @Override
    public Object javaValue() {
        return value;
    }

    JavaClass javaClass() {
        return clazz;
    }

    public Slot<Object> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java instance");
    }

    public boolean isDeclared(String key) {
        return clazz.isDeclared(key);
    }

    public Slot<Object> get(String key) {
        Slot<Object> method = methods.get(key);
        if (method != null) {
            return method;
        }
        Slot<Object> slot = new Slot<>(key, true);
        Object member = member(clazz, value, key);
        slot.set(member);
        // A bound method is the same every time ; a static field may not be.
        return member instanceof BoundJavaMethod ? methods.computeIfAbsent(key, k -> slot) : slot;
    }

    /**
     * {@code value.name} on any Java object, wrapped or not (as Java calls return them) : a method
     * bound to it, or a member of its class, a static field or a member class.
     */
    public static Object member(Object value, String name) {
        return member(JavaClass.of(value.getClass()), value, name);
    }

    private static Object member(JavaClass clazz, Object value, String name) {
        Object member = clazz.value(value, name);
        return member instanceof JavaMethod method ? new BoundJavaMethod(method, value) : member;
    }

    /**
     * {@code target.name = value} on a Java class (a static field), or on a Java object, wrapped or
     * not (a field of it, or a static one).
     */
    public static void assign(Object target, String name, Object value) {
        if (target instanceof JavaClass clazz) {
            clazz.assign(null, name, value);
            return;
        }
        Object receiver = target instanceof JavaValue wrapper ? wrapper.javaValue() : target;
        JavaClass.of(receiver.getClass()).assign(receiver, name, value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    /** Java objects compare with their own equals(), as Babble's "==" does. */
    @Override
    public boolean equals(Object o) {
        return o instanceof JavaObject other && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
