package org.babblelang.analysis;

/**
 * Unboxed JVM primitive storage/expression kind published by {@code LoweringAnalyzer} and consumed
 * across {@code ir.hir}, {@code opt}, and {@code compiler} with zero ANTLR dependency (BIP027-D3).
 */
public enum PrimitiveKind {
    NONE,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOL,
    CHAR;

    public boolean isPrimitive() {
        return this != NONE;
    }

    public int slots() {
        return (this == LONG || this == DOUBLE) ? 2 : 1;
    }

    public boolean isNumeric() {
        return numericRank() >= 0;
    }

    public int numericRank() {
        return switch (this) {
            case BYTE -> 0;
            case SHORT -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
        };
    }

    public static PrimitiveKind ofNumericRank(int rank) {
        return switch (rank) {
            case 0 -> BYTE;
            case 1 -> SHORT;
            case 2 -> INT;
            case 3 -> LONG;
            case 4 -> FLOAT;
            case 5 -> DOUBLE;
            default -> NONE;
        };
    }

    public static PrimitiveKind promoteNumeric(PrimitiveKind a, PrimitiveKind b) {
        int max = Math.max(a.numericRank(), b.numericRank());
        return switch (max) {
            case 0, 1, 2 -> INT;
            case 3 -> LONG;
            case 4 -> FLOAT;
            default -> DOUBLE;
        };
    }

    /**
     * Common numeric kind for comparisons ({@code ==}, {@code !=}, {@code <}, {@code <=}, {@code
     * >}, {@code >=}), matching {@code Operators.compareNumbers}: mixed {@code int}/{@code long} vs
     * {@code float} promotes to {@code double} so integral operands are not lossily rounded to
     * 24-bit {@code float}.
     */
    public static PrimitiveKind comparisonKind(PrimitiveKind a, PrimitiveKind b) {
        if ((a == FLOAT && (b == INT || b == LONG)) || (b == FLOAT && (a == INT || a == LONG))) {
            return DOUBLE;
        }
        return a.numericRank() >= b.numericRank() ? a : b;
    }

    public static PrimitiveKind ofJavaClass(Class<?> cls) {
        if (cls == int.class) return INT;
        if (cls == long.class) return LONG;
        if (cls == float.class) return FLOAT;
        if (cls == double.class) return DOUBLE;
        if (cls == boolean.class) return BOOL;
        if (cls == byte.class) return BYTE;
        if (cls == short.class) return SHORT;
        if (cls == char.class) return CHAR;
        return NONE;
    }

    static PrimitiveKind of(Type type) {
        if (type == Type.BYTE) {
            return BYTE;
        }
        if (type == Type.SHORT) {
            return SHORT;
        }
        if (type == Type.INT) {
            return INT;
        }
        if (type == Type.LONG) {
            return LONG;
        }
        if (type == Type.FLOAT) {
            return FLOAT;
        }
        if (type == Type.DOUBLE) {
            return DOUBLE;
        }
        if (type == Type.BOOL) {
            return BOOL;
        }
        if (type == Type.CHAR) {
            return CHAR;
        }
        return NONE;
    }
}
