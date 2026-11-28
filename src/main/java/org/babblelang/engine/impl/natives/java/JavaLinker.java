package org.babblelang.engine.impl.natives.java;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.BabbleObjects;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.Faces;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

/**
 * Fast paths for Java calls at {@code invokedynamic} sites ({@code runtime.Bootstrap}), in compiled
 * mode : a guard and a method handle doing what {@link JavaMethod#invoke} does through reflection,
 * for the overload the arguments resolved to.
 *
 * <p>The guard is what resolution depends on : the same Java method (bound to a receiver of the
 * same class, which selected it) and arguments of the same kinds. Arguments are converted as {@link
 * Values#toJava} does, the result as {@link Values#fromJava} does, and a failure is the same {@link
 * BabbleException} reflection gives. A call with an argument whose kind isn't its JVM class (a
 * Babble instance or class) or that may need the runtime to convert (a Babble function) gets no
 * fast path.
 */
public final class JavaLinker {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle IS_METHOD;
    private static final MethodHandle IS_BOUND;
    private static final MethodHandle RECEIVER;
    private static final MethodHandle IS_KIND;
    private static final MethodHandle IS_WRAPPED_KIND;
    private static final MethodHandle IS_NULL;
    private static final MethodHandle TO_JAVA;
    private static final MethodHandle FROM_JAVA;
    private static final MethodHandle CHECK_BOXED;
    private static final MethodHandle IS_WRAPPED_OF;
    private static final MethodHandle BIND_WRAPPED;
    private static final MethodHandle BIND_RAW;

