package org.babblelang.opt;

import org.babblelang.ir.hir.HirModule;

/**
 * Single-responsibility Nanopass transformation over {@link HirModule} (BIP014-P2).
 */
public interface HirPass {
    String name();

    Result run(HirModule module, PassContext context);

    record Result(HirModule module, int rewrites) {
        public static Result unchanged(HirModule module) {
            return new Result(module, 0);
        }
    }
}
