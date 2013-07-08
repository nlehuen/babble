package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Slot;

import java.util.HashMap;
import java.util.Map;

public class JavaObject implements Resolver {
    private final JavaClass _class;
    private final Object value;
    private final Map<String, Slot> members;

    public JavaObject(JavaClass _class, Object value) {
        this._class = _class;
        this.value = value;
        members = new HashMap<String, Slot>();
    }

    public Slot define(String key, boolean _final) {
        throw new IllegalStateException("Cannot define anything in a Java instance");
    }

    public boolean isDeclared(String key) {
        return _class.isDeclared(key);
    }

    public Slot get(String key) {
        Slot slot = members.get(key);

        if (slot == null) {
            slot = new Slot(key, true);
            members.put(key, slot);

            JavaMethod result = (JavaMethod) _class.get(key).get();
            slot.set(new BoundJavaMethod(result, value));
        }

        return slot;
    }
}
