package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code super.m(args)} and {@code super(I).m(args)} : the default method an interface
 * provides, called on {@code this}. The analysis has already resolved which interface.
 */
public final class Supers {
    private Supers() {
    }

    public static Object call(Object thiz, Object type, String name, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (type instanceof BabbleInterface babble) {
            BabbleFunction function = babble.findDefault(name);
            if (function == null) {
                throw new BabbleException("No default " + name + " to call");
            }
            return function.invoke(thiz, rt, args, names, site);
        }
        if (type instanceof JavaType wrapper) {
            type = wrapper.javaClass();
        }
        if (type instanceof Class<?> java && thiz instanceof BabbleInstance instance) {
            List<Method> methods = new ArrayList<>();
            for (Method method : java.getMethods()) {
                if (method.isDefault() && method.getName().equals(name)) {
                    methods.add(method);
                }
            }
            if (methods.isEmpty()) {
                throw new BabbleException("No default " + name + " to call");
            }
            return new JavaDefaultCall(instance, methods, java.getSimpleName() + "." + name).call(rt, args, names, site);
        }
        throw new BabbleException("super needs an interface");
    }
}
