# Design: Function Effects and Algebraic Properties in Babble

**Status:** Draft (High-Level Design)  
**Scope:** Static type system, effect inference & verification engine, `axiom` declarations, optimizer contract, Java interop, and runtime/bytecode compiler integration.

---

## Executive Summary

Babble captures **function effects and algebraic properties** directly in its type system and compiler IR so the compiler can both **secure computation** (preventing unintended state mutation, data races, and ambient I/O leaks) and **optimize computation** (reordering, common subexpression elimination / memoization, parallel execution, zero-copy thread sharing, predicate push-down/push-up, tree reductions, and safe automatic retries).

Rather than creating parallel "trusted" vs "checked" keyword hierarchies (e.g., `pure` vs `trusted_pure`), Babble uses **one composable property vocabulary** paired with **three derivation modes**:

1. **Inferred** — unannotated Babble functions automatically receive the strongest properties provable by whole-module fixed-point analysis.
2. **Verified** — explicitly annotated functions (e.g., `pure deterministic`) are statically proved by the compiler against their body and callees.
3. **Axiomatic (`axiom`)** — functions or Java targets prefixed with `axiom` (e.g., `axiom pure deterministic`, `axiom associative commutative`) assert properties that the compiler accepts as trusted contracts without inspecting/proving an implementation.

---

## 1. Context & Objectives

### 1.1 Problem Statement

A single binary `pure` / `impure` distinction is too coarse for modern compilers, query/pipeline optimizers, and concurrent runtimes:

* A time-reading function (`Instant.now()`) mutates no memory yet cannot be memoized or cached (`pure`, **not** `deterministic`).
* A builder helper mutates only one argument passed by its caller without touching globals (`pure` except for `mut` on that target argument).
* Division (`a / b`) is `pure deterministic` yet cannot be speculatively hoisted past `b != 0` (`nothrow` is false).
* A parallel accumulator (`ConcurrentHashMap.newKeySet().add(x)`) mutates shared state, yet is safe across threads, order-independent, and retry-safe (`mut idempotent commutative threadsafe`).
* String concatenation (`a + b`) is `associative` (parallel tree-reducible) without being `commutative`, whereas integer addition is both.

To unlock real compiler transformations without turning code into unreadable annotation noise, Babble needs a **structured, composable effect and algebraic property model** that maximizes static **inference** while offering **`axiom`** as an explicit escape hatch where static proof is impossible or external.

### 1.2 Objectives: What Effects Unlock

Every property in the model is justified by concrete security guarantees or compiler/runtime optimizations:

| Compiler / Runtime Capability | Required Properties (Contract) | Example Transformation |
|---|---|---|
| **CSE, Memoization & Constant Folding** | `pure deterministic` (no `mut` args) | Cache `f(x)` across repeated calls; fold `f(const)` at compile time. |
| **Loop-Invariant Code Motion (LICM)** | `pure deterministic` (+ `nothrow` if guarded) | Hoist `normalize(cfg)` out of a loop body. |
| **`noalias` Register Promotion & SIMD** | `mut` exclusivity (no overlapping args/`this`) | Hoist `src` loads past `mut dst` stores; auto-vectorize loops without alias checks. |
| **Expression & Filter Reordering** | `pure` (+ `nothrow` for guarded/short-circuit swaps) | Reorder `filter(p) \|> filter(q)` by estimated cost/selectivity. |
| **Predicate Push-Down & Push-Up** | `pure deterministic nothrow` (+ `monotone` / `injective` for inversion/projection pushdown) | Push `filter(p)` ahead of `map(f)` (`filter(x -> p(f(x))) \|> map(f)`), invert `monotone` bounds, push equality past `injective` projections, factor common filters above `union`. |
| **Parallel `map` / `filter`** | `pure threadsafe` (automatically implied by strong `pure deterministic` on immutable/`freeze` inputs) | Evaluate elements independently across worker threads without locks. |
| **Zero-Copy Builder $\to$ Parallel Read** | `unique` return + `freeze` | Build a large mutable graph/index locally, then `freeze` zero-copy into deeply immutable `threadsafe` data. |
| **Zero-Copy Thread Handoff & In-Place Reuse** | `unique` + `move` (single live owner) | Transfer mutable buffers between threads lock-free, or lower pure `sort(move xs)` into in-place mutation (FBIP). |
| **Stack Unboxing / Scalar Replacement** | `noescape` parameter (with `value` / fresh arg) | Pass `Point(x, y)` or closure captures directly in registers/locals without heap allocation. |
| **Parallel `reduce` (Tree Fold)** | `pure deterministic associative` (+ `commutative` if partition order unconstrained) | Split reduction into balanced parallel subtrees (`reduce(combine)`). |
| **Parallel Accumulation (`forEach`)** | `mut` (on target) + `commutative threadsafe` (+ `idempotent` for retry) | Concurrent unordered updates (`AtomicLong.add`, CRDT set union). |
| **Concurrent Data Sharing** | Non-`mut` params + `value class` / `freeze` (or `threadsafe` targets) | Share large immutable structures across threads with zero copying and zero locking. |
| **Safe Automatic Retry** | `idempotent` (or `pure` for read/compute steps) | Re-run failed steps (`Files.createDirectories`, `set.add`, `upsert`) without duplicating effects. |
| **Dead-Code Elimination & Speculation** | `pure nothrow` (+ `terminating`) | Drop unused results; speculatively evaluate before branches. |

### 1.3 Non-Goals

* **Full theorem proving / SMT verification of arbitrary equations:** The compiler does not attempt to prove algebraically from raw ASTs that arbitrary user arithmetic satisfies $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ or $f(a, b) = f(b, a)$; those rely on **`axiom`** (with optional property-based test verification).
* **Mandatory effect annotations everywhere:** Unannotated Babble scripts must continue to work seamlessly; inference and gradual typing avoid ceremony on ordinary scripting code.
* **Rust lifetime annotations (`'a`) or Pony's 6-way reference qualifiers on every variable:** Babble captures `noalias` exclusivity, `noescape`, `unique`/`move`, and `freeze` through parameter/return modes, definite-unassignment, and intra-procedural escape analysis rather than lifetime generics or pervasive viewpoint adaptation.

---

## 2. State of the Art & Prior Art

Babble synthesizes ideas from four distinct communities: typed functional/effect languages, compiler/static-analysis annotations, database query optimizers, and distributed monotonic systems.

| Community / System | Key Concepts & Annotations | Strengths (`+`) & Pitfalls (`-`) for Babble |
|---|---|---|
| **Haskell / Clean** | Monadic `IO` / `ST` vs uniqueness types | `+` Sound referential transparency.<br>`-` "Function color" split makes refactoring local mutable helpers painful (`ST` monad ceremony). |
| **D** | Strong vs weak `pure`, `@safe`, `nothrow`, `@nogc` | `+` Weak `pure` (no global mutation, local/param mutation allowed) is huge for imperative ergonomics.<br>`-` **"Attribute soup"** (`pure @safe nothrow @nogc const`) overwhelms readers without inference. |
| **Koka / Flix / Eff** | Algebraic effect rows (`<exn, div, console>`) + inference | `+` Effect inference avoids boilerplate.<br>`-` Higher-order effect polymorphism adds steep type-signature complexity for a gradual JVM language. |
| **Rust / Pony** | `&T` / `&mut T`, `Send` / `Sync`, `unsafe`; Pony `val`/`iso`/`box`/`ref` | `+` Distinguishing shared read (`&T` / `box`) vs exclusive mutation (`&mut T`) + immutable data (`val`) makes thread sharing safe.<br>`+` Explicit `unsafe` boundary concentrates audits. |
| **Java Checker Framework** | `@SideEffectFree`, `@Deterministic`, `@Pure` (`@SideEffectFree` + `@Deterministic`) | `+` Crucial separation: `@SideEffectFree` (no external mutation, e.g. `System.nanoTime()`) vs `@Deterministic` (same inputs $\implies$ same output). Both are needed together for memoization/CSE! |
| **PostgreSQL / GCC & Clang** | PG `IMMUTABLE` / `STABLE` / `VOLATILE`; GCC `__attribute__((const))` / `((pure))` | `+` `IMMUTABLE` $\approx$ `pure deterministic`; `STABLE` $\approx$ `pure` reading snapshot state (reorderable within statement, not foldable forever). |
| **Apache Calcite / Spark Catalyst / DuckDB** | `deterministic`, `strict` (null-preserving), `monotonic`, `injective`, `nothrow` | `+` Query optimizers show *which* properties unlock **predicate push-down/push-up**: `deterministic` + `nothrow` allow reordering across guards; `monotone` enables range pruning; `injective` pushes `distinct`/`==` across projections. |
| **Chapel / Fortress / OpenMP** | Reduction clauses (`+`, `*`, `min`, `max`), associativity + identity (monoids) | `+` `associative` enables parallel tree reductions; floating-point `+` requires opt-in or strict separation from integer `+`. |
| **Bloom (CALM) / LVars / Datafun / CRDTs** | Monotonic join-semilattices (ACI: Associative, Commutative, Idempotent) + `freeze` barriers | `+` Separates retry safety (`idempotent`) from order independence (`commutative`) and clarifies why reads of concurrent accumulators must happen after a barrier. |
| **Formal Systems / Lean / Dafny** | Checked proofs vs `axiom` declarations | `+` One uniform `axiom` concept works across both host FFI/Java and subtle user algorithms, replacing ad-hoc `trusted_*` keyword forks. |

