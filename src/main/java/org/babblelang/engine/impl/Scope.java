package org.babblelang.engine.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Scope implements Resolver {
    protected final Scope parent;
    protected final Map<String, Object> locals = new HashMap<String, Object>();

    public Scope() {
        this.parent = null;
    }

    protected Scope(Scope parent) {
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
        if (parent == null) {
            throw new IllegalStateException("Too many calls to leave()");
        }
        return parent;
    }

    public Resolver closure(Set<String> closureKeys) {
        Scope result = findStaticScope().enter(null);
        for (String key : closureKeys) {
            result.define(key, get(key));
        }
        return result;
    }

    private Scope findStaticScope() {
        Scope current = this;
        Scope result = this;

        while (current != null) {
            if (current.locals.containsKey("$recurse")) {
                result = current.parent;
            }
            current = current.parent;
        }

        return result;
    }

    public Object define(String key, Object value) {
        if (locals.containsKey(key)) {
            throw new IllegalArgumentException("Key already defined : " + key);
        }
        locals.put(key, value);
        return value;
    }

    public boolean isDeclared(String key) {
        Scope current = this;
        while (current != null) {
            if (current.locals.containsKey(key)) {
                return true;
            } else {
                current = current.parent;
            }
        }
        return false;
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
