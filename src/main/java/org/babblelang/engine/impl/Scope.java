package org.babblelang.engine.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Scope implements Resolver {
    protected final Scope parent;
    protected final Map<String, Slot> locals = new HashMap<String, Slot>();

    public Scope() {
        this.parent = null;
    }

    protected Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope enter(String name) {
        Scope newScope;
        if (name != null) {
            Slot slot = locals.get(name);
            if (slot != null) {
                newScope = (Scope) slot.get();
            } else {
                slot = define(name, true);
                newScope = new Scope(this);
                slot.set(newScope);
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
            Slot slot = get(key);
            result.locals.put(key, slot);
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

    public Slot define(String key, boolean _final) {
        if (locals.containsKey(key)) {
            throw new IllegalArgumentException("Key already defined : " + key);
        }
        Slot slot = new Slot(key, _final);
        locals.put(key, slot);
        return slot;
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

    public Slot get(String key) {
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
