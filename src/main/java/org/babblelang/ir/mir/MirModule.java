package org.babblelang.ir.mir;

import org.babblelang.ir.IrDumpable;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.hir.HirModule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Module-level SSA {@code MIR} container implementing {@link IrDumpable} for {@code BIP029}
 * observability ({@code BIP014-I3} §6.4).
 *
 * <p>Holds lowered {@link MirFunction}s keyed by {@link org.babblelang.ir.hir.HirFunction#id()}
 * alongside explicit {@link #fallbacks()} recording why any function was kept on {@code HirEmitter}
 * so fallback decisions are always visible in {@link MirCanonicalPrinter} and {@code .batest.md}.
 */
public final class MirModule implements IrDumpable {
    private final HirModule hir;
    private final Map<Integer, MirFunction> functions;
    private final Map<Integer, String> fallbacks;

    public MirModule(HirModule hir, Map<Integer, MirFunction> functions, Map<Integer, String> fallbacks) {
        this.hir = Objects.requireNonNull(hir, "hir");
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.fallbacks = Collections.unmodifiableMap(new LinkedHashMap<>(fallbacks));
    }

    public HirModule hir() {
        return hir;
    }

    public Map<Integer, MirFunction> functions() {
        return functions;
    }

    public MirFunction function(int hirFunctionId) {
        return functions.get(hirFunctionId);
    }

    public Map<Integer, String> fallbacks() {
        return fallbacks;
    }

    public String fallbackReason(int hirFunctionId) {
        return fallbacks.get(hirFunctionId);
    }

    public MirModule withFunctions(Map<Integer, MirFunction> updated) {
        return new MirModule(hir, updated, fallbacks);
    }

    @Override
    public org.babblelang.ir.IrTier tier() {
        return org.babblelang.ir.IrTier.MIR;
    }

    @Override
    public String printCanonical(IrTarget target) {
        return MirCanonicalPrinter.print(this, target);
    }

    @Override
    public String printRaw() {
        return MirPrinter.print(this);
    }
}
