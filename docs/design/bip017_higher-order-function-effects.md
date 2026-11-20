# BIP017: Higher-Order Function Effects (`fn calling f`)

**BIP:** 017  
**Date:** 2026-11-19  
**Status:** Implemented in `RM009` (`BIP017-Q1`–`Q4` → `BIP017-D1`–`D4` decided; `BIP017-P1`–`P3` implemented across `Babble.g4`, `Formatter`, `EffectSet`, `Type.Function`, `TypeAnnotations`, `TypeChecker`, `TypeAlgebra`, `CallSolver`, `EffectChecker`, `TypedModuleSummary`, Argon `lib/hittables.ba`, `EffectsTestCase`, and `hof_calling_effects.batest`); `BIP017-D5` (captured callbacks, §2.5) implemented as `BIP017-P4`  
**Depends on:** [bip007_effects.md](bip007_effects.md) (`RM002`, `RM007`), [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md) (`RM004`: `TypedModuleSummary`, `CallSolver`, `TypeAlgebra`), [bip012_grammar-improvements.md](bip012_grammar-improvements.md) (`RM006`: predicate-free `SLL` grammar), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md) (`RM008`: pure-by-default opt-out surface syntax & return-through-borrow)  
**Scope:** Effect-polymorphic callback-effect forwarding (`fn calling f` / `fn calling (f, g)` / `fn io calling (f, g)`) across concrete functions, cross-module `TypedModuleSummary`, `interface` methods, and first-class function values; forwarding of callbacks a returned closure captures (`compose`); retirement of `BIP016-D6`'s implicit intra-file `fn io` HOF specialization.

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

### 2.1 Syntax: `fn calling f` / `fn calling (f, g)` (`BIP017-D1`)

Introduce a lightweight callback-effect forwarding clause **`calling`** on function literals, function types, and interface method signatures (with no heavy named effect variables like `of (effect E) E`; closure combinators like `compose`, whose callbacks outlive the call, name them in the clause of the function they return, §2.5):

```babble
;; Argon lib/hittables.ba — zero `io` noise anywhere!
def forEach = fn calling f (f: fn (item: Hittable): any?) -> (
    def cur: HittableNode? = this.head
    while cur != null then (
        f(cur.item)
        cur = cur.next
    )
)

def countWhere = fn calling pred (pred: fn (item: Hittable): bool): int -> (
    def matched: int = 0
    this.forEach(fn (item: Hittable) -> (
        if pred(item) then (
            matched += 1
        )
    ))
    matched
)

;; Future std/ combinators (cross-module and interface-safe):
export def map of (T, R) = fn calling f (shared xs: list of T, f: fn (x: T): R): list of R -> ( ... )
export def fold2 of (T, A) = fn calling (f, g) (xs: list of T, init: A, f: fn (a: A, x: T): A, g: fn (x: T): bool): A -> ( ... )
export def mapInPlace = fn mut calling f (f: fn (item: T): T) -> ( ... )
export def logAndApply = fn io calling (f, g) (v: int, f: fn (x: int): int, g: fn (x: int): int): int -> ( ... )
```

* **Grammar (`Babble.g4`, predicate-free `SLL`):**
  * Add lexer token `CALLING: 'calling';` and include `CALLING` in `memberName` so existing Java/Babble member accesses such as `obj.calling(...)` remain valid.
  * Add optional `callingClause` to `effectPrefix` (both `fn` literals and `fn` types):
    ```antlr
    callingClause: CALLING (ID | '(' NL* ID (NL* ',' NL* ID)* NL* ')');
    ```
  * Canonical formatting (`Formatter`): `fn [mut|volatile|io|shared|nothrow|...] [calling f | calling (f, g)] (...)`.
* **Meaning of `f`'s type when listed in `calling f`:** Listing parameter `f` in `calling f` makes `f` **effect-polymorphic** — `f` accepts pure, `volatile`, `io`, `nothrow`, frame-local mutating closures, and bound mutating methods (zero `io` noise in the signature!). Inside the HOF body, invoking `f(...)` is checked as if `f` were pure (its effects are forwarded to the HOF's caller rather than attributed to the HOF's own body).
* **Validation:**
  * Every identifier named in `calling ...` must match a parameter of the function whose type is a `Type.Function` (not a non-function type, not a duplicate name), or a callback parameter of an enclosing function it captures (§2.5).
  * Every parameter named in `calling ...` must be **`@BORROWED`** (the unmarked default — rejecting `shared f` or `unique f` in `calling f`, because a stored/escaping callback could be invoked *after* the call returns, breaking synchronous call-site effect forwarding).
  * The same holds for a callback the body lets escape (stored, returned, or captured by a returned closure, as in `compose`): it is reported once as `Parameter in calling clause escapes via … : remove f from calling and mark it as shared`, not as a borrowed parameter to mark `shared`. Such a combinator takes `shared` callbacks, and the closure that captures them names them in its own `calling` clause (§2.5).

