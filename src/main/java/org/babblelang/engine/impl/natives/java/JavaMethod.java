package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Overloads;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

class JavaMethod implements Callable {
    private final Class<?> clazz;
    private final String name;
    private final Overloads.Cache<Method> overloads = new Overloads.Cache<>();

    JavaMethod(Class<?> clazz, String name) {
        this.clazz = clazz;
        this.name = name;
    }

    /**
     * Called through the class, as a static method.
     */
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return invoke(clazz, args, rt);
    }

    /**
     * Arguments go to Java through Values.toJava, and the result comes back through
     * Values.fromJava, so Babble code never sees the Java face of an instance.
     */
    Object invoke(Object thiz, Object[] args, ScriptRuntime rt) {
        Method method = resolve(args);
        Object[] java = Values.toJava(args, method.getParameterTypes(), rt);
        Object result;
        try {
            result = method.invoke(thiz, java);
        } catch (InvocationTargetException e) {
            // A Babble error raised in a callback comes back as itself.
            if (e.getCause() instanceof BabbleException babble) {
                throw babble;
            }
            throw new BabbleException(e);
        } catch (Exception e) {
            throw new BabbleException(e);
        }
        return JavaTypes.checkBoxed(method.getReturnType(), Values.fromJava(result));
    }

    /** The overload these arguments call, by their kinds : what {@link JavaLinker} guards on. */
    Method resolve(Object[] args) {
        Method method = overloads.find(args);
        if (method == null) {
            method = Overloads.resolveMethod(clazz, name, args);
            overloads.put(args, method);
        }
        return method;
    }
}
