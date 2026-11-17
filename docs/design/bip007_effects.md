# BIP007: Function Effects and Algebraic Properties in Babble

**BIP:** 007  
**Date:** 2026-11-14  
**Status:** Draft (High-Level Design)  
**Depends on:** [bip008_static-typing.md](bip008_static-typing.md) (`BIP008-P1`–`BIP008-P7`: static typing, `any`/`any?`, `T?`, `A | B`, `as`, generics, call-site parameter inference)  
**Scope:** Static type system, effect inference & verification engine, `axiom` declarations, optimizer contract, Java interop, and runtime/bytecode compiler integration.

---

## Executive Summary

Babble captures **function effects and algebraic properties** directly in its static type system and parse-tree analysis (`ScopeAnalysis`) so the compiler can both **secure computation** (preventing unintended state mutation, data races, and ambient I/O leaks) and **optimize computation** (reordering, common subexpression elimination (CSE) / memoization, parallel execution, zero-copy thread sharing, predicate push-down/push-up, tree reductions, and safe automatic retries).

Rather than creating parallel "trusted" vs "checked" keyword hierarchies (e.g., `pure` vs `trusted_pure`), Babble uses **one composable property vocabulary** paired with **three derivation modes**:

1. **Inferred** — unannotated Babble functions automatically receive the strongest properties provable by whole-module fixed-point analysis.
2. **Verified** — explicitly annotated functions (e.g., `pure deterministic`) are statically proved by the compiler against their body and callees.
3. **Axiomatic (`axiom`)** — functions or Java targets prefixed with `axiom` (e.g., `axiom pure deterministic`, `axiom associative commutative`) assert properties that the compiler accepts as trusted contracts without inspecting/proving an implementation.

---

## 1. Context & Objectives

### 1.1 Problem Statement

A single binary `pure` / `impure` distinction is too coarse for modern compilers, query/pipeline optimizers, and concurrent runtimes:

