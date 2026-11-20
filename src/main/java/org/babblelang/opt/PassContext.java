package org.babblelang.opt;

import org.babblelang.ir.hir.HirFunction;

import java.util.Map;

/**
 * Execution options and AOT cross-module context for {@link PassManager} (BIP014-P2, BIP014-D2).
 */
public record PassContext(
        int optLevel,
        boolean verify,
        String dumpIr,
        boolean aotCrossModule,
        Map<String, HirFunction> exportedInlineFunctions
) {
    public PassContext {
        exportedInlineFunctions = exportedInlineFunctions != null ? Map.copyOf(exportedInlineFunctions) : Map.of();
    }

    public static PassContext of(int optLevel, boolean verify, String dumpIr) {
        return new PassContext(optLevel, verify, dumpIr, false, Map.of());
    }
}
