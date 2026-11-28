package org.babblelang.runtime;

import java.util.Collection;
import java.util.Map;

/**
 * Runtime support for {@code match} pattern matching ({@code BIP038}): membership tests ({@code
 * in}), null-safe relational and range pattern checks, AOT Java {@code enum} switch-map
 * construction, and defensive non-exhaustive fallback. Depends only on {@code
 * org.babblelang.runtime} and {@code java.base}.
 */
public final class MatchSupport {
    public static final int OP_LT = 0;
    public static final int OP_LTE = 1;
    public static final int OP_GT = 2;
    public static final int OP_GTE = 3;

    private static final OpSite MATCH_OP_SITE = new OpSite("match", "pattern");

    private MatchSupport() {}

    /**
     * Evaluates {@code value in container} for {@link Range}, {@link Collection}, {@link Map}, or
     * array.
     */
    public static boolean inContainer(Object value, Object container) {
        Object rawContainer = Values.unwrap(container);
        if (rawContainer == null) {
            return false;
        }
        if (rawContainer instanceof Range<?> range) {
            return range.contains(value);
        }
        if (rawContainer instanceof Collection<?> col) {
            try {
                return col.contains(value);
            } catch (ClassCastException | NullPointerException e) {
                return false;
            }
        }
        if (rawContainer instanceof Map<?, ?> map) {
            try {
                return map.containsKey(value);
            } catch (ClassCastException | NullPointerException e) {
                return false;
            }
        }
        if (rawContainer.getClass().isArray()) {
            return ArraySupport.contains(rawContainer, value);
        }
        throw new BabbleException(
                "Cannot test membership with 'in' on " + Types.describe(rawContainer));
    }

    /**
     * Evaluates relational ordering pattern ({@code <}, {@code <=}, {@code >}, {@code >=}). A
     * {@code null} scrutinee never matches an ordering pattern ({@code BIP038} §4.2).
     */
    public static boolean relational(Object scrutinee, Object rhs, int op) {
        if (scrutinee == null || rhs == null) {
            return false;
        }
        return switch (op) {
            case OP_LT -> Boolean.TRUE.equals(Operators.lt(scrutinee, rhs, MATCH_OP_SITE));
            case OP_LTE -> Boolean.TRUE.equals(Operators.le(scrutinee, rhs, MATCH_OP_SITE));
            case OP_GT -> Boolean.TRUE.equals(Operators.gt(scrutinee, rhs, MATCH_OP_SITE));
            case OP_GTE -> Boolean.TRUE.equals(Operators.ge(scrutinee, rhs, MATCH_OP_SITE));
            default -> throw new IllegalArgumentException("Unknown relational op : " + op);
        };
    }

    /**
     * Evaluates a bare range-membership pattern ({@code lo..hi}, {@code lo..<hi}, or stepped)
     * without allocating a {@link Range} object. Returns {@code false} if {@code value} is {@code
     * null}.
     */
    public static boolean rangeMatch(
            Object value, Object lo, Object hi, boolean inclusive, Object stepObj) {
        if (lo == null || hi == null) {
            throw new BabbleException("Range bound cannot be null");
        }
        if (stepObj != null) {
            if (!(stepObj instanceof Number sn)) {
                throw new BabbleException("Range step must be positive : " + stepObj);
            }
            if (sn instanceof Long) {
                Range.validateStep(sn.longValue());
            } else {
                Range.validateStep(sn.intValue());
            }
        }
        if (value == null) {
            return false;
        }
        if (value instanceof Number vn && lo instanceof Number lon && hi instanceof Number hin) {
            boolean integral = isIntegral(vn) && isIntegral(lon) && isIntegral(hin);
            if (integral) {
                long v = vn.longValue();
                long l = lon.longValue();
                long h = hin.longValue();
                boolean inBounds = v >= l && (inclusive ? v <= h : v < h);
                if (!inBounds) {
                    return false;
                }
                if (stepObj != null) {
                    long step = ((Number) stepObj).longValue();
                    return Math.floorMod(v - l, step) == 0L;
                }
                return true;
            }
            double v = vn.doubleValue();
            double l = lon.doubleValue();
            double h = hin.doubleValue();
            return !Double.isNaN(v) && v >= l && (inclusive ? v <= h : v < h);
        }
        if (value instanceof Character vc
                && lo instanceof Character loc
                && hi instanceof Character hic) {
            char v = vc;
            char l = loc;
            char h = hic;
            boolean inBounds = v >= l && (inclusive ? v <= h : v < h);
            if (!inBounds) {
                return false;
            }
            if (stepObj != null) {
                int step = ((Number) stepObj).intValue();
                return Math.floorMod(v - l, step) == 0;
            }
            return true;
        }
        if (!Boolean.TRUE.equals(Operators.ge(value, lo, MATCH_OP_SITE))) {
            return false;
        }
        return inclusive
                ? Boolean.TRUE.equals(Operators.le(value, hi, MATCH_OP_SITE))
                : Boolean.TRUE.equals(Operators.lt(value, hi, MATCH_OP_SITE));
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long;
    }

    /**
     * Builds an AOT-resilient Java {@code enum} switch-map array mapping each runtime {@link
     * Enum#ordinal()} to its switch case index (or {@code -1} if not matched).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int[] buildEnumSwitchMap(Class<?> enumClass, String[] constantNames) {
        Object[] constants = enumClass.getEnumConstants();
        int[] map = new int[constants == null ? 0 : constants.length];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < constantNames.length; i++) {
            try {
                Enum<?> e = Enum.valueOf((Class<Enum>) enumClass, constantNames[i]);
                map[e.ordinal()] = i;
            } catch (IllegalArgumentException ignored) {
                // Enum constant removed in a newer runtime version; remains -1.
            }
        }
        return map;
    }

    /** {@code CONSTANT_Dynamic} bootstrap materializing an enum switch map lazily. */
    public static int[] constantEnumSwitchMap(
            java.lang.invoke.MethodHandles.Lookup lookup,
            String name,
            Class<int[]> type,
            Class<?> enumClass,
            String packedNames) {
        return buildEnumSwitchMap(enumClass, BabbleConstants.unpackStrings(packedNames));
    }

    /**
     * Defensive fallback when an exhaustive-without-else match encounters an unknown runtime
     * variant.
     */
    public static BabbleException noMatchingCase(Object value) {
        return new BabbleException("No matching case : " + ArraySupport.format(value));
    }
}
