package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;

import java.util.HashMap;
import java.util.Map;

public class Namespace implements Scope {
    private final Namespace parent;
    final Map<String, Slot> locals = new HashMap<String, Slot>();

    public Namespace() {
        this.parent = null;
    }

    Namespace(Namespace parent) {
        this.parent = parent;
    }

    public Namespace enter(String name) {
        Namespace newNamespace;
        if (name != null) {
            Slot slot = locals.get(name);
            if (slot != null) {
                newNamespace = (Namespace) slot.get();
            } else {
                slot = define(name, true);
                newNamespace = new Namespace(this);
                slot.set(newNamespace);
            }
        } else {
            newNamespace = new Namespace(this);
        }
        return newNamespace;
    }

    public Namespace leave() {
        if (parent == null) {
            throw new IllegalStateException("Too many calls to leave()");
        }
        return parent;
    }

    public Slot define(String key, boolean _final) {
        if (locals.containsKey(key)) {
            throw new BabbleException("Name already defined : " + key);
        }
        Slot slot = new Slot(key, _final);
        locals.put(key, slot);
        return slot;
    }

    public boolean isDeclared(String key) {
        Namespace current = this;
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
        Namespace current = this;
        while (current != null) {
            if (current.locals.containsKey(key)) {
                return current.locals.get(key);
            } else {
                current = current.parent;
            }
        }
        throw new BabbleException("No such name : " + key);
    }

}
