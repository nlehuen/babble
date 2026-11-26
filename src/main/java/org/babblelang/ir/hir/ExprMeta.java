package org.babblelang.ir.hir;

import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.effects.Effect;

/**
 * Per-expression static metadata carried on every {@link HirExpr} node (BIP014-I1).
 *
 * <p>Detaches downstream optimization passes and bytecode emission from ANTLR4 {@code
 * ParserRuleContext} side tables in {@code ScopeAnalysis}.
 */
public record ExprMeta(
        int line,
        PrimitiveKind kind,
        int wideningRank,
        String checkedType,
        EffectSet effects,
        boolean deeplyImmutable,
        boolean stringType,
        boolean frozen,
        boolean unique) {
    public ExprMeta {
        kind = kind != null ? kind : PrimitiveKind.NONE;
        effects = effects != null ? effects : EffectSet.EMPTY;
    }

    public ExprMeta(
            int line,
            PrimitiveKind kind,
            int wideningRank,
            String checkedType,
            EffectSet effects,
            boolean deeplyImmutable,
            boolean stringType) {
        this(
                line,
                kind,
                wideningRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                false,
                false);
    }

    public static ExprMeta of(int line, PrimitiveKind kind) {
        boolean imm = kind != null && kind.isPrimitive();
        return new ExprMeta(line, kind, -1, null, EffectSet.EMPTY, imm, false, false, false);
    }

    public static ExprMeta of(
            int line, PrimitiveKind kind, boolean deeplyImmutable, boolean stringType) {
        return new ExprMeta(
                line, kind, -1, null, EffectSet.EMPTY, deeplyImmutable, stringType, false, false);
    }

    public ExprMeta withLine(int newLine) {
        return new ExprMeta(
                newLine,
                kind,
                wideningRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                frozen,
                unique);
    }

    public ExprMeta withKind(PrimitiveKind newKind) {
        return new ExprMeta(
                line,
                newKind,
                wideningRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                frozen,
                unique);
    }

    public ExprMeta withWideningRank(int newRank) {
        return new ExprMeta(
                line,
                kind,
                newRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                frozen,
                unique);
    }

    public ExprMeta withCheckedType(String newCheckedType) {
        return new ExprMeta(
                line,
                kind,
                wideningRank,
                newCheckedType,
                effects,
                deeplyImmutable,
                stringType,
                frozen,
                unique);
    }

    public ExprMeta withEffects(EffectSet newEffects) {
        return new ExprMeta(
                line,
                kind,
                wideningRank,
                checkedType,
                newEffects,
                deeplyImmutable,
                stringType,
                frozen,
                unique);
    }

    public ExprMeta withFrozen(boolean newFrozen) {
        return new ExprMeta(
                line,
                kind,
                wideningRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                newFrozen,
                unique);
    }

    public ExprMeta withUnique(boolean newUnique) {
        return new ExprMeta(
                line,
                kind,
                wideningRank,
                checkedType,
                effects,
                deeplyImmutable,
                stringType,
                frozen,
                newUnique);
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
