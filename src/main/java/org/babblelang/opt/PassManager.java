package org.babblelang.opt;

import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPrinter;
import org.babblelang.ir.hir.HirVerifier;
import org.babblelang.opt.hir.AlgebraicSimplifyPass;
import org.babblelang.opt.hir.ConstFoldPass;
import org.babblelang.opt.hir.HirDcePass;
import org.babblelang.opt.hir.HirLicmPass;
import org.babblelang.opt.hir.InlinePass;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Bounded fixed-point Nanopass driver over {@link HirModule} (BIP014-I2, §6.5).
 *
 * <p>At {@code -O0}, returns the input module unchanged. At {@code -O1} (and {@code -O2} until
 * Tier 2 SSA MIR lands), iterates the HIR Nanopass pipeline up to {@value #MAX_ITERATIONS} rounds,
 * stopping early as soon as a full round performs zero rewrites. Whenever {@code verify} is true
 * ({@code -Dbabble.verify=true}), runs {@link HirVerifier#verify(HirModule)} after every modifying
 * pass.
 */
public final class PassManager {
    public static final int MAX_ITERATIONS = 3;

    private static final List<HirPass> O1_PASSES = List.of(
            new ConstFoldPass(),
            new AlgebraicSimplifyPass(),
            new InlinePass(),
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
        return optimize(module, context, null);
    }

    public HirModule optimize(HirModule module, PassContext context, BiConsumer<String, String> irDumper) {
        if (context.verify()) {
            HirVerifier.verify(module);
        }
        if (irDumper != null) {
            irDumper.accept("hir-before", HirPrinter.print(module));
        }
        if (passes.isEmpty()) {
            return module;
        }
        HirModule current = module;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
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
            }
            if (roundRewrites == 0) {
                break;
            }
        }
        if (irDumper != null) {
            irDumper.accept("hir-after", HirPrinter.print(current));
        }
        return current;
    }
}
