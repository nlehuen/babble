package org.babblelang.ir.hir;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.effects.Effect;

/**
 * Per-expression static metadata carried on every {@link HirExpr} node (BIP014-I1).
 *
 * <p>Detaches downstream optimization passes and bytecode emission from ANTLR4
 * {@code ParserRuleContext} side tables in {@link ScopeAnalysis}.
 */
public record ExprMeta(
        int line,
        PrimitiveKind kind,
        int wideningRank,
        String checkedType,
        EffectSet effects,
        boolean deeplyImmutable,
        boolean stringType
) {
    public ExprMeta {
        kind = kind != null ? kind : PrimitiveKind.NONE;
        effects = effects != null ? effects : EffectSet.EMPTY;
    }

    public static ExprMeta of(int line, PrimitiveKind kind) {
        boolean imm = kind != null && kind.isPrimitive();
        return new ExprMeta(line, kind, -1, null, EffectSet.EMPTY, imm, false);
    }

    public static ExprMeta of(int line, PrimitiveKind kind, boolean deeplyImmutable, boolean stringType) {
        return new ExprMeta(line, kind, -1, null, EffectSet.EMPTY, deeplyImmutable, stringType);
    }

    public ExprMeta withLine(int newLine) {
        return new ExprMeta(newLine, kind, wideningRank, checkedType, effects, deeplyImmutable, stringType);
    }

    public ExprMeta withKind(PrimitiveKind newKind) {
        return new ExprMeta(line, newKind, wideningRank, checkedType, effects, deeplyImmutable, stringType);
    }

    public ExprMeta withWideningRank(int newRank) {
        return new ExprMeta(line, kind, newRank, checkedType, effects, deeplyImmutable, stringType);
    }

    public ExprMeta withCheckedType(String newCheckedType) {
        return new ExprMeta(line, kind, wideningRank, newCheckedType, effects, deeplyImmutable, stringType);
    }

    public ExprMeta withEffects(EffectSet newEffects) {
        return new ExprMeta(line, kind, wideningRank, checkedType, newEffects, deeplyImmutable, stringType);
    }

    public boolean isPure() {
        return effects.isPure();
    }

    public boolean isDeterministic() {
        return effects.isDeterministic();
    }

    public boolean isNothrow() {
        return effects.isNothrow();
    }

    public boolean isTerminating() {
        return effects.terminating();
    }

    public boolean isIdempotent() {
        return effects.has(Effect.IDEMPOTENT);
    }

    public boolean isCommutative() {
        return effects.has(Effect.COMMUTATIVE);
    }

    public boolean isAssociative() {
        return effects.has(Effect.ASSOCIATIVE);
    }
}
