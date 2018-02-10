package org.babblelang.engine.impl;

public class Slot<T> {
    private final String name;
    private final boolean isFinal;
    private boolean set = false;
    private T value;

    public Slot(String name, boolean isFinal) {
        this.name = name;
        this.isFinal = isFinal;
    }

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

    public boolean isSet() {
        return set;
    }

    public T get() {
        if (set) {
            return value;
        } else {
            throw new IllegalStateException("Slot not set : " + name);
        }
    }
}
