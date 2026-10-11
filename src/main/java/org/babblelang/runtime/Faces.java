package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The proxies Java sees : the face of a Babble instance whose class implements Java
 * interfaces, and Babble functions converted to Java functional interfaces.
 */
final class Faces {
    private Faces() {
    }

    static Object create(BabbleInstance instance) {
        Class<?>[] interfaces = instance.babbleClass().javaInterfaces();
        return Proxy.newProxyInstance(loader(interfaces), interfaces, new FaceHandler(instance));
    }

    /**
     * Converts a Babble callable to a Java functional interface. Its arity is checked
     * now, where the mistake is made, rather than when Java calls it.
     */
    static Object function(Callable callable, Class<?> type, ScriptRuntime rt) {
        Method method = JavaTypes.functionalMethod(type);
        String description = type.getSimpleName() + "." + method.getName();
        int[] arity = Values.arity(callable);
        int passed = method.getParameterCount();
        if (arity != null && (passed < arity[0] || passed > arity[1])) {
            String takes = arity[0] == arity[1] ? String.valueOf(arity[0]) : arity[0] + " to " + arity[1];
            throw new BabbleException(description + " takes " + passed + " arguments, function takes " + takes);
        }
        return Proxy.newProxyInstance(loader(new Class<?>[]{type}), new Class<?>[]{type},
                new FunctionHandler(callable, method, description, rt));
    }

    private static ClassLoader loader(Class<?>[] interfaces) {
        for (Class<?> type : interfaces) {
            if (type.getClassLoader() != null) {
                return type.getClassLoader();
            }
        }
        return Faces.class.getClassLoader();
    }

    private static CallSite site(String description, int arguments) {
        String[] texts = new String[arguments];
        for (int i = 0; i < arguments; i++) {
            texts[i] = "<argument " + i + ">";
        }
        return new CallSite("<java>", -1, description, texts);
    }

    private static boolean isObjectMethod(Method method, String name, int parameters) {
        return method.getName().equals(name) && method.getParameterCount() == parameters;
    }

    private static Object invokeDefault(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw new BabbleException("No implementation of " + JavaTypes.signature(method));
    }

    static final class FaceHandler implements InvocationHandler {
        private final BabbleInstance instance;

        FaceHandler(BabbleInstance instance) {
            this.instance = instance;
        }

        BabbleInstance instance() {
            return instance;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method, "equals", 1)) {
                return instance.equals(Values.fromJava(args[0]));
            }
            if (isObjectMethod(method, "hashCode", 0)) {
                return instance.hashCode();
            }
            if (isObjectMethod(method, "toString", 0)) {
                return instance.toString();
            }
            BabbleFunction function = instance.method(method.getName());
            if (function != null) {
                Object[] babble = Values.fromJava(args);
                Object result = function.invoke(instance, instance.runtime(), babble, null, site(method.getName(), babble.length));
                return JavaTypes.convertResult(result, method.getReturnType(),
                        instance.babbleClass().name() + "." + method.getName(), instance.runtime());
            }
            return invokeDefault(proxy, method, args);
        }
    }

    private static final class FunctionHandler implements InvocationHandler {
        private final Callable callable;
        private final Method method;
        private final String description;
        private final ScriptRuntime rt;

        FunctionHandler(Callable callable, Method method, String description, ScriptRuntime rt) {
            this.callable = callable;
            this.method = method;
            this.description = description;
            this.rt = rt;
        }

        @Override
        public Object invoke(Object proxy, Method called, Object[] args) throws Throwable {
            if (isObjectMethod(called, "equals", 1)) {
                return proxy == args[0];
            }
            if (isObjectMethod(called, "hashCode", 0)) {
                return System.identityHashCode(proxy);
            }
            if (isObjectMethod(called, "toString", 0)) {
                return callable + " as " + method.getDeclaringClass().getSimpleName();
            }
            if (called.getName().equals(method.getName()) && called.getParameterCount() == method.getParameterCount()) {
                Object[] babble = Values.fromJava(args);
                Object result = callable.call(rt, babble, null, site(description, babble.length));
                return JavaTypes.convertResult(result, called.getReturnType(), description, rt);
            }
            return invokeDefault(proxy, called, args);
        }
    }
}