### Key Design Lessons Synthesized for Babble

1. **Separate `pure` (writes) from `deterministic` (external reads) and `mut` (target/arg mutation):** Checker Framework, PostgreSQL, and D prove these are orthogonal. Folding them all into one bit either forbids valid read-only helpers or breaks caching.
2. **Inference by default beats attribute soup:** Every effect that *can* be inferred/verified structurally (`pure`, `mut`, `deterministic`, `nothrow`, derived `threadsafe`) should be inferred for unannotated Babble code.
3. **One `axiom` keyword replaces all `trusted_*` variants:** Writing `axiom pure deterministic` instead of `trusted_pure` scales cleanly to `axiom commutative`, `axiom associative`, `axiom idempotent`, and `axiom monotone` without multiplying keywords.
4. **Never conflate `idempotent` with `commutative`:** `map.put(k, v)` is `idempotent` (retry-safe) without being `commutative`; `counter.add(n)` is `commutative threadsafe` without being `idempotent`; CRDT set union (`set.add(x)`) has **both**. Keeping them independent makes the algebra exact.
5. **Distinguish `pure` from `nothrow`:** Predicate push-down and hoisting across `if`/loop guards require proving no runtime exception can be introduced.
6. **Respect Java bytecode & numeric limits:** Native/FFI calls (`System.arraycopy`) and floating-point non-associativity must be handled conservatively unless `axiom` says otherwise.

---

## 3. Proposed Model for Babble

### 3.1 Architecture Overview: Infer, Verify, or `axiom`

Every callable in Babble (function literal, method, constructor, or imported Java method/class) has an **Effect Profile** (`EffectSet` + per-parameter/receiver `mut` flags), derived in one of three modes:

1. **Inferred Mode (no explicit effect modifiers, and not marked `impure`):**
   * The compiler analyzes the function body and call graph via fixed-point iteration and infers the **maximal sound subset** of `{pure, deterministic, nothrow, threadsafe}`, the **minimal `mut` parameter/receiver set**, and compositional relational properties (`monotone`, `injective`).
   * An explicitly marked **`impure`** function suppresses inference and locks the signature to unconstrained (used to prevent callers from depending on accidental purity, or to opt out default-pure value methods like `URL.equals`).
2. **Verified Mode (explicit effect modifiers *without* `axiom`, e.g., `pure deterministic (x: int) -> (...)`):**
   * Declared modifiers become **compile-time obligations**: the compiler verifies that the body, all expressions, and all callees satisfy every declared property and mutates **only** declared `mut` parameters / `mut` `this` / fresh local objects.
   * Any violation is a compile-time `AnalysisException` pointing to the offending expression.
   * Meanwhile, *additional* unmentioned properties (like `nothrow`) can still be inferred quietly for the optimizer without cluttering the user's signature.
3. **Axiomatic Mode (`axiom <modifiers> ...`, e.g., `axiom pure deterministic`, `axiom associative commutative`):**
   * Tells the compiler to **assume** the listed properties for this function or Java target without verifying the implementation.
   * All `axiom` declarations are tagged with an auditable metadata flag (`axiomatic = true`) so security reviewers, linters, or strict sandbox policies can inspect every trust boundary—analogous to auditing `unsafe` in Rust.
   * If a property *is* still partially verifiable alongside an `axiom` (or if the user mixes `axiom` on one function called by a non-`axiom` caller), callers rely on the declared `axiom` contract normally.

```
Function declaration / Java target
   ├── No modifiers          ──▶ INFER (fixed-point over body & call graph)
   ├── Explicit modifiers    ──▶ VERIFY body satisfies declared contract (+ infer extras)
   ├── `axiom` + modifiers   ──▶ ASSUME contract as ground truth (audited trust boundary)
   └── `impure`              ──▶ LOCK unconstrained (suppress promotion; opt out defaults)
```

### 3.2 Effect & Property Taxonomy

#### Axis 1 — Memory, Aliasing, and Ownership Effects
* **`pure` (Effect-free outside `mut` targets):**
  * Performs **no I/O** (`print`, sockets, files, FFI).
  * Mutates **no global/outer variables, static fields, or external objects**.
  * Mutates **no parameters and not `this`**, *except* those explicitly tagged `mut` (and newly allocated fresh local objects that have not escaped to non-`mut` state).
  * *Strong vs Weak purity:* A `pure` function with **zero** `mut` parameters and a **non-`mut`** `this` is **strongly pure** (zero external state mutation). A `pure` function with `mut` parameters or `mut` `this` is **weakly pure** (effects strictly scoped to caller-supplied `mut` targets).
* **`mut` + Call-Site Exclusivity (`noalias`):**
  * Written on a parameter (`(mut target: List, item: string)`) or on the function itself (`mut` before parameters) to indicate **`this` may be mutated**.
  * Absent `mut`, inside any `pure` (or verified/effect-typed) scope, that parameter or `this` is **read-only borrowed**: field assignments (`p.x = ...`), calling `mut` methods on `p`, and passing `p` as a `mut` argument are forbidden.
  * **Law of Exclusivity (`noalias`):** Whenever an argument (or receiver) is passed to a `mut` slot, **no other argument or `this` at the same call site may visibly alias it** (checked statically for distinct variables/paths/immutable `value class`es, with runtime alias checks where dynamic). This guarantees Rust-style `noalias` semantics without lifetime parameters.
* **`noescape` (Frame-local parameter borrowing):**
  * Written (or automatically inferred) on a parameter `(noescape p: Point)` or receiver: guarantees the function never stores `p` (or mutable references reachable from `p`) into `this`, another parameter, a global, a captured closure, or its return value.
  * Unlocks **scalar replacement / stack unboxing** of wrapper objects (`Point`, temporary buffers, lambda captures) across call boundaries.
