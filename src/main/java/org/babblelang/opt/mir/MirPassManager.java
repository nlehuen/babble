package org.babblelang.opt.mir;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTier;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirVerifier;
import org.babblelang.opt.PassContext;

/**
 * Bounded fixed-point SSA {@code MIR} pass driver ({@code BIP014-I3} §6.5, {@code BIP029}).
 *
 * <p>Emits {@link IrSnapshot}s on {@link IrTier#MIR} at {@code before}, {@code after:<PassName>},
 * and {@code final} stages whenever an {@link IrObserver} requests them, and runs {@link
 * MirVerifier#verify(MirModule)} after lowering and after every modifying pass when {@code
 * -Dbabble.verify=true} is active.
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
        return List.of(
                new SccpPass(),
                new GvnCsePass(),
                new AliasRlePass(),
                new LicmPass(),
                new MirDcePass(),
                new PredicateReorderPass());
    }

    public MirModule optimize(
            MirModule module, PassContext context, IrObserver observer, String moduleKey) {
        return MirCfg.withCache(() -> run(module, context, observer, moduleKey));
    }

    private MirModule run(
            MirModule module, PassContext context, IrObserver observer, String moduleKey) {
        if (context.verify()) {
            MirVerifier.verify(module);
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_BEFORE, module);
        MirModule current = module;
        // Every pass is intra-procedural and deterministic, and returns a function it did not
        // rewrite as is : a function no pass changed in a round would not change in the next one
        // either, so each round after the first only runs on the functions the previous one
        // changed.
        Set<Integer> dirty = null;
        for (int iter = 0; iter < MAX_ITERATIONS && !passes.isEmpty(); iter++) {
            MirModule work = dirty == null ? current : current.withOnly(dirty);
            Map<Integer, MirFunction> roundStart = work.functions();
            for (MirPass pass : passes) {
                MirPass.Result res = pass.run(work, context);
                work = res.module();
                if (res.rewrites() > 0) {
                    current = current.withReplaced(work.functions());
                    if (context.verify()) {
                        MirVerifier.verify(current);
                    }
                }
                notifyObserver(
                        observer,
                        moduleKey,
                        context.optLevel(),
                        IrSnapshot.afterPass(pass.name()),
                        current);
            }
            Set<Integer> changed = new LinkedHashSet<>();
            for (Map.Entry<Integer, MirFunction> e : work.functions().entrySet()) {
                if (e.getValue() != roundStart.get(e.getKey())) {
                    changed.add(e.getKey());
                }
            }
            if (changed.isEmpty()) {
                break;
            }
            dirty = changed;
        }
        notifyObserver(observer, moduleKey, context.optLevel(), IrSnapshot.STAGE_FINAL, current);
        return current;
    }

    private static void notifyObserver(
            IrObserver observer, String moduleKey, int optLevel, String stage, MirModule mir) {
        if (observer != null && observer.wants(IrTier.MIR, optLevel, stage)) {
            observer.onSnapshot(
                    new IrSnapshot(
                            IrTier.MIR, moduleKey, mir.hir().className(), optLevel, stage, mir));
        }
    }
}
