package org.babblelang.runtime;

/**
 * The binary operators, shared by the interpreter and compiled code.
 *
 * <p>Results are boxed. Arithmetic on two Integers stays an Integer, with Java's wrap-around ; any
 * other pair of numbers gives a double.
 */
public final class Operators {
    private Operators() {}

    private static boolean isSubInt(Number n) {
        return n instanceof Byte || n instanceof Short || n instanceof Integer;
    }

    private static boolean isFloatDivOperand(Number n) {
        return n instanceof Byte
                || n instanceof Short
                || n instanceof Integer
                || n instanceof Float;
    }

    public static Object mul(Object a, Object b, OpSite site) {
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        if (isSubInt(na) && isSubInt(nb)) {
            return na.intValue() * nb.intValue();
        }
        if ((isSubInt(na) || na instanceof Long) && (isSubInt(nb) || nb instanceof Long)) {
            return na.longValue() * nb.longValue();
        }
        if ((isSubInt(na) || na instanceof Long || na instanceof Float)
                && (isSubInt(nb) || nb instanceof Long || nb instanceof Float)) {
            return na.floatValue() * nb.floatValue();
        }
        return na.doubleValue() * nb.doubleValue();
    }

    /**
     * {@code a / b} : floating-point division. Yields {@code float} when at least one operand is
     * {@code float} and neither is {@code double}, {@code long}, or another {@code Number};
     * otherwise yields {@code double}.
     */
    public static Object div(Object a, Object b, OpSite site) {
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        if ((na instanceof Float || nb instanceof Float)
                && isFloatDivOperand(na)
                && isFloatDivOperand(nb)) {
            return na.floatValue() / nb.floatValue();
        }
        return na.doubleValue() / nb.doubleValue();
    }

    /**
     * {@code a mod b} : floored integer modulo ({@link Math#floorMod}). {@code byte}/{@code
     * short}/{@code int} operands yield {@code int}; if either operand is {@code long}, yields
     * {@code long}. Rejects floating-point and non-integer operands.
     */
    public static Object mod(Object a, Object b, OpSite site) {
        Number na = integer(a, site.leftText());
        Number nb = integer(b, site.rightText());
        if (isSubInt(na) && isSubInt(nb)) {
            return Math.floorMod(na.intValue(), nb.intValue());
        }
        return Math.floorMod(na.longValue(), nb.longValue());
    }

    /**
     * {@code a // b} : floored integer division ({@link Math#floorDiv}). {@code byte}/{@code
     * short}/{@code int} operands yield {@code int}; if either operand is {@code long}, yields
     * {@code long}. Rejects floating-point and non-integer operands.
     */
    public static Object floorDiv(Object a, Object b, OpSite site) {
        Number na = integer(a, site.leftText());
        Number nb = integer(b, site.rightText());
        if (isSubInt(na) && isSubInt(nb)) {
            return Math.floorDiv(na.intValue(), nb.intValue());
        }
        return Math.floorDiv(na.longValue(), nb.longValue());
    }

    public static Object add(Object a, Object b, OpSite site) {
        if (a instanceof String sa) {
            return sa + ErrorFormat.format(b);
        }
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        if (isSubInt(na) && isSubInt(nb)) {
            return na.intValue() + nb.intValue();
        }
        if ((isSubInt(na) || na instanceof Long) && (isSubInt(nb) || nb instanceof Long)) {
            return na.longValue() + nb.longValue();
        }
        if ((isSubInt(na) || na instanceof Long || na instanceof Float)
                && (isSubInt(nb) || nb instanceof Long || nb instanceof Float)) {
            return na.floatValue() + nb.floatValue();
        }
        return na.doubleValue() + nb.doubleValue();
    }

    /** The method {@code -a} calls on a Babble instance : {@code a.neg()}. */
    public static final String NEGATE = "neg";

