package org.babblelang.runtime;

/**
 * Runtime carrier for Babble nominal objects ({@code class}, {@code record}, {@code object}, {@code
 * interface} instances) raised by {@code throw} (BIP028-D2).
 *
 * <p>Extends {@link RuntimeException} so Java host callers catching {@code RuntimeException} or
 * {@code Exception} intercept thrown Babble values cleanly, while Babble {@code catch} handlers
 * targeting {@code java.lang.Exception} or {@code java.lang.RuntimeException} skip this carrier so
 * Java and Babble catch clauses remain type-honest and order-independent.
 */
public final class ThrownException extends RuntimeException {
    private final Object value;

    public ThrownException(Object value) {
        super(ArraySupport.format(value));
        this.value = value;
    }

    /** The unwrapped Babble value passed to {@code throw}. */
    public Object value() {
        return value;
    }
}
