package org.babblelang.ir.mir;

import java.util.Objects;

/**
 * Compact effect and alias summary attached to every {@link MirInst} ({@code BIP014-P3} §6.4).
 *
 * <p>Guides SSA dataflow passes ({@code GvnCse}, {@code Licm}, {@code AliasRle}, {@code MirDce},
 * {@code PredicateReorder}) without Sea-of-Nodes effect chains:
 * <ul>
 *   <li>{@link Kind#PURE} — depends only on SSA operands (no heap state); freely CSE'd/hoisted when
 *       {@code nothrow && terminating}.</li>
 *   <li>{@link Kind#READ} — reads heap state in {@link AliasClass}; invalidated only by writes whose
 *       {@link AliasClass} may overlap ({@link AliasClass#FROZEN} is never invalidated;
 *       {@code UNIQUE_PARAM(k)} is disjoint from {@code UNIQUE_PARAM(j)} for {@code j != k} and
 *       from {@link AliasClass#SHARED}).</li>
 *   <li>{@link Kind#WRITE} — mutates heap state in {@link AliasClass}; anchored in block order.</li>
 *   <li>{@link Kind#IMPURE} — arbitrary I/O, synchronization, or unknown mutation; full barrier.</li>
 * </ul>
 */
public record MirEffect(
        Kind kind,
        AliasClass alias,
        boolean nothrow,
        boolean terminating,
        boolean deterministic
) {
    public enum Kind {
        PURE,
        READ,
        WRITE,
        IMPURE
    }

    public sealed interface AliasClass permits
            AliasClass.None,
            AliasClass.Frozen,
            AliasClass.UniqueParam,
            AliasClass.Shared {

        AliasClass NONE = new None();
        AliasClass FROZEN = new Frozen();
        AliasClass SHARED = new Shared();

        static AliasClass uniqueParam(int paramIndex) {
            return new UniqueParam(paramIndex);
        }

        /** Whether a write to {@code writer} may alias a read from {@code this}. */
        default boolean overlapsWrite(AliasClass writer) {
            if (this instanceof None || this instanceof Frozen || writer instanceof None || writer instanceof Frozen) {
                return false;
            }
            if (this instanceof UniqueParam u1 && writer instanceof UniqueParam u2) {
                return u1.paramIndex() == u2.paramIndex();
            }
            if (this instanceof UniqueParam || writer instanceof UniqueParam) {
                // Disjoint by BIP007-I2 uniqueness contract: a unique parameter aliases neither
                // shared heap references nor any other unique parameter.
                return false;
            }
            return true;
        }

        String format();

        record None() implements AliasClass {
            @Override
            public String format() {
                return "none";
            }
        }

        record Frozen() implements AliasClass {
            @Override
            public String format() {
                return "frozen";
            }
        }

        record UniqueParam(int paramIndex) implements AliasClass {
            @Override
            public String format() {
                return "unique#" + paramIndex;
            }
        }

        record Shared() implements AliasClass {
            @Override
            public String format() {
                return "shared";
            }
        }
    }

    public static final MirEffect PURE_NOTHROW = new MirEffect(Kind.PURE, AliasClass.NONE, true, true, true);
    public static final MirEffect PURE_MAY_THROW = new MirEffect(Kind.PURE, AliasClass.NONE, false, true, true);
    public static final MirEffect READ_FROZEN = new MirEffect(Kind.READ, AliasClass.FROZEN, true, true, true);
    public static final MirEffect READ_SHARED = new MirEffect(Kind.READ, AliasClass.SHARED, true, true, true);
    public static final MirEffect READ_SHARED_THROW = new MirEffect(Kind.READ, AliasClass.SHARED, false, true, true);
    public static final MirEffect WRITE_SHARED = new MirEffect(Kind.WRITE, AliasClass.SHARED, true, true, false);
    public static final MirEffect WRITE_SHARED_THROW = new MirEffect(Kind.WRITE, AliasClass.SHARED, false, true, false);
    public static final MirEffect IMPURE_THROW = new MirEffect(Kind.IMPURE, AliasClass.SHARED, false, false, false);

    public MirEffect {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(alias, "alias");
    }

    public static MirEffect read(AliasClass alias, boolean nothrow, boolean terminating, boolean deterministic) {
        return new MirEffect(Kind.READ, alias, nothrow, terminating, deterministic);
    }

    public static MirEffect write(AliasClass alias, boolean nothrow) {
        return new MirEffect(Kind.WRITE, alias, nothrow, true, false);
    }

    public boolean isRemovableIfUnused() {
        return (kind == Kind.PURE || kind == Kind.READ) && nothrow && terminating;
    }

    public boolean isPureFloatable() {
        return kind == Kind.PURE && nothrow && terminating && deterministic;
    }

    public boolean isReadFloatable() {
        return kind == Kind.READ && nothrow && terminating && deterministic;
    }

    public boolean isMemoryBarrierFor(AliasClass readAlias) {
        if (kind == Kind.IMPURE) {
            return !(readAlias instanceof AliasClass.Frozen || readAlias instanceof AliasClass.None);
        }
        if (kind == Kind.WRITE) {
            return readAlias.overlapsWrite(this.alias);
        }
        return false;
    }

    /** Compact canonical annotation tag for {@link MirCanonicalPrinter}. */
    public String canonicalTag() {
        return switch (kind) {
            case PURE -> nothrow && terminating && deterministic ? "pure"
                    : (nothrow ? "pure!" : "pure,throws");
            case READ -> "read:" + alias.format() + (nothrow ? "" : ",throws");
            case WRITE -> "write:" + alias.format() + (nothrow ? "" : ",throws");
            case IMPURE -> "impure";
        };
    }
}
