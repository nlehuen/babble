package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;

import java.util.HashMap;
import java.util.Map;

public class JavaObject implements Scope<BoundJavaMethod> {
    private final JavaClass clazz;
    private final Object value;
    private final Map<String, Slot<BoundJavaMethod>> members = new HashMap<>();

    JavaObject(JavaClass clazz, Object value) {
        this.clazz = clazz;
        this.value = value;
    }

    public Slot<BoundJavaMethod> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java instance");
    }

    public boolean isDeclared(String key) {
        return clazz.isDeclared(key);
    }

    public Slot<BoundJavaMethod> get(String key) {
        Slot<BoundJavaMethod> slot = members.get(key);

        if (slot == null) {
            slot = new Slot<>(key, true);
            members.put(key, slot);

            JavaMethod result = (JavaMethod) clazz.get(key).get();
            slot.set(new BoundJavaMethod(result, value));
        }

        return slot;
    }
}
