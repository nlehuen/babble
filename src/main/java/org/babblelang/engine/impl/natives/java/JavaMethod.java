package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

import java.lang.reflect.Method;

class JavaMethod implements Callable {
    private final Class<?> clazz;
    private final String name;

    JavaMethod(Class<?> clazz, String name) {
        this.clazz = clazz;
        this.name = name;
    }

    /**
     * Called through the class, as a static method.
     */
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return invoke(clazz, args);
    }

    Object invoke(Object thiz, Object[] args) {
        Method method = Overloads.resolveMethod(clazz, name, args);
        try {
            return method.invoke(thiz, args);
        } catch (Exception e) {
            throw new BabbleException(e);
        }
    }
}