* **`unique`, `move`, and `freeze` (Single-owner graph ownership & zero-copy transition):**
  * **`unique`** on a parameter `(unique mut buf: Buffer)` or return type `unique (...) : Index` asserts that **zero other aliases exist** to the object graph outside that parameter/return value (Pony `iso` / Rust owned value).
  * **`move x`** explicitly transfers a `unique` or `FRESH` variable to a `unique` consumer (`channel.send(move buf)`, `sortInPlace(move list)`), triggering **definite-unassignment** on `x` in the caller (cannot be used after moving; omitted automatically on a binding's provable last use).
  * **`freeze(expr)`** consumes a `unique` (or `FRESH`) mutable graph and produces a **deeply immutable (`threadsafe`) read-only value** with **zero runtime copying**—allowing imperatively built indexes, graphs, and maps to immediately enter parallel pipelines.
* **`threadsafe` (Concurrent execution safety):**
  * Safe to invoke simultaneously from multiple threads without external locking or data races.
  * **Automatically derived** when a function is **strongly `pure` + `deterministic`** and every parameter/captured variable is deeply immutable (`value class`, `freeze` result, primitive) or read-only without concurrent writers. Must otherwise be granted via `axiom threadsafe` (e.g., `ConcurrentHashMap`, `AtomicInteger`).

#### Axis 2 — Evaluation & Safety Guarantees
* **`deterministic` (Functional dependence on visible inputs):**
  * Given identical input values (and identical pre-call states of any `mut` arguments/`this`), always yields the exact same result (and identical post-states).
  * Forbids calling non-`deterministic` functions (`System.nanoTime()`, `Math.random()`), reading non-`final` Java statics, reading reassigned/mutable outer bindings, or depending on unordered hash iteration.
  * *Why separate from `pure`?* A function reading an external config file (`axiom pure Files.readString`) or clock (`axiom pure Instant.now`) mutates nothing (`pure`), yet is **not** `deterministic` across time—so optimizers may reorder unused calls or discard them (if `nothrow`), **but must not memoize or CSE them across time**.
* **`nothrow` (Exception-freedom):**
  * Guaranteed never to throw a runtime error or exception (`throw`, `assert`, `/ 0`, null member dereference, failing dynamic cast/typecheck).
  * Essential for **speculative evaluation**, **hoisting out of guarded branches** (`if b != 0 then f(a, b)`), and **reordering short-circuiting predicates**.
* **`terminating` *(Phase 2 / optimizer flag, or combined as `total = nothrow terminating`)*:**
  * Guaranteed to finish in finite steps (no unbounded `while` loops or unguarded recursion). Required only when dropping dead calls whose infinite loop would otherwise prevent termination.

#### Axis 3 — Algebraic & Relational Properties
* **`idempotent`:**
  * Repeating the call with the same arguments/state produces the same result and the same state as calling it once:
    * For pure value endofunctions ($T \to T$): $f(f(x)) = f(x)$ (e.g., `abs`, `String.trim`, canonicalizers). Enables $f(f(x)) \to f(x)$ elimination.
    * For `mut` / stateful functions: executing `f(args)` twice consecutively yields the exact same post-state as once (e.g., `Files.createDirectories`, `set.add`, `latch.set()`, `upsert`). Enables **safe automatic retries** and duplicate suppression.
    * *Important:* Unlike the old purity draft, `idempotent` does **not** implicitly claim `commutative`.
* **`commutative`:**
  * **Value form** ($\ge 2$ symmetric parameters): reordering arguments yields the same result, $f(a, b) = f(b, a)$ (e.g., `int +`, `min`, `max`, `==`). Enables canonical operand ordering and argument-order-independent CSE.
  * **Effect form** (`mut` methods/functions on same target): two consecutive calls with different arguments commute, $s.f(a); s.f(b) \equiv s.f(b); s.f(a)$ (e.g., `AtomicInteger.add(k)`, `ConcurrentHashMap.newKeySet().add(k)`). Together with `threadsafe` (and `idempotent` for retries), enables **lock-free unordered parallel accumulation**.
* **`associative`** *(binary combiners `(T, T) : T`)*:
  * $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ (e.g., integer `+`, `*`, `min`, `max`, string `+`, list concat). Enables **parallel tree reductions (`parallelReduce`)**, loop reassociation, and streaming partition merges.
  * Note: Adding an optional identity/monoid tag (e.g., `identity(0)` in future extensions) lets `parallelReduce` run without a caller-supplied seed.
* **`monotone`** *(order-preserving unary or first-arg mapping)*:
  * $x \le y \implies f(x) \le f(y)$ (e.g., `x + k`, `x * k` for `k >= 0`, monotonic timestamp/ID buckets). Enables **zone-map / min-max range pruning** and pushing range predicates ($f(x) > c$) down into storage scans.
* **`injective`:**
  * $x \ne y \implies f(x) \ne f(y)$ (one-to-one mapping, e.g., bijective encodings, lossless wrappers `UserId(id)`). Enables pushing **`distinct`**, **`groupBy`**, and **`f(x) == f(y)`** through projections directly onto underlying keys.

### 3.3 Exact Verification vs. `axiom` Matrix

Because static checking faces fundamental computability bounds (Rice's theorem), Babble draws an explicit line between what the compiler **infers/verifies** and what requires **`axiom`**:

| Property | Inferred automatically? | Verified on body (without `axiom`)? | Requires `axiom` for non-trivial cases? |
|---|---|---|---|
| **`pure`** | **Yes** (fixed-point call graph) | **Yes** (sound) | Only for Java/FFI calls or benign hidden state (caches) |
| **`mut`** (params / `this`) + exclusivity | **Yes** (minimal mutated set + call-site `noalias`) | **Yes** (sound) | Only for Java methods (`axiom pure mut add(...)`) |
| **`noescape`** (params / `this`) | **Yes** (intra/inter-procedural escape analysis) | **Yes** (sound) | Only for Java methods (`axiom pure noescape length(...)`) |
| **`unique`** (return & moved params) | **Yes** for unaliased `FRESH` builders & returns | **Yes** (definite-unassignment + no-alias escape) | Needed when Java methods allocate/return isolated graphs (`axiom pure unique parse(...)`) |
| **`deterministic`** | **Yes** (fixed-point; forbids clocks/RNG/mutable globals) | **Yes** (sound) | Only for benign lazy memoization (`String.hashCode`) or Java |
| **`threadsafe`** | **Derived** for strong `pure deterministic` on `value`/`freeze` data | **Derived** (or rejects concurrent mutable sharing) | Required for lock-free / concurrent mutating Java (`ConcurrentHashMap`, `AtomicInteger`) |
| **`nothrow`** | **Yes** (conservative: no `throw`, `assert`, `/`, nullable/dynamic calls) | **Yes** (conservative) | Needed when invariants prevent failure (e.g. proven non-zero divisor / bounds-checked indexing) |
| **`idempotent`** | Trivial forwarding only | Trivial forwarding / invariant overwrite only | **Yes** (`axiom pure deterministic idempotent abs`, `axiom mut idempotent set.add`) |
| **`commutative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... commutative`) |
| **`associative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... associative`) |
| **`monotone` / `injective`** | **Compositional** ($f \circ g$ preserves `monotone` and `injective`) | **Compositional** over primitive axioms | Base arithmetic/conversions need `axiom` (primitives pre-seeded for built-ins) |

### 3.4 Subtyping and Effect Lattice

Each property $p$ defines a simple boolean partial order: having $p$ is strictly stronger (more constrained) than lacking $p$:

$$\text{has } p \quad <:\quad \text{may lack } p$$

For a function signature `(p1: T1, ..., mut noescape unique pk: Tk) : R` with effect set $E$:

* **Guarantee covariance (function-level effects, `noescape` params, and `unique` returns):**
  * `actual` satisfies `target` iff $E_{\text{target}} \subseteq E_{\text{actual}}$, `target.noescape(i) => actual.noescape(i)` (promising not to escape satisfies a caller allowing escape), and `target.uniqueReturn => actual.uniqueReturn` (returning a fresh unique object satisfies any non-unique return slot too: `unique R <: R`).
  * `pure deterministic nothrow (noescape x: Point) : unique Path` `<:` `pure deterministic (x: Point) : Path` `<:` `(x: Point) : Path`.
* **Mutability & Ownership contravariance-of-permission:**
  * Whenever `target` carries *any* effect modifier (e.g. `pure`, `deterministic`, etc.), `actual` may only declare `mut` on a parameter (or `this`) if `target` also declares `mut` on that parameter (or `this`):
    * `pure (x: List) : int` `<:` `pure (mut x: List) : int`, **not** the reverse!
  * On parameters, demanding ownership (`unique p`) is *stricter* on the caller: a callee taking an ordinary borrowed `(p: T)` satisfies a slot expecting `(unique p: T)` (`pure (p: Vec) <: pure (unique p: Vec)`), **not** the reverse!
* **Gradual / Legacy Compatibility:** An **unconstrained** function type `(x: T) : R` (empty effect set, no explicit `mut`/`unique`) acts as the top type for gradual Babble compatibility—any callable matches it, while callers receive no optimization or aliasing guarantees.

---

## 4. Syntax, Semantics, and Language Rules

### 4.1 Syntax in `Babble.g4`

Effects compose naturally in front of function literals, parameter declarations, function types, and external Java declarations:

