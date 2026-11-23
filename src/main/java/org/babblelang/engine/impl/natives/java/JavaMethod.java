package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.Overloads;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Throwables;
import org.babblelang.runtime.Values;

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
        Overloads.Resolved<Method> resolved = resolveDetailed(args);
        Object[] java = Overloads.toJavaArguments(resolved.executable(), resolved.varArgs(), args, rt);
        Object result;
        try {
            result = resolved.executable().invoke(thiz, java);
        } catch (Throwable t) {
            throw Throwables.unwrapReflection(t);
        }
        return Values.fromJava(result);
    }

    /** The overload these arguments call, by their kinds : what {@link JavaLinker} guards on. */
    Overloads.Resolved<Method> resolveDetailed(Object[] args) {
        Overloads.Resolved<Method> resolved = overloads.findResolved(args);
        if (resolved == null) {
            resolved = Overloads.resolveMethodDetailed(clazz, name, args);
            overloads.putResolved(args, resolved);
        }
        return resolved;
    }
}
