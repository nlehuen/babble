package org.babblelang.ir.hir;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;

/**
 * ANTLR-free frame variable identity in {@link HirModule} (BIP014-I1).
 *
 * <p>Replaces {@link Resolution.Variable} throughout the HIR, optimizer passes, and bytecode
 * emitter. Equality and hashing are keyed solely on {@link #id()}, so passes can clone or
 * alpha-rename variables cleanly by allocating fresh IDs.
 */
public record HirVar(
        int id,
        String name,
        Resolution.Variable.Kind kind,
        int ownerMethodId,
        PrimitiveKind primitiveKind,
        boolean cell,
        boolean used,
        boolean mayBeUndefined,
        boolean definitelyDefined,
        boolean mutable,
        boolean capturedByValue) {
    public HirVar {
        primitiveKind = primitiveKind != null ? primitiveKind : PrimitiveKind.NONE;
    }

    public HirVar(
            int id,
            String name,
            Resolution.Variable.Kind kind,
            int ownerMethodId,
            PrimitiveKind primitiveKind,
            boolean cell,
            boolean used,
            boolean mayBeUndefined,
            boolean definitelyDefined,
            boolean mutable) {
        this(
                id,
                name,
                kind,
                ownerMethodId,
                primitiveKind,
                cell,
                used,
                mayBeUndefined,
                definitelyDefined,
                mutable,
                false);
    }

    public HirVar(
            int id,
            String name,
            Resolution.Variable.Kind kind,
            int ownerMethodId,
            PrimitiveKind primitiveKind,
            boolean cell,
            boolean used,
            boolean mayBeUndefined,
            boolean definitelyDefined) {
        this(
                id,
                name,
                kind,
                ownerMethodId,
                primitiveKind,
                cell,
                used,
                mayBeUndefined,
                definitelyDefined,
                false,
                false);
    }

    /**
     * Whether any nested method captures this variable (either via a heap {@code Cell} or by
     * value).
     */
    public boolean isCaptured() {
        return cell || capturedByValue;
    }

    /** Synthetic hidden field name on a compiled class for a constructor-captured variable. */
    public String hiddenFieldName() {
        return "$v$" + name;
    }

    public HirVar withIdAndOwner(int newId, int newOwnerMethodId) {
        return new HirVar(
                newId,
                name,
                kind,
                newOwnerMethodId,
                primitiveKind,
                cell,
                used,
                mayBeUndefined,
                definitelyDefined,
                mutable,
                capturedByValue);
    }

    public HirVar asUsed() {
        return used
                ? this
                : new HirVar(
                        id,
                        name,
                        kind,
                        ownerMethodId,
                        primitiveKind,
                        cell,
                        true,
                        mayBeUndefined,
                        definitelyDefined,
                        mutable,
                        capturedByValue);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HirVar other && this.id == other.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return name + "#" + id + (cell ? "(cell)" : capturedByValue ? "(val)" : "");
    }
}
