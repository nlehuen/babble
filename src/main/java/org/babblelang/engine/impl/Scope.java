package org.babblelang.engine.impl;

import javax.script.Bindings;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Scope extends HashMap<String, Object> implements Bindings {
    private final Scope parent;

    public Scope() {
        this.parent = null;
    }

    public Scope(Scope parent) {
        this.parent = parent;
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
    public Set<String> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<Object> values() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && (parent == null || parent.isEmpty());
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();


    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(key) || (parent != null && parent.containsKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return super.containsValue(value) || (parent != null && parent.containsValue(value));
    }

    public Scope getParent() {
        return parent;
    }
}
