package org.babblelang.engine.impl;

public class BabbleObject extends Namespace {
    BabbleObject(Namespace parent) {
        super(parent);
    }

    @Override
    public Slot<Object> get(String key) {
        Slot<Object> result = super.get(key);
        Object value = result.get();
        if (!locals.containsKey(key) && value instanceof Function) {
            Function function = (Function) value;
            result = define(key, true);
            result.set(new BoundMethod(function, this));
        }
        return result;
    }
}
