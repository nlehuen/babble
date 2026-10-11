package org.babblelang.runtime;

public final class Truth {
    private Truth() {
    }

    /**
     * A Boolean is itself, a number is true when it is not zero, and anything else is
     * true when it is not null.
     */
    public static boolean test(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        } else if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        } else {
            return value != null;
        }
    }
}
