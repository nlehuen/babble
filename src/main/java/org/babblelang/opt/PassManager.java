package org.babblelang.opt;

import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVerifier;
import org.babblelang.opt.hir.FbipReusePass;
import org.babblelang.opt.hir.HirDcePass;
import org.babblelang.opt.hir.HirEGraphSimplifyPass;
import org.babblelang.opt.hir.HirLicmPass;
import org.babblelang.opt.hir.HirTryNormalize;
import org.babblelang.opt.hir.InlinePass;
import org.babblelang.opt.hir.ScalarReplacePass;

import java.util.List;

/**
 * Bounded fixed-point Nanopass driver over {@link HirModule} (BIP014-I2, §6.5).
 *
 * <p>At {@code -O0}, runs only mandatory pre-emission normalization ({@link HirTryNormalize}).
 * At {@code -O1} (and as Tier 1 front-end before Tier 2 SSA MIR at {@code -O2}), iterates the
 * HIR Nanopass pipeline up to {@value #MAX_ITERATIONS} rounds, stopping early as soon as a full
 * round performs zero rewrites, followed by {@link HirTryNormalize}. Whenever {@code verify} is
 * true ({@code -Dbabble.verify=true}), runs {@link HirVerifier#verify(HirModule)} after every
 * modifying pass.
 */
public final class PassManager {
    public static final int MAX_ITERATIONS = 3;

    private static final List<HirPass> O1_PASSES = List.of(
            new HirEGraphSimplifyPass(),
            new InlinePass(),
            new ScalarReplacePass(),
            new FbipReusePass(),
            new HirDcePass(),
            new HirLicmPass()
    );

    private final List<HirPass> passes;

    public PassManager(List<HirPass> passes) {
        this.passes = List.copyOf(passes);
    }

    public static PassManager defaultFor(int optLevel) {
        if (optLevel <= 0) {
            return new PassManager(List.of());
        }
        return new PassManager(O1_PASSES);
    }

    public HirModule optimize(HirModule module, PassContext context) {
        return optimize(module, context, null, null);
    }

    public HirModule optimize(HirModule module, PassContext context, IrObserver observer, String moduleKey) {
        if (context.verify()) {
            HirVerifier.verify(module);
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_BEFORE, module);
        HirModule current = module;
        for (int iter = 0; iter < MAX_ITERATIONS && !passes.isEmpty(); iter++) {
            int roundRewrites = 0;
            for (HirPass pass : passes) {
                HirPass.Result res = pass.run(current, context);
                current = res.module();
                if (res.rewrites() > 0) {
                    roundRewrites += res.rewrites();
                    if (context.verify()) {
                        HirVerifier.verify(current);
                    }
                }
                notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.afterPass(pass.name()), current);
            }
            if (roundRewrites == 0) {
                break;
            }
        }
        HirModule normalized = HirTryNormalize.normalize(current);
        if (normalized != current) {
            current = normalized;
            if (context.verify()) {
                HirVerifier.verify(current);
            }
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_FINAL, current);
        return current;
    }

    private static void notifyObserver(IrObserver observer, String moduleKey, int optLevel, String stage, HirModule hir) {
        if (observer != null && observer.wants(IrTier.HIR, optLevel, stage)) {
            observer.onSnapshot(new IrSnapshot(IrTier.HIR, moduleKey, hir.className(), optLevel, stage, hir));
        }
    }
}
