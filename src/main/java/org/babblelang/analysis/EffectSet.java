package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Compile-time effect, receiver-aliasing, and algebraic property summary of a callable
 * (BIP007, BIP016).
 *
 * <p>Layer 1 internal traits stored here ({@code @HERMETIC}, {@code @DETERMINISTIC},
 * {@code @NOTHROW}, {@code @TERMINATING}, {@code mutThis}, {@code borrowThis},
 * {@code uniqueResult}, algebraic traits) are decoupled from Layer 2's opt-out surface syntax
 * ({@code io}, {@code volatile}, {@code mut}, {@code shared}, {@code unique}, opt-in
 * {@code nothrow}).
 */
public final class EffectSet {

    /** Canonical algebraic modifier display order. */
    private static final Effect[] ALGEBRAIC_ORDER = {
            Effect.IDEMPOTENT,
            Effect.COMMUTATIVE,
            Effect.ASSOCIATIVE,
            Effect.MONOTONE,
            Effect.INJECTIVE
    };

    public static final EffectSet EMPTY = new EffectSet(
            EnumSet.noneOf(Effect.class), EnumSet.noneOf(Effect.class),
            false, false, false, false, false, false, false, false, false);

    public static final EffectSet IO_DECLARED = new EffectSet(
            EnumSet.noneOf(Effect.class), EnumSet.noneOf(Effect.class),
            false, false, false, false, false, false, true, false, false);

    private final EnumSet<Effect> effects;
    private final EnumSet<Effect> declared;
    private final boolean mutThis;
    private final boolean borrowThis;
    private final boolean explicitSharedThis;
    private final boolean uniqueResult;
    private final boolean explicitUniqueResult;
    private final boolean terminating;
    private final boolean io;
    private final boolean explicitVolatile;
    private final boolean axiomatic;

    public static EnumSet<Effect> copySet(Set<Effect> set) {
        return set == null || set.isEmpty() ? EnumSet.noneOf(Effect.class) : EnumSet.copyOf(set);
    }

    public EffectSet(Set<Effect> effects,
                     Set<Effect> declared,
                     boolean mutThis,
                     boolean borrowThis,
                     boolean explicitSharedThis,
                     boolean uniqueResult,
                     boolean explicitUniqueResult,
                     boolean terminating,
                     boolean io,
                     boolean explicitVolatile,
                     boolean axiomatic) {
        EnumSet<Effect> norm = copySet(effects);
        norm.remove(Effect.MUT);
        norm.remove(Effect.BORROW);
        norm.remove(Effect.SHARED);
        norm.remove(Effect.UNIQUE);
        norm.remove(Effect.VOLATILE);
        norm.remove(Effect.IO);
        if (io) {
            norm.remove(Effect.PURE);
            norm.remove(Effect.HERMETIC);
            norm.remove(Effect.DETERMINISTIC);
        } else if (explicitVolatile) {
            norm.add(Effect.HERMETIC);
            norm.remove(Effect.DETERMINISTIC);
        } else if (norm.contains(Effect.PURE)) {
            norm.add(Effect.HERMETIC);
        }
        if (mutThis) {
            norm.remove(Effect.PURE);
        }
        EnumSet<Effect> decl = copySet(declared);
        decl.retainAll(norm);
        this.effects = norm;
        this.declared = decl;
        this.mutThis = mutThis;
        this.borrowThis = borrowThis && !explicitSharedThis;
        this.explicitSharedThis = explicitSharedThis;
        this.uniqueResult = uniqueResult;
        this.explicitUniqueResult = explicitUniqueResult;
        this.terminating = terminating;
        this.io = io;
        this.explicitVolatile = !io && explicitVolatile;
        this.axiomatic = axiomatic;
    }

    public static EffectSet ofInferred(Set<Effect> effects, boolean mutThis, boolean borrowThis) {
        return new EffectSet(effects, EnumSet.noneOf(Effect.class), mutThis, borrowThis, false,
                false, false, false, false, false, false);
    }

