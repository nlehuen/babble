package org.babblelang.ir;

/**
 * Unified printable IR contract implemented by {@code HirModule} and upcoming {@code BIP014-I3}
 * {@code MirModule} ({@code BIP029-I1}).
 */
public interface IrDumpable {
    IrTier tier();

    /** Full raw textual representation (e.g. {@code HirPrinter.print(module)}). */
    String printRaw();

    /**
     * Canonical, preamble-independent, locally-renumbered representation of {@code target}.
     *
     * @throws IllegalArgumentException if {@code target} does not match a unique entity in this IR
     */
    String printCanonical(IrTarget target);
}