    static {
        try {
            IS_METHOD =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isMethod",
                            MethodType.methodType(boolean.class, JavaMethod.class, Callable.class));
            IS_BOUND =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isBound",
                            MethodType.methodType(boolean.class, JavaMethod.class, Callable.class));
            RECEIVER =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "receiver",
                            MethodType.methodType(Object.class, Callable.class));
            IS_KIND =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isKind",
                            MethodType.methodType(boolean.class, Class.class, Object.class));
            IS_WRAPPED_KIND =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isWrappedKind",
                            MethodType.methodType(boolean.class, Class.class, Object.class));
            IS_NULL =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isNull",
                            MethodType.methodType(boolean.class, Object.class));
            TO_JAVA =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "toJava",
                            MethodType.methodType(Object.class, Class.class, Object.class));
            FROM_JAVA =
                    LOOKUP.findStatic(
                            Values.class,
                            "fromJava",
                            MethodType.methodType(Object.class, Object.class));
            CHECK_BOXED =
                    LOOKUP.findStatic(
                            JavaTypes.class,
                            "checkBoxed",
                            MethodType.methodType(Object.class, Class.class, Object.class));
            IS_WRAPPED_OF =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "isWrappedOf",
                            MethodType.methodType(boolean.class, JavaClass.class, Object.class));
            BIND_WRAPPED =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "bindWrapped",
                            MethodType.methodType(Object.class, JavaMethod.class, Object.class));
            BIND_RAW =
                    LOOKUP.findStatic(
                            JavaLinker.class,
                            "bindRaw",
                            MethodType.methodType(Object.class, JavaMethod.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private JavaLinker() {}

    /**
     * For a call site of type {@code (Callable callee, ScriptRuntime rt, String[] names, CallSite
     * site, Object a0, ...)Object} with positional arguments : {guard, target} for a call of this
     * callee with arguments of these kinds, or null when it isn't a Java method or no fast path
     * applies.
     */
    public static MethodHandle[] call(Callable callee, Object[] args, MethodType type) {
        JavaMethod method;
        boolean bound = callee instanceof BoundJavaMethod;
        if (callee instanceof BoundJavaMethod b) {
            method = b.method;
        } else if (callee instanceof JavaMethod m) {
            method = m;
        } else {
            return null;
        }
        org.babblelang.runtime.Overloads.Resolved<Method> resolvedCall;
        try {
            resolvedCall = method.resolveDetailed(args);
        } catch (BabbleException e) {
            // No applicable overload : the generic path reports it.
            return null;
        }
        if (resolvedCall.varArgs()) {
            return null;
        }
        Method resolved = resolvedCall.executable();
        boolean isStatic = Modifier.isStatic(resolved.getModifiers());
        Class<?>[] parameters = resolved.getParameterTypes();
        if (!bound && !isStatic || parameters.length != args.length) {
            return null;
        }
        MethodHandle h;
        try {
            h = MethodHandles.publicLookup().unreflect(resolved);
        } catch (IllegalAccessException e) {
            return null;
        }
        if (!isStatic) {
            h = h.asType(h.type().changeParameterType(0, Object.class));
        }

        List<Class<?>> siteParameters = type.parameterList();
        MethodHandle test =
                MethodHandles.dropArguments(
                        bound ? IS_BOUND.bindTo(method) : IS_METHOD.bindTo(method),
                        1,
                        siteParameters.subList(1, siteParameters.size()));
        MethodHandle[] filters = new MethodHandle[parameters.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (BabbleObjects.isInstance(arg)
                    || arg instanceof BabbleClass
                    || Values.isBabbleCallable(arg)) {
                return null;
            }
            Class<?> parameter = parameters[i];
            MethodHandle kindTest;
            if (arg == null) {
                kindTest = IS_NULL;
            } else if (arg instanceof JavaObject wrapper) {
                kindTest = IS_WRAPPED_KIND.bindTo(wrapper.javaValue().getClass());
            } else {
                kindTest = IS_KIND.bindTo(arg.getClass());
            }
            test =
                    and(
                            test,
                            MethodHandles.dropArguments(
                                    MethodHandles.dropArguments(
                                            kindTest, 0, siteParameters.subList(0, 4 + i)),
                                    5 + i,
                                    siteParameters.subList(5 + i, siteParameters.size())));
            if (parameter.isPrimitive()) {
                if (arg == null || arg instanceof JavaObject) {
                    return null;
                }
                // A wrapper of a primitive : unboxed, then widened if needs be.
                filters[i] =
                        MethodHandles.identity(Object.class)
                                .asType(MethodType.methodType(arg.getClass(), Object.class))
                                .asType(MethodType.methodType(parameter, Object.class));
            } else {
                filters[i] =
                        TO_JAVA.bindTo(parameter)
                                .asType(MethodType.methodType(parameter, Object.class));
            }
        }
        h = MethodHandles.filterArguments(h, isStatic ? 0 : 1, filters);

        Class<?> result = resolved.getReturnType();
        h = h.asType(h.type().changeReturnType(Object.class));
        if (!result.isPrimitive() && result != String.class) {
            h = MethodHandles.filterReturnValue(h, FROM_JAVA);
        }
        h =
                isStatic
                        ? MethodHandles.dropArguments(h, 0, Callable.class)
                        : MethodHandles.filterArguments(h, 0, RECEIVER);
        h = MethodHandles.dropArguments(h, 1, ScriptRuntime.class, String[].class, CallSite.class);
        h = h.asType(type);
        return new MethodHandle[] {test, h};
    }

    /**
     * For a select site of type {@code (Object)Object} whose member is a Java method : {guard,
     * target} binding it to targets of the same class, wrapped or not, or null.
     */
    public static MethodHandle[] select(Object target, Object member) {
        if (!(member instanceof BoundJavaMethod bound)) {
            return null;
        }
        if (target instanceof JavaObject wrapper) {
            return new MethodHandle[] {
                IS_WRAPPED_OF.bindTo(wrapper.javaClass()), BIND_WRAPPED.bindTo(bound.method)
            };
        }
        if (target == null || bound.thiz != target) {
            return null;
        }
        return new MethodHandle[] {
            IS_KIND.bindTo(target.getClass()), BIND_RAW.bindTo(bound.method)
        };
    }

    private static MethodHandle and(MethodHandle a, MethodHandle b) {
        MethodHandle no =
                MethodHandles.dropArguments(
                        MethodHandles.constant(boolean.class, false), 0, a.type().parameterList());
        return MethodHandles.guardWithTest(a, b, no);
    }

    private static boolean isMethod(JavaMethod method, Callable callee) {
        return callee == method;
    }

    private static boolean isBound(JavaMethod method, Callable callee) {
        return callee instanceof BoundJavaMethod bound && bound.method == method;
    }

    private static Object receiver(Callable callee) {
        return ((BoundJavaMethod) callee).thiz;
    }

    private static boolean isKind(Class<?> kind, Object value) {
        return value != null && value.getClass() == kind;
    }

    private static boolean isWrappedKind(Class<?> kind, Object value) {
        return value instanceof JavaObject wrapper && wrapper.javaValue().getClass() == kind;
    }

    private static boolean isNull(Object value) {
        return value == null;
    }

    private static Object toJava(Class<?> type, Object value) {
        return Faces.toJava(value, type, null);
    }

    private static boolean isWrappedOf(JavaClass clazz, Object target) {
        return target instanceof JavaObject wrapper && wrapper.javaClass() == clazz;
    }

    private static Object bindWrapped(JavaMethod method, Object target) {
        return new BoundJavaMethod(method, ((JavaObject) target).javaValue());
    }

    private static Object bindRaw(JavaMethod method, Object target) {
        return new BoundJavaMethod(method, target);
    }
}
