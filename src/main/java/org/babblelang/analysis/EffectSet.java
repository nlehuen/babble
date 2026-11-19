package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Compile-time effect, receiver-aliasing, and algebraic property summary of a callable (BIP007).
 *
 * <p>{@link Effect#PURE} implies {@link Effect#HERMETIC} and is mutually exclusive with
 * {@code mutThis} (and with {@code mut} parameters on the owning {@link Type.Function}).
 */
public final class EffectSet {

    /** Canonical modifier display order. */
    private static final Effect[] DISPLAY_ORDER = {
            Effect.PURE,
            Effect.HERMETIC,
            Effect.DETERMINISTIC,
            Effect.NOTHROW,
            Effect.IDEMPOTENT,
            Effect.COMMUTATIVE,
            Effect.ASSOCIATIVE,
            Effect.MONOTONE,
            Effect.INJECTIVE
    };

    public static final EffectSet EMPTY = new EffectSet(
            EnumSet.noneOf(Effect.class), EnumSet.noneOf(Effect.class),
            false, false, false, false, false, false, false, false);

    public static final EffectSet IMPURE_DECLARED = new EffectSet(
            EnumSet.noneOf(Effect.class), EnumSet.noneOf(Effect.class),
            false, false, false, false, false, false, true, false);

    private final EnumSet<Effect> effects;
    private final EnumSet<Effect> declared;
    private final boolean mutThis;
    private final boolean borrowThis;
    private final boolean explicitBorrowThis;
    private final boolean uniqueResult;
    private final boolean explicitUniqueResult;
    private final boolean terminating;
    private final boolean impure;
    private final boolean axiomatic;

    public static EnumSet<Effect> copySet(Set<Effect> set) {
        return set == null || set.isEmpty() ? EnumSet.noneOf(Effect.class) : EnumSet.copyOf(set);
    }

    public EffectSet(Set<Effect> effects,
                     Set<Effect> declared,
                     boolean mutThis,
                     boolean borrowThis,
                     boolean explicitBorrowThis,
                     boolean impure,
                     boolean axiomatic) {
        this(effects, declared, mutThis, borrowThis, explicitBorrowThis, false, false, false, impure, axiomatic);
    }

    public EffectSet(Set<Effect> effects,
                     Set<Effect> declared,
                     boolean mutThis,
                     boolean borrowThis,
                     boolean explicitBorrowThis,
                     boolean uniqueResult,
                     boolean explicitUniqueResult,
                     boolean terminating,
                     boolean impure,
                     boolean axiomatic) {
        EnumSet<Effect> norm = copySet(effects);
        norm.remove(Effect.MUT);
        norm.remove(Effect.BORROW);
        norm.remove(Effect.UNIQUE);
        norm.remove(Effect.IMPURE);
        if (impure) {
            norm.clear();
        } else if (norm.contains(Effect.PURE)) {
            norm.add(Effect.HERMETIC);
        }
        EnumSet<Effect> decl = copySet(declared);
        decl.retainAll(norm);
        this.effects = norm;
        this.declared = decl;
        this.mutThis = !impure && mutThis;
        this.borrowThis = !impure && (borrowThis || explicitBorrowThis);
        this.explicitBorrowThis = !impure && explicitBorrowThis;
        this.uniqueResult = !impure && uniqueResult;
        this.explicitUniqueResult = !impure && explicitUniqueResult;
        this.terminating = !impure && terminating;
        this.impure = impure;
        this.axiomatic = axiomatic;
    }

    public static EffectSet ofInferred(Set<Effect> effects, boolean mutThis, boolean borrowThis) {
        return new EffectSet(effects, EnumSet.noneOf(Effect.class), mutThis, borrowThis, false, false, false, false, false, false);
    }

    public static EffectSet ofInferred(Set<Effect> effects, boolean mutThis, boolean borrowThis,
                                       boolean uniqueResult, boolean terminating) {
        return new EffectSet(effects, EnumSet.noneOf(Effect.class), mutThis, borrowThis, false,
                uniqueResult, false, terminating, false, false);
    }

    public static EffectSet ofDeclared(Set<Effect> effects, boolean mutThis, boolean explicitBorrowThis, boolean axiomatic) {
        return new EffectSet(effects, effects, mutThis, explicitBorrowThis, explicitBorrowThis, false, false, false, false, axiomatic);
    }

    public static EffectSet ofDeclared(Set<Effect> effects, boolean mutThis, boolean explicitBorrowThis,
                                       boolean explicitUniqueResult, boolean axiomatic) {
        return new EffectSet(effects, effects, mutThis, explicitBorrowThis, explicitBorrowThis,
                explicitUniqueResult, explicitUniqueResult, false, false, axiomatic);
    }

    public Set<Effect> effects() {
        return Collections.unmodifiableSet(effects);
    }

    public Set<Effect> declared() {
        return Collections.unmodifiableSet(declared);
    }

    public boolean has(Effect effect) {
        if (effect == Effect.MUT) {
            return mutThis;
        }
        if (effect == Effect.BORROW) {
            return borrowThis;
        }
        if (effect == Effect.UNIQUE) {
            return uniqueResult;
        }
        if (effect == Effect.IMPURE) {
            return impure;
        }
        return effects.contains(effect);
    }

    public boolean isPure() {
        return effects.contains(Effect.PURE);
    }

    public boolean isHermetic() {
        return effects.contains(Effect.HERMETIC);
    }

    public boolean isDeterministic() {
        return effects.contains(Effect.DETERMINISTIC);
    }

    public boolean isNothrow() {
        return effects.contains(Effect.NOTHROW);
    }

    public boolean mutThis() {
        return mutThis;
    }

    public boolean borrowThis() {
        return borrowThis;
    }

    public boolean explicitBorrowThis() {
        return explicitBorrowThis;
    }

    public boolean uniqueResult() {
        return uniqueResult;
    }

    public boolean explicitUniqueResult() {
        return explicitUniqueResult;
    }

    public boolean terminating() {
        return terminating;
    }

    public boolean impure() {
        return impure;
    }

    public boolean axiomatic() {
        return axiomatic;
    }

    public boolean hasExplicitAnnotations() {
        return impure || axiomatic || mutThis || explicitBorrowThis || explicitUniqueResult || !declared.isEmpty();
    }

    public boolean isUnconstrained() {
        return effects.isEmpty() && !mutThis && !borrowThis && !uniqueResult;
    }

    /**
     * Whether a callable with {@code this} effect summary satisfies {@code required} on a
     * function type or interface slot : it must guarantee every positive effect in
     * {@code required}, borrow {@code this} if required, return {@code unique} if required,
     * and mutate {@code this} only if {@code required} permits {@code mutThis}.
     */
    public boolean satisfies(EffectSet required) {
        if (!effects.containsAll(required.effects)) {
            return false;
        }
        if (required.borrowThis && !borrowThis) {
            return false;
        }
        if (required.uniqueResult && !uniqueResult) {
            return false;
        }
        if (mutThis && !required.mutThis) {
            return false;
        }
        return true;
    }

    /** Intersection of guarantees across control-flow branches. */
    public EffectSet intersect(EffectSet other) {
        EnumSet<Effect> common = copySet(effects);
        common.retainAll(other.effects);
        EnumSet<Effect> commonDecl = copySet(declared);
        commonDecl.retainAll(other.declared);
        boolean eitherMut = mutThis || other.mutThis;
        if (eitherMut) {
            common.remove(Effect.PURE);
            commonDecl.remove(Effect.PURE);
        }
        return new EffectSet(common, commonDecl,
                eitherMut,
                borrowThis && other.borrowThis,
                explicitBorrowThis && other.explicitBorrowThis,
                uniqueResult && other.uniqueResult,
                explicitUniqueResult && other.explicitUniqueResult,
                terminating && other.terminating,
                false,
                axiomatic && other.axiomatic);
    }

    public static String keywordOf(Effect effect) {
        return switch (effect) {
            case PURE -> "pure";
            case HERMETIC -> "hermetic";
            case MUT -> "mut";
            case BORROW -> "borrow";
            case UNIQUE -> "unique";
            case DETERMINISTIC -> "deterministic";
            case NOTHROW -> "nothrow";
            case IDEMPOTENT -> "idempotent";
            case COMMUTATIVE -> "commutative";
            case ASSOCIATIVE -> "associative";
            default -> effect.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Formats only explicitly written modifiers (plus {@code mut} on receiver) for default type display. */
    public String formatDeclaredPrefix() {
        return formatPrefix(declared, mutThis, explicitBorrowThis);
    }

    /** Formats all active guarantees (and receiver {@code mut}/{@code borrow}) for effect diagnostics. */
    public String formatFullPrefix(boolean includeInferredBorrow) {
        return formatPrefix(effects, mutThis, includeInferredBorrow ? borrowThis : explicitBorrowThis);
    }

    private static String formatPrefix(Set<Effect> set, boolean mut, boolean borrow) {
        StringJoiner joiner = new StringJoiner(" ");
        boolean hasPure = set.contains(Effect.PURE);
        for (Effect effect : DISPLAY_ORDER) {
            if (effect == Effect.HERMETIC && hasPure) {
                continue;
            }
            if (set.contains(effect)) {
                joiner.add(effect.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (mut) {
            joiner.add("mut");
        }
        if (borrow) {
            joiner.add("borrow");
        }
        return joiner.length() == 0 ? "" : joiner + " ";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EffectSet that)) {
            return false;
        }
        return mutThis == that.mutThis
                && borrowThis == that.borrowThis
                && explicitBorrowThis == that.explicitBorrowThis
                && uniqueResult == that.uniqueResult
                && explicitUniqueResult == that.explicitUniqueResult
                && terminating == that.terminating
                && impure == that.impure
                && axiomatic == that.axiomatic
                && effects.equals(that.effects)
                && declared.equals(that.declared);
    }

    @Override
    public int hashCode() {
        return Objects.hash(effects, declared, mutThis, borrowThis, explicitBorrowThis,
                uniqueResult, explicitUniqueResult, terminating, impure, axiomatic);
    }

    @Override
    public String toString() {
        if (impure) {
            return "impure";
        }
        String prefix = formatFullPrefix(true).trim();
        if (uniqueResult) {
            prefix = prefix.isEmpty() ? "unique" : prefix + " unique";
        }
        return prefix.isEmpty() ? "<unconstrained>" : prefix;
    }
}
