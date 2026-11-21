package org.babblelang.runtime;


import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Overload resolution for the Java interop.
 * <p>
 * Reflection's own {@code getMethod} / {@code getConstructor} demand an exact
 * parameter-type match, which almost never happens when the arguments come from Babble :
 * "list.add(&quot;a&quot;)" hands a String to add(Object), and "abs(2)" an Integer to abs(int).
 * So candidates are filtered by applicability, as the Java compiler does, and the most
 * specific of them wins.
 * <p>
 * Phases 1–2 (fixed arity) run first; phase 3 (variable-arity {@code isVarArgs()},
 * {@code BIP021-D8}) runs only when no fixed-arity candidate is applicable.
 */
public final class Overloads {
    private Overloads() {
    }

    /** The resolved executable and whether it was matched via phase 3 variable-arity expansion. */
    public record Resolved<E extends Executable>(E executable, boolean varArgs) {
    }

    public static Method resolveMethod(Class<?> clazz, String name, Object[] arguments) {
        return resolveMethodDetailed(clazz, name, arguments).executable();
    }

    public static Resolved<Method> resolveMethodDetailed(Class<?> clazz, String name, Object[] arguments) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : JavaTypes.methods(clazz, name)) {
            // An object of a class Babble can't reach, as Java returns some, is called
            // through the public type the method comes from.
            Method accessible = JavaTypes.accessible(method);
            if (accessible != null && !candidates.contains(accessible)) {
                candidates.add(accessible);
            }
        }
        return resolveDetailed(candidates, clazz.getCanonicalName() + "." + name, arguments);
    }

    public static Constructor<?> resolveConstructor(Class<?> clazz, Object[] arguments) {
        return resolveConstructorDetailed(clazz, arguments).executable();
    }

    public static Resolved<Constructor<?>> resolveConstructorDetailed(Class<?> clazz, Object[] arguments) {
        return resolveDetailed(Arrays.asList(clazz.getConstructors()), clazz.getCanonicalName(), arguments);
    }

    /**
     * Converts Babble call arguments to Java reflection arguments, packing trailing arguments
     * into the varargs array when {@code varArgs} is true.
     */
    public static Object[] toJavaArguments(Executable exec, boolean varArgs, Object[] args, ScriptRuntime rt) {
        Class<?>[] parameterTypes = exec.getParameterTypes();
        if (!varArgs) {
            return Values.toJava(args, parameterTypes, rt);
        }
        int fixed = parameterTypes.length - 1;
        Class<?> comp = parameterTypes[fixed].getComponentType();
        Object[] java = new Object[parameterTypes.length];
        for (int i = 0; i < fixed; i++) {
            java[i] = Values.toJava(args[i], parameterTypes[i], rt);
        }
        int varCount = args.length - fixed;
        Object array = Array.newInstance(comp, varCount);
        for (int j = 0; j < varCount; j++) {
            Object elem = Values.toJava(args[fixed + j], comp, rt);
            Array.set(array, j, elem);
        }
        java[fixed] = array;
        return java;
    }

    /**
     * The overloads a Java method or constructor resolved to, by the kinds of the arguments
     * (compiler phase 3, but the interpreter uses it too) : resolution only depends on
     * them, so a call with arguments of the same kinds as an earlier one picks the same
     * overload. A kind is the argument's class, or for a Babble instance its class value,
     * whose Java interfaces it passes for. Errors are not cached, and only the first
     * {@link #CACHE_SIZE} kinds of argument lists are.
     */
    public static final class Cache<E extends Executable> {
        private record Entry<E extends Executable>(Object[] kinds, Resolved<E> resolved) {
        }

        private volatile Entry<E>[] entries = newEntries(0);

        @SuppressWarnings("unchecked")
        private static <E extends Executable> Entry<E>[] newEntries(int size) {
            return (Entry<E>[]) new Entry[size];
        }

        /** The overload resolved earlier for arguments of these kinds, or null. */
        public E find(Object[] arguments) {
            Resolved<E> r = findResolved(arguments);
            return r != null ? r.executable() : null;
        }

        public Resolved<E> findResolved(Object[] arguments) {
            for (Entry<E> entry : entries) {
                if (matches(entry.kinds(), arguments)) {
                    return entry.resolved();
                }
            }
            return null;
        }

        public synchronized void put(Object[] arguments, E executable) {
            putResolved(arguments, new Resolved<>(executable, false));
        }

        public synchronized void putResolved(Object[] arguments, Resolved<E> resolved) {
            Entry<E>[] current = entries;
            if (current.length >= CACHE_SIZE) {
                return;
            }
            Object[] kinds = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                kinds[i] = kind(arguments[i]);
            }
            Entry<E>[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = new Entry<>(kinds, resolved);
            entries = updated;
        }

        private static boolean matches(Object[] kinds, Object[] arguments) {
            if (kinds.length != arguments.length) {
                return false;
            }
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i] != kind(arguments[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    static final int CACHE_SIZE = 8;
    private static final Object NULL_KIND = new Object();
    private static final Object OBJECT_CLASS_KIND = new Object();

    /** What the applicability of an argument depends on (see {@link #isApplicable(Class, Object)}). */
    private static Object kind(Object argument) {
        if (argument instanceof JavaValue wrapper) {
            argument = wrapper.javaValue();
        }
        if (argument == null) {
            return NULL_KIND;
        }
        BabbleClass cls = BabbleObjects.babbleClass(argument);
        if (cls != null) {
            return cls;
        }
        // The anonymous class of an object literal can't be called, so it isn't a function.
        if (argument instanceof BabbleClass babbleClass && !Values.isBabbleCallable(babbleClass)) {
            return OBJECT_CLASS_KIND;
        }
        return argument.getClass();
    }

    public static <E extends Executable> E resolve(List<E> candidates, String description, Object[] arguments) {
        return resolveDetailed(candidates, description, arguments).executable();
    }

    public static <E extends Executable> Resolved<E> resolveDetailed(List<E> candidates, String description, Object[] arguments) {
        List<E> applicable = candidates.stream()
                .filter(candidate -> isApplicable(candidate, arguments))
                .toList();
        boolean varArgs = false;
        if (applicable.isEmpty()) {
            applicable = candidates.stream()
                    .filter(candidate -> candidate.isVarArgs() && isVarArgsApplicable(candidate, arguments))
                    .toList();
            varArgs = !applicable.isEmpty();
        }

        if (applicable.isEmpty()) {
            throw new BabbleException("No applicable overload of " + description + " for " + signature(arguments)
                    + " : candidates are " + describe(candidates));
        }

        E best = applicable.get(0);
        for (E candidate : applicable) {
            boolean more = varArgs ? isVarArgsMoreSpecific(candidate, best) : isMoreSpecific(candidate, best);
            boolean reverse = varArgs ? isVarArgsMoreSpecific(best, candidate) : isMoreSpecific(best, candidate);
            if (more && !reverse) {
                best = candidate;
            }
        }

        // The scan above finds a maximum, but nothing guarantees it is the only one :
        // "append(null)" fits both append(String) and append(char[]), which are unrelated.
        for (E candidate : applicable) {
            if (candidate == best) {
                continue;
            }
            boolean bestMore = varArgs ? isVarArgsMoreSpecific(best, candidate) : isMoreSpecific(best, candidate);
            boolean candMore = varArgs ? isVarArgsMoreSpecific(candidate, best) : isMoreSpecific(candidate, best);
            boolean sameParams = Arrays.equals(best.getParameterTypes(), candidate.getParameterTypes());
            if (!bestMore || (varArgs && candMore && !sameParams)) {
                throw new BabbleException("Ambiguous call to " + description + " for " + signature(arguments)
                        + " : candidates are " + describe(applicable));
            }
        }

        return new Resolved<>(best, varArgs);
    }

    private static boolean isApplicable(Executable candidate, Object[] arguments) {
        Class<?>[] parameterTypes = candidate.getParameterTypes();
        if (parameterTypes.length != arguments.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!isApplicable(parameterTypes[i], arguments[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVarArgsApplicable(Executable candidate, Object[] arguments) {
        Class<?>[] parameterTypes = candidate.getParameterTypes();
        int fixed = parameterTypes.length - 1;
        if (fixed < 0 || arguments.length < fixed || !parameterTypes[fixed].isArray()) {
            return false;
        }
        for (int i = 0; i < fixed; i++) {
            if (!isApplicable(parameterTypes[i], arguments[i])) {
                return false;
            }
        }
        Class<?> comp = parameterTypes[fixed].getComponentType();
        for (int i = fixed; i < arguments.length; i++) {
            if (!isApplicable(comp, arguments[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * A null argument is a wildcard : it fits any reference parameter and no primitive
     * one. It is never dereferenced, so a null never escapes as a bare
     * NullPointerException from the resolution itself.
     */
    private static boolean isApplicable(Class<?> parameterType, Object argument) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        if (argument instanceof JavaValue wrapper) {
            argument = wrapper.javaValue();
        }
        // A Babble instance is applicable where its class's declared Java interfaces are,
        // since it reaches Java as its face (Values.toJava).
        BabbleClass cls = BabbleObjects.babbleClass(argument);
        if (cls != null && cls.isJava(parameterType)) {
            return true;
        }
        // A Babble function converts to any functional interface.
        if (Values.isBabbleCallable(argument) && JavaTypes.isFunctionalInterface(parameterType)) {
            return true;
        }
        if (box(parameterType).isInstance(argument)) {
            return true;
        }
        // "Math.abs(2)" hands an Integer to abs(long), which Java widens. It does not
        // widen the wrappers themselves, so this only applies to a primitive parameter.
        return parameterType.isPrimitive() && isWidening(argument.getClass(), box(parameterType));
    }

    private static boolean isTypeMoreSpecific(Class<?> candidateType, Class<?> otherType) {
        Class<?> from = box(candidateType);
        Class<?> to = box(otherType);
        return to.isAssignableFrom(from) || isWidening(from, to);
    }

    /**
     * Whether every parameter of {@code candidate} could be passed on to {@code other},
     * i.e. whether {@code candidate} is at least as specific as it.
     */
    private static boolean isMoreSpecific(Executable candidate, Executable other) {
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] otherTypes = other.getParameterTypes();
        for (int i = 0; i < candidateTypes.length; i++) {
            if (!isTypeMoreSpecific(candidateTypes[i], otherTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /** JLS §15.12.2.5 variable-arity specificity comparison between two varargs executables. */
    private static boolean isVarArgsMoreSpecific(Executable candidate, Executable other) {
        Class<?>[] cTypes = candidate.getParameterTypes();
        Class<?>[] oTypes = other.getParameterTypes();
        int m = cTypes.length;
        int n = oTypes.length;
        Class<?> cComp = cTypes[m - 1].getComponentType();
        Class<?> oComp = oTypes[n - 1].getComponentType();
        int max = Math.max(m, n);
        for (int i = 0; i < max; i++) {
            Class<?> from = i < m - 1 ? cTypes[i] : cComp;
            Class<?> to = i < n - 1 ? oTypes[i] : oComp;
            if (!isTypeMoreSpecific(from, to)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWidening(Class<?> from, Class<?> to) {
        return JavaTypes.isWidening(from, to);
    }

    private static Class<?> box(Class<?> type) {
        return JavaTypes.box(type);
    }

    private static String signature(Object[] arguments) {
        return Arrays.stream(arguments)
                .map(argument -> argument == null ? "null" : JavaTypes.describe(argument))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String describe(List<? extends Executable> candidates) {
        return candidates.stream()
                .map(candidate -> Arrays.stream(candidate.getParameterTypes())
                        .map(Class::getCanonicalName)
                        .collect(Collectors.joining(", ", "(", ")")))
                .collect(Collectors.joining(", "));
    }
}
