package org.babblelang.opt;

import java.util.Map;
import org.babblelang.analysis.CompilationTarget;
import org.babblelang.ir.hir.HirFunction;

/**
 * Execution options and AOT cross-module context for {@link PassManager} (BIP014-I2, BIP014-D2,
 * BIP025-D11).
 */
public record PassContext(
        int optLevel,
        boolean verify,
        String dumpIr,
        boolean aotCrossModule,
        Map<String, HirFunction> exportedInlineFunctions,
        CompilationTarget target) {
    public PassContext {
        exportedInlineFunctions =
                exportedInlineFunctions != null ? Map.copyOf(exportedInlineFunctions) : Map.of();
        target = target != null ? target : CompilationTarget.JVM;
    }

    public PassContext(
            int optLevel,
            boolean verify,
            String dumpIr,
            boolean aotCrossModule,
            Map<String, HirFunction> exportedInlineFunctions) {
        this(
                optLevel,
                verify,
                dumpIr,
                aotCrossModule,
                exportedInlineFunctions,
                CompilationTarget.JVM);
    }

    public static PassContext of(int optLevel, boolean verify, String dumpIr) {
        return new PassContext(optLevel, verify, dumpIr, false, Map.of(), CompilationTarget.JVM);
    }

    public static PassContext forTarget(
            int optLevel, boolean verify, String dumpIr, CompilationTarget target) {
        return new PassContext(optLevel, verify, dumpIr, false, Map.of(), target);
    }
}
