package org.babblelang.ir.hir;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Parameter declaration of a {@link HirFunction} or {@link HirClass} constructor (BIP014-I1).
 */
public record HirParam(
        String name,
        HirVar var,
        PrimitiveKind kind,
        int wideningRank,
        String checkedType,
        int memberIndex,
        HirExpr defaultValue,
        LoweringTargets.JavaSamTarget samTarget,
        LoweringTargets.ParamOwnership ownership,
        int line
) {
    public HirParam {
        kind = kind != null ? kind : PrimitiveKind.NONE;
        ownership = ownership != null ? ownership : LoweringTargets.ParamOwnership.DEFAULT;
    }

    public HirParam(
            String name,
            HirVar var,
            PrimitiveKind kind,
            int wideningRank,
            String checkedType,
            int memberIndex,
            HirExpr defaultValue,
            LoweringTargets.JavaSamTarget samTarget,
            int line
    ) {
        this(name, var, kind, wideningRank, checkedType, memberIndex, defaultValue, samTarget,
                LoweringTargets.ParamOwnership.DEFAULT, line);
    }

    public HirParam(
            String name,
            HirVar var,
            PrimitiveKind kind,
            int wideningRank,
            String checkedType,
            int memberIndex,
            HirExpr defaultValue,
            int line
    ) {
        this(name, var, kind, wideningRank, checkedType, memberIndex, defaultValue, null,
                LoweringTargets.ParamOwnership.DEFAULT, line);
    }

    public boolean isMut() {
        return ownership.mut();
    }

    public boolean isBorrow() {
        return ownership.borrow();
    }

    public boolean isUnique() {
        return ownership.unique();
    }

    public String recordTypeId() {
        return ownership.recordTypeId();
    }

    public HirParam withDefaultValue(HirExpr newDefault) {
        return new HirParam(name, var, kind, wideningRank, checkedType, memberIndex, newDefault, samTarget, ownership, line);
    }
}

