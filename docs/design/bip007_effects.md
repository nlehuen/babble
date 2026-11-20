# BIP007: Function Effects and Algebraic Properties in Babble

**BIP:** 007  
**Date:** 2026-11-14 (updated 2026-11-19 for `RM005` / `BIP011`, `RM006` / `BIP012`, and `BIP016`)  
**Status:** Implemented (`BIP007-P1` landed in `RM002`, core-type profiles landed in `RM005` / `BIP011-P5`, predicate-free syntax landed in `RM006` / `BIP012`, and `BIP007-P2`–`P3` landed in `RM007`; `BIP007-D1`–`D10` settled; optimizer passes postponed beyond `BIP007` per `BIP007-D10`). Surface syntax subsequently consolidated to pure-by-default (`io`, `volatile`, `mut`, `shared`, `unique`, opt-in `nothrow`, `axiom pure`) with return-through-borrow in [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md) (`BIP016-D1`–`D10`, `BIP016-P1`–`P4`) while preserving `BIP007`'s Layer 1 internal orthogonal `@TRAITS`.  
**Depends on:** [bip008_static-typing.md](bip008_static-typing.md) (`BIP008-P1`–`P7`), [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md) (`RM004`: `TypedProgram`, `LoweringAnalyzer`, `TypedModuleSummary`), [bip011_core-types.md](bip011_core-types.md) (`RM005`: 9 scalars, core collections, Java nullability, `e!`, 8-primitive unboxing), [bip012_grammar-improvements.md](bip012_grammar-improvements.md) (`RM006`: predicate-free `fn`/`of`/`on`/`record` LL(1)/LL(2) grammar)  
**Scope:** Static type system, effect inference & verification engine, `axiom` declarations, optimizer contract, Java interop, and runtime/bytecode compiler integration.

---

## Executive Summary

Babble captures **function effects and algebraic properties** directly in its static type system and parse-tree analysis (`ScopeAnalysis`) so the compiler can both **secure computation** (preventing unintended state mutation and ambient I/O leaks) and **optimize computation** (reordering, common subexpression elimination (CSE) / memoization, predicate push-down/push-up, tree reductions, and safe automatic retries).

Rather than creating separate "trusted" vs "checked" keyword hierarchies (e.g., `pure` vs `trusted_pure`), Babble uses **one composable property vocabulary** paired with **three derivation modes**:

1. **Inferred** — unannotated Babble functions automatically receive the strongest properties provable by whole-module fixed-point analysis.
2. **Verified** — explicitly annotated functions (e.g., `fn pure deterministic (...)`) are statically proved by the compiler against their body and callees.
3. **Axiomatic (`axiom`)** — functions or Java targets prefixed with `axiom` (e.g., `fn axiom pure deterministic (...)`, `axiom associative commutative on ...`) assert properties that the compiler accepts as trusted contracts without inspecting/proving an implementation.

---

## 1. Context & Objectives

### 1.1 Problem Statement

A single binary `pure` / `impure` distinction is too coarse for modern compilers and query/pipeline optimizers:

