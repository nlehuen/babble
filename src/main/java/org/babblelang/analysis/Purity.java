package org.babblelang.analysis;

/**
 * The purity lattice Java call profiles ({@link EffectChecker.JavaCallProfile}) and Babble callable
 * summaries ({@link EffectChecker.CallableSummary}) share ({@code BIP027-P5}), after OPAL's unified
 * purity lattice (Helm et al., ASE 2018) : how far a callee is from pure, as the product of three
 * bits, joined pointwise.
 *
 * <pre>
 *                 PURE
 *               /      \
 *   SIDE_EFFECT_FREE   CONTEXTUALLY_PURE
 *               \      /
 *    CONTEXTUALLY_SIDE_EFFECT_FREE
 *                  |
 *                IMPURE
 * </pre>
 *
 * <p>"Contextually" means the callee mutates its arguments ({@code mut} parameters) and nothing else
 * outside its frame ; the receiver is left to {@link EffectSet#mutThis()}, which only matters for a
 * method call. {@code SIDE_EFFECT_FREE} is Babble's {@code volatile} : it reads state it does not
 * own, so its result is not deterministic. {@code IMPURE} is anything not hermetic : I/O, or
 * mutation of state outside the frame.
 *
 * <p>ANTLR-free.
 */
enum Purity {
    PURE(false, false, false),
    SIDE_EFFECT_FREE(false, true, false),
    CONTEXTUALLY_PURE(true, false, false),
    CONTEXTUALLY_SIDE_EFFECT_FREE(true, true, false),
    IMPURE(true, true, true);

    private final boolean mutatesArguments;
    private final boolean volatileReads;
    private final boolean impure;

    Purity(boolean mutatesArguments, boolean volatileReads, boolean impure) {
        this.mutatesArguments = mutatesArguments;
        this.volatileReads = volatileReads;
        this.impure = impure;
    }

    /** The purity of a callee with {@code effects} that mutates the arguments it is given iff {@code mutatesArguments}. */
    static Purity of(EffectSet effects, boolean mutatesArguments) {
        if (!effects.isHermetic()) {
            return IMPURE;
        }
        return of(mutatesArguments, !effects.isDeterministic(), false);
    }

    /** {@link #of(EffectSet, boolean)} for a callee whose {@code mut} parameters are {@code mutParams}. */
    static Purity of(EffectSet effects, boolean[] mutParams) {
        return of(effects, EffectChecker.anyTrue(mutParams));
    }

    private static Purity of(boolean mutatesArguments, boolean volatileReads, boolean impure) {
        if (impure) {
            return IMPURE;
        }
        if (mutatesArguments) {
            return volatileReads ? CONTEXTUALLY_SIDE_EFFECT_FREE : CONTEXTUALLY_PURE;
        }
        return volatileReads ? SIDE_EFFECT_FREE : PURE;
    }

    /** Hermetic, and mutates none of its arguments. */
    boolean mutatesNothing() {
        return !impure && !mutatesArguments;
    }

    /** Hermetic, and its result depends on its arguments only. */
    boolean isDeterministic() {
        return !impure && !volatileReads;
    }

    /** The join : what calling either may do (downward in the diagram). */
    Purity join(Purity other) {
        return of(mutatesArguments || other.mutatesArguments, volatileReads || other.volatileReads,
                impure || other.impure);
    }

    /** Whether this is at least as pure as {@code other} (above it in the diagram, or it). */
    boolean atLeastAsPureAs(Purity other) {
        return join(other) == other;
    }
}
