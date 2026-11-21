package org.babblelang.runtime;

import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The adapters Java sees : the face of an interpreted Babble instance whose class
 * implements Java interfaces, and Babble callables converted to Java functional interfaces
 * (via cached {@link LambdaMetafactory} factories for accessible {@code 0..16}-argument
 * SAMs, with {@link Proxy} fallback for inaccessible or higher-arity SAMs).
 */
final class Faces {
    static final int MAX_SAM_ARITY = 16;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle[] INVOKER_HANDLES = new MethodHandle[MAX_SAM_ARITY + 1];

    static {
        try {
            for (int i = 0; i <= MAX_SAM_ARITY; i++) {
                INVOKER_HANDLES[i] = LOOKUP.findVirtual(SamInvoker.class, "invoke" + i, MethodType.genericMethodType(i));
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ClassValue<SamFactory> SAM_FACTORIES = new ClassValue<>() {
        @Override
        protected SamFactory computeValue(Class<?> type) {
            return buildSamFactory(type);
        }
    };

    private Faces() {
    }

    static Object create(Object instance) {
        Class<?>[] interfaces = BabbleObjects.babbleClass(instance).javaInterfaces();
        return Proxy.newProxyInstance(loader(interfaces), interfaces, new FaceHandler(instance));
    }

    /**
     * Converts a Babble callable to a Java functional interface. Its arity is checked
     * now, where the mistake is made, rather than when Java calls it.
     */
    static Object function(Callable callable, Class<?> type, ScriptRuntime rt) {
        SamFactory sam = SAM_FACTORIES.get(type);
        int[] arity = Values.arity(callable);
        int passed = sam.parameterTypes.length;
        if (arity != null && (passed < arity[0] || passed > arity[1])) {
            String takes = arity[0] == arity[1] ? String.valueOf(arity[0]) : arity[0] + " to " + arity[1];
            throw new BabbleException(sam.description + " takes " + passed + " arguments, function takes " + takes);
        }
        if (sam.factory != null) {
            try {
                return sam.factory.invokeExact(new SamInvoker(callable, sam, rt));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        return Proxy.newProxyInstance(loader(new Class<?>[]{type}), new Class<?>[]{type},
                new FunctionHandler(callable, sam, rt));
    }

    private static SamFactory buildSamFactory(Class<?> type) {
        Method method = JavaTypes.functionalMethod(type);
        if (method == null) {
            throw new IllegalArgumentException("Not a functional interface: " + type.getName());
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        String description = type.getSimpleName() + "." + method.getName();
        CallSite callSite = site(description, parameterTypes.length);
        MethodHandle factory = buildMetafactoryHandle(type, method, parameterTypes, returnType);
        return new SamFactory(method, parameterTypes, returnType, description, callSite, factory);
    }

    private static MethodHandle buildMetafactoryHandle(Class<?> type, Method method,
                                                       Class<?>[] parameterTypes, Class<?> returnType) {
        int arity = parameterTypes.length;
        if (arity > MAX_SAM_ARITY || !isVisibleFromFaces(type) || !isVisibleFromFaces(returnType)) {
            return null;
        }
        for (Class<?> paramType : parameterTypes) {
            if (!isVisibleFromFaces(paramType)) {
                return null;
            }
        }
        MethodType samMethodType = MethodType.methodType(returnType, parameterTypes);
        Set<MethodType> bridges = new LinkedHashSet<>();
        for (Method m : JavaTypes.abstractMethods(type)) {
            MethodType mt = MethodType.methodType(m.getReturnType(), m.getParameterTypes());
            if (!mt.equals(samMethodType)) {
                if (!isMethodTypeVisibleFromFaces(mt)) {
                    return null;
                }
                bridges.add(mt);
            }
        }
        MethodType invokedType = MethodType.methodType(type, SamInvoker.class);
        MethodHandle implMethod = INVOKER_HANDLES[arity];
        try {
            java.lang.invoke.CallSite cs;
            if (bridges.isEmpty()) {
                cs = LambdaMetafactory.metafactory(
                        LOOKUP, method.getName(), invokedType,
                        samMethodType, implMethod, samMethodType);
            } else {
                Object[] altArgs = new Object[5 + bridges.size()];
                altArgs[0] = samMethodType;
                altArgs[1] = implMethod;
                altArgs[2] = samMethodType;
                altArgs[3] = LambdaMetafactory.FLAG_BRIDGES;
                altArgs[4] = bridges.size();
                int idx = 5;
                for (MethodType bt : bridges) {
                    altArgs[idx++] = bt;
                }
                cs = LambdaMetafactory.altMetafactory(
                        LOOKUP, method.getName(), invokedType, altArgs);
            }
            return cs.getTarget().asType(MethodType.methodType(Object.class, SamInvoker.class));
        } catch (LambdaConversionException | LinkageError e) {
            return null;
        }
    }

    private static boolean isMethodTypeVisibleFromFaces(MethodType mt) {
        if (!isVisibleFromFaces(mt.returnType())) {
            return false;
        }
        for (int i = 0; i < mt.parameterCount(); i++) {
            if (!isVisibleFromFaces(mt.parameterType(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVisibleFromFaces(Class<?> type) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive()) {
            return true;
        }
        if (!JavaTypes.isAccessible(type)) {
            return false;
        }
        try {
            return Class.forName(type.getName(), false, Faces.class.getClassLoader()) == type;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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

    private record SamFactory(Method method, Class<?>[] parameterTypes, Class<?> returnType,
                              String description, CallSite site, MethodHandle factory) {
    }

    static final class SamInvoker {
        private final Callable callable;
        private final SamFactory sam;
        private final ScriptRuntime rt;

        SamInvoker(Callable callable, SamFactory sam, ScriptRuntime rt) {
            this.callable = callable;
            this.sam = sam;
            this.rt = rt;
        }

        private Object dispatch(Object[] args) {
            Class<?>[] paramTypes = sam.parameterTypes;
            for (int i = 0; i < args.length; i++) {
                args[i] = JavaTypes.checkParameter(Values.fromJava(args[i]), paramTypes[i]);
            }
            Object result = callable.call(rt, args, null, sam.site);
            return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
        }

        Object invoke0() {
            Object result = callable.call(rt, Arguments.NONE, null, sam.site);
            return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
        }

        Object invoke1(Object a0) {
            return dispatch(new Object[]{a0});
        }

        Object invoke2(Object a0, Object a1) {
            return dispatch(new Object[]{a0, a1});
        }

        Object invoke3(Object a0, Object a1, Object a2) {
            return dispatch(new Object[]{a0, a1, a2});
        }

        Object invoke4(Object a0, Object a1, Object a2, Object a3) {
            return dispatch(new Object[]{a0, a1, a2, a3});
        }

        Object invoke5(Object a0, Object a1, Object a2, Object a3, Object a4) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4});
        }

        Object invoke6(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5});
        }

        Object invoke7(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6});
        }

        Object invoke8(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7});
        }

        Object invoke9(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                       Object a8) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8});
        }

