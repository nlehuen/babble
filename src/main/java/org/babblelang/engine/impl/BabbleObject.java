package org.babblelang.engine.impl;

public class BabbleObject extends Scope {
    public BabbleObject(Scope parent) {
        super(parent);
    }

    @Override
    public Object get(String key) {
        Object result = super.get(key);
        if (result instanceof Function && !locals.containsKey(key)) {
            Function function = (Function) result;
            result = new BoundMethod(function, this);
            define(key, result);
        }
        return result;
    }
}
