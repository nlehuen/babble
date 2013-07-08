package org.babblelang.engine.impl;

public class BabbleObject extends Scope {
    public BabbleObject(Scope parent) {
        super(parent);
    }

    @Override
    public Slot get(String key) {
        Slot result = super.get(key);
        Object value = result.get();
        if (!locals.containsKey(key) && value instanceof Function) {
            Function function = (Function) value;
            result = define(key, true);
            result.set(new BoundMethod(function, this));
        }
        return result;
    }
}
