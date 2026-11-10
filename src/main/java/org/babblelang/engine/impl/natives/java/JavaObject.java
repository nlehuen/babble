package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;
import org.babblelang.runtime.JavaValue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;

public class JavaObject implements Scope<BoundJavaMethod>, JavaValue {
    private final JavaClass clazz;
    private final Object value;
    private final Map<String, Slot<BoundJavaMethod>> members = new ConcurrentHashMap<>();

    JavaObject(JavaClass clazz, Object value) {
        this.clazz = clazz;
        this.value = value;
    }

    /**
     * A Java object a host hands to a script, as if the script had constructed it : its
     * methods can be called. Its class must be public, as they are found through it.
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

    public Slot<BoundJavaMethod> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java instance");
    }

    public boolean isDeclared(String key) {
        return clazz.isDeclared(key);
    }

    public Slot<BoundJavaMethod> get(String key) {
        return members.computeIfAbsent(key, k -> {
            Slot<BoundJavaMethod> slot = new Slot<>(k, true);
            JavaMethod result = (JavaMethod) clazz.get(k).get();
            slot.set(new BoundJavaMethod(result, value));
            return slot;
        });
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
