package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

/**
 * The binary operators, shared by the interpreter and compiled code.
 * <p>
 * Results are boxed. Arithmetic on two Integers stays an Integer, with Java's
 * wrap-around ; any other pair of numbers gives a double.
 */
public final class Operators {
    private Operators() {
    }

    public static Object mul(Object a, Object b, OpSite site) {
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia * ib;
        }
        return number(a, site.leftText()).doubleValue() * number(b, site.rightText()).doubleValue();
    }

    public static Object div(Object a, Object b, OpSite site) {
        return number(a, site.leftText()).doubleValue() / number(b, site.rightText()).doubleValue();
    }

    public static Object add(Object a, Object b, OpSite site) {
        if (a instanceof String sa) {
            return sa + b;
        }
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia + ib;
        }
        return number(a, site.leftText()).doubleValue() + number(b, site.rightText()).doubleValue();
    }

    public static Object sub(Object a, Object b, OpSite site) {
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia - ib;
        }
        return number(a, site.leftText()).doubleValue() - number(b, site.rightText()).doubleValue();
    }

    public static Object lt(Object a, Object b, OpSite site) {
        return compare(a, b, site) < 0;
    }

    public static Object le(Object a, Object b, OpSite site) {
        return compare(a, b, site) <= 0;
    }

    public static Object gt(Object a, Object b, OpSite site) {
        return compare(a, b, site) > 0;
    }

    public static Object ge(Object a, Object b, OpSite site) {
        return compare(a, b, site) >= 0;
    }

    public static Object eq(Object a, Object b, OpSite site) {
        return equal(a, b);
    }

    public static Object ne(Object a, Object b, OpSite site) {
        return !equal(a, b);
    }

    /**
     * Equality never fails : null is only equal to null, numbers compare numerically
     * across types so that "1 == 1.0" holds, and everything else uses Java's equals().
     */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb) == 0;
        }
        return a.equals(b);
    }

    /**
     * Three-way comparison for the ordering operators. Values that have no ordering
     * relative to each other are a hard error : "1 &lt; \"x\"" is meaningless, and
     * silently answering true or false would hide the mistake.
     */
    private static int compare(Object a, Object b, OpSite site) {
        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb);
        }
        if (a instanceof Comparable<?> && a.getClass().isInstance(b)) {
            //noinspection unchecked
            return ((Comparable<Object>) a).compareTo(b);
        }
        throw new BabbleException("Not comparable : " + site.leftText() + " and " + site.rightText());
    }

    private static int compareNumbers(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private static Number number(Object a, String text) {
        if (a instanceof Number n) {
            return n;
        }
        throw new BabbleException("Not a number : " + text);
    }
}