* A time-reading function (`Instant.now()`) mutates no memory yet cannot be memoized or cached (`pure`, **not** `deterministic`).
* A builder helper mutates a caller-passed buffer without doing any global I/O (effects strictly scoped to its `mut` parameter, not `pure`!).
* Parsing an integer (`Integer.parseInt(s)`), asserting a precondition (`assert(x > 0)`), or dereferencing a nullable receiver (`a.x`) can be `pure deterministic` yet cannot be speculatively hoisted ahead of a guard (`nothrow` is false; note that Babble's `a / b` is IEEE-754 `double` division and never throws).
* A parallel accumulator (`ConcurrentHashMap.newKeySet().add(x)`) mutates shared state, yet is safe across threads, order-independent, and retry-safe (`mut idempotent commutative threadsafe`).
* String concatenation (`a + b`) is `associative` (parallel tree-reducible) without being `commutative`, whereas integer addition is both.

To unlock real compiler transformations without turning code into unreadable annotation noise, Babble needs a **structured, composable effect and algebraic property model** that maximizes static **inference** while offering **`axiom`** as an explicit escape hatch where static proof is impossible or external.

### 1.2 Objectives: What Effects Unlock

Every property in the model is justified by concrete security guarantees or compiler/runtime optimizations:

| Compiler / Runtime Capability | Required Properties (Contract) | Example Transformation |
|---|---|---|
| **Common Subexpression Elimination (CSE), Memoization & Constant Folding** | `pure deterministic` + deeply immutable result; memoization and folding also need deeply immutable arguments (§5.3.1) | Cache `f(x)` across repeated calls; fold `f(const)` at compile time. |
| **Loop-Invariant Code Motion (LICM)** | `pure deterministic` (+ `nothrow` and `@TERMINATING` whenever the loop may execute zero times or the call is hoisted across a guard) | Hoist `normalize(cfg)` out of a loop body. |
| **`@NOALIAS` Local/Field Load Hoisting & Redundant-Load Elimination** | `unique mut` parameter (exclusively owned graph, §3.2) | Keep `src` fields in JVM locals and hoist invariant `src` reads across `unique mut dst` updates without alias checks. |
| **Expression & Filter Reordering** | `pure` (+ `nothrow` for guarded/short-circuit swaps) | Reorder `xs.filter(p).filter(q)` by estimated cost/selectivity. |
| **Predicate Push-Down & Push-Up** | `pure deterministic nothrow` (+ `monotone` / `injective` for inversion/projection pushdown) | Push `filter(p)` ahead of `map(f)` (`xs.filter(x -> p(f(x))).map(f)` when `p(f(x))` simplifies via `monotone`/`injective` or `f` is cheap/CSE'd), invert `monotone` bounds, push equality past `injective` projections, factor common filters above `union`. |
| **Parallel `map` / `filter`** | `pure threadsafe` (automatically implied by `pure deterministic` on immutable/`freeze` inputs) | Evaluate elements independently across worker threads without locks. |
| **Zero-Copy Builder $\to$ Parallel Read** | `unique` return + `freeze` | Build a large mutable graph/index locally, then `freeze` zero-copy into deeply immutable `threadsafe` data. |
| **Zero-Copy Thread Handoff & In-Place Reuse** | `unique` + `move` (single live owner) | Transfer mutable buffers between threads lock-free, or lower `sort(move xs)` into in-place mutation (FBIP) with zero I/O/globals. |
| **Stack Unboxing / Scalar Replacement** | `noescape` parameter (with `value` / fresh arg) | Pass `Point(x, y)` or closure captures directly in registers/locals without heap allocation. |
| **Parallel `reduce` (Tree Fold)** | `pure deterministic associative` (+ `commutative` if partition order unconstrained) | Split reduction into balanced parallel subtrees (`reduce(combine)`). |
| **Parallel Accumulation (`forEach`)** | `mut` (on target) + `commutative threadsafe` (+ `idempotent` for retry) | Concurrent unordered updates (`AtomicLong.add`, CRDT set union). |
| **Concurrent Data Sharing** | Deeply immutable data (`frozen T`, deep `value class`, primitives) or `threadsafe` targets | Share large immutable structures across threads with zero copying and zero locking. |
| **Safe Automatic Retry** | `idempotent` (or `pure` for read/compute steps) | Re-run failed steps (`Files.createDirectories`, `set.add`, `upsert`) without duplicating effects. |
| **Dead-Code Elimination & Speculation** | `pure nothrow` (+ `@TERMINATING`) | Drop unused results; speculatively evaluate before branches. |

### 1.3 Non-Goals

* **Full theorem proving / SMT verification of arbitrary equations:** The compiler does not attempt to prove algebraically from raw ASTs that arbitrary user arithmetic satisfies $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ or $f(a, b) = f(b, a)$; those rely on **`axiom`** (with optional property-based test verification).
* **Mandatory effect annotations everywhere:** Unannotated Babble scripts must continue to work seamlessly; effect inference (alongside BIP008's local and call-site type inference) avoids ceremony on ordinary scripting code.
* **Rust lifetime annotations (`'a`) or Pony's 6-way reference qualifiers on every variable:** Babble captures `@NOALIAS` exclusivity, `noescape`, `unique`/`move`, and `freeze` through parameter/return modes, definite-unassignment, and intra-procedural escape analysis rather than lifetime generics or pervasive viewpoint adaptation. The single `frozen` type qualifier (§3.2) is the only reference capability that appears in types.

---

## 2. State of the Art & Prior Art

Babble synthesizes ideas from four distinct communities: typed functional/effect languages, compiler/static-analysis annotations, database query optimizers, and distributed monotonic systems.

| Community / System | Key Concepts & Annotations | Strengths (`+`) & Pitfalls (`-`) for Babble |
|---|---|---|
| **Haskell / Clean** | Monadic `IO` / `ST` vs uniqueness types | `+` Sound referential transparency.<br>`-` "Function color" split makes refactoring local mutable helpers painful (`ST` monad ceremony). |
| **D** | Strong vs weak `pure`, `@safe`, `nothrow`, `@nogc` | `+` Separating "no global state/I/O" from parameter mutation enables local imperative helpers.<br>`-` Overloading the word `pure` onto mutating functions ("weak purity") confuses users, and **"attribute soup"** (`pure @safe nothrow @nogc const`) overwhelms readers without inference. |
| **Koka / Flix / Eff** | Algebraic effect rows (`<exn, div, console>`) + inference | `+` Effect inference avoids boilerplate.<br>`-` Higher-order effect polymorphism adds steep type-signature complexity for a JVM scripting/compiled language. |
| **Rust / Pony** | `&T` / `&mut T`, `Send` / `Sync`, `unsafe`; Pony `val`/`iso`/`box`/`ref` | `+` Distinguishing shared read (`&T` / `box`) vs exclusive mutation (`&mut T`) + immutable data (`val`) makes thread sharing safe.<br>`+` Explicit `unsafe` boundary concentrates audits. |
| **Google C++ / Abseil** | *Thread-Safe* vs *Thread-Compatible* vs *Thread-Unsafe* | `+` Explicitly separates race-free under concurrent writes (*thread-safe*) from race-free under concurrent `const` reads (*thread-compatible*, most standard containers) vs racy hidden `mutable` caches (*thread-unsafe*). |
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
7. **Distinguish Thread-Safe (`threadsafe`) vs. `@THREAD_COMPATIBLE` vs. `@THREAD_UNSAFE` (Google C++ model):** Verified Babble `class`es forbid C++ `mutable`-style backdoors in non-`mut` `pure` methods, making every verified `class` **`@THREAD_COMPATIBLE`** by construction; meanwhile, `axiom pure deterministic` without `threadsafe` may hide unsynchronized lazy caches (**`@THREAD_UNSAFE`**) and must never be auto-promoted to `threadsafe`.

---

## 3. Proposed Model for Babble

> **Notation:** Surface modifiers and keywords written by Babble programmers appear in lowercase (`pure`, `mut`, `noescape`, `unique`, `move`, `freeze`, `frozen`, `threadsafe`, ...). Internal compiler states, inferred-only flags, and guarantees not directly manipulable by users use an **`@UPPERCASE` prefix** (`@FRESH`, `@UNIQUE`, `@NOALIAS`, `@NO_GLOBAL_EFFECTS`, `@PARAM(i)`, `@THIS`, `@FROZEN`, `@MOVED`, `@EXTERNAL`, `@TERMINATING`, `@TOTAL`, `@THREAD_COMPATIBLE`, `@THREAD_UNSAFE`, `@AXIOMATIC`). Java interop annotations (§6.2) use TitleCase (`@Pure`, `@Mut`, ...).

### 3.1 Architecture Overview: Infer, Verify, or `axiom`

Every callable in Babble (function literal, method, constructor, or imported Java method/class) has an **Effect Profile** (`EffectSet` + per-parameter/receiver `mut` flags), derived in one of three modes:

1. **Inferred Mode (no explicit effect modifiers, and not marked `impure`):**
   * The compiler analyzes the function body and call graph via fixed-point iteration and infers the **maximal sound subset** of `{pure, deterministic, nothrow, threadsafe}`, the **minimal `mut` parameter/receiver set**, and compositional relational properties (`monotone`, `injective`).
   * An explicitly marked **`impure`** function suppresses inference and locks the signature to unconstrained (used to prevent callers from depending on accidental purity, or to opt out default-pure value methods like `URL.equals`).
2. **Verified Mode (explicit effect modifiers *without* `axiom`, e.g., `pure deterministic (x: int) -> (...)`):**
   * Declared modifiers become **compile-time obligations**: the compiler verifies that the body, all expressions, and all callees satisfy every declared property and mutates **only** declared `mut` parameters / `mut` `this` / `@FRESH` local objects.
   * Any violation is a compile-time type/effect error (`TypeCheckException`) pointing to the offending expression.
   * Meanwhile, *additional* unmentioned properties (like `nothrow` or `@TERMINATING`) can still be inferred quietly for intra-module optimization of this function body, while callers' own verification checks rely strictly on the explicitly declared modifiers so callers never depend on accidental implementation properties.
3. **Axiomatic Mode (`axiom <modifiers> ...`, e.g., `axiom pure deterministic`, `axiom associative commutative`):**
   * Tells the compiler to **assume** the listed properties for this function or Java target without verifying the implementation.
   * All `axiom` declarations are tagged with an auditable metadata flag (`@AXIOMATIC`) so security reviewers, linters, or strict sandbox policies can inspect every trust boundary—analogous to auditing `unsafe` in Rust.
   * **Host sandbox policy (`engine.allowAxioms(false)`):** Embedding hosts can disable in-script `axiom` and `impure` declarations altogether via `BabbleScriptEngine.allowAxioms(false)` (§6.2), causing `TypeChecker` to reject any script-level `axiom` with a `TypeCheckException` so untrusted scripts may only use effects statically proved by the compiler or pre-registered by the host.

```
Function declaration / Java target
   ├── No modifiers          ──▶ INFER (fixed-point over body & call graph)
   ├── Explicit modifiers    ──▶ VERIFY body satisfies declared contract (+ infer extras)
   ├── `axiom` + modifiers   ──▶ ASSUME contract as ground truth (audited trust boundary)
   └── `impure`              ──▶ LOCK unconstrained (suppress promotion; opt out defaults)
```

### 3.2 Effect & Property Taxonomy

#### Axis 1 — Memory, Aliasing, and Ownership Effects
* **`pure` (Strictly effect-free — no weak purity):**
  * Performs **no output or mutating I/O** (`print`, socket/file writes, mutating FFI). Read-only environment queries (`axiom pure Files.readString`, `axiom pure Instant.now`) and reads of mutable outer state perform no writes and may be `pure`, but are **not** `deterministic`.
  * Mutates **no global/outer variables, static fields, or external objects**.
  * Mutates **no parameters and not `this`**: **`pure` cannot be combined with `mut`** on parameters or `this` (`pure (mut x)` and `pure mut` are rejected).
  * *Fresh local builders:* Inside a `pure` function, mutating **newly allocated `@FRESH` local objects** (`def tmp = Counter(0); tmp.add(1)`) *is* allowed whenever the called `mut` method/helper is inferred (or axiomatized via `axiom mut`) to have **no global/I/O side effects** (tracked internally as `@NO_GLOBAL_EFFECTS`, without calling mutating functions "`pure`" in surface syntax).
* **`mut` + Call-Site Exclusivity (`@NOALIAS`):**
  * Written on a parameter (`(mut target: List, item: string)`) or on the function itself (`mut` before parameters) to indicate **`this` may be mutated**. Mutually exclusive with `pure`.
  * Absent `mut`, a parameter or `this` is **read-only borrowed** across all typed functions (whether annotated or unannotated): field assignments (`p.x = ...`), calling `mut` methods on `p`, and passing `p` as a `mut` argument are forbidden unless `mut` is declared on `p` (or inferred on an unannotated function's parameter).
  * **Law of Exclusivity (two strengths):**
    * *Plain `mut` (shallow, a bug catcher):* whenever an argument (or receiver) is passed to a `mut` slot, **no other argument or `this` at the same call site may be the same object** (checked statically for distinct variables/paths, with a runtime `same` check where dynamic). This is an identity check only. Two distinct objects can still share mutable state (`Vec(data)` and `Vec(data)` over one list), and the callee can still reach the object through a captured variable or a global. **Plain `mut` therefore grants permission to mutate and nothing else: neither the callee nor the caller may assume `@NOALIAS` from it.**
    * *`unique mut` (deep, the optimizer's `@NOALIAS`):* a `unique` parameter receives a graph that nothing else can reach (see `unique` below), so it is disjoint from every other argument, from `this`, from captured variables and from globals **by construction**, with no alias check at all. All `@NOALIAS` transformations (§5.3.2) key on `unique mut`, never on plain `mut`.
    * *Caller side:* after a call that passes `x` to a plain `mut` slot (or any unconstrained call), the caller must drop every cached fact about objects that are not provably disjoint from `x`. Provably disjoint means: `@UNIQUE` locals not passed to the call, and deeply immutable values.
  * *Confined `mut` inference (`@NO_GLOBAL_EFFECTS`):* Fixed-point inference tracks whether a `mut` Babble function avoids globals and I/O (`@NO_GLOBAL_EFFECTS`), and **`axiom mut`** on Java targets (`axiom mut ArrayList.add`) likewise declares mutations confined strictly to `mut` targets with zero global/I/O effects—allowing `pure` callers to invoke them on `@FRESH` local objects.
* **`noescape` (Frame-local parameter borrowing):**
  * Written (or automatically inferred) on a parameter `(noescape p: Point)` or receiver: guarantees the function never stores `p` (or mutable references reachable from `p`) into `this`, another parameter, a global, a captured closure, or its return value.
  * Unlocks **scalar replacement / stack unboxing** of wrapper objects (`Point`, temporary buffers, lambda captures) across call boundaries.
* **`unique`, `move`, and `freeze` (Single-owner graph ownership & zero-copy transition):**
  * **`unique`** on a parameter `(unique mut buf: Buffer)` or return type `(...) : unique Index` asserts **deep ownership**: every *mutable* object reachable from the reference is reachable **only** through it (Pony `iso` / Rust owned value). Deeply immutable values (primitives, strings, `null`, `frozen T`, deep `value class`es) may be shared freely inside a unique graph, since sharing them is unobservable.
    * *Keeping a graph unique:* a `@UNIQUE` object stays unique only while every reference stored into it is itself `@UNIQUE` (moved in) or deeply immutable. Storing a parameter, an outer variable, an `any`/`any?` value, or anything else demotes it to merely `@FRESH`: still new, still mutable without breaking `pure`, but no longer movable, freezable or returnable as `unique` (§5.2). Arguments passed to a Java method are assumed stored in the receiver unless the method's axiom marks that parameter `noescape`.
    * *Consume vs. borrow:* a `unique` parameter **consumes** its argument (the callee may store it). A `unique noescape` parameter is an **exclusive borrow** (Rust `&mut`): the callee may not store it, so the caller's binding is usable again, and still `@UNIQUE`, after the call.
  * **`move x`** explicitly transfers a `@UNIQUE` variable to a `unique` consumer (`channel.send(move buf)`, `sortInPlace(move list)`), triggering **definite-unassignment** on `x` in the caller (cannot be used after moving; omitted automatically on a binding's provable last use).
    * *Locals only:* ownership is tracked only for **function-local bindings that no closure captures**. Top-level, package, class-static and captured bindings are namespace or cell variables that other functions, closures and later REPL inputs can reach; they are always `@EXTERNAL`, never `@UNIQUE`, and `move` on one is a compile-time error. A temporary that is never bound (`freeze(buildIndex(1000))`) is consumed directly and needs no `move`.
  * **`freeze(expr)`** consumes a `@UNIQUE` mutable graph and produces a **deeply immutable (`threadsafe`) value of type `frozen T`** as a **purely static type transition (a zero-cost identity no-op at runtime)**—allowing imperatively built indexes, graphs, and maps to immediately enter parallel pipelines.
    * *Why it is deep:* the argument must be `@UNIQUE`, and a unique graph contains only owned objects and deeply immutable values. Nothing mutable is shared with the caller, so nothing outside can observe or change the frozen graph. A merely `@FRESH` graph (one that absorbed a parameter or other shared object) is rejected.
    * *The `frozen T` type:* frozen-ness is part of the type, so it survives fields, calls, returns and top-level `def`s (`def lookup : frozen HashMap(string, int) = freeze(...)`; inferred for never-reassigned unannotated `def`s like any other type). "Deeply immutable" means: primitives, strings, `null`, `frozen T`, `value class`es all of whose fields have deeply immutable declared types, and unions/nullables (`A | B`, `T?`) all of whose members are deeply immutable. `frozen` distributes over unions (`frozen (A | B)` = `frozen A | frozen B`, so `frozen (T?)` simplifies to `(frozen T)?`).
    * *Purely static enforcement (no `any` erasure, zero runtime machinery):* because static type checking is mandatory (BIP008), `TypeChecker` enforces immutability without any runtime graph walk, `BabbleInstance` frozen bit, or `JavaObject` wrapper:
      * `frozen T` is **not assignable to `any`, `any?`, or plain `T`** (and `e as T` / `e as any` cannot unwrap a `frozen` type), and **`any` / `any?` is not assignable to `frozen T`**. Any parameter, field, or reassigned `def` holding a frozen value must be typed `frozen T` (so `frozen` can never be erased into `any` or mutated via narrowing).
      * On a `frozen T` receiver, field writes and `mut` method calls are rejected; non-`mut` method calls are allowed only when `this` is `noescape` (or `axiom pure` on Java methods, so `this` cannot escape as unfrozen `T`), and passing `frozen T` to a Java parameter requires that parameter to be known non-`mut` and `noescape`.
      * Reading a field or calling a method on `frozen T` with concrete static type `U` yields `frozen U` (or `U` if `U` is already deeply immutable; nullable `U?` yields `(frozen U)?`). If a Java method on `frozen T` returns `java.lang.Object` (`any` / `any?`), `TypeChecker` types the result as `frozen any` / `frozen any?`, which can only be narrowed (`is`) or cast (`as`) to a deeply immutable concrete type (`int`, `double`, `bool`, `string`, or a deep `value class`) where `Types.check` verifies the runtime value.
* **`threadsafe` (Concurrent execution safety — Google C++ 3-tier alignment):**
  * Means **no data races occur** when invoked simultaneously from multiple threads on shared state (not scheduling determinism—use `commutative` for order independence):
    1. **Thread-Safe (`threadsafe`):** Safe under arbitrary concurrent calls—even alongside concurrent writes on `mut threadsafe` targets (`AtomicInteger`, `ConcurrentHashMap`), or across stateless/immutable `pure deterministic threadsafe` functions.
       * **Derived automatically *only* for verified/inferred code** that is `pure deterministic`, captures/accepts deeply immutable data (`value class`, `freeze` result, primitives), and calls **only** `threadsafe` callees (or confines `mut` helpers strictly to `@FRESH` locals).
       * **Never auto-derived from `axiom`s omitting `threadsafe`:** An `axiom pure deterministic` function may conceal an unsynchronized lazy memoization cache (**`@THREAD_UNSAFE`**) and therefore is **not** `threadsafe` unless explicitly tagged `axiom ... threadsafe`.
    2. **`@THREAD_COMPATIBLE` (default for verified Babble `class`es & read-only containers):** Verified non-`mut` `pure` methods cannot mutate `this` secretly (unlike C++ `const` + `mutable`), so concurrent non-`mut` reads on the same instance are race-free *provided* no thread holds a concurrent `mut` writer. The compiler only accepts that proviso when it is proved: the data is `frozen`, or it is `@UNIQUE` in the caller and exclusively borrowed by a parallel combinator for its whole duration (so no writer can exist). Merely `@THREAD_COMPATIBLE` data that is neither is **not** shareable across threads; `mut` calls still require exclusive ownership (`@UNIQUE`) or external synchronization.
    3. **`@THREAD_UNSAFE`:** `axiom` functions lacking `threadsafe` (e.g., unsynchronized caches, `SimpleDateFormat`) or `impure` globals; confined to a single thread unless externally locked.

#### Axis 2 — Evaluation & Safety Guarantees
* **`deterministic` (Functional dependence on visible inputs):**
  * The result (and the post-states of `mut` arguments/`this`) is a function of the **observable state of the graphs reachable from the arguments, `this` and captured variables at the time of the call**, and of nothing else. Object *identity* is not an input: two calls on equal states give equal results, and a call returning a new object returns a structurally equal, **distinct** object each time.
  * Consequently `deterministic` alone does not license reuse of a result. Reuse needs the *state* to be the same and the *result* to be shareable: see the conditions in §5.3.1 (deeply immutable arguments for memoization and folding, no intervening write for CSE, deeply immutable result in all cases).
  * Identity-derived values (`System.identityHashCode`, the inherited `Object.hashCode`/`Object.toString`, the default `hashCode` of a non-value Babble instance) differ between runs: reading one is `pure` but **not** `deterministic`.
  * Forbids calling non-`deterministic` functions (`System.nanoTime()`, `Math.random()`), reading non-`final` Java statics, reading reassigned/mutable outer bindings, or depending on unordered hash iteration.
  * *Why separate from `pure`?* A function reading an external config file (`axiom pure Files.readString`) or clock (`axiom pure Instant.now`) mutates nothing (`pure`), yet is **not** `deterministic` across time—so optimizers may reorder unused calls or discard them (if `nothrow`), **but must not memoize or CSE them across time**.
* **`nothrow` (Exception-freedom):**
  * Guaranteed never to throw a runtime error or exception (`assert` / `suppose`, failing `as` cast, Java boundary check failure such as `JavaTypes.checkBoxed` / generic `TypeVariable` return, or throwing Java callee; note that Babble has no `throw` statement, `a / b` is non-throwing IEEE-754 `double` division, and non-nullable `T` prevents null dereference on `a.x` while `a?.x` short-circuits on `null`).
  * Essential for **speculative evaluation**, **hoisting out of guarded branches** (`if a != null then f(a)`), and **reordering short-circuiting predicates**.
  * With BIP008 (`BIP008-P5`), Babble-to-Babble calls have no runtime entry `Types.check`, so calling a `nothrow` Babble function is unconditionally `nothrow`.
* **`@TERMINATING` *(`BIP007-P2` / optimizer flag, or combined as `@TOTAL` = `nothrow` + `@TERMINATING`)*:**
  * Guaranteed to finish in finite steps (no unbounded `while` loops or unguarded recursion). Required only when dropping dead calls whose infinite loop would otherwise prevent termination. Inferred in `TypeChecker` for functions whose body contains no `while` loops and no recursive calls (`recurse` or same-SCC callees) and calls only `@TERMINATING` callees.

#### Axis 3 — Algebraic & Relational Properties
* **`idempotent`:**
  * Has two distinct forms distinguished by the callable's signature (*Signature applicability rules* below):
    * **Value projection form** (`pure` unary endofunction `(T) : T`): $f(f(x)) = f(x)$ (e.g., `abs`, `String.trim`, canonicalizers). Enables $f(f(x)) \to f(x)$ elimination.
    * **Effect / retry form** (`mut` or effectful `axiom` function): executing the call twice consecutively with the same arguments produces the exact same observable **post-state** as calling it once, **ignoring the return value** (e.g., `Files.createDirectories`, `set.add`—which returns `true` then `false`—`latch.set()`, `upsert`). Enables **safe automatic retries** and duplicate suppression when the return value is unused.
  * *Important:* `idempotent` is orthogonal to `commutative` (`map.put(k, v)` is `idempotent` without being `commutative`; `counter.add(n)` is `commutative` without being `idempotent`).
* **`commutative`:**
  * **Value form** (`pure` binary `(T, T) : R` with two parameters of the same type `T`): reordering arguments yields the same result, $f(a, b) = f(b, a)$ (e.g., `int +`, `min`, `max`, `==`). Enables canonical operand ordering and argument-order-independent CSE.
  * **Effect form** (`mut` method on receiver `S` or function taking `mut target: S`): two calls on the same `mut` target with different arguments produce the exact same observable **post-state**, $s.f(a); s.f(b) \equiv_{\text{state}} s.f(b); s.f(a)$, **ignoring return values** (e.g., `AtomicInteger.addAndGet(k)`, `ConcurrentSkipListSet.add(k)`). Together with `threadsafe` (and `idempotent` for retries), enables **lock-free unordered parallel accumulation**.
* **`associative`** *(binary combiners `(T, T) : T`)*:
  * $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ (e.g., integer `+`, `*`, `min`, `max`, string `+`, list concat). Enables **parallel tree reductions (`parallelReduce`)**, loop reassociation, and streaming partition merges.
  * Note: Adding an optional identity/monoid tag (e.g., `identity(0)` in future extensions) lets `parallelReduce` run without a caller-supplied seed.
* **`monotone`** *(order-preserving unary or first-arg mapping)*:
  * $x \le y \implies f(x) \le f(y)$ over the whole declared domain (e.g., `double` `x + k`, `double` `x * k` for `k >= 0`, monotonic timestamp/ID buckets). **Wrapping `int` arithmetic is not monotone** (`x + 1` wraps at the maximum), so `int` `x + k` and `x * k` never get the property; see §4.6. Enables **zone-map / min-max range pruning** and pushing range predicates ($f(x) > c$) down into storage scans.
* **`injective`:**
  * $x \ne y \implies f(x) \ne f(y)$ (one-to-one mapping, e.g., bijective encodings, lossless wrappers `UserId(id)`, wrapping `int` `x + k`, wrapping `int` `x * k` for **odd** `k` only). Enables pushing **`distinct`**, **`groupBy`**, and **`f(x) == f(y)`** through projections directly onto underlying keys.
* **Signature applicability rules (`TypeChecker`):** Every algebraic modifier is validated against the declaration's parameter and return types: `associative` requires a `pure` binary `(T, T) : T` signature; value `commutative` requires a `pure` binary `(T, T) : R` signature with identical parameter types; effect `commutative` requires `mut` on `this` or a parameter; value `idempotent` requires a `pure` unary `(T) : T` signature while effect `idempotent` requires `mut` or an effectful `axiom`; `monotone` requires ordered first-parameter and return types (`int`, `double`, `bool`, `string`, or Java `Comparable`); and `injective` requires at least one parameter and a return type other than `null`. Any mismatch is a compile-time `TypeCheckException`.

#### Soundness premise — static typing and external boundaries

Every profile, inferred or verified, is derived assuming the arguments conform to their declared or inferred parameter types. Under BIP008, that assumption is enforced statically throughout Babble (`TypeChecker`, `BIP008-P5`), with runtime `Types.check` remaining only at `as` casts and Java boundaries:

* **Typed parameters are statically guaranteed:** Every parameter has a static type (annotated, contextual, default, or call-site inferred via `BIP008-P7`), and `TypeChecker` rejects mismatches before execution. Inside `(x: int, y: int) -> ( x + y )`, `x + y` is guaranteed to be integer addition with no runtime entry check.
* **Java boundaries remain checked:** Calls entering Babble from Java interfaces (`Faces` via `JavaTypes.checkParameters`) and boxed/generic values exiting Java (`JavaTypes.checkBoxed`, generic `TypeVariable`/`WildcardType` returns and fields) are checked at runtime, so un-axiomatized Java calls and `as` casts drop `nothrow`.

### 3.3 Exact Verification vs. `axiom` Matrix

Because static checking faces fundamental computability bounds (Rice's theorem), Babble draws an explicit line between what the compiler **infers/verifies** and what requires **`axiom`**:

| Property | Inferred automatically? | Verified on body (without `axiom`)? | Requires `axiom` for non-trivial cases? |
|---|---|---|---|
| **`pure`** | **Yes** (fixed-point call graph; rejects `mut`) | **Yes** (sound; requires zero `mut` targets) | Only for Java/FFI calls or benign hidden state (caches) |
| **`mut`** (params / `this`) + exclusivity | **Yes** (minimal mutated set + shallow call-site exclusivity + internal `@NO_GLOBAL_EFFECTS`; deep `@NOALIAS` only via `unique mut`) | **Yes** (sound; mutually exclusive with `pure`) | Only for Java methods (`axiom mut ArrayList.add` asserts `@NO_GLOBAL_EFFECTS`) |
| **`noescape`** (params / `this`) | **Yes** (intra/inter-procedural escape analysis) | **Yes** (sound) | Only for Java methods (`axiom pure noescape length(...)`) |
| **`unique`** (return & moved params) | **Yes** for `@UNIQUE` local builders & returns (deep ownership, §5.2) | **Yes** (definite-unassignment + no shared mutable object stored into the graph) | Needed when Java methods allocate/return isolated graphs (`axiom pure unique parse(...)`) |
| **`deterministic`** | **Yes** (fixed-point; forbids clocks/RNG/mutable globals) | **Yes** (sound) | Only for benign lazy memoization (`String.hashCode`) or Java |
| **`threadsafe`** | **Derived** for verified `pure deterministic` on `value`/`freeze` data | **Derived** (or rejects concurrent mutable sharing) | Required for lock-free / concurrent mutating Java (`ConcurrentHashMap`, `AtomicInteger`) |
| **`nothrow`** | **Yes** (conservative: no `assert`/`suppose`, failing `as` casts, or throwing Java calls) | **Yes** (conservative) | Needed when invariants prevent failure (e.g. bounds-checked Java indexing `list.get(i)` or validated `Integer.parseInt`) |
| **`idempotent`** | Trivial forwarding only | Trivial forwarding / invariant overwrite only | **Yes** (`axiom pure deterministic idempotent abs`, `axiom mut idempotent set.add`) |
| **`commutative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... commutative`) |
| **`associative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... associative`) |
| **`monotone` / `injective`** | **Compositional** ($f \circ g$ preserves `monotone` and `injective`) | **Compositional** over primitive axioms | Base arithmetic/conversions need `axiom` (built-ins pre-seeded per §4.6: no `monotone` for wrapping `int` arithmetic) |

### 3.4 Subtyping and Effect Lattice

Each property $p$ defines a simple boolean partial order: having $p$ is strictly stronger (more constrained) than lacking $p$:

$$\text{has } p \quad <:\quad \text{may lack } p$$

For a function signature `(p1: T1, ..., mut noescape unique pk: Tk) : R` with effect set $E$:

* **Guarantee covariance (function-level effects, `noescape` params, and `unique` returns):**
  * `actual` satisfies `target` iff $E_{\text{target}} \subseteq E_{\text{actual}}$, `target.noescape(i) => actual.noescape(i)` (promising not to escape satisfies a caller allowing escape), and `target.uniqueReturn => actual.uniqueReturn` (returning a fresh unique object satisfies any non-unique return slot too: `unique R <: R`).
  * `pure deterministic nothrow (noescape x: Point) : unique Path` `<:` `pure deterministic (x: Point) : Path` `<:` `(x: Point) : Path`.
* **Mutability & Ownership contravariance-of-permission:**
  * Because `pure` forbids all `mut` parameters and `mut` `this`, `pure` is strictly disjoint from `mut` (`pure` signatures never carry `mut`).
  * Across **all** `Type.Function` signatures (whether or not function-level effect modifiers are present), absence of `mut` on a parameter (or `this`) means that parameter (or `this`) is **not mutated**: `actual` may declare `mut` on parameter $i$ (or `this`) only if `target` also declares `mut` on parameter $i$ (or `this`). Adding an orthogonal modifier like `nothrow` to `(x: List(int)) : int` therefore never changes the meaning of absent `mut`:
    * `pure deterministic (x: List(int)) : int` `<:` `deterministic (mut x: List(int)) : int`, **not** the reverse! (Promising no mutation at all satisfies a caller slot that permits mutating `x` without global effects.)
  * On parameters, demanding ownership (`unique p`) is *stricter* on the caller: a callee taking an ordinary borrowed `(p: T)` satisfies a slot expecting `(unique p: T)` (`pure (p: Vec) <: pure (unique p: Vec)`), **not** the reverse!
* **Unconstrained vs. Constrained Function Types:** An **unconstrained** function type `(x: T) : R` (empty effect set, no `mut`/`noescape`/`unique`) accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects, while callers receive no optimization or aliasing guarantees; mutating parameter $i$ still requires `(mut x: T) : R` uniformly across all `Type.Function`s. Under BIP008, `any` / `any?` is never assignable to any `Type.Function` (constrained or unconstrained), and `Type.Function` is non-nullable (`null` is only accepted by `((x: T) : R)?`). Inline lambdas and un-reassigned `def`s obtain their concrete `Type.Function` and `EffectSet` via bidirectional local type inference.

---

## 4. Syntax, Semantics, and Language Rules

### 4.1 Syntax in `Babble.g4`

Effects compose naturally in front of function literals, parameter declarations, function types, and external Java declarations:

```babble
;; Host axioms declared before use (within a file, TypeChecker collects axioms order-independently in a pre-pass):
axiom pure deterministic nothrow threadsafe java.lang.Math.sqrt
axiom pure deterministic java.util.HashMap
axiom mut deterministic java.util.HashMap.put

;; 1. Verified function literals (compiler proves all listed properties):
def hypot = pure deterministic (a: double, b: double) : double -> (
    java.lang.Math.sqrt(a * a + b * b)
)

;; 2. Parameter & receiver mutability (`mut` — never combined with `pure`), `noescape`, and `unique`:
def appendAll = deterministic (mut dst: java.util.ArrayList(string), noescape src: java.util.List(string)) -> (
    ... ;; `dst` is mutated; `src` is untouched; compiler infers @NO_GLOBAL_EFFECTS!
    ;; Plain `mut` is not @NOALIAS: `dst` and `src` may share state. Use `unique mut` for that (§8.5).
)

def buildIndex = pure deterministic (n: int) : unique java.util.HashMap(int, int) -> (
    def m = java.util.HashMap(int, int)()
    ... ;; calling `axiom mut deterministic` methods (`HashMap.put`) on fresh local `m` stays `pure deterministic`!
    ... ;; `m` stays @UNIQUE because only primitives (deeply immutable) are stored into it
    m
)

def sortInPlace = deterministic (unique mut xs: java.util.List(int)) : unique java.util.List(int) -> (
    ... ;; mutates owned `xs` in place (zero globals/I/O) and returns unique ownership (FBIP)
    xs
)

def Counter = class (def count: int = 0) (
    def peek = pure deterministic () : int -> ( this.count )
    def add  = mut deterministic (delta: int) -> ( this.count = this.count + delta )
)

;; 3. Unannotated functions (fully inferred by the compiler!):
def square = (x: int) : int -> ( x * x )   ;; inferred: pure deterministic nothrow threadsafe (`x` is statically `int`, §3.2)

;; 4. Axiomatic declarations (trusted contract, no proof checked on body/target):
def fastFib = axiom pure deterministic (n: int) : int -> ( ... )   ;; single-threaded cache (NOT threadsafe!)
axiom mut idempotent commutative threadsafe java.util.concurrent.ConcurrentSkipListSet.add

;; 5. Explicit opt-out (suppresses inference / opts out value defaults):
def debugLog = impure (msg: string) -> ( ... )
impure java.net.URL.equals

;; 6. Function types & ownership handoff (`move` / `freeze`):
def parallelMap = axiom pure threadsafe (items: java.util.List(int), fn: pure threadsafe (x: int) : int) : java.util.List(int) -> ( ... )
def parallelReduce = axiom pure threadsafe (items: java.util.List(int), combine: pure deterministic threadsafe associative (a: int, b: int) : int) : int -> ( ... )
def frozenIndex = freeze(buildIndex(1000))      ;; zero-copy conversion; type `frozen java.util.HashMap(int, int)`, `threadsafe`
def sortedCopy = (n: int) : unique java.util.List(int) -> (
    def myList = makeList(n)                    ;; @UNIQUE local (ownership is tracked for locals only)
    sortInPlace(move myList)                    ;; transfers ownership; `myList` unusable afterward
)
```

**Grammar additions (`Babble.g4`):**
* Contextual effect keywords: `axiom`, `pure`, `mut`, `noescape`, `unique`, `move`, `freeze`, `frozen`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `threadsafe`, `monotone`, `injective`, `impure`.
* `# simpleType`: `FROZEN? ID ('.' ID)* typeArguments?` (the `frozen T` type of §3.2, including parameterized types such as `frozen java.util.HashMap(int, int)`).
* `# functionLiteral`: `(AXIOM effectModifier+ | effectModifier*) parametersDeclaration (':' UNIQUE? type)? '->' NL* functionBlock=block`
* `# parameterDeclaration`: `PRIVATE? DEF? (MUT | NOESCAPE | UNIQUE)* ID (':' type)? ('=' NL* defaultValue=expression)?`
* `# functionType`: `effectModifier* parametersDeclaration (':' UNIQUE? type)?` (note: `axiom` and `impure` are invalid inside function types—`axiom` is a definition-site mechanism, never part of a caller-visible type!).
* `# effectDeclaration`: `EXPORT? AXIOM effectModifier+ expression` (plus `EXPORT? IMPURE expression` for opting out defaults).
* `# moveExpression`: `MOVE ID` (alongside built-in `freeze(expr)`).

> **Syntax order:** `axiom` (when present) appears first before one or more effect modifiers (requiring at least one modifier or `mut` parameter); `impure` stands alone and cannot combine with `axiom` or positive effects; **`pure` and `mut` (on receiver or parameters) cannot be combined**. Function-level `effectModifier`s (`pure`, `deterministic`, etc.) and parameter modifiers (`(mut | noescape | unique)*`) may each appear in arbitrary order and are normalized into an `EffectSet` and `Type.Parameter` flags (`unique noescape` is an exclusive borrow; plain `unique` consumes).
>
> **Grammar disambiguation:** All effect keywords remain valid contextual identifiers outside their syntactic positions so existing code continues to parse. In expression position, `effectModifier` tokens match `#functionLiteral` only when followed by `parametersDeclaration (':' UNIQUE? type)? '->'` (so `pure(x)` still parses as a function call, unambiguous in ANTLR4 ALL(*) with no newline before `(`); `#moveExpression` matches `MOVE ID` specifically (never binary subtraction); and in type position (`:` prefix), `#simpleType` starts with `FROZEN? ID` while `#functionType` starts with `effectModifier* '('`, leaving the two alternatives disjoint from `#nullType` (`NULL`) and `#parenType` (`'(' NL* type NL* ')'`). `freeze(expr)` is a regular built-in function call.

### 4.2 Local State, Fresh Objects, Exclusivity, Ownership, and Outer State

Inside any function whose checked or inferred effect includes **`pure`** (and across call sites checking `mut` / `unique` / `noescape`):

1. **Local variables (`def x = ...`) may be reassigned freely** inside the function (`def i = 0; i = i + 1`). Local stack state is invisible outside the call.
2. **Freshly constructed local objects may be mutated freely** (`def v = Counter(0); v.add(5); v.count = 10`):
   * A local `def` is **fresh-local (`@FRESH`)** if its initializer (and any reassignment) creates a new instance via a `pure` constructor, a `unique`-returning call, or an `object (...)` literal.
   * Mutating a fresh local does **not** require `mut` and does **not** break `pure` or `deterministic`. This covers the new object only: an object *read out of* a `@FRESH` (not `@UNIQUE`) local may be a parameter or outer object that was stored into it, so it is treated as `@EXTERNAL` and mutating it is a mutation of external state.
   * A fresh local is additionally **`@UNIQUE`** while everything stored into it is `@UNIQUE` or deeply immutable (§3.2). Only `@UNIQUE` locals can be moved, frozen or returned as `unique`; objects read out of a `@UNIQUE` local belong to its graph and may be mutated in place, but storing them anywhere else demotes the local to `@FRESH`.
3. **Parameters and `this` without `mut` are strictly unmutated:**
   * `this.x = ...`, `param.x = ...`, and calling any `mut` method `param.mutMethod()` are **rejected** unless `mut` is declared on `this` or `param`.
   * Aliasing a non-`mut` parameter (`def alias = param; alias.x = 1`) is rejected by local alias/freshness tracking.
4. **Call-Site `mut` Exclusivity (`@NOALIAS`):**
   * At any call passing an expression `arg` to a `mut` parameter (or calling a `mut` method `arg.m(...)`), `arg` cannot be the same object as any other argument (or `this`) passed to the same call.
   * Statically checked for distinct locals/disjoint object paths (and automatically satisfied by deeply immutable arguments); calls with dynamic aliasing fall back to compile-time rejection or a runtime `same(a, b)` exclusivity error (`Cannot alias mut argument at call site`).
   * This check is shallow and gives **no `@NOALIAS` guarantee** (§3.2). Deep disjointness comes only from `unique mut` parameters, which need no check: the argument must be a `@UNIQUE` local, which cannot also appear as another argument, be captured, or be reachable from a global.
5. **Ownership Transfer (`unique`, `move`, `freeze`) and `noescape`:**
   * Passing an argument to a `unique` parameter, passing it to `freeze(...)`, or returning a `unique` local consumes ownership via `move expr` (inserted implicitly when the compiler proves it is the variable's last use). Definite-unassignment analysis rejects any subsequent read or write to a moved variable. Passing it to a `unique noescape` parameter is an exclusive borrow instead: nothing is consumed.
   * Only function-local bindings that no closure captures can be `@UNIQUE` and moved. Top-level, package, static and captured bindings are `@EXTERNAL`; `move` on them is rejected (`Cannot move x : not a local variable`).
   * Inside a function declaring `(noescape p: T)`, assigning `p` (or any mutable sub-reference of `p`) to fields of `this`, another parameter, an outer/global variable, a returning closure, or returning `p` directly is rejected at compile time.
6. **Outer variables and global mutable singletons:**
   * **Writing** any outer `def`, mutable `object` singleton field, or `static` field is forbidden in `pure` (clears `@NO_GLOBAL_EFFECTS`), except when a frame-local (`noescape`) helper closure reassigns an unescaped local `def` or mutates a `@FRESH` local of its enclosing function `F` (§4.5).
   * **Reading** a reassigned outer `def`, non-`final` Java static field, or mutable outer singleton/field (even reached transitively through an outer `value class`) performs no observable write: it stays valid in **`pure`**, but is forbidden in **`deterministic`** (and therefore blocks auto-deriving `threadsafe`).
   * Reading immutable outer `def`s (never reassigned and deeply `value class` / `freeze` / primitive / `pure` function) is valid in both `pure` and `deterministic`.

### 4.3 `value class`es, `object` Singletons, `static` Blocks, and Standard Value Protocol

1. **`value class` defaults:**
   * A `value class` constructor is **verified `pure deterministic`** (its parameter defaults and top-level `classBlock` expressions must satisfy `pure deterministic`; fields are read-only at runtime in `BabbleInstance.setMember`).
   * Unannotated instance methods of a `value class` use **Inferred Mode** (§3.1)—they automatically infer `pure deterministic` (and `nothrow` / `threadsafe` where applicable) when their body avoids I/O and mutable globals, while unannotated methods that call `println` or performing Java I/O silently infer unconstrained rather than failing compilation, preserving backward compatibility for unannotated scripts (§1.3). Explicitly annotated methods on a `value class` are verified normally.
   * Note: Because `value class` instances may hold references to mutable objects passed in at construction time, transitivity checks inspect field types when deciding `threadsafe` (deeply immutable `value class` vs shallow wrapper). The same distinction applies to the built-in structural `hashCode`/`toString`: they hash and print the fields, so they are `deterministic` only for a deeply immutable `value class` (a field holding a non-value instance contributes an identity hash).
2. **Standard Value Protocol (`equals`, `hashCode`, `compareTo`, `toString`):**
   * Babble's `==` and `!=` (`Operators.equal`) use structural field equality on `value class`es, object identity on regular `BabbleInstance`s, and `Object.equals` on Java objects—never invoking a user-defined Babble `equals`, `hashCode`, or `compareTo` method. Ordering operators (`<`, `<=`, `>`, `>=`) operate on primitives, `string`, or Java `Comparable` instances of the same class. Only `toString` is user-definable on Babble classes (`string + x` invokes `BabbleInstance.toString()`, which delegates to a 0-arg `toString` method if defined).
   * Consequently, on **Java classes**, `equals` and `compareTo` **default to `pure deterministic`** (while Babble's built-in `value class` structural equality and `BabbleInstance` identity equality are intrinsically `pure deterministic nothrow`).
   * On Java `hashCode` and Java/Babble `toString`, defaults are **`pure deterministic` only where they do not derive from object identity**: on a Java class that overrides them (declaring class other than `java.lang.Object`), on a deeply immutable `value class`, and on a Babble class defining a verified/inferred `pure deterministic` `toString`. The inherited `Object.hashCode`/`Object.toString` and the default ones of a non-value `BabbleInstance` are identity-derived and differ between runs: they default to **`pure` only**, so nothing that reaches them is folded at compile time or memoized.
   * Explicit `impure java.net.URL.equals` / `impure` opt-out stays available when a Java override performs DNS or mutates external state.
   * For a `value class`, `TypeChecker` determines whether the built-in structural `toString()` / `hashCode()` is `pure deterministic` or `pure`-only by inspecting the declared field types statically (deeply immutable `value class` $\to$ `pure deterministic`; fields that may hold non-value instances $\to$ `pure` only; fields whose own `toString` may be unconstrained $\to$ unconstrained). No runtime effect propagation inside `BabbleInstance.toString()` is needed.
3. **Inline `object (...)` and `static` blocks:**
   * An `object (...)` expression or nested `class`/`interface` `static` block executed inside a `pure` or `deterministic` function is checked under the enclosing function's active constraints (cannot secretly print or mutate globals during class initialization).

### 4.4 Interfaces, Overrides, and Covariance

Interfaces can require arbitrary effect & algebraic contracts on members and default methods:

```babble
def Reducer = interface (
    def zero : pure deterministic nothrow () : int
    def combine : pure deterministic nothrow associative commutative (a: int, b: int) : int
    def mergeInto : mut commutative threadsafe (delta: int)
)
```

* Implementing or overriding an interface member must satisfy `actual <: required` across both `EffectSet` and `mut` permissions.
* Example: `pure deterministic` cannot satisfy an interface expecting `pure deterministic associative commutative`, whereas `axiom pure deterministic nothrow associative commutative` **does**.
* `pure deterministic ()` (non-`mut` `this`) CAN implement a `mut` slot `mut deterministic ()` (stronger guarantee satisfies weaker caller requirement), while `mut ()` CANNOT implement `pure ()`.

### 4.5 Nested Functions and Closures

Every function literal creates its own effect context (`inferred`, `verified`, or `axiom`):

* **Constructing a less-constrained closure inside a `pure deterministic` function** (e.g., returning an `impure` logger closure `() -> ( println(msg) )`) is `pure deterministic` itself (building the closure has no side effects and captures immutable `msg`), **provided** the inner closure does not capture a `mut` mutation of the enclosing function's non-`mut` parameters or `this`.
* **Calling** that closure inside the enclosing `pure deterministic` function requires its effect profile to satisfy the enclosing requirement (`actual <: enclosing`).
* **Frame-local (`noescape`) helper closures:** When a closure literal defined inside function `F` never escapes `F` (it is only invoked directly inside `F` or passed to `noescape` parameters within `F`), any reads or reassignments of `F`'s otherwise-uncaptured local `def`s and any mutations of `F`'s `@FRESH` locals performed by that helper closure are attributed to `F`'s own local frame rather than `@EXTERNAL` outer state—allowing `F` to use local helper closures and stay `pure deterministic`.
* **Higher-order helpers & call-site specialization:** Rather than introducing Koka-style effect-row variables into Babble's type signatures, small higher-order Babble helpers whose effects depend on a parameter call `f(...)` (e.g., `def apply = (f, x) -> ( f(x) )`) can be evaluated in `TypeChecker` via call-site specialization whenever the caller supplies a statically known `Type.Function` or contextual lambda.

### 4.6 Built-ins, Operators, and Numeric Semantics

| Built-in / Operator | Intrinsic Effect Profile | Notes |
|---|---|---|
| `same(a, b)`, `classof(v)`, `x is T` | `pure deterministic nothrow` | Pointer/class/type inspection only (`same` is also `commutative` and `noescape`; `x is T` also establishes flow-sensitive `Narrowing`). |
| `freeze(unique v)` | `pure deterministic nothrow threadsafe` | Consumes a `@UNIQUE` mutable graph and yields a `frozen T` (purely static type transition; identity no-op at runtime, §3.2). |
| `assert(cond, msg?)` / `suppose` | `pure deterministic` | **Not `nothrow`** (throws on false); arguments checked under enclosing mode. |
| `print(...)`, `println(...)`, `import(...)` | Unconstrained (`impure`) | I/O / module loading side effects (static `import("...ba")` still resolves `ModuleInterface` types at compile time). |
| `int +`, `int *` | `pure deterministic nothrow associative commutative` | Two's-complement wraparound is associative and commutative, but **not `monotone`** (overflow wraps). `x + k` is `injective`; `x * k` is `injective` only for odd `k`. |
| unary `-x`, binary `a - b` (`int`) | `pure deterministic nothrow` | Not `associative` or `commutative`; not `monotone` (wraps); unary `-x` and `x - k` are `injective`. |
| `double +`, `double *`, unary `-x`, binary `a - b` (and mixed `int`/`double`) | `pure deterministic nothrow` (`+`, `*` are `commutative`) | **Not `associative`** (IEEE-754 rounding). Mixed `int`/`double` promotes `int` cleanly (`int` to `double` conversion is exact, `monotone`, and `injective`). `x + k`, `x - k`, and `x * k` (`k >= 0`) are `monotone` (not `injective`). |
| `a / b` (on `int` or `double`) | `pure deterministic nothrow` | In `Operators.div`, division is always IEEE-754 `double` division (`1 / 0 == Infinity`), so it never throws. |
| `string +` (both operands `string`) | `pure deterministic nothrow associative` | **Not `commutative`**; not `injective` (`"ab" + "c" == "a" + "bc"`). |
| `string + non-string` / `non-string + string` | Inherits static `toString()` profile of non-string operand | `pure deterministic nothrow` for primitives/`string`/deep `value class`; `pure deterministic` for overridden Java `toString` or verified Babble `toString`; `pure` for non-value `BabbleInstance`/`Object.toString`; unconstrained for `any`/`any?` or unconstrained `toString`. |
| `==`, `!=` | `pure deterministic nothrow commutative` on primitives, `string`, `value class`es, and `BabbleInstance` identity; Java objects inherit `equals` profile (default `pure deterministic`) | `Operators.equal` uses structural equality on `value class`es and identity on regular `BabbleInstance`s (never calling a Babble `equals` method). |
| `<`, `<=`, `>`, `>=` | `pure deterministic nothrow` on `int`, `double`, `bool`, `string`; Java `Comparable` inherits `compareTo` profile (default `pure deterministic`) | Flip-duality (`a < b <=> b > a`) built into optimizer. |
| `not a`, short-circuit `a and b`, `a or b` | `pure deterministic nothrow` (when `bool` operands are `pure deterministic nothrow`) | `a and b` / `a or b` only evaluate `b` conditionally; `b` is checked under flow-sensitive `Narrowing` established by `a`. |
| Member selection `a.x` / `a?.x` | `pure deterministic nothrow` | Under BIP008, `a.x` is allowed only when `a` is statically non-nullable `T`, and `a?.x` handles `T?` by short-circuiting to `null` (and `a?.m(...)` inherits `m`'s effects). |

---

## 5. Inference Engine, Verification, and Optimizations

### 5.1 Fixed-Point Effect Inference (Call-Graph SCC in `TypeChecker`)

To minimize annotation burden, Babble maximizes inference across unannotated functions in the active compilation unit:

1. **Call-Graph Construction:** Build a static call graph over all functions, methods, constructors, closures, and `value class` bodies in `TypeChecker` (after `BlockScopes`, `TypeChecks`, and `Resolver`, alongside BIP008's bidirectional local type inference, call-site parameter inference `BIP008-P7`, generics instantiation, and flow-sensitive `is`/`!= null` `Narrowing`—where every expression `Type`, `ClassShape`, and static module `ModuleInterface` type is statically known).
2. **SCC Condensation (Bottom-Up Fixed Point):** Partition into Strongly Connected Components (handling direct and mutual recursion in `recurse` or mutually recursive functions).
3. **Optimistic Initialization (per unannotated node):**
   * Start with `{@NO_GLOBAL_EFFECTS, deterministic, nothrow, threadsafe}`, `mutParams = {}`, `noescapeParams = allParams`, `mutThis = false`, `uniqueReturn = true`, and (for `BIP007-P2` optimizer flags) `@TERMINATING`. (`pure` holds after convergence iff `@NO_GLOBAL_EFFECTS && mutParams.isEmpty() && !mutThis`.)
   * Functions explicitly marked `impure` start and stay at `{}` (`@NO_GLOBAL_EFFECTS = false`, `noescapeParams = {}`, `uniqueReturn = false`).
   * Functions marked `axiom` are initialized directly to their declared `axiom` `EffectSet` / parameter flags (`axiom pure ...` and `axiom mut ...` set `@NO_GLOBAL_EFFECTS`) and excluded from downward weakening.
4. **Transfer & Monotone Fixed-Point Iteration:**
   * **Field writes / calls to `mut` / unconstrained callees:** If target is `@FRESH`, keep `@NO_GLOBAL_EFFECTS` and `mutParams` unchanged (provided the called `mut` method/helper itself has `@NO_GLOBAL_EFFECTS`!); if target is `@PARAM(i)` or `@THIS`, add $i$ / `this` to `mutParams`/`mutThis` (which automatically prevents `pure`); if target is `@EXTERNAL` or an un-axiomatized/I/O Java method, clear `@NO_GLOBAL_EFFECTS` (dropping `pure`), `deterministic`, `nothrow`, and `threadsafe`. Any callback invoked inside a verified `pure` or `deterministic` function must carry a constrained `Type.Function`—which `TypeChecker` enforces statically at all call sites (`any`/`any?` cannot be called or assigned to `Type.Function`, §3.4). Also check shallow call-site **`mut` exclusivity** across arguments, and that every argument to a `unique` slot is a `@UNIQUE` local.
   * **Finalize `pure`:** After convergence, `pure` is set iff `@NO_GLOBAL_EFFECTS && mutParams.isEmpty() && !mutThis` (so `pure` **always** implies zero external mutation).
   * **Escape & uniqueness transfer:** Storing `@PARAM(i)` into `this`, another parameter, a global, a closure, or returning it removes $i$ from `noescapeParams`. Returning any expression that may alias `@EXTERNAL`, non-`unique` `@PARAM`, or `@THIS`, or a local that is `@FRESH` but no longer `@UNIQUE` (something shared was stored into it), drops `uniqueReturn`.
   * **Reads / non-determinism:** If expression reads a reassigned outer `def` (outside a frame-local `noescape` closure, §4.5), non-`final` static field, mutable singleton field, or calls a non-`deterministic` callee, drop `deterministic`.
   * **Exceptions:** If expression executes `assert`/`suppose`, a runtime-checked `as` cast (not statically proven redundant), a Java boundary check (`JavaTypes.checkBoxed` or a generic `TypeVariable`/`WildcardType` return/field check unless covered by `axiom nothrow`), or calls a non-`nothrow` callee, drop `nothrow`.
   * **Static parameter conformance:** Babble-to-Babble calls are statically type-checked with zero runtime `Types.check` overhead (`BIP008-P5`), while `Faces` checks `JavaTypes.checkParameters` when Java calls into Babble.
   * **Compositional algebraic propagation:**
     * Composition $f(g(x))$ where both $f$ and $g$ are `monotone` $\implies$ `monotone`; same for `injective`.
     * Single-op wrapper `(a, b) -> ( op(a, b) )` inherits `commutative` / `associative` from `op`.
   * **Derived `threadsafe` (in-SCC monotone rule):** `threadsafe` stays set across SCC fixed-point iteration iff `@NO_GLOBAL_EFFECTS` and `deterministic` hold, `mutParams` is empty and `!mutThis` (unless explicitly annotated `threadsafe`), every parameter and captured variable has a deeply immutable type (deep `value class`, `frozen T`, primitives, strings, `null`, or unions/nullables thereof—or is an exclusively borrowed `unique noescape` parameter), **and** every callee retains `threadsafe` (with any `mut` helpers confined strictly to `@FRESH` locals). Because `threadsafe` iterates inside the SCC fixed point, direct and mutual recursion over deeply immutable data naturally converges with `threadsafe`. Functions carrying `axiom` without `threadsafe` never auto-derive `threadsafe`.
   * **`@TERMINATING` *(`BIP007-P2`)*:** Dropped if the body contains any `while` loop, calls `recurse` or another function in the same cyclic SCC, or calls any non-`@TERMINATING` callee.
5. **Verification Check (Verified Mode):**
   * For every function with explicit non-`axiom` annotations, verify `inferred.satisfies(declared)` (including `declared.noescape ⊆ inferred.noescape`, `declared.uniqueReturn => inferred.uniqueReturn`) and `inferred.mutSet ⊆ declared.mutSet`, plus definite-unassignment after `move`/`freeze`. Emit targeted diagnostics if any obligation fails—while retaining *extra* inferred properties strictly for intra-module optimization of that function (callers' own verification relies only on the declared signature, §3.1).

> **What is fixed-point iteration?**  
> Because functions can be directly or mutually recursive (`f` calls `g` and `g` calls `f`), a single top-down or bottom-up pass cannot always know every callee's effect summary upfront. Fixed-point iteration solves these cyclic dependencies by treating the call graph as a system of constraints $\mathbf{S} = F(\mathbf{S})$: starting from optimistic initial guesses, a worklist loop repeatedly recomputes each function's summary from its body and callees' current summaries until a full iteration produces no further changes (a **fixed point**, $F(\mathbf{S}) = \mathbf{S}$). Because transfer rules only shrink finite effect sets monotonically (and never re-add dropped guarantees), termination is guaranteed in finite steps—producing the tightest sound summary permitted by the lattice.  
> **Further reading:**
> * [Data-flow analysis (Wikipedia)](https://en.wikipedia.org/wiki/Data-flow_analysis) — overview of iterative equation solving and Kildall's worklist method.
> * [Kleene fixed-point theorem (Wikipedia)](https://en.wikipedia.org/wiki/Kleene_fixed-point_theorem) — order-theoretic guarantee that monotone iteration on a finite-height lattice converges to a fixed point.
> * Anders Møller & Michael I. Schwartzbach, [*Static Program Analysis*](https://cs.au.dk/~amoeller/spa/) (Aarhus University) — accessible textbook covering lattices, monotone frameworks, and worklist solvers.
> * Gary A. Kildall (1973), *"A Unified Approach to Global Program Optimization"*, Proc. ACM POPL — foundational paper introducing lattice-based fixed-point dataflow analysis.

### 5.2 Escape, Uniqueness (`unique`), and Exclusivity Analysis

A completely unannotated function like:
```babble
axiom pure deterministic nothrow noescape java.util.List.size
def Stats = class (def count: int = 0, def total: int = 0) (
    def add = mut deterministic nothrow (v: int) -> (
        this.count = this.count + 1
        this.total = this.total + v
    )
)
def buildStats = (xs: java.util.List(int)) -> (
    def s = Stats(0, 0)
    s.add(xs.size())
    s
)
```
has its return type `Stats` inferred by bidirectional local type inference, and automatically infers **`pure deterministic nothrow`**, **`noescape xs`** (`xs` is untouched and never escapes), AND **`unique` return** (`: unique Stats`)—because `s` was freshly constructed inside `buildStats`, `xs.size()` yields a primitive `int` rather than escaping `xs`, and zero aliases to `s` remain in `buildStats`.

Babble tracks these local points-to / ownership states per binding in `TypeChecker` (only for function-local bindings that no escaping closure captures; every other binding is `@EXTERNAL`):
* **`@FRESH`:** Bound solely to a `pure` constructor, `object` literal, `unique` return, or `unique` parameter; calling a `@NO_GLOBAL_EFFECTS` `mut` helper/method on it leaves `@NO_GLOBAL_EFFECTS` and `mutParams` untouched (so the caller stays `pure`!). The object is new, but its contents may be shared.
* **`@UNIQUE`** *(substate of `@FRESH` — deeply owned)*: everything stored into it so far was `@UNIQUE` (moved in) or deeply immutable. Only a `@UNIQUE` binding may be passed to `move`, a `unique` slot or `freeze(...)`, or returned as `unique`. Storing a `@PARAM`, `@EXTERNAL`, `@FRESH`-but-not-`@UNIQUE` or `any`/`any?` value into it (directly, or as an argument a Java method may retain) demotes it to `@FRESH` for the rest of the function.
* **`@PARAM(i)` / `@THIS`:** Aliases parameter $i$ or `this`. Any mutation marks $i$ / `this` as `mut`; escaping it into `this`, another parameter, an outer variable, an escaping closure, or the return value clears `noescape(i)`.
* **Sub-reference derivation (`p.child`, `xs.get(0)`):** Reading a non-deeply-immutable field or element from `@PARAM(i)` or `@THIS` inherits `@PARAM(i)` / `@THIS` on the derived value, so mutating or escaping `p.child` is tracked back to parameter $i$ / `this` (forbidding hidden mutation through aliases of non-`mut` parameters). Reading a field from `@UNIQUE` stays inside that owned graph (mutating it preserves `@FRESH`/`@UNIQUE`, while storing it externally demotes the root to `@FRESH`); reading a mutable reference out of a merely `@FRESH` (non-`@UNIQUE`) local yields `@EXTERNAL`.
* **`@FROZEN`:** Any binding whose type is deeply immutable (`frozen T` from `freeze(expr)`, deep `value class`, primitive, string, `null`, or unions/nullables thereof); **`threadsafe`** for zero-copy concurrent sharing. Unlike the other states this one is carried by the type, so it holds for parameters, fields and top-level `def`s too.
* **`@MOVED`:** After `move x` or `freeze(x)` (including implicit last-use move), `x` transitions to `@MOVED`; definite-unassignment rejects any subsequent read or write to `x`.
* **`@EXTERNAL`:** Any reference stored to or loaded from top-level, package, static, or escaping-closure bindings, returned from an unconstrained call, or read out of a non-`@UNIQUE` `@FRESH` container becomes `@EXTERNAL` (cannot be mutated in `pure` or returned as `unique`).
* **Control-flow merge join ($\sqcup$):** At `if`/`else` joins and loop back-edges, local ownership states join monotonically:
  * `@UNIQUE` $\sqcup$ `@UNIQUE` = `@UNIQUE`; `@UNIQUE` $\sqcup$ `@FRESH` = `@FRESH`;
  * `@MOVED` $\sqcup$ *any state* = `@MOVED` (a binding moved on either branch cannot be used after the merge until reassigned);
  * `@PARAM(i)` $\sqcup$ `@PARAM(j)` = `@PARAM({i, j})` (subsequent mutation or escape taints all potentially aliased parameters);
  * joining `@EXTERNAL` (or mixing `@FRESH` with `@PARAM(i)`) yields `@EXTERNAL` while preserving `@PARAM(i)` tainting.
* **Call-site `mut` exclusivity:** At `callee(a0, a1, ...)`, every `mut` argument `ai` must be a different object from every other `aj` and `this`—checked statically across distinct variables/paths/frozen values and guarded at runtime via `same(a, b)` if runtime aliasing is possible. The check is shallow; only a `@UNIQUE` argument in a `unique mut` slot is deeply disjoint (§3.2).

### 5.3 Concrete Compiler Optimizations Unlocked

With resolved `EffectSet`, `mut`/`noescape`, and `unique` metadata recorded in `ScopeAnalysis` per parse-tree node and consumed by `CodeGenerator`, bytecode passes in `org.babblelang.compiler.optimizer.OptimizerBase`, and parallel collection combinators, the compiler enables:

1. **CSE, Memoization & LICM:**
   * All result reuse requires `f` to be **`pure deterministic`** and its **result type to be deeply immutable** (primitive, string, `frozen T`, deep `value class`). A function returning a new mutable object (any `unique` return, e.g. `buildIndex`) is never reused: two calls must yield two distinct objects, or a mutation of one would show in the other and `same` would change its answer. Such calls can still be reordered or dropped.
   * **CSE:** two occurrences of `f(a, b)` in one function collapse into one call when, in addition, the state the first call read is unchanged at the second: either all arguments are deeply immutable, or nothing in between may write to a graph reachable from them (no assignment through them, no plain-`mut` or unconstrained call that could reach them; see the caller-side rule in §3.2). When `f` is additionally **`commutative`** (on top of `pure deterministic`), `f(b, a)` also matches `f(a, b)`.
   * **Memoization across calls and compile-time folding:** additionally require **all arguments to be deeply immutable**, so that equal keys mean equal states for the lifetime of the cache. A call that can reach an identity-derived value (§4.3) is not `deterministic` and is never folded.
   * Repeated nested calls `norm(norm(s))` collapse to `norm(s)` iff `norm` is **`pure deterministic idempotent`** (value projection form `(T) : T`, with the same conditions on `s` and the result).
2. **`@NOALIAS` Local/Field Load Hoisting & Redundant-Load Elimination:**
   * Because a **`unique mut dst`** parameter owns its whole graph, it shares no mutable object with `src`, `this`, captured variables or globals; loops writing `dst` can safely keep `src` fields in JVM locals, hoist invariant `src` reads out of loops, and eliminate redundant loads without runtime alias guards. Plain `mut dst` gives none of this: the shallow identity check does not exclude shared interior state.
3. **Predicate Push-Down & Push-Up (Stream / Query Pipelines):**
   * **Guard-safe filter swap:** `if p(x) and q(x)` $\leftrightarrow$ `if q(x) and p(x)` (put cheaper/more selective filter first) iff `p` and `q` are **`pure` and `nothrow`** (or `q` never faults when `p` is false).
   * **Push filter below expensive `map` / `join`:** `xs.map(f).filter(p)` can invert/evaluate `p` on raw keys before a costly step (`xs.filter(x -> p'(x)).map(f)` or `join(xs, ys).filter(p_on_x)` $\to$ `join(xs.filter(p_on_x), ys)`) iff `f` and `p` are **`pure deterministic`** and **`nothrow`**. Inverted range predicates (`f(x) > c`) rely on **`monotone`**, and equality / `distinct` push-down (`f(x) == f(y) <=> x == y`) relies on **`injective`**.
   * **Push-up / Factorization:** `union(a.filter(p), b.filter(p))` $\leftrightarrow$ `union(a, b).filter(p)` iff `p` is **`pure deterministic nothrow`**.
4. **Zero-Copy Builders (`freeze`), Thread Handoff (`move`), and Functional-But-In-Place (FBIP):**
   * **`unique` $\to$ `freeze`:** Imperatively populate a local index/collection (`@UNIQUE`: only owned or deeply immutable contents), call `freeze(idx)`, and immediately share it across `parallelMap`/`parallelFilter` threads with **zero copies and zero locks**.
   * **`unique` + `move` thread handoff:** Send mutable buffers across concurrent tasks/workers lock-free when `move` guarantees single ownership.
   * **FBIP in-place reuse:** When a caller passes a dead/moved `unique` collection into a pure transform (`sort(move xs)` / `xs.map(f)`), specialize to mutate backing storage in place with zero heap allocation while preserving referential transparency.
5. **Stack Unboxing / Scalar Replacement (`noescape`):**
   * Temporary `value class` arguments or closure captures passed into `noescape` parameters can be unboxed into primitive JVM locals/registers across call boundaries in `CodeGenerator`, avoiding heap allocation even when JIT inlining stops.
6. **Parallel Execution & Concurrent Data Sharing:**
   * **`parallelMap` / `parallelFilter`:** Safe whenever callback is **`pure threadsafe`** (automatically inferred for verified `pure deterministic` code on deeply immutable inputs). Data shared between workers must be deeply immutable, or `@UNIQUE` in the caller and exclusively borrowed by the combinator for its duration; `@THREAD_COMPATIBLE` data that is neither is rejected, since nothing rules out a concurrent writer.
   * **`parallelReduce`:** Safe divide-and-conquer tree fold whenever combiner is **`pure deterministic threadsafe associative`** (preserving partition order if not `commutative`, or arbitrary work-stealing if **`commutative`**).
   * **`parallelForEach`:** Safe concurrent accumulation whenever callback's `mut` writes on the shared accumulator target use **`commutative threadsafe`** operations (and retryable on worker failure if **`idempotent`** in effect form).
7. **Reassociation & Dead-Code Elimination:**
   * Reassociate `(x + c1) + c2` $\to$ `x + (c1 + c2)` when `+` is **`pure deterministic nothrow associative`** (valid for `int` / `string`, skipped for IEEE-754 `double`!).
   * Eliminate unused expression `f(x); e` when `f` is **`pure nothrow`** (and `@TERMINATING`).

### 5.4 Property-Based Verification of `axiom`s (Test / Debug Aid)

While `axiom` tells the compiler to skip compile-time proof, semantic axioms (`deterministic`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `nothrow`) are ideal targets for **automated property-based testing** in a future `babble.checkAxioms` test mode:

* **Input generators:** `checkAxioms` provides built-in boundary and pseudo-random generators for primitives and core types (`int`, `double`, `bool`, `string`, and standard Java collections of generator-supported elements) and recursively constructs Babble `value class` instances whose fields have generator-supported types; functions with unsupported parameter types either use a test-registered generator or skip fuzzing with a diagnostic.
* For `axiom commutative`: sample pairs $(a, b)$ and assert `f(a, b) == f(b, a)` (or post-state equality on fresh `mut` receivers).
* For `axiom associative`: sample triples $(a, b, c)$ and assert `f(f(a, b), c) == f(a, f(b, c))`.
* For `axiom idempotent`: assert `f(f(x)) == f(x)` (or post-state equality after 1 vs 2 calls on fresh `mut` state).
* For `axiom deterministic`: invoke twice with equal cloned inputs and assert identical outputs.

This turns unchecked mathematical assumptions into continuously fuzz-tested invariants in CI.

---

## 6. Java Interop and Bytecode Analysis

Java methods and classes default to **unconstrained** (`EffectSet.EMPTY`, conservative `mut` assumed on mutable receivers/args), **except** the standard value protocol (§4.3.2): on Java classes, `equals` and `compareTo` default to `pure deterministic`, and so do `hashCode` and `toString` when overridden (while the identity-derived `Object.hashCode`/`Object.toString` default to `pure` only). All can be opted out via `impure`.

### 6.1 In-Babble `axiom` and `impure` Declarations

```babble
;; Method-level axioms:
axiom pure deterministic nothrow threadsafe java.lang.Math.sqrt
axiom pure deterministic nothrow threadsafe associative commutative java.lang.Integer.sum
axiom pure deterministic threadsafe commutative java.lang.Math.addExact  ;; throws on overflow (not `nothrow`, not associative across overflow)
axiom pure deterministic nothrow threadsafe commutative java.lang.Math.max
axiom pure threadsafe java.nio.file.Files.readString                 ;; pure & race-free read, NOT deterministic!
def mkdirs = axiom idempotent threadsafe java.nio.file.Files.createDirectories
axiom mut commutative threadsafe java.util.concurrent.atomic.AtomicInteger.addAndGet

;; Class-level axioms (marks all public constructors and methods; individual methods can refine):
def BigDecimal = axiom pure deterministic threadsafe import("java.math.BigDecimal")

;; Module re-export & per-runtime isolation:
export axiom pure deterministic nothrow threadsafe java.lang.Math.sin

;; Opting out default-pure value protocol methods:
impure java.net.URL.equals
impure java.net.URL.hashCode
```

* **Compile-time resolution:** `TypeChecker` resolves all `axiom` / `impure` declarations purely at compile time via `ScopeAnalysis` (and `AnalysisSession` across REPL inputs, or `ModuleInterface` for exported module axioms), never in `ScriptRuntime` or global singletons.
* **Order-independent within a file:** `TypeChecker` collects all top-level and `package`-level `axiom` / `impure` declarations in a pre-pass before building the SCC call graph, so declaring an axiom before or after a caller in the same file has the exact same effect. `axiom` declarations are forbidden inside verified `pure`/`deterministic` function bodies (must appear at top-level, `package`, or unconstrained scope).
* **Overloads and instance methods:** A method-level target (`java.lang.Math.max`, `java.util.HashMap.put`) applies uniformly to **all overloads** of that method name on the target Java class (for instance methods, `mut` applies to the receiver `this`, and `noescape` on the declaration applies to `this` and all reference parameters). A class-level declaration (`def BigDecimal = axiom ... import("java.math.BigDecimal")`) applies to **all public constructors and public methods** of that class, while method-level overrides on the same class take priority (§6.2).
* **`export axiom` in `ModuleInterface`:** `export axiom ... <target>` does not introduce a runtime value binding; instead, `ModuleInterface` carries an exported axiom table mapping `(Class<?>, memberName | "*") -> EffectSet`, which `TypeChecker` imports into the caller's `ScopeAnalysis` on static `import("...ba")`.

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

Embedded hosts can register trusted JDK/third-party methods programmatically on `BabbleScriptEngine` (isolated per engine instance and read by `TypeChecker` at compile time), and optionally forbid untrusted scripts from declaring in-script axioms via `allowAxioms(false)`:

```java
engine.allowAxioms(false); // optional sandbox policy: reject script-level axiom/impure declarations
engine.assumeEffects(Math.class, "sqrt", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
engine.assumeEffects(Files.class, "readString", Effect.PURE); // read-only, non-deterministic
engine.assumeEffects(Files.class, "createDirectories", Effect.IDEMPOTENT);
engine.markImpure(URL.class, "equals", "hashCode");
```

Compile-time resolution priority in `TypeChecker`:
1. File / imported `ModuleInterface` / `AnalysisSession` method override (`axiom` / `impure`), else
2. Engine-level `BabbleScriptEngine` method override (`assumeEffects` / `markImpure`), else
3. Java method annotations (`@Pure`, `@Deterministic`, ...), else
4. File / module / session or engine class override, else
5. Java class annotations, else
6. Default value protocol (`equals`, `compareTo`, and overridden `hashCode`/`toString` $\to$ `{PURE, DETERMINISTIC}`; `Object`'s own `hashCode`/`toString` $\to$ `{PURE}`), else
7. Unconstrained (`EffectSet.EMPTY`).

### 6.3 Java Callbacks and Interface Proxies (`Faces`)

When a verified `pure deterministic` Babble function passes a Babble `Callable` or `BabbleInstance` (wrapped via `Faces.function` or `Faces.create` into `Function`, `Predicate`, `Comparator`, etc.) to an axiomatized Java higher-order function such as `list.parallelStream().map(fn)`:

* **Checked purely statically (`TypeChecker`):** Functional-interface arguments inside constrained scopes must have a statically known `Type.Function` (or implementing `BabbleClass`) whose effects satisfy the enclosing call's requirements (e.g., passing an unconstrained `(x) -> ( println(x) )` or an `any` value to `parallelStream().map(...)` inside a `pure` caller is rejected at compile time). `Faces.FunctionHandler` and `Faces.FaceHandler` require zero effect-checking logic or thread/frame context and work seamlessly across `parallelStream` worker threads.

### 6.4 Automatic Static Analysis of Java Bytecode via ASM (Feasibility & Limits)

Babble already depends on `org.ow2.asm:asm` (`pom.xml`), raising the question: can bytecode analysis automatically infer effects on JDK/library `.class` files instead of requiring `axiom` declarations—assuming all `native`/FFI methods (`ACC_NATIVE`, JNI, Panama FFM) are unconstrained (`impure`)?

* **Feasibility & Soundness:** **Yes**, for memory/exception effects (`pure`, `mut`, `deterministic`, `nothrow`). An ASM analyzer (like Sălcianu–Rinard's pointer/escape analysis or JPure) can soundly build a conservative call graph and inspect `PUTFIELD`, `PUTSTATIC`, `GETSTATIC` (non-`final`), `*ASTORE`, `MONITORENTER`, `<clinit>`, and `ACC_NATIVE`, falling back to unconstrained whenever open virtual/interface dispatch (`INVOKEVIRTUAL`, `INVOKEINTERFACE`), reflection, or `<clinit>` cannot be bounded.
* **Precision Limits:** Under a strict *"all `native` calls are impure"* policy, precision degrades sharply on the JDK:
  1. **Transitive `native` intrinsics:** `System.arraycopy` (`native`), `Object.getClass()` (`native`), and `Double.doubleToRawLongBits` (`native`) appear inside `String.substring`, `ArrayList`, `Arrays.copyOf`, and `BigDecimal`—tainting nearly the whole JDK without a small audited intrinsic seed table.
  2. **Internal local mutations & benign lazy caches:** `StringBuilder` inside `toString()`, `this.hash` caching in `String.hashCode()`, and `BigDecimal.stringCache` perform `PUTFIELD`/`*ASTORE`, requiring inter-procedural escape analysis for local buffers and explicit `axiom` for benign lazy memoization caches.
  3. **Algebraic & policy properties cannot come from bytecode:** Bytecode inspection can **never** infer that `Files.readString` is acceptable policy (`axiom pure`), nor in general prove `commutative` / `associative` / `idempotent` / `monotone` / `injective`.
* **Verdict for Babble:** Keep explicit `axiom` + annotations + `BabbleScriptEngine` API as the primary `BIP007-P1` mechanism; add an optional **ASM bytecode analyzer in `BIP007-P3`** (seeded with proven JVM `native` intrinsics like `System.arraycopy`) to **suggest `axiom` candidates** or auto-certify closed pure helpers.

---

## 7. Type System, Compiler, and Runtime Architecture

### 7.1 `Type.Function`, `Type.Parameter`, and `EffectSet`

In `org.babblelang.analysis.Type`:

* **`EffectSet`** (immutable bitmask / enum set over `{PURE, DETERMINISTIC, NOTHROW, IDEMPOTENT, COMMUTATIVE, ASSOCIATIVE, THREADSAFE, MONOTONE, INJECTIVE}` + internal compiler flags `boolean noGlobalEffects` [`@NO_GLOBAL_EFFECTS`], `boolean mutThis`, `boolean noescapeThis`, `boolean uniqueResult`, and explicit `boolean impureOptOut`).
* **`Type.Parameter(String name, Type type, boolean optional, boolean mut, boolean noescape, boolean unique)`**: the single representation of per-parameter mutability, escape guarantee, and ownership (`(mut noescape unique dst: Buffer, noescape src: java.util.List(string))`).
* **`Type.Frozen(Type of)`**: the `frozen T` type produced by `freeze` (simplified to `of` when `of.isDeeplyImmutable()`, and distributing over `Type.Union` so `frozen (A | B)` becomes `frozen A | frozen B` and `frozen (T?)` simplifies to `(frozen T)?`). Member reads and non-`mut` `noescape` method calls on `Frozen(T)` wrap their result `U` in `Frozen(U)`; `Frozen(T)` is not assignable to `ANY`, `ANY?`, or plain `T`, `ANY`/`ANY?` is not assignable to `Frozen(T)`, and `Frozen(ANY)` / `Frozen(ANY?)` (from Java methods returning `Object`) can only be narrowed (`is`) or cast (`as`) to deeply immutable concrete types (verified at runtime by `Types.check`). `Type.isDeeplyImmutable()` holds for primitives, strings, `NULL`, `Frozen`, `value class`es whose field types all satisfy it, and `Union`s whose members all satisfy it.
* **`Type.Function(List<Parameter> parameters, Type result, EffectSet effects)`**:
  * Invariant: `effects.contains(PURE)` implies `effects.noGlobalEffects && !effects.mutThis` and no parameter in `parameters` has `mut`.
  * Assignability (`TypeChecker`): `actual.mut(i) => target.mut(i)` and `actual.mutThis => target.mutThis` hold uniformly across **all** `Type.Function`s (even when `target.effects` is empty, so absent `mut` always means read-only, §3.4). When `target` is unconstrained (empty `effects` and no `mut`/`noescape`/`unique`), it accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects. When `target` is constrained (non-empty `effects` or any parameter/`this` modifier), `actual` must additionally satisfy `actual.effects.satisfies(target.effects)`, `target.noescapeThis => actual.noescapeThis`, `target.uniqueResult => actual.uniqueResult`, `target.noescape(i) => actual.noescape(i)`, and `actual.uniqueParam(i) => target.uniqueParam(i)`. Neither unconstrained nor constrained `Type.Function` accepts `ANY`, `ANY?`, or `NULL`. For generic functions (`BIP008-P6b`), `THREADSAFE` depends on the instantiated type arguments being deeply immutable (unless `unique noescape`).

### 7.2 Zero Runtime Effect Machinery (`Types`, `babble-runtime`)

Because Babble is statically typed (`bip008_static-typing.md`), `TypeChecker` disallows assigning `frozen T` to/from `any`/`any?` (§3.2) or `any`/`any?` to `Type.Function` (§3.4), and `any` cannot be called or have members accessed without narrowing (`is`) or casting (`as`). Consequently, **the entire effect and ownership system is enforced at compile time with zero effect code in `org.babblelang.runtime` (`babble-runtime.jar`)**:

* `Types.check` (retained only for `as` casts, Java boundaries, and `setMember`, `bip008_static-typing.md` §7) requires no `fx|` or `frozen` descriptors (`as` already rejects function types statically and cannot unwrap `frozen T`).
* `Callable`, `Function`, `CompiledFunction`, `CompiledMethod`, `JavaMethod`, `BabbleInstance`, `ScriptRuntime`, `Bootstrap`, `Calls`, `Faces`, and `Operators` carry no runtime `EffectSet` fields or effect-checking hooks.

### 7.3 Compiler (`CodeGenerator`) and Optimizer Integration

1. **`freeze` intrinsic:** `Interpreter` and `CodeGenerator` evaluate `freeze(expr)` as a zero-cost identity no-op (`expr`).
2. **`CodeGenerator` & Optimizers:** `TypeChecker` publishes resolved `Type.Function` / `EffectSet`, `noescape`, and `unique` metadata in `ScopeAnalysis` so `CodeGenerator` and bytecode optimizer passes (`org.babblelang.compiler.optimizer.OptimizerBase` extensions) can query `effects(ctx)` directly without re-running analysis.

---

## 8. Examples

### 8.1 Verified Pure Deterministic Code vs. `axiom` Java Interop

```babble
;; Declare trusted host axioms explicitly:
axiom pure deterministic nothrow threadsafe java.lang.Math.sin
axiom pure deterministic nothrow threadsafe java.lang.Math.cos
axiom pure deterministic nothrow threadsafe java.lang.Math.sqrt
axiom pure threadsafe java.nio.file.Files.readString   ;; side-effect-free & race-free, NOT deterministic!
axiom pure deterministic threadsafe java.nio.file.Path.of  ;; may throw InvalidPathException (not `nothrow`)

package math (
    ;; Verified: compiler checks body satisfies `pure deterministic`.
    ;; Because `/` on `double` is IEEE-754 division (`Operators.div`) and never throws, `TypeChecker` also
    ;; infers `nothrow` and `threadsafe` quietly for intra-module optimization (while external callers
    ;; rely only on the explicitly declared `pure deterministic` signature, §3.1).
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

### 8.2 Argument & Receiver `mut`, Confined `@NO_GLOBAL_EFFECTS`, and Fresh Locals

```babble
def Counter = class (def count: int = 0) (
    def peek = pure deterministic nothrow () : int -> ( this.count )
    ;; Inferred @NO_GLOBAL_EFFECTS (mutates ONLY `this`, zero globals/I/O), never marked `pure`:
    def add  = mut deterministic nothrow (d: int) -> ( this.count = this.count + d )
)

;; Strictly `pure`: zero global writes, zero param/this mutation; calling `tmp.add` on @FRESH local is allowed!
def previewNext = pure deterministic (c: Counter, delta: int) : int -> (
    def tmp = Counter(c.peek())   ;; fresh local instance (@FRESH)
    tmp.add(delta)                ;; OK: `Counter.add` has no global/I/O side effects
    tmp.peek()
)

;; Confined `mut` helper (mutates ONLY `dst`, not `pure`):
def bumpBoth = deterministic (mut dst: Counter, src: Counter) -> (
    dst.add(src.peek())           ;; OK: `dst` is `mut`
    ;; src.add(1)                 ;; REJECTED: `src` is not `mut`!
    null
)
```

### 8.3 Predicate Push-Down, Reordering & Algebraic Pruning

```babble
;; Lossless monotone encoding. Computed in `double`: `int` to `double` is exact, and `raw * 10.0 + 5.0`
;; stays far below 2^53, so it is order-preserving and one-to-one. The `int` version `raw * 10 + 5`
;; would be neither: it wraps, and multiplying by an even number is not injective mod 2^32.
def encodeScore = axiom pure deterministic nothrow monotone injective (raw: int) : double -> (
    raw * 10.0 + 5.0
)

def isEligible = (score: double) : bool -> ( score > 100.0 )       ;; inferred: pure deterministic nothrow
def nonNegative = (raw: int) : bool -> ( raw >= 0 )            ;; inferred: pure deterministic nothrow

;; Because `encodeScore`, `isEligible`, and `nonNegative` are all `pure deterministic nothrow`:
;; 1) Predicates in `filter` can be reordered freely by cost/selectivity without changing errors.
;; 2) Filters can be pushed past `map(encodeScore)`.
;; 3) With `monotone`, `encodeScore(raw) > 100` can even be rewritten/pruned on raw sorted chunks!
```

### 8.4 Parallel `map`, Tree `reduce` (`associative`), and Retry-Safe Accumulation

```babble
axiom pure deterministic java.util.concurrent.ConcurrentSkipListSet
axiom mut idempotent commutative threadsafe java.util.concurrent.ConcurrentSkipListSet.add
axiom idempotent threadsafe java.nio.file.Files.createDirectories

def sumInt = (a: int, b: int) : int -> ( a + b )   ;; inferred: pure deterministic nothrow threadsafe associative commutative (single-op `int +` wrapper)
def fibCached = axiom pure deterministic (n: int) : int -> ( ... )  ;; unsynchronized HashMap cache: @THREAD_UNSAFE (NO `threadsafe`!)

def parallelMap = axiom pure threadsafe (xs: java.util.List(int), f: pure threadsafe (x: int) : int) : java.util.List(int) -> ( ... )
def parallelReduce = axiom pure threadsafe (xs: java.util.List(int), seed: int, op: pure deterministic threadsafe associative (a: int, b: int) : int) : int -> ( ... )
def parallelForEach = axiom threadsafe (mut acc: java.util.concurrent.ConcurrentSkipListSet(string), xs: java.util.List(string), f: idempotent commutative threadsafe (mut s: java.util.concurrent.ConcurrentSkipListSet(string), x: string)) -> ( ... )

;; 1) Safe parallel map + tree reduction (contextual lambda typing infers `x: int` and `: int`):
def totalSq = parallelReduce(parallelMap(items, (x) -> ( x * x )), 0, sumInt)
;; parallelMap(items, fibCached)  ;; REJECTED: `fibCached` omits `threadsafe` (may hide unsynchronized cache)!

;; 2) Safe unordered concurrent accumulation + retry (mut target + idempotent + commutative + threadsafe):
def collector = java.util.concurrent.ConcurrentSkipListSet(string)()
def ensureAndTrack = axiom idempotent commutative threadsafe (mut s: java.util.concurrent.ConcurrentSkipListSet(string), dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    s.add(dir)
)
parallelForEach(collector, dirs, ensureAndTrack)
```

### 8.5 Exclusivity (`@NOALIAS`), `unique` / `move` / `freeze`, and `noescape`

```babble
def Vec = class (def data: java.util.ArrayList(double)) ( ... )

;; 1) Deep exclusivity (@NOALIAS) through an exclusive borrow (`unique noescape mut`):
def addScaled = deterministic (mut noescape unique dst: Vec, noescape src: Vec, k: double) -> (
    ... ;; `dst` owns its whole graph: it shares nothing with `src` -> safe invariant-load hoisting & redundant-load elimination!
)
def scaleDemo = (src: Vec) -> (
    def v = Vec(java.util.ArrayList(double)())  ;; @UNIQUE local: a fresh Vec over a fresh list
    addScaled(v, src, 2.0)                      ;; exclusive borrow: `v` is usable (and still @UNIQUE) afterwards
    ;; addScaled(v, v, 2.0)                     ;; REJECTED: `v` is exclusively borrowed and cannot be passed twice
    ;; addScaled(src, v, 2.0)                   ;; REJECTED: `src` is a parameter, not @UNIQUE
    ;; def w = Vec(src.data)                    ;; `w` is @FRESH but not @UNIQUE (shares `src.data`): not accepted as `dst` either
    v
)
;; With plain `(mut dst: Vec, src: Vec)` all of these calls except `(v, v)` would be accepted, and the
;; compiler would assume nothing about aliasing: two distinct Vecs may share one `data` list.

;; 2) Imperative local builder -> zero-copy `freeze` -> concurrent parallel reads:
axiom pure deterministic java.util.HashMap            ;; pure constructor (creates a fresh empty map)
axiom mut deterministic java.util.HashMap.put         ;; mutates only `this` (@NO_GLOBAL_EFFECTS); retains its arguments
axiom pure deterministic threadsafe java.util.HashMap.get   ;; safe across concurrent readers once frozen/unmodified
def buildLookup = pure deterministic (n: int) : unique java.util.HashMap(int, int) -> (
    def m = java.util.HashMap(int, int)()
    def i : int = 0
    while i < n then (
        m.put(i, i * i)   ;; on @FRESH `m` this preserves `pure deterministic`; ints are deeply
        i = i + 1         ;; immutable, so `m` also stays @UNIQUE
    )
    m   ;; nothing mutable is shared with anyone -> verified `unique` return!
)
;; Had `buildLookup` stored elements of a caller-supplied list into `m`, `m` would be demoted to @FRESH:
;; the `unique` return and the `freeze` below would both be rejected (the "frozen" map would still
;; share mutable objects with the caller).
def readOnlyLookup = freeze(buildLookup(1000))  ;; compile-time transition (zero-cost no-op); type `frozen java.util.HashMap(int, int)`, `threadsafe`
def results = parallelMap(queries, (q: int) : int -> ( readOnlyLookup.get(q) as int ))  ;; `get` returns `int?`, cast to `int` (or handled via `!= null`)
;; readOnlyLookup.put(1, 2)                     ;; REJECTED statically: `put` is `mut` on `frozen HashMap`
;; def leaked : any = readOnlyLookup            ;; REJECTED statically: `frozen T` cannot assign to `any`

;; 3) Functional-but-in-place (FBIP) & definite-unassignment with `move` (locals only):
def sortInPlace = deterministic (unique mut xs: java.util.List(int)) : unique java.util.List(int) -> ( ... )
def sortedSquares = (n: int) : unique java.util.List(int) -> (
    def a = makeSquares(n)       ;; `makeSquares` returns `unique java.util.List(int)`: `a` is a @UNIQUE local
    def b = sortInPlace(move a)  ;; `a` is sorted in place without cloning
    ;; a.size()                  ;; REJECTED: `a` was moved and cannot be used afterward!
    b
)
;; def top = makeSquares(10)
;; sortInPlace(move top)         ;; REJECTED: `top` is a top-level binding, visible to other code

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

* **`BIP007-Q1` — Short-circuiting `and` / `or` and predicate push-down:** Under BIP008, `a.x > 0` on `a : T?` is already rejected unless guarded by `a != null` (or written `a?.x`), so `a.x` is `nothrow` in the narrowed right-hand branch of `a != null and a.x > 0`—yet hoisting `a.x > 0` ahead of `a != null` would still be unsound. Should optimizers treat short-circuit guards via path-sensitive nullability (building on `TypeChecker`'s existing flow-sensitive `Narrowing` for `!= null` and `is T` across `if`, `while`, and short-circuit `and`/`or`), leaving unconditional reordering strictly for two mutually `nothrow` predicates without guard dependencies? *(Recommended: Yes—never reorder a narrowed right-hand side ahead of its guard.)*
* **`BIP007-Q2` — Module exports and inferred vs explicit contracts:** Should `export def f = (x) -> (...)` export `f`'s **inferred** effects across module boundaries, or should cross-module public contracts require explicit annotations unless opted into inference? *(Exporting inferred effects maximizes optimization across modules, while explicit `impure` lets library authors hide unstable implementation properties.)*
* **`BIP007-Q3` — Zero / Identity elements for `associative` (`monoid`):** Should `parallelReduce` allow an optional identity annotation (e.g., `axiom associative identity(0)`) or take `seed` explicitly as in §8.4? *(Taking `seed` in `BIP007-P1` keeps the annotation language small; `identity` can be added as an optional `BIP007-P3` attribute.)*
* **`BIP007-Q4` — Local `axiom (...)` expression blocks:** Like Rust `unsafe { ... }`, should a verified `pure deterministic` function be allowed to enclose a tiny verified-safe internal mutation cache in an inline `axiom ( ... )` block, or should `axiom` stay strictly at function/declaration granularity? *(Starting at declaration granularity keeps trust boundaries crisp and grep-able.)*

### 9.2 Phased Implementation Roadmap

1. **`BIP007-P1` — Core Effect Model, SCC Fixed-Point Inference, Verification & `axiom`:**
   * Grammar (`Babble.g4`): add disambiguated contextual keywords `axiom`, `pure`, `mut`, `noescape`, `unique`, `move`, `freeze`, `frozen`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `threadsafe`, `monotone`, `injective`, `impure` on function literals, parameters, function types, `move` / `frozen` expressions/types, and top-level/`package`/exported Java `axiom` / `impure` targets.
   * Type system, inference & verification (`EffectSet`, `Type.Parameter(..., mut, noescape, unique)`, `Type.Function`, `TypeChecker`): enforce uniform absent `mut` across all `Type.Function`s, implement SCC bottom-up fixed-point inference and verification (integrated with BIP008's bidirectional local type inference, call-site parameter inference, generics, and flow-sensitive `Narrowing`) for `{pure, mut, noescape, deterministic, nothrow, threadsafe}`, single-op/compositional algebraic properties + signature applicability validation, frame-local `noescape` closure attribution, call-site specialization for small higher-order helpers, conservative `nothrow`, standard value protocol defaults (`equals`, `hashCode`, `toString`, `compareTo`), `value class` constructor checks, effect-constrained `Type.Function` assignability (zero `babble-runtime` changes, building on BIP008 static typing), interface conformance (`actual <: required`), and covariance/contravariance subtyping.
   * Engine & Registries (`BabbleScriptEngine`, `ModuleInterface`, `AnalysisSession`, `ScopeAnalysis`): order-independent pre-pass collection of file/`package` `axiom` and `impure` declarations, compile-time Java effect registries (`assumeEffects`, `markImpure`, `allowAxioms(false)`), exported axiom tables in `ModuleInterface`, and composable Java annotations (`@Pure`, `@Mut`, `@Noescape`, `@Unique`, `@Deterministic`, ...).
2. **`BIP007-P2` — Deep Ownership (`unique` / `move` / `freeze`), `@TERMINATING`, and Core Optimizations:**
   * Implement `@FRESH`/`@UNIQUE`/`@PARAM(i)`/`@THIS`/`@MOVED`/`@EXTERNAL` ownership lattice, sub-reference propagation, control-flow merge joins, definite-unassignment, implicit last-use `move`, purely static `Type.Frozen` (`freeze` zero-cost identity no-op in `Interpreter`/`CodeGenerator`), and `@TERMINATING` inference.
   * Publish `EffectSet`, `noescape`, `unique`, and `@TERMINATING` metadata in `ScopeAnalysis` and wire `CodeGenerator`, bytecode passes in `org.babblelang.compiler.optimizer.OptimizerBase`, and parallel combinators: CSE / constant folding (`pure deterministic` on deeply immutable args/results), `@NOALIAS` invariant load hoisting & redundant-load elimination (`unique mut` parameters), scalar replacement/unboxing (`noescape`), zero-copy `freeze` / FBIP reuse (`unique`), predicate reordering & hoisting (`pure deterministic nothrow`), dead-code elimination (`pure nothrow` + `@TERMINATING`), loop reassociation (`associative`), and parallel collection combinators (`parallelMap`, `parallelReduce`, `parallelForEach`).
3. **`BIP007-P3` — Algebraic Rewrite Rules, ASM Bytecode Suggester & Axiom Fuzzing:**
   * Add `monotone` range inversion and `injective` projection push-down in pipeline optimizers.
   * Add opt-in ASM bytecode analyzer (`ClassLoader` + escape analysis + seeded JVM `native` intrinsics) to suggest/auto-derive `pure`/`mut`/`deterministic`/`nothrow` on closed Java helpers.
   * Add property-based test harness (`babble.checkAxioms`) with built-in primitive, collection, and recursive `value class` generators to fuzz-test `axiom commutative`, `associative`, `idempotent`, and `deterministic` declarations in CI.
