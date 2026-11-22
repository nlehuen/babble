package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaLinker;
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
    private static final MethodHandle IS_FUNCTION_SPEC;
    private static final MethodHandle IS_BOUND_FUNCTION;
    private static final MethodHandle IS_BOUND_FUNCTION_SPEC;
    private static final MethodHandle IS_BOUND_METHOD;
    private static final MethodHandle BOUND_FUNCTION;
    private static final MethodHandle RECEIVER;
    private static final MethodHandle TYPED_CALL_FALLBACK;
    private static final MethodHandle TYPED_CALL_GENERIC_OPT;
    private static final MethodHandle SELECT_FALLBACK;
    private static final MethodHandle SELECT_GENERIC;
    private static final MethodHandle IS_CLASS;
    private static final MethodHandle IS_SAME;
    private static final MethodHandle MEMBER;
    private static final MethodHandle METHOD;
    private static final MethodHandle REQUIRE_MEMBER_DEFINED;
    private static final MethodHandle EXPORT_AT;

    static {
        try {
            MethodType call = MethodType.methodType(Object.class, Callable.class, ScriptRuntime.class, String[].class, CallSite.class, Object[].class);
            CALL_FALLBACK = LOOKUP.findStatic(Bootstrap.class, "callFallback", MethodType.methodType(Object.class, CallCache.class, Object[].class));
            CALL_GENERIC = LOOKUP.findStatic(Bootstrap.class, "callGeneric", call);
            CALLABLE_CALL = LOOKUP.findVirtual(Callable.class, "call", CALL);
            IS_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "isFunction", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_FUNCTION_SPEC = LOOKUP.findStatic(Bootstrap.class, "isFunctionSpec", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_BOUND_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "isBoundFunction", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_BOUND_FUNCTION_SPEC = LOOKUP.findStatic(Bootstrap.class, "isBoundFunctionSpec", MethodType.methodType(boolean.class, MethodHandle.class, Callable.class));
            IS_BOUND_METHOD = LOOKUP.findStatic(Bootstrap.class, "isBoundMethod", MethodType.methodType(boolean.class, CompiledMethod.class, Callable.class));
            BOUND_FUNCTION = LOOKUP.findStatic(Bootstrap.class, "boundFunction", MethodType.methodType(CompiledFunction.class, Callable.class));
            RECEIVER = LOOKUP.findStatic(Bootstrap.class, "receiver", MethodType.methodType(Object.class, Callable.class));
            TYPED_CALL_FALLBACK = LOOKUP.findStatic(Bootstrap.class, "typedCallFallback", MethodType.methodType(Object.class, TypedCallCache.class, Object[].class));
            TYPED_CALL_GENERIC_OPT = LOOKUP.findStatic(Bootstrap.class, "typedCallGenericOpt", MethodType.methodType(Object.class, int.class, Callable.class, ScriptRuntime.class, CallSite.class, Object[].class));
            SELECT_FALLBACK = LOOKUP.findStatic(Bootstrap.class, "selectFallback", MethodType.methodType(Object.class, SelectCache.class, Object.class));
            SELECT_GENERIC = LOOKUP.findStatic(Members.class, "select", MethodType.methodType(Object.class, Object.class, String.class, String.class));
            IS_CLASS = LOOKUP.findStatic(Bootstrap.class, "isClass", MethodType.methodType(boolean.class, Class.class, Object.class));
            IS_SAME = LOOKUP.findStatic(Bootstrap.class, "isSame", MethodType.methodType(boolean.class, Object.class, Object.class));
            MEMBER = LOOKUP.findStatic(Classes.class, "member", MethodType.methodType(Object.class, Object.class, String.class));
            METHOD = LOOKUP.findStatic(Bootstrap.class, "method", MethodType.methodType(Object.class, Object.class, Object.class, String.class));
            REQUIRE_MEMBER_DEFINED = LOOKUP.findStatic(Bootstrap.class, "requireMemberDefined", MethodType.methodType(void.class, Object.class, int.class, String.class));
            EXPORT_AT = LOOKUP.findVirtual(Module.class, "exportAt", MethodType.methodType(Object.class, int.class));
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

        synchronized void relink(Callable callee, String[] names, Object[] args) {
            if (depth > MAX_DEPTH) {
                return;
            }
            if (++depth > MAX_DEPTH) {
                setTarget(generic);
                return;
            }
            MethodHandle test = names == null ? callTest(callee, arguments()) : null;
            MethodHandle[] java = test == null && names == null ? JavaLinker.call(callee, args, type()) : null;
            MethodHandle fast;
            if (test != null) {
                fast = MethodHandles.dropArguments(callTarget(callee, arguments()), 2, String[].class, CallSite.class);
            } else if (java != null) {
                // A Java method, for arguments of these kinds : its method handle.
                test = java[0];
                fast = java[1];
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
        cache.relink(callee, names, args);
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
        if (callee instanceof CompiledFunction f && f.direct() != null && f.restIndex() < 0 && arguments <= f.maxArity()) {
            return IS_FUNCTION.bindTo(f.direct());
        }
        if (callee instanceof BoundMethod bound) {
            if (bound.function() instanceof CompiledFunction f && f.direct() != null && f.restIndex() < 0 && arguments <= f.maxArity()) {
                return IS_BOUND_FUNCTION.bindTo(f.direct());
            }
            if (bound.function() instanceof CompiledMethod m && m.direct() != null && m.restIndex() < 0 && arguments <= m.maxArity()) {
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

    /**
     * {@code typedCall(Callable callee, ScriptRuntime rt, CallSite site, <args...>)<ret>} :
     * a statically typed positional Babble call with primitive parameters or return value,
     * inline-caching the callee's {@code $spec} entry point.
     */
    public static java.lang.invoke.CallSite typedCall(MethodHandles.Lookup lookup, String name, MethodType type) {
        return new TypedCallCache(type, 0);
    }

    /**
     * {@code typedCallOpt(Callable callee, ScriptRuntime rt, CallSite site, <args...>)<ret>}
     * with a constant {@code missingBits} mask (bit {@code i} set when argument {@code i} was
     * omitted and uses its default, {@code BIP021-D3}).
     */
    public static java.lang.invoke.CallSite typedCallOpt(MethodHandles.Lookup lookup, String name, MethodType type, int missingBits) {
        return new TypedCallCache(type, missingBits);
    }

    static final class TypedCallCache extends MutableCallSite {
        private final MethodHandle generic;
        private final int missingBits;
        private int depth;

        TypedCallCache(MethodType type, int missingBits) {
            super(type);
            this.missingBits = missingBits;
            int arguments = type.parameterCount() - 3;
            MethodHandle fallback = TYPED_CALL_FALLBACK.bindTo(this)
                    .asCollector(Object[].class, type.parameterCount())
                    .asType(type);
            if (missingBits == 0) {
                MethodHandle noNames = MethodHandles.insertArguments(CALL_GENERIC, 2, new Object[]{null});
                this.generic = (arguments == 0
                        ? MethodHandles.insertArguments(noNames, 3, (Object) Arguments.NONE)
                        : noNames.asCollector(Object[].class, arguments)).asType(type);
            } else {
                MethodHandle optGeneric = MethodHandles.insertArguments(TYPED_CALL_GENERIC_OPT, 0, missingBits);
                this.generic = optGeneric.asCollector(Object[].class, arguments).asType(type);
            }
            setTarget(fallback);
        }

        int arguments() {
            return type().parameterCount() - 3;
        }

        synchronized void relink(Callable callee) {
            if (depth > MAX_DEPTH) {
                return;
            }
            if (++depth > MAX_DEPTH) {
                setTarget(generic);
                return;
            }
            int arguments = arguments();
            MethodHandle test = null;
            MethodHandle fast = null;
            if (callee instanceof CompiledFunction f && f.spec() != null && f.restIndex() < 0) {
                MethodHandle adapted = adaptSpec(f.spec(), f.optionalParamBits(), type(), missingBits);
                if (adapted != null) {
                    test = IS_FUNCTION_SPEC.bindTo(f.spec());
                    MethodHandle h = MethodHandles.insertArguments(adapted, 1, Undefined.UNBOUND);
                    fast = MethodHandles.dropArguments(h, 2, CallSite.class).asType(type());
                }
            } else if (callee instanceof BoundMethod bound) {
                if (bound.function() instanceof CompiledMethod m && m.spec() != null && m.restIndex() < 0) {
                    MethodHandle adapted = adaptSpec(m.spec(), m.optionalParamBits(), type(), missingBits);
                    if (adapted != null) {
                        test = IS_BOUND_METHOD.bindTo(m);
                        MethodHandle h = MethodHandles.insertArguments(adapted, 1, m);
                        h = h.asType(h.type().changeParameterType(0, Object.class));
                        h = MethodHandles.filterArguments(h, 0, RECEIVER);
                        fast = MethodHandles.dropArguments(h, 2, CallSite.class).asType(type());
                    }
                } else if (bound.function() instanceof CompiledFunction f && f.spec() != null && f.restIndex() < 0) {
                    MethodHandle adapted = adaptSpec(f.spec(), f.optionalParamBits(), type(), missingBits);
                    if (adapted != null) {
                        test = IS_BOUND_FUNCTION_SPEC.bindTo(f.spec());
                        MethodHandle h = MethodHandles.filterArguments(adapted, 0, BOUND_FUNCTION, RECEIVER);
                        int[] reorder = new int[h.type().parameterCount()];
                        for (int i = 1; i < reorder.length; i++) {
                            reorder[i] = i - 1;
                        }
                        h = MethodHandles.permuteArguments(h, h.type().dropParameterTypes(0, 1), reorder);
                        fast = MethodHandles.dropArguments(h, 2, CallSite.class).asType(type());
                    }
                }
            }
            if (test == null) {
                test = callTest(callee, arguments);
                if (test != null) {
                    MethodHandle target = callTarget(callee, arguments);
                    if (missingBits != 0) {
                        for (int i = 0; i < arguments && i < 32; i++) {
                            if ((missingBits & (1 << i)) != 0) {
                                target = MethodHandles.insertArguments(target, 2 + i, Arguments.MISSING);
                                target = MethodHandles.dropArguments(target, 2 + i, type().parameterType(3 + i));
                            }
                        }
                    }
                    fast = MethodHandles.dropArguments(target, 2, CallSite.class).asType(type());
                } else if (missingBits != 0) {
                    test = IS_CLASS.bindTo(callee.getClass()).asType(MethodType.methodType(boolean.class, Callable.class));
                    fast = generic;
                } else {
                    test = IS_CLASS.bindTo(callee.getClass()).asType(MethodType.methodType(boolean.class, Callable.class));
                    MethodHandle noNames = MethodHandles.insertArguments(
                            MethodHandles.permuteArguments(CALLABLE_CALL, MethodType.methodType(Object.class, Callable.class, ScriptRuntime.class, String[].class, CallSite.class, Object[].class), 0, 1, 4, 2, 3),
                            2, new Object[]{null});
                    fast = (arguments == 0
                            ? MethodHandles.insertArguments(noNames, 3, (Object) Arguments.NONE)
                            : noNames.asCollector(Object[].class, arguments)).asType(type());
                }
            }
            setTarget(MethodHandles.guardWithTest(test, fast, getTarget()));
        }

        private static MethodHandle adaptSpec(MethodHandle spec, int optionalParamBits, MethodType callType, int missingBits) {
            MethodType specType = spec.type();
            int k = callType.parameterCount() - 3;
            boolean hasMask = optionalParamBits != 0;
            int n = specType.parameterCount() - 3 - (hasMask ? 1 : 0);
            if (k > n || specType.returnType() != callType.returnType()) {
                return null;
            }
            if (!hasMask) {
                if (k != n || missingBits != 0) {
                    return null;
                }
            }
            for (int i = 0; i < k; i++) {
                boolean omitted = i < 32 && (missingBits & (1 << i)) != 0;
                if (omitted) {
                    if ((optionalParamBits & (1 << i)) == 0) {
                        return null;
                    }
                } else if (specType.parameterType(3 + i) != callType.parameterType(3 + i)) {
                    return null;
                }
            }
            for (int i = k; i < n; i++) {
                if (i >= 32 || (optionalParamBits & (1 << i)) == 0) {
                    return null;
                }
            }
            MethodHandle h = spec;
            if (hasMask) {
                int calleeMask = 0;
                int optBit = 0;
                for (int i = 0; i < n; i++) {
                    if ((optionalParamBits & (1 << i)) != 0) {
                        if (i >= k || (i < 32 && (missingBits & (1 << i)) != 0)) {
                            calleeMask |= (1 << optBit);
                        }
                        optBit++;
                    }
                }
                h = MethodHandles.insertArguments(h, 3 + n, calleeMask);
            }
            if (k < n) {
                Object[] padValues = new Object[n - k];
                for (int i = k; i < n; i++) {
                    padValues[i - k] = defaultValue(specType.parameterType(3 + i));
                }
                h = MethodHandles.insertArguments(h, 3 + k, padValues);
            }
            if (missingBits != 0) {
                for (int i = 0; i < k && i < 32; i++) {
                    if ((missingBits & (1 << i)) != 0) {
                        Class<?> expectedType = specType.parameterType(3 + i);
                        Class<?> incomingType = callType.parameterType(3 + i);
                        if (expectedType != incomingType) {
                            h = MethodHandles.insertArguments(h, 3 + i, defaultValue(expectedType));
                            h = MethodHandles.dropArguments(h, 3 + i, incomingType);
                        }
                    }
                }
            }
            return h;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) return Boolean.FALSE;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == char.class) return '\0';
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0.0f;
            if (type == double.class) return 0.0d;
            return null;
        }
    }

    private static Object typedCallGenericOpt(int missingBits, Callable callee, ScriptRuntime rt, CallSite site, Object[] args) {
        if (missingBits != 0) {
            for (int i = 0; i < args.length && i < 32; i++) {
                if ((missingBits & (1 << i)) != 0) {
                    args[i] = Arguments.MISSING;
                }
            }
        }
        return callee.call(rt, args, null, site);
    }

    private static Object typedCallFallback(TypedCallCache cache, Object[] all) {
        Callable callee = (Callable) all[0];
        ScriptRuntime rt = (ScriptRuntime) all[1];
        CallSite site = (CallSite) all[2];
        Object[] args = all.length == 3 ? Arguments.NONE : Arrays.copyOfRange(all, 3, all.length);
        if (cache.missingBits != 0) {
            for (int i = 0; i < args.length && i < 32; i++) {
                if ((cache.missingBits & (1 << i)) != 0) {
                    args[i] = Arguments.MISSING;
                }
            }
        }
        Object result = callee.call(rt, args, null, site);
        cache.relink(callee);
        return result;
    }

    private static boolean isFunction(MethodHandle direct, Callable callee) {
        return callee instanceof CompiledFunction f && f.direct() == direct;
    }

    private static boolean isFunctionSpec(MethodHandle spec, Callable callee) {
        return callee instanceof CompiledFunction f && f.spec() == spec;
    }

    private static boolean isBoundFunction(MethodHandle direct, Callable callee) {
        return callee instanceof BoundMethod bound && bound.function() instanceof CompiledFunction f && f.direct() == direct;
    }

    private static boolean isBoundFunctionSpec(MethodHandle spec, Callable callee) {
        return callee instanceof BoundMethod bound && bound.function() instanceof CompiledFunction f && f.spec() == spec;
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
        return new SelectCache(type, member, targetText);
    }

    static final class SelectCache extends MutableCallSite {
        private final String member;
        private final MethodHandle generic;
        private int depth;

        SelectCache(MethodType type, String member, String targetText) {
            super(type);
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
            if (target instanceof JavaPackage || target instanceof JavaClass javaClass && javaClass.isConstant(member)) {
                // Their members never change, except a Java class's static fields that aren't final.
                test = IS_SAME.bindTo(target);
                fast = MethodHandles.dropArguments(MethodHandles.constant(Object.class, result), 0, Object.class);
            } else if (target instanceof Module mod) {
                int index = mod.exportIndex(member);
                if (index >= 0) {
                    test = IS_SAME.bindTo(mod);
                    fast = MethodHandles.dropArguments(MethodHandles.insertArguments(EXPORT_AT.bindTo(mod), 0, index), 0, Object.class);
                }
            } else if (BabbleObjects.isCompiledInstance(target)) {
                test = IS_CLASS.bindTo(target.getClass());
                fast = instanceMember(target);
            } else {
                // A Java object's method, bound to it.
                MethodHandle[] java = JavaLinker.select(target, result);
                if (java != null) {
                    test = java[0];
                    fast = java[1];
                }
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
         * own. The field is looked up with access to the instance's class rather than the
         * caller's : a class another file compiled is in another class loader, whose
         * package-private fields the caller can't reach (an imported module's class).
         */
        private MethodHandle instanceMember(Object instance) {
            ClassShape shape = BabbleObjects.babbleClass(instance).shape();
            int index = shape.memberIndex(member);
            if (index < 0 || shape.isPrivate(index)) {
                return null;
            }
            Class<?> fieldType;
            try {
                fieldType = instance.getClass().getDeclaredField(member).getType();
            } catch (NoSuchFieldException | SecurityException e) {
                return null;
            }
            MethodHandle getter;
            try {
                getter = MethodHandles.privateLookupIn(instance.getClass(), LOOKUP).findGetter(instance.getClass(), member, fieldType);
            } catch (ReflectiveOperationException | SecurityException e) {
                return null;
            }
            getter = getter.asType(MethodType.methodType(Object.class, Object.class));
            if (!shape.isMethod(index)) {
                if (fieldType.isPrimitive()) {
                    return MethodHandles.foldArguments(getter, MethodHandles.insertArguments(REQUIRE_MEMBER_DEFINED, 1, index, member));
                }
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

    private static void requireMemberDefined(Object receiver, int index, String name) {
        if (!BabbleObjects.isMemberDefined(receiver, index)) {
            throw Members.usedBeforeDefinition(name);
        }
    }

    /** The value of a method member, selected through an instance : bound to it. */
    private static Object method(Object value, Object receiver, String name) {
        return new BoundMethod((BabbleFunction) Classes.member(value, name), receiver);
    }
}
