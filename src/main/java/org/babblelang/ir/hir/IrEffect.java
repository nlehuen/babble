package org.babblelang.ir.hir;

import java.util.Objects;

/**
 * Fine-grained memory and control effect descriptor shared across HIR analysis and optimization
 * passes ({@code BIP039-D2}).
 *
 * <p>Promoted from legacy {@code MirEffect} to govern LICM hoisting, backward-liveness DCE,
 * predicate reordering, and dominator-scoped AEGraph GVN/CSE &amp; store-to-load forwarding on
 * structured {@link HirExpr} trees.
 */
public record IrEffect(
        Kind kind, AliasClass alias, boolean nothrow, boolean terminates, boolean deterministic) {

    public enum Kind {
        /** Reads no mutable heap/namespace state and writes nothing. */
        PURE,
        /** Reads heap/namespace state belonging to {@link #alias()}. */
        READ,
        /** Writes heap/namespace state belonging to {@link #alias()}. */
        WRITE,
        /** Arbitrary side effects (I/O, unknown calls, synchronization, unknown writes). */
        IMPURE
    }

    /** Alias partition of heap memory touched by a {@link Kind#READ} or {@link Kind#WRITE}. */
    public sealed interface AliasClass
            permits AliasClass.None, AliasClass.Frozen, AliasClass.UniqueParam, AliasClass.Shared {
        None NONE = new None();
        Frozen FROZEN = new Frozen();
        Shared SHARED = new Shared();

        static UniqueParam uniqueParam(int paramIndex) {
            return new UniqueParam(paramIndex);
        }

        record None() implements AliasClass {
            @Override
            public String toString() {
                return "none";
            }
        }

        /** Deeply immutable / frozen / final fields : no write can ever alias this partition. */
        record Frozen() implements AliasClass {
            @Override
            public String toString() {
                return "frozen";
            }
        }

        /**
         * Memory reachable exclusively from the {@code paramIndex}-th {@code unique} parameter of
         * the enclosing function : disjoint from every other {@code unique} parameter and from
         * {@link Shared} state.
         */
        record UniqueParam(int paramIndex) implements AliasClass {
            @Override
            public String toString() {
                return "unique#" + paramIndex;
            }
        }

        /** General mutable heap or namespace state. */
        record Shared() implements AliasClass {
            @Override
            public String toString() {
                return "shared";
            }
        }

        /**
         * Returns {@code true} if a write to {@code writeAlias} may modify a location in {@code
         * this} read alias class.
         */
        default boolean overlapsWrite(AliasClass writeAlias) {
            if (this instanceof None || this instanceof Frozen) {
                return false;
            }
            if (writeAlias instanceof None || writeAlias instanceof Frozen) {
                return false;
            }
            if (this instanceof UniqueParam u1 && writeAlias instanceof UniqueParam u2) {
                return u1.paramIndex == u2.paramIndex;
            }
            if (this instanceof UniqueParam && writeAlias instanceof Shared) {
                return false;
            }
            if (this instanceof Shared && writeAlias instanceof UniqueParam) {
                return false;
            }
            return true;
        }
    }

    public static final IrEffect PURE_NOTHROW =
            new IrEffect(Kind.PURE, AliasClass.NONE, true, true, true);
    public static final IrEffect PURE_MAY_THROW =
            new IrEffect(Kind.PURE, AliasClass.NONE, false, true, true);
    public static final IrEffect READ_FROZEN =
            new IrEffect(Kind.READ, AliasClass.FROZEN, true, true, true);
    public static final IrEffect READ_FROZEN_THROW =
            new IrEffect(Kind.READ, AliasClass.FROZEN, false, true, true);
    public static final IrEffect READ_SHARED =
            new IrEffect(Kind.READ, AliasClass.SHARED, true, true, true);
    public static final IrEffect READ_SHARED_THROW =
            new IrEffect(Kind.READ, AliasClass.SHARED, false, true, true);
    public static final IrEffect WRITE_SHARED =
            new IrEffect(Kind.WRITE, AliasClass.SHARED, true, true, true);
    public static final IrEffect WRITE_SHARED_THROW =
            new IrEffect(Kind.WRITE, AliasClass.SHARED, false, true, true);
    public static final IrEffect IMPURE_THROW =
            new IrEffect(Kind.IMPURE, AliasClass.SHARED, false, false, false);

    public IrEffect {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(alias, "alias");
    }

    public static IrEffect read(
            AliasClass alias, boolean nothrow, boolean terminates, boolean deterministic) {
        return new IrEffect(Kind.READ, alias, nothrow, terminates, deterministic);
    }

    public static IrEffect write(AliasClass alias, boolean nothrow) {
        return new IrEffect(Kind.WRITE, alias, nothrow, true, true);
    }

    /** True if this expression can be dropped completely when its value is unused. */
    public boolean isRemovableIfUnused() {
        return (kind == Kind.PURE || kind == Kind.READ) && nothrow && terminates;
    }

    /** True if this expression can be hoisted/floated purely without checking memory writes. */
    public boolean isPureFloatable() {
        return (kind == Kind.PURE || (kind == Kind.READ && alias instanceof AliasClass.Frozen))
                && nothrow
                && terminates
                && deterministic;
    }

    /** True if this expression is a deterministic, terminating, nothrow heap read. */
    public boolean isReadFloatable() {
        return kind == Kind.READ && nothrow && terminates && deterministic;
    }

    /** True if this expression may invalidate memory reads of {@code readAlias}. */
    public boolean isMemoryBarrierFor(AliasClass readAlias) {
        if (readAlias instanceof AliasClass.None || readAlias instanceof AliasClass.Frozen) {
            return false;
        }
        return switch (kind) {
            case PURE, READ -> false;
            case WRITE -> readAlias.overlapsWrite(alias);
            case IMPURE -> true;
        };
    }

    /** Combines two sequential/child effects into a conservative summary effect. */
    public static IrEffect combine(IrEffect a, IrEffect b) {
        if (a == PURE_NOTHROW) {
            return b;
        }
        if (b == PURE_NOTHROW) {
            return a;
        }
        Kind k = a.kind.ordinal() >= b.kind.ordinal() ? a.kind : b.kind;
        AliasClass al = mergeAlias(a.alias, b.alias);
        return new IrEffect(
                k,
                al,
                a.nothrow && b.nothrow,
                a.terminates && b.terminates,
                a.deterministic && b.deterministic);
    }

    /** Joins two alias partitions conservatively. */
    public static AliasClass mergeAlias(AliasClass a, AliasClass b) {
        if (a.equals(b)) {
            return a;
        }
        if (a instanceof AliasClass.None) {
            return b;
        }
        if (b instanceof AliasClass.None) {
            return a;
        }
        if (a instanceof AliasClass.Frozen) {
            return b;
        }
        if (b instanceof AliasClass.Frozen) {
            return a;
        }
        return AliasClass.SHARED;
    }

    /** Compact tag for IR dumps. */
    public String canonicalTag() {
        String base =
                switch (kind) {
                    case PURE -> "pure";
                    case READ -> "read:" + alias;
                    case WRITE -> "write:" + alias;
                    case IMPURE -> "impure";
                };
        return nothrow ? base : base + "!";
    }
}
