package org.babblelang.ir;

/**
 * Lazy, thread-safe observer receiving compiler IR snapshots ({@code BIP029-P1}, {@code BIP029-D6}).
 *
 * <p>Attached per-engine via {@code BabbleCompiler.IR_OBSERVER} for parallel-safe {@code .batest.md}
 * assertions and also used by CLI {@code -Dbabble.dumpIr}. Calls to {@link #wants} allow pass drivers
 * to skip retaining or formatting unused stages.
 */
public interface IrObserver {
    /** Whether this observer is interested in snapshots for the given tier, opt level, and stage. */
    default boolean wants(IrTier tier, int optLevel, String stage) {
        return IrSnapshot.STAGE_FINAL.equals(stage);
    }

    /** Receives a captured IR snapshot for which {@link #wants} returned {@code true}. */
    void onSnapshot(IrSnapshot snapshot);

    /** Combines two optional observers so both receive every snapshot they want. */
    static IrObserver combine(IrObserver first, IrObserver second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new IrObserver() {
            @Override
            public boolean wants(IrTier tier, int optLevel, String stage) {
                return first.wants(tier, optLevel, stage) || second.wants(tier, optLevel, stage);
            }

            @Override
            public void onSnapshot(IrSnapshot snapshot) {
                if (first.wants(snapshot.tier(), snapshot.optLevel(), snapshot.stage())) {
                    first.onSnapshot(snapshot);
                }
                if (second.wants(snapshot.tier(), snapshot.optLevel(), snapshot.stage())) {
                    second.onSnapshot(snapshot);
                }
            }
        };
    }
}
