package org.babblelang.engine.impl;

public class Slot {
    private final String name;
    private final boolean isFinal;
    private boolean set = false;
    private Object value;

    public Slot(String name, boolean isFinal) {
        this.name = name;
        this.isFinal = isFinal;
    }

    public void set(Object value) {
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

    public Object get() {
        if (set) {
            return value;
        } else {
            throw new IllegalStateException("Slot not set : " + name);
        }
    }
}
