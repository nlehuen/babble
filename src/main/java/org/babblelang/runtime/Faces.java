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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapters Java sees : the face of an interpreted Babble instance whose class implements Java
 * interfaces, and Babble callables converted to Java functional interfaces (via cached {@link
 * LambdaMetafactory} factories for accessible {@code 0..16}-argument SAMs, with {@link Proxy}
 * fallback for inaccessible or higher-arity SAMs).
 */
public final class Faces {
    static final int MAX_SAM_ARITY = 16;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle[] INVOKER_HANDLES = new MethodHandle[MAX_SAM_ARITY + 1];

    static {
        try {
            for (int i = 0; i <= MAX_SAM_ARITY; i++) {
                INVOKER_HANDLES[i] =
                        LOOKUP.findVirtual(
                                SamInvoker.class, "invoke" + i, MethodType.genericMethodType(i));
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Class<?>[] NO_MARKERS = new Class<?>[0];

    private static final ClassValue<SamFactory> SAM_FACTORIES =
            new ClassValue<>() {
                @Override
                protected SamFactory computeValue(Class<?> type) {
                    return buildSamFactory(type, NO_MARKERS);
                }
            };

    private static final ClassValue<ConcurrentHashMap<List<Class<?>>, SamFactory>>
            MARKER_SAM_FACTORIES =
                    new ClassValue<>() {
                        @Override
                        protected ConcurrentHashMap<List<Class<?>>, SamFactory> computeValue(
                                Class<?> type) {
                            return new ConcurrentHashMap<>();
                        }
                    };

    private Faces() {}

    static Object create(Object instance) {
        Class<?>[] interfaces = BabbleObjects.babbleClass(instance).javaInterfaces();
        return Proxy.newProxyInstance(loader(interfaces), interfaces, new FaceHandler(instance));
    }

    /**
     * Converts a Babble callable to a Java functional interface. Its arity is checked now, where
     * the mistake is made, rather than when Java calls it.
     */
    static Object function(Callable callable, Class<?> type, ScriptRuntime rt) {
        return function(callable, type, NO_MARKERS, rt);
    }

    /**
     * Converts a Babble callable to a Java functional interface implementing additional marker (or
     * override-equivalent) interfaces.
     */
    static Object function(Callable callable, Class<?> type, Class<?>[] markers, ScriptRuntime rt) {
        return function(callable, type, markers, null, rt);
    }

    static Object function(
            Callable callable,
            Class<?> type,
            Class<?>[] markers,
            String[] paramDescriptors,
            ScriptRuntime rt) {
        List<Class<?>> normalizedMarkers = normalizeMarkers(type, markers);
        SamFactory sam =
                normalizedMarkers.isEmpty()
                        ? SAM_FACTORIES.get(type)
                        : MARKER_SAM_FACTORIES
                                .get(type)
                                .computeIfAbsent(
                                        normalizedMarkers,
                                        m -> buildSamFactory(type, m.toArray(Class<?>[]::new)));
        int[] arity = Values.arity(callable);
        int passed = sam.parameterTypes.length;
        if (arity != null && (passed < arity[0] || passed > arity[1])) {
            String takes =
                    arity[0] == arity[1] ? String.valueOf(arity[0]) : arity[0] + " to " + arity[1];
            throw new BabbleException(
                    sam.description + " takes " + passed + " arguments, function takes " + takes);
        }
        if (sam.factory != null) {
            try {
                return sam.factory.invokeExact(new SamInvoker(callable, sam, paramDescriptors, rt));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
        Class<?>[] proxyInterfaces;
        if (normalizedMarkers.isEmpty()) {
            proxyInterfaces = new Class<?>[] {type};
        } else {
            proxyInterfaces = new Class<?>[1 + normalizedMarkers.size()];
            proxyInterfaces[0] = type;
            for (int i = 0; i < normalizedMarkers.size(); i++) {
                proxyInterfaces[i + 1] = normalizedMarkers.get(i);
            }
        }
        return Proxy.newProxyInstance(
                loader(proxyInterfaces),
                proxyInterfaces,
                new FunctionHandler(callable, sam, paramDescriptors, rt));
    }

    private static List<Class<?>> normalizeMarkers(Class<?> samType, Class<?>[] markers) {
        if (markers == null || markers.length == 0) {
            return List.of();
        }
        Set<Class<?>> unique = new LinkedHashSet<>();
        for (Class<?> marker : markers) {
            if (marker != null
                    && marker.isInterface()
                    && marker != samType
                    && !marker.isAssignableFrom(samType)) {
                unique.add(marker);
            }
        }
        return unique.isEmpty() ? List.of() : List.copyOf(unique);
    }

    private static SamFactory buildSamFactory(Class<?> type, Class<?>[] markers) {
        Method method = JavaTypes.functionalMethod(type);
        if (method == null) {
            throw new IllegalArgumentException("Not a functional interface: " + type.getName());
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        String description = type.getSimpleName() + "." + method.getName();
        CallSite callSite = site(description, parameterTypes.length);
        MethodHandle factory =
                buildMetafactoryHandle(type, markers, method, parameterTypes, returnType);
        return new SamFactory(method, parameterTypes, returnType, description, callSite, factory);
    }

    private static MethodHandle buildMetafactoryHandle(
            Class<?> type,
            Class<?>[] markers,
            Method method,
            Class<?>[] parameterTypes,
            Class<?> returnType) {
        int arity = parameterTypes.length;
        if (arity > MAX_SAM_ARITY || !isVisibleFromFaces(type) || !isVisibleFromFaces(returnType)) {
            return null;
        }
        for (Class<?> paramType : parameterTypes) {
            if (!isVisibleFromFaces(paramType)) {
                return null;
            }
        }
        for (Class<?> marker : markers) {
            if (!isVisibleFromFaces(marker)) {
                return null;
            }
        }
        MethodType samMethodType = MethodType.methodType(returnType, parameterTypes);
        Set<MethodType> bridges = new LinkedHashSet<>();
        if (!collectBridgeMethodTypes(type, samMethodType, bridges)) {
            return null;
        }
        for (Class<?> marker : markers) {
            if (!collectBridgeMethodTypes(marker, samMethodType, bridges)) {
                return null;
            }
        }
        MethodType invokedType = MethodType.methodType(type, SamInvoker.class);
        MethodHandle implMethod = INVOKER_HANDLES[arity];
        try {
            java.lang.invoke.CallSite cs;
            if (bridges.isEmpty() && markers.length == 0) {
                cs =
                        LambdaMetafactory.metafactory(
                                LOOKUP,
                                method.getName(),
                                invokedType,
                                samMethodType,
                                implMethod,
                                samMethodType);
            } else {
                // FLAG_MARKERS must precede FLAG_BRIDGES in altArgs; never set FLAG_SERIALIZABLE
                // so Babble closures are not tied to JDK SerializedLambda serialization.
                int flags =
                        (markers.length > 0 ? LambdaMetafactory.FLAG_MARKERS : 0)
                                | (!bridges.isEmpty() ? LambdaMetafactory.FLAG_BRIDGES : 0);
                int extra =
                        (markers.length > 0 ? 1 + markers.length : 0)
                                + (!bridges.isEmpty() ? 1 + bridges.size() : 0);
                Object[] altArgs = new Object[4 + extra];
                altArgs[0] = samMethodType;
                altArgs[1] = implMethod;
                altArgs[2] = samMethodType;
                altArgs[3] = flags;
                int idx = 4;
                if (markers.length > 0) {
                    altArgs[idx++] = markers.length;
                    for (Class<?> marker : markers) {
                        altArgs[idx++] = marker;
                    }
                }
                if (!bridges.isEmpty()) {
                    altArgs[idx++] = bridges.size();
                    for (MethodType bt : bridges) {
                        altArgs[idx++] = bt;
                    }
                }
                cs =
                        LambdaMetafactory.altMetafactory(
                                LOOKUP, method.getName(), invokedType, altArgs);
            }
            return cs.getTarget().asType(MethodType.methodType(Object.class, SamInvoker.class));
        } catch (LambdaConversionException | LinkageError e) {
            return null;
        }
    }

    private static boolean collectBridgeMethodTypes(
            Class<?> iface, MethodType samMethodType, Set<MethodType> bridges) {
        for (Method m : JavaTypes.abstractMethods(iface)) {
            MethodType mt = MethodType.methodType(m.getReturnType(), m.getParameterTypes());
            if (!mt.equals(samMethodType)) {
                if (!isMethodTypeVisibleFromFaces(mt)) {
                    return false;
                }
                bridges.add(mt);
            }
        }
        return true;
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
            ClassLoader cl = type.getClassLoader();
            if (cl != null && canLoadAll(cl, interfaces)) {
                return cl;
            }
        }
        for (Class<?> type : interfaces) {
            if (type.getClassLoader() != null) {
                return type.getClassLoader();
            }
        }
        return Faces.class.getClassLoader();
    }

    private static boolean canLoadAll(ClassLoader cl, Class<?>[] interfaces) {
        for (Class<?> c : interfaces) {
            try {
                if (Class.forName(c.getName(), false, cl) != c) {
                    return false;
                }
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
        return true;
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

    private static Object invokeDefault(Object proxy, Method method, Object[] args)
            throws Throwable {
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw new BabbleException("No implementation of " + JavaTypes.signature(method));
    }

    private record SamFactory(
            Method method,
            Class<?>[] parameterTypes,
            Class<?> returnType,
            String description,
            CallSite site,
            MethodHandle factory) {}

    static final class SamInvoker {
        private final Callable callable;
        private final SamFactory sam;
        private final String[] paramDescriptors;
        private final ScriptRuntime rt;

        SamInvoker(Callable callable, SamFactory sam, String[] paramDescriptors, ScriptRuntime rt) {
            this.callable = callable;
            this.sam = sam;
            this.paramDescriptors = paramDescriptors;
            this.rt = rt;
        }

        private Object dispatch(Object[] args) {
            Class<?>[] paramTypes = sam.parameterTypes;
            for (int i = 0; i < args.length; i++) {
                Object val = JavaTypes.checkParameter(Values.fromJava(args[i]), paramTypes[i]);
                if (paramDescriptors != null
                        && i < paramDescriptors.length
                        && paramDescriptors[i] != null
                        && !paramDescriptors[i].isEmpty()) {
                    val = Types.check(val, paramDescriptors[i]);
                }
                args[i] = val;
            }
            if (sam.method.isVarArgs()
                    && callable instanceof BabbleFunction bf
                    && bf.restIndex() == args.length - 1
                    && args.length > 0
                    && args[args.length - 1] != null) {
                args[args.length - 1] =
                        Spreads.packedRest(ArgumentsView.fromArray(args[args.length - 1]));
            }
            Object result = callable.call(rt, args, null, sam.site);
            return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
        }

        Object invoke0() {
            Object result = callable.call(rt, Arguments.NONE, null, sam.site);
            return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
        }

        Object invoke1(Object a0) {
            return dispatch(new Object[] {a0});
        }

        Object invoke2(Object a0, Object a1) {
            return dispatch(new Object[] {a0, a1});
        }

        Object invoke3(Object a0, Object a1, Object a2) {
            return dispatch(new Object[] {a0, a1, a2});
        }

        Object invoke4(Object a0, Object a1, Object a2, Object a3) {
            return dispatch(new Object[] {a0, a1, a2, a3});
        }

        Object invoke5(Object a0, Object a1, Object a2, Object a3, Object a4) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4});
        }

        Object invoke6(Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5});
        }

        Object invoke7(
                Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6});
        }

        Object invoke8(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7});
        }

