package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * ANTLR-free single-pass symbolic higher-order effect summary ({@code BIP027-I4}, §3.2).
 *
 * <p>Records the residual base effects of a callable {@code F(p_0, ..., p_{n-1})} excluding
 * invocations of its callback parameters, the bitmask {@code calledCallbacks} ({@code C_called})
 * of callback parameters actually invoked, and the parametric argument-flow matrix
 * {@code paramFlow[i][m]} (which parameters of {@code F} flow into argument {@code m} of
 * callback {@code p_i}). Instantiating any intra-file or cross-module HOF call becomes an
 * {@code O(k)} bitvector evaluation without body re-walks or probing caches.
 */
final class SymbolicEffectSummary {
    final EffectChecker.CallableSummary base;
    final boolean lockedContract;
    final EffectSet residualEffects;
    final boolean[] residualMutParams;
    final boolean[] residualBorrowParams;
    final boolean[] residualReadParams;
    final boolean residualReadsThis;
    final BitSet calledCallbacks;
    final EffectSet[] defaultCallbackEffects;
    /** {@code paramFlow[i][m]} = bitmask of {@code F}'s parameter indices flowing to arg {@code m} of callback {@code p_i}. */
    final long[][] paramFlow;
    /** {@code thisFlow[i][m]} = true if {@code this} of {@code F} flows to arg {@code m} of callback {@code p_i}. */
    final boolean[][] thisFlow;
    /** {@code extFlow[i][m]} = true if an external object flows to arg {@code m} of callback {@code p_i}. */
    final boolean[][] extFlow;

    SymbolicEffectSummary(EffectChecker.CallableSummary base,
                          boolean lockedContract,
                          EffectSet residualEffects,
                          boolean[] residualMutParams,
                          boolean[] residualBorrowParams,
                          boolean[] residualReadParams,
                          boolean residualReadsThis,
                          BitSet calledCallbacks,
                          EffectSet[] defaultCallbackEffects,
                          long[][] paramFlow,
                          boolean[][] thisFlow,
                          boolean[][] extFlow) {
        this.base = base;
        this.lockedContract = lockedContract;
        this.residualEffects = residualEffects;
        this.residualMutParams = residualMutParams;
        this.residualBorrowParams = residualBorrowParams;
        this.residualReadParams = residualReadParams;
        this.residualReadsThis = residualReadsThis;
        this.calledCallbacks = calledCallbacks;
        this.defaultCallbackEffects = defaultCallbackEffects;
        this.paramFlow = paramFlow;
        this.thisFlow = thisFlow;
        this.extFlow = extFlow;
    }

    static SymbolicEffectSummary fixed(EffectChecker.CallableSummary base, boolean lockedContract) {
        return new SymbolicEffectSummary(base, lockedContract, base.effects(), base.mutParams(),
                base.borrowParams(), base.readParams(), base.readsThis(),
                new BitSet(0), new EffectSet[0], new long[0][], new boolean[0][], new boolean[0][]);
    }

    boolean isParametric() {
        return !lockedContract && !calledCallbacks.isEmpty();
    }