    public static EffectSet ofInferred(Set<Effect> effects, boolean mutThis, boolean borrowThis,
                                       boolean uniqueResult, boolean terminating) {
        return new EffectSet(effects, EnumSet.noneOf(Effect.class), mutThis, borrowThis, false,
                uniqueResult, false, terminating, false, false, false);
    }

    public static EffectSet ofDeclared(Set<Effect> effects, boolean mutThis, boolean borrowThis, boolean axiomatic) {
        return ofDeclared(effects, mutThis, borrowThis, false, false, false, false, axiomatic);
    }

    public static EffectSet ofDeclared(Set<Effect> effects, boolean mutThis, boolean borrowThis,
                                       boolean explicitUniqueResult, boolean axiomatic) {
        return ofDeclared(effects, mutThis, borrowThis, false, explicitUniqueResult, false, false, axiomatic);
    }

    public static EffectSet ofDeclared(Set<Effect> effects,
                                       boolean mutThis,
                                       boolean borrowThis,
                                       boolean explicitSharedThis,
                                       boolean explicitUniqueResult,
                                       boolean io,
                                       boolean explicitVolatile,
                                       boolean axiomatic) {
        return new EffectSet(effects, effects, mutThis, borrowThis, explicitSharedThis,
                explicitUniqueResult, explicitUniqueResult, false, io, explicitVolatile, axiomatic);
    }

    public Set<Effect> effects() {
        return Collections.unmodifiableSet(effects);
    }

    public Set<Effect> declared() {
        return Collections.unmodifiableSet(declared);
    }

