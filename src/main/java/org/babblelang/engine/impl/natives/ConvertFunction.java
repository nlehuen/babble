package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

/**
 * Built-in scalar conversions ({@code byte(x)}, {@code short(x)}, {@code int(x)},
 * {@code long(x)}, {@code float(x)}, {@code double(x)}, {@code bool(x)}, {@code char(x)},
 * {@code string(x)}) — BIP011-D3 §4.3.
 */
public final class ConvertFunction implements Callable {
    public enum Target {
        BYTE("byte"),
        SHORT("short"),
        INT("int"),
        LONG("long"),
        FLOAT("float"),
        DOUBLE("double"),
        BOOL("bool"),
        CHAR("char"),
        STRING("string");

        private final String babbleName;

        Target(String babbleName) {
            this.babbleName = babbleName;
        }

        public String babbleName() {
            return babbleName;
        }
    }

    private final Target target;

    private ConvertFunction(Target target) {
        this.target = target;
    }

    public static final ConvertFunction BYTE = new ConvertFunction(Target.BYTE);
    public static final ConvertFunction SHORT = new ConvertFunction(Target.SHORT);
    public static final ConvertFunction INT = new ConvertFunction(Target.INT);
    public static final ConvertFunction LONG = new ConvertFunction(Target.LONG);
    public static final ConvertFunction FLOAT = new ConvertFunction(Target.FLOAT);
    public static final ConvertFunction DOUBLE = new ConvertFunction(Target.DOUBLE);
    public static final ConvertFunction BOOL = new ConvertFunction(Target.BOOL);
    public static final ConvertFunction CHAR = new ConvertFunction(Target.CHAR);
    public static final ConvertFunction STRING = new ConvertFunction(Target.STRING);

    public Target target() {
        return target;
    }

    private String name() {
        return target.babbleName();
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException(name() + "() takes 1 argument, " + args.length + " given");
        }
        Object value = args[0];
        String text = site != null && site.argTexts().length == 1 ? site.argTexts()[0] : String.valueOf(value);
        return switch (target) {
            case BYTE -> toByte(value, text);
            case SHORT -> toShort(value, text);
            case INT -> toInt(value, text);
            case LONG -> toLong(value, text);
            case FLOAT -> toFloat(value, text);
            case DOUBLE -> toDouble(value, text);
            case BOOL -> toBool(value, text);
            case CHAR -> toChar(value, text);
            case STRING -> toStringValue(value);
        };
    }

    private static String rangeDetail(Object value, String text) {
        String shown = String.valueOf(value);
        return text.equals(shown) ? shown : text + " = " + shown;
    }

    public static Byte toByte(Object value, String text) {
        if (value instanceof Byte b) {
            return b;
        }
        if (value instanceof Character c) {
            int code = c;
            if (code > Byte.MAX_VALUE) {
                throw new BabbleException("Out of the byte range : " + rangeDetail(value, text));
            }
            return (byte) code;
        }
        if (value instanceof String s) {
            String trimmed = s.strip();
            try {
                return Byte.parseByte(trimmed);
            } catch (NumberFormatException e) {
                try {
                    new java.math.BigInteger(trimmed);
                    throw new BabbleException("Out of the byte range : \"" + s + "\"");
                } catch (NumberFormatException ignored) {
                    throw new BabbleException("Not a byte : \"" + s + "\"");
                }
            }
        }
        if (value instanceof Short || value instanceof Integer || value instanceof Long) {
            long l = ((Number) value).longValue();
            if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) {
                throw new BabbleException("Out of the byte range : " + rangeDetail(value, text));
            }
            return (byte) l;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || d >= 128.0 || d <= -129.0) {
                throw new BabbleException("Out of the byte range : " + rangeDetail(n, text));
            }
            return (byte) (int) d;
        }
        throw new BabbleException("Not a number : " + text);
    }

    public static Short toShort(Object value, String text) {
        if (value instanceof Short s) {
            return s;
        }
        if (value instanceof Character c) {
            int code = c;
            if (code > Short.MAX_VALUE) {
                throw new BabbleException("Out of the short range : " + rangeDetail(value, text));
            }
            return (short) code;
        }
        if (value instanceof String s) {
            String trimmed = s.strip();
            try {
                return Short.parseShort(trimmed);
            } catch (NumberFormatException e) {
                try {
                    new java.math.BigInteger(trimmed);
                    throw new BabbleException("Out of the short range : \"" + s + "\"");
                } catch (NumberFormatException ignored) {
                    throw new BabbleException("Not a short : \"" + s + "\"");
                }
            }
        }
        if (value instanceof Byte || value instanceof Integer || value instanceof Long) {
            long l = ((Number) value).longValue();
            if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) {
                throw new BabbleException("Out of the short range : " + rangeDetail(value, text));
            }
            return (short) l;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || d >= 32768.0 || d <= -32769.0) {
                throw new BabbleException("Out of the short range : " + rangeDetail(n, text));
            }
            return (short) (int) d;
        }
        throw new BabbleException("Not a number : " + text);
    }

    public static Integer toInt(Object value, String text) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Character c) {
            return (int) c;
        }
        if (value instanceof String s) {
            String trimmed = s.strip();
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new BabbleException("Not an int : \"" + s + "\"");
            }
        }
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new BabbleException("Out of the int range : " + rangeDetail(l, text));
            }
            return l.intValue();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || d >= 2147483648.0 || d <= -2147483649.0) {
                throw new BabbleException("Out of the int range : " + rangeDetail(n, text));
            }
            return (int) d;
        }
        throw new BabbleException("Not a number : " + text);
    }

    public static Long toLong(Object value, String text) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Character c) {
            return (long) c;
        }
        if (value instanceof String s) {
            String trimmed = s.strip();
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                try {
                    new java.math.BigInteger(trimmed);
                    throw new BabbleException("Out of the long range : \"" + s + "\"");
                } catch (NumberFormatException ignored) {
                    throw new BabbleException("Not a long : \"" + s + "\"");
                }
            }
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            // 9223372036854775808.0 == 2^63 (exact in IEEE 754); -9223372036854775808.0 == -2^63 (exact).
            if (Double.isNaN(d) || Double.isInfinite(d) || d >= 9223372036854775808.0 || d < -9223372036854775808.0) {
                throw new BabbleException("Out of the long range : " + rangeDetail(n, text));
            }
            return (long) d;
        }
        throw new BabbleException("Not a number : " + text);
    }

    public static Float toFloat(Object value, String text) {
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

    public static Boolean toBool(Object value, String text) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equals(s)) {
                return Boolean.TRUE;
            }
            if ("false".equals(s)) {
                return Boolean.FALSE;
            }
            throw new BabbleException("Not a boolean : \"" + s + "\"");
        }
        throw new BabbleException("Not a boolean : " + text);
    }

    public static Character toChar(Object value, String text) {
        if (value instanceof Character c) {
            return c;
        }
        if (value instanceof String s) {
            if (s.length() == 1) {
                return s.charAt(0);
            }
            throw new BabbleException("Expected single-character string : \"" + s + "\"");
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long l = ((Number) value).longValue();
            if (l < 0 || l > Character.MAX_VALUE) {
                throw new BabbleException("Out of the char range : " + rangeDetail(value, text));
            }
            return (char) l;
        }
        throw new BabbleException("Not a char : " + text);
    }

    public static String toStringValue(Object value) {
        Object unwrapped = Values.unwrap(value);
        String s = String.valueOf(unwrapped);
        return s != null ? s : "null";
    }

    @Override
    public String toString() {
        return name();
    }
}
