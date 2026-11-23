package org.babblelang.runtime;

import java.lang.reflect.InvocationTargetException;

/**
 * Runtime helpers for direct JVM {@link Throwable} propagation, reflection unwrapping,
 * custom Babble value throwing, and {@code finally} suppression (BIP028-D2, BIP028-D3, BIP028-D6).
 */
public final class Throwables {
    private Throwables() {
    }

    /**
     * Rethrows any {@link Throwable} (including checked Java exceptions such as
     * {@link java.io.IOException}) without wrapping it, matching JVM bytecode's unchecked
     * {@code ATHROW} semantics (BIP028-D3).
     */
    public static RuntimeException sneakyThrow(Throwable t) {
        Throwables.<RuntimeException>doThrow(t);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void doThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Peels reflective {@link InvocationTargetException} wrappers and rethrows the underlying
     * {@link Throwable} directly (BIP028-D3).
     */
    public static RuntimeException unwrapReflection(Throwable t) {
        while (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            t = ite.getCause();
        }
        return sneakyThrow(t);
    }

    /**
     * Converts a Babble value into a JVM {@link Throwable} for {@code throw expr} (BIP028-D2):
     * Java {@link Throwable} instances (including {@link BabbleException}) are returned unwrapped,
     * and Babble nominal objects are carried in a {@link ThrownException}.
     */
    public static Throwable toThrowable(Object value) {
        if (value == null) {
            throw new BabbleException("Cannot throw null");
        }
        Object unwrapped = Values.unwrap(value);
        if (unwrapped instanceof Throwable t) {
            return t;
        }
        return new ThrownException(value);
    }

    /**
     * Extracts the value bound to a Babble {@code catch} parameter: unwraps {@link ThrownException}
     * to its Babble payload and passes Java {@link Throwable}s through via {@link Values#fromJava}.
     */
    public static Object unwrapForCatch(Throwable t) {
        return t instanceof ThrownException te ? te.value() : Values.fromJava(t);
    }

    /**
     * Attaches {@code secondary} thrown by a {@code finally} block to an in-flight {@code primary}
     * exception, guarding against self-suppression (BIP028-D6).
     */
    public static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary != null && secondary != null && primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    /**
     * Tests whether {@code t} matches a Babble {@code catch} clause at runtime (BIP028-D2, BIP028-D4).
     *
     * @param t                    the caught JVM throwable
     * @param typeDescriptor       encoded {@link Types} descriptor, or {@code null} for untyped catch-all
     * @param catchesNonErrorAny   true for untyped catch-all (matches all non-{@link Error} exceptions)
     * @param catchesJavaThrowable true if the catch type explicitly accepts {@link Throwable} directly
     */
    public static boolean matchesCatch(Throwable t, String typeDescriptor,
                                       boolean catchesNonErrorAny, boolean catchesJavaThrowable) {
        if (catchesNonErrorAny) {
            return !(t instanceof Error);
        }
        if (t instanceof ThrownException te) {
            if (catchesJavaThrowable) {
                return true;
            }
            return Types.matches(te.value(), typeDescriptor);
        }
        return Types.matches(t, typeDescriptor);
    }
}
