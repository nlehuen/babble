# BIP017: Higher-Order Function Effects (Placeholder)

**BIP:** 017  
**Date:** 2026-11-19  
**Status:** Draft / Placeholder (problem statement captured from `BIP016` review; full design and decisions deferred)  
**Depends on:** [bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md) (`BIP016-D3`, `BIP016-D6`)  
**Scope:** Effect polymorphism / callback-effect forwarding for higher-order functions (HOFs) across modules, interfaces, and first-class function values.

---

## 1. Problem Statement (`forEach` under `BIP016-D6`)

Under [`BIP016-D6`](bip016_consolidating-effect-annotations.md), Babble intentionally deferred effect-polymorphism syntax and instead used `fn io` upper bounds paired with intra-file bounded call-site specialization (`EffectChecker.trySpecializeCall`).

In `examples/raytracer-argon/lib/hittables.ba`, this produces an awkward signature pair:

```babble
;; Higher-order iteration over all hittables in the list
def forEach = fn io (f: fn io (item: Hittable): any?) -> (
    def cur: HittableNode? = this.head
    while cur != null then (
        f(cur.item)
        cur = cur.next
    )
)

;; Higher-order predicate counter using a closure over a mutable local
def countWhere = fn (pred: fn (item: Hittable): bool): int -> (
    def matched: int = 0
    this.forEach(fn (item: Hittable) -> (
        if pred(item) then (
            matched += 1
        )
    ))
    matched
)
```

This surfaces two closely related problems:

1. **Misleading surface contract & apparent inconsistency:** `forEach` performs **no I/O of its own** — its only effects come from invoking `f`. Labeling `forEach` as `fn io` makes it look impure to readers, and makes `countWhere` (which calls `this.forEach(...)` while remaining unmarked pure) appear inconsistent unless the reader knows about intra-file `trySpecializeCall` specialization.
2. **Cross-module and interface cliff:** `trySpecializeCall` requires access to the callee's concrete AST body and therefore only works **intra-file**. `countWhere` succeeds only because it is defined in the same file (`lib/hittables.ba`) as `forEach`. Any caller in *another module* (e.g., `raytracer.ba`), or any HOF declared on an `interface` or passed as a first-class function value, only sees the exported `fn io` contract — making pure calls to library HOFs like `forEach`, `map`, `filter`, or `fold` fail with an `io` error unless duplicated into separate pure and `io` overloads.

---

## 2. Placeholder Questions to Elaborate Later

* **`BIP017-Q1` (Surface syntax):** How should an effect-forwarding / effect-polymorphic HOF be written without reintroducing `BIP007`-style annotation noise or violating `BIP012`'s predicate-free `SLL` grammar (e.g., callback-effect forwarding such as `fn with f (...)`, lightweight effect variables, or a qualifier on callback parameters)?
* **`BIP017-Q2` (Cross-module & interface contracts):** How should `TypedModuleSummary`, `Type.Function`, and `interface` method signatures encode callback-effect forwarding so cross-module and dynamic-dispatch callers specialize cleanly without exposing AST bodies?
* **`BIP017-Q3` (Scope of forwarded traits):** Should forwarding cover only `{@HERMETIC, @DETERMINISTIC}` (`io`/`volatile`), or also `nothrow`, `@TERMINATING`, and receiver/argument mutability across multiple callback parameters?
* **`BIP017-Q4` (Relationship to `BIP013` iterators and future stdlib combinators):** While `BIP013`'s `for` loops reduce the need for `forEach`, standard collection combinators (`map`, `filter`, `flatMap`, `fold`, `any`, `all`) will rely directly on `BIP017`.