```babble
;; 1. Verified function literals (compiler proves all listed properties):
def hypot = pure deterministic (a: double, b: double) : double -> (
    java.lang.Math.sqrt(a * a + b * b)
)

;; 2. Parameter & receiver mutability (`mut`), `noescape`, and `unique`:
def appendAll = pure deterministic (mut dst: ArrayList, noescape src: List) -> (
    ... ;; `dst` is exclusive (`noalias`) & mutated; `src` is untouched & never escapes
)

def buildIndex = pure deterministic (n: int) : unique HashMap -> (
    def m = java.util.HashMap()
    ... ;; returns unaliased mutable graph (`unique`)
    m
)

def sortPure = pure deterministic (unique mut xs: List) : unique List -> (
    ... ;; mutates owned `xs` in place and returns unique ownership (FBIP)
    xs
)

def Counter = class (def count: int = 0) (
    def peek = pure deterministic () : int -> ( this.count )
    def add  = pure mut deterministic (delta: int) -> ( this.count = this.count + delta )
)

;; 3. Unannotated functions (fully inferred by the compiler!):
def square = (x: int) : int -> ( x * x )   ;; inferred: pure deterministic nothrow threadsafe

;; 4. Axiomatic declarations (trusted contract, no proof checked on body/target):
def fastFib = axiom pure deterministic (n: int) : int -> ( ... )
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom mut idempotent commutative threadsafe my.CrdtSet.add

;; 5. Explicit opt-out (suppresses inference / opts out value defaults):
def debugLog = impure (msg: string) -> ( ... )
impure java.net.URL.equals

;; 6. Function types & ownership handoff (`move` / `freeze`):
def parallelMap = axiom pure threadsafe (items: List, fn: pure deterministic (x: int) : int) : List -> ( ... )
def parallelReduce = axiom pure threadsafe (items: List, combine: pure deterministic associative (a: int, b: int) : int) : int -> ( ... )
def frozenIndex = freeze(buildIndex(1000))      ;; zero-copy conversion to threadsafe immutable
def sorted = sortPure(move myList)              ;; transfers ownership; `myList` unusable afterward
```

**Grammar additions (`Babble.g4`):**
* Contextual or reserved effect keywords: `axiom`, `pure`, `mut`, `noescape`, `unique`, `move`, `freeze`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `threadsafe`, `monotone`, `injective`, `impure`.
* `# functionLiteral`: `AXIOM? effectModifier* parametersDeclaration (':' UNIQUE? type)? '->' NL* block`
* `# parameterDeclaration`: `PRIVATE? DEF? MUT? NOESCAPE? UNIQUE? ID (':' type)? ('=' NL* expression)?`
* `# functionType`: `effectModifier* parametersDeclaration (':' UNIQUE? type)?` (note: `axiom` and `impure` are invalid inside function types—`axiom` is a definition-site mechanism, never part of a caller-visible type!).
* `# effectDeclaration`: `EXPORT? AXIOM effectModifier+ expression` (plus `EXPORT? IMPURE expression` for opting out defaults).
* `# moveExpression`: `MOVE expression` (alongside built-in `freeze(expr)`).

> **Syntax order:** `axiom` (when present) appears first before effect modifiers; `impure` stands alone and cannot combine with `axiom` or positive effects. Effect modifiers (`pure`, `mut`, `deterministic`, etc.) may appear in canonical or arbitrary order and are normalized into an `EffectSet`.

### 4.2 Local State, Fresh Objects, Exclusivity, Ownership, and Outer State

Inside any function whose checked or inferred effect includes **`pure`** (and across call sites checking `mut` / `unique` / `noescape`):

1. **Local variables (`def x = ...`) may be reassigned freely** inside the function (`def i = 0; i = i + 1`). Local stack state is invisible outside the call.
2. **Freshly constructed local objects may be mutated freely** (`def v = Counter(0); v.add(5); v.count = 10`):
   * A local `def` is **fresh-local (`FRESH`)** if its initializer (and any reassignment) creates a new instance via a `pure` constructor, a `unique`-returning call, or an `object (...)` literal and no mutable alias from an un-`mut` parameter or outer scope was merged into it.
   * Mutating a fresh local does **not** require `mut` and does **not** break `pure` or `deterministic`.
3. **Parameters and `this` without `mut` are strictly unmutated:**
   * `this.x = ...`, `param.x = ...`, and calling any `mut` method `param.mutMethod()` are **rejected** unless `mut` is declared on `this` or `param`.
   * Aliasing a non-`mut` parameter (`def alias = param; alias.x = 1`) is rejected by local alias/freshness tracking.
4. **Call-Site `mut` Exclusivity (`noalias`):**
   * At any call passing an expression `arg` to a `mut` parameter (or calling a `mut` method `arg.m(...)`), `arg` cannot alias any other argument (or `this`) passed to the same call.
   * Statically checked for distinct locals/disjoint object paths (and automatically satisfied by `value class` / `freeze` arguments); calls with dynamic aliasing fall back to compile-time rejection or a runtime `same(a, b)` exclusivity error (`Cannot alias mut argument at call site`).
