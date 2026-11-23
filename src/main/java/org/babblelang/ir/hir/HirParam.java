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
        int line
) {
    public HirParam {
        kind = kind != null ? kind : PrimitiveKind.NONE;
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
        this(name, var, kind, wideningRank, checkedType, memberIndex, defaultValue, null, line);
    }

    public HirParam withDefaultValue(HirExpr newDefault) {
        return new HirParam(name, var, kind, wideningRank, checkedType, memberIndex, newDefault, samTarget, line);
    }
}
