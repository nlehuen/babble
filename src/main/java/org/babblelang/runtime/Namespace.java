package org.babblelang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

public class Namespace implements Scope<Object> {
    /**
     * Bumped whenever a name is defined in a scope that a {@link #resolve} looked through : a
     * cached resolution (a {@link Bootstrap} name site) is only valid for the epoch it was made in,
     * since the new definition may now shadow the slot it found.
     */
    private static final AtomicInteger EPOCH = new AtomicInteger();

    private final Namespace parent;
    final Map<String, Slot<Object>> locals = new HashMap<>();

    /** Whether a {@link #resolve} looked through this scope without finding its name here. */
    private boolean walked;

    public Namespace() {
        this.parent = null;
    }

    Namespace(Namespace parent) {
        this.parent = parent;
    }

    public Namespace enter() {
        return new Namespace(this);
    }

    /**
     * Enters a block scope : a child namespace with the names the block defines already declared.
     */
    public Namespace child(String[] declared) {
        return child(declared, null);
    }

    public Namespace child(String[] declared, boolean[] finals) {
        Namespace child = new Namespace(this);
        child.declare(declared, finals);
        return child;
    }

    public Slot<Object> define(String key, boolean isFinal) {
        if (locals.containsKey(key)) {
            throw new BabbleException("Name already defined : " + key);
        }
        Slot<Object> slot = new Slot<>(key, isFinal);
        locals.put(key, slot);
        if (walked) {
            EPOCH.incrementAndGet();
        }
        return slot;
    }

    /**
     * Declares names without giving them a value : they shadow outer names from now on, but reading
     * them fails until their definition runs.
     */
    public void declare(String[] names) {
        declare(names, null);
    }

    public void declare(String[] names, boolean[] finals) {
        for (int i = 0; i < names.length; i++) {
            boolean isFinal = finals != null && i < finals.length && finals[i];
            define(names[i], isFinal);
        }
    }

    /** Runs the definition {@code def name = value} in this scope, and returns the value. */
    public Object initialize(String name, Object value) {
        Slot<Object> slot = locals.get(name);
        if (slot == null) {
            // Only reachable when the names of the scope were not declared up front.
            slot = define(name, false);
        }
        slot.set(value);
        return value;
    }

    /** Binds a parameter of a function call. */
    public void defineParameter(String name, Object value) {
        defineParameter(name, value, true);
    }

    public void defineParameter(String name, Object value, boolean isFinal) {
        define(name, isFinal).set(value);
    }

    /** The assignment {@code name = value} : sets the nearest visible definition. */
    public Object assign(String name, Object value) {
        get(name).assign(value);
        return value;
    }

    /** The value of the nearest visible definition of {@code name}. */
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

    /** The current epoch of name resolutions : see {@link #resolve}. */
    static int epoch() {
        return EPOCH.get();
    }

    /**
     * {@link #get}, for a cache : the slot {@code key} resolves to from here, which stays the same
     * as long as {@link #epoch} does. The scopes looked through on the way are marked, so that
     * defining a name in one of them later starts a new epoch.
     */
    Slot<Object> resolve(String key) {
        Namespace current = this;
        while (current != null) {
            Slot<Object> slot = current.locals.get(key);
            if (slot != null) {
                return slot;
            }
            current.walked = true;
            current = current.parent;
        }
        throw new BabbleException("No such name : " + key);
    }

    /**
     * Every name visible here, this scope's and its parents', for completion in the interactive
     * mode.
     */
    public SortedSet<String> names() {
        SortedSet<String> names = new TreeSet<>();
        for (Namespace current = this; current != null; current = current.parent) {
            names.addAll(current.locals.keySet());
        }
        return names;
    }
}
