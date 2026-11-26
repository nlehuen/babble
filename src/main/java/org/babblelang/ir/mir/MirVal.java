package org.babblelang.ir.mir;

import java.util.Objects;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Immutable SSA value identity in {@link MirFunction} ({@code BIP014-I3}).
 *
 * <p>Equality and hashing are keyed solely on {@link #id()}. Every SSA value is defined exactly
 * once (either as a function parameter, a basic-block parameter, or the result of a {@link
 * MirInst}) and carries its unboxed JVM {@link PrimitiveKind}.
 */
public record MirVal(int id, PrimitiveKind kind, String hint) {
    public MirVal {
        Objects.requireNonNull(kind, "kind");
    }

    public MirVal(int id, PrimitiveKind kind) {
        this(id, kind, null);
    }

    public String kindLabel() {
        return kindLabel(kind);
    }

    public static String kindLabel(PrimitiveKind kind) {
        return switch (kind == null ? PrimitiveKind.NONE : kind) {
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BOOL -> "bool";
            case CHAR -> "char";
            case NONE -> "any";
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MirVal other && this.id == other.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "v" + id + ":" + kindLabel();
    }
}
