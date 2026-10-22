package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaPackage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Arrays;

/**
 * The bootstrap methods of compiled code's {@code invokedynamic} instructions (compiler
 * phase 3) : calls and member selections, each site with its own inline cache.
 * <p>
 * A site starts unlinked. Each time it sees a receiver its cache doesn't cover, it does the
 * work the generic way, then adds a guarded fast path for that kind of receiver in front
 * of what it had. Past {@link #MAX_DEPTH} entries, or for a receiver no fast path applies
 * to, it settles on the generic path for good. Errors are never cached : a select that
 * fails links nothing.
 */
public final class Bootstrap {
    /** The number of fast paths a site keeps before it goes generic. */
    static final int MAX_DEPTH = 4;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle CALL_FALLBACK;
    private static final MethodHandle CALL_GENERIC;
    private static final MethodType CALL = MethodType.methodType(Object.class, ScriptRuntime.class, Object[].class, String[].class, CallSite.class);
    private static final MethodHandle CALLABLE_CALL;
    private static final MethodHandle IS_FUNCTION;
    private static final MethodHandle IS_BOUND_FUNCTION;
    private static final MethodHandle IS_BOUND_METHOD;
    private static final MethodHandle BOUND_FUNCTION;
    private static final MethodHandle RECEIVER;
    private static final MethodHandle SELECT_FALLBACK;
    private static final MethodHandle SELECT_GENERIC;
    private static final MethodHandle IS_CLASS;
    private static final MethodHandle IS_SAME;
    private static final MethodHandle MEMBER;
    private static final MethodHandle METHOD;

