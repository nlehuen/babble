package org.babblelang.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight value/array formatting, structural equality/hashing, and exception normalization
 * split out of {@link ArraySupport} so programs that do not use {@code array of T} operations do
 * not pull array creation/method dispatch into tree-shaken AOT jars.
 */
public final class ErrorFormat {
    private static final Pattern COMPILED_ARRAY_CLASS =
            Pattern.compile("(\\[+)L(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*);");
    private static final Pattern COMPILED_BABBLE_CLASS =
            Pattern.compile("(?:[\\w.$]+\\$)?C\\d+\\$([A-Za-z_][\\w]*)");

    private ErrorFormat() {}

    /** Deep structural equality matching {@code Arrays.deepEquals} with Babble's {@code ==}. */
    public static boolean deepEquals(Object a, Object b) {
        Object ua = Values.unwrap(a);
        Object ub = Values.unwrap(b);
        if (ua == ub) {
            return true;
        }
        if (ua == null || ub == null) {
            return false;
        }
        if (ua instanceof Object[] oa && ub instanceof Object[] ob) {
            if (oa.length != ob.length) {
                return false;
            }
            for (int i = 0; i < oa.length; i++) {
                if (!Operators.equal(oa[i], ob[i])) {
                    return false;
                }
            }
            return true;
        }
        if (ua instanceof int[] ia && ub instanceof int[] ib) return Arrays.equals(ia, ib);
        if (ua instanceof double[] da && ub instanceof double[] db) return Arrays.equals(da, db);
        if (ua instanceof long[] la && ub instanceof long[] lb) return Arrays.equals(la, lb);
        if (ua instanceof boolean[] ba && ub instanceof boolean[] bb) return Arrays.equals(ba, bb);
        if (ua instanceof byte[] ya && ub instanceof byte[] yb) return Arrays.equals(ya, yb);
        if (ua instanceof short[] sa && ub instanceof short[] sb) return Arrays.equals(sa, sb);
        if (ua instanceof float[] fa && ub instanceof float[] fb) return Arrays.equals(fa, fb);
        if (ua instanceof char[] ca && ub instanceof char[] cb) return Arrays.equals(ca, cb);
        return false;
    }

    /** Content hash consistent with {@link #deepEquals}. */
    public static int deepHashCode(Object a) {
        Object ua = Values.unwrap(a);
        if (ua == null) {
            return 0;
        }
        if (ua instanceof Object[] oa) {
            int hash = 1;
            for (Object e : oa) {
                hash = 31 * hash + Values.hash(e);
            }
            return hash;
        }
        if (ua instanceof int[] ia) return Arrays.hashCode(ia);
        if (ua instanceof double[] da) return Arrays.hashCode(da);
        if (ua instanceof long[] la) return Arrays.hashCode(la);
        if (ua instanceof boolean[] ba) return Arrays.hashCode(ba);
        if (ua instanceof byte[] ya) return Arrays.hashCode(ya);
        if (ua instanceof short[] sa) return Arrays.hashCode(sa);
        if (ua instanceof float[] fa) return Arrays.hashCode(fa);
        if (ua instanceof char[] ca) return Arrays.hashCode(ca);
        return Values.hash(ua);
    }

    /** Formats an array as {@code [a, b]}, matching {@code Arrays.deepToString} semantics. */
    public static String deepToString(Object a) {
        Object ua = Values.unwrap(a);
        if (ua == null) {
            return "null";
        }
        if (ua instanceof int[] ia) return Arrays.toString(ia);
        if (ua instanceof double[] da) return Arrays.toString(da);
        if (ua instanceof long[] la) return Arrays.toString(la);
        if (ua instanceof boolean[] ba) return Arrays.toString(ba);
        if (ua instanceof byte[] ya) return Arrays.toString(ya);
        if (ua instanceof short[] sa) return Arrays.toString(sa);
        if (ua instanceof float[] fa) return Arrays.toString(fa);
        if (ua instanceof char[] ca) return Arrays.toString(ca);
        if (ua instanceof Object[] oa) {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            StringBuilder sb = new StringBuilder();
            appendDeep(oa, sb, seen);
            return sb.toString();
        }
        return String.valueOf(ua);
    }

    private static void appendDeep(Object[] oa, StringBuilder sb, Set<Object> seen) {
        if (!seen.add(oa)) {
            sb.append("[...]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < oa.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object elem = Values.fromJava(oa[i]);
            if (elem == null) {
                sb.append("null");
            } else if (elem instanceof Object[] nested) {
                appendDeep(nested, sb, seen);
            } else if (elem.getClass().isArray()) {
                sb.append(deepToString(elem));
            } else {
                sb.append(elem);
            }
        }
        sb.append(']');
        seen.remove(oa);
    }

    /** Formats any Babble value for display, printing arrays by contents. */
    public static String format(Object value) {
        Object raw = Values.unwrap(value);
        return raw != null && raw.getClass().isArray() ? deepToString(raw) : String.valueOf(raw);
    }

    /**
     * Formats a host Java {@link Throwable} consistently across modes (stripping Helpful NPE
     * details).
     */
    public static String formatJavaException(Throwable t) {
        String name = t.getClass().getName();
        String msg = t instanceof NullPointerException ? null : t.getMessage();
        return msg == null ? name : name + " : " + msg;
    }

    private static boolean isInternalEngineFrame(StackTraceElement top) {
        String cls = top.getClassName();
        if (cls.equals("org.babblelang.engine.impl.Interpreter")
                && "visitThrowExpression".equals(top.getMethodName())) {
            return false;
        }
        if (cls.equals("org.babblelang.compiler.ScriptClassLoader")
                || cls.equals("org.babblelang.compiler.CompositeClassLoader")) {
            return false;
        }
        return cls.startsWith("org.babblelang.engine.impl.")
                        && !cls.startsWith("org.babblelang.engine.impl.natives.")
                || cls.startsWith("org.babblelang.analysis.")
                || cls.startsWith("org.babblelang.compiler.")
                || cls.startsWith("org.babblelang.ir.")
                || cls.startsWith("org.babblelang.opt.");
    }

    /** Normalizes uncaught runtime exceptions for script boundaries (BIP028-D3). */
    public static String formatThrowable(Throwable t) {
        if (t instanceof BabbleException) {
            return t.toString();
        }
        if (t instanceof ThrownException te) {
            return BabbleException.class.getName() + ": " + format(te.value());
        }
        if (t instanceof ArrayStoreException) {
            String raw = t.toString();
            Matcher arrMatcher = COMPILED_ARRAY_CLASS.matcher(raw);
            StringBuilder sb = new StringBuilder();
            while (arrMatcher.find()) {
                String prefix = "array of ".repeat(arrMatcher.group(1).length());
                arrMatcher.appendReplacement(
                        sb, Matcher.quoteReplacement(prefix + arrMatcher.group(2)));
            }
            arrMatcher.appendTail(sb);
            return COMPILED_BABBLE_CLASS.matcher(sb.toString()).replaceAll("$1");
        }
        if (t instanceof ArrayIndexOutOfBoundsException
                || t instanceof NegativeArraySizeException) {
            return t.toString();
        }
        StackTraceElement[] stack = t.getStackTrace();
        if (stack.length > 0 && isInternalEngineFrame(stack[0])) {
            return t.toString();
        }
        return BabbleException.class.getName() + ": " + formatJavaException(t);
    }
}