### 2.2 Forwarded Traits (`BIP017-D2`)

At any call site `hof(..., cb, ...)` where `hof` declares `calling f`:

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

* **`Type.Function` & `TypedModuleSummary`:** Store `Set<Integer> forwardedEffectParams` (indexed by parameter position) on `Type.Function` (persisted automatically across modules and REPL sessions via `TypedModuleSummary`).
  * No AST bodies are needed across modules or interfaces — every caller (intra-file, cross-module, `interface` dispatch, or first-class function value) computes exact call-site effects directly from `Type.Function.forwardedEffectParams()` and the argument's `EffectSet` / `CallableSummary`.
* **Subtyping (`TypeAlgebra.functionRelates`):**
  * A forwarding function `fn calling f (f: fn (x: T): R): S` is semantically $\forall e.\; (\text{fn } e\,(x: T) \to R) \xrightarrow{e} S$, so it is a **subtype** of:
    * `fn (f: fn (x: T): R): S` (pure instantiation),
    * `fn volatile (f: fn volatile (x: T): R): S` (`volatile` instantiation), and
    * `fn io (f: fn io (x: T): R): S` (`io` instantiation),
    * as well as another `fn calling f` function whose own base effects and parameter/return types are compatible.
  * Conversely, a monomorphic `fn (f: fn ...)` (accepting only pure callbacks) is **not** a subtype of `fn calling f` (which must accept arbitrary callbacks).
* **Zero backend or runtime churn:** `calling f` is 100% erased after static effect checking — zero changes to `Interpreter`, `CodeGenerator`, or `org.babblelang.runtime`.

### 2.4 Retire `BIP016-D6` Implicit `fn io` Specialization (`BIP017-D4`)

Once `fn calling f` lands:

* **Retire `BIP016-D6`'s implicit intra-file specialization of `fn io` HOFs.** A function marked `fn io` always honestly means *"this function performs I/O"* — identically intra-file, cross-module, and on interfaces.
* **Migrate Argon & tests:** Update `HittableList.forEach` (and `countWhere`) in `examples/raytracer-argon/lib/hittables.ba` from `fn io` to `fn calling f` (reducing Argon to `3` `io` entrypoints, zero `io` in `lib/hittables.ba`!) and migrate any `BIP016-D6` HOF tests to `fn calling f`.

### 2.5 Captured Callbacks : `compose` (`BIP017-D5`)

`calling f` forwards `f`'s effects only while the call runs, so `f` must be borrowed (§2.1). A combinator whose callbacks outlive the call, such as `compose`, takes them `shared` instead, and the closure it returns, which is what calls them, names them in its own `calling` clause:

```babble
def compose of (A, B, C) = fn (shared f: fn (A): B, shared g: fn (B): C): fn calling (f, g) (A): C -> (
    fn calling (f, g) (a: A) -> (g(f(a)))
)
def show = compose(fn (x: int): int -> (x + 1), fn (x: int): string -> ("v" + x))     ;; fn (int) : string
def loud: fn io (x: int): int = compose(fn io (x: int): int -> (println(x); x), inc)  ;; io, since its f is
```

