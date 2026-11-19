package org.babblelang.runtime;


/**
 * The binary operators, shared by the interpreter and compiled code.
 * <p>
 * Results are boxed. Arithmetic promotes as Java does (JLS 5.6.2, BIP011-D4) : a double
 * operand makes a double, else a float a float, else a long a long, else bytes, shorts and
 * ints make an int, with Java's wrap-around. Other numbers (BigDecimal...) make a double.
 */
public final class Operators {
    private Operators() {
    }

    public static Object mul(Object a, Object b, OpSite site) {
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia * ib;
        }
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        return switch (promoted(na, nb)) {
            case INT -> (Object) (na.intValue() * nb.intValue());
            case LONG -> (Object) (na.longValue() * nb.longValue());
            case FLOAT -> (Object) (na.floatValue() * nb.floatValue());
            default -> (Object) (na.doubleValue() * nb.doubleValue());
        };
    }

    private static final int INT = 2;
    private static final int LONG = 3;
    private static final int FLOAT = 4;

    /** The rank of the type two numbers promote to (JLS 5.6.2) : int, long, float or double ({@link Values#numericRank}). */
    private static int promoted(Number a, Number b) {
        int ra = Values.numericRank(a);
        int rb = Values.numericRank(b);
        if (ra < 0 || rb < 0) {
            return 5;
        }
        return Math.max(INT, Math.max(ra, rb));
    }

    /**
     * {@code a / b} : always floating point, even between two ints, unlike {@code +}, {@code -}
     * and {@code *} : a float when one operand is a float and neither is a long or a double,
     * else a double. Dividing by zero gives an infinity or NaN (IEEE 754), not an error; only a
     * non-number fails.
     */
    public static Object div(Object a, Object b, OpSite site) {
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        int ra = Values.numericRank(na);
        int rb = Values.numericRank(nb);
        if (ra >= 0 && rb >= 0 && Math.max(ra, rb) == FLOAT && ra != LONG && rb != LONG) {
            return na.floatValue() / nb.floatValue();
        }
        return na.doubleValue() / nb.doubleValue();
    }

    public static Object add(Object a, Object b, OpSite site) {
        if (a instanceof String sa) {
            return sa + b;
        }
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia + ib;
        }
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        return switch (promoted(na, nb)) {
            case INT -> (Object) (na.intValue() + nb.intValue());
            case LONG -> (Object) (na.longValue() + nb.longValue());
            case FLOAT -> (Object) (na.floatValue() + nb.floatValue());
            default -> (Object) (na.doubleValue() + nb.doubleValue());
        };
    }

    /** The method {@code -a} calls on a Babble instance : {@code a.neg()}. */
    public static final String NEGATE = "neg";

    /**
     * The method an arithmetic operator calls when its left operand is a Babble instance :
     * {@code a + b} is {@code a.plus(b)}, then {@code minus}, {@code times} and {@code div}.
     * Null for the other operators.
     */
    public static String method(String operator) {
        return switch (operator) {
            case "+" -> "plus";
            case "-" -> "minus";
            case "*" -> "times";
            case "/" -> "div";
            default -> null;
        };
    }

    /**
     * The operator a compound assignment applies : {@code x += v} is {@code x = x + v}, then
     * {@code -=}, {@code *=} and {@code /=}. Null for a plain {@code =}.
     */
    public static String compound(String assignment) {
        return switch (assignment) {
            case "+=" -> "+";
            case "-=" -> "-";
            case "*=" -> "*";
            case "/=" -> "/";
            default -> null;
        };
    }

    /** An arithmetic operator, {@code +}, {@code -}, {@code *} or {@code /}, on two values that aren't Babble instances. */
    public static Object arithmetic(String operator, Object a, Object b, OpSite site) {
        return switch (operator) {
            case "+" -> add(a, b, site);
            case "-" -> sub(a, b, site);
            case "*" -> mul(a, b, site);
            case "/" -> div(a, b, site);
            default -> throw new IllegalArgumentException("Not an arithmetic operator : " + operator);
        };
    }

    /**
     * {@code -a} : a byte, short or int gives an int (wrapping, as {@code +} and {@code -} do),
     * a long a long, a float a float, and any other number a double.
     */
    public static Object negate(Object a, String text) {
        if (a instanceof Integer i) {
            return -i;
        }
        Number n = number(a, text);
        return switch (Values.numericRank(n)) {
            case 0, 1 -> (Object) (-n.intValue());
            case LONG -> (Object) (-n.longValue());
            case FLOAT -> (Object) (-n.floatValue());
            default -> (Object) (-n.doubleValue());
        };
    }

    public static Object sub(Object a, Object b, OpSite site) {
        if (a instanceof Integer ia && b instanceof Integer ib) {
            return ia - ib;
        }
        Number na = number(a, site.leftText());
        Number nb = number(b, site.rightText());
        return switch (promoted(na, nb)) {
            case INT -> (Object) (na.intValue() - nb.intValue());
            case LONG -> (Object) (na.longValue() - nb.longValue());
            case FLOAT -> (Object) (na.floatValue() - nb.floatValue());
            default -> (Object) (na.doubleValue() - nb.doubleValue());
        };
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
