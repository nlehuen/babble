package org.babblelang.engine.impl;

import javax.script.Bindings;
import java.util.HashMap;

public class Scope extends HashMap<String, Object> implements Bindings {
    private final Scope parent;

    public Scope() {
        this.parent = null;
    }

    public Scope(Scope parent) {
        this.parent = parent;
        put("..", parent);
    }

    @Override
    public Object get(Object key) {
        Object result = super.get(key);

        if (result == null && parent != null) {
            result = parent.get(key);
        }

        return result;
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && (parent == null || parent.isEmpty());
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(key) || (parent != null && parent.containsKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return super.containsValue(value) || (parent != null && parent.containsValue(value));
    }

    public Scope enter(String name) {
        Scope newScope;
        if (name != null) {
            newScope = (Scope) super.get(name);
            if (newScope == null) {
                newScope = new Scope(this);
                put(name, newScope);
            }
        } else {
            newScope = new Scope(this);
        }
        return newScope;
    }

    public Scope leave() {
        if (parent == null) throw new IllegalStateException("Too many calls to leave()");
        return parent;
    }

    public Object assign(String key, Object value) {
        Scope current = this;
        while (current != null) {
            if (current.containsKey(key)) {
                return current.put(key, value);
            } else {
                current = current.parent;
            }
        }
        throw new IllegalArgumentException("No such key : " + key);
    }
}