    public boolean has(Effect effect) {
        return switch (effect) {
            case MUT -> mutThis;
            case BORROW -> borrowThis;
            case SHARED -> !borrowThis;
            case UNIQUE -> uniqueResult;
            case IO -> io || !effects.contains(Effect.HERMETIC);
            case VOLATILE -> isVolatile();
            default -> effects.contains(effect);
        };
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

    public boolean isVolatile() {
        return !io && effects.contains(Effect.HERMETIC) && !effects.contains(Effect.DETERMINISTIC);
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

    public boolean explicitSharedThis() {
        return explicitSharedThis;
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

    public boolean isIO() {
        return io;
    }

    public boolean explicitVolatile() {
        return explicitVolatile;
    }

    public boolean axiomatic() {
        return axiomatic;
    }

    public boolean hasExplicitAnnotations() {
        return io || explicitVolatile || axiomatic || mutThis || explicitSharedThis || explicitUniqueResult || !declared.isEmpty();
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
        boolean eitherIO = io || other.io || !common.contains(Effect.HERMETIC);
        boolean eitherVolatile = !eitherIO && (explicitVolatile || other.explicitVolatile
                || (common.contains(Effect.HERMETIC) && !common.contains(Effect.DETERMINISTIC)));
        boolean sharedThis = explicitSharedThis || other.explicitSharedThis;
        boolean bothEmpty = isUnconstrained() && !io && !explicitSharedThis
                && other.isUnconstrained() && !other.io && !other.explicitSharedThis;
        return new EffectSet(common, commonDecl,
                eitherMut,
                borrowThis && other.borrowThis,
                sharedThis,
                uniqueResult && other.uniqueResult,
                explicitUniqueResult && other.explicitUniqueResult,
                terminating && other.terminating,
                !bothEmpty && (io || other.io),
                eitherVolatile,
                axiomatic && other.axiomatic);
    }

    /**
     * Relaxes function-level and receiver-mutation requirements for a callback parameter
     * listed in {@code fn calling f} (BIP017-D1), while preserving any explicit {@code unique}
     * return requirement.
     */
    public EffectSet relaxedForForwardedCallback() {
        return new EffectSet(
                EnumSet.noneOf(Effect.class), EnumSet.noneOf(Effect.class),
                true, false, false, uniqueResult, explicitUniqueResult,
                false, true, false, false);
    }

    /**
     * Combines a higher-order function's base effects ({@code this}) with the forwarded
     * function-level effects of a callback argument ({@code cb}) per BIP017-D2/D3.
     */
    public EffectSet combineForwarded(EffectSet cb) {
        if (cb == null) {
            return this;
        }
        EnumSet<Effect> combined = copySet(effects);
        if (!cb.isHermetic()) {
            combined.remove(Effect.HERMETIC);
            combined.remove(Effect.PURE);
            combined.remove(Effect.DETERMINISTIC);
        }
        if (!cb.isDeterministic()) {
            combined.remove(Effect.DETERMINISTIC);
        }
        if (!cb.isNothrow()) {
            combined.remove(Effect.NOTHROW);
        }
        if (!cb.isPure()) {
            combined.remove(Effect.PURE);
        }
        EnumSet<Effect> combinedDecl = copySet(declared);
        combinedDecl.retainAll(combined);
        boolean combinedIO = io || cb.isIO() || !combined.contains(Effect.HERMETIC);
        boolean combinedVolatile = !combinedIO && (explicitVolatile || cb.isVolatile()
                || (combined.contains(Effect.HERMETIC) && !combined.contains(Effect.DETERMINISTIC)));
        return new EffectSet(combined, combinedDecl,
                mutThis, borrowThis, explicitSharedThis,
                uniqueResult, explicitUniqueResult,
                terminating && cb.terminating(),
                combinedIO, combinedVolatile,
                axiomatic && cb.axiomatic());
    }

    public static String keywordOf(Effect effect) {
        return effect.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Formats explicitly written BIP016 surface modifiers for default type display. */
    public String formatDeclaredPrefix() {
        return formatSurface(declared, io, explicitVolatile, mutThis, explicitSharedThis);
    }

    /** Formats active BIP016 surface modifiers for effect diagnostics. */
    public String formatFullPrefix(boolean showSharedThis) {
        boolean showIO = io || !effects.contains(Effect.HERMETIC);
        boolean showVolatile = !showIO && !effects.contains(Effect.DETERMINISTIC);
        boolean showShared = explicitSharedThis || (showSharedThis && !borrowThis);
        return formatSurface(effects, showIO, showVolatile, mutThis, showShared);
    }

    static String formatSurface(Set<Effect> set,
                                boolean showIO,
                                boolean showVolatile,
                                boolean showMut,
                                boolean showShared) {
        StringJoiner joiner = new StringJoiner(" ");
        if (showIO) {
            joiner.add("io");
        } else if (showVolatile) {
            joiner.add("volatile");
        }
        if (set.contains(Effect.NOTHROW)) {
            joiner.add("nothrow");
        }
        if (showMut) {
            joiner.add("mut");
        }
        if (showShared) {
            joiner.add("shared");
        }
        for (Effect effect : ALGEBRAIC_ORDER) {
            if (set.contains(effect)) {
                joiner.add(effect.name().toLowerCase(java.util.Locale.ROOT));
            }
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
                && explicitSharedThis == that.explicitSharedThis
                && uniqueResult == that.uniqueResult
                && explicitUniqueResult == that.explicitUniqueResult
                && terminating == that.terminating
                && io == that.io
                && explicitVolatile == that.explicitVolatile
                && axiomatic == that.axiomatic
                && effects.equals(that.effects)
                && declared.equals(that.declared);
    }

    @Override
    public int hashCode() {
        return Objects.hash(effects, declared, mutThis, borrowThis, explicitSharedThis,
                uniqueResult, explicitUniqueResult, terminating, io, explicitVolatile, axiomatic);
    }

    @Override
    public String toString() {
        if (isUnconstrained() && !io && !explicitSharedThis) {
            return "<unconstrained>";
        }
        String prefix = formatFullPrefix(false).trim();
        if (prefix.isEmpty() && effects.contains(Effect.PURE) && effects.contains(Effect.DETERMINISTIC)) {
            prefix = "pure";
        }
        if (uniqueResult) {
            prefix = prefix.isEmpty() ? "unique" : prefix + " unique";
        }
        return prefix.isEmpty() ? "pure" : prefix;
    }
}
