package org.babblelang.opt.mir;

import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirVerifier;
import org.babblelang.opt.PassContext;

import java.util.List;

/**
 * Bounded fixed-point SSA {@code MIR} pass driver ({@code BIP014-P3} §6.5, {@code BIP029}).
 *
 * <p>Emits {@link IrSnapshot}s on {@link IrTier#MIR} at {@code before}, {@code after:<PassName>},
 * and {@code final} stages whenever an {@link IrObserver} requests them, and runs
 * {@link MirVerifier#verify(MirModule)} after lowering and after every modifying pass when
 * {@code -Dbabble.verify=true} is active.
 */
public final class MirPassManager {
    public static final int MAX_ITERATIONS = 3;

    private final List<MirPass> passes;

    public MirPassManager(List<MirPass> passes) {
        this.passes = List.copyOf(passes);
    }

    public static MirPassManager defaultFor(int optLevel) {
        if (optLevel < 2) {
            return new MirPassManager(List.of());
        }
        return new MirPassManager(defaultO2Passes());
    }

    private static List<MirPass> defaultO2Passes() {
        // Populated with SCCP, GVN/CSE, LICM, AliasRLE, MirDce, and PredicateReorder in Slice 3.
        return List.of();
    }

    public MirModule optimize(MirModule module, PassContext context, IrObserver observer, String moduleKey) {
        if (context.verify()) {
            MirVerifier.verify(module);
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_BEFORE, module);
        MirModule current = module;
        for (int iter = 0; iter < MAX_ITERATIONS && !passes.isEmpty(); iter++) {
            int roundRewrites = 0;
            for (MirPass pass : passes) {
                MirPass.Result res = pass.run(current, context);
                current = res.module();
                if (res.rewrites() > 0) {
                    roundRewrites += res.rewrites();
                    if (context.verify()) {
                        MirVerifier.verify(current);
                    }
                }
                notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.afterPass(pass.name()), current);
            }
            if (roundRewrites == 0) {
                break;
            }
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_FINAL, current);
        return current;
    }

    private static void notifyObserver(IrObserver observer, String moduleKey, int optLevel, String stage, MirModule mir) {
        if (observer != null && observer.wants(IrTier.MIR, optLevel, stage)) {
            observer.onSnapshot(new IrSnapshot(IrTier.MIR, moduleKey, mir.hir().className(), optLevel, stage, mir));
        }
    }
}