* A time-reading function (`Instant.now()`) mutates no memory yet cannot be memoized or cached (`pure`, **not** `deterministic`).
* A builder helper mutates a caller-passed buffer without doing any global I/O (effects strictly scoped to its `mut` parameter, not `pure`—marked `hermetic mut`).
* Parsing an integer (`Integer.parseInt(s)`), asserting a precondition (`assert(x > 0)`), narrowing conversions (`int(d)`), or non-null assertions (`x!`) can be `pure deterministic` yet cannot be speculatively hoisted ahead of a guard (`nothrow` is false; note that Babble's `a / b` is IEEE-754 `float`/`double` division and never throws).
* A set insertion (`set.add(x)`) mutates its target, yet is order-independent and retry-safe (`hermetic mut idempotent commutative`).
* String concatenation (`a + b`) is `associative` (tree-reducible) without being `commutative`, whereas integer addition (`int`/`long`) is both.

To unlock real compiler transformations without turning code into unreadable annotation noise, Babble needs a **structured, composable effect and algebraic property model** that maximizes static **inference** while offering **`axiom`** as an explicit escape hatch where static proof is impossible or external.

### 1.2 Objectives: What Effects Unlock

Every property in the model is justified by concrete security guarantees or compiler/runtime optimizations:

| Compiler / Runtime Capability | Required Properties (Contract) | Example Transformation |
|---|---|---|
| **Common Subexpression Elimination (CSE), Memoization & Constant Folding** | `pure deterministic` + deeply immutable result; memoization and folding also need deeply immutable arguments (§5.3.1) | Cache `f(x)` across repeated calls; fold `f(const)` at compile time. |
| **Loop-Invariant Code Motion (LICM)** | `pure deterministic` (+ `nothrow` and `@TERMINATING` whenever the loop may execute zero times or the call is hoisted across a guard) | Hoist `normalize(cfg)` out of a loop body. |
| **No-Alias (`unique mut`) Local/Field Load Hoisting & Redundant-Load Elimination** | `unique mut` parameter (exclusively owned graph, §3.2) | Keep `src` fields in JVM locals and hoist invariant `src` reads across `unique mut dst` updates without alias checks. |
| **Expression & Filter Reordering** | `pure` (+ `nothrow` for guarded/short-circuit swaps) | Reorder `xs.filter(p).filter(q)` by estimated cost/selectivity. |
| **Predicate Push-Down & Push-Up** | `pure deterministic nothrow` (+ `monotone` / `injective` for inversion/projection pushdown) | Push `filter(p)` ahead of `map(f)` (`xs.filter(fn (x) -> ( p(f(x)) )).map(f)` when `p(f(x))` simplifies via `monotone`/`injective` or `f` is cheap/CSE'd), invert `monotone` bounds, push equality past `injective` projections, factor common filters above `union`. |
| **Zero-Copy Builder $\to$ Immutable Snapshot** | `unique` return + `freeze(...)` | Build a large mutable graph/index/collection locally, then `freeze(...)` zero-copy into deeply immutable `frozen T` data. |
| **In-Place Reuse (FBIP)** | `unique` + `move(...)` (single live owner) | Lower `sort(move(xs))` into in-place mutation (FBIP) with zero I/O/globals. |
| **Stack Unboxing / Scalar Replacement** | `borrow` parameter (with `record` / fresh arg) | Pass `Point(x, y)` or closure captures directly in registers/locals without heap allocation. |
| **Tree Reduction / Reassociation** | `pure deterministic associative` | Reassociate reductions and split folds into balanced subtrees. |
| **Safe Automatic Retry** | `idempotent` (or `pure` for read/compute steps) | Re-run failed steps (`Files.createDirectories`, `set.add`, `upsert`) without duplicating effects. |
| **Dead-Expression Elimination, Speculation & Unused-Result Errors** *(`BIP007-P2` / future optimizer BIP)* | `pure nothrow` (+ `@TERMINATING`) | **Report unused `pure nothrow` expressions in source as a compile-time error** in `BIP007-P2` (catching bugs like `s.trim()` with a discarded result; opt out explicitly with built-in `discard(expr)`); future optimizer passes drop dead `pure` expressions after inlining/CSE and speculatively evaluate before branches. |

### 1.3 Non-Goals

* **Full theorem proving / SMT verification of arbitrary equations:** The compiler does not attempt to prove algebraically from raw ASTs that arbitrary user arithmetic satisfies $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ or $f(a, b) = f(b, a)$; those rely on **`axiom`** (with optional property-based test verification).
* **Mandatory effect annotations everywhere:** Unannotated Babble scripts must continue to work seamlessly; effect inference (alongside BIP008's local and call-site type inference) avoids ceremony on ordinary scripting code.
* **Rust lifetime annotations (`'a`) or Pony's 6-way reference qualifiers on every variable:** Babble captures call-site exclusivity, `borrow`, `unique`/`move(...)`, and `freeze(...)` through parameter/return modes, definite-unassignment, and intra-procedural escape analysis rather than lifetime generics or pervasive viewpoint adaptation. The single `frozen` type qualifier (§3.2) is the only reference capability that appears in types.

---

## 2. State of the Art & Prior Art

Babble synthesizes ideas from four distinct communities: typed functional/effect languages, compiler/static-analysis annotations, database query optimizers, and distributed monotonic systems.

| Community / System | Key Concepts & Annotations | Strengths (`+`) & Pitfalls (`-`) for Babble |
|---|---|---|
| **Haskell / Clean** | Monadic `IO` / `ST` vs uniqueness types | `+` Sound referential transparency.<br>`-` "Function color" split makes refactoring local mutable helpers painful (`ST` monad ceremony). |
| **D** | Strong vs weak `pure`, `@safe`, `nothrow`, `@nogc` | `+` Separating "no global state/I/O" from parameter mutation enables local imperative helpers.<br>`-` Overloading the word `pure` onto mutating functions ("weak purity") confuses users, and **"attribute soup"** (`pure @safe nothrow @nogc const`) overwhelms readers without inference. |
| **Koka / Flix / Eff** | Algebraic effect rows (`<exn, div, console>`) + inference | `+` Effect inference avoids boilerplate.<br>`-` Higher-order effect polymorphism adds steep type-signature complexity for a JVM scripting/compiled language. |
| **Rust / Pony** | `&T` / `&mut T`, `unsafe`; Pony `val`/`iso`/`box`/`ref` | `+` Distinguishing shared read (`&T` / `box`) vs exclusive mutation (`&mut T`) + isolated ownership (`iso`) and immutable data (`val`) enables zero-copy freezing and in-place reuse.<br>`+` Explicit `unsafe` boundary concentrates audits. |
| **Java Checker Framework** | `@SideEffectFree`, `@Deterministic`, `@Pure` (`@SideEffectFree` + `@Deterministic`) | `+` Crucial separation: `@SideEffectFree` (no external mutation, e.g. `System.nanoTime()`) vs `@Deterministic` (same inputs $\implies$ same output). Both are needed together for memoization/CSE! |
| **PostgreSQL / GCC & Clang** | PG `IMMUTABLE` / `STABLE` / `VOLATILE`; GCC `__attribute__((const))` / `((pure))` | `+` `IMMUTABLE` $\approx$ `pure deterministic`; `STABLE` $\approx$ `pure` reading snapshot state (reorderable within statement, not foldable forever). |
| **Apache Calcite / Spark Catalyst / DuckDB** | `deterministic`, `strict` (null-preserving), `monotonic`, `injective`, `nothrow` | `+` Query optimizers show *which* properties unlock **predicate push-down/push-up**: `deterministic` + `nothrow` allow reordering across guards; `monotone` enables range pruning; `injective` pushes `distinct`/`==` across projections. |
| **Chapel / Fortress / OpenMP** | Reduction clauses (`+`, `*`, `min`, `max`), associativity + identity (monoids) | `+` `associative` enables tree reductions and loop reassociation; floating-point `+` requires opt-in or strict separation from integer `+`. |
| **Bloom (CALM) / LVars / Datafun / CRDTs** | Monotonic join-semilattices (ACI: Associative, Commutative, Idempotent) + `freeze` barriers | `+` Separates retry safety (`idempotent`) from order independence (`commutative`) and clarifies why reads of accumulators must happen after a barrier. |
| **Formal Systems / Lean / Dafny** | Checked proofs vs `axiom` declarations | `+` One uniform `axiom` concept works across both host FFI/Java and subtle user algorithms, replacing ad-hoc `trusted_*` keyword forks. |

### Key Design Lessons Synthesized for Babble

1. **Separate `pure` (writes) from `deterministic` (external reads) and `mut` (target/arg mutation):** Checker Framework, PostgreSQL, and D prove these are orthogonal. Folding them all into one bit either forbids valid read-only helpers or breaks caching.
2. **Inference by default beats attribute soup:** Every effect that *can* be inferred/verified structurally (`pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`) should be inferred for unannotated Babble code.
3. **One `axiom` keyword replaces all `trusted_*` variants:** Writing `axiom pure deterministic` instead of `trusted_pure` scales cleanly to `axiom commutative`, `axiom associative`, `axiom idempotent`, and `axiom monotone` without multiplying keywords.
4. **Never conflate `idempotent` with `commutative`:** `map.put(k, v)` is `idempotent` (retry-safe) without being `commutative`; `counter.add(n)` is `commutative` without being `idempotent`; set insertion (`set.add(x)`) has **both**. Keeping them independent makes the algebra exact.
5. **Distinguish `pure` from `nothrow`:** Predicate push-down and hoisting across `if`/loop guards require proving no runtime exception can be introduced.
6. **Respect Java bytecode & numeric limits:** Native/FFI calls (`System.arraycopy`) and floating-point non-associativity must be handled conservatively unless `axiom` says otherwise.

---

## 3. Proposed Model for Babble

> **Notation:** Surface modifiers and keywords written by Babble programmers appear in lowercase (`pure`, `hermetic`, `mut`, `borrow`, `unique`, `frozen`, plus built-in functions `move`, `freeze`, `discard`). Internal compiler states, inferred-only flags, and guarantees not directly manipulable by users use an **`@UPPERCASE` prefix** (`@PARAM(i)`, `@THIS`, `@FROZEN`, `@MOVED`, `@EXTERNAL`, `@TERMINATING`, `@TOTAL`, `@AXIOMATIC`). Java interop annotations (§6.2) use TitleCase (`@Pure`, `@Mut`, `@Unique`, ...).

### 3.1 Architecture Overview: Infer, Verify, or `axiom`

Every callable in Babble (function literal, method, constructor, or imported Java method/class) has an **Effect Profile** (`EffectSet` + per-parameter/receiver `mut` flags), derived in one of three modes:

1. **Inferred Mode (no explicit effect modifiers, and not marked `impure`):**
   * The compiler analyzes the function body and call graph via fixed-point iteration and infers the **maximal sound subset** of `{pure, hermetic, deterministic, nothrow}`, the **minimal `mut` parameter/receiver set**, and compositional relational properties (`monotone`, `injective`).
   * An explicitly marked **`impure`** function suppresses inference and locks the signature to unconstrained (used to prevent callers from depending on accidental purity, or to opt out default-pure value methods like `URL.equals`).
2. **Verified Mode (explicit effect modifiers *without* `axiom`, e.g., `fn pure deterministic (x: int) -> (...)`):**
   * Declared modifiers become **compile-time obligations**: the compiler verifies that the body, all expressions, and all callees satisfy every declared property and mutates **only** declared `mut` parameters / `mut` `this` / `unique` local objects.
   * Any violation is a compile-time type/effect error (`TypeCheckException`) pointing to the offending expression.
   * Meanwhile, *additional* unmentioned properties (like `nothrow` or `@TERMINATING`) can still be inferred quietly for intra-module optimization of this function body, while callers' own verification checks rely strictly on the explicitly declared modifiers so callers never depend on accidental implementation properties.
3. **Axiomatic Mode (`fn axiom <modifiers> ...` or `axiom <modifiers> on ...`):**
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
  * Performs **no output or mutating I/O** (`print`, socket/file writes, mutating FFI). Read-only environment queries (`axiom pure on java.nio.file.Files.readString`, `axiom pure on java.time.Instant.now`) and reads of mutable outer state perform no writes and may be `pure`, but are **not** `deterministic`.
  * Mutates **no global/outer variables, static fields, or external objects** (`hermetic`).
  * Mutates **no parameters and not `this`**: **`pure` cannot be combined with `mut`** on parameters or `this` (`fn pure (mut x: T)` and `fn pure mut ()` are rejected).
  * *Local builders (`unique`, `BIP007-P2`):* Inside a `pure` function, mutating **newly allocated `unique` local objects** (`def tmp = Counter(0); tmp.add(1)` or `def xs = list of int (); xs.add(1)`) *is* allowed whenever the called `mut` method/helper is **`hermetic`** (inferred, declared `fn hermetic mut`, built-in `RM005` core-collection mutators, or axiomatized via `axiom hermetic mut on ...`—without calling mutating functions "`pure`" in surface syntax). In `BIP007-P1` (`RM002`), before `unique` lands, mutating any object (including a newly allocated local) is disallowed in `pure`/`hermetic` functions, while local `def` variable reassignments remain valid.
* **`hermetic` (No global or I/O side effects):**
  * Performs **no output or mutating I/O** and mutates **no global/outer variables, static fields, or external objects**—any mutation is confined strictly to `mut` parameters, `mut` `this`, or `unique` local objects (`BIP007-P2`).
  * Unlike `pure`, **`hermetic` may be combined with `mut`** (`fn hermetic mut (...)`, `fn hermetic (mut dst: list of string, item: string)`). Every `pure` function is automatically `hermetic`, and a `hermetic` function with no `mut` parameters or `mut` `this` is `pure`.
  * Allows `pure` callers to invoke `hermetic mut` helpers, `RM005` core-collection mutators (`list.add`, `map.put`, `set.add`), and axiomatized Java methods (`axiom hermetic mut on java.util.ArrayList.add`) on `unique` local objects (`BIP007-P2`).
* **`mut` + Call-Site Exclusivity:**
  * Written on a parameter (`fn (mut target: list of string, item: string)`) or on the function itself (`fn mut (...)`) to indicate **`this` may be mutated**. Mutually exclusive with `pure` (use `hermetic mut` when the function also guarantees zero global/I/O side effects).
  * Absent `mut`, a parameter or `this` is **read-only** across all typed functions—including effect-unannotated functions whenever any parameter type or `: R` return type is written (`fn (a: T) ->`, `fn (): R ->`, `fn (rng): Vec3 ->`): field assignments (`p.x = ...`), calling `mut` methods on `p`, and passing `p` as a `mut` argument are forbidden unless `mut` is declared on `p`/`this` (with `mut` inferred only on completely untyped functions having no effect modifiers, no parameter types, and no `: R` return type).
  * **Law of Exclusivity (two strengths):**
    * *Plain `mut` (shallow, purely static bug catcher — `BIP007-D9`):* whenever an argument (or receiver) is passed to a `mut` slot, **no other argument or `this` at the same call site may be the same object** (`EffectChecker` checks static conflicts for same bindings, tracked local aliases, and identical member paths; zero runtime `Values.same` fallback). This is an identity check only. Two distinct objects can still share mutable state (`Vec(data)` and `Vec(data)` over one list), and the callee can still reach the object through a captured variable or a global. **Plain `mut` therefore grants permission to mutate and nothing else: neither the callee nor the caller may assume deep non-aliasing from it.**
    * *`unique mut` (deep disjointness / no-alias):* a `unique` parameter receives a graph that nothing else can reach (see `unique` below), so it is disjoint from every other argument, from `this`, from captured variables and from globals **by construction**, with no runtime alias check at all. All no-alias transformations (§5.3.2) key on `unique mut`, never on plain `mut`.
    * *Caller side:* after a call that passes `x` to a plain `mut` slot (or any unconstrained call), the caller must drop every cached fact about objects that are not provably disjoint from `x`. Provably disjoint means: `unique` locals not passed to the call, and deeply immutable values.
  * *Confined `mut` inference (`hermetic`):* Fixed-point inference tracks whether a `mut` Babble function avoids globals and I/O (`hermetic`), `RM005` (`BIP011-P5`) pre-registers core collection mutators as `hermetic mut`, and **`axiom hermetic mut on ...`** on Java targets likewise declares mutations confined strictly to `mut` targets with zero global/I/O effects—allowing `pure` callers to invoke them on `unique` local objects (`BIP007-P2`).
* **`borrow` (Frame-local parameter borrowing):**
  * Written (or automatically inferred) on a parameter (`fn (borrow p: Point)`) or receiver: guarantees the function never stores `p` (or mutable references reachable from `p`) into `this`, another parameter, or a global. Since `BIP016-D10` it may return `p`, and since `BIP017-D6` return a closure capturing it: the caller then sees the result as an alias of its argument, and storing it there is the escape.
  * Unlocks **read-only generic covariance** (`RM005` / `BIP011-D8`: `borrow list of Dog <: borrow list of Animal`) and **scalar replacement / stack unboxing** of wrapper objects (`Point`, temporary buffers, lambda captures) across call boundaries (future optimizer BIP).
* **`unique`, `move(...)`, and `freeze(...)` *(`BIP007-P2` — single-owner graph ownership & zero-copy transition)*:**
  * **`unique`** on a parameter (`fn (unique mut buf: Buffer)`) or return slot (`fn (...): unique map of (int, int)`) asserts **deep ownership**: every *mutable* object reachable from the reference is reachable **only** through it (Pony `iso` / Rust owned value). Deeply immutable values (all 9 core scalars `byte`..`string`, `null`, `frozen T`, deep `record`s, deep `entry of (K, V)`) may be shared freely inside a unique graph, since sharing them is unobservable.
    * *Keeping a graph unique (`BIP007-D6`):* a newly allocated object (from a `pure` constructor, a `unique` return, an `object` literal, or an `RM005` core collection constructor `list`/`set`/`sortedset`/`map`/`sortedmap`) starts `unique` provided every argument passed to it is `unique` (moved in) or deeply immutable (and for `from: xs`, `xs`'s element type is deeply immutable; for `comparator: cmp`, `cmp` is `pure deterministic`), and stays `unique` only while every reference stored into it (including via built-in `hermetic mut` core collection mutators) is itself `unique` (moved in) or deeply immutable. Storing a parameter, an outer variable, an `any`/`any?` value, or anything else demotes it to `@EXTERNAL` (or `@PARAM`-tainted): no longer mutable in `pure`, movable, freezable, or returnable as `unique` (§5.2). Arguments passed to a Java method are assumed stored in the receiver unless the method's built-in profile or axiom marks that parameter `borrow`.
    * *Consume vs. borrow:* a `unique` parameter **consumes** its argument (the callee may store it). A `unique borrow` parameter is an **exclusive borrow** (Rust `&mut`): the callee may not store it, so the caller's binding is usable again, and still `unique`, after the call.
  * **`move(x)`** (`BIP007-D5`, built-in function, not a keyword) explicitly transfers a `unique` local variable to a `unique` consumer (`channel.send(move(buf))`, `sortInPlace(move(xs))`), triggering **definite-unassignment** on `x` in the caller (cannot be used after moving; omitted automatically on a binding's provable last use). Zero-cost identity no-op at runtime.
    * *Locals only:* ownership is tracked only for **function-local bindings that no closure captures**. Top-level, module, class-static and captured bindings are namespace or cell variables that other functions, closures and later REPL inputs can reach; they are always `@EXTERNAL`, never `unique`, and `move(...)` on one is a compile-time error. A temporary that is never bound (`freeze(buildIndex(1000))`) is consumed directly and needs no `move(...)`.
  * **`freeze(expr)`** (`BIP007-D5`, built-in function, not a keyword) consumes a `unique` mutable graph and produces a **deeply immutable value of type `frozen T`** as a **purely static type transition (a zero-cost identity no-op at runtime)**—allowing imperatively built indexes, graphs, and core collections to become deeply immutable snapshots with zero copying.
    * *Why it is deep:* the argument must be `unique`, and a unique graph contains only owned objects and deeply immutable values. Nothing mutable is shared with the caller, so nothing outside can observe or change the frozen graph. A non-`unique` graph (one that absorbed a parameter or other shared mutable object) is rejected.
    * *The `frozen T` type (`BIP007-D7`):* frozen-ness is part of the type, so it survives fields, calls, returns and top-level `def`s (`def lookup: frozen map of (string, int) = freeze(...)`; inferred for never-reassigned unannotated `def`s like any other type). "Deeply immutable" means: all 9 core scalars (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`), `null`, `frozen T`, `record`s whose field types are all deeply immutable, `entry of (K, V)` where `K` and `V` are deeply immutable, and unions/nullables (`A | B`, `T?`) all of whose members are deeply immutable.
    * *Distribution & preservation (`BIP007-D7`):* `frozen` distributes over unions (`frozen (A | B)` = `frozen A | frozen B`, so `frozen T?` simplifies to `(frozen T)?` since `frozen null` == `null`), and is identity on the 9 core scalars and `null` (`frozen int` == `int`, `frozen string` == `string`). For all nominal/reference types (`class`, `record`, `interface`, Java classes, `any`), **`frozen T` never unwraps back to plain `T`**: a deeply immutable `record` is assignable *into* `frozen MyRecord` or `frozen any`, but `frozen MyRecord` is **not** assignable back to plain `MyRecord`.
    * *Covariance (`BIP007-D7`):* because `frozen T` forbids all `mut` calls and field writes, `frozen` generic types are **covariant** in all type arguments (`frozen list of Dog <: frozen list of Animal`, `frozen Box of Dog <: frozen Box of Animal`), extending `RM005`'s `borrow`/`record`/`entry` covariance safely without reopening heap pollution.
    * *Purely static enforcement & `is`/`as` narrowing (`BIP007-D7`, `RM006` `BIP012-D8`):* because static type checking is mandatory (`BIP008`), `TypeChecker` enforces immutability without any runtime graph walk, `BabbleInstance` frozen bit, or `JavaObject` wrapper:
      * `frozen T` is **not assignable to `any`, `any?`, or plain `T`** (and `e as T` / `e as any` cannot unwrap a `frozen` type), and **`any` / `any?` is not assignable to `frozen T`**. Any parameter, field, or reassigned `def` holding a frozen value must be typed `frozen T` (so `frozen` can never be erased into `any` or mutated via narrowing).
      * On a `frozen T` receiver, field writes and `mut` method calls are rejected; non-`mut` method calls are allowed only when `this` is `borrow` (or `pure` / `hermetic borrow` in built-in core-collection profiles or `axiom pure` on Java methods, so `this` cannot escape as unfrozen `T`), and passing `frozen T` to a Java parameter requires that parameter to be known non-`mut` and `borrow`.
      * Reading a field or calling a method on `frozen T` with concrete static type `U` yields `frozen U` (or `U` if `U` is a core scalar or `null`; nullable `U?` yields `(frozen U)?`, e.g. `(frozen map of (string, Counter)).get("a")` yields `(frozen Counter)?`). If a Java method on `frozen T` returns `java.lang.Object` (`any` / `any?`), `TypeChecker` types the result as `frozen any` / `frozen any?`.
      * **`is` / `as` on `frozen` (`BIP007-D7`):** `x is frozen T` is rejected statically (since `frozen` has no runtime tag, matching `RM006`'s rejection of `x is list of int`). Narrowing a `frozen any`, `frozen any?`, or `frozen (A | B)` with `x is T` **always preserves `frozen`**, narrowing `x` to **`frozen T`** (even for `record`s and mutable classes, so `a is Box` safely narrows to `frozen Box`, and `a is MyRecord` narrows to `frozen MyRecord`, never losing `frozen` to plain `MyRecord`!). Similarly, `a as T` on `frozen any` is rejected unless written `a as frozen T` (or targeting a core scalar/`null`).
      * Built-ins `same(a, b)`, `classof(x)`, and `discard(x)` (`BIP007-D8`/`D9`) have `pure deterministic nothrow` profiles with `borrow` parameters that accept any type including `frozen T` and `unique` locals without consuming `unique` or unwrapping `frozen`.

#### Axis 2 — Evaluation & Safety Guarantees
* **`deterministic` (Functional dependence on visible inputs):**
  * The result (and the post-states of `mut` arguments/`this`) is a function of the **observable state of the graphs reachable from the arguments, `this` and captured variables at the time of the call**, and of nothing else. Object *identity* is not an input: two calls on equal states give equal results, and a call returning a new object returns a structurally equal, **distinct** object each time.
  * Consequently `deterministic` alone does not license reuse of a result. Reuse needs the *state* to be the same and the *result* to be shareable: see the conditions in §5.3.1 (deeply immutable arguments for memoization and folding, no intervening write for CSE, deeply immutable result in all cases).
  * Identity-derived values (`System.identityHashCode`, the inherited `Object.hashCode`/`Object.toString`, the default `hashCode` of a non-value Babble instance) differ between runs: reading one is `pure` but **not** `deterministic`.
  * Forbids calling non-`deterministic` functions (`System.nanoTime()`, `Math.random()`), reading non-`final` Java statics, reading reassigned/mutable outer bindings, or depending on unordered hash iteration.
  * *Why separate from `pure`?* A function reading an external config file (`axiom pure Files.readString`) or clock (`axiom pure Instant.now`) mutates nothing (`pure`), yet is **not** `deterministic` across time—so optimizers may reorder unused calls or discard them (if `nothrow`), **but must not memoize or CSE them across time**.
* **`nothrow` (Exception-freedom):**
  * Guaranteed never to throw a runtime error or exception (`assert` / `suppose`, failing `as` cast, non-null assertion `e!`, narrowing scalar conversions like `int(d)` / `int("42")`, Java boundary check failure such as `JavaTypes.checkNotNull` / generic `TypeVariable` return, or throwing Java callee; note that Babble has no `throw` statement, `a / b` is non-throwing IEEE-754 `float`/`double` division, and non-nullable `T` prevents null dereference on `a.x` while `a?.x` short-circuits on `null`).
  * Essential for **speculative evaluation**, **hoisting out of guarded branches** (`if a != null then ( f(a) )`), and **reordering short-circuiting predicates**.
  * With BIP008 (`BIP008-P5`), Babble-to-Babble calls have no runtime entry `Types.check`, so calling a `nothrow` Babble function is unconditionally `nothrow`.
* **`@TERMINATING` *(`BIP007-P2` / optimizer flag, or combined as `@TOTAL` = `nothrow` + `@TERMINATING`)*:**
  * Guaranteed to finish in finite steps (no unbounded `while` loops or unguarded recursion). Required only when dropping dead calls whose infinite loop would otherwise prevent termination. Inferred in `EffectChecker` for functions whose body contains no `while` loops and no recursive calls (`recurse` or same-SCC callees) and calls only `@TERMINATING` callees.

#### Axis 3 — Algebraic & Relational Properties
* **`idempotent`:**
  * Has two distinct forms distinguished by the callable's signature (*Signature applicability rules* below):
    * **Value projection form** (`pure` unary endofunction `fn (T): T`): $f(f(x)) = f(x)$ (e.g., `abs`, `String.trim`, canonicalizers). Enables $f(f(x)) \to f(x)$ elimination.
    * **Effect / retry form** (`mut` or effectful `axiom` function): executing the call twice consecutively with the same arguments produces the exact same observable **post-state** as calling it once, **ignoring the return value** (e.g., `Files.createDirectories`, `set.add`—which returns `true` then `false`—`latch.set()`, `upsert`). Enables **safe automatic retries** and duplicate suppression when the return value is unused.
  * *Important:* `idempotent` is orthogonal to `commutative` (`map.put(k, v)` is `idempotent` without being `commutative`; `counter.add(n)` is `commutative` without being `idempotent`).
* **`commutative`:**
  * **Value form** (`pure` binary `fn (T, T): R` with two parameters of the same type `T`): reordering arguments yields the same result, $f(a, b) = f(b, a)$ (e.g., `int +`, `long +`, `min`, `max`, `==`). Enables canonical operand ordering and argument-order-independent CSE.
  * **Effect form** (`mut` method on receiver `S` or function taking `mut target: S`): two calls on the same `mut` target with different arguments produce the exact same observable **post-state**, $s.f(a); s.f(b) \equiv_{\text{state}} s.f(b); s.f(a)$, **ignoring return values** (e.g., `set.add(k)`, `counter.add(k)`). Together with `idempotent` for retries, enables **order-independent, retry-safe accumulation**.
* **`associative`** *(binary combiners `fn (T, T): T`)*:
  * $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ (e.g., `int`/`long` `+`, `*`, `min`, `max`, `string +`, list concat). Enables **tree reductions**, loop reassociation, and partition merges.
  * *(`BIP007-D3`: `associative` takes no `identity(...)` clause in `BIP007-P1`; reductions supply explicit seeds as in §8.4, with optional monoid identity deferred.)*
* **`monotone`** *(order-preserving unary or first-arg mapping)*:
  * $x \le y \implies f(x) \le f(y)$ over the whole declared domain (e.g., `float`/`double` `x + k`, `x * k` for `k >= 0`, widening scalar conversions, monotonic timestamp/ID buckets). **Wrapping `int`/`long` arithmetic is not monotone** (`x + 1` wraps at the maximum), so `int`/`long` `x + k` and `x * k` never get the property; see §4.6. Enables **zone-map / min-max range pruning** and pushing range predicates ($f(x) > c$) down into storage scans.
* **`injective`:**
  * $x \ne y \implies f(x) \ne f(y)$ (one-to-one mapping, e.g., bijective encodings, lossless wrappers `UserId(id)`, wrapping `int`/`long` `x + k`, wrapping `int`/`long` `x * k` for **odd** `k` only, exact widening conversions). Enables pushing **`distinct`**, **`groupBy`**, and **`f(x) == f(y)`** through projections directly onto underlying keys.
* **Signature applicability rules (`EffectChecker`):** Every algebraic modifier is validated against the declaration's parameter and return types: `associative` requires a `pure` binary `fn (T, T): T` signature; value `commutative` requires a `pure` binary `fn (T, T): R` signature with identical parameter types; effect `commutative` requires `mut` on `this` or a parameter; value `idempotent` requires a `pure` unary `fn (T): T` signature while effect `idempotent` requires `mut` or an effectful `axiom`; `monotone` requires ordered first-parameter and return types (any of the 9 core scalars `byte`..`string`, or Java `Comparable`); and `injective` requires at least one parameter and a return type other than `null`/`void`. Any mismatch is a compile-time `TypeCheckException`.

#### Soundness premise — static typing and external boundaries

Every profile, inferred or verified, is derived assuming the arguments conform to their declared or inferred parameter types. Under BIP008 and RM005 (`BIP011`), that assumption is enforced statically throughout Babble (`TypeChecker`, `BIP008-P5`), with runtime `Types.check` / `JavaTypes.checkNotNull` remaining only at `as` casts, `e!` assertions, and Java boundaries:

* **Typed parameters are statically guaranteed:** Every parameter has a static type (annotated, contextual, default, or call-site inferred via `BIP008-P7`), and `TypeChecker` rejects mismatches before execution. Inside `fn (x: int, y: int) -> ( x + y )`, `x + y` is guaranteed to be integer addition with no runtime entry check.
* **Java boundaries remain checked:** Calls entering Babble from Java interfaces (`Faces` / `RM003` direct Java bridges via `JavaTypes.checkParameters` & `checkNotNull`) and non-null / generic values exiting Java (`JavaTypes.checkNotNull`, generic `TypeVariable`/`WildcardType` returns and fields) are checked at runtime, so un-axiomatized Java calls, `e!`, and `as` casts drop `nothrow`.

### 3.3 Exact Verification vs. `axiom` Matrix

Because static checking faces fundamental computability bounds (Rice's theorem), Babble draws an explicit line between what the compiler **infers/verifies** and what requires **`axiom`**:

| Property | Inferred automatically? | Verified on body (without `axiom`)? | Requires `axiom` for non-trivial cases? |
|---|---|---|---|
| **`pure`** | **Yes** (fixed-point call graph; rejects `mut`) | **Yes** (sound; requires `hermetic` and zero `mut` targets) | Only for Java/FFI calls or benign hidden state (caches) |
| **`hermetic`** | **Yes** (fixed-point call graph; zero globals/I/O) | **Yes** (sound; compatible with `mut`) | Only for Java/FFI calls (`axiom hermetic mut on java.util.ArrayList.add`; `RM005` core collections are pre-registered) |
| **`mut`** (params / `this`) + exclusivity | **Yes** (minimal mutated set on untyped fns + static shallow call-site exclusivity + `hermetic`; deep disjointness only via `unique mut` in `P2`) | **Yes** (sound; mutually exclusive with `pure`, compatible with `hermetic`) | Only for Java methods (`axiom mut on ...` or `axiom hermetic mut on ...`) |
| **`borrow`** (params / `this`) | **Yes** (intra/inter-procedural escape analysis) | **Yes** (sound) | Only for Java methods (`axiom pure borrow on ...`) |
| **`unique`** *(`BIP007-P2`)* (return & moved params) | **Yes** for `unique` local builders, core collections, & returns (deep ownership, §5.2) | **Yes** (definite-unassignment + no shared mutable object stored into the graph) | Needed when Java methods allocate/return isolated graphs (`axiom pure unique on ...`) |
| **`deterministic`** | **Yes** (fixed-point; forbids clocks/RNG/mutable globals) | **Yes** (sound) | Only for benign lazy memoization (`String.hashCode`) or Java |
| **`nothrow`** | **Yes** (conservative: no `assert`/`suppose`, `e!`, failing `as` casts, narrowing conversions, or throwing Java calls) | **Yes** (conservative) | Needed when invariants prevent failure (e.g. bounds-checked Java indexing `list.get(i)` or validated `Integer.parseInt`) |
| **`idempotent`** | Trivial forwarding only | Trivial forwarding / invariant overwrite only | **Yes** (`axiom pure deterministic idempotent on java.lang.Math.abs`, `axiom hermetic mut idempotent on java.util.HashSet.add`) |
| **`commutative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... commutative on ...`) |
| **`associative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... associative on ...`) |
| **`monotone` / `injective`** | **Compositional** ($f \circ g$ preserves `monotone` and `injective`) | **Compositional** over primitive axioms | Base arithmetic/conversions need `axiom` (built-ins pre-seeded per §4.6: no `monotone` for wrapping `int`/`long` arithmetic) |

### 3.4 Subtyping and Effect Lattice

Each property $p$ defines a simple boolean partial order: having $p$ is strictly stronger (more constrained) than lacking $p$:

$$\text{has } p \quad <:\quad \text{may lack } p$$

For a function signature `fn (p1: T1, ..., mut borrow pk: Tk): R` (extended with `unique` in `BIP007-P2`) with effect set $E$:

* **Guarantee covariance (function-level effects, `borrow` params, and `BIP007-P2` `unique` returns):**
  * `actual` satisfies `target` iff $E_{\text{target}} \subseteq E_{\text{actual}}$, `target.borrow(i) => actual.borrow(i)` (promising not to escape satisfies a caller allowing escape), and (in `BIP007-P2`) `target.uniqueReturn => actual.uniqueReturn` (`unique R <: R`).
  * `fn pure deterministic nothrow (borrow x: Point): Path` `<:` `fn pure deterministic (x: Point): Path` `<:` `fn (x: Point): Path` (and `unique` return satisfies non-`unique` return in `P2`).
* **Mutability & Ownership contravariance-of-permission:**
  * Because `pure` forbids all `mut` parameters and `mut` `this`, `pure` is strictly disjoint from `mut` (`pure` signatures never carry `mut`).
  * Across **all** `Type.Function` signatures (whether or not function-level effect modifiers are present), absence of `mut` on a parameter (or `this`) means that parameter (or `this`) is **not mutated**: `actual` may declare `mut` on parameter $i$ (or `this`) only if `target` also declares `mut` on parameter $i$ (or `this`). Adding an orthogonal modifier like `nothrow` to `fn (x: list of int): int` therefore never changes the meaning of absent `mut`:
    * `fn pure deterministic (x: list of int): int` `<:` `fn deterministic (mut x: list of int): int`, **not** the reverse! (Promising no mutation at all satisfies a caller slot that permits mutating `x` without global effects.)
  * *(In `BIP007-P2`)* On parameters, demanding ownership (`unique p`) is *stricter* on the caller: a callee taking an ordinary non-`unique` `fn (p: T)` satisfies a slot expecting `fn (unique p: T)` (`fn pure (p: Vec) <: fn pure (unique p: Vec)`), **not** the reverse!
* **Unconstrained vs. Constrained Function Types:** An **unconstrained** function type `fn (x: T): R` (empty effect set, no `mut`/`borrow`—or `unique` in `P2`) accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects, while callers receive no optimization or aliasing guarantees; mutating parameter $i$ still requires `fn (mut x: T): R` uniformly across all `Type.Function`s. Under BIP008, `any` / `any?` is never assignable to any `Type.Function` (constrained or unconstrained), and `Type.Function` is non-nullable (`null` is only accepted by `(fn (x: T): R)?`). Inline lambdas and un-reassigned `def`s obtain their concrete `Type.Function` and `EffectSet` via bidirectional local type inference.

---

## 4. Syntax, Semantics, and Language Rules

### 4.1 Syntax in `Babble.g4`

Effects compose naturally after `fn` on function literals and function types, before parameter names, and before `on` in external Java declarations (`RM006` / `BIP012`):

```babble
;; Host axioms declared before use (within a file, EffectChecker collects axioms order-independently in a pre-pass):
axiom pure deterministic nothrow on java.lang.Math.sqrt
axiom pure deterministic on java.util.HashMap()         ;; Class() targets public constructors only (leaving put/clear untouched)
axiom hermetic mut deterministic on java.util.HashMap.put

;; 1. Verified function literals (compiler proves all listed properties):
def hypot = fn pure deterministic (a: double, b: double): double -> (
    java.lang.Math.sqrt(a * a + b * b)
)

;; 2. Parameter & receiver mutability (`mut` — never combined with `pure`), `borrow`, and (`BIP007-P2`) `unique`:
def appendAll = fn deterministic (mut dst: list of string, borrow src: list of string) -> (
    ... ;; `dst` is mutated; `src` is untouched; compiler infers `hermetic`!
    ;; Plain `mut` does not guarantee deep disjointness: `dst` and `src` may share state. Use `unique mut` (`BIP007-P2`) for that (§8.5).
)

;; `BIP007-P2` unique builder & FBIP (using RM005 core collections with built-in hermetic mut profiles — zero axiom boilerplate!):
def buildIndex = fn pure deterministic (n: int): unique map of (int, int) -> (
    def m = map of (int, int) ()
    ... ;; calling built-in `hermetic mut deterministic` `m.put(i, i * i)` on fresh local `m` stays `pure deterministic`!
    ... ;; `m` stays unique because only scalars (deeply immutable) are stored into it
    m
)

def sortInPlace = fn deterministic (unique mut xs: list of int): unique list of int -> (
    ... ;; mutates owned `xs` in place (zero globals/I/O) and returns unique ownership (FBIP)
    xs
)

def Counter = class (def count: int = 0) (
    def peek = fn pure deterministic (): int -> ( this.count )
    def add  = fn mut deterministic (delta: int) -> ( this.count = this.count + delta )
)

;; 3. Unannotated functions (fully inferred by the compiler!):
def square = fn (x: int): int -> ( x * x )   ;; inferred: pure deterministic nothrow (`x` is statically `int`, §3.2)

;; 4. Axiomatic declarations (trusted contract, no proof checked on body/target; declaration-level only per `BIP007-D4`):
def fastFib = fn axiom pure deterministic (n: int): int -> ( ... )
axiom hermetic mut idempotent commutative on java.util.HashSet.add

;; 5. Explicit opt-out (suppresses inference / opts out value defaults):
def debugLog = fn impure (msg: string) -> ( ... )
impure on java.net.URL.equals

;; 6. Function types & (`BIP007-P2`) ownership handoff (`move(...)` / `freeze(...)` / `discard(...)`):
def mapInts = fn (items: list of int, f: fn pure (x: int): int): list of int -> ( ... )
def reduceInts = fn (items: list of int, combine: fn pure deterministic associative (a: int, b: int): int): int -> ( ... )
def frozenIndex = freeze(buildIndex(1000))      ;; (`BIP007-P2`) zero-copy conversion; type `frozen map of (int, int)`
def sortedCopy = fn (n: int): unique list of int -> (
    def myList = makeList(n)                    ;; (`BIP007-P2`) unique local (ownership is tracked for locals only)
    sortInPlace(move(myList))                   ;; (`BIP007-P2`) transfers ownership; `myList` unusable afterward
)
```

**Grammar additions (`Babble.g4`) & canonical formatting (`Formatter`):**
* **`BIP007-P1` (`RM002`, migrated to predicate-free LL(1)/LL(2) in `RM006` / `BIP012`):**
  * Zero `@parser::members` and zero semantic predicates: all 13 effect keywords (`axiom`, `impure`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`) plus `fn` and `on` are real lexer tokens, exempted after `.`/`?.` in `memberName` (and in named call arguments `memberName ':' expression`).
  * `# functionLiteral`: `FN effectPrefix? fnParameters (':' type)? '->' NL* functionBlock=block`.
  * `paramModifier`: `MUT | BORROW` (used in `classParam`, `fnParam`, and `fnTypeParam`).
  * `# functionType`: `FN effectPrefix? fnTypeParameters (':' type)?` (note: `axiom` and `impure` are rejected inside function types—`axiom` is a definition-site mechanism, never part of a caller-visible type!).
  * `effectDeclaration`: `EXPORT? effectClause ON effectTarget (ctorOpen='(' ctorClose=')')?` (targeting Java methods, constructors via `Class()`, or bare classes at declaration granularity per `BIP007-D4`; `def C = axiom ... on import("...")` uses `(effectClause ON)?` on `# defExpression`). Inline `axiom ( ... )` expression blocks are forbidden (`BIP007-D4`).
  * `Formatter` follows Babble's canonical style (`fn pure (mut x: T): R -> (...)`, `axiom pure on java.lang.Math`, and tight `()` on `Class()` targets).
* **`BIP007-P2` additions (`BIP007-D5`, preserving zero semantic predicates & pure `SLL`):**
  * Only **two** new lexer tokens: `UNIQUE: 'unique';` and `FROZEN: 'frozen';`, both added to `memberName` so `.unique` and `.frozen` remain valid member and named-argument names.
  * **`move(x)`, `freeze(expr)`, and `discard(expr)` are built-in functions** (regular `ID`s like `same`/`classof`, not reserved keywords)—requiring zero new expression grammar rules!
  * Extend `paramModifier: MUT | BORROW | UNIQUE;`.
  * Extend `# functionLiteral` and `# functionType` return clauses to `(':' UNIQUE? type)?` (keeping `unique` strictly on parameter/return slots, never in general `type` positions).
  * Extend `nonParenNullableType` with `FROZEN?` prefix on `# simpleType` (and `FROZEN? '(' NL* type NL* ')' question='?'?` on `# parenType`): under `RM006`'s `BIP012-D2` rule (`?` binds tighter than `of` on bare single type args), `frozen list of int?` parses as `frozen (list of (int?))`, `frozen MyRecord?` parses as `frozen (MyRecord?)` which distributes to `(frozen MyRecord)?`, and `(frozen list of int)?` is a nullable frozen list.

> **Syntax order:** After `fn`, `axiom` (when present) appears first before one or more effect modifiers (requiring at least one modifier or `mut` parameter); `impure` stands alone and cannot combine with `axiom` or positive effects; **`pure` and `mut` (on receiver or parameters) cannot be combined**. Function-level `effectModifier`s (`pure`, `deterministic`, etc.) and parameter modifiers (`(mut | borrow)*` in `P1`, `(mut | borrow | unique)*` in `P2`) may each appear in arbitrary order and are normalized into an `EffectSet` and `Type.Parameter` flags (`unique borrow` is an exclusive borrow; plain `unique` consumes).
>
> **Pure `SLL` guarantee:** Because `fn`, `on`, `unique`, and `frozen` are real lexer tokens (exempted in `memberName`) and `move`/`freeze`/`discard` are built-in functions, `Babble.g4` stays 100% predicate-free LL(1)/LL(2) and continues to pass `ParserGrammarTestCase`'s zero-`@parser::members`, zero-predicate, pure-`PredictionMode.SLL` checks.

### 4.2 Local State, Unique Objects, Exclusivity, Ownership, and Outer State

Inside any function whose checked or inferred effect includes **`pure`** (and across call sites checking `mut` / `borrow` / `unique`):

1. **Local variables (`def x = ...`) may be reassigned freely** inside the function (`def i = 0; i = i + 1`). Local stack state is invisible outside the call.
2. **Fresh local object & collection mutation (`unique`, `BIP007-P2`):**
   * *In `BIP007-P1` (`RM002`):* Before `unique` ownership lands, mutating any object (even a freshly constructed local such as `def tmp = Counter(0); tmp.add(1)`) is disallowed inside `pure`/`hermetic` functions, while local `def` variable reassignments remain valid.
   * *In `BIP007-P2` (`BIP007-D6`):* A local `def` is **`unique`** if its initializer (and any reassignment) creates a new instance via a `pure` constructor, a `unique`-returning call, an `object (...)` literal, or an `RM005` core collection constructor (`list`/`set`/`sortedset`/`map`/`sortedmap`) **whose arguments are all `unique` (moved in) or deeply immutable** (with `from:` copying deeply immutable elements and `comparator:` being `pure deterministic`), and it stays `unique` while everything stored into it is `unique` or deeply immutable (§3.2). Mutating a `unique` local via `hermetic mut` helpers or built-in core-collection mutators (`xs.add(1)`, `m.put(k, v)`) does **not** require `mut` and does **not** break `pure` or `deterministic`. Only `unique` locals can be moved, frozen, or returned as `unique`; objects read out of a `unique` local belong to its owned graph and may be mutated in place, but storing them anywhere else (or storing a `@PARAM` / `@EXTERNAL` value into the local) demotes the local to `@EXTERNAL`.
3. **Parameters and `this` without `mut` are strictly unmutated:**
   * `this.x = ...`, `param.x = ...`, and calling any `mut` method `param.mutMethod()` are **rejected** unless `mut` is declared on `this` or `param` (with `mut` inferred only on completely untyped functions having no effect modifiers, no parameter `: Type`, and no `: R` return type).
   * Aliasing a non-`mut` parameter (`def alias = param; alias.x = 1`, or derived sub-references `def c = param.child; c.x = 1`, or `RM005` collection views like `m.values()` / `xs.subList(...)`) is rejected in `BIP007-P1` / `RM005` by local `@PARAM`/`@THIS` alias tracking in `EffectChecker`.
4. **Call-Site `mut` Exclusivity (`BIP007-D9`):**
   * At any call passing an expression `arg` to a `mut` parameter (or calling a `mut` method `arg.m(...)`), `arg` cannot be the same object as any other argument (or `this`) passed to the same call.
   * Checked purely statically in `EffectChecker`: rejects same bindings, tracked local aliases (`def y = x; f(x, y)`), and identical member paths (`a.b.m(a.b)`), while permitting distinct locals/expressions (`f(xs.get(i), xs.get(j))`) and immutable `string` receivers (which never require `mut`). There is no runtime `Values.same(a, b)` exclusivity fallback (`BIP007-D9`).
   * This check is shallow and gives **no deep non-aliasing guarantee** (§3.2). Deep disjointness comes only from `unique mut` parameters (`BIP007-P2`), which need no check: the argument must be a `unique` local, which cannot also appear as another argument, be captured, or be reachable from a global.
5. **Borrow Escape (`BIP007-P1`) & Ownership Transfer (`BIP007-P2`):**
   * Inside a function declaring `fn (borrow p: T)`, assigning `p` (or any mutable sub-reference of `p`) to fields of `this`, another parameter, an outer/global variable, a returning closure, or returning `p` directly is rejected at compile time (`BIP007-P1`).
   * *(`BIP007-P2`):* Passing an argument to a `unique` parameter, passing it to `freeze(...)`, or returning a `unique` local consumes ownership via `move(expr)` (inserted implicitly when the compiler proves it is the variable's last use). Definite-unassignment analysis rejects any subsequent read or write to a moved variable. Passing it to a `unique borrow` parameter is an exclusive borrow instead: nothing is consumed. Only function-local bindings that no closure captures can be `unique` and moved; top-level, module, static and captured bindings are `@EXTERNAL`, and `move(...)` on them is rejected (`Cannot move x : not a local variable`).
6. **Outer variables and global mutable singletons:**
   * **Writing** any outer `def`, mutable `object` singleton field, or `static` field is forbidden in `pure` and `hermetic` (clears `hermetic`), except when a frame-local (`borrow`) helper closure reassigns an unescaped local `def` (`BIP007-P1`) or mutates a `unique` local (`BIP007-P2`) of its enclosing function `F` (§4.5).
   * **Reading** a reassigned outer `def`, non-`final` Java static field, or mutable outer singleton/field (even reached transitively through an outer `record`) performs no observable write: it stays valid in **`pure`**, but is forbidden in **`deterministic`**.
   * Reading immutable outer `def`s (never reassigned and deeply `record` / `entry` / `frozen` / scalar / `pure` function) is valid in both `pure` and `deterministic`.

### 4.3 `record`s, `object` Singletons, `static` Blocks, and Standard Value Protocol

1. **`record` defaults (formerly `value class`, renamed in `RM006` / `BIP012-D6`):**
   * A `record` constructor is **verified `pure deterministic`** (its parameter defaults and top-level `classBlock` expressions must satisfy `pure deterministic`; fields are read-only at runtime in `BabbleInstance.setMember`).
   * Unannotated instance methods of a `record` use **Inferred Mode** (§3.1)—they automatically infer `pure deterministic` (and `nothrow` where applicable) when their body avoids I/O and mutable globals, while unannotated methods that call `println` or perform Java I/O silently infer unconstrained rather than failing compilation, preserving backward compatibility for unannotated scripts (§1.3). Explicitly annotated methods on a `record` are verified normally.
   * Note: Because `record` instances may hold references to mutable objects passed in at construction time, transitivity checks inspect field types when deciding whether the built-in structural `hashCode`/`toString` are `deterministic`: they hash and print the fields, so they are `deterministic` only for a deeply immutable `record` (a field holding a mutable instance contributes an identity hash).
2. **Standard Value Protocol (`equals`, `hashCode`, `compareTo`, `toString`):**
   * Babble's `==` and `!=` (`Operators.equal`) use structural field equality on `record`s, object identity on regular `BabbleInstance`s, and `Object.equals` on Java objects—never invoking a user-defined Babble `equals`, `hashCode`, or `compareTo` method. Ordering operators (`<`, `<=`, `>`, `>=`) operate on core scalars (`byte`..`string`), or Java `Comparable` instances of the same class. Only `toString` is user-definable on Babble classes (`string + x` invokes `BabbleInstance.toString()`, which delegates to a 0-arg `toString` method if defined).
   * Consequently, on **Java classes**, `equals` and `compareTo` **default to `pure deterministic`** (while Babble's built-in `record` structural equality and `BabbleInstance` identity equality are intrinsically `pure deterministic nothrow`), and immutable **`string` (`java.lang.String`) receivers never require `mut`**.
   * On Java `hashCode` and Java/Babble `toString`, defaults are **`pure deterministic` only where they do not derive from object identity**: on a Java class that overrides them (declaring class other than `java.lang.Object`), on a deeply immutable `record`, and on a Babble class defining a verified/inferred `pure deterministic` `toString`. The inherited `Object.hashCode`/`Object.toString` and the default ones of a non-record `BabbleInstance` are identity-derived and differ between runs: they default to **`pure` only**, so nothing that reaches them is folded at compile time or memoized.
   * Explicit `impure on java.net.URL.equals` / `impure` opt-out stays available when a Java override performs DNS or mutates external state.
   * For a `record`, `EffectChecker` determines whether the built-in structural `toString()` / `hashCode()` is `pure deterministic` or `pure`-only by inspecting the declared field types statically (deeply immutable `record` $\to$ `pure deterministic`; fields that may hold mutable instances $\to$ `pure` only; fields whose own `toString` may be unconstrained $\to$ unconstrained). No runtime effect propagation inside `BabbleInstance.toString()` is needed.
3. **Inline `object (...)` and `static` blocks:**
   * An `object (...)` expression or nested `class`/`interface` `static` block executed inside a `pure` or `deterministic` function is checked under the enclosing function's active constraints (cannot secretly print or mutate globals during class initialization).

### 4.4 Interfaces, Overrides, and Covariance

Interfaces can require arbitrary effect & algebraic contracts on members and default methods:

```babble
def Reducer = interface (
    def zero: fn pure deterministic nothrow (): int
    def combine: fn pure deterministic nothrow associative commutative (a: int, b: int): int
    def mergeInto: fn mut commutative (delta: int)
)
```

* Implementing or overriding an interface member must satisfy `actual <: required` across both `EffectSet` and `mut` permissions.
* Example: `fn pure deterministic` cannot satisfy an interface expecting `fn pure deterministic associative commutative`, whereas `fn axiom pure deterministic nothrow associative commutative` **does**.
* `fn pure deterministic ()` (non-`mut` `this`) CAN implement a `mut` slot `fn mut deterministic ()` (stronger guarantee satisfies weaker caller requirement), while `fn mut ()` CANNOT implement `fn pure ()`.

### 4.5 Nested Functions and Closures

Every function literal creates its own effect context (`inferred`, `verified`, or `axiom`):

* **Constructing a less-constrained closure inside a `pure deterministic` function** (e.g., returning an `impure` logger closure `fn () -> ( println(msg) )`) is `pure deterministic` itself (building the closure has no side effects and captures immutable `msg`), **provided** the inner closure does not capture a `mut` mutation of the enclosing function's non-`mut` parameters or `this`.
* **Calling** that closure inside the enclosing `pure deterministic` function requires its effect profile to satisfy the enclosing requirement (`actual <: enclosing`).
* **Frame-local (`borrow`) helper closures:** When a closure literal defined inside function `F` never escapes `F` (it is only invoked directly inside `F` or passed to `borrow` parameters within `F`), any reads or reassignments of `F`'s otherwise-uncaptured local `def`s (`BIP007-P1`, plus mutations of `F`'s `unique` locals in `BIP007-P2`) performed by that helper closure are attributed to `F`'s own local frame rather than `@EXTERNAL` outer state—allowing `F` to use local helper closures (such as `countWhere` over local loop accumulators) and stay `pure deterministic`.
* **Higher-order helpers & bounded call-site specialization:** Rather than introducing Koka-style effect-row variables into Babble's type signatures, `EffectChecker` evaluates higher-order Babble helpers whose effects depend on a parameter call `f(...)` (e.g., `def apply = fn (f, x) -> ( f(x) )`) via bounded call-site specialization for statically resolved concrete Babble functions and methods (intra-file definitions and statically imported module exports, excluding `impure` callables and abstract interface dispatches) using the caller-supplied `Type.Function` or contextual lambda arguments.

### 4.6 Built-ins, Operators, Core Types, and Numeric Semantics (`RM005` + `RM006`)

| Built-in / Operator | Intrinsic Effect Profile | Notes |
|---|---|---|
| `same(a, b)`, `classof(v)`, `x is T` | `pure deterministic nothrow` (`borrow` params) | Pointer/class/type inspection only (`same` is also `commutative`; `x is T` takes a static `type` per `RM006` and establishes flow-sensitive `Narrowing`; `same` and `classof` accept `frozen T` and `unique` locals without consuming or unwrapping them, `BIP007-D9`). |
| `move(x)`, `freeze(expr)` *(`BIP007-P2`, `BIP007-D5`)* | `pure deterministic nothrow` | Built-in functions (not keywords); zero-cost identity no-ops at runtime. `move(x)` transfers `unique` ownership of local `x`; `freeze(expr)` transitions a `unique` graph to `frozen T`. |
| `discard(expr)` *(`BIP007-P2`, `BIP007-D8`)* | `pure deterministic nothrow` (`borrow` param), returns `void`/`null` | Built-in function accepting any type (including `any?`, `frozen T`, and `unique` locals without consuming or unwrapping them); explicitly opts out of the unused `pure nothrow` compile error. Zero-cost no-op at runtime. |
| `e!` (`nonNullAssertion`, `RM005`) | `pure deterministic` | **Not `nothrow`** (throws on `null`, unless `Narrowing` already proved `e` non-null). |
| `assert(cond, msg?)` / `suppose` | `pure deterministic` | **Not `nothrow`** (throws on false); arguments checked under enclosing mode. |
| `print(...)`, `println(...)`, `import(...)` | Unconstrained (`impure`) | I/O / module loading side effects (static `import("...ba")` still resolves `ModuleInterface` types at compile time). |
| Scalar conversions (`byte(x)`..`string(x)`, `RM005`) | Widening: `pure deterministic nothrow`; Narrowing / string-parsing: `pure deterministic` (not `nothrow`) | Exact widenings (`byte`→`short`→`int`→`long`, `byte`/`short`/`int`→`double`, `byte`/`short`→`float`) are also `monotone injective`; lossy widenings (`int`→`float`, `long`→`float`/`double`) are `monotone` only. Narrowing (`int(d)`, `byte(n)`) and string parsing (`int("42")`) drop `nothrow`. |
| `int` / `long` `+`, `*` (incl. `byte`/`short` promoted to `int`) | `pure deterministic nothrow associative commutative` | Two's-complement wraparound is associative and commutative, but **not `monotone`** (overflow wraps). `x + k` is `injective`; `x * k` is `injective` only for odd `k`. |
| unary `-x`, binary `a - b` (`byte`/`short`/`int`/`long`) | `pure deterministic nothrow` | Not `associative` or `commutative`; not `monotone` (wraps); unary `-x` and `x - k` are `injective`. |
| `float` / `double` `+`, `*`, unary `-x`, binary `a - b` (and mixed promotions) | `pure deterministic nothrow` (`+`, `*` are `commutative`) | **Not `associative`** (IEEE-754 rounding). `x + k`, `x - k`, and `x * k` (`k >= 0`) are `monotone` (not `injective`). |
| `a / b` (integer or floating-point) | `pure deterministic nothrow` | Single-precision IEEE-754 `float` when both operands are `float` (or narrower + `float`), else IEEE-754 `double` (`1 / 0 == Infinity`); never throws. |
| `string +` (both operands `string`) | `pure deterministic nothrow associative` | **Not `commutative`**; not `injective` (`"ab" + "c" == "a" + "bc"`). |
| `string + non-string` / `non-string + string` | Inherits static `toString()` profile of non-string operand | `pure deterministic nothrow` for all 9 scalars/deep `record`; `pure deterministic` for overridden Java `toString` or verified Babble `toString`; `pure` for non-record `BabbleInstance`/`Object.toString`; unconstrained for `any`/`any?` or unconstrained `toString`. |
| `==`, `!=` | `pure deterministic nothrow commutative` on all 9 scalars, `record`s, and `BabbleInstance` identity; Java objects inherit `equals` profile (default `pure deterministic`) | `Operators.equal` uses structural equality on `record`s and identity on regular `BabbleInstance`s (never calling a Babble `equals` method). |
| `<`, `<=`, `>`, `>=` | `pure deterministic nothrow` on all 9 scalars (`byte`..`string`, incl. `char`); Java `Comparable` inherits `compareTo` profile (default `pure deterministic`) | Flip-duality (`a < b <=> b > a`) built into optimizer. |
| `not a`, short-circuit `a and b`, `a or b` | `pure deterministic nothrow` (when `bool` operands are `pure deterministic nothrow`) | `a and b` / `a or b` only evaluate `b` conditionally; `b` is checked under flow-sensitive `Narrowing` (`!= null`, `is T`), establishing `nothrow` on guarded branches without allowing optimizers to reorder `b` ahead of guard `a` (`BIP007-D1`). |
| Member selection `a.x` / `a?.x` | `pure deterministic nothrow` | Under BIP008, `a.x` is allowed only when `a` is statically non-nullable `T` (or narrowed via `!= null` / `is T` per `BIP007-D1`), and `a?.x` handles `T?` by short-circuiting to `null` (and `a?.m(...)` inherits `m`'s effects). |
| Core collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, `RM005`) | Constructors: `hermetic deterministic` (`unique` in `P2` per `BIP007-D6`); Readers: `hermetic deterministic borrow`; Mutators: `hermetic mut deterministic` (`set.add`: + `idempotent commutative`, `map.put`: + `idempotent`); `entry`: `pure deterministic nothrow` | Built-in `BIP011-P5` profiles (`EffectChecker`); view methods (`subList`, `keySet`, `values`, `entrySet`) propagate receiver alias origin so mutating a view requires `mut` on the backing collection. |

---

## 5. Inference Engine, Verification, and Future Optimizer Contracts

### 5.1 Fixed-Point Effect Inference (Call-Graph SCC in `EffectChecker`)

To minimize annotation burden, Babble maximizes inference across unannotated functions in the active compilation unit:

1. **Call-Graph Construction:** Build a static call graph over all functions, methods, constructors, closures, and `record` bodies in `EffectChecker` (reading converged types and narrowed occurrences from `TypedProgram`, `RM004` / `BIP010`, alongside `BIP008`'s bidirectional local type inference, call-site parameter inference `BIP008-P7`, `of` generics instantiation, and flow-sensitive `is`/`!= null` `Narrowing`—where every expression `Type`, `ClassShape`, and static module `TypedModuleSummary` is statically known).
2. **SCC Condensation (Bottom-Up Fixed Point):** Partition into Strongly Connected Components (handling direct and mutual recursion in `recurse` or mutually recursive functions).
3. **Optimistic Initialization (per unannotated node):**
   * *(`BIP007-P1`):* Start with `{hermetic, deterministic, nothrow}`, `mutParams = {}`, `borrowParams = allParams`, `mutThis = false`, `borrowThis = true`. (`pure` holds after convergence iff `hermetic && mutParams.isEmpty() && !mutThis`.)
   * *(`BIP007-P2` additions):* `uniqueReturn = true` and `@TERMINATING`.
   * Functions explicitly marked `impure` start and stay at `{}` (`hermetic = false`, `borrowParams = {}`, `borrowThis = false`; and `uniqueReturn = false` in `P2`).
   * Functions marked `axiom` are initialized directly to their declared `axiom` `EffectSet` / parameter flags (`axiom pure ...` implies `hermetic`, and `axiom hermetic mut ...` sets `hermetic`) and excluded from downward weakening.
4. **Transfer & Monotone Fixed-Point Iteration:**
   * **`mut` inference scope:** `mutParams` and `mutThis` are inferred **only** on completely untyped functions (no effect modifiers, no parameter `: Type`, and no `: R` return type). On any function carrying effect modifiers, any parameter type, or `: R`, `mutParams`/`mutThis` stay fixed to explicitly declared `mut` annotations, and mutating an unannotated parameter/`this` is rejected immediately as a verification error.
   * **Field writes / calls to `mut` / unconstrained callees:** In `BIP007-P2`, if target is `unique`, keep `hermetic` and `mutParams` unchanged (provided the called `mut` method/helper or built-in core-collection mutator itself has `hermetic`!). In `BIP007-P1` (before `unique`), mutating any object inside a `pure`/`hermetic` function fails verification (or clears `hermetic` on an unannotated function unless targeting an inferred `mut` param/`this` of a completely untyped function). If target is `@PARAM(i)` or `@THIS`, record mutation of $i$ / `this` (preventing `pure`); if target is `@EXTERNAL` or an unconstrained/I/O Java method, clear `hermetic` (dropping `pure`), `deterministic`, and `nothrow`. Any callback invoked inside a verified `pure` or `deterministic` function must carry a constrained `Type.Function`—which `TypeChecker` enforces statically at all call sites (`any`/`any?` cannot be called or assigned to `Type.Function`, §3.4). Also check static shallow call-site **`mut` exclusivity** across arguments (`BIP007-D9`), and (`P2`) that every argument to a `unique` slot is a `unique` local or fresh `unique` expression.
   * **Finalize `pure`:** After convergence, `pure` is set iff `hermetic && mutParams.isEmpty() && !mutThis` (so `pure` **always** implies `hermetic` and zero external mutation).
   * **Escape & (`P2`) uniqueness transfer:** Storing `@PARAM(i)` (or derived sub-references / `RM005` collection views) into `this`, another parameter, a global, an escaping closure, or returning it removes $i$ from `borrowParams` (and similarly clears `borrowThis` when `@THIS` escapes). *(In `BIP007-P2`)* returning any expression that may alias `@EXTERNAL`, non-`unique` `@PARAM`, or `@THIS` drops `uniqueReturn`.
   * **Reads / non-determinism:** If expression reads a reassigned outer `def` (outside a frame-local `borrow` closure, §4.5), non-`final` static field, mutable singleton field, or calls a non-`deterministic` callee, drop `deterministic`.
   * **Exceptions (`BIP007-D1`):** If expression executes `assert`/`suppose`, a non-null assertion `e!` (not statically proven non-null), a narrowing scalar conversion (`int(d)`, `int("42")`), a runtime-checked `as` cast (not statically proven redundant), a Java boundary check (`JavaTypes.checkNotNull` or a generic `TypeVariable`/`WildcardType` return/field check unless covered by `axiom nothrow`), or calls a non-`nothrow` callee, drop `nothrow`. Guarded dereferences and branches narrowed by `Narrowing` (`!= null`, `is T`) establish `nothrow` on the guarded right-hand side (`BIP007-D1`).
   * **Static parameter conformance:** Babble-to-Babble calls are statically type-checked with zero runtime `Types.check` overhead (`BIP008-P5`), while `Faces` and `RM003` direct Java bridges check `JavaTypes.checkParameters` / `checkNotNull` when Java calls into Babble.
   * **Compositional algebraic propagation:**
     * Composition $f(g(x))$ where both $f$ and $g$ are `monotone` $\implies$ `monotone`; same for `injective`.
     * Single-op wrapper `fn (a, b) -> ( op(a, b) )` inherits `commutative` / `associative` from `op` (and unary `idempotent`).
   * **`@TERMINATING` *(`BIP007-P2`)*:** Dropped if the body contains any `while` loop, calls `recurse` or another function in the same cyclic SCC, or calls any non-`@TERMINATING` callee.
5. **Verification Check (Verified Mode) & Module Export Contract (`BIP007-D2`):**
   * For every function with explicit non-`axiom` annotations, verify `inferred.satisfies(declared)` (including `declared.borrow ⊆ inferred.borrow`, `declared.borrowThis => inferred.borrowThis`, and in `P2` `declared.uniqueReturn => inferred.uniqueReturn`) and `inferred.mutSet ⊆ declared.mutSet`, plus definite-unassignment after `move(...)`/`freeze(...)` and unused `pure nothrow` expression checks (`P2`). Emit targeted diagnostics if any obligation fails—while retaining *extra* inferred properties strictly for intra-module optimization metadata (`ScopeAnalysis`; external callers rely only on the explicitly declared modifiers, §3.1).
   * Per **`BIP007-D2`**, unannotated `export def` exports its inferred `EffectSet` and inferred parameter flags across module boundaries via AST-detached `TypedModuleSummary` (`ModuleInterface`, `RM004`), whereas exports with explicit modifiers or `impure` expose only their declared contract.

> **What is fixed-point iteration?**  
> Because functions can be directly or mutually recursive (`f` calls `g` and `g` calls `f`), a single top-down or bottom-up pass cannot always know every callee's effect summary upfront. Fixed-point iteration solves these cyclic dependencies by treating the call graph as a system of constraints $\mathbf{S} = F(\mathbf{S})$: starting from optimistic initial guesses, a worklist loop repeatedly recomputes each function's summary from its body and callees' current summaries until a full iteration produces no further changes (a **fixed point**, $F(\mathbf{S}) = \mathbf{S}$). Because transfer rules only shrink finite effect sets monotonically (and never re-add dropped guarantees), termination is guaranteed in finite steps—producing the tightest sound summary permitted by the lattice.  
> **Further reading:**
> * [Data-flow analysis (Wikipedia)](https://en.wikipedia.org/wiki/Data-flow_analysis) — overview of iterative equation solving and Kildall's worklist method.
> * [Kleene fixed-point theorem (Wikipedia)](https://en.wikipedia.org/wiki/Kleene_fixed-point_theorem) — order-theoretic guarantee that monotone iteration on a finite-height lattice converges to a fixed point.
> * Anders Møller & Michael I. Schwartzbach, [*Static Program Analysis*](https://cs.au.dk/~amoeller/spa/) (Aarhus University) — accessible textbook covering lattices, monotone frameworks, and worklist solvers.
> * Gary A. Kildall (1973), *"A Unified Approach to Global Program Optimization"*, Proc. ACM POPL — foundational paper introducing lattice-based fixed-point dataflow analysis.

### 5.2 Alias Tracking, Exclusivity, and (`BIP007-P2`) Deep Ownership (`unique`)

`BIP007-P1` tracks local `@PARAM(i)` / `@THIS` aliases, derived sub-references (extended in `RM005` with core collection views), control-flow merge joins, `borrow` escape, and static call-site `mut` exclusivity; `BIP007-P2` adds `unique`, `move(...)`, `freeze(...)`, `discard(...)`, `@MOVED`, and `Type.Frozen`:

```babble
;; Full inference illustration (`BIP007-P2` adds `: unique Stats` local builder mutation & unique return):
;; Note: RM005 already pre-registers `list.size` as `hermetic deterministic nothrow borrow` — no axiom needed!
def Stats = class (def count: int = 0, def total: int = 0) (
    def add = fn mut deterministic nothrow (v: int) -> (
        this.count = this.count + 1
        this.total = this.total + v
    )
)
def buildStats = fn (xs: list of int) -> (
    def s = Stats(0, 0)
    s.add(xs.size())
    s
)
```
Under `BIP007-P2`, `buildStats` has its return type `Stats` inferred by bidirectional local type inference, and automatically infers **`pure deterministic nothrow`**, **`borrow xs`** (`xs` is untouched and never escapes), AND **`unique` return** (`: unique Stats`)—because `s` was freshly constructed inside `buildStats`, `xs.size()` yields a primitive `int` rather than escaping `xs`, and zero aliases to `s` remain in `buildStats`.

Babble tracks these local points-to / ownership states per binding in `EffectChecker` (only for function-local bindings that no escaping closure captures; every other binding is `@EXTERNAL`):
* **`unique` *(`BIP007-P2`, deeply owned)*:** Bound solely to a `pure` constructor, `object` literal, `RM005` core collection constructor (`list`/`set`/`sortedset`/`map`/`sortedmap`, `BIP007-D6`), `unique` return, or `unique` parameter whose arguments are `unique` (moved in) or deeply immutable, and everything stored into it so far was `unique` (moved in) or deeply immutable. Calling a `hermetic mut` helper/method (or built-in core-collection mutator) on it leaves `hermetic` and `mutParams` untouched (so the caller stays `pure`!). Only a `unique` binding may be passed to `move(...)`, a `unique` slot or `freeze(...)`, or returned as `unique`. Storing a `@PARAM`, `@EXTERNAL`, or `any`/`any?` value into it (at construction, via field write, or as an argument a Java method may retain) demotes it to `@EXTERNAL` (or `@PARAM`-tainted) for the rest of the function. *(In `BIP007-P1`, freshly allocated locals default to `@EXTERNAL` so alias tracking catches `@PARAM`/`@THIS` bypasses without allowing object mutation in `pure`/`hermetic` yet.)*
* **`@PARAM(i)` / `@THIS` *(`BIP007-P1`)*:** Aliases parameter $i$ or `this`. Any mutation checks or taints $i$ / `this` for `mut` (forbidding hidden mutation bypasses like `def a = p; a.x = 1`); escaping it into `this`, another parameter, an outer variable, an escaping closure, or the return value clears `borrow(i)` (or `borrowThis`).
* **Sub-reference derivation (`p.child`, `xs.get(0)`, `m.values()`) *(`BIP007-P1` / `RM005` / `P2`)*:** Reading a non-deeply-immutable field, element, or collection view from `@PARAM(i)` or `@THIS` inherits `@PARAM(i)` / `@THIS` on the derived value, so mutating or escaping `p.child` is tracked back to parameter $i$ / `this` (forbidding hidden mutation through aliases of non-`mut` parameters). *(In `BIP007-P2`)* reading a field from `unique` stays inside that owned graph (mutating it preserves `unique`, while storing it externally demotes the root to `@EXTERNAL`).
* **`@FROZEN` *(`BIP007-P2`)*:** Any binding whose type is deeply immutable (`frozen T` from `freeze(expr)` in `P2`, or deep `record`, deep `entry of (K, V)`, all 9 core scalars, `null`, or unions/nullables thereof). Unlike the other states this one is carried by the type, so it holds for parameters, fields and top-level `def`s too.
* **`@MOVED` *(`BIP007-P2`)*:** After `move(x)` or `freeze(x)` (including implicit last-use move), `x` transitions to `@MOVED`; definite-unassignment rejects any subsequent read or write to `x`.
* **`@EXTERNAL` *(`BIP007-P1`)*:** Any reference stored to or loaded from top-level, module, static, or escaping-closure bindings, returned from an unconstrained call, or demoted from `unique` becomes `@EXTERNAL` (cannot be mutated in `pure`/`hermetic` or returned as `unique`).
* **Control-flow merge join ($\sqcup$):** At `if`/`else` joins and loop back-edges, local alias/ownership states join monotonically:
  * *(`BIP007-P1`)* `@PARAM(i)` $\sqcup$ `@PARAM(j)` = `@PARAM({i, j})` (subsequent mutation or escape taints all potentially aliased parameters); joining `@EXTERNAL` preserves `@PARAM(i)` tainting.
  * *(`BIP007-P2` additions)* `unique` $\sqcup$ `unique` = `unique`; `@MOVED` $\sqcup$ *any state* = `@MOVED` (a binding moved on either branch cannot be used after the merge until reassigned); mixing `unique` with `@PARAM(i)` / `@EXTERNAL` yields `@EXTERNAL` while preserving `@PARAM(i)` tainting.
* **Call-site `mut` exclusivity (`BIP007-D9`):** At `callee(a0, a1, ...)`, every `mut` argument `ai` must be a different object from every other `aj` and `this`. Checked purely statically in `EffectChecker`: rejects same bindings, tracked local aliases (`def y = x; f(x, y)`), and identical member paths (`a.b.m(a.b)`), while allowing distinct expressions (`f(xs.get(i), xs.get(j))`) and immutable `string` receivers. There is no runtime `Values.same(a, b)` fallback; only a `unique` argument in a `unique mut` slot guarantees deep disjointness (§3.2).

### 5.3 Source Diagnostics (`BIP007-P2`) & Future Optimizer Contracts *(Postponed Beyond BIP007)*

In **`BIP007-P1` (`RM002`)** and **`BIP007-P2`**, `EffectChecker` and `LoweringAnalyzer` compute and publish resolved `EffectSet`, `mut`/`borrow`, `unique`, and `@TERMINATING` metadata into `ScopeAnalysis`, `TypedModuleSummary`, and `AnalysisSession`.

* **Unused `pure nothrow` source expressions (`BIP007-P2`, `BIP007-D8`):** When a `pure nothrow` expression (such as `s.trim()` or `a + b`) appears in a discarded statement position in source (`f(x); e`), its result is unused and it has no side effects—almost always a bug. In `BIP007-P2`, `EffectChecker` reports it as a compile-time error (`Unused pure expression`), while built-in **`discard(expr)`** (`pure deterministic nothrow`, `borrow` parameter, returns `void`/`null`) provides an explicit, self-documenting opt-out (`assert`/`suppose` and `e!` are not `nothrow` and remain valid in statement position).
* **Bytecode & pipeline optimizer passes *(postponed to a follow-up optimizer BIP after `BIP007`)*:** `BIP007` delivers the complete static effect/ownership type system, compile-time enforcement, and published `ScopeAnalysis` metadata; the downstream optimizer passes that consume those contracts are deferred to a separate BIP after `BIP007` lands:
  1. **CSE, Memoization & LICM:** Reuse requires `pure deterministic` + deeply immutable result (never reusing `unique` mutable allocations); CSE additionally requires no intervening writes to arguments' reachable state (`commutative` matches `f(b, a)` with `f(a, b)`); memoization/folding require deeply immutable arguments; `idempotent` unary projections collapse `norm(norm(s))` $\to$ `norm(s)`.
  2. **No-Alias (`unique mut`) Local/Field Load Hoisting & Redundant-Load Elimination:** Keyed strictly on `unique mut` (never plain `mut`), keeping `src` fields in JVM locals and hoisting invariant reads across `unique mut dst` writes without runtime alias guards.
  3. **Predicate Push-Down & Push-Up:** Guard-safe filter swap (`pure nothrow`, respecting `BIP007-D1` `Narrowing` guard dependencies), pushing filters below `map`/`join` (`pure deterministic nothrow`, using `monotone` for range inversion and `injective` for equality/`distinct` push-down), and factoring common filters above `union`.
  4. **Zero-Copy Builders (`freeze`) and FBIP Reuse:** `freeze(expr)` is already a zero-cost identity no-op in `BIP007-P2`; specializing `sort(move(xs))` / `xs.map(f)` for in-place reuse (FBIP) builds on `unique` + `move(...)` in the follow-up optimizer BIP.
  5. **Stack Unboxing / Scalar Replacement (`borrow`):** Passing temporary `record`s or closure captures into `borrow` parameters in primitive JVM locals/registers across all 8 `RM005` primitive kinds.
  6. **Reassociation & Optimizer Dead-Expression Elimination:** Reassociating `pure deterministic nothrow associative` chains (`int`/`long` `+`/`*`, `string +`, skipping IEEE-754 `float`/`double`), and eliminating dead `pure nothrow @TERMINATING` expressions left behind by inlining or CSE.

### 5.4 Property-Based Verification of `axiom`s *(`BIP007-P3`, Test / Debug Aid)*

While `axiom` tells the compiler to skip compile-time proof, semantic axioms (`deterministic`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `nothrow`) are ideal targets for **automated property-based testing** in a `babble.checkAxioms` test mode (`BIP007-P3`):

* **Input generators:** `checkAxioms` provides built-in boundary and pseudo-random generators for all 9 `RM005` core scalars (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`) and core collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`) of generator-supported elements, and recursively constructs Babble `record` instances whose fields have generator-supported types; functions with unsupported parameter types either use a test-registered generator or skip fuzzing with a diagnostic.
* For `axiom commutative`: sample pairs $(a, b)$ and assert `f(a, b) == f(b, a)` (or post-state equality on fresh `mut` receivers).
* For `axiom associative`: sample triples $(a, b, c)$ and assert `f(f(a, b), c) == f(a, f(b, c))`.
* For `axiom idempotent`: assert `f(f(x)) == f(x)` (or post-state equality after 1 vs 2 calls on fresh `mut` state).
* For `axiom deterministic`: invoke twice with equal cloned inputs and assert identical outputs.

This turns unchecked mathematical assumptions into continuously fuzz-tested invariants in CI.

---

## 6. Java Interop and Bytecode Analysis

Java methods and classes default to **unconstrained** (`EffectSet.EMPTY`, conservative `mut` assumed on mutable receivers/args), **except**:
1. **Standard value protocol (§4.3.2):** on Java classes, `equals` and `compareTo` default to `pure deterministic`, and so do `hashCode` and `toString` when overridden (while the identity-derived `Object.hashCode`/`Object.toString` default to `pure` only).
2. **Built-in `RM005` (`BIP011-P5`) core-type profiles:** immutable **`string` (`java.lang.String`)** receivers never require `mut` and safe `String` methods (`length`, `isEmpty`, `trim`, `toLowerCase`, `startsWith`, `contains`, `indexOf`, etc.) carry built-in `pure deterministic` profiles (`nothrow` where bounds-free; `charAt`/`substring` drop `nothrow`); **`entry` (`Map.Entry`)** readers carry `pure deterministic nothrow`; and **`list`, `set`, `sortedset`, `map`, `sortedmap`** carry built-in `hermetic deterministic` constructor, `hermetic deterministic borrow` reader, and `hermetic mut deterministic` mutator profiles (plus `idempotent`/`commutative` on `set.add` and `idempotent` on `map.put`, and view sub-reference tracking).
Since `BIP019`, these defaults are no longer in the checker : they are axioms, stated in the stubs of `org.babblelang.axioms` (`java/lang/ObjectAxioms`, `ComparableAxioms`, `StringAxioms`, the collection stubs of `java/util`) and read as the built-in tier, after the program's and the engine's declarations and the Java annotations.
3. All other JDK classes/methods (`Math.*`, `Files.*`, etc.) remain unconstrained until declared with `axiom ... on ...` / `export axiom ... on ...` or registered on the host engine. Any built-in default can be opted out via `impure on ...`.

### 6.1 In-Babble `axiom` and `impure` Declarations (`RM006` `on` Syntax)

```babble
;; Method-level axioms:
axiom pure deterministic nothrow on java.lang.Math.sqrt
axiom pure deterministic nothrow associative commutative on java.lang.Integer.sum
axiom pure deterministic commutative on java.lang.Math.addExact  ;; throws on overflow (not `nothrow`, not associative across overflow)
axiom pure deterministic nothrow commutative on java.lang.Math.max
axiom pure on java.nio.file.Files.readString                 ;; pure read, NOT deterministic!
def mkdirs = axiom idempotent on java.nio.file.Files.createDirectories
axiom hermetic mut idempotent commutative on java.util.HashSet.add

;; Constructor-only axioms (`Class()` marks public constructors without marking mutators like `clear`/`remove` pure):
axiom pure deterministic on java.util.HashMap()

;; Class-level axioms (marks all public constructors and methods; individual methods/constructors can refine):
def BigDecimal = axiom pure deterministic on import("java.math.BigDecimal")

;; Module re-export & per-runtime isolation:
export axiom pure deterministic nothrow on java.lang.Math.sin
export impure on java.net.URL.equals

;; Opting out default-pure value protocol methods:
impure on java.net.URL.hashCode
```

* **Compile-time resolution & cross-module/session contracts (`BIP007-D2`, `BIP007-D4`):** `EffectChecker` resolves all `axiom` / `impure` declarations purely at compile time via `ScopeAnalysis` (persisted across REPL inputs in `AnalysisSession` alongside frozen inferred `EffectSet`s, and across modules via `TypedModuleSummary` / `ModuleInterface`), never in `ScriptRuntime` or global singletons. Per `BIP007-D4`, `axiom` is valid only at declaration/function granularity (no inline `axiom ( ... )` expression blocks).
* **Order-independent within a file:** `EffectChecker` collects all top-level and block `axiom` / `impure` declarations in a pre-pass before building the SCC call graph, so declaring an axiom before or after a caller in the same file has the exact same effect. `axiom` declarations are forbidden inside verified `pure`/`deterministic` function bodies.
* **Method, constructor (`Class()`), and bare class targets:** A method-level target (`java.lang.Math.max`, `java.util.HashMap.put`) applies uniformly to **all overloads** of that method name on the target Java class (for instance methods, `mut` applies to the receiver `this`, and `borrow` on the declaration applies to `this` and all reference parameters). A constructor-only target (`axiom ... on java.util.HashMap()`) applies strictly to **public constructors** of that class so mutating methods are not marked `pure`. A bare class-level declaration (`def BigDecimal = axiom ... on import("java.math.BigDecimal")` or `axiom ... on java.math.BigDecimal`) applies to **all public constructors and public methods** of that class, while constructor-only and method-level overrides on the same class take priority (§6.2).
* **`export` contracts & `export axiom` in `ModuleInterface` (`BIP007-D2`):** Per `BIP007-D2`, an unannotated `export def` exports its inferred `EffectSet` and parameter flags in `TypedModuleSummary` (`ModuleInterface`), while exports with explicit effect modifiers or `impure` expose only their declared contract. Separately, `export axiom ... on <target>` / `export impure on <target>` do not introduce a runtime value binding; instead, `ModuleInterface` carries an exported axiom table mapping `(Class<?>, memberName | "<init>" | "*") -> EffectSet`, which `EffectChecker` imports into the caller's `ScopeAnalysis` on static `import("...ba")`.

### 6.2 Host-Side Java Annotations & `BabbleScriptEngine` API

Public package **`org.babblelang.effects`** defines the `Effect` enum (`PURE`, `HERMETIC`, `MUT`, `BORROW`, `DETERMINISTIC`, `NOTHROW`, `IDEMPOTENT`, `COMMUTATIVE`, `ASSOCIATIVE`, `MONOTONE`, `INJECTIVE`, `IMPURE`, adding `UNIQUE` / `@Unique` in `BIP007-P2`) and composable Java annotations (`@Pure`, `@Hermetic`, `@Mut`, `@Borrow`, `@Deterministic`, `@Nothrow`, `@Idempotent`, `@Commutative`, `@Associative`, `@Monotone`, `@Injective`, `@Impure`), keeping `org.babblelang.runtime` and `org.babblelang.engine.impl.natives` completely free of effect types (`RuntimeBoundaryTestCase`). Java libraries exposing APIs to Babble can annotate classes, constructors, or methods directly:

```java
import org.babblelang.effects.*;

public final class Geo {
    @Pure @Deterministic @Nothrow
    public static double haversine(double lat1, double lon1, double lat2, double lon2) { ... }

    @Idempotent
    public static void ensureOutputDir(String dir) { ... }
}
```

Embedded hosts can register trusted JDK/third-party methods, constructors, or classes programmatically on `BabbleScriptEngine` (isolated per engine instance and read by `EffectChecker` at compile time), and optionally forbid untrusted scripts from declaring in-script axioms via `allowAxioms(false)`:

```java
engine.allowAxioms(false); // optional sandbox policy: reject script-level axiom/impure declarations
engine.assumeEffects(Math.class, "sqrt", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
engine.assumeConstructorEffects(HashMap.class, Effect.PURE, Effect.DETERMINISTIC);
engine.assumeEffects(Files.class, "readString", Effect.PURE); // read-only, non-deterministic
engine.assumeEffects(Files.class, "createDirectories", Effect.IDEMPOTENT);
engine.markImpure(URL.class, "equals", "hashCode");
```

Compile-time resolution priority in `EffectChecker`:
1. File / imported `ModuleInterface` / `AnalysisSession` method or constructor (`Class()`) override (`axiom` / `impure`), else
2. Engine-level `BabbleScriptEngine` method or constructor override (`assumeEffects` / `assumeConstructorEffects` / `markImpure`), else
3. Java method / constructor annotations (`@Pure`, `@Deterministic`, ...), else
4. File / module / session or engine bare-class override, else
5. Java class annotations, else
6. Built-in `RM005` (`BIP011-P5`) core-type profiles (`String`, `Map.Entry`, `List`/`Set`/`SortedSet`/`Map`/`SortedMap`) and default value protocol (`equals`, `compareTo`, and overridden `hashCode`/`toString` $\to$ `{PURE, DETERMINISTIC}`; `Object`'s own `hashCode`/`toString` $\to$ `{PURE}`), else
7. Unconstrained (`EffectSet.EMPTY`).

### 6.3 Java Callbacks and Interface Proxies (`Faces` / `RM003` Bridges)

When a verified `pure deterministic` Babble function passes a Babble `Callable` or `BabbleInstance` (wrapped via `Faces.function` / `LambdaMetafactory` SAM adapters or `RM003` direct Java-interface bridges) to an axiomatized Java higher-order function such as `xs.stream().map(f)`:

* **Checked purely statically (`EffectChecker`):** Functional-interface arguments inside constrained scopes must have a statically known `Type.Function` (or implementing `BabbleClass`) whose effects satisfy the enclosing call's requirements (e.g., passing an unconstrained `fn (x) -> ( println(x) )` or an `any` value to `stream().map(...)` inside a `pure` caller is rejected at compile time). `Faces` and compiled Java-interface bridges require zero effect-checking logic or frame context.

### 6.4 Automatic Static Analysis of Java Bytecode via ASM (Feasibility & Limits)

Babble already depends on `org.ow2.asm:asm` (`pom.xml`), raising the question: can bytecode analysis automatically infer effects on JDK/library `.class` files instead of requiring `axiom` declarations—assuming all `native`/FFI methods (`ACC_NATIVE`, JNI, Panama FFM) are unconstrained (`impure`)?

* **Feasibility & Soundness:** **Yes**, for memory/exception effects (`pure`, `mut`, `deterministic`, `nothrow`). An ASM analyzer (like Sălcianu–Rinard's pointer/escape analysis or JPure) can soundly build a conservative call graph and inspect `PUTFIELD`, `PUTSTATIC`, `GETSTATIC` (non-`final`), `*ASTORE`, `MONITORENTER`, `<clinit>`, and `ACC_NATIVE`, falling back to unconstrained whenever open virtual/interface dispatch (`INVOKEVIRTUAL`, `INVOKEINTERFACE`), reflection, or `<clinit>` cannot be bounded.
* **Precision Limits:** Under a strict *"all `native` calls are impure"* policy, precision degrades sharply on the JDK:
  1. **Transitive `native` intrinsics:** `System.arraycopy` (`native`), `Object.getClass()` (`native`), and `Double.doubleToRawLongBits` (`native`) appear inside `String.substring`, `ArrayList`, `Arrays.copyOf`, and `BigDecimal`—tainting nearly the whole JDK without a small audited intrinsic seed table.
  2. **Internal local mutations & benign lazy caches:** `StringBuilder` inside `toString()`, `this.hash` caching in `String.hashCode()`, and `BigDecimal.stringCache` perform `PUTFIELD`/`*ASTORE`, requiring inter-procedural escape analysis for local buffers and explicit `axiom` for benign lazy memoization caches.
  3. **Algebraic & policy properties cannot come from bytecode:** Bytecode inspection can **never** infer that `Files.readString` is acceptable policy (`axiom pure`), nor in general prove `commutative` / `associative` / `idempotent` / `monotone` / `injective`.
* **Verdict for Babble:** Keep explicit `axiom` + annotations + `BabbleScriptEngine` API (plus `RM005`'s built-in core-type profiles) as the primary mechanism; add an optional **ASM bytecode analyzer in `BIP007-P3`** (seeded with proven JVM `native` intrinsics like `System.arraycopy`) to **suggest `axiom` candidates** or auto-certify closed pure helpers.

---

## 7. Type System, Compiler, and Runtime Architecture

### 7.1 `Type.Function`, `Type.Parameter`, `Type.Frozen`, and `EffectSet`

In `org.babblelang.analysis.Type` (integrated with `RM004`'s `TypeAlgebra`, `TypedProgram`, `LoweringAnalyzer`, and AST-detached `TypedModuleSummary`):

* **`EffectSet`** (immutable bitmask / enum set over `{PURE, HERMETIC, DETERMINISTIC, NOTHROW, IDEMPOTENT, COMMUTATIVE, ASSOCIATIVE, MONOTONE, INJECTIVE}` + internal compiler flags `boolean mutThis`, `boolean borrowThis`, explicit `boolean impureOptOut`, and in `BIP007-P2` `boolean uniqueResult`).
* **`Type.Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow, boolean readOnly)`** *(`+ boolean unique` in `BIP007-P2`)*: the single representation of per-parameter mutability, escape guarantee, `RM005` `borrow` covariance, and (`P2`) ownership (`fn (mut borrow dst: Buffer, borrow src: list of string)`).
* **`Type.Frozen(Type of)` *(`BIP007-P2`, `BIP007-D7`)*:** The `frozen T` type produced by `freeze(...)`, integrated into `Type.anyMatch`/`mapTypes` and `TypeAlgebra`:
  * Simplifies to `of` **only** when `of` is one of the 9 core scalars (`byte`..`string`) or `Type.NULL`; distributes over `Type.Union` so `frozen (A | B)` becomes `frozen A | frozen B` and `frozen T?` simplifies to `(frozen T)?`.
  * **Never unwraps** for nominal/reference types (`class`, `record`, `interface`, Java classes, `any`): a deeply immutable `record` or `entry of (K, V)` is assignable *into* `Frozen(T)` / `Frozen(ANY)`, but `Frozen(T)` is **not** assignable to `ANY`, `ANY?`, or plain `T` (even when `T` is a deeply immutable `record`), and `ANY`/`ANY?` is not assignable to `Frozen(T)`.
  * **Covariant in generic type arguments:** `Frozen(C of A) <: Frozen(C of B)` whenever `A <: B` (or `Frozen(A) <: Frozen(B)`), safely extending `RM005`'s `borrow`/`record`/`entry` covariance.
  * Member reads and non-`mut` `borrow` method calls on `Frozen(T)` wrap their result `U` in `Frozen(U)` (distributing over unions/nullables: `(frozen map of (string, Counter)).get("a")` yields `(frozen Counter)?`).
  * **Narrowing & casts:** `x is frozen T` is rejected statically (no runtime frozen tag); narrowing `Frozen(ANY)` / `Frozen(ANY?)` / `Frozen(Union)` via `x is T` **always preserves `Frozen`**, producing `Frozen(T)` (so `a is MyRecord` narrows to `frozen MyRecord` and `a is Box` narrows to `frozen Box`, never losing `frozen`). `a as T` on `Frozen(ANY)` requires `a as frozen T` (unless `T` is a core scalar or `null`).
  * `Type.isDeeplyImmutable()` holds for all 9 core scalars, `NULL`, `Frozen`, `record`s whose field types all satisfy it, `entry of (K, V)` where both `K` and `V` satisfy it, and `Union`s whose members all satisfy it.
* **`Type.Function(List<Parameter> parameters, Type result, EffectSet effects)`**:
  * Invariant: `effects.contains(PURE)` implies `effects.contains(HERMETIC) && !effects.mutThis` and no parameter in `parameters` has `mut`.
  * Assignability (`TypeAlgebra`): `actual.mut(i) => target.mut(i)` and `actual.mutThis => target.mutThis` hold uniformly across **all** `Type.Function`s (even when `target.effects` is empty, so absent `mut` always means read-only, §3.4). When `target` is unconstrained (empty `effects` and no `mut`/`borrow`/`unique`), it accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects. When `target` is constrained (non-empty `effects` or any parameter/`this` modifier), `actual` must additionally satisfy `actual.effects.satisfies(target.effects)`, `target.borrowThis => actual.borrowThis`, `target.borrow(i) => actual.borrow(i)`, and (in `P2`) `target.uniqueResult => actual.uniqueResult` and `actual.uniqueParam(i) => target.uniqueParam(i)`. Neither unconstrained nor constrained `Type.Function` accepts `ANY`, `ANY?`, or `NULL`.

### 7.2 Zero Runtime Effect Machinery (`Types`, `babble-runtime`)

Because Babble is statically typed (`bip008_static-typing.md`), `TypeChecker` / `EffectChecker` disallows assigning (`P2`) `frozen T` to/from `any`/`any?` (§3.2) or `any`/`any?` to `Type.Function` (§3.4), keeps plain `mut` exclusivity purely static (`BIP007-D9`), and `any` cannot be called or have members accessed without narrowing (`is`) or casting (`as`). Consequently, **the entire effect and ownership system is enforced at compile time with zero effect code in `org.babblelang.runtime` or `org.babblelang.engine.impl.natives` (`babble-runtime.jar`, enforced by `RuntimeBoundaryTestCase`)**, while public Java annotations and `Effect` live in `org.babblelang.effects`:

* `Types.check` / `Types.matches` (retained only for `as` casts, `is` checks, Java boundaries, and `setMember`) require no `fx|` or `frozen` descriptors (`as` and `is` already reject function types and `is frozen T` statically, and `as` cannot unwrap `frozen T`).
* `Callable`, `Function`, `CompiledFunction`, `CompiledMethod`, `JavaMethod`, `BabbleInstance`, `ScriptRuntime`, `Bootstrap`, `Calls`, `Faces`, and `Operators` carry no runtime `EffectSet` fields or effect-checking hooks.

### 7.3 Back-End Integration (`BIP007-P1` & `BIP007-P2`)

1. **`BIP007-P1` (`RM002`) — 100% front-end:** Neither `Interpreter` nor `CodeGenerator` was modified in `BIP007-P1`. `EffectChecker` records resolved `EffectSet` and `mut`/`borrow` profiles in `ScopeAnalysis` (and `TypedModuleSummary` / `AnalysisSession`).
2. **`BIP007-P2` — Zero-cost `move`/`freeze`/`discard` & Published Metadata:** `Interpreter` and `CodeGenerator` evaluate `move(x)` and `freeze(expr)` as zero-cost identity no-ops (`expr`) and `discard(expr)` as evaluate-and-return-`null` (zero new runtime classes), while `LoweringAnalyzer` / `ScopeAnalysis` additionally publishes `unique` and `@TERMINATING` metadata for future optimizer passes (which live in a separate follow-up BIP after `BIP007`).

---

## 8. Examples

### 8.1 Verified Pure Deterministic Code vs. `axiom` Java Interop

```babble
;; Declare trusted host axioms explicitly (`RM006` `on` syntax):
axiom pure deterministic nothrow on java.lang.Math.sin
axiom pure deterministic nothrow on java.lang.Math.cos
axiom pure deterministic nothrow on java.lang.Math.sqrt
axiom pure on java.nio.file.Files.readString        ;; side-effect-free, NOT deterministic!
axiom pure deterministic on java.nio.file.Path.of   ;; may throw InvalidPathException (not `nothrow`)

;; Verified: compiler checks body satisfies `pure deterministic`.
;; Because `/` on `double` is IEEE-754 division (`Operators.div`) and never throws, `EffectChecker` also
;; infers `nothrow` quietly (published in `ScopeAnalysis` for future intra-module optimization, while
;; external callers rely only on the explicitly declared `pure deterministic` signature, §3.1).
export def sqrtApprox = fn pure deterministic (x: double): double -> (
    if x <= 0.0 then ( return 0.0 )
    def g: double = x
    def i: int = 0
    while i < 12 then (
        g = 0.5 * (g + x / g)
        i = i + 1
    )
    g
)

;; Unannotated helper: compiler infers `pure deterministic nothrow` automatically (`BIP007-D2` exports it)!
export def dot2 = fn (ax: double, ay: double, bx: double, by: double): double -> (
    ax * bx + ay * by
)

;; Rejected: `Files.readString` is `pure` but NOT `deterministic`!
def badMemo = fn pure deterministic (p: string): string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)

;; Accepted as `pure` (safe to reorder across memory, not memoized across I/O):
def loadConfig = fn pure (p: string): string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)
```

### 8.2 Argument & Receiver `mut`, Confined `hermetic`, and (`BIP007-P2`) `unique` Locals

```babble
def Counter = class (def count: int = 0) (
    def peek = fn pure deterministic nothrow (): int -> ( this.count )
    ;; Inferred `hermetic` (mutates ONLY `this`, zero globals/I/O), never marked `pure`:
    def add  = fn mut deterministic nothrow (d: int) -> ( this.count = this.count + d )
)

;; `BIP007-P2` strictly `pure`: calling `tmp.add` on fresh `unique` local `tmp` requires `unique` ownership:
def previewNext = fn pure deterministic (c: Counter, delta: int): int -> (
    def tmp = Counter(c.peek())   ;; unique local instance (argument `c.peek()` is a primitive `int`)
    tmp.add(delta)                ;; OK in `BIP007-P2`: `Counter.add` is `hermetic` (disallowed in `P1`)
    tmp.peek()
)

;; `BIP007-P1` (`RM002`) confined `mut` helper (mutates ONLY `dst`, not `pure`):
def bumpBoth = fn deterministic (mut dst: Counter, src: Counter) -> (
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
def encodeScore = fn axiom pure deterministic nothrow monotone injective (raw: int): double -> (
    raw * 10.0 + 5.0
)

def isEligible = fn (score: double): bool -> ( score > 100.0 )       ;; inferred: pure deterministic nothrow
def nonNegative = fn (raw: int): bool -> ( raw >= 0 )                ;; inferred: pure deterministic nothrow

;; Because `encodeScore`, `isEligible`, and `nonNegative` are all `pure deterministic nothrow`:
;; future optimizer passes (postponed beyond BIP007, `BIP007-D10`) can reorder filters by selectivity,
;; push filters past `map(encodeScore)`, and invert `monotone` bounds on sorted chunks.
```

### 8.4 Algebraic Properties: `associative`, `commutative`, and `idempotent`

```babble
;; Note: RM005 already pre-registers `set.add` as `hermetic mut deterministic idempotent commutative`!
axiom idempotent on java.nio.file.Files.createDirectories

def sumInt = fn (a: int, b: int): int -> ( a + b )   ;; inferred: pure deterministic nothrow associative commutative (single-op `int +` wrapper)

def reduceInts = fn (xs: list of int, seed: int, op: fn pure deterministic associative (a: int, b: int): int): int -> ( ... )
def forEachDir = fn (mut acc: set of string, xs: list of string, f: fn idempotent commutative (mut s: set of string, x: string)) -> ( ... )

;; 1) Tree reduction (`BIP007-D3`: explicit `seed`; contextual lambda typing infers `x: int` and `: int`):
def totalSq = reduceInts(mapInts(items, fn (x) -> ( x * x )), 0, sumInt)

;; 2) Order-independent, retry-safe accumulation (mut target + idempotent + commutative):
def collector = set of string ()
def ensureAndTrack = fn axiom idempotent commutative (mut s: set of string, dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    discard(s.add(dir))
)
forEachDir(collector, dirs, ensureAndTrack)
```

### 8.5 Exclusivity, `unique` / `move(...)` / `freeze(...)` / `frozen` (`BIP007-P2`), and `borrow` (`BIP007-P1`)

```babble
def Vec = class (def data: list of double) ( ... )

;; 1) (`BIP007-P2`) Deep disjointness (no-alias) through an exclusive borrow (`unique borrow mut`):
def addScaled = fn deterministic (mut borrow unique dst: Vec, borrow src: Vec, k: double) -> (
    ... ;; `dst` owns its whole graph: it shares nothing with `src`
)
def scaleDemo = fn (src: Vec) -> (
    def v = Vec(list of double ())              ;; unique local: a fresh Vec over a fresh `RM005` list
    addScaled(v, src, 2.0)                      ;; exclusive borrow: `v` is usable (and still unique) afterwards
    ;; addScaled(v, v, 2.0)                     ;; REJECTED: `v` is exclusively borrowed and cannot be passed twice
    ;; addScaled(src, v, 2.0)                   ;; REJECTED: `src` is a parameter, not unique
    ;; def w = Vec(src.data)                    ;; `w` is not unique (shares `src.data`): not accepted as `dst` either
    v
)
;; With plain `fn (mut dst: Vec, src: Vec)` all of these calls except `(v, v)` would be accepted, and the
;; compiler assumes nothing about deep aliasing: two distinct Vecs may share one `data` list.

;; 2) (`BIP007-P2`, `BIP007-D6`) Imperative local core-collection builder -> zero-copy `freeze(...)` (zero axiom boilerplate!):
def buildLookup = fn pure deterministic (n: int): unique map of (int, int) -> (
    def m = map of (int, int) ()                ;; RM005 core collection constructor returns `unique`
    def i: int = 0
    while i < n then (
        discard(m.put(i, i * i))                ;; built-in `hermetic mut deterministic`; ints are deeply immutable -> stays unique
        i = i + 1
    )
    m   ;; nothing mutable is shared with anyone -> verified `unique` return!
)
;; Had `buildLookup` stored elements of a caller-supplied list into `m`, `m` would be demoted from unique:
;; the `unique` return and the `freeze(...)` below would both be rejected.
def readOnlyLookup = freeze(buildLookup(1000))  ;; compile-time transition (zero-cost no-op); type `frozen map of (int, int)`
def squareOf42 = readOnlyLookup.get(42)!        ;; `get` returns `int?`, unwrapped via `e!` (or handled via `!= null`)
;; readOnlyLookup.put(1, 2)                     ;; REJECTED statically: `put` is `mut` on `frozen map`
;; def leaked: any = readOnlyLookup             ;; REJECTED statically: `frozen T` cannot assign to `any`

;; 3) (`BIP007-P2`, `BIP007-D7`) `frozen` preservation under `is` narrowing (mandatory test cases):
def MyRecord = record (a: int) ()
def a: frozen any = MyRecord(5)
;; REJECTED: narrowing `a is MyRecord` yields `frozen MyRecord`, never losing `frozen` to plain `MyRecord`!
;; def b: MyRecord? = if a is MyRecord then ( a ) else ( null )
;; ACCEPTED: `frozen MyRecord?` == `(frozen MyRecord)?`, matching the narrowed `frozen MyRecord`!
def c: frozen MyRecord? = if a is MyRecord then ( a ) else ( null )

;; 4) (`BIP007-P2`, `BIP007-D5`) Functional-but-in-place (FBIP) & definite-unassignment with `move(...)` (locals only):
def sortInPlace = fn deterministic (unique mut xs: list of int): unique list of int -> ( ... )
def sortedSquares = fn (n: int): unique list of int -> (
    def a = makeSquares(n)          ;; `makeSquares` returns `unique list of int`: `a` is a unique local
    def b = sortInPlace(move(a))    ;; transfers ownership of `a`
    ;; a.size()                     ;; REJECTED: `a` was moved and cannot be used afterward!
    b
)
;; def top = makeSquares(10)
;; sortInPlace(move(top))           ;; REJECTED: `top` is a top-level binding, visible to other code

;; 5) `borrow` (`BIP007-P1` verification + `RM005` read-only covariance):
def distSq = fn pure deterministic nothrow (borrow p: Point, borrow q: Point): double -> (
    def dx = p.x - q.x
    def dy = p.y - q.y
    dx * dx + dy * dy
)
```

---

## 9. Design Decisions & Phased Implementation Plan

### 9.1 Settled Design Decisions

* **`BIP007-D1` (resolves `BIP007-Q1`) — Guard-safe `Narrowing` (`!= null`, `is T`) and `nothrow`:** `TypeChecker`'s flow-sensitive `Narrowing` (`!= null`, `is T`) establishes `nothrow` on guarded `if`/`while` branches and short-circuit `and`/`or` right-hand sides (`a != null and a.x > 0`), and future optimizers must never reorder a narrowed predicate ahead of its guard; unconditional reordering is restricted strictly to mutually `nothrow` predicates with no guard dependencies.
* **`BIP007-D2` (resolves `BIP007-Q2`) — Cross-module export contracts (`ModuleInterface` / `TypedModuleSummary`):** Unannotated `export def` exports its **inferred** `EffectSet` and parameter flags (`mut`/`borrow`, plus `unique` in `P2`) across modules via AST-detached `TypedModuleSummary`, maximizing cross-module inference; exports carrying explicit effect modifiers or `impure` expose strictly their declared contract so external callers never depend on uncontracted implementation details.
* **`BIP007-D3` (resolves `BIP007-Q3`) — No `identity(...)` clause on `associative`:** Reduction combiners supply explicit seeds as in §8.4; no `identity(...)` annotation attribute is included in `BIP007`, keeping the surface vocabulary minimal while leaving optional monoid identity deferred.
* **`BIP007-D4` (resolves `BIP007-Q4`) — Declaration-only `axiom` granularity:** `axiom` stays strictly at declaration and function-literal granularity (`def f = fn axiom ... ->`, `axiom ... on Target`, `def C = axiom ... on import("...")`, `export axiom ... on Target`, plus `Class()` for constructor-only targets); inline `axiom ( ... )` expression blocks are forbidden so trust boundaries remain explicit and auditable.
* **`BIP007-D5` (`RM006` alignment) — Minimal predicate-free `P2` syntax:** Preserve `Babble.g4`'s zero-`@parser::members`, zero-predicate, pure-`SLL` invariant by adding only **two** new lexer tokens (`UNIQUE`, `FROZEN`, both exempted in `memberName`), while keeping **`move(x)`**, **`freeze(expr)`**, and **`discard(expr)`** as built-in functions (not reserved keywords). `unique` appears only in `paramModifier` and on `fn` return clauses (`':' UNIQUE? type`); `FROZEN?` prefixes `nonParenNullableType` and `# parenType`.
* **`BIP007-D6` (`RM005` alignment) — `unique` ownership on core collections & `entry`:** `RM005` core collection constructors (`list`, `set`, `sortedset`, `map`, `sortedmap`) return `unique` when initialized from `unique`/deeply-immutable elements (or `from:` of deeply-immutable elements, and `pure deterministic` `comparator:`); `entry of (K, V)` is deeply immutable iff both `K` and `V` are; and built-in `hermetic mut` core-collection mutators preserve `unique` when all inserted arguments are `unique` or deeply immutable.
* **`BIP007-D7` (`RM005` + `RM006` alignment) — `frozen T` covariance, preservation & `is` narrowing:** `frozen` generic types are **covariant** in all type arguments (`frozen list of Dog <: frozen list of Animal`, `frozen Box of Dog <: frozen Box of Animal`). `frozen` simplifies only on the 9 core scalars and `null`, and distributes over unions/nullables (`frozen T?` == `(frozen T)?`). For all nominal/reference types (`class`, `record`, `interface`, Java classes, `any`), `frozen T` **never** unwraps back to plain `T`: `x is frozen T` is rejected statically, and narrowing `frozen any` (or `frozen (A | B)`) with `x is T` **always preserves `frozen`**, narrowing to `frozen T` (verified by the two `MyRecord` test cases in §8.5).
* **`BIP007-D8` — Unused `pure nothrow` errors + built-in `discard(expr)`:** Keep discarded `pure nothrow` source expressions as a compile-time error in `BIP007-P2`, paired with built-in `discard(expr)` (`pure deterministic nothrow`, `borrow` parameter accepting any type including `any?`, `frozen T`, and `unique` locals without consuming or unwrapping them, returning `void`/`null`) as the explicit opt-out.
* **`BIP007-D9` — Purely static `mut` exclusivity & `borrow`-polymorphic built-ins:** Drop the optional runtime `Values.same(a, b)` exclusivity fallback; plain `mut` exclusivity stays purely static in `EffectChecker`, while `unique mut` provides deep disjointness, preserving §7.2's strict zero-runtime-effect-machinery invariant. Built-ins `same(a, b)`, `classof(x)`, and `discard(x)` are `pure deterministic nothrow` with `borrow` parameters accepting any type including `frozen T` and `unique` locals without consuming or unwrapping them.
* **`BIP007-D10` — Postpone bytecode & pipeline optimizer passes beyond `BIP007`:** `BIP007` is scoped strictly to the effect & ownership type system, its compile-time enforcement, metadata publication in `ScopeAnalysis`, and `P3` verification/analysis aids (`checkAxioms` + ASM suggester). All bytecode and pipeline optimizer passes consuming `EffectSet`/`borrow`/`unique`/`@TERMINATING` (CSE, constant folding, no-alias load hoisting, `borrow` scalar replacement, FBIP reuse, predicate reordering/push-down, dead-expression elimination, loop reassociation, `monotone`/`injective` rewrites) are postponed to a dedicated follow-up optimizer BIP after `BIP007`.

### 9.2 Phased Implementation Roadmap

1. **`BIP007-P1` (`RM002`, extended in `RM005` & `RM006`) — Core Effect Model, SCC Fixed-Point Inference, Verification & `axiom` *(Implemented)*:**
   * Grammar & Formatting (`Babble.g4`, `Formatter`): initially landed in `RM002` with contextual predicates, then upgraded in `RM006` (`BIP012`) to zero-predicate LL(1)/LL(2) `SLL` grammar (`FN`, `ON`, and all 13 effect keywords as real lexer tokens exempted in `memberName`, `fn pure (mut x: T): R -> (...)`, `axiom ... on Target`).
   * Type System, Inference & Verification (`EffectSet`, `Type.Parameter(..., mut, borrow, readOnly)`, `Type.Function`, `EffectChecker` + `RM004` `TypedProgram`): uniform absent `mut` across typed functions (`mut` inferred only on completely untyped functions); SCC bottom-up fixed-point inference and verification for `{pure, hermetic, mut, borrow, deterministic, nothrow}`; `@PARAM`/`@THIS` local alias and derived sub-reference tracking (extended in `RM005` with core collection views); static call-site `mut` exclusivity; compositional algebraic properties + signature applicability validation; frame-local `borrow` closure attribution; bounded call-site effect specialization; built-in `RM005` (`BIP011-P5`) effect profiles for all 9 scalars, `string`, `entry`, and core collections (`list`..`sortedmap`) alongside standard value protocol defaults; `record` constructor checks; effect-constrained `Type.Function` assignability; interface conformance (`actual <: required`); and covariance/contravariance subtyping.
   * Public Java API, Engine & Persistence (`org.babblelang.effects`, `BabbleScriptEngine`, `TypedModuleSummary`, `AnalysisSession`, `ScopeAnalysis`): public `Effect` enum and `@Pure`..`@Impure` annotations; order-independent pre-pass collection of `axiom`/`impure` declarations (`BIP007-D4`); compile-time per-engine registries (`allowAxioms(false)`, `assumeEffects`, `assumeConstructorEffects`, `markImpure`); AST-detached cross-module export (`BIP007-D2`, including `export axiom`/`export impure` tables) and `AnalysisSession` persistence; resolved `EffectSet`/`mut`/`borrow` metadata published in `ScopeAnalysis`.
2. **`BIP007-P2` — Deep Ownership (`unique` / `frozen`), Built-ins (`move` / `freeze` / `discard`), `@Unique`, `@TERMINATING`, and Enforcement:**
   * Grammar & Formatting (`Babble.g4`, `Formatter`, `BIP007-D5`): add only `UNIQUE` and `FROZEN` lexer tokens (both exempted in `memberName`), extend `paramModifier` with `UNIQUE`, `fn` return clauses with `':' UNIQUE? type`, and `nonParenNullableType` / `# parenType` with `FROZEN?`, keeping `ParserGrammarTestCase` 0-predicate pure-`SLL` green.
   * Type System & Ownership Lattice (`Type.Frozen`, `Type.Parameter(..., unique)`, `EffectSet.uniqueResult`, `TypeAlgebra`, `EffectChecker`, `@Unique` in `org.babblelang.effects`): implement full `unique`/`@PARAM(i)`/`@THIS`/`@MOVED`/`@EXTERNAL` ownership lattice enabling mutation of fresh `unique` local objects and `RM005` core collections (`BIP007-D6`) inside `pure`/`hermetic` functions, sub-reference propagation, control-flow merge joins, definite-unassignment, implicit last-use `move(...)`, purely static `Type.Frozen` with generic covariance and strict `frozen` preservation under `is` narrowing (`BIP007-D7`, including the two `MyRecord` test cases in §8.5), `@TERMINATING` inference, unused `pure nothrow` compile errors paired with built-in `discard(expr)` (`BIP007-D8`), and `borrow`-polymorphic `same`/`classof`/`discard` accepting `frozen T` and `unique` locals (`BIP007-D9`).
   * Back-End & Metadata Publication: evaluate `move(x)` and `freeze(expr)` as zero-cost identity no-ops and `discard(expr)` as evaluate-and-return-`null` in both `Interpreter` and `CodeGenerator` (zero runtime effect machinery in `babble-runtime`, `RuntimeBoundaryTestCase` green); publish `unique` and `@TERMINATING` metadata alongside `EffectSet`/`borrow` in `LoweringAnalyzer` / `ScopeAnalysis` and `TypedModuleSummary` / `AnalysisSession` for future optimizer BIPs.
3. **`BIP007-P3` — ASM Bytecode Suggester & `babble.checkAxioms` Fuzzing:**
   * Add opt-in ASM bytecode analyzer (`ClassLoader` + escape analysis + seeded JVM `native` intrinsics) to suggest/auto-certify `pure`/`mut`/`deterministic`/`nothrow` on closed Java helpers.
   * Add property-based test harness (`babble.checkAxioms`) with built-in generators for all 9 `RM005` scalars, core collections (`list`..`entry`), and recursive `record`s to fuzz-test `axiom commutative`, `associative`, `idempotent`, and `deterministic` declarations in CI.

---

## 10. Future Directions

* **Effect- & Ownership-Driven Bytecode & Pipeline Optimizers *(separate follow-up BIP after `BIP007`, `BIP007-D10`)*:** Consuming the `EffectSet`, `borrow`, `unique`, and `@TERMINATING` metadata published in `ScopeAnalysis` by `BIP007-P1`–`P2` across all 8 `RM005` unboxed primitives: CSE & constant folding (`pure deterministic` on deeply immutable args/results), deep no-alias load hoisting & redundant-load elimination (`unique mut`), `borrow` scalar replacement / stack unboxing of `record`s, FBIP in-place reuse (`unique` + `move(...)`), guard-safe predicate reordering & hoisting (`pure deterministic nothrow`, `BIP007-D1`), dead-expression elimination (`pure nothrow @TERMINATING`), `associative` loop reassociation, and `monotone`/`injective` pipeline rewrites.
* **Thread Safety & Optimized Parallelization:** Once thread-safety annotations (e.g., `threadsafe`) are added to the effect system, the existing effect and ownership guarantees in BIP007 will directly enable safe, optimized parallelization of work without locks or defensive copying:
  * **`parallelMap` / `parallelFilter`:** Evaluating `pure threadsafe` callbacks independently across worker threads, with shared inputs guaranteed race-free via deeply immutable types (`frozen T`, deep `record`, scalars) or exclusive `unique borrow` ownership.
  * **`parallelReduce`:** Splitting reductions into balanced parallel tree folds whenever the combiner is `pure deterministic threadsafe associative` (preserving partition order when non-commutative, or using arbitrary work-stealing when also `commutative`).
  * **`parallelForEach` & Lock-Free Accumulation:** Performing unordered concurrent updates on `mut threadsafe` targets via `commutative` (and `idempotent` for safe retries) operations, and transferring owned `unique` buffers across threads zero-copy via `move(...)`.

