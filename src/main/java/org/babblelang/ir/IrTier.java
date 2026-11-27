package org.babblelang.ir;

import java.util.Locale;

/**
 * Compiler intermediate representation tier ({@code BIP029-I1}, {@code BIP039}).
 *
 * <p>Shared by unified {@code HIR} ({@code -O0}/{@code -O1}/{@code -O2}) and WebAssembly {@code
 * WAT} so CLI dumping ({@code -Dbabble.dumpIr}) and {@code .batest.md} golden assertions use one
 * unified pipeline.
 */
public enum IrTier {
    HIR("hir"),
    WAT("wat");

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
            case "wat" -> WAT;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown IR tier : " + raw + " (expected hir or wat)");
        };
    }
}