        Object invoke9(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8});
        }

        Object invoke10(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9});
        }

        Object invoke11(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10});
        }

        Object invoke12(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10,
                Object a11) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11});
        }

        Object invoke13(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10,
                Object a11,
                Object a12) {
            return dispatch(new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12});
        }

        Object invoke14(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10,
                Object a11,
                Object a12,
                Object a13) {
            return dispatch(
                    new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13});
        }

        Object invoke15(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10,
                Object a11,
                Object a12,
                Object a13,
                Object a14) {
            return dispatch(
                    new Object[] {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14});
        }

        Object invoke16(
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                Object a4,
                Object a5,
                Object a6,
                Object a7,
                Object a8,
                Object a9,
                Object a10,
                Object a11,
                Object a12,
                Object a13,
                Object a14,
                Object a15) {
            return dispatch(
                    new Object[] {
                        a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15
                    });
        }
    }

    /**
     * What Java sees of a Babble instance : itself if compiled and implementing all interfaces,
     * else a proxy.
     */
    public static Object face(Object instance) {
        if (instance instanceof DynamicInstance interpreted) {
            return interpreted.face();
        }
        BabbleClass cls = BabbleObjects.babbleClass(instance);
        if (cls == null) {
            return instance;
        }
        Class<?>[] interfaces = cls.javaInterfaces();
        if (interfaces.length == 0) {
            return instance;
        }
        for (Class<?> iface : interfaces) {
            if (!iface.isInstance(instance)) {
                return create(instance);
            }
        }
        return instance;
    }

    /**
     * A value going to Java, where {@code type} is expected : wrappers are unwrapped, a Babble
     * instance becomes its face, and a Babble function becomes an implementation of an expected
     * functional interface.
     */
    public static Object toJava(Object value, Class<?> type, ScriptRuntime rt) {
        if (value instanceof JavaValue wrapper) {
            return wrapper.javaValue();
        }
        if (BabbleObjects.isInstance(value)) {
            return face(value);
        }
        if (type != null
                && !type.isInstance(value)
                && Values.isBabbleCallable(value)
                && JavaTypes.isFunctionalInterface(type)) {
            return function((Callable) value, type, rt);
        }
        return value;
    }

    public static Object[] toJava(Object[] values, Class<?>[] types, ScriptRuntime rt) {
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = toJava(values[i], types[i], rt);
        }
        return result;
    }

    /**
     * Eagerly adapts a Babble callable to a Java functional interface plus optional marker
     * interfaces.
     */
    public static Object toJavaSam(
            Object value, Class<?> samInterface, Class<?>[] markers, ScriptRuntime rt) {
        return toJavaSam(value, samInterface, markers, null, rt);
    }

    /**
     * Eagerly adapts a Babble callable to a Java functional interface with inbound Babble parameter
     * type checks ({@code BIP032}).
     */
    public static Object toJavaSam(
            Object value,
            Class<?> samInterface,
            Class<?>[] markers,
            String[] paramDescriptors,
            ScriptRuntime rt) {
        if (value != null && Values.isBabbleCallable(value)) {
            return function((Callable) value, samInterface, markers, paramDescriptors, rt);
        }
        return value;
    }

    /**
     * Name-based {@link #toJavaSam} for non-public Java interfaces where {@code LDC} cannot
     * reference the class.
     */
    public static Object toJavaSamByName(
            Object value, String samName, String[] markerNames, ScriptRuntime rt) {
        return toJavaSamByName(value, samName, markerNames, null, rt);
    }

    /** Name-based {@link #toJavaSam} with inbound Babble parameter type checks ({@code BIP032}). */
    public static Object toJavaSamByName(
            Object value,
            String samName,
            String[] markerNames,
            String[] paramDescriptors,
            ScriptRuntime rt) {
        if (value == null || !Values.isBabbleCallable(value)) {
            return value;
        }
        ClassLoader cl = Faces.class.getClassLoader();
        try {
            Class<?> sam = Class.forName(samName, false, cl);
            Class<?>[] markers = null;
            if (markerNames != null && markerNames.length > 0) {
                markers = new Class<?>[markerNames.length];
                for (int i = 0; i < markerNames.length; i++) {
                    markers[i] = Class.forName(markerNames[i], false, cl);
                }
            }
            return function((Callable) value, sam, markers, paramDescriptors, rt);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown Java interface : " + samName, e);
        }
    }

    static final class FaceHandler implements InvocationHandler, FaceProxy {
        private final Object instance;

        FaceHandler(Object instance) {
            this.instance = instance;
        }

        @Override
        public Object instance() {
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
            Object member = BabbleObjects.methodOrOverload(instance, method.getName());
            if (member instanceof OverloadSet ov) {
                Callable selected = ov.selectForJavaMethod(method);
                if (selected != null) {
                    Object[] babble =
                            JavaTypes.checkParameters(
                                    Values.fromJava(args), method.getParameterTypes());
                    if (method.isVarArgs()
                            && selected instanceof BabbleFunction bf
                            && bf.restIndex() == babble.length - 1
                            && babble.length > 0
                            && babble[babble.length - 1] != null) {
                        babble = babble.clone();
                        babble[babble.length - 1] =
                                Spreads.packedRest(
                                        ArgumentsView.fromArray(babble[babble.length - 1]));
                    }
                    ScriptRuntime rt = BabbleObjects.runtime(instance);
                    Object result =
                            selected instanceof BabbleFunction bf
                                    ? bf.invoke(
                                            instance,
                                            rt,
                                            babble,
                                            null,
                                            site(method.getName(), babble.length))
                                    : selected.call(
                                            rt,
                                            babble,
                                            null,
                                            site(method.getName(), babble.length));
                    return JavaTypes.convertResult(
                            result,
                            method.getReturnType(),
                            BabbleObjects.babbleClass(instance).name() + "." + method.getName(),
                            rt);
                }
            } else if (member instanceof BabbleFunction function
                    && (!method.isDefault()
                            || function.acceptsJavaDefaultOverload(method.getParameterTypes()))) {
                Object[] babble =
                        JavaTypes.checkParameters(
                                Values.fromJava(args), method.getParameterTypes());
                if (method.isVarArgs()
                        && function.restIndex() == babble.length - 1
                        && babble.length > 0
                        && babble[babble.length - 1] != null) {
                    babble = babble.clone();
                    babble[babble.length - 1] =
                            Spreads.packedRest(ArgumentsView.fromArray(babble[babble.length - 1]));
                }
                ScriptRuntime rt = BabbleObjects.runtime(instance);
                Object result =
                        function.invoke(
                                instance, rt, babble, null, site(method.getName(), babble.length));
                return JavaTypes.convertResult(
                        result,
                        method.getReturnType(),
                        BabbleObjects.babbleClass(instance).name() + "." + method.getName(),
                        rt);
            }
            return invokeDefault(proxy, method, args);
        }
    }

    private static final class FunctionHandler implements InvocationHandler {
        private final Callable callable;
        private final SamFactory sam;
        private final String[] paramDescriptors;
        private final ScriptRuntime rt;

        FunctionHandler(
                Callable callable, SamFactory sam, String[] paramDescriptors, ScriptRuntime rt) {
            this.callable = callable;
            this.sam = sam;
            this.paramDescriptors = paramDescriptors;
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
            if (called.getName().equals(sam.method.getName())
                    && called.getParameterCount() == sam.parameterTypes.length) {
                Object[] babble =
                        JavaTypes.checkParameters(Values.fromJava(args), sam.parameterTypes);
                if (paramDescriptors != null) {
                    for (int i = 0; i < babble.length && i < paramDescriptors.length; i++) {
                        if (paramDescriptors[i] != null && !paramDescriptors[i].isEmpty()) {
                            babble[i] = Types.check(babble[i], paramDescriptors[i]);
                        }
                    }
                }
                if (sam.method.isVarArgs()
                        && callable instanceof BabbleFunction bf
                        && bf.restIndex() == babble.length - 1
                        && babble.length > 0
                        && babble[babble.length - 1] != null) {
                    babble = babble.clone();
                    babble[babble.length - 1] =
                            Spreads.packedRest(ArgumentsView.fromArray(babble[babble.length - 1]));
                }
                Object result = callable.call(rt, babble, null, sam.site);
                return JavaTypes.convertResult(result, sam.returnType, sam.description, rt);
            }
            return invokeDefault(proxy, called, args);
        }
    }
}
