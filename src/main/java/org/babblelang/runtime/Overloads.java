package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

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
 * Only fixed-arity applicability is implemented : varargs methods are matched on their
 * declared array parameter, not expanded.
 */
public final class Overloads {
    private Overloads() {
    }

    public static Method resolveMethod(Class<?> clazz, String name, Object[] arguments) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : clazz.getMethods()) {
            // Bridge and synthetic methods duplicate a real one with erased parameters,
            // and would only ever add spurious ambiguity.
            if (method.getName().equals(name) && !method.isBridge() && !method.isSynthetic()) {
                candidates.add(method);
            }
        }
        return resolve(candidates, clazz.getCanonicalName() + "." + name, arguments);
    }

    public static Constructor<?> resolveConstructor(Class<?> clazz, Object[] arguments) {
        return resolve(Arrays.asList(clazz.getConstructors()), clazz.getCanonicalName(), arguments);
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
        private record Entry<E>(Object[] kinds, E executable) {
        }

        private volatile Entry<E>[] entries = newEntries(0);

        @SuppressWarnings("unchecked")
        private static <E> Entry<E>[] newEntries(int size) {
            return (Entry<E>[]) new Entry[size];
        }

        /** The overload resolved earlier for arguments of these kinds, or null. */
        public E find(Object[] arguments) {
            for (Entry<E> entry : entries) {
                if (matches(entry.kinds(), arguments)) {
                    return entry.executable();
                }
            }
            return null;
        }

        public synchronized void put(Object[] arguments, E executable) {
            Entry<E>[] current = entries;
            if (current.length >= CACHE_SIZE) {
                return;
            }
            Object[] kinds = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                kinds[i] = kind(arguments[i]);
            }
            Entry<E>[] updated = Arrays.copyOf(current, current.length + 1);
            updated[current.length] = new Entry<>(kinds, executable);
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
        if (argument instanceof BabbleInstance instance) {
            return instance.babbleClass();
        }
        // The anonymous class of an object literal can't be called, so it isn't a function.
        if (argument instanceof BabbleClass babbleClass && !Values.isBabbleCallable(babbleClass)) {
            return OBJECT_CLASS_KIND;
        }
        return argument.getClass();
    }

    public static <E extends Executable> E resolve(List<E> candidates, String description, Object[] arguments) {
        List<E> applicable = candidates.stream()
                .filter(candidate -> isApplicable(candidate, arguments))
                .toList();

        if (applicable.isEmpty()) {
            throw new BabbleException("No applicable overload of " + description + " for " + signature(arguments)
                    + " : candidates are " + describe(candidates));
        }

        E best = applicable.get(0);
        for (E candidate : applicable) {
            if (isMoreSpecific(candidate, best)) {
                best = candidate;
            }
        }

        // The scan above finds a maximum, but nothing guarantees it is the only one :
        // "append(null)" fits both append(String) and append(char[]), which are unrelated.
        for (E candidate : applicable) {
            if (candidate != best && !isMoreSpecific(best, candidate)) {
                throw new BabbleException("Ambiguous call to " + description + " for " + signature(arguments)
                        + " : candidates are " + describe(applicable));
            }
        }

        return best;
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
        if (argument instanceof BabbleInstance instance && instance.babbleClass().isJava(parameterType)) {
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

    /**
     * Whether every parameter of {@code candidate} could be passed on to {@code other},
     * i.e. whether {@code candidate} is at least as specific as it.
     */
    private static boolean isMoreSpecific(Executable candidate, Executable other) {
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] otherTypes = other.getParameterTypes();
        for (int i = 0; i < candidateTypes.length; i++) {
            Class<?> from = box(candidateTypes[i]);
            Class<?> to = box(otherTypes[i]);
            if (!to.isAssignableFrom(from) && !isWidening(from, to)) {
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
