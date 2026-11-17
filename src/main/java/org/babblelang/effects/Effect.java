package org.babblelang.effects;

/**
 * Compile-time effect, aliasing, and algebraic property guarantees (BIP007).
 */
public enum Effect {
    /** Reads no external mutable state and mutates no external or parameter state. */
    PURE,
    /** Reads no external mutable state ; may mutate {@code mut} parameters or {@code this}. */
    HERMETIC,
    /** Mutates its receiver ({@code this}) or an annotated parameter. */
    MUT,
    /** Does not retain a reference past the call's return. */
    BORROW,
    /** Same inputs and initial {@code mut} states always produce equal outputs and final states. */
    DETERMINISTIC,
    /** Never throws a Babble or runtime exception on well-typed inputs. */
    NOTHROW,
    /** Repeated application is equivalent to one : {@code f(x) ; f(x) == f(x)} or {@code f(f(x)) == f(x)}. */
    IDEMPOTENT,
    /** Binary argument order does not matter : {@code f(a, b) == f(b, a)}. */
    COMMUTATIVE,
    /** Binary grouping does not matter : {@code f(f(a, b), c) == f(a, f(b, c))}. */
    ASSOCIATIVE,
    /** Order-preserving : {@code a <= b} implies {@code f(a) <= f(b)}. */
    MONOTONE,
    /** Distinct inputs map to distinct outputs : {@code f(a) == f(b)} implies {@code a == b}. */
    INJECTIVE,
    /** Explicit opt-out clearing inherited or inferred effect guarantees. */
    IMPURE
}