* **Names:** a name of a `calling` clause that is not a parameter of its own function resolves to a callback parameter of the innermost enclosing function literal or function type, from that function's result type or body; never from its parameter types (a callback can't forward a sibling: `Unknown parameter in calling clause`). The parameter must be a function (`Parameter in calling clause must be a function`); it isn't required to be borrowed, since capturing it is the point.
* **Representation:** `Type.Function.capturedCallings` holds those names, next to `forwardedEffectParams`, and is displayed in the same clause (`calling (f, g) (a : A) : C`). Substitution, joins (the union of both sides' captured names: forwarding more is weaker), and `TypedModuleSummary` carry it, so it works across modules and session inputs.
* **Inside the closure:** a call to a captured callback named in the clause is pure-modulo-forwarding, like a call to a `calling` parameter.
* **At the call site** (`CallSolver.instantiateCapturedCallings`): the callback parameters the result captures accept a callback of any effects (as `calling` parameters do), and every function type of the result (through nested results, so currying instantiates one call at a time) has the effects of the callbacks given folded in with `EffectSet.combineForwarded`, and loses their names; a callback of unknown effects (a Java functional object) counts as `io`. `compose(h, k)` of an `io h` is thus an `fn io (A) : C`, and calling it reaches its caller with `io`.
* **Subtyping:** a function forwarding a captured callback is a subtype only of a type forwarding it too, so the closure can't be returned as a plain `fn (A): C`.
* **Soundness:** the closure's own summary takes its captured callbacks as pure, so a call never resolves to it statically: it uses the call's type, where the callbacks' effects are (`EffectChecker.resolveBabbleCallSummary`). Calling the closure inside the function that captures the callbacks, before a call instantiates them, has their declared effects (or none, if that function forwards them in turn).
* **Limits:** passing a `calling` (borrowed) callback to `compose` makes it escape (`Parameter in calling clause escapes via call to compose`), although the composition may not outlive the call: telling that apart needs an escape analysis of the result.

---

## 3. Settled Decisions (`BIP017-Q1`–`Q4` → `BIP017-D1`–`D4`; `BIP017-D5`)

* **`BIP017-Q1` → `BIP017-D1` (Surface syntax):** `fn calling f (f: fn (...))` and `fn calling (f, g) (...)` (combinable with effect words, e.g., `fn io calling (f, g)`), with `CALLING` reserved and exempted in `memberName`, making listed callback parameters effect-polymorphic with zero `io` noise; skip heavy named effect variables (`effect E`).
* **`BIP017-Q2` → `BIP017-D3` (Cross-module & interface contracts):** Encode `forwardedEffectParams` directly in `Type.Function` and `TypedModuleSummary` so cross-module, `interface`, and first-class HOF calls forward effects identically without AST access; require forwarded parameters to be `@BORROWED`.
* **`BIP017-Q3` → `BIP017-D2` (Forwarded traits):** Forward function-level effects (`io`, `volatile`, `nothrow`, `@TERMINATING`) plus frame-local closures (`frameReadOwners`/`frameWriteOwners`) and captured/bound-method `mut` (`buf.append`), while preserving `f`'s declared parameter slot modifiers.
* **`BIP017-Q4` → `BIP017-D4` (Interaction with `BIP016-D6` & future `std/`):** Retire `BIP016-D6`'s implicit intra-file `fn io` HOF specialization so `fn io` is always honest, migrate Argon's `forEach` to `fn calling f`, and use `fn calling f` across future `std/` combinators (`map`, `filter`, `flatMap`, `fold`, `forEach`).
* **`BIP017-D5` (Captured callbacks):** a `calling` clause may name callback parameters of an enclosing function that the function captures (`compose`'s returned closure), recorded as `Type.Function.capturedCallings` and replaced at each call of the enclosing function by the effects of the callbacks given (§2.5).

---

## 4. Phased Implementation Plan (`BIP017-P1`–`P4`)

| Phase | Deliverables |
|---|---|
| **`BIP017-P1`: Grammar, `Formatter` & `Type.Function` Representation** | Add `CALLING` token (`memberName`-exempted, 0 predicates, pure `SLL`) and `callingClause` to `Babble.g4`; update `Formatter` (`FormatterTestCase`); add `forwardedEffectParams` to `Type.Function`, `TypeAnnotations`, `TypedModuleSummary`, and `TypeAlgebra.functionRelates` subtyping rules. |
| **`BIP017-P2`: `EffectChecker` Forwarding & Retirement of `BIP016-D6` Specialization** | Validate `calling` parameter names (must exist, be `Type.Function`, and be `@BORROWED`); inside a `fn calling f` body, treat calls to `f` as pure-modulo-forwarding; at call sites (concrete, cross-module, `interface`, first-class), combine HOF base effects with passed callback effects + frame-local `frameReadOwners`/`frameWriteOwners` + bound-method `mut` origin tracking; retire `BIP016-D6`'s implicit `fn io` specialization in `trySpecializeCall`. |
| **`BIP017-P3`: Migration, Tests & Verification** | Migrate `examples/raytracer-argon/lib/hittables.ba` (`forEach` / `countWhere` to `fn calling f` / `fn calling pred`); add comprehensive intra-file, cross-module, `interface`, first-class, bound-method (`buf.append`), and negative (`shared f`, unknown param, `fn io` no longer secretly specialized) tests in `EffectsTestCase` and `src/test/batest/effects/hof_calling_effects.batest`; verify full dual-mode `mvn -B test`, `ParserGrammarTestCase` (pure `SLL`), `RuntimeBoundaryTestCase` (0 runtime changes), `-format -check`, and byte-identical `examples/raytracer-argon/scene.ppm`. |
| **`BIP017-P4`: Captured Callbacks (`BIP017-D5`)** | `Type.Function.capturedCallings` (display, substitution, joins, subtyping); name resolution in `TypeAnnotations.resolveCapturedCallings` for function types and literals; `EffectChecker` validation, pure-modulo-forwarding calls in the closure and the summary guard; `CallSolver` relaxation and instantiation at call sites; tests in `src/test/batest/effects/hof_calling_captured.batest` (intra-file, inferred result, currying, cross-module, session, negatives) and `src/test/babble/Effects.ba`. |
