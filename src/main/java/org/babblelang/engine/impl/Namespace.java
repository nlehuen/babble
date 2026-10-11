package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;

import java.util.HashMap;
import java.util.Map;

public class Namespace implements Scope<Object> {
    private final Namespace parent;
    final Map<String, Slot<Object>> locals = new HashMap<>();

    public Namespace() {
        this.parent = null;
    }

    Namespace(Namespace parent) {
        this.parent = parent;
    }

    public Namespace enter(String name) {
        Namespace newNamespace;
        if (name != null) {
            Slot<Object> slot = locals.get(name);
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

    /**
     * Enters a block scope : a child namespace with the names the block defines
     * already declared.
     */
    public Namespace child(String[] declared) {
        Namespace child = new Namespace(this);
        child.declare(declared);
        return child;
    }

    /**
     * Enters, or reopens, the package {@code name}, and declares the names its body
     * defines.
     */
    public Namespace enterPackage(String name, String[] declared) {
        Namespace namespace = enter(name);
        namespace.declare(declared);
        return namespace;
    }

    public Namespace leave() {
        if (parent == null) {
            throw new IllegalStateException("Too many calls to leave()");
        }
        return parent;
    }

    public Slot<Object> define(String key, boolean isFinal) {
        if (locals.containsKey(key)) {
            throw new BabbleException("Name already defined : " + key);
        }
        Slot<Object> slot = new Slot<>(key, isFinal);
        locals.put(key, slot);
        return slot;
    }

    /**
     * Declares names without giving them a value : they shadow outer names from now on,
     * but reading them fails until their definition runs.
     */
    public void declare(String[] names) {
        for (String name : names) {
            define(name, false);
        }
    }

    /**
     * Runs the definition {@code def name = value} in this scope, and returns the value.
     */
    public Object initialize(String name, Object value) {
        Slot<Object> slot = locals.get(name);
        if (slot == null) {
            // Only reachable when the names of the scope were not declared up front.
            slot = define(name, false);
        }
        slot.set(value);
        return value;
    }

    /**
     * Binds a parameter of a function call.
     */
    public void defineParameter(String name, Object value) {
        define(name, false).set(value);
    }

    /**
     * The assignment {@code name = value} : sets the nearest visible definition.
     */
    public Object assign(String name, Object value) {
        get(name).assign(value);
        return value;
    }

    /**
     * The value of the nearest visible definition of {@code name}.
     */
    public Object lookup(String name) {
        return get(name).get();
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

    public Slot<Object> get(String key) {
        Namespace current = this;
        while (current != null) {
            Slot<Object> slot = current.locals.get(key);
            if (slot != null) {
                return slot;
            } else {
                current = current.parent;
            }
        }
        throw new BabbleException("No such name : " + key);
    }

}
