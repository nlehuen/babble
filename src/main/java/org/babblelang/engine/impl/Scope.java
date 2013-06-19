package org.babblelang.engine.impl;

import java.util.HashMap;
import java.util.Map;

public class Scope {
    private final Scope parent;
    private final HashMap<String, Object> locals = new HashMap<String, Object>();

    public Scope() {
        this.parent = null;
    }

    private Scope(Scope parent) {
        this.parent = parent;
        locals.put("..", parent);
    }

    public Scope enter(String name) {
        Scope newScope;
        if (name != null) {
            newScope = (Scope) locals.get(name);
            if (newScope == null) {
                newScope = new Scope(this);
                locals.put(name, newScope);
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

    public Object define(String key, Object value) {
        if (locals.containsKey(key)) {
            throw new IllegalArgumentException("Key already defined : " + key);
        }
        locals.put(key, value);
        return value;
    }

    public void defineAll(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            define(entry.getKey(), entry.getValue());
        }
    }

    public Object assign(String key, Object value) {
        Scope current = this;
        while (current != null) {
            if (current.locals.containsKey(key)) {
                current.locals.put(key, value);
                return value;
            } else {
                current = current.parent;
            }
        }
        throw new IllegalArgumentException("No such key : " + key);
    }

    public Object get(String key) {
        Scope current = this;
        while (current != null) {
            if (current.locals.containsKey(key)) {
                return current.locals.get(key);
            } else {
                current = current.parent;
            }
        }
        throw new IllegalArgumentException("No such key : " + key);
    }
}