    static {
        try {
            MethodType call = MethodType.methodType(Object.class, Callable.class, ScriptRuntime.class, String[].class, CallSite.class, Object[].class);
            CALL_FALLBACK = LOOKUP.findStatic(Bootstrap.class, "callFallback", MethodType.methodType(Object.class, CallCache.class, Object[].class));
            CALL_GENERIC = LOOKUP.findStatic(Bootstrap.class, "callGeneric", call);
            CALLABLE_CALL = LOOKUP.findVirtual(Callable.class, "call", CALL);
            IS_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "isFunction", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_BOUND_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "isBoundFunction", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_BOUND_METHOD = LOOKUP.findStatic(Bootstrap.class, "isBoundMethod", MethodType.methodType(boolean.class, CompiledMethod.class, Callable.class));
            BOUND_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "boundFunction", MethodType.methodType(CompiledFunction.class, Callable.class));
            RECEIVER = LOOKUP.findStatic(Bootstrap.class, "receiver", MethodType.methodType(Object.class, Callable.class));
            SELECT_FALLBACK = LOOKUP.findStatic(Bootstrap.class, "selectFallback", MethodType.methodType(Object.class, SelectCache.class, Object.class));
            SELECT_GENERIC = LOOKUP.findStatic(Members.class, "select", MethodType.methodType(Object.class, Object.class, String.class, String.class));
            IS_CLASS = LOOKUP.findStatic(Bootstrap.class, "isClass", MethodType.methodType(boolean.class, Class.class, Object.class));
            IS_SAME = LOOKUP.findStatic(Bootstrap.class, "isSame", MethodType.methodType(boolean.class, Object.class, Object.class));
            MEMBER = LOOKUP.findStatic(Classes.class, "member", MethodType.methodType(Object.class, Object.class, String.class));
            METHOD = LOOKUP.findStatic(Bootstrap.class, "method", MethodType.methodType(Object.class, Object.class, Object.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Bootstrap() {
    }

    // ------------------------------------------------------------------ calls

    /**
     * {@code call(Callable callee, ScriptRuntime rt, String[] names, CallSite site, Object a0,
     * ...)} : the arguments of the call, spread. The callee was checked callable before
     * they were evaluated.
     */
    public static java.lang.invoke.CallSite call(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new CallCache(type);
    }

    static final class CallCache extends MutableCallSite {
        private final MethodHandle fallback;
        private final MethodHandle generic;
        private int depth;

        CallCache(MethodType type) {
            super(type);
            int arguments = type.parameterCount() - 4;
            fallback = CALL_FALLBACK.bindTo(this).asCollector(Object[].class, type.parameterCount()).asType(type);
            generic = (arguments == 0
                    ? MethodHandles.insertArguments(CALL_GENERIC, 4, (Object) Arguments.NONE)
                    : CALL_GENERIC.asCollector(Object[].class, arguments)).asType(type);
            setTarget(fallback);
        }

        int arguments() {
            return type().parameterCount() - 4;
        }

        synchronized void relink(Callable callee, String[] names) {
            if (depth > MAX_DEPTH) {
                return;
            }
            if (++depth > MAX_DEPTH) {
                setTarget(generic);
                return;
            }
            MethodHandle test = names == null ? callTest(callee, arguments()) : null;
            MethodHandle fast;
            if (test != null) {
                fast = MethodHandles.dropArguments(callTarget(callee, arguments()), 2, String[].class, CallSite.class);
            } else {
                // Any other callee : its own call method, for callees of its class only, so
                // that each site has its own profile instead of sharing callGeneric's.
                test = IS_CLASS.bindTo(callee.getClass()).asType(MethodType.methodType(boolean.class, Callable.class));
                fast = typedCall(callee.getClass(), arguments());
            }
            setTarget(MethodHandles.guardWithTest(test, fast.asType(type()), getTarget()));
        }
    }

    private static Object callFallback(CallCache cache, Object[] all) {
        Callable callee = (Callable) all[0];
        ScriptRuntime rt = (ScriptRuntime) all[1];
        String[] names = (String[]) all[2];
        CallSite site = (CallSite) all[3];
        Object[] args = all.length == 4 ? Arguments.NONE : Arrays.copyOfRange(all, 4, all.length);
        cache.relink(callee, names);
        return callee.call(rt, args, names, site);
    }

    /**
     * {@code callee.call(rt, args, names, site)} for a callee of that class, as {@code
     * (Callable callee, ScriptRuntime rt, String[] names, CallSite site, Object a0, ...)}.
     */
    private static MethodHandle typedCall(Class<?> type, int arguments) {
        MethodHandle h;
        try {
            h = MethodHandles.publicLookup().findVirtual(type, "call", CALL);
        } catch (ReflectiveOperationException e) {
            // A class this can't name : through the interface, cast to the class all the same.
            h = CALLABLE_CALL.asType(CALL.insertParameterTypes(0, type));
        }
        // (C, ScriptRuntime, Object[], String[], CallSite) -> (C, ScriptRuntime, String[], CallSite, Object[])
        h = MethodHandles.permuteArguments(h, MethodType.methodType(Object.class, type, ScriptRuntime.class, String[].class, CallSite.class, Object[].class),
                0, 1, 4, 2, 3);
        return arguments == 0 ? MethodHandles.insertArguments(h, 4, (Object) Arguments.NONE) : h.asCollector(Object[].class, arguments);
    }

    private static Object callGeneric(Callable callee, ScriptRuntime rt, String[] names, CallSite site, Object[] args) {
        return callee.call(rt, args, names, site);
    }

    /** The guard of a fast path for this callee, or null if none applies. */
    private static MethodHandle callTest(Callable callee, int arguments) {
        if (callee instanceof CompiledFunction f && f.direct() != null && arguments <= f.maxArity()) {
            return IS_FUNCTION.bindTo(f.direct());
        }
        if (callee instanceof BoundMethod bound) {
            if (bound.function() instanceof CompiledFunction f && f.direct() != null && arguments <= f.maxArity()) {
                return IS_BOUND_FUNCTION.bindTo(f.direct());
            }
            if (bound.function() instanceof CompiledMethod m && m.direct() != null && arguments <= m.maxArity()) {
                return IS_BOUND_METHOD.bindTo(m);
            }
        }
        return null;
    }

    /**
     * The fast path for a callee that passed its guard : its direct entry point, taking
     * {@code (Callable callee, ScriptRuntime rt, Object a0, ...)}. There is no binder, so
     * there are no leftovers to reject, and the parameters without an argument get
     * {@link Arguments#MISSING}, which makes the function use their default.
     */
    private static MethodHandle callTarget(Callable callee, int arguments) {
        if (callee instanceof CompiledFunction f) {
            // (CompiledFunction self, Object thiz, ScriptRuntime rt, Binder binder, Object p0, ...)
            MethodHandle h = MethodHandles.insertArguments(f.direct(), 3, (Object) null);
            h = MethodHandles.insertArguments(h, 1, Undefined.UNBOUND);
            return pad(h, 2 + arguments, f.maxArity() - arguments);
        }
        BoundMethod bound = (BoundMethod) callee;
        if (bound.function() instanceof CompiledFunction f) {
            MethodHandle h = MethodHandles.insertArguments(f.direct(), 3, (Object) null);
            h = pad(h, 3 + arguments, f.maxArity() - arguments);
            // The function and the receiver both come from the bound method.
            h = MethodHandles.filterArguments(h, 0, BOUND_FUNCTION, RECEIVER);
            int[] reorder = new int[h.type().parameterCount()];
            for (int i = 1; i < reorder.length; i++) {
                reorder[i] = i - 1;
            }
            return MethodHandles.permuteArguments(h, h.type().dropParameterTypes(0, 1), reorder);
        }
        CompiledMethod m = (CompiledMethod) bound.function();
        // (C thiz, CompiledMethod self, ScriptRuntime rt, Binder binder, Object p0, ...)
        MethodHandle h = MethodHandles.insertArguments(m.direct(), 3, (Object) null);
        h = MethodHandles.insertArguments(h, 1, m);
        h = pad(h, 2 + arguments, m.maxArity() - arguments);
        h = h.asType(h.type().changeParameterType(0, Object.class));
        return MethodHandles.filterArguments(h, 0, RECEIVER);
    }

    private static MethodHandle pad(MethodHandle h, int position, int missing) {
        if (missing == 0) {
            return h;
        }
        Object[] values = new Object[missing];
        Arrays.fill(values, Arguments.MISSING);
        return MethodHandles.insertArguments(h, position, values);
    }

    private static boolean isFunction(MethodHandle direct, Callable callee) {
        return callee instanceof CompiledFunction f && f.direct() == direct;
    }

    private static boolean isBoundFunction(MethodHandle direct, Callable callee) {
        return callee instanceof BoundMethod bound && bound.function() instanceof CompiledFunction f && f.direct() == direct;
    }

    private static boolean isBoundMethod(CompiledMethod method, Callable callee) {
        return callee instanceof BoundMethod bound && bound.function() == method;
    }

    private static CompiledFunction boundFunction(Callable callee) {
        return (CompiledFunction) ((BoundMethod) callee).function();
    }

    private static Object receiver(Callable callee) {
        return ((BoundMethod) callee).receiver();
    }

    // ------------------------------------------------------------------ member selection

    /**
     * {@code select(Object target)Object}, for {@code target.name} : the member's name and
     * the target's source text, for errors, are static arguments.
     */
    public static java.lang.invoke.CallSite select(MethodHandles.Lookup lookup, String name, MethodType type, String member, String targetText) {
        return new SelectCache(lookup, type, member, targetText);
    }

    static final class SelectCache extends MutableCallSite {
        private final MethodHandles.Lookup lookup;
        private final String member;
        private final MethodHandle generic;
        private int depth;

        SelectCache(MethodHandles.Lookup lookup, MethodType type, String member, String targetText) {
            super(type);
            this.lookup = lookup;
            this.member = member;
            this.generic = MethodHandles.insertArguments(SELECT_GENERIC, 1, member, targetText).asType(type);
            setTarget(SELECT_FALLBACK.bindTo(this).asType(type));
        }

        Object generic(Object target) throws Throwable {
            return generic.invoke(target);
        }

        synchronized void relink(Object target, Object result) {
            if (depth > MAX_DEPTH) {
                return;
            }
            MethodHandle test = null;
            MethodHandle fast = null;
            if (target instanceof JavaPackage || target instanceof JavaClass) {
                // Their members never change.
                test = IS_SAME.bindTo(target);
                fast = MethodHandles.dropArguments(MethodHandles.constant(Object.class, result), 0, Object.class);
            } else if (target instanceof BabbleInstance instance && target.getClass() != BabbleInstance.class) {
                test = IS_CLASS.bindTo(target.getClass());
                fast = instanceMember(instance);
            }
            if (fast == null || ++depth > MAX_DEPTH) {
                depth = MAX_DEPTH + 1;
                setTarget(generic);
                return;
            }
            setTarget(MethodHandles.guardWithTest(test, fast.asType(type()), getTarget()));
        }

        /**
         * A public member of a compiled class : a read of its field, checked defined, and
         * for a method, bound to the receiver. Null when the member isn't one of the class's
         * own, or when its field can't be reached from the calling class (a class compiled by
         * another script, in another class loader).
         */
        private MethodHandle instanceMember(BabbleInstance instance) {
            ClassShape shape = instance.babbleClass().shape();
            int index = shape.memberIndex(member);
            if (index < 0 || shape.isPrivate(index)) {
                return null;
            }
            MethodHandle getter;
            try {
                getter = lookup.findGetter(instance.getClass(), member, Object.class);
            } catch (ReflectiveOperationException | SecurityException e) {
                return null;
            }
            getter = getter.asType(MethodType.methodType(Object.class, Object.class));
            if (!shape.isMethod(index)) {
                return MethodHandles.filterReturnValue(getter, MethodHandles.insertArguments(MEMBER, 1, member));
            }
            // method(getter(receiver), receiver, name)
            MethodHandle bind = MethodHandles.insertArguments(METHOD, 2, member);
            bind = MethodHandles.filterArguments(bind, 0, getter);
            return MethodHandles.permuteArguments(bind, MethodType.methodType(Object.class, Object.class), 0, 0);
        }
    }

    private static Object selectFallback(SelectCache cache, Object target) throws Throwable {
        Object result = cache.generic(target);
        cache.relink(target, result);
        return result;
    }

    private static boolean isClass(Class<?> type, Object target) {
        return target != null && target.getClass() == type;
    }

    private static boolean isSame(Object expected, Object target) {
        return target == expected;
    }

    /** The value of a method member, selected through an instance : bound to it. */
    private static Object method(Object value, Object receiver, String name) {
        return new BoundMethod((BabbleFunction) Classes.member(value, name), receiver);
    }
}
