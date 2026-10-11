package org.babblelang.engine.impl;

import org.babblelang.runtime.BabbleFunction;
import org.babblelang.runtime.BoundMethod;

public class BabbleObject extends Namespace {
    BabbleObject(Namespace parent) {
        super(parent);
    }

    /**
     * Creates an object literal's namespace, with the names its body defines declared.
     */
    public static BabbleObject create(Namespace parent, String[] declared) {
        BabbleObject object = new BabbleObject(parent);
        object.declare(declared);
        return object;
    }

    @Override
    public Slot<Object> get(String key) {
        Slot<Object> result = super.get(key);
        if (!locals.containsKey(key) && result.isSet() && result.get() instanceof BabbleFunction function) {
            result = define(key, true);
            result.set(new BoundMethod(function, this));
        }
        return result;
    }
}