    /**
     * Instantiates this symbolic summary at a call site supplying {@code boundCallbacks}
     * (and optional intra-file {@code boundSummaries} for exact {@code readParams} /
     * frame-local closure propagation).
     */
    EffectChecker.CallableSummary instantiate(Type.Function[] boundCallbacks,
                                              EffectChecker.CallableSummary[] boundSummaries) {
        if (!isParametric()) {
            return base;
        }
        boolean anyProvided = false;
        for (int i = calledCallbacks.nextSetBit(0); i >= 0; i = calledCallbacks.nextSetBit(i + 1)) {
            if (i < boundCallbacks.length && boundCallbacks[i] != null) {
                anyProvided = true;
                break;
            }
        }
        if (!anyProvided) {
            return base;
        }

        EnumSet<Effect> effs = EffectSet.copySet(residualEffects.effects());
        boolean terminating = residualEffects.terminating();
        boolean mutThis = residualEffects.mutThis();
        boolean borrowThis = residualEffects.borrowThis();
        boolean readsThis = residualReadsThis;
        boolean[] mutParams = residualMutParams.clone();
        boolean[] borrowParams = residualBorrowParams.clone();
        boolean[] readParams = residualReadParams.clone();
        Set<Object> frameReads = null;
        Set<Object> frameWrites = null;

        for (int i = calledCallbacks.nextSetBit(0); i >= 0; i = calledCallbacks.nextSetBit(i + 1)) {
            Type.Function argFn = i < boundCallbacks.length ? boundCallbacks[i] : null;
            EffectChecker.CallableSummary argSum = (boundSummaries != null && i < boundSummaries.length)
                    ? boundSummaries[i] : null;
            EffectSet cbEffects = argSum != null
                    ? argSum.effects()
                    : (argFn != null ? argFn.effects()
                            : (i < defaultCallbackEffects.length ? defaultCallbackEffects[i] : null));
            if (cbEffects != null) {
                effs.retainAll(cbEffects.effects());
                terminating &= cbEffects.terminating();
            }
            if (argSum != null) {
                if (!argSum.frameReadOwners().isEmpty()) {
                    if (frameReads == null) {
                        frameReads = Collections.newSetFromMap(new IdentityHashMap<>());
                        frameReads.addAll(base.frameReadOwners());
                    }
                    frameReads.addAll(argSum.frameReadOwners());
                }
                if (!argSum.frameWriteOwners().isEmpty()) {
                    if (frameWrites == null) {
                        frameWrites = Collections.newSetFromMap(new IdentityHashMap<>());
                        frameWrites.addAll(base.frameWriteOwners());
                    }
                    frameWrites.addAll(argSum.frameWriteOwners());
                }
            }
            if (i < paramFlow.length && paramFlow[i] != null) {
                long[] masks = paramFlow[i];
                boolean[] tFlow = i < thisFlow.length ? thisFlow[i] : null;
                boolean[] eFlow = i < extFlow.length ? extFlow[i] : null;
                List<Type.Parameter> cbParams = argFn != null ? argFn.parameters() : List.of();
                int cbArity = Math.max(masks.length, cbParams.size());
                for (int m = 0; m < cbArity && m < masks.length; m++) {
                    boolean cbMut = (argSum != null && m < argSum.mutParams().length)
                            ? argSum.mutParams()[m]
                            : (m < cbParams.size() && cbParams.get(m).mut());
                    boolean cbBorrow = (argSum != null && m < argSum.borrowParams().length)
                            ? argSum.borrowParams()[m]
                            : (m >= cbParams.size() || cbParams.get(m).borrow());
                    boolean cbReads = (argSum != null && m < argSum.readParams().length)
                            ? argSum.readParams()[m]
                            : (m < cbParams.size() && !cbParams.get(m).mut() && !Type.isCoreScalar(cbParams.get(m).type()));

                    long mask = masks[m];
                    if (cbMut) {
                        for (int j = 0; j < mutParams.length && j < 64; j++) {
                            if ((mask & (1L << j)) != 0) {
                                mutParams[j] = true;
                            }
                        }
                        if (tFlow != null && m < tFlow.length && tFlow[m]) {
                            mutThis = true;
                        }
                        if (eFlow != null && m < eFlow.length && eFlow[m]) {
                            effs.remove(Effect.PURE);
                            effs.remove(Effect.HERMETIC);
                            effs.remove(Effect.DETERMINISTIC);
                        }
                    }
                    if (!cbBorrow) {
                        for (int j = 0; j < borrowParams.length && j < 64; j++) {
                            if ((mask & (1L << j)) != 0) {
                                borrowParams[j] = false;
                            }
                        }
                        if (tFlow != null && m < tFlow.length && tFlow[m]) {
                            borrowThis = false;
                        }
                    }
                    if (cbReads) {
                        for (int j = 0; j < readParams.length && j < 64; j++) {
                            if ((mask & (1L << j)) != 0) {
                                readParams[j] = true;
                            }
                        }
                        if (tFlow != null && m < tFlow.length && tFlow[m]) {
                            readsThis = true;
                        }
                        if (eFlow != null && m < eFlow.length && eFlow[m]) {
                            effs.remove(Effect.DETERMINISTIC);
                        }
                    }
                }
            }
        }

        boolean anyMut = mutThis;
        for (boolean m : mutParams) {
            anyMut |= m;
        }
        if (anyMut) {
            effs.remove(Effect.PURE);
        }
        boolean isIO = !effs.contains(Effect.HERMETIC);
        boolean isVol = !isIO && !effs.contains(Effect.DETERMINISTIC);
        EffectSet combined = new EffectSet(effs, EnumSet.noneOf(Effect.class),
                mutThis, borrowThis, residualEffects.explicitSharedThis(),
                residualEffects.uniqueResult(), residualEffects.explicitUniqueResult(),
                terminating, isIO, isVol, false);

        Set<Object> nextFrameReads = frameReads != null ? frameReads : base.frameReadOwners();
        Set<Object> nextFrameWrites = frameWrites != null ? frameWrites : base.frameWriteOwners();

        return new EffectChecker.CallableSummary(combined, mutParams, borrowParams, base.uniqueParams(),
                nextFrameReads, nextFrameWrites, base.deepMutThis() || mutThis,
                base.returnParams(), base.returnThis(), base.returnExternal(), base.returnKnown(),
                base.ctorStoredParams(), readParams, readsThis);
    }

    /** Returns a copy with finalized {@code base} and {@code PURE} derived on {@code residualEffects}. */
    SymbolicEffectSummary withFinalizedBase(EffectChecker.CallableSummary finalizedBase, boolean locked) {
        if (locked || calledCallbacks.isEmpty()) {
            return fixed(finalizedBase, locked);
        }
        EnumSet<Effect> resEffs = EffectSet.copySet(residualEffects.effects());
        boolean anyMut = residualEffects.mutThis();
        for (boolean m : residualMutParams) {
            anyMut |= m;
        }
        if (resEffs.contains(Effect.HERMETIC) && !anyMut) {
            resEffs.add(Effect.PURE);
        } else {
            resEffs.remove(Effect.PURE);
        }
        EffectSet finalizedRes = new EffectSet(resEffs, residualEffects.declared(),
                residualEffects.mutThis(), residualEffects.borrowThis(), residualEffects.explicitSharedThis(),
                residualEffects.uniqueResult(), residualEffects.explicitUniqueResult(),
                residualEffects.terminating(), residualEffects.isIO(), residualEffects.explicitVolatile(),
                residualEffects.axiomatic());
        return new SymbolicEffectSummary(finalizedBase, false, finalizedRes, residualMutParams,
                residualBorrowParams, residualReadParams, residualReadsThis,
                calledCallbacks, defaultCallbackEffects, paramFlow, thisFlow, extFlow);
    }
}
