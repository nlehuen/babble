package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
final class Overloads {
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            void.class, Void.class);

    /** Widening primitive conversions (JLS 5.1.2), expressed over the wrapper types. */
    private static final Map<Class<?>, List<Class<?>>> WIDENINGS = Map.of(
            Byte.class, List.of(Short.class, Integer.class, Long.class, Float.class, Double.class),
            Short.class, List.of(Integer.class, Long.class, Float.class, Double.class),
            Character.class, List.of(Integer.class, Long.class, Float.class, Double.class),
            Integer.class, List.of(Long.class, Float.class, Double.class),
            Long.class, List.of(Float.class, Double.class),
            Float.class, List.of(Double.class));

    private Overloads() {
    }

    static Method resolveMethod(Class<?> clazz, String name, Object[] arguments) {
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

    static Constructor<?> resolveConstructor(Class<?> clazz, Object[] arguments) {
        return resolve(Arrays.asList(clazz.getConstructors()), clazz.getCanonicalName(), arguments);
    }

    private static <E extends Executable> E resolve(List<E> candidates, String description, Object[] arguments) {
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
        return WIDENINGS.getOrDefault(from, List.of()).contains(to);
    }

    private static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? BOXES.get(type) : type;
    }

    private static String signature(Object[] arguments) {
        return Arrays.stream(arguments)
                .map(argument -> argument == null ? "null" : argument.getClass().getCanonicalName())
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
