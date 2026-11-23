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
