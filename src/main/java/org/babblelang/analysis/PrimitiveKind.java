package org.babblelang.analysis;

/**
 * Unboxed JVM primitive storage/expression kind published by {@code LoweringAnalyzer}
 * and consumed across {@code ir.hir}, {@code opt}, and {@code compiler} with zero ANTLR dependency (BIP027-D3).
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
