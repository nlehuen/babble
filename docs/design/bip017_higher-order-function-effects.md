# BIP017: Higher-Order Function Effects (`fn with f`)

**BIP:** 017  
**Date:** 2026-11-19  
**Status:** Implemented in `RM009` (`BIP017-Q1`–`Q4` → `BIP017-D1`–`D4` decided; `BIP017-P1`–`P3` implemented across `Babble.g4`, `Formatter`, `EffectSet`, `Type.Function`, `TypeAnnotations`, `TypeChecker`, `TypeAlgebra`, `CallSolver`, `EffectChecker`, `TypedModuleSummary`, Argon `lib/hittables.ba`, `EffectsTestCase`, and `hof_with_effects.batest`)  
**Depends on:** [bip007_effects.md](bip007_effects.md) (`RM002`, `RM007`), [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md) (`RM004`: `TypedModuleSummary`, `CallSolver`, `TypeAlgebra`), [bip012_grammar-improvements.md](bip012_grammar-improvements.md) (`RM006`: predicate-free `SLL` grammar), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md) (`RM008`: pure-by-default opt-out surface syntax & return-through-borrow)  
**Scope:** Effect-polymorphic callback-effect forwarding (`fn with f` / `fn with (f, g)`) across concrete functions, cross-module `TypedModuleSummary`, `interface` methods, and first-class function values; retirement of `BIP016-D6`'s implicit intra-file `fn io` HOF specialization.

---

## 1. Problem Statement (`forEach` under `BIP016-D6`)

Under [`BIP016-D6`](bip016_consolidating-effect-annotations.md), Babble deferred effect-polymorphism syntax and instead used `fn io` upper bounds paired with intra-file bounded call-site specialization (`EffectChecker.trySpecializeCall`).

In `examples/raytracer-argon/lib/hittables.ba`, this produced an awkward signature pair:

```babble
;; Under BIP016-D6: looks like forEach performs IO itself, and breaks cross-module!
def forEach = fn io (f: fn io (item: Hittable): any?) -> (
    def cur: HittableNode? = this.head
    while cur != null then (
        f(cur.item)
        cur = cur.next
    )
)

;; Pure caller works ONLY because countWhere is in the same file as forEach:
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

This creates two concrete problems:

1. **Misleading surface contract & apparent inconsistency:** `forEach` performs **no I/O of its own** — its only effects come from invoking `f`. Labeling `forEach` as `fn io` makes it look impure to readers, and makes `countWhere` (calling `this.forEach(...)` while unmarked pure) look inconsistent unless the reader knows about intra-file `trySpecializeCall` specialization.
2. **Cross-module and interface cliff:** `trySpecializeCall` requires access to the callee's concrete AST body and therefore only works **intra-file**. Any caller in *another module* (e.g., `raytracer.ba`), or any HOF declared on an `interface` or passed as a first-class function value, sees only the exported `fn io` contract — causing pure calls to library HOFs like `forEach`, `map`, `filter`, or `fold` to fail with an `io` error unless duplicated into separate pure and `io` overloads.

---

## 2. Design (`BIP017-D1`–`D4`)

### 2.1 Syntax: `fn with f` / `fn with (f, g)` (`BIP017-D1`)

Introduce a lightweight callback-effect forwarding clause **`with`** on function literals, function types, and interface method signatures (with no heavy named effect variables like `of (effect E) E`, since higher-order closure combinators like `compose` are rare enough not to optimize syntax for):

```babble
;; Argon lib/hittables.ba — zero `io` noise anywhere!
def forEach = fn with f (f: fn (item: Hittable): any?) -> (
    def cur: HittableNode? = this.head
    while cur != null then (
        f(cur.item)
        cur = cur.next
    )
)

def countWhere = fn with pred (pred: fn (item: Hittable): bool): int -> (
    def matched: int = 0
    this.forEach(fn (item: Hittable) -> (
        if pred(item) then (
            matched += 1
        )
    ))
    matched
)

