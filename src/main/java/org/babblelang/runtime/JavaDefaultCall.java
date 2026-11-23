package org.babblelang.runtime;


import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Java default method called on a Babble instance : {@code it.forEachRemaining(f)} where
 * the class implements {@code java.util.Iterator}. It runs on the instance's Java face.
 */
final class JavaDefaultCall implements Callable {
    private static final ClassValue<ConcurrentHashMap<Method, MethodHandle>> SPECIAL_HANDLES = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<Method, MethodHandle> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private final Object instance;
    private final List<Method> methods;
    private final String description;

    JavaDefaultCall(Object instance, List<Method> methods, String description) {
        this.instance = instance;
        this.methods = methods;
        this.description = description;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        Method method = Overloads.resolve(methods, description, args);
        Object[] java = Values.toJava(args, method.getParameterTypes(), rt);
        Object face = BabbleObjects.face(instance);
        Object result;
        try {
            if (face instanceof Proxy) {
                result = InvocationHandler.invokeDefault(face, method, java);
            } else {
                MethodHandle mh = SPECIAL_HANDLES.get(face.getClass()).computeIfAbsent(method, m -> {
                    try {
                        MethodHandles.Lookup lookup = BabbleObjects.javaDefaultLookup(instance);
                        MethodType mt = MethodType.methodType(m.getReturnType(), m.getParameterTypes());
                        return lookup.findSpecial(m.getDeclaringClass(), m.getName(), mt, face.getClass()).asFixedArity();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Object[] callArgs = new Object[java.length + 1];
                callArgs[0] = face;
                System.arraycopy(java, 0, callArgs, 1, java.length);
                result = mh.invokeWithArguments(callArgs);
            }
        } catch (Throwable t) {
            throw Throwables.unwrapReflection(t);
        }
        return Values.fromJava(result);
    }
}
