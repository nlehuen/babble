package org.babblelang.ir;

import java.util.Locale;

/**
 * Compiler intermediate representation tier ({@code BIP029-P1}).
 *
 * <p>Shared by {@code HIR} ({@code BIP014-I1}–{@code I2}, {@code BIP014-I4}) and upcoming Tier 2
 * SSA {@code MIR} ({@code BIP014-P3}) so CLI dumping ({@code -Dbabble.dumpIr}) and
 * {@code .batest.md} golden assertions use one unified pipeline.
 */
public enum IrTier {
    HIR("hir"),
    MIR("mir");

    private final String label;

    IrTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static IrTier parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("IR tier cannot be null");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "hir" -> HIR;
            case "mir" -> MIR;
            default -> throw new IllegalArgumentException("Unknown IR tier : " + raw + " (expected hir or mir)");
        };
    }
}
