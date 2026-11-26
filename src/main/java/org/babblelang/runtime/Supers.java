package org.babblelang.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code super.m(args)} and {@code super(I).m(args)} : the default method an interface provides,
 * called on {@code this}. The analysis has already resolved which interface.
 */
public final class Supers {
    private Supers() {}

    public static Object call(
            Object thiz,
            Object type,
            String name,
            ScriptRuntime rt,
            Object[] args,
            String[] names,
            CallSite site) {
        if (type instanceof BabbleInterface babble) {
            Callable found = babble.findDefault(name);
            if (found == null) {
                throw new BabbleException("No default " + name + " to call");
            }
            if (found instanceof BabbleFunction function) {
                return function.invoke(thiz, rt, args, names, site);
            }
            if (found instanceof OverloadSet ov) {
                return ov.bind(thiz).call(rt, args, names, site);
            }
            return found.call(rt, args, names, site);
        }
        if (type instanceof JavaType wrapper) {
            type = wrapper.javaClass();
        }
        if (type instanceof Class<?> java && BabbleObjects.isInstance(thiz)) {
            List<Method> methods = new ArrayList<>();
            for (Method method : java.getMethods()) {
                if (method.isDefault() && method.getName().equals(name)) {
                    methods.add(method);
                }
            }
            if (methods.isEmpty()) {
                throw new BabbleException("No default " + name + " to call");
            }
            return new JavaDefaultCall(thiz, methods, java.getSimpleName() + "." + name)
                    .call(rt, args, names, site);
        }
        throw new BabbleException("super needs an interface");
    }
}