;; Future std/ combinators (cross-module and interface-safe):
export def map = fn with f of (T, R) (xs: list of T, f: fn (x: T): R): unique list of R -> ( ... )
export def fold2 = fn with (f, g) of (T, A) (xs: list of T, init: A, f: fn (a: A, x: T): A, g: fn (x: T): bool): A -> ( ... )
export def mapInPlace = fn mut with f (f: fn (item: T): T) -> ( ... )
```

* **Grammar (`Babble.g4`, predicate-free `SLL`):**
  * Add lexer token `WITH: 'with';` and include `WITH` in `memberName` so existing Java/Babble member accesses such as `java.time.LocalDate.with(...)` remain valid.
  * Add optional `withClause` to `effectPrefix` (both `fn` literals and `fn` types):
    ```antlr
    withClause: WITH (ID | LPAR ID (COMMA ID)* RPAR);
    ```
  * Canonical formatting (`Formatter`): `fn [mut|volatile|io|shared|nothrow|...] [with f | with (f, g)] [of ...] (...)`.
* **Meaning of `f`'s type when listed in `with f`:** Listing parameter `f` in `with f` makes `f` **effect-polymorphic** — `f` accepts pure, `volatile`, `io`, `nothrow`, frame-local mutating closures, and bound mutating methods (zero `io` noise in the signature!). Inside the HOF body, invoking `f(...)` is checked as if `f` were pure (its effects are forwarded to the HOF's caller rather than attributed to the HOF's own body).
* **Validation:**
  * Every identifier named in `with ...` must match a parameter of the function whose type is a `Type.Function` (not a non-function type, not a duplicate name).
  * Every parameter named in `with ...` must be **`@BORROWED`** (the unmarked default — rejecting `shared f` or `unique f` in `with f`, because a stored/escaping callback could be invoked *after* the call returns, breaking synchronous call-site effect forwarding).

### 2.2 Forwarded Traits (`BIP017-D2`)

At any call site `hof(..., cb, ...)` where `hof` declares `with f`:

* **Forwarded function-level effects:** `io` (`!@HERMETIC`), `volatile` (`!@DETERMINISTIC`), `@NOTHROW`, and `@TERMINATING`.
  * The call's effective `EffectSet` is the conjunction/meet of `hof`'s own effects and the actual passed `cb`'s effects:
    * `hermetic = hof.hermetic && cb.hermetic`
    * `deterministic = hof.deterministic && cb.deterministic`
    * `nothrow = hof.nothrow && cb.nothrow`
    * `terminating = hof.terminating && cb.terminating`
* **Forwarded frame-local & captured `mut`:**
  * Frame-local closures (`xs.forEach(fn (x) -> (sum += x))`) propagate their `frameReadOwners` / `frameWriteOwners` directly to the caller's frame (just as intra-file specialization did, now working **cross-module** and through **interfaces**!).
  * Bound mutating methods (`xs.forEach(buf.append)`, where `cb` has `mutThis = true` on `buf`) forward the mutation to `buf`'s origin at the call site — allowing `xs.forEach(buf.append)` inside a pure function when `buf` is a local `unique` variable or a `mut` parameter, and enforcing static `mut` exclusivity on `buf`!
* **Slot contracts preserved:** `f`'s own parameter slots keep their declared `@CONST`/`mut` and `@BORROWED`/`shared`/`unique` contracts (e.g., `f: fn (item: Hittable): any?` still guarantees `f` neither mutates nor stores `item`).

### 2.3 Type System, Subtyping & Cross-Module Representation (`BIP017-D3`)

* **`Type.Function` & `TypedModuleSummary`:** Store a compact `BitSet forwardedEffectParams` (indexed by parameter position) on `Type.Function` and `CallableEffectTemplate` / `TypedModuleSummary`.
  * No AST bodies are needed across modules or interfaces — every caller (intra-file, cross-module, `interface` dispatch, or first-class function value) computes exact call-site effects directly from `Type.Function.forwardedEffectParams()` and the argument's `EffectSet` / `CallableSummary`.
* **Subtyping (`TypeAlgebra.functionRelates`):**
  * A forwarding function `fn with f (f: fn (x: T): R): S` is semantically $\forall e.\; (\text{fn } e\,(x: T) \to R) \xrightarrow{e} S$, so it is a **subtype** of:
    * `fn (f: fn (x: T): R): S` (pure instantiation),
    * `fn volatile (f: fn volatile (x: T): R): S` (`volatile` instantiation), and
    * `fn io (f: fn io (x: T): R): S` (`io` instantiation),
    * as well as another `fn with f` function whose own base effects and parameter/return types are compatible.
  * Conversely, a monomorphic `fn (f: fn ...)` (accepting only pure callbacks) is **not** a subtype of `fn with f` (which must accept arbitrary callbacks).
* **Zero backend or runtime churn:** `with f` is 100% erased after static effect checking — zero changes to `Interpreter`, `CodeGenerator`, or `org.babblelang.runtime`.

### 2.4 Retire `BIP016-D6` Implicit `fn io` Specialization (`BIP017-D4`)

Once `fn with f` lands:

* **Retire `BIP016-D6`'s implicit intra-file specialization of `fn io` HOFs.** A function marked `fn io` always honestly means *"this function performs I/O"* — identically intra-file, cross-module, and on interfaces.
* **Migrate Argon & tests:** Update `HittableList.forEach` (and `countWhere`) in `examples/raytracer-argon/lib/hittables.ba` from `fn io` to `fn with f` (further reducing Argon's `io` count from `5 → 4`!) and migrate any `BIP016-D6` HOF tests to `fn with f`.

---

## 3. Settled Decisions (`BIP017-Q1`–`Q4` → `BIP017-D1`–`D4`)

* **`BIP017-Q1` → `BIP017-D1` (Surface syntax):** `fn with f (f: fn (...))` and `fn with (f, g) (...)`, with `WITH` reserved and exempted in `memberName`, making listed callback parameters effect-polymorphic with zero `io` noise; skip heavy named effect variables (`effect E`).
* **`BIP017-Q2` → `BIP017-D3` (Cross-module & interface contracts):** Encode `forwardedEffectParams` (`BitSet`) directly in `Type.Function` and `TypedModuleSummary` so cross-module, `interface`, and first-class HOF calls forward effects identically without AST access; require forwarded parameters to be `@BORROWED`.
* **`BIP017-Q3` → `BIP017-D2` (Forwarded traits):** Forward function-level effects (`io`, `volatile`, `nothrow`, `@TERMINATING`) plus frame-local closures (`frameReadOwners`/`frameWriteOwners`) and captured/bound-method `mut` (`buf.append`), while preserving `f`'s declared parameter slot modifiers.
* **`BIP017-Q4` → `BIP017-D4` (Interaction with `BIP016-D6` & future `std/`):** Retire `BIP016-D6`'s implicit intra-file `fn io` HOF specialization so `fn io` is always honest, migrate Argon's `forEach` to `fn with f`, and use `fn with f` across future `std/` combinators (`map`, `filter`, `flatMap`, `fold`, `forEach`).

---

## 4. Phased Implementation Plan (`BIP017-P1`–`P3`)

| Phase | Deliverables |
|---|---|
| **`BIP017-P1`: Grammar, `Formatter` & `Type.Function` Representation** | Add `WITH` token (`memberName`-exempted, 0 predicates, pure `SLL`) and `withClause` to `Babble.g4`; update `Formatter` (`FormatterTestCase`); add `forwardedEffectParams` (`BitSet`) to `Type.Function`, `TypeAnnotations`, `TypedModuleSummary`, and `TypeAlgebra.functionRelates` subtyping rules. |
| **`BIP017-P2`: `EffectChecker` Forwarding & Retirement of `BIP016-D6` Specialization** | Validate `with` parameter names (must exist, be `Type.Function`, and be `@BORROWED`); inside a `fn with f` body, treat calls to `f` as pure-modulo-forwarding; at call sites (concrete, cross-module, `interface`, first-class), combine HOF base effects with passed callback effects + frame-local `frameReadOwners`/`frameWriteOwners` + bound-method `mut` origin tracking; retire `BIP016-D6`'s implicit `fn io` specialization in `trySpecializeCall`. |
| **`BIP017-P3`: Migration, Tests & Verification** | Migrate `examples/raytracer-argon/lib/hittables.ba` (`forEach` / `countWhere` to `fn with f`); add comprehensive intra-file, cross-module, `interface`, first-class, bound-method (`buf.append`), and negative (`shared f`, unknown param, `fn io` no longer secretly specialized) tests in `EffectsTestCase` and `src/test/batest/effects/`; verify full dual-mode `mvn -B test`, `ParserGrammarTestCase` (pure `SLL`), `RuntimeBoundaryTestCase` (0 runtime changes), `-format -check`, and byte-identical `examples/raytracer-argon/scene.ppm`. |
