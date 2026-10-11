package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleException;

public class Slot<T> {
    private final String name;
    private final boolean isFinal;
    private boolean set = false;
    private T value;

    public Slot(String name, boolean isFinal) {
        this.name = name;
        this.isFinal = isFinal;
    }

    /**
     * Gives the slot its value : the initial write of a definition, or a later write.
     */
    public void set(T value) {
        if (set) {
            if (isFinal) {
                throw new IllegalStateException("Slot already set : " + name);
            }
        } else {
            set = true;
        }
        this.value = value;
    }

    /**
     * An assignment, {@code x = v} : unlike {@link #set}, it requires the definition to
     * have run already.
     */
    public void assign(T value) {
        if (!set) {
            throw notDefined();
        }
        set(value);
    }

    public boolean isSet() {
        return set;
    }

    /**
     * A declared slot is unset until its definition runs : block scope hoists every
     * name defined in a scope, so reading it any earlier is an error.
     */
    public T get() {
        if (set) {
            return value;
        } else {
            throw notDefined();
        }
    }

    private BabbleException notDefined() {
        return new BabbleException("Name used before definition : " + name);
    }
}
