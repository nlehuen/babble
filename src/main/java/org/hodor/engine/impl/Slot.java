package org.hodor.engine.impl;

public class Slot {
    private final String name;
    private final boolean _final;
    private boolean set = false;
    private Object value;

    public Slot(String name, boolean _final) {
        this.name = name;
        this._final = _final;
    }

    public void set(Object value) {
        if (set) {
            if (_final) {
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