    /**
     * The method an arithmetic operator calls when its left operand is a Babble instance : {@code a
     * + b} is {@code a.plus(b)}, then {@code minus}, {@code times}, {@code div}, {@code floorDiv}
     * and {@code mod}. Null for the other operators.
     */
    public static String method(String operator) {
        return switch (operator) {
            case "+" -> "plus";
            case "-" -> "minus";
            case "*" -> "times";
            case "/" -> "div";
            case "//" -> "floorDiv";
            case "mod" -> "mod";
            default -> null;
        };
    }

    /**
     * The operator a compound assignment applies : {@code x += v} is {@code x = x + v}, then {@code
     * -=}, {@code *=}, {@code /=} and {@code //=}. Null for a plain {@code =}.
     */
    public static String compound(String assignment) {
        return switch (assignment) {
            case "+=" -> "+";
            case "-=" -> "-";
            case "*=" -> "*";
            case "/=" -> "/";
            case "//=" -> "//";
            default -> null;
        };
    }

    /**
     * An arithmetic operator, {@code +}, {@code -}, {@code *}, {@code /}, {@code //} or {@code
     * mod}, on two values that aren't Babble instances.
     */
    public static Object arithmetic(String operator, Object a, Object b, OpSite site) {
        return switch (operator) {
            case "+" -> add(a, b, site);
            case "-" -> sub(a, b, site);
            case "*" -> mul(a, b, site);
            case "/" -> div(a, b, site);
            case "//" -> floorDiv(a, b, site);
            case "mod" -> mod(a, b, site);
            default ->
                    throw new IllegalArgumentException("Not an arithmetic operator : " + operator);
        };
    }

    /**
     * {@code -a} : byte/short/int yield int, long yields long, float yields float, double/other
     * yield double.
     */
    public static Object negate(Object a, String text) {
        Number n = number(a, text);
        if (isSubInt(n)) {
            return -n.intValue();
        }
        if (n instanceof Long l) {
            return -l;
        }
        if (n instanceof Float f) {
            return -f;
        }
        return -n.doubleValue();
    }

    public static Object sub(Object a, Object b, OpSite site) {
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        if (isSubInt(na) && isSubInt(nb)) {
            return na.intValue() - nb.intValue();
        }
        if ((isSubInt(na) || na instanceof Long) && (isSubInt(nb) || nb instanceof Long)) {
            return na.longValue() - nb.longValue();
        }
        if ((isSubInt(na) || na instanceof Long || na instanceof Float)
                && (isSubInt(nb) || nb instanceof Long || nb instanceof Float)) {
            return na.floatValue() - nb.floatValue();
        }
        return na.doubleValue() - nb.doubleValue();
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
     * Equality never fails : null is only equal to null, numbers compare numerically across types
     * so that "1 == 1.0" holds, arrays compare deeply by contents, and everything else uses Java's
     * equals().
     */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb) == 0;
        }
        Object ua = Values.unwrap(a);
        Object ub = Values.unwrap(b);
        if (ua != null && ub != null && ua.getClass().isArray() && ub.getClass().isArray()) {
            return ErrorFormat.deepEquals(ua, ub);
        }
        return a.equals(b);
    }

    /**
     * Three-way comparison for the ordering operators. Values that have no ordering relative to
     * each other are a hard error : "1 &lt; \"x\"" is meaningless, and silently answering true or
     * false would hide the mistake.
     */
    private static int compare(Object a, Object b, OpSite site) {
        if (a instanceof Number na && b instanceof Number nb) {
            return compareNumbers(na, nb);
        }
        if (a instanceof Comparable<?> && a.getClass().isInstance(b)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) a;
            return comparable.compareTo(b);
        }
        throw new BabbleException(
                "Not comparable : " + site.leftText() + " and " + site.rightText());
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

    private static Number integer(Object a, String text) {
        if (a instanceof Number n && isIntegral(n)) {
            return n;
        }
        throw new BabbleException("Not an integer : " + text);
    }
}
