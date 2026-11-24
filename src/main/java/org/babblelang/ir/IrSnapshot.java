package org.babblelang.ir;

import java.util.Objects;

/**
 * Immutable snapshot of an IR tier captured at a specific optimization level and pipeline stage
 * ({@code BIP029-P1}, {@code BIP029-D6}).
 *
 * @param tier         IR tier ({@link IrTier#HIR} or {@link IrTier#MIR})
 * @param moduleKey    logical module key if compiling a module, or {@code null} for main scripts
 * @param internalName generated JVM internal class name (for raw CLI dump headers/filenames)
 * @param optLevel     active optimization level ({@code 0}, {@code 1}, {@code 2})
 * @param stage        pipeline stage: {@value #STAGE_BEFORE}, {@value #STAGE_FINAL}, or {@code "after:<PassName>"}
 * @param ir           captured printable IR snapshot
 */
public record IrSnapshot(
        IrTier tier,
        String moduleKey,
        String internalName,
        int optLevel,
        String stage,
        IrDumpable ir
) {
    public static final String STAGE_BEFORE = "before";
    public static final String STAGE_FINAL = "final";
    public static final String STAGE_AFTER_PREFIX = "after:";

    public IrSnapshot {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(ir, "ir");
    }

    public static String afterPass(String passName) {
        Objects.requireNonNull(passName, "passName");
        return STAGE_AFTER_PREFIX + passName;
    }

    /** Validates stage names accepted in {@code .batest.md} {@code stage=...} attributes. */
    public static String validateStage(String stage) {
        if (stage == null || stage.isEmpty()) {
            throw new IllegalArgumentException("IR stage cannot be empty");
        }
        if (STAGE_BEFORE.equals(stage) || STAGE_FINAL.equals(stage)) {
            return stage;
        }
        if (stage.startsWith(STAGE_AFTER_PREFIX) && stage.length() > STAGE_AFTER_PREFIX.length()) {
            return stage;
        }
        throw new IllegalArgumentException("Invalid IR stage : " + stage
                + " (expected before, final, or after:<PassName>)");
    }
}
