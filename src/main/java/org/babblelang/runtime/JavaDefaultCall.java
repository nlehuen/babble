package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * A Java default method called on a Babble instance : {@code it.forEachRemaining(f)} where
 * the class implements {@code java.util.Iterator}. It runs on the instance's Java face.
 */
final class JavaDefaultCall implements Callable {
    private final BabbleInstance instance;
    private final List<Method> methods;
    private final String description;

    JavaDefaultCall(BabbleInstance instance, List<Method> methods, String description) {
        this.instance = instance;
        this.methods = methods;
        this.description = description;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        Method method = Overloads.resolve(methods, description, args);
        Object[] java = Values.toJava(args, method.getParameterTypes(), rt);
        try {
            return Values.fromJava(InvocationHandler.invokeDefault(instance.face(), method, java));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new BabbleException(t);
        }
    }
}
