package org.babblelang.effects;

/** Compile-time effect, aliasing, and algebraic property guarantees (BIP007, BIP016). */
public enum Effect {
    /** Pure and deterministic : {@code {@HERMETIC, @CONST, @DETERMINISTIC}} (BIP016-D2, D8). */
    PURE,
    /** Internal Layer 1 trait : no I/O or global/outer writes ; may mutate {@code mut} slots. */
    HERMETIC,
    /**
     * Side-effect-free non-deterministic reader : {@code {@HERMETIC, !@DETERMINISTIC}} (BIP016-D3).
     */
    VOLATILE,
    /** Mutates its receiver ({@code this}) or an annotated parameter ({@code @MUTABLE}). */
    MUT,
    /**
     * Internal Layer 1 trait : does not retain a reference past the call's return
     * ({@code @BORROWED}).
     */
    BORROW,
    /**
     * Reference may outlive the call ({@code @SHARED}, opting out of {@code @BORROWED} default ;
     * BIP016-D4).
     */
    SHARED,
    /** Deeply owned reference graph transferred via {@code move} ({@code @UNIQUE} ; BIP016-D5). */
    UNIQUE,
    /**
     * Internal Layer 1 trait : same inputs and initial {@code mut} states always produce equal
     * outputs.
     */
    DETERMINISTIC,
    /** Never throws a Babble or runtime exception on well-typed inputs ({@code @NOTHROW}). */
    NOTHROW,
    /**
     * Repeated application is equivalent to one : {@code f(x) ; f(x) == f(x)} or {@code f(f(x)) ==
     * f(x)}.
     */
    IDEMPOTENT,
    /** Binary argument order does not matter : {@code f(a, b) == f(b, a)}. */
    COMMUTATIVE,
    /** Binary grouping does not matter : {@code f(f(a, b), c) == f(a, f(b, c))}. */
    ASSOCIATIVE,
    /** Order-preserving : {@code a <= b} implies {@code f(a) <= f(b)}. */
    MONOTONE,
    /** Distinct inputs map to distinct outputs : {@code f(a) == f(b)} implies {@code a == b}. */
    INJECTIVE,
    /** Explicit opt-out clearing {@code @HERMETIC} and {@code @DETERMINISTIC} (BIP016-D3, D8). */
    IO
}
