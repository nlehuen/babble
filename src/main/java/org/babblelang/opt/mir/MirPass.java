package org.babblelang.opt.mir;

import org.babblelang.ir.mir.MirModule;
import org.babblelang.opt.PassContext;

/**
 * Single SSA {@code MIR} optimization pass ({@code BIP014-P3} §6.4).
 */
public interface MirPass {
    record Result(MirModule module, int rewrites) {
        public static Result unchanged(MirModule module) {
            return new Result(module, 0);
        }
    }

    String name();

    Result run(MirModule module, PassContext context);
}