5. **Ownership Transfer (`unique`, `move`, `freeze`) and `noescape`:**
   * Passing an argument to a `unique` parameter, passing it to `freeze(...)`, or returning a `unique` local consumes ownership via `move expr` (inserted implicitly when the compiler proves it is the variable's last use). Definite-unassignment analysis rejects any subsequent read or write to a moved variable.
   * Inside a function declaring `(noescape p: T)`, assigning `p` (or any mutable sub-reference of `p`) to fields of `this`, another parameter, an outer/global variable, a returning closure, or returning `p` directly is rejected at compile time.
6. **Outer variables and global mutable singletons:**
   * **Writing** any outer `def`, mutable `object` singleton field, or `static` field is forbidden in `pure`.
   * **Reading** a reassigned outer `def`, non-`final` Java static field, or mutable outer singleton/field (even reached transitively through an outer `value class`) is forbidden in **`deterministic`** (and in checked **`pure`** unless explicitly mediated via an `axiom pure` reader—blocking accidental unsynchronized global state reads and data races).
   * Reading immutable outer `def`s (never reassigned and deeply `value class` / `freeze` / primitive / `pure` function) is valid in both `pure` and `deterministic`.

### 4.3 `value class`es, `object` Singletons, `static` Blocks, and Standard Value Protocol

1. **`value class` defaults:**
   * A `value class` constructor is **enforced `pure deterministic`** (its parameter defaults and top-level `classBlock` expressions must satisfy `pure deterministic`; fields are read-only at runtime in `BabbleInstance.setMember`).
   * Instance methods of a `value class` **default to `pure deterministic`** (or stronger inferred properties) unless explicitly opted out with `impure` or marked `axiom`—avoiding boilerplate on immutable data types.
   * Note: Because `value class` instances may hold references to mutable objects passed in at construction time, transitivity checks inspect field types when deciding `threadsafe` (deeply immutable `value class` vs shallow wrapper).
2. **Standard Value Protocol (`equals`, `hashCode`, `compareTo`, `toString`):**
   * Operators `==`, `!=`, `<`, `<=`, `>`, `>=` and string `+` invoke `equals`, `compareTo`, and `toString` implicitly (`Operators.java`, `BabbleInstance.toString()`).
   * On any Babble `class` or Java class, `equals`, `hashCode`, `compareTo`, and `toString` **default to `pure deterministic`** (with explicit `impure java.net.URL.equals` / `impure` opt-out supported when an override performs DNS or mutates external state).
   * `BabbleInstance.toString()` propagates active effect checks recursively through nested fields when called inside a `pure` / `deterministic` scope.
3. **Inline `object (...)` and `static` blocks:**
   * An `object (...)` expression or nested `class`/`interface` `static` block executed inside a `pure` or `deterministic` function is checked under the enclosing function's active constraints (cannot secretly print or mutate globals during class initialization).

### 4.4 Interfaces, Overrides, and Covariance

Interfaces can require arbitrary effect & algebraic contracts on members and default methods:

```babble
def Reducer = interface (
    def zero : pure deterministic nothrow () : int
    def combine : pure deterministic nothrow associative commutative (a: int, b: int) : int
    def mergeInto : pure mut commutative threadsafe (delta: int)
)
```

* Implementing or overriding an interface member must satisfy `actual <: required` across both `EffectSet` and `mut` permissions.
* Example: `pure deterministic` cannot satisfy an interface expecting `pure deterministic associative commutative`, whereas `axiom pure deterministic nothrow associative commutative` **does**.
* `pure ()` (non-`mut` `this`) CAN implement a `mut` slot `pure mut ()` (stronger guarantee satisfies weaker caller requirement), while `pure mut ()` CANNOT implement `pure ()`.

### 4.5 Nested Functions and Closures

Every function literal creates its own effect context (`inferred`, `verified`, or `axiom`):

* **Constructing a less-constrained closure inside a `pure deterministic` function** (e.g., returning an `impure` logger closure `() -> ( println(msg) )`) is `pure deterministic` itself (building the closure has no side effects and captures immutable `msg`), **provided** the inner closure does not capture a `mut` mutation of the enclosing function's non-`mut` parameters or `this`.
* **Calling** that closure inside the enclosing `pure deterministic` function requires its effect profile to satisfy the enclosing requirement (`actual <: enclosing`).

### 4.6 Built-ins, Operators, and Numeric Semantics

| Built-in / Operator | Intrinsic Effect Profile | Notes |
|---|---|---|
| `same(a, b)`, `classof(v)` | `pure deterministic nothrow` | Pointer/class inspection only (`same` is also `commutative` and `noescape`). |
| `freeze(unique v)` | `pure deterministic nothrow threadsafe` | Consumes a `unique` mutable graph zero-copy and yields a deeply immutable (`threadsafe`) reference. |
| `assert(cond, msg?)` / `suppose` | `pure deterministic` | **Not `nothrow`** (throws on false); arguments checked under enclosing mode. |
| `print(...)`, `println(...)`, `import(...)` | Unconstrained (`impure`) | I/O / module loading side effects. |
| `int +`, `int *` | `pure deterministic nothrow associative commutative` | Two's-complement wraparound is associative and commutative; `int +` & `int *` (`k > 0`) also `monotone`. |
| `double +`, `double *` | `pure deterministic nothrow commutative` | **Not `associative`** (IEEE-754 rounding). Parallel reduction over `double` requires explicit `axiom associative` or ordered fold. |
| `string +` (when both operands are `string`) | `pure deterministic nothrow associative` | **Not `commutative`**; **`injective`?** No (`"ab" + "c" == "a" + "bc"`). |
| `a / b` | `pure deterministic` | **Not `nothrow`** (`DivisionByZero` when `b == 0`); cannot be speculatively hoisted past `b != 0`. |
| `==`, `!=` (on value/primitive types) | `pure deterministic nothrow commutative` | Delegates to `equals` (checked for custom classes). |
| `<`, `<=`, `>`, `>=` | `pure deterministic` (+ `nothrow` for primitives) | Flip-duality (`a < b <=> b > a`) built into optimizer. |

---

## 5. Inference Engine, Verification, and Optimizations

### 5.1 Fixed-Point Effect Inference (`BlockScopes` + Call-Graph SCC + `TypeChecks`)

To minimize annotation burden, Babble maximizes inference across unannotated functions in the active compilation unit:

1. **Call-Graph Construction:** Build a static call graph over all functions, methods, constructors, closures, and `value class` bodies in `ScopeAnalysis`.
2. **SCC Condensation (Bottom-Up Fixed Point):** Partition into Strongly Connected Components (handling direct and mutual recursion in `recurse` or mutually recursive functions).
3. **Optimistic Initialization (per unannotated node):**
   * Start with `{pure, deterministic, nothrow}`, `mutParams = {}`, `noescapeParams = allParams`, `mutThis = false`, `uniqueReturn = true`.
   * Functions explicitly marked `impure` start and stay at `{}` (bottom; `noescapeParams = {}`, `uniqueReturn = false`).
   * Functions marked `axiom` are initialized directly to their declared `axiom` `EffectSet` / parameter flags and excluded from downward weakening.
4. **Transfer & Monotone Fixed-Point Iteration:**
   * **Field writes / calls to `mut`:** If target is `FRESH`, keep `pure` and `mutParams` unchanged; if target is `PARAM(i)` or `THIS`, add $i$ / `this` to `mutParams`/`mutThis`; if target is `EXTERNAL` or un-blessed Java, drop `pure`. Also check call-site **`mut` exclusivity** across arguments.
   * **Escape & uniqueness transfer:** Storing `PARAM(i)` into `this`, another parameter, a global, a closure, or returning it removes $i$ from `noescapeParams`. Returning any expression that may alias `EXTERNAL`, non-`unique` `PARAM`, or `THIS` drops `uniqueReturn`.
   * **Reads / non-determinism:** If expression reads a reassigned outer `def`, non-`final` static field, mutable singleton field, or calls a non-`deterministic` callee, drop `deterministic`.
   * **Exceptions:** If expression executes `throw`, `assert`, `/`, nullable/dynamic call, or calls a non-`nothrow` callee, drop `nothrow`.
   * **Compositional algebraic propagation:**
     * Composition $f(g(x))$ where both $f$ and $g$ are `monotone` $\implies$ `monotone`; same for `injective`.
     * Single-op wrapper `(a, b) -> ( op(a, b) )` inherits `commutative` / `associative` from `op`.
   * **Derived `threadsafe`:** After SCC converges, any strongly `pure deterministic` function whose parameter/captured types are deeply immutable (`value class`, `freeze` result, primitives, immutable strings) is marked `threadsafe`.
5. **Verification Check (Verified Mode):**
   * For every function with explicit non-`axiom` annotations, verify `inferred.satisfies(declared)` (including `declared.noescape ⊆ inferred.noescape`, `declared.uniqueReturn => inferred.uniqueReturn`) and `inferred.mutSet ⊆ declared.mutSet`, plus definite-unassignment after `move`/`freeze`. Emit targeted diagnostics if any obligation fails—while retaining *extra* inferred properties for downstream optimization!

> **What is fixed-point iteration?**  
> Because functions can be directly or mutually recursive (`f` calls `g` and `g` calls `f`), a single top-down or bottom-up pass cannot always know every callee's effect summary upfront. Fixed-point iteration solves these cyclic dependencies by treating the call graph as a system of constraints $\mathbf{S} = F(\mathbf{S})$: starting from optimistic initial guesses, a worklist loop repeatedly recomputes each function's summary from its body and callees' current summaries until a full iteration produces no further changes (a **fixed point**, $F(\mathbf{S}) = \mathbf{S}$). Because transfer rules only shrink finite effect sets monotonically (and never re-add dropped guarantees), termination is guaranteed in finite steps—producing the tightest sound summary permitted by the lattice.  
> **Further reading:**
> * [Data-flow analysis (Wikipedia)](https://en.wikipedia.org/wiki/Data-flow_analysis) — overview of iterative equation solving and Kildall's worklist method.
> * [Kleene fixed-point theorem (Wikipedia)](https://en.wikipedia.org/wiki/Kleene_fixed-point_theorem) — order-theoretic guarantee that monotone iteration on a finite-height lattice converges to a fixed point.
> * Anders Møller & Michael I. Schwartzbach, [*Static Program Analysis*](https://cs.au.dk/~amoeller/spa/) (Aarhus University) — accessible textbook covering lattices, monotone frameworks, and worklist solvers.
> * Gary A. Kildall (1973), *"A Unified Approach to Global Program Optimization"*, Proc. ACM POPL — foundational paper introducing lattice-based fixed-point dataflow analysis.

### 5.2 Escape, Uniqueness (`unique`), and Exclusivity Analysis

A function like:
```babble
def buildStats = (noescape xs: List) : unique Stats -> (
    def s = Stats(0, 0)
    s.add(xs.size())
    s
)
```
infers **`pure deterministic`** (`xs` is untouched and `noescape`, **not** `mut`) AND **`unique` return**—because `s` was freshly constructed inside `buildStats`, `xs` never leaked into `s`, and zero aliases to `s` remain in `buildStats`.

Babble tracks four local points-to / ownership states per binding in `TypeChecks`:
* **`FRESH` / `UNIQUE`:** Bound solely to a `pure` constructor, `object` literal, `unique` return, or unaliased `unique` parameter; mutating it never alters `mutParams`/`pure`, and it may be passed to `move`, `freeze(...)`, or returned as `unique`.
* **`PARAM(i)` / `THIS`:** Aliases parameter $i$ or `this`. Any mutation marks $i$ / `this` as `mut`; escaping it into `this`, another parameter, an outer variable, a closure, or the return value clears `noescape(i)`.
* **`FROZEN`:** Produced by `freeze(expr)` (or deeply immutable `value class` / primitive); deeply immutable and **`threadsafe`** for zero-copy concurrent sharing.
* **`MOVED` / `EXTERNAL`:** After `move x` or `freeze(x)` (including implicit last-use move), `x` transitions to `MOVED` (definite-unassignment rejects any later use of `x`); any reference escaping to/from globals becomes `EXTERNAL`.
* **Call-site `mut` exclusivity:** At `callee(a0, a1, ...)`, every `mut` argument `ai` must be alias-disjoint from every other `aj` and `this`—checked statically across distinct variables/paths/frozen values and guarded dynamically if dynamic aliasing is possible.

### 5.3 Concrete Compiler Optimizations Unlocked

With resolved `EffectSet`, `mut`/`noescape`, and `unique` metadata attached to AST / `TypeNode` / bytecode IR (`org.babblelang.engine.optimizer` and future passes), the compiler enables:

1. **CSE, Memoization & LICM:**
   * Two occurrences of `f(a, b)` with no intervening `mut` write to `a` or `b` collapse into one call iff `f` is **`pure deterministic`** (no `mut` args), or if **`commutative`** matches `f(b, a)` to `f(a, b)`.
   * Repeated nested calls `norm(norm(s))` collapse to `norm(s)` iff `norm` is **`pure deterministic idempotent`**.
2. **`noalias` Register Promotion, Store-to-Load Hoisting & SIMD Vectorization:**
   * Because **call-site `mut` exclusivity** guarantees `mut dst` never aliases `src` or `this`, loops writing `dst` can safely keep `src` fields in registers, hoist invariant `src` reads, eliminate redundant loads, and vectorize without runtime alias guards.
3. **Predicate Push-Down & Push-Up (Stream / Query Pipelines):**
   * **Guard-safe filter swap:** `if p(x) and q(x)` $\leftrightarrow$ `if q(x) and p(x)` (put cheaper/more selective filter first) iff `p` and `q` are **`pure` and `nothrow`** (or `q` never faults when `p` is false).
   * **Push filter below expensive `map` / `join`:** `xs.map(f).filter(p)` can invert/evaluate `p` on raw keys before a costly step (`xs.filter(x -> p'(x)).map(f)` or `join(xs, ys).filter(p_on_x)` $\to$ `join(xs.filter(p_on_x), ys)`) iff `f` and `p` are **`pure deterministic`** and **`nothrow`**. Inverted range predicates (`f(x) > c`) rely on **`monotone`**, and equality / `distinct` push-down (`f(x) == f(y) <=> x == y`) relies on **`injective`**.
   * **Push-up / Factorization:** `union(a.filter(p), b.filter(p))` $\leftrightarrow$ `union(a, b).filter(p)` iff `p` is **`pure deterministic nothrow`**.
4. **Zero-Copy Builders (`freeze`), Thread Handoff (`move`), and Functional-But-In-Place (FBIP):**
   * **`unique` $\to$ `freeze`:** Imperatively populate a local index/collection (`FRESH`/`unique`), call `freeze(idx)`, and immediately share it across `parallelMap`/`parallelFilter` threads with **zero copies and zero locks**.
   * **`unique` + `move` thread handoff:** Send mutable buffers across concurrent tasks/workers lock-free when `move` guarantees single ownership.
   * **FBIP in-place reuse:** When a caller passes a dead/moved `unique` collection into a pure transform (`sort(move xs)` / `xs.map(f)`), specialize to mutate backing storage in place with zero heap allocation while preserving referential transparency.
5. **Stack Unboxing / Scalar Replacement (`noescape`):**
   * Temporary `value class` arguments or closure captures passed into `noescape` parameters can be unboxed into primitive JVM locals/registers across call boundaries in `CodeGenerator`, avoiding heap allocation even when JIT inlining stops.
6. **Parallel Execution & Concurrent Data Sharing:**
   * **`parallelMap` / `parallelFilter`:** Safe whenever callback is **`pure threadsafe`** (automatically true for strong `pure deterministic` on `value class`/`freeze`/primitive inputs).
   * **`parallelReduce`:** Safe divide-and-conquer tree fold whenever combiner is **`pure deterministic associative`** (preserving partition order if not `commutative`, or arbitrary work-stealing if **`commutative`**).
   * **`parallelForEach`:** Safe concurrent accumulation whenever callback's `mut` writes use **`commutative threadsafe`** operations (and retryable on worker failure if **`idempotent`**).
7. **Reassociation & Dead-Code Elimination:**
   * Reassociate `(x + c1) + c2` $\to$ `x + (c1 + c2)` when `+` is **`pure deterministic nothrow associative`** (valid for `int` / `string`, skipped for IEEE-754 `double`!).
   * Eliminate unused expression `f(x); e` when `f` is **`pure nothrow`** (and terminating).

### 5.4 Property-Based Verification of `axiom`s (Test / Debug Aid)

While `axiom` tells the compiler to skip compile-time proof, semantic axioms (`deterministic`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `nothrow`) are ideal targets for **automated property-based testing** in a future `babble.checkAxioms` test mode:

* For `axiom commutative`: sample pairs $(a, b)$ and assert `f(a, b) == f(b, a)`.
* For `axiom associative`: sample triples $(a, b, c)$ and assert `f(f(a, b), c) == f(a, f(b, c))`.
* For `axiom idempotent`: assert `f(f(x)) == f(x)` (or post-state equality after 1 vs 2 calls on fresh `mut` state).
* For `axiom deterministic`: invoke twice with equal cloned inputs and assert identical outputs.

This turns unchecked mathematical assumptions into continuously fuzz-tested invariants in CI.

---

## 6. Java Interop and Bytecode Analysis

Java methods and classes default to **unconstrained** (`EffectSet.EMPTY`, conservative `mut` assumed on mutable receivers/args), **except** the standard value protocol (`equals`, `hashCode`, `toString`, `compareTo`), which defaults to `pure deterministic` unless opted out via `impure`.

### 6.1 In-Babble `axiom` and `impure` Declarations

```babble
;; Method-level axioms:
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom pure deterministic nothrow associative commutative java.lang.Integer.sum
axiom pure deterministic commutative java.lang.Math.addExact  ;; throws on overflow (not `nothrow`, not associative across overflow)
axiom pure deterministic nothrow commutative java.lang.Math.max
axiom pure java.nio.file.Files.readString                 ;; pure (no writes), NOT deterministic!
def mkdirs = axiom idempotent java.nio.file.Files.createDirectories
axiom mut commutative threadsafe java.util.concurrent.atomic.AtomicInteger.addAndGet

;; Class-level axioms (marks constructor + all public methods; individual methods can refine):
def BigDecimal = axiom pure deterministic import("java.math.BigDecimal")

;; Module re-export & per-runtime isolation:
export axiom pure deterministic nothrow java.lang.Math.sin

;; Opting out default-pure value protocol methods:
impure java.net.URL.equals
impure java.net.URL.hashCode
```

* Stored in the active `ScriptRuntime` (never mutating global `JavaClass.CACHE` singletons) so separate `BabbleScriptEngine` instances stay strictly isolated.
* `axiom` declarations on Java targets are forbidden inside verified `pure`/`deterministic` function bodies (must appear at module/top/impure scope).

### 6.2 Host-Side Java Annotations & `BabbleScriptEngine` API

Java libraries exposing APIs to Babble can annotate classes, constructors, or methods directly with composable annotations (`@Pure`, `@Mut`, `@Noescape`, `@Unique`, `@Deterministic`, `@Nothrow`, `@Idempotent`, `@Commutative`, `@Associative`, `@Threadsafe`, `@Monotone`, `@Injective`, `@Impure`):

```java
public final class Geo {
    @Pure @Deterministic @Nothrow
    public static double haversine(double lat1, double lon1, double lat2, double lon2) { ... }

    @Idempotent
    public static void ensureOutputDir(String dir) { ... }
}
```

Or embedded hosts can register trusted JDK/third-party methods programmatically via `BabbleScriptEngine`:

```java
engine.assumeEffects(Math.class, "sqrt", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
engine.assumeEffects(Files.class, "readString", Effect.PURE); // read-only, non-deterministic
engine.assumeEffects(Files.class, "createDirectories", Effect.IDEMPOTENT);
engine.markImpure(URL.class, "equals", "hashCode");
```

Resolution priority (`JavaMethod.effects(ScriptRuntime)` / `JavaClass.effects(ScriptRuntime)`):
1. Per-runtime method override (`ScriptRuntime` via Babble `axiom` / `impure` or `BabbleScriptEngine`), else
2. Java method annotations (`@Pure`, `@Deterministic`, ...), else
3. Per-runtime class override, else
4. Java class annotations, else
5. Default value protocol (`equals`, `hashCode`, `toString`, `compareTo` $\to$ `{PURE, DETERMINISTIC}`), else
6. Unconstrained (`EffectSet.EMPTY`).

### 6.3 Java Callbacks and Interface Proxies (`Faces`)

When a verified `pure deterministic` Babble function passes a Babble `Callable` or `BabbleInstance` (wrapped via `Faces.function` or `Faces.create` into `Function`, `Predicate`, `Comparator`, etc.) to a Java HOF such as `list.parallelStream().map(fn)`:

* **Static check (`TypeChecker`):** Functional-interface arguments inside constrained scopes must satisfy the enclosing call's effect requirements (e.g., passing an unconstrained `(x) -> ( println(x) )` to `parallelStream().map(...)` inside a `pure` caller is rejected statically).
* **Runtime check (`Faces.FunctionHandler` & `Faces.FaceHandler`):** Active effect requirements (`pure`, `deterministic`, `nothrow`) propagate via thread/frame context into Java proxies so dynamic Babble callbacks cannot smuggle side effects back through `axiom` Java libraries.

### 6.4 Automatic Static Analysis of Java Bytecode via ASM (Feasibility & Limits)

Babble already depends on `org.ow2.asm:asm` (`pom.xml`), raising the question: can bytecode analysis automatically infer effects on JDK/library `.class` files instead of requiring `axiom` declarations—assuming all `native`/FFI methods (`ACC_NATIVE`, JNI, Panama FFM) are unconstrained (`impure`)?

* **Feasibility & Soundness:** **Yes**, for memory/exception effects (`pure`, `mut`, `deterministic`, `nothrow`). An ASM analyzer (like Sălcianu–Rinard's pointer/escape analysis or JPure) can soundly build a conservative call graph and inspect `PUTFIELD`, `PUTSTATIC`, `GETSTATIC` (non-`final`), `*ASTORE`, `MONITORENTER`, `<clinit>`, and `ACC_NATIVE`, falling back to unconstrained whenever open virtual/interface dispatch (`INVOKEVIRTUAL`, `INVOKEINTERFACE`), reflection, or `<clinit>` cannot be bounded.
* **Precision Limits:** Under a strict *"all `native` calls are impure"* policy, precision degrades sharply on the JDK:
  1. **Transitive `native` intrinsics:** `System.arraycopy` (`native`), `Object.getClass()` (`native`), and `Double.doubleToRawLongBits` (`native`) appear inside `String.substring`, `ArrayList`, `Arrays.copyOf`, and `BigDecimal`—tainting nearly the whole JDK without a small audited intrinsic seed table.
  2. **Internal local mutations & benign lazy caches:** `StringBuilder` inside `toString()`, `this.hash` caching in `String.hashCode()`, and `BigDecimal.stringCache` perform `PUTFIELD`/`*ASTORE`, requiring inter-procedural escape analysis for local buffers and explicit `axiom` for benign lazy memoization caches.
  3. **Algebraic & policy properties cannot come from bytecode:** Bytecode inspection can **never** infer that `Files.readString` is acceptable policy (`axiom pure`), nor in general prove `commutative` / `associative` / `idempotent` / `monotone` / `injective`.
* **Verdict for Babble:** Keep explicit `axiom` + annotations + `BabbleScriptEngine` API as the primary Phase 1 mechanism; add an optional **ASM bytecode analyzer in Phase 3** (seeded with proven JVM `native` intrinsics like `System.arraycopy`) to **suggest `axiom` candidates** or auto-certify closed pure helpers.

---

## 7. Type System, Compiler, and Runtime Architecture

### 7.1 `Type.Function`, `Type.Parameter`, and `EffectSet`

In `org.babblelang.analysis.Type`:

* **`EffectSet`** (immutable bitmask / enum set over `{PURE, DETERMINISTIC, NOTHROW, IDEMPOTENT, COMMUTATIVE, ASSOCIATIVE, THREADSAFE, MONOTONE, INJECTIVE}` + metadata flags `boolean mutThis`, `boolean noescapeThis`, `boolean uniqueResult`, and explicit `boolean impureOptOut`).
* **`Type.Parameter(String name, Type type, boolean optional, boolean mut, boolean noescape, boolean unique)`**: encodes per-parameter mutability, escape guarantee, and ownership (`(mut noescape unique dst: Buffer, noescape src: List)`).
* **`Type.Function(List<Parameter> parameters, Type result, EffectSet effects)`**:
  * Assignability (`TypeChecker`): if `target.effects` is constrained (non-empty or any `mut`/`noescape`/`unique`), enforce `actual.effects.satisfies(target.effects)`, `target.noescapeThis => actual.noescapeThis`, `target.uniqueResult => actual.uniqueResult`, `actual.mutThis => target.mutThis`, and per parameter: `target.noescape(i) => actual.noescape(i)`, `actual.mut(i) => target.mut(i)`, `actual.uniqueParam(i) => target.uniqueParam(i)`. Unconstrained `(A) : B` stays top for gradual compatibility.

### 7.2 Dynamic Boundary Checks (`Types` in `error` mode)

In `babble.typecheck=error` mode (`type-system.md` §5), function descriptors encode required effects (`fx|<effects>|<display>|`), checking `callable.effects(runtime).satisfies(required)` when crossing dynamic boundaries into effect-constrained parameters or fields. Even in `off`/`warn` mode, call sites inside verified functions enforce callee compatibility before dispatch.

### 7.3 Compiler (`CodeGenerator`, `Bootstrap`) and Interpreter Integration

1. **Callable & `ScriptRuntime`:** `Callable.effects(ScriptRuntime)` returns the runtime `EffectSet` (including `mutThis` and `BitSet`/`long` mask of mutated param indices); `ScriptRuntime` holds isolated per-engine Java class/method effect overrides (`assumeEffects`, `markImpure`).
2. **`CodeGenerator`:**
   * Stores resolved `EffectSet` metadata on `CompiledFunction` / `CompiledMethod` and AST/IR nodes so optimizer passes (`OptimizerBase` extensions) can query `effects(node)` directly.
   * For `invokedynamic` call sites inside verified scopes, encodes the required `EffectSet` in `Bootstrap`: linkage checks `target.effects(runtime).satisfies(required)` **once at inline-cache linkage time** (zero steady-state per-call overhead), falling back to `Calls.callWithEffects(...)` on mismatch.
3. **Interpreter:** Propagates active verified `EffectSet` requirements across call sites, operators (`+`, `==`, `!=`, `<`, `<=`, `>`, `>=`), `BabbleInstance.toString()`, and `Faces` callbacks.

---

## 8. Examples

### 8.1 Verified Pure Deterministic Code vs. `axiom` Java Interop

```babble
;; Declare trusted host axioms explicitly:
axiom pure deterministic nothrow java.lang.Math.sin
axiom pure deterministic nothrow java.lang.Math.cos
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom pure java.nio.file.Files.readString   ;; side-effect-free, NOT deterministic!
axiom pure deterministic nothrow java.nio.file.Path.of

package math (
    ;; Verified: compiler checks body satisfies `pure deterministic` (also infers `nothrow`?
    ;; No: divides by `g`, so `nothrow` is not inferred unless proven/axiomatized)
    export def sqrtApprox = pure deterministic (x: double) : double -> (
        if x <= 0.0 then ( return 0.0 )
        def g : double = x
        def i : int = 0
        while i < 12 then (
            g = 0.5 * (g + x / g)
            i = i + 1
        )
        g
    )

    ;; Unannotated helper: compiler infers `pure deterministic nothrow threadsafe` automatically!
    export def dot2 = (ax: double, ay: double, bx: double, by: double) : double -> (
        ax * bx + ay * by
    )
)

;; Rejected: `Files.readString` is `pure` but NOT `deterministic`!
def badMemo = pure deterministic (p: string) : string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)

;; Accepted as `pure` (safe to discard/reorder across memory, not memoized across I/O):
def loadConfig = pure (p: string) : string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)
```

### 8.2 Argument & Receiver `mut` (Weak Purity & Fresh Locals)

```babble
def Counter = class (def count: int = 0) (
    def peek = pure deterministic nothrow () : int -> ( this.count )
    def add  = pure mut deterministic nothrow (d: int) -> ( this.count = this.count + d )
)

;; Strongly pure: mutates neither globals nor `c`, only fresh local `tmp`:
def previewNext = pure deterministic (c: Counter, delta: int) : int -> (
    def tmp = Counter(c.peek())   ;; fresh local instance
    tmp.add(delta)                ;; OK: mutating fresh local object is pure!
    tmp.peek()
)

;; Weakly pure: explicitly declares `mut c`, forbids touching globals or other parameters:
def bumpBoth = pure deterministic (mut dst: Counter, src: Counter) -> (
    dst.add(src.peek())           ;; OK: `dst` is `mut`
    ;; src.add(1)                 ;; REJECTED: `src` is not `mut`!
    null
)
```

### 8.3 Predicate Push-Down, Reordering & Algebraic Pruning

```babble
;; Lossless monotone encoding: compiler infers/checks composition and optimizer can push bounds
def encodeScore = axiom pure deterministic nothrow monotone injective (raw: int) : int -> (
    raw * 10 + 5
)

def isEligible = (score: int) : bool -> ( score > 100 )       ;; inferred: pure deterministic nothrow
def nonNegative = (raw: int) : bool -> ( raw >= 0 )            ;; inferred: pure deterministic nothrow

;; Because `encodeScore`, `isEligible`, and `nonNegative` are all `pure deterministic nothrow`:
;; 1) Predicates in `filter` can be reordered freely by cost/selectivity without changing errors.
;; 2) Filters can be pushed past `map(encodeScore)`.
;; 3) With `monotone`, `encodeScore(raw) > 100` can even be rewritten/pruned on raw sorted chunks!
```

### 8.4 Parallel `map`, Tree `reduce` (`associative`), and Retry-Safe Accumulation

```babble
axiom mut idempotent commutative threadsafe my.CrdtSet.add
axiom idempotent java.nio.file.Files.createDirectories

def sumInt = axiom pure deterministic nothrow associative commutative (a: int, b: int) : int -> ( a + b )

def parallelMap = axiom pure threadsafe (xs: List, f: pure deterministic (x: int) : int) : List -> ( ... )
def parallelReduce = axiom pure threadsafe (xs: List, seed: int, op: pure deterministic associative (a: int, b: int) : int) : int -> ( ... )
def parallelForEach = axiom (xs: List, f: commutative threadsafe (x: string)) -> ( ... )

;; 1) Safe parallel map + tree reduction:
def totalSq = parallelReduce(parallelMap(items, (x: int) -> ( x * x )), 0, sumInt)

;; 2) Safe unordered concurrent accumulation + retry (idempotent + commutative + threadsafe):
def collector = my.CrdtSet()
def ensureAndTrack = axiom idempotent commutative threadsafe (dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    collector.add(dir)
)
parallelForEach(dirs, ensureAndTrack)
```

### 8.5 Exclusivity (`noalias`), `unique` / `move` / `freeze`, and `noescape`

```babble
def Vec = class (def data: java.util.ArrayList) ( ... )

;; 1) Call-site `mut` exclusivity (`noalias`):
def addScaled = pure deterministic (mut dst: Vec, noescape src: Vec, k: double) -> (
    ... ;; Compiler knows `dst` cannot alias `src` -> safe SIMD / register hoisting!
)
def v = Vec(...)
;; addScaled(v, v, 2.0)   ;; REJECTED: `dst` (`mut`) and `src` cannot alias `v`!

;; 2) Imperative local builder -> zero-copy `freeze` -> concurrent parallel reads:
def buildLookup = pure deterministic (entries: List) : unique java.util.HashMap -> (
    def m = java.util.HashMap()
    ... ;; populate `m` locally
    m   ;; no other aliases to `m` exist -> verified `unique` return!
)
def readOnlyLookup = freeze(buildLookup(rawEntries))  ;; zero copy, now `threadsafe`!
def results = parallelMap(queries, (q: int) -> ( readOnlyLookup.get(q) ))

;; 3) Functional-but-in-place (FBIP) & definite-unassignment with `move`:
def sortInPlace = pure deterministic (unique mut xs: List) : unique List -> ( ... )
def a = List(3, 1, 2)
def b = sortInPlace(move a)  ;; `a` is sorted in place without cloning
;; a.size()                  ;; REJECTED: `a` was moved and cannot be used afterward!

;; 4) Scalar replacement / stack unboxing via `noescape`:
def distSq = pure deterministic nothrow (noescape p: Point, noescape q: Point) : double -> (
    def dx = p.x - q.x
    def dy = p.y - q.y
    dx * dx + dy * dy
)
;; Calling `distSq(Point(x1, y1), Point(x2, y2))` passes raw doubles without heap allocation!
```

---

## 9. Open Questions & Phased Implementation Plan

### 9.1 Open Design Questions

1. **Short-circuiting `and` / `or` and predicate push-down:** In `a != null and a.x > 0`, `a.x` is **not** `nothrow` unconditionally—it is only safe *after* `a != null`. Should optimizers treat short-circuit guards via path-sensitive nullability, leaving unconditional reordering strictly for two mutually `nothrow` predicates? *(Recommended: Yes—never reorder a non-`nothrow` right-hand side ahead of a guard.)*
2. **Module exports and inferred vs explicit contracts:** Should `export def f = (x) -> (...)` export `f`'s **inferred** effects across module boundaries, or should cross-module public contracts require explicit annotations unless opted into inference? *(Exporting inferred effects maximizes optimization across modules, while explicit `impure` lets library authors hide unstable implementation properties.)*
3. **Zero / Identity elements for `associative` (`monoid`):** Should `parallelReduce` allow an optional identity annotation (e.g., `axiom associative identity(0)`) or take `seed` explicitly as in §8.4? *(Taking `seed` in Phase 1 keeps the annotation language small; `identity` can be added as an optional Phase 3 attribute.)*
4. **Local `axiom (...)` expression blocks:** Like Rust `unsafe { ... }`, should a verified `pure deterministic` function be allowed to enclose a tiny verified-safe internal mutation cache in an inline `axiom ( ... )` block, or should `axiom` stay strictly at function/declaration granularity? *(Starting at declaration granularity keeps trust boundaries crisp and grep-able.)*

### 9.2 Phased Implementation Roadmap

1. **Phase 1 — Unified Core Model, Verification & `axiom`:**
   * Grammar (`Babble.g4`): add `axiom`, `pure`, `mut`, `noescape`, `unique`, `move`, `freeze`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `threadsafe`, `monotone`, `injective`, `impure` on function literals, parameters, function types, expressions, and top-level/exported Java `axiom` / `impure` targets.
   * Type system & analysis (`EffectSet`, `Type.Parameter(..., mut, noescape, unique)`, `Type.Function`, `BlockScopes`, `TypeChecks`, `TypeChecker`, `Types`): verify `pure`, `mut` parameter/`this` + call-site `noalias` exclusivity, `noescape` checks, `unique`/`move`/`freeze` ownership + definite-unassignment, `deterministic` (outer mutable & `axiom` non-deterministic checks), conservative `nothrow`, default `pure deterministic` on `value class` and standard value protocol (`equals`, `hashCode`, `toString`, `compareTo`), interface conformance (`actual <: required`), and covariance/contravariance subtyping.
   * Runtime & Compiler (`Callable`, `ScriptRuntime`, `JavaClass`, `JavaMethod`, `BabbleScriptEngine`, `Faces`, `Operators`, `Interpreter`, `CodeGenerator`, `Bootstrap`): isolated per-runtime `EffectSet` registries, composable Java annotations (`@Pure`, `@Mut`, `@Noescape`, `@Unique`, `@Deterministic`, ...), `Faces` proxy checks, `freeze` support, and `Bootstrap` linkage enforcement.
2. **Phase 2 — Fixed-Point Inference & Core Optimizations:**
   * Implement SCC bottom-up fixed-point inference in `TypeChecks` for `{pure, mut, noescape, unique, deterministic, nothrow}`, derived `threadsafe`, implicit last-use `move`, and compositional `monotone` / `injective`.
   * Expose `EffectSet`, `noescape`, and `unique` metadata on IR/bytecode and wire compiler optimization passes: CSE / constant folding (`pure deterministic`), `noalias` hoisting & SIMD (`mut` exclusivity), scalar replacement/unboxing (`noescape`), zero-copy `freeze` / FBIP reuse (`unique`), predicate reordering & hoisting (`pure deterministic nothrow`), loop reassociation (`associative`), and parallel collection combinators (`parallelMap`, `parallelReduce`, `parallelForEach`).
3. **Phase 3 — Algebraic Rewrite Rules, ASM Bytecode Suggester & Axiom Fuzzing:**
   * Add `monotone` range inversion and `injective` projection push-down in pipeline optimizers.
   * Add opt-in ASM bytecode analyzer (`ClassLoader` + escape analysis + seeded JVM `native` intrinsics) to suggest/auto-derive `pure`/`mut`/`deterministic`/`nothrow` on closed Java helpers.
   * Add property-based test harness (`checkAxioms`) to fuzz-test `axiom commutative`, `associative`, `idempotent`, and `deterministic` declarations in CI.
