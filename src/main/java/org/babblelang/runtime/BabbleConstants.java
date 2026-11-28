package org.babblelang.runtime;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code CONSTANT_Dynamic} ({@code condy}) bootstrap methods for compact, lazily materialized
 * runtime constant arrays ({@code String[]}, {@code boolean[]}, {@code Class<?>[]}) without static
 * fields or {@code <clinit>} builder bytecode.
 *
 * <p>Deliberately depends only on {@code java.base} so referencing array constants never pulls
 * unrelated runtime subsystems into tree-shaken AOT jars.
 */
public final class BabbleConstants {
    public static final String[] EMPTY_STRINGS = new String[0];
    public static final boolean[] EMPTY_BOOLEANS = new boolean[0];
    public static final Class<?>[] EMPTY_CLASSES = new Class<?>[0];

    private BabbleConstants() {}

    /**
     * Decodes a length-prefixed string array produced by {@code ConstantPool.packStrings}: {@code
     * ""} is empty, {@code 'n'} is {@code null}, and {@code <len>:<chars>} is a non-null string.
     */
    public static String[] unpackStrings(String packed) {
        if (packed == null || packed.isEmpty()) {
            return EMPTY_STRINGS;
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = packed.length();
        while (i < n) {
            char ch = packed.charAt(i);
            if (ch == 'n') {
                out.add(null);
                i++;
            } else {
                int colon = packed.indexOf(':', i);
                int len = Integer.parseInt(packed, i, colon, 10);
                int start = colon + 1;
                out.add(packed.substring(start, start + len));
                i = start + len;
            }
        }
        return out.toArray(EMPTY_STRINGS);
    }

    public static String[] stringArray(
            MethodHandles.Lookup lookup, String name, Class<String[]> type, String packed) {
        return unpackStrings(packed);
    }

    public static boolean[] booleanArray(
            MethodHandles.Lookup lookup, String name, Class<boolean[]> type, String bits) {
        if (bits == null || bits.isEmpty()) {
            return EMPTY_BOOLEANS;
        }
        boolean[] out = new boolean[bits.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bits.charAt(i) == '1';
        }
        return out;
    }

    public static Class<?>[] classArray(
            MethodHandles.Lookup lookup, String name, Class<Class<?>[]> type, Class<?>... classes) {
        return classes.length == 0 ? EMPTY_CLASSES : classes;
    }
}
