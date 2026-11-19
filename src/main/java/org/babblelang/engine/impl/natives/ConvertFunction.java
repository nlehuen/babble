package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

/**
 * The scalar conversions (BIP011-D3) : {@code byte(x)}, {@code short(x)}, {@code int(x)},
 * {@code long(x)}, {@code float(x)}, {@code double(x)}, {@code bool(x)}, {@code char(x)} and
 * {@code string(x)}, what a core type name is as a value.
 * <p>
 * The integral ones truncate a floating-point number towards zero ({@code int(-2.7) == -2}),
 * take a character's code unit ({@code int('A') == 65}) and fail on a value out of their range,
 * an infinity or NaN ; {@code float} and {@code double} convert any number. All of them parse a
 * string ({@code int("42")}, {@code double("2.5")}, {@code bool("true")}), and accept Java
 * numbers ({@code BigDecimal}…). {@code char} takes a code unit or a one-character string, and
 * {@code string} formats anything, null included, as {@code "" + x} does.
 */
public final class ConvertFunction implements Callable {
    private final String name;

    private ConvertFunction(String name) {
        this.name = name;
    }

    public static final ConvertFunction BYTE = new ConvertFunction("byte");
    public static final ConvertFunction SHORT = new ConvertFunction("short");
    public static final ConvertFunction INT = new ConvertFunction("int");
    public static final ConvertFunction LONG = new ConvertFunction("long");
    public static final ConvertFunction FLOAT = new ConvertFunction("float");
    public static final ConvertFunction DOUBLE = new ConvertFunction("double");
    public static final ConvertFunction BOOL = new ConvertFunction("bool");
    public static final ConvertFunction CHAR = new ConvertFunction("char");
    public static final ConvertFunction STRING = new ConvertFunction("string");

    /**
     * What a core type name is as a value : a scalar's conversion function ({@code int},
     * {@code double}...), or a collection's factory ({@code list}...).
     */
    public static Callable of(String coreType) {
        CollectionFactory factory = CollectionFactory.of(coreType);
        if (factory != null) {
            return factory;
        }
        return switch (coreType) {
            case "byte" -> BYTE;
            case "short" -> SHORT;
            case "int" -> INT;
            case "long" -> LONG;
            case "float" -> FLOAT;
            case "double" -> DOUBLE;
            case "bool" -> BOOL;
            case "char" -> CHAR;
            case "string" -> STRING;
            default -> throw new BabbleException("Not a value : " + coreType);
        };
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException(name + "() takes 1 argument, " + args.length + " given");
        }
        Object value = Values.unwrap(args[0]);
        String text = site != null && site.argTexts().length == 1 ? site.argTexts()[0] : String.valueOf(value);
        // Each arm boxed apart : a switch expression would promote its numbers to one type.
        Object result;
        switch (name) {
            case "byte" -> result = Byte.valueOf((byte) integral(value, text, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case "short" -> result = Short.valueOf((short) integral(value, text, Short.MIN_VALUE, Short.MAX_VALUE));
            case "int" -> result = toInt(value, text);
            case "long" -> result = Long.valueOf(integral(value, text, Long.MIN_VALUE, Long.MAX_VALUE));
            case "float" -> result = toFloat(value, text);
            case "double" -> result = toDouble(value, text);
            case "bool" -> result = toBool(value, text);
            case "char" -> result = toChar(value, text);
            default -> result = "" + value;
        }
        return result;
    }

    public static Integer toInt(Object value, String text) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                throw new BabbleException("Not an int : \"" + s + "\"");
            }
        }
        return (int) new ConvertFunction("int").integral(value, text, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** A number, character or string as an integer of [min, max], failing outside it. */
    private long integral(Object value, String text, long min, long max) {
        if (value instanceof Character c) {
            return inRange(c, value, text, min, max);
        }
        if (value instanceof String s) {
            long parsed;
            try {
                parsed = Long.parseLong(s.strip());
            } catch (NumberFormatException e) {
                throw new BabbleException(article() + " : \"" + s + "\"");
            }
            return inRange(parsed, value, text, min, max);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return inRange(((Number) value).longValue(), value, text, min, max);
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            // Above max : max + 1 is a power of two, exact as a double, unlike Long.MAX_VALUE.
            if (Double.isNaN(d) || d >= (double) max + 1 || d <= (double) min - 1) {
                throw outOfRange(value, text);
            }
            return (long) d;
        }
        throw new BabbleException("Not a number : " + text);
    }

    private long inRange(long v, Object value, String text, long min, long max) {
        if (v < min || v > max) {
            throw outOfRange(value, text);
        }
        return v;
    }

    private BabbleException outOfRange(Object value, String text) {
        String shown = String.valueOf(value);
        return new BabbleException("Out of the " + name + " range : " + (text.equals(shown) ? shown : text + " = " + shown));
    }

    private String article() {
        return (name.equals("int") ? "Not an " : "Not a ") + name;
    }

    private static Float toFloat(Object value, String text) {
        if (value instanceof Float f) {
            return f;
        }
        if (value instanceof String s) {
            try {
                return Float.parseFloat(s.strip());
            } catch (NumberFormatException e) {
                throw new BabbleException("Not a float : \"" + s + "\"");
            }
        }
        if (value instanceof Number n) {
            return n.floatValue();
        }
        throw new BabbleException("Not a number : " + text);
    }

    public static Double toDouble(Object value, String text) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                throw new BabbleException("Not a double : \"" + s + "\"");
            }
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new BabbleException("Not a number : " + text);
    }

    private static Boolean toBool(Object value, String text) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return switch (s) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new BabbleException("Not a bool : \"" + s + "\"");
            };
        }
        throw new BabbleException("Not a bool : " + text);
    }

    private Character toChar(Object value, String text) {
        if (value instanceof Character c) {
            return c;
        }
        if (value instanceof String s) {
            if (s.length() != 1) {
                throw new BabbleException("Not a char : \"" + s + "\"");
            }
            return s.charAt(0);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return (char) inRange(((Number) value).longValue(), value, text, Character.MIN_VALUE, Character.MAX_VALUE);
        }
        throw new BabbleException("Not a char : " + text);
    }

    @Override
    public String toString() {
        return name;
    }
}