        Object invoke10(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9});
        }

        Object invoke11(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10});
        }

        Object invoke12(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10, Object a11) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11});
        }

        Object invoke13(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10, Object a11, Object a12) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12});
        }

        Object invoke14(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10, Object a11, Object a12, Object a13) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13});
        }

        Object invoke15(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14});
        }

        Object invoke16(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
                        Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15) {
            return dispatch(new Object[]{a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15});
        }
    }

    static final class FaceHandler implements InvocationHandler {
        private final Object instance;

        FaceHandler(Object instance) {
            this.instance = instance;
        }

        Object instance() {
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
            BabbleFunction function = BabbleObjects.method(instance, method.getName());
            if (function != null) {
                Object[] babble = JavaTypes.checkParameters(Values.fromJava(args), method.getParameterTypes());
                ScriptRuntime rt = BabbleObjects.runtime(instance);
                Object result = function.invoke(instance, rt, babble, null, site(method.getName(), babble.length));
                return JavaTypes.convertResult(result, method.getReturnType(),
                        BabbleObjects.babbleClass(instance).name() + "." + method.getName(), rt);
            }
            return invokeDefault(proxy, method, args);
        }
    }

    private static final class FunctionHandler implements InvocationHandler {
        private final Callable callable;
        private final SamFactory sam;
        private final ScriptRuntime rt;

        FunctionHandler(Callable callable, SamFactory sam, ScriptRuntime rt) {
            this.callable = callable;
            this.sam = sam;
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
                return callable + " as " + sam.method.getDeclaringClass().getSimpleName();
            }
            if (called.getName().equals(sam.method.getName()) && called.getParameterCount() == sam.parameterTypes.length) {
                Object[] babble = JavaTypes.checkParameters(Values.fromJava(args), sam.parameterTypes);
                Object result = callable.call(rt, babble, null, sam.site);
                return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
            }
            return invokeDefault(proxy, called, args);
        }
    }
}
