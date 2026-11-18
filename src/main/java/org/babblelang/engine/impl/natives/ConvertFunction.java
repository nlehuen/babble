package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

/**
 * {@code int(x)} and {@code double(x)} : number conversions. {@code int} truncates a double
 * towards zero ({@code int(-2.7) == -2}) and fails on one out of the int range, an infinity
 * or NaN ; {@code double} widens an int. Both parse a string ({@code int("42")},
 * {@code double("2.5")}), and accept Java numbers ({@code long}, {@code BigDecimal}…).
 */
public final class ConvertFunction implements Callable {
    private final boolean toInt;

    private ConvertFunction(boolean toInt) {
        this.toInt = toInt;
    }

    public static final ConvertFunction INT = new ConvertFunction(true);
    public static final ConvertFunction DOUBLE = new ConvertFunction(false);

    /** The conversion function a core type name denotes as a value ({@code int}, {@code double}). */
    public static Callable of(String coreType) {
        return switch (coreType) {
            case "int" -> INT;
            case "double" -> DOUBLE;
            default -> throw new BabbleException("Not a value : " + coreType);
        };
    }

    private String name() {
        return toInt ? "int" : "double";
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException(name() + "() takes 1 argument, " + args.length + " given");
        }
        Object value = args[0];
        String text = site != null && site.argTexts().length == 1 ? site.argTexts()[0] : String.valueOf(value);
        // Not a conditional expression : Integer and Double operands would both become double.
        if (toInt) {
            return toInt(value, text);
        }
        return toDouble(value, text);
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
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || d >= 2147483648.0 || d <= -2147483649.0) {
                String shown = String.valueOf(n);
                throw new BabbleException("Out of the int range : " + (text.equals(shown) ? shown : text + " = " + shown));
            }
            return (int) d;
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

    @Override
    public String toString() {
        return name();
    }
}
