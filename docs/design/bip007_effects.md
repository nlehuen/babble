# BIP007: Function Effects and Algebraic Properties in Babble

**BIP:** 007  
**Date:** 2026-11-14  
**Status:** Draft (implemented through `RM002` / `BIP007-P1`; `BIP007-Q1`–`Q4` → `BIP007-D1`–`D4` decided; `BIP007-P2`–`P3` planned for `RM004`)  
**Depends on:** [bip008_static-typing.md](bip008_static-typing.md) (`BIP008-P1`–`BIP008-P7`: static typing, `any`/`any?`, `T?`, `A | B`, `as`, generics, call-site parameter inference)  
**Scope:** Static type system, effect inference & verification engine, `axiom` declarations, optimizer contract, Java interop, and runtime/bytecode compiler integration.

---

## Executive Summary

Babble captures **function effects and algebraic properties** directly in its static type system and parse-tree analysis (`ScopeAnalysis`) so the compiler can both **secure computation** (preventing unintended state mutation and ambient I/O leaks) and **optimize computation** (reordering, common subexpression elimination (CSE) / memoization, predicate push-down/push-up, tree reductions, and safe automatic retries).

Rather than creating separate "trusted" vs "checked" keyword hierarchies (e.g., `pure` vs `trusted_pure`), Babble uses **one composable property vocabulary** paired with **three derivation modes**:

1. **Inferred** — unannotated Babble functions automatically receive the strongest properties provable by whole-module fixed-point analysis.
2. **Verified** — explicitly annotated functions (e.g., `pure deterministic`) are statically proved by the compiler against their body and callees.
3. **Axiomatic (`axiom`)** — functions or Java targets prefixed with `axiom` (e.g., `axiom pure deterministic`, `axiom associative commutative`) assert properties that the compiler accepts as trusted contracts without inspecting/proving an implementation.

---

## 1. Context & Objectives

### 1.1 Problem Statement

A single binary `pure` / `impure` distinction is too coarse for modern compilers and query/pipeline optimizers:

* A time-reading function (`Instant.now()`) mutates no memory yet cannot be memoized or cached (`pure`, **not** `deterministic`).
* A builder helper mutates a caller-passed buffer without doing any global I/O (effects strictly scoped to its `mut` parameter, not `pure`—marked `hermetic mut`).
* Parsing an integer (`Integer.parseInt(s)`), asserting a precondition (`assert(x > 0)`), or dereferencing a nullable receiver (`a.x`) can be `pure deterministic` yet cannot be speculatively hoisted ahead of a guard (`nothrow` is false; note that Babble's `a / b` is IEEE-754 `double` division and never throws).
* A set insertion (`set.add(x)`) mutates its target, yet is order-independent and retry-safe (`mut idempotent commutative`).
* String concatenation (`a + b`) is `associative` (tree-reducible) without being `commutative`, whereas integer addition is both.

To unlock real compiler transformations without turning code into unreadable annotation noise, Babble needs a **structured, composable effect and algebraic property model** that maximizes static **inference** while offering **`axiom`** as an explicit escape hatch where static proof is impossible or external.

### 1.2 Objectives: What Effects Unlock

Every property in the model is justified by concrete security guarantees or compiler/runtime optimizations:

| Compiler / Runtime Capability | Required Properties (Contract) | Example Transformation |
|---|---|---|
| **Common Subexpression Elimination (CSE), Memoization & Constant Folding** | `pure deterministic` + deeply immutable result; memoization and folding also need deeply immutable arguments (§5.3.1) | Cache `f(x)` across repeated calls; fold `f(const)` at compile time. |
| **Loop-Invariant Code Motion (LICM)** | `pure deterministic` (+ `nothrow` and `@TERMINATING` whenever the loop may execute zero times or the call is hoisted across a guard) | Hoist `normalize(cfg)` out of a loop body. |
| **No-Alias (`unique mut`) Local/Field Load Hoisting & Redundant-Load Elimination** | `unique mut` parameter (exclusively owned graph, §3.2) | Keep `src` fields in JVM locals and hoist invariant `src` reads across `unique mut dst` updates without alias checks. |
| **Expression & Filter Reordering** | `pure` (+ `nothrow` for guarded/short-circuit swaps) | Reorder `xs.filter(p).filter(q)` by estimated cost/selectivity. |
| **Predicate Push-Down & Push-Up** | `pure deterministic nothrow` (+ `monotone` / `injective` for inversion/projection pushdown) | Push `filter(p)` ahead of `map(f)` (`xs.filter(x -> p(f(x))).map(f)` when `p(f(x))` simplifies via `monotone`/`injective` or `f` is cheap/CSE'd), invert `monotone` bounds, push equality past `injective` projections, factor common filters above `union`. |
| **Zero-Copy Builder $\to$ Immutable Snapshot** | `unique` return + `freeze` | Build a large mutable graph/index locally, then `freeze` zero-copy into deeply immutable `frozen T` data. |
| **In-Place Reuse (FBIP)** | `unique` + `move` (single live owner) | Lower `sort(move xs)` into in-place mutation (FBIP) with zero I/O/globals. |
| **Stack Unboxing / Scalar Replacement** | `borrow` parameter (with `value` / fresh arg) | Pass `Point(x, y)` or closure captures directly in registers/locals without heap allocation. |
| **Tree Reduction / Reassociation** | `pure deterministic associative` | Reassociate reductions and split folds into balanced subtrees. |
| **Safe Automatic Retry** | `idempotent` (or `pure` for read/compute steps) | Re-run failed steps (`Files.createDirectories`, `set.add`, `upsert`) without duplicating effects. |
| **Dead-Expression Elimination, Speculation & Unused-Result Errors** *(`BIP007-P2`)* | `pure nothrow` (+ `@TERMINATING`) | Drop dead `pure` expressions after inlining/CSE; **report unused `pure nothrow` expressions in source as a compile-time error** (catching bugs like `s.trim()` with a discarded result); speculatively evaluate before branches. |

### 1.3 Non-Goals

* **Full theorem proving / SMT verification of arbitrary equations:** The compiler does not attempt to prove algebraically from raw ASTs that arbitrary user arithmetic satisfies $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ or $f(a, b) = f(b, a)$; those rely on **`axiom`** (with optional property-based test verification).
* **Mandatory effect annotations everywhere:** Unannotated Babble scripts must continue to work seamlessly; effect inference (alongside BIP008's local and call-site type inference) avoids ceremony on ordinary scripting code.
* **Rust lifetime annotations (`'a`) or Pony's 6-way reference qualifiers on every variable:** Babble captures call-site exclusivity, `borrow`, `unique`/`move`, and `freeze` through parameter/return modes, definite-unassignment, and intra-procedural escape analysis rather than lifetime generics or pervasive viewpoint adaptation. The single `frozen` type qualifier (§3.2) is the only reference capability that appears in types.

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

> **Notation:** Surface modifiers and keywords written by Babble programmers appear in lowercase (`pure`, `hermetic`, `mut`, `borrow`, `unique`, `move`, `freeze`, `frozen`, ...). Internal compiler states, inferred-only flags, and guarantees not directly manipulable by users use an **`@UPPERCASE` prefix** (`@PARAM(i)`, `@THIS`, `@FROZEN`, `@MOVED`, `@EXTERNAL`, `@TERMINATING`, `@TOTAL`, `@AXIOMATIC`). Java interop annotations (§6.2) use TitleCase (`@Pure`, `@Mut`, ...).

### 3.1 Architecture Overview: Infer, Verify, or `axiom`

Every callable in Babble (function literal, method, constructor, or imported Java method/class) has an **Effect Profile** (`EffectSet` + per-parameter/receiver `mut` flags), derived in one of three modes:

1. **Inferred Mode (no explicit effect modifiers, and not marked `impure`):**
   * The compiler analyzes the function body and call graph via fixed-point iteration and infers the **maximal sound subset** of `{pure, hermetic, deterministic, nothrow}`, the **minimal `mut` parameter/receiver set**, and compositional relational properties (`monotone`, `injective`).
   * An explicitly marked **`impure`** function suppresses inference and locks the signature to unconstrained (used to prevent callers from depending on accidental purity, or to opt out default-pure value methods like `URL.equals`).
2. **Verified Mode (explicit effect modifiers *without* `axiom`, e.g., `pure deterministic (x: int) -> (...)`):**
   * Declared modifiers become **compile-time obligations**: the compiler verifies that the body, all expressions, and all callees satisfy every declared property and mutates **only** declared `mut` parameters / `mut` `this` / `unique` local objects.
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
  * Mutates **no global/outer variables, static fields, or external objects** (`hermetic`).
  * Mutates **no parameters and not `this`**: **`pure` cannot be combined with `mut`** on parameters or `this` (`pure (mut x)` and `pure mut` are rejected).
  * *Local builders (`unique`, `BIP007-P2` / `RM004`):* Inside a `pure` function, mutating **newly allocated `unique` local objects** (`def tmp = Counter(0); tmp.add(1)`) *is* allowed whenever the called `mut` method/helper is **`hermetic`** (inferred, declared `hermetic mut`, or axiomatized via `axiom hermetic mut`—without calling mutating functions "`pure`" in surface syntax). In `BIP007-P1` (`RM002`), before `unique` lands, mutating any object (including a newly allocated local) is disallowed in `pure`/`hermetic` functions, while local `def` variable reassignments remain valid.
* **`hermetic` (No global or I/O side effects):**
  * Performs **no output or mutating I/O** and mutates **no global/outer variables, static fields, or external objects**—any mutation is confined strictly to `mut` parameters, `mut` `this`, or `unique` local objects (`BIP007-P2`).
  * Unlike `pure`, **`hermetic` may be combined with `mut`** (`hermetic mut`, `hermetic (mut dst: List, item: string)`). Every `pure` function is automatically `hermetic`, and a `hermetic` function with no `mut` parameters or `mut` `this` is `pure`.
  * Allows `pure` callers to invoke `hermetic mut` helpers and Java methods (`axiom hermetic mut ArrayList.add`) on `unique` local objects (`BIP007-P2`).
* **`mut` + Call-Site Exclusivity:**
  * Written on a parameter (`(mut target: List, item: string)`) or on the function itself (`mut` before parameters) to indicate **`this` may be mutated**. Mutually exclusive with `pure` (use `hermetic mut` when the function also guarantees zero global/I/O side effects).
  * Absent `mut`, a parameter or `this` is **read-only** across all typed functions—including effect-unannotated functions whenever any parameter type or `: R` return type is written (`(a: T) ->`, `(): R ->`, `(rng): Vec3 ->`): field assignments (`p.x = ...`), calling `mut` methods on `p`, and passing `p` as a `mut` argument are forbidden unless `mut` is declared on `p`/`this` (with `mut` inferred only on completely untyped functions having no effect modifiers, no parameter types, and no `: R` return type).
  * **Law of Exclusivity (two strengths):**
    * *Plain `mut` (shallow, a bug catcher):* whenever an argument (or receiver) is passed to a `mut` slot, **no other argument or `this` at the same call site may be the same object** (`BIP007-P1` checks static conflicts for same bindings, tracked local aliases, and identical member paths; optional runtime `Values.same(a, b)` checks for dynamic expressions are deferred to `BIP007-P2`). This is an identity check only. Two distinct objects can still share mutable state (`Vec(data)` and `Vec(data)` over one list), and the callee can still reach the object through a captured variable or a global. **Plain `mut` therefore grants permission to mutate and nothing else: neither the callee nor the caller may assume deep non-aliasing from it.**
    * *`unique mut` (deep disjointness / no-alias):* a `unique` parameter receives a graph that nothing else can reach (see `unique` below), so it is disjoint from every other argument, from `this`, from captured variables and from globals **by construction**, with no alias check at all. All no-alias transformations (§5.3.2) key on `unique mut`, never on plain `mut`.
    * *Caller side:* after a call that passes `x` to a plain `mut` slot (or any unconstrained call), the caller must drop every cached fact about objects that are not provably disjoint from `x`. Provably disjoint means: `unique` locals not passed to the call, and deeply immutable values.
  * *Confined `mut` inference (`hermetic`):* Fixed-point inference tracks whether a `mut` Babble function avoids globals and I/O (`hermetic`), and **`axiom hermetic mut`** on Java targets (`axiom hermetic mut ArrayList.add`) likewise declares mutations confined strictly to `mut` targets with zero global/I/O effects—allowing `pure` callers to invoke them on `unique` local objects (`BIP007-P2`).
* **`borrow` (Frame-local parameter borrowing):**
  * Written (or automatically inferred) on a parameter `(borrow p: Point)` or receiver: guarantees the function never stores `p` (or mutable references reachable from `p`) into `this`, another parameter, a global, a captured closure, or its return value.
  * Unlocks **scalar replacement / stack unboxing** of wrapper objects (`Point`, temporary buffers, lambda captures) across call boundaries.
* **`unique`, `move`, and `freeze` *(`BIP007-P2` / `RM004` — single-owner graph ownership & zero-copy transition)*:**
  * **`unique`** on a parameter `(unique mut buf: Buffer)` or return type `(...): unique Index` asserts **deep ownership**: every *mutable* object reachable from the reference is reachable **only** through it (Pony `iso` / Rust owned value). Deeply immutable values (primitives, strings, `null`, `frozen T`, deep `value class`es) may be shared freely inside a unique graph, since sharing them is unobservable.
    * *Keeping a graph unique:* a newly allocated object (from a `pure` constructor, `unique` return, or `object` literal) starts `unique` provided every argument passed to it is `unique` (moved in) or deeply immutable, and stays `unique` only while every reference stored into it is itself `unique` (moved in) or deeply immutable. Storing a parameter, an outer variable, an `any`/`any?` value, or anything else demotes it to `@EXTERNAL` (or `@PARAM`-tainted): no longer mutable in `pure`, movable, freezable, or returnable as `unique` (§5.2). Arguments passed to a Java method are assumed stored in the receiver unless the method's axiom marks that parameter `borrow`.
    * *Consume vs. borrow:* a `unique` parameter **consumes** its argument (the callee may store it). A `unique borrow` parameter is an **exclusive borrow** (Rust `&mut`): the callee may not store it, so the caller's binding is usable again, and still `unique`, after the call.
  * **`move x`** explicitly transfers a `unique` variable to a `unique` consumer (`channel.send(move buf)`, `sortInPlace(move list)`), triggering **definite-unassignment** on `x` in the caller (cannot be used after moving; omitted automatically on a binding's provable last use).
    * *Locals only:* ownership is tracked only for **function-local bindings that no closure captures**. Top-level, package, class-static and captured bindings are namespace or cell variables that other functions, closures and later REPL inputs can reach; they are always `@EXTERNAL`, never `unique`, and `move` on one is a compile-time error. A temporary that is never bound (`freeze(buildIndex(1000))`) is consumed directly and needs no `move`.
  * **`freeze(expr)`** consumes a `unique` mutable graph and produces a **deeply immutable value of type `frozen T`** as a **purely static type transition (a zero-cost identity no-op at runtime)**—allowing imperatively built indexes, graphs, and maps to become deeply immutable snapshots with zero copying.
    * *Why it is deep:* the argument must be `unique`, and a unique graph contains only owned objects and deeply immutable values. Nothing mutable is shared with the caller, so nothing outside can observe or change the frozen graph. A non-`unique` graph (one that absorbed a parameter or other shared mutable object) is rejected.
    * *The `frozen T` type:* frozen-ness is part of the type, so it survives fields, calls, returns and top-level `def`s (`def lookup: frozen HashMap(string, int) = freeze(...)`; inferred for never-reassigned unannotated `def`s like any other type). "Deeply immutable" means: primitives, strings, `null`, `frozen T`, `value class`es all of whose fields have deeply immutable declared types, and unions/nullables (`A | B`, `T?`) all of whose members are deeply immutable. `frozen` distributes over unions (`frozen (A | B)` = `frozen A | frozen B`, so `frozen (T?)` simplifies to `(frozen T)?`).
    * *Purely static enforcement (no `any` erasure, zero runtime machinery):* because static type checking is mandatory (BIP008), `TypeChecker` enforces immutability without any runtime graph walk, `BabbleInstance` frozen bit, or `JavaObject` wrapper:
      * `frozen T` is **not assignable to `any`, `any?`, or plain `T`** (and `e as T` / `e as any` cannot unwrap a `frozen` type), and **`any` / `any?` is not assignable to `frozen T`**. Any parameter, field, or reassigned `def` holding a frozen value must be typed `frozen T` (so `frozen` can never be erased into `any` or mutated via narrowing).
      * On a `frozen T` receiver, field writes and `mut` method calls are rejected; non-`mut` method calls are allowed only when `this` is `borrow` (or `axiom pure` on Java methods, so `this` cannot escape as unfrozen `T`), and passing `frozen T` to a Java parameter requires that parameter to be known non-`mut` and `borrow`.
      * Reading a field or calling a method on `frozen T` with concrete static type `U` yields `frozen U` (or `U` if `U` is already deeply immutable; nullable `U?` yields `(frozen U)?`). If a Java method on `frozen T` returns `java.lang.Object` (`any` / `any?`), `TypeChecker` types the result as `frozen any` / `frozen any?`, which can only be narrowed (`is`) or cast (`as`) to a deeply immutable concrete type (`int`, `double`, `bool`, `string`, or a deep `value class`) where `Types.check` verifies the runtime value.

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
    * **Value projection form** (`pure` unary endofunction `(T): T`): $f(f(x)) = f(x)$ (e.g., `abs`, `String.trim`, canonicalizers). Enables $f(f(x)) \to f(x)$ elimination.
    * **Effect / retry form** (`mut` or effectful `axiom` function): executing the call twice consecutively with the same arguments produces the exact same observable **post-state** as calling it once, **ignoring the return value** (e.g., `Files.createDirectories`, `set.add`—which returns `true` then `false`—`latch.set()`, `upsert`). Enables **safe automatic retries** and duplicate suppression when the return value is unused.
  * *Important:* `idempotent` is orthogonal to `commutative` (`map.put(k, v)` is `idempotent` without being `commutative`; `counter.add(n)` is `commutative` without being `idempotent`).
* **`commutative`:**
  * **Value form** (`pure` binary `(T, T): R` with two parameters of the same type `T`): reordering arguments yields the same result, $f(a, b) = f(b, a)$ (e.g., `int +`, `min`, `max`, `==`). Enables canonical operand ordering and argument-order-independent CSE.
  * **Effect form** (`mut` method on receiver `S` or function taking `mut target: S`): two calls on the same `mut` target with different arguments produce the exact same observable **post-state**, $s.f(a); s.f(b) \equiv_{\text{state}} s.f(b); s.f(a)$, **ignoring return values** (e.g., `set.add(k)`, `counter.add(k)`). Together with `idempotent` for retries, enables **order-independent, retry-safe accumulation**.
* **`associative`** *(binary combiners `(T, T): T`)*:
  * $(a \oplus b) \oplus c = a \oplus (b \oplus c)$ (e.g., integer `+`, `*`, `min`, `max`, string `+`, list concat). Enables **tree reductions**, loop reassociation, and partition merges.
  * *(`BIP007-D3`: `associative` takes no `identity(...)` clause in `BIP007-P1`; reductions supply explicit seeds as in §8.4, with optional monoid identity deferred.)*
* **`monotone`** *(order-preserving unary or first-arg mapping)*:
  * $x \le y \implies f(x) \le f(y)$ over the whole declared domain (e.g., `double` `x + k`, `double` `x * k` for `k >= 0`, monotonic timestamp/ID buckets). **Wrapping `int` arithmetic is not monotone** (`x + 1` wraps at the maximum), so `int` `x + k` and `x * k` never get the property; see §4.6. Enables **zone-map / min-max range pruning** and pushing range predicates ($f(x) > c$) down into storage scans.
* **`injective`:**
  * $x \ne y \implies f(x) \ne f(y)$ (one-to-one mapping, e.g., bijective encodings, lossless wrappers `UserId(id)`, wrapping `int` `x + k`, wrapping `int` `x * k` for **odd** `k` only). Enables pushing **`distinct`**, **`groupBy`**, and **`f(x) == f(y)`** through projections directly onto underlying keys.
* **Signature applicability rules (`TypeChecker`):** Every algebraic modifier is validated against the declaration's parameter and return types: `associative` requires a `pure` binary `(T, T): T` signature; value `commutative` requires a `pure` binary `(T, T): R` signature with identical parameter types; effect `commutative` requires `mut` on `this` or a parameter; value `idempotent` requires a `pure` unary `(T): T` signature while effect `idempotent` requires `mut` or an effectful `axiom`; `monotone` requires ordered first-parameter and return types (`int`, `double`, `bool`, `string`, or Java `Comparable`); and `injective` requires at least one parameter and a return type other than `null`. Any mismatch is a compile-time `TypeCheckException`.

#### Soundness premise — static typing and external boundaries

Every profile, inferred or verified, is derived assuming the arguments conform to their declared or inferred parameter types. Under BIP008, that assumption is enforced statically throughout Babble (`TypeChecker`, `BIP008-P5`), with runtime `Types.check` remaining only at `as` casts and Java boundaries:

* **Typed parameters are statically guaranteed:** Every parameter has a static type (annotated, contextual, default, or call-site inferred via `BIP008-P7`), and `TypeChecker` rejects mismatches before execution. Inside `(x: int, y: int) -> ( x + y )`, `x + y` is guaranteed to be integer addition with no runtime entry check.
* **Java boundaries remain checked:** Calls entering Babble from Java interfaces (`Faces` via `JavaTypes.checkParameters`) and boxed/generic values exiting Java (`JavaTypes.checkBoxed`, generic `TypeVariable`/`WildcardType` returns and fields) are checked at runtime, so un-axiomatized Java calls and `as` casts drop `nothrow`.

### 3.3 Exact Verification vs. `axiom` Matrix

Because static checking faces fundamental computability bounds (Rice's theorem), Babble draws an explicit line between what the compiler **infers/verifies** and what requires **`axiom`**:

| Property | Inferred automatically? | Verified on body (without `axiom`)? | Requires `axiom` for non-trivial cases? |
|---|---|---|---|
| **`pure`** | **Yes** (fixed-point call graph; rejects `mut`) | **Yes** (sound; requires `hermetic` and zero `mut` targets) | Only for Java/FFI calls or benign hidden state (caches) |
| **`hermetic`** | **Yes** (fixed-point call graph; zero globals/I/O) | **Yes** (sound; compatible with `mut`) | Only for Java/FFI calls (`axiom hermetic mut ArrayList.add`) |
| **`mut`** (params / `this`) + exclusivity | **Yes** (minimal mutated set on untyped fns + static shallow call-site exclusivity + `hermetic`; deep disjointness only via `unique mut` in `P2`) | **Yes** (sound; mutually exclusive with `pure`, compatible with `hermetic`) | Only for Java methods (`axiom mut ...` or `axiom hermetic mut ...`) |
| **`borrow`** (params / `this`) | **Yes** (intra/inter-procedural escape analysis) | **Yes** (sound) | Only for Java methods (`axiom pure borrow length(...)`) |
| **`unique`** *(`BIP007-P2`)* (return & moved params) | **Yes** for `unique` local builders & returns (deep ownership, §5.2) | **Yes** (definite-unassignment + no shared mutable object stored into the graph) | Needed when Java methods allocate/return isolated graphs (`axiom pure unique parse(...)`) |
| **`deterministic`** | **Yes** (fixed-point; forbids clocks/RNG/mutable globals) | **Yes** (sound) | Only for benign lazy memoization (`String.hashCode`) or Java |
| **`nothrow`** | **Yes** (conservative: no `assert`/`suppose`, failing `as` casts, or throwing Java calls) | **Yes** (conservative) | Needed when invariants prevent failure (e.g. bounds-checked Java indexing `list.get(i)` or validated `Integer.parseInt`) |
| **`idempotent`** | Trivial forwarding only | Trivial forwarding / invariant overwrite only | **Yes** (`axiom pure deterministic idempotent abs`, `axiom mut idempotent set.add`) |
| **`commutative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... commutative`) |
| **`associative`** | Direct single-op wrapper only | Direct single-op wrapper only | **Yes** (`axiom ... associative`) |
| **`monotone` / `injective`** | **Compositional** ($f \circ g$ preserves `monotone` and `injective`) | **Compositional** over primitive axioms | Base arithmetic/conversions need `axiom` (built-ins pre-seeded per §4.6: no `monotone` for wrapping `int` arithmetic) |

### 3.4 Subtyping and Effect Lattice

Each property $p$ defines a simple boolean partial order: having $p$ is strictly stronger (more constrained) than lacking $p$:

$$\text{has } p \quad <:\quad \text{may lack } p$$

For a function signature `(p1: T1, ..., mut borrow pk: Tk): R` (extended with `unique` in `BIP007-P2`) with effect set $E$:

* **Guarantee covariance (function-level effects, `borrow` params, and `BIP007-P2` `unique` returns):**
  * `actual` satisfies `target` iff $E_{\text{target}} \subseteq E_{\text{actual}}$, `target.borrow(i) => actual.borrow(i)` (promising not to escape satisfies a caller allowing escape), and (in `BIP007-P2`) `target.uniqueReturn => actual.uniqueReturn` (`unique R <: R`).
  * `pure deterministic nothrow (borrow x: Point): Path` `<:` `pure deterministic (x: Point): Path` `<:` `(x: Point): Path` (and `unique` return satisfies non-`unique` return in `P2`).
* **Mutability & Ownership contravariance-of-permission:**
  * Because `pure` forbids all `mut` parameters and `mut` `this`, `pure` is strictly disjoint from `mut` (`pure` signatures never carry `mut`).
  * Across **all** `Type.Function` signatures (whether or not function-level effect modifiers are present), absence of `mut` on a parameter (or `this`) means that parameter (or `this`) is **not mutated**: `actual` may declare `mut` on parameter $i$ (or `this`) only if `target` also declares `mut` on parameter $i$ (or `this`). Adding an orthogonal modifier like `nothrow` to `(x: List(int)): int` therefore never changes the meaning of absent `mut`:
    * `pure deterministic (x: List(int)): int` `<:` `deterministic (mut x: List(int)): int`, **not** the reverse! (Promising no mutation at all satisfies a caller slot that permits mutating `x` without global effects.)
  * *(In `BIP007-P2`)* On parameters, demanding ownership (`unique p`) is *stricter* on the caller: a callee taking an ordinary non-`unique` `(p: T)` satisfies a slot expecting `(unique p: T)` (`pure (p: Vec) <: pure (unique p: Vec)`), **not** the reverse!
* **Unconstrained vs. Constrained Function Types:** An **unconstrained** function type `(x: T): R` (empty effect set, no `mut`/`borrow`—or `unique` in `P2`) accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects, while callers receive no optimization or aliasing guarantees; mutating parameter $i$ still requires `(mut x: T): R` uniformly across all `Type.Function`s. Under BIP008, `any` / `any?` is never assignable to any `Type.Function` (constrained or unconstrained), and `Type.Function` is non-nullable (`null` is only accepted by `((x: T): R)?`). Inline lambdas and un-reassigned `def`s obtain their concrete `Type.Function` and `EffectSet` via bidirectional local type inference.

---

## 4. Syntax, Semantics, and Language Rules

### 4.1 Syntax in `Babble.g4`

Effects compose naturally in front of function literals, parameter declarations, function types, and external Java declarations:

```babble
;; Host axioms declared before use (within a file, TypeChecker collects axioms order-independently in a pre-pass):
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom pure deterministic java.util.HashMap()        ;; Class() targets public constructors only (leaving put/clear untouched)
axiom hermetic mut deterministic java.util.HashMap.put

;; 1. Verified function literals (compiler proves all listed properties):
def hypot = pure deterministic (a: double, b: double): double -> (
    java.lang.Math.sqrt(a * a + b * b)
)

;; 2. Parameter & receiver mutability (`mut` — never combined with `pure`), `borrow`, and (`BIP007-P2`) `unique`:
def appendAll = deterministic (mut dst: java.util.ArrayList(string), borrow src: java.util.List(string)) -> (
    ... ;; `dst` is mutated; `src` is untouched; compiler infers `hermetic`!
    ;; Plain `mut` does not guarantee deep disjointness: `dst` and `src` may share state. Use `unique mut` (`BIP007-P2`) for that (§8.5).
)

;; `BIP007-P2` (`RM004`) unique builder & FBIP:
def buildIndex = pure deterministic (n: int): unique java.util.HashMap(int, int) -> (
    def m = java.util.HashMap(int, int)()
    ... ;; calling `axiom hermetic mut deterministic` methods (`HashMap.put`) on fresh local `m` stays `pure deterministic`!
    ... ;; `m` stays unique because only primitives (deeply immutable) are stored into it
    m
)

def sortInPlace = deterministic (unique mut xs: java.util.List(int)): unique java.util.List(int) -> (
    ... ;; mutates owned `xs` in place (zero globals/I/O) and returns unique ownership (FBIP)
    xs
)

def Counter = class (def count: int = 0) (
    def peek = pure deterministic (): int -> ( this.count )
    def add  = mut deterministic (delta: int) -> ( this.count = this.count + delta )
)

;; 3. Unannotated functions (fully inferred by the compiler!):
def square = (x: int): int -> ( x * x )   ;; inferred: pure deterministic nothrow (`x` is statically `int`, §3.2)

;; 4. Axiomatic declarations (trusted contract, no proof checked on body/target; declaration-level only per `BIP007-D4`):
def fastFib = axiom pure deterministic (n: int): int -> ( ... )
axiom mut idempotent commutative java.util.HashSet.add

;; 5. Explicit opt-out (suppresses inference / opts out value defaults):
def debugLog = impure (msg: string) -> ( ... )
impure java.net.URL.equals

;; 6. Function types & (`BIP007-P2`) ownership handoff (`move` / `freeze`):
def mapInts = (items: java.util.List(int), fn: pure (x: int): int): java.util.List(int) -> ( ... )
def reduceInts = (items: java.util.List(int), combine: pure deterministic associative (a: int, b: int): int): int -> ( ... )
def frozenIndex = freeze(buildIndex(1000))      ;; (`BIP007-P2`) zero-copy conversion; type `frozen java.util.HashMap(int, int)`
def sortedCopy = (n: int): unique java.util.List(int) -> (
    def myList = makeList(n)                    ;; (`BIP007-P2`) unique local (ownership is tracked for locals only)
    sortInPlace(move myList)                    ;; (`BIP007-P2`) transfers ownership; `myList` unusable afterward
)
```

**Grammar additions (`Babble.g4`) & canonical formatting (`Formatter`):**
* **`BIP007-P1` (`RM002`):**
  * Contextual effect keywords (disambiguated via semantic predicates on `ID`, matching the existing `value` class modifier so names like `pure`, `mut`, `borrow`, `move` stay valid identifiers in ordinary code): `axiom`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `impure`.
  * `# functionLiteral`: `(AXIOM effectModifier+ | IMPURE | effectModifier*) parametersDeclaration (':' type)? '->' NL* functionBlock=block`.
  * `# parameterDeclaration`: `PRIVATE? DEF? (MUT | BORROW)* ID (':' type)? ('=' NL* defaultValue=expression)?`.
  * `# functionType`: `effectModifier* parametersDeclaration (':' type)?` (note: `axiom` and `impure` are invalid inside function types—`axiom` is a definition-site mechanism, never part of a caller-visible type!).
  * `# effectDeclaration`: `EXPORT? (AXIOM effectModifier+ | IMPURE) target=expression ('(' ')')?` (targeting Java methods, constructors via `Class()`, bare classes, or `import("...")` bindings at declaration granularity per `BIP007-D4`; optional `()` restricts a class target to its public constructors only). Inline `axiom ( ... )` expression blocks are forbidden (`BIP007-D4`).
  * `Formatter` follows Babble's existing tight-colon style (`(mut x: T): R -> (...)` with no space before `:`, space after `effectModifier` before `(`, and `()` on `Class()` targets marked in `callParentheses` so `java.util.HashMap()` stays tight).
* **`BIP007-P2` (`RM004`) additions:**
  * Contextual keywords `unique`, `move`, `freeze`, `frozen`.
  * `# simpleType`: `FROZEN? ID ('.' ID)* typeArguments?` (the `frozen T` type of §3.2, including parameterized types such as `frozen java.util.HashMap(int, int)`).
  * Extend `# parameterDeclaration` with `UNIQUE` (`(MUT | BORROW | UNIQUE)*`), and `# functionLiteral` / `# functionType` returns with `':' UNIQUE? type`.
  * `# moveExpression`: `MOVE ID` (alongside built-in `freeze(expr)`).

> **Syntax order:** `axiom` (when present) appears first before one or more effect modifiers (requiring at least one modifier or `mut` parameter); `impure` stands alone and cannot combine with `axiom` or positive effects; **`pure` and `mut` (on receiver or parameters) cannot be combined**. Function-level `effectModifier`s (`pure`, `deterministic`, etc.) and parameter modifiers (`(mut | borrow)*` in `P1`, `(mut | borrow | unique)*` in `P2`) may each appear in arbitrary order and are normalized into an `EffectSet` and `Type.Parameter` flags (`unique borrow` is an exclusive borrow; plain `unique` consumes).
>
> **Grammar disambiguation:** All effect keywords remain valid contextual `ID` predicates outside their syntactic positions so existing code continues to parse. In expression position, `effectModifier` tokens match `#functionLiteral` only when followed by `parametersDeclaration (':' ...)? '->'` (so `pure(x)` still parses as a function call, unambiguous in ANTLR4 ALL(*) with no newline before `(`); `#effectDeclaration` matches at declaration level rather than inline inside arbitrary expression subterms (`BIP007-D4`); `#moveExpression` (`BIP007-P2`) matches `MOVE ID` specifically (never binary subtraction); and in type position (`:` prefix), `#simpleType` starts with `FROZEN? ID` while `#functionType` starts with `effectModifier* '('`, leaving the two alternatives disjoint from `#nullType` (`NULL`) and `#parenType` (`'(' NL* type NL* ')'`). `freeze(expr)` (`BIP007-P2`) is a regular built-in function call.

### 4.2 Local State, Unique Objects, Exclusivity, Ownership, and Outer State

Inside any function whose checked or inferred effect includes **`pure`** (and across call sites checking `mut` / `borrow` / `unique`):

1. **Local variables (`def x = ...`) may be reassigned freely** inside the function (`def i = 0; i = i + 1`). Local stack state is invisible outside the call.
2. **Fresh local object mutation (`unique`, `BIP007-P2` / `RM004`):**
   * *In `BIP007-P1` (`RM002`):* Before `unique` ownership lands, mutating any object (even a freshly constructed local such as `def tmp = Counter(0); tmp.add(1)`) is disallowed inside `pure`/`hermetic` functions, while local `def` variable reassignments remain valid.
   * *In `BIP007-P2` (`RM004`):* A local `def` is **`unique`** if its initializer (and any reassignment) creates a new instance via a `pure` constructor, a `unique`-returning call, or an `object (...)` literal **whose arguments are all `unique` (moved in) or deeply immutable**, and it stays `unique` while everything stored into it is `unique` or deeply immutable (§3.2). Mutating a `unique` local via `hermetic mut` helpers does **not** require `mut` and does **not** break `pure` or `deterministic`. Only `unique` locals can be moved, frozen, or returned as `unique`; objects read out of a `unique` local belong to its owned graph and may be mutated in place, but storing them anywhere else (or storing a `@PARAM` / `@EXTERNAL` value into the local) demotes the local to `@EXTERNAL`.
3. **Parameters and `this` without `mut` are strictly unmutated:**
   * `this.x = ...`, `param.x = ...`, and calling any `mut` method `param.mutMethod()` are **rejected** unless `mut` is declared on `this` or `param` (with `mut` inferred only on completely untyped functions having no effect modifiers, no parameter `: Type`, and no `: R` return type).
   * Aliasing a non-`mut` parameter (`def alias = param; alias.x = 1`, or derived sub-references `def c = param.child; c.x = 1`) is rejected in `BIP007-P1` by local `@PARAM`/`@THIS` alias tracking in `TypeChecker`.
4. **Call-Site `mut` Exclusivity:**
   * At any call passing an expression `arg` to a `mut` parameter (or calling a `mut` method `arg.m(...)`), `arg` cannot be the same object as any other argument (or `this`) passed to the same call.
   * In **`BIP007-P1` (`RM002`)**, this check is purely static in `TypeChecker`: it rejects same bindings, tracked local aliases (`def y = x; f(x, y)`), and identical member paths (`a.b.m(a.b)`), while permitting distinct locals/expressions (`f(list.get(i), list.get(j))`) and immutable `string` receivers (which never require `mut`). An optional runtime `Values.same(a, b)` exclusivity fallback for dynamic expressions is deferred to `BIP007-P2` (`RM004`).
   * This check is shallow and gives **no deep non-aliasing guarantee** (§3.2). Deep disjointness comes only from `unique mut` parameters (`BIP007-P2`), which need no check: the argument must be a `unique` local, which cannot also appear as another argument, be captured, or be reachable from a global.
5. **Borrow Escape (`BIP007-P1`) & Ownership Transfer (`BIP007-P2`):**
   * Inside a function declaring `(borrow p: T)`, assigning `p` (or any mutable sub-reference of `p`) to fields of `this`, another parameter, an outer/global variable, a returning closure, or returning `p` directly is rejected at compile time (`BIP007-P1`).
   * *(`BIP007-P2` / `RM004`):* Passing an argument to a `unique` parameter, passing it to `freeze(...)`, or returning a `unique` local consumes ownership via `move expr` (inserted implicitly when the compiler proves it is the variable's last use). Definite-unassignment analysis rejects any subsequent read or write to a moved variable. Passing it to a `unique borrow` parameter is an exclusive borrow instead: nothing is consumed. Only function-local bindings that no closure captures can be `unique` and moved; top-level, package, static and captured bindings are `@EXTERNAL`, and `move` on them is rejected (`Cannot move x : not a local variable`).
6. **Outer variables and global mutable singletons:**
   * **Writing** any outer `def`, mutable `object` singleton field, or `static` field is forbidden in `pure` and `hermetic` (clears `hermetic`), except when a frame-local (`borrow`) helper closure reassigns an unescaped local `def` (`BIP007-P1`) or mutates a `unique` local (`BIP007-P2`) of its enclosing function `F` (§4.5).
   * **Reading** a reassigned outer `def`, non-`final` Java static field, or mutable outer singleton/field (even reached transitively through an outer `value class`) performs no observable write: it stays valid in **`pure`**, but is forbidden in **`deterministic`**.
   * Reading immutable outer `def`s (never reassigned and deeply `value class` / `freeze` / primitive / `pure` function) is valid in both `pure` and `deterministic`.

### 4.3 `value class`es, `object` Singletons, `static` Blocks, and Standard Value Protocol

1. **`value class` defaults:**
   * A `value class` constructor is **verified `pure deterministic`** (its parameter defaults and top-level `classBlock` expressions must satisfy `pure deterministic`; fields are read-only at runtime in `BabbleInstance.setMember`).
   * Unannotated instance methods of a `value class` use **Inferred Mode** (§3.1)—they automatically infer `pure deterministic` (and `nothrow` where applicable) when their body avoids I/O and mutable globals, while unannotated methods that call `println` or performing Java I/O silently infer unconstrained rather than failing compilation, preserving backward compatibility for unannotated scripts (§1.3). Explicitly annotated methods on a `value class` are verified normally.
   * Note: Because `value class` instances may hold references to mutable objects passed in at construction time, transitivity checks inspect field types when deciding whether the built-in structural `hashCode`/`toString` are `deterministic`: they hash and print the fields, so they are `deterministic` only for a deeply immutable `value class` (a field holding a non-value instance contributes an identity hash).
2. **Standard Value Protocol (`equals`, `hashCode`, `compareTo`, `toString`):**
   * Babble's `==` and `!=` (`Operators.equal`) use structural field equality on `value class`es, object identity on regular `BabbleInstance`s, and `Object.equals` on Java objects—never invoking a user-defined Babble `equals`, `hashCode`, or `compareTo` method. Ordering operators (`<`, `<=`, `>`, `>=`) operate on primitives, `string`, or Java `Comparable` instances of the same class. Only `toString` is user-definable on Babble classes (`string + x` invokes `BabbleInstance.toString()`, which delegates to a 0-arg `toString` method if defined).
   * Consequently, on **Java classes**, `equals` and `compareTo` **default to `pure deterministic`** (while Babble's built-in `value class` structural equality and `BabbleInstance` identity equality are intrinsically `pure deterministic nothrow`), and immutable **`string` (`java.lang.String`) receivers never require `mut`**.
   * On Java `hashCode` and Java/Babble `toString`, defaults are **`pure deterministic` only where they do not derive from object identity**: on a Java class that overrides them (declaring class other than `java.lang.Object`), on a deeply immutable `value class`, and on a Babble class defining a verified/inferred `pure deterministic` `toString`. The inherited `Object.hashCode`/`Object.toString` and the default ones of a non-value `BabbleInstance` are identity-derived and differ between runs: they default to **`pure` only**, so nothing that reaches them is folded at compile time or memoized.
   * Explicit `impure java.net.URL.equals` / `impure` opt-out stays available when a Java override performs DNS or mutates external state.
   * For a `value class`, `TypeChecker` determines whether the built-in structural `toString()` / `hashCode()` is `pure deterministic` or `pure`-only by inspecting the declared field types statically (deeply immutable `value class` $\to$ `pure deterministic`; fields that may hold non-value instances $\to$ `pure` only; fields whose own `toString` may be unconstrained $\to$ unconstrained). No runtime effect propagation inside `BabbleInstance.toString()` is needed.
3. **Inline `object (...)` and `static` blocks:**
   * An `object (...)` expression or nested `class`/`interface` `static` block executed inside a `pure` or `deterministic` function is checked under the enclosing function's active constraints (cannot secretly print or mutate globals during class initialization).

### 4.4 Interfaces, Overrides, and Covariance

Interfaces can require arbitrary effect & algebraic contracts on members and default methods:

```babble
def Reducer = interface (
    def zero: pure deterministic nothrow (): int
    def combine: pure deterministic nothrow associative commutative (a: int, b: int): int
    def mergeInto: mut commutative (delta: int)
)
```

* Implementing or overriding an interface member must satisfy `actual <: required` across both `EffectSet` and `mut` permissions.
* Example: `pure deterministic` cannot satisfy an interface expecting `pure deterministic associative commutative`, whereas `axiom pure deterministic nothrow associative commutative` **does**.
* `pure deterministic ()` (non-`mut` `this`) CAN implement a `mut` slot `mut deterministic ()` (stronger guarantee satisfies weaker caller requirement), while `mut ()` CANNOT implement `pure ()`.

### 4.5 Nested Functions and Closures

Every function literal creates its own effect context (`inferred`, `verified`, or `axiom`):

* **Constructing a less-constrained closure inside a `pure deterministic` function** (e.g., returning an `impure` logger closure `() -> ( println(msg) )`) is `pure deterministic` itself (building the closure has no side effects and captures immutable `msg`), **provided** the inner closure does not capture a `mut` mutation of the enclosing function's non-`mut` parameters or `this`.
* **Calling** that closure inside the enclosing `pure deterministic` function requires its effect profile to satisfy the enclosing requirement (`actual <: enclosing`).
* **Frame-local (`borrow`) helper closures:** When a closure literal defined inside function `F` never escapes `F` (it is only invoked directly inside `F` or passed to `borrow` parameters within `F`), any reads or reassignments of `F`'s otherwise-uncaptured local `def`s (`BIP007-P1`, plus mutations of `F`'s `unique` locals in `BIP007-P2`) performed by that helper closure are attributed to `F`'s own local frame rather than `@EXTERNAL` outer state—allowing `F` to use local helper closures (such as `countWhere` over local loop accumulators) and stay `pure deterministic`.
* **Higher-order helpers & bounded call-site specialization:** Rather than introducing Koka-style effect-row variables into Babble's type signatures, `TypeChecker` evaluates higher-order Babble helpers whose effects depend on a parameter call `f(...)` (e.g., `def apply = (f, x) -> ( f(x) )`) via bounded call-site specialization for statically resolved concrete Babble functions and methods (intra-file definitions and statically imported module exports, excluding `impure` callables and abstract interface dispatches) using the caller-supplied `Type.Function` or contextual lambda arguments.

### 4.6 Built-ins, Operators, and Numeric Semantics

| Built-in / Operator | Intrinsic Effect Profile | Notes |
|---|---|---|
| `same(a, b)`, `classof(v)`, `x is T` | `pure deterministic nothrow` | Pointer/class/type inspection only (`same` is also `commutative` and `borrow`; `x is T` also establishes flow-sensitive `Narrowing`). |
| `freeze(unique v)` *(`BIP007-P2`)* | `pure deterministic nothrow` | Consumes a `unique` mutable graph and yields a `frozen T` (purely static type transition; identity no-op at runtime, §3.2). |
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
| `not a`, short-circuit `a and b`, `a or b` | `pure deterministic nothrow` (when `bool` operands are `pure deterministic nothrow`) | `a and b` / `a or b` only evaluate `b` conditionally; `b` is checked under flow-sensitive `Narrowing` (`!= null`, `is T`), establishing `nothrow` on guarded branches without allowing optimizers to reorder `b` ahead of guard `a` (`BIP007-D1`). |
| Member selection `a.x` / `a?.x` | `pure deterministic nothrow` | Under BIP008, `a.x` is allowed only when `a` is statically non-nullable `T` (or narrowed via `!= null` / `is T` per `BIP007-D1`), and `a?.x` handles `T?` by short-circuiting to `null` (and `a?.m(...)` inherits `m`'s effects). |

---

## 5. Inference Engine, Verification, and Optimizations

### 5.1 Fixed-Point Effect Inference (Call-Graph SCC in `TypeChecker`)

To minimize annotation burden, Babble maximizes inference across unannotated functions in the active compilation unit:

1. **Call-Graph Construction:** Build a static call graph over all functions, methods, constructors, closures, and `value class` bodies in `TypeChecker` (after `BlockScopes`, `TypeChecks`, and `Resolver`, alongside BIP008's bidirectional local type inference, call-site parameter inference `BIP008-P7`, generics instantiation, and flow-sensitive `is`/`!= null` `Narrowing`—where every expression `Type`, `ClassShape`, and static module `ModuleInterface` type is statically known).
2. **SCC Condensation (Bottom-Up Fixed Point):** Partition into Strongly Connected Components (handling direct and mutual recursion in `recurse` or mutually recursive functions).
3. **Optimistic Initialization (per unannotated node):**
   * *(`BIP007-P1`):* Start with `{hermetic, deterministic, nothrow}`, `mutParams = {}`, `borrowParams = allParams`, `mutThis = false`, `borrowThis = true`. (`pure` holds after convergence iff `hermetic && mutParams.isEmpty() && !mutThis`.)
   * *(`BIP007-P2` additions):* `uniqueReturn = true` and `@TERMINATING`.
   * Functions explicitly marked `impure` start and stay at `{}` (`hermetic = false`, `borrowParams = {}`, `borrowThis = false`; and `uniqueReturn = false` in `P2`).
   * Functions marked `axiom` are initialized directly to their declared `axiom` `EffectSet` / parameter flags (`axiom pure ...` implies `hermetic`, and `axiom hermetic mut ...` sets `hermetic`) and excluded from downward weakening.
4. **Transfer & Monotone Fixed-Point Iteration:**
   * **`mut` inference scope:** `mutParams` and `mutThis` are inferred **only** on completely untyped functions (no effect modifiers, no parameter `: Type`, and no `: R` return type). On any function carrying effect modifiers, any parameter type, or `: R`, `mutParams`/`mutThis` stay fixed to explicitly declared `mut` annotations, and mutating an unannotated parameter/`this` is rejected immediately as a verification error.
   * **Field writes / calls to `mut` / unconstrained callees:** In `BIP007-P2`, if target is `unique`, keep `hermetic` and `mutParams` unchanged (provided the called `mut` method/helper itself has `hermetic`!). In `BIP007-P1` (before `unique`), mutating any object inside a `pure`/`hermetic` function fails verification (or clears `hermetic` on an unannotated function unless targeting an inferred `mut` param/`this` of a completely untyped function). If target is `@PARAM(i)` or `@THIS`, record mutation of $i$ / `this` (preventing `pure`); if target is `@EXTERNAL` or an unconstrained/I/O Java method, clear `hermetic` (dropping `pure`), `deterministic`, and `nothrow`. Any callback invoked inside a verified `pure` or `deterministic` function must carry a constrained `Type.Function`—which `TypeChecker` enforces statically at all call sites (`any`/`any?` cannot be called or assigned to `Type.Function`, §3.4). Also check shallow call-site **`mut` exclusivity** across arguments (static conflict checks in `P1`), and (`P2`) that every argument to a `unique` slot is a `unique` local.
   * **Finalize `pure`:** After convergence, `pure` is set iff `hermetic && mutParams.isEmpty() && !mutThis` (so `pure` **always** implies `hermetic` and zero external mutation).
   * **Escape & (`P2`) uniqueness transfer:** Storing `@PARAM(i)` (or derived sub-references) into `this`, another parameter, a global, an escaping closure, or returning it removes $i$ from `borrowParams` (and similarly clears `borrowThis` when `@THIS` escapes). *(In `BIP007-P2`)* returning any expression that may alias `@EXTERNAL`, non-`unique` `@PARAM`, or `@THIS` drops `uniqueReturn`.
   * **Reads / non-determinism:** If expression reads a reassigned outer `def` (outside a frame-local `borrow` closure, §4.5), non-`final` static field, mutable singleton field, or calls a non-`deterministic` callee, drop `deterministic`.
   * **Exceptions (`BIP007-D1`):** If expression executes `assert`/`suppose`, a runtime-checked `as` cast (not statically proven redundant), a Java boundary check (`JavaTypes.checkBoxed` or a generic `TypeVariable`/`WildcardType` return/field check unless covered by `axiom nothrow`), or calls a non-`nothrow` callee, drop `nothrow`. Guarded dereferences and branches narrowed by `Narrowing` (`!= null`, `is T`) establish `nothrow` on the guarded right-hand side (`BIP007-D1`).
   * **Static parameter conformance:** Babble-to-Babble calls are statically type-checked with zero runtime `Types.check` overhead (`BIP008-P5`), while `Faces` checks `JavaTypes.checkParameters` when Java calls into Babble.
   * **Compositional algebraic propagation:**
     * Composition $f(g(x))$ where both $f$ and $g$ are `monotone` $\implies$ `monotone`; same for `injective`.
     * Single-op wrapper `(a, b) -> ( op(a, b) )` inherits `commutative` / `associative` from `op` (and unary `idempotent`).
   * **`@TERMINATING` *(`BIP007-P2`)*:** Dropped if the body contains any `while` loop, calls `recurse` or another function in the same cyclic SCC, or calls any non-`@TERMINATING` callee.
5. **Verification Check (Verified Mode) & Module Export Contract (`BIP007-D2`):**
   * For every function with explicit non-`axiom` annotations, verify `inferred.satisfies(declared)` (including `declared.borrow ⊆ inferred.borrow`, `declared.borrowThis => inferred.borrowThis`, and in `P2` `declared.uniqueReturn => inferred.uniqueReturn`) and `inferred.mutSet ⊆ declared.mutSet`, plus definite-unassignment after `move`/`freeze` (`P2`). Emit targeted diagnostics if any obligation fails—while retaining *extra* inferred properties strictly for intra-module optimization of that function (`P2`; external callers rely only on the explicitly declared modifiers, §3.1).
   * Per **`BIP007-D2`**, unannotated `export def` exports its inferred `EffectSet` and inferred parameter flags across module boundaries via `ModuleInterface`, whereas exports with explicit modifiers or `impure` expose only their declared contract.

> **What is fixed-point iteration?**  
> Because functions can be directly or mutually recursive (`f` calls `g` and `g` calls `f`), a single top-down or bottom-up pass cannot always know every callee's effect summary upfront. Fixed-point iteration solves these cyclic dependencies by treating the call graph as a system of constraints $\mathbf{S} = F(\mathbf{S})$: starting from optimistic initial guesses, a worklist loop repeatedly recomputes each function's summary from its body and callees' current summaries until a full iteration produces no further changes (a **fixed point**, $F(\mathbf{S}) = \mathbf{S}$). Because transfer rules only shrink finite effect sets monotonically (and never re-add dropped guarantees), termination is guaranteed in finite steps—producing the tightest sound summary permitted by the lattice.  
> **Further reading:**
> * [Data-flow analysis (Wikipedia)](https://en.wikipedia.org/wiki/Data-flow_analysis) — overview of iterative equation solving and Kildall's worklist method.
> * [Kleene fixed-point theorem (Wikipedia)](https://en.wikipedia.org/wiki/Kleene_fixed-point_theorem) — order-theoretic guarantee that monotone iteration on a finite-height lattice converges to a fixed point.
> * Anders Møller & Michael I. Schwartzbach, [*Static Program Analysis*](https://cs.au.dk/~amoeller/spa/) (Aarhus University) — accessible textbook covering lattices, monotone frameworks, and worklist solvers.
> * Gary A. Kildall (1973), *"A Unified Approach to Global Program Optimization"*, Proc. ACM POPL — foundational paper introducing lattice-based fixed-point dataflow analysis.

### 5.2 Alias Tracking, Exclusivity, and (`BIP007-P2`) Deep Ownership (`unique`)

`BIP007-P1` tracks local `@PARAM(i)` / `@THIS` aliases, derived sub-references, control-flow merge joins, `borrow` escape, and static call-site `mut` exclusivity; `BIP007-P2` (`RM004`) adds `unique`, `move`, `@MOVED`, and `Type.Frozen`:

```babble
;; Full inference illustration (`BIP007-P2` adds `: unique Stats` local builder mutation & unique return):
axiom pure deterministic nothrow borrow java.util.List.size
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
Under `BIP007-P2`, `buildStats` has its return type `Stats` inferred by bidirectional local type inference, and automatically infers **`pure deterministic nothrow`**, **`borrow xs`** (`xs` is untouched and never escapes), AND **`unique` return** (`: unique Stats`)—because `s` was freshly constructed inside `buildStats`, `xs.size()` yields a primitive `int` rather than escaping `xs`, and zero aliases to `s` remain in `buildStats`.

Babble tracks these local points-to / ownership states per binding in `TypeChecker` (only for function-local bindings that no escaping closure captures; every other binding is `@EXTERNAL`):
* **`unique` *(`BIP007-P2` / `RM004`, deeply owned)*:** Bound solely to a `pure` constructor, `object` literal, `unique` return, or `unique` parameter whose arguments are `unique` (moved in) or deeply immutable, and everything stored into it so far was `unique` (moved in) or deeply immutable. Calling a `hermetic` `mut` helper/method on it leaves `hermetic` and `mutParams` untouched (so the caller stays `pure`!). Only a `unique` binding may be passed to `move`, a `unique` slot or `freeze(...)`, or returned as `unique`. Storing a `@PARAM`, `@EXTERNAL`, or `any`/`any?` value into it (at construction, via field write, or as an argument a Java method may retain) demotes it to `@EXTERNAL` (or `@PARAM`-tainted) for the rest of the function. *(In `BIP007-P1`, freshly allocated locals default to `@EXTERNAL` so alias tracking catches `@PARAM`/`@THIS` bypasses without allowing object mutation in `pure`/`hermetic` yet.)*
* **`@PARAM(i)` / `@THIS` *(`BIP007-P1`)*:** Aliases parameter $i$ or `this`. Any mutation checks or taints $i$ / `this` for `mut` (forbidding hidden mutation bypasses like `def a = p; a.x = 1`); escaping it into `this`, another parameter, an outer variable, an escaping closure, or the return value clears `borrow(i)` (or `borrowThis`).
* **Sub-reference derivation (`p.child`, `xs.get(0)`) *(`BIP007-P1` / `P2`)*:** Reading a non-deeply-immutable field or element from `@PARAM(i)` or `@THIS` inherits `@PARAM(i)` / `@THIS` on the derived value, so mutating or escaping `p.child` is tracked back to parameter $i$ / `this` (forbidding hidden mutation through aliases of non-`mut` parameters). *(In `BIP007-P2`)* reading a field from `unique` stays inside that owned graph (mutating it preserves `unique`, while storing it externally demotes the root to `@EXTERNAL`).
* **`@FROZEN` *(`BIP007-P2`)*:** Any binding whose type is deeply immutable (`frozen T` from `freeze(expr)` in `P2`, or deep `value class`, primitive, string, `null`, or unions/nullables thereof). Unlike the other states this one is carried by the type, so it holds for parameters, fields and top-level `def`s too.
* **`@MOVED` *(`BIP007-P2`)*:** After `move x` or `freeze(x)` (including implicit last-use move), `x` transitions to `@MOVED`; definite-unassignment rejects any subsequent read or write to `x`.
* **`@EXTERNAL` *(`BIP007-P1`)*:** Any reference stored to or loaded from top-level, package, static, or escaping-closure bindings, returned from an unconstrained call, or demoted from `unique` becomes `@EXTERNAL` (cannot be mutated in `pure`/`hermetic` or returned as `unique`).
* **Control-flow merge join ($\sqcup$):** At `if`/`else` joins and loop back-edges, local alias/ownership states join monotonically:
  * *(`BIP007-P1`)* `@PARAM(i)` $\sqcup$ `@PARAM(j)` = `@PARAM({i, j})` (subsequent mutation or escape taints all potentially aliased parameters); joining `@EXTERNAL` preserves `@PARAM(i)` tainting.
  * *(`BIP007-P2` additions)* `unique` $\sqcup$ `unique` = `unique`; `@MOVED` $\sqcup$ *any state* = `@MOVED` (a binding moved on either branch cannot be used after the merge until reassigned); mixing `unique` with `@PARAM(i)` / `@EXTERNAL` yields `@EXTERNAL` while preserving `@PARAM(i)` tainting.
* **Call-site `mut` exclusivity:** At `callee(a0, a1, ...)`, every `mut` argument `ai` must be a different object from every other `aj` and `this`:
  * *(`BIP007-P1`)* Checked purely statically in `TypeChecker`: rejects same bindings, tracked local aliases (`def y = x; f(x, y)`), and identical member paths (`a.b.m(a.b)`), while allowing distinct expressions (`f(list.get(i), list.get(j))`) and immutable `string` receivers.
  * *(`BIP007-P2`)* Optionally adds runtime `Values.same(a, b)` fallback checks for dynamic aliasing when desired. This check is shallow; only a `unique` argument in a `unique mut` slot is deeply disjoint (§3.2).

### 5.3 Concrete Compiler Optimizations Unlocked *(`BIP007-P2` / `P3`, `RM004`)*

In **`BIP007-P1` (`RM002`)**, resolved `EffectSet` and `mut`/`borrow` metadata are computed by `TypeChecker` and stored in `ScopeAnalysis`, `ModuleInterface`, and `AnalysisSession` with zero bytecode or runtime changes. In **`BIP007-P2` (`RM004`)**, `ScopeAnalysis` additionally publishes `unique` and `@TERMINATING`, and `CodeGenerator` and bytecode passes in `org.babblelang.compiler.optimizer.OptimizerBase` consume that metadata to enable:

1. **CSE, Memoization & LICM:**
   * All result reuse requires `f` to be **`pure deterministic`** and its **result type to be deeply immutable** (primitive, string, `frozen T`, deep `value class`). A function returning a new mutable object (any `unique` return, e.g. `buildIndex`) is never reused: two calls must yield two distinct objects, or a mutation of one would show in the other and `same` would change its answer. Such calls can still be reordered or dropped.
   * **CSE:** two occurrences of `f(a, b)` in one function collapse into one call when, in addition, the state the first call read is unchanged at the second: either all arguments are deeply immutable, or nothing in between may write to a graph reachable from them (no assignment through them, no plain-`mut` or unconstrained call that could reach them; see the caller-side rule in §3.2). When `f` is additionally **`commutative`** (on top of `pure deterministic`), `f(b, a)` also matches `f(a, b)`.
   * **Memoization across calls and compile-time folding:** additionally require **all arguments to be deeply immutable**, so that equal keys mean equal states for the lifetime of the cache. A call that can reach an identity-derived value (§4.3) is not `deterministic` and is never folded.
   * Repeated nested calls `norm(norm(s))` collapse to `norm(s)` iff `norm` is **`pure deterministic idempotent`** (value projection form `(T): T`, with the same conditions on `s` and the result).
2. **No-Alias (`unique mut`) Local/Field Load Hoisting & Redundant-Load Elimination:**
   * Because a **`unique mut dst`** parameter owns its whole graph, it shares no mutable object with `src`, `this`, captured variables or globals; loops writing `dst` can safely keep `src` fields in JVM locals, hoist invariant `src` reads out of loops, and eliminate redundant loads without runtime alias guards. Plain `mut dst` gives none of this: the shallow identity check does not exclude shared interior state.
3. **Predicate Push-Down & Push-Up (Stream / Query Pipelines):**
   * **Guard-safe filter swap (`BIP007-D1`):** `if p(x) and q(x)` $\leftrightarrow$ `if q(x) and p(x)` (put cheaper/more selective filter first) iff `p` and `q` are **`pure` and `nothrow`** and `q` does not depend on flow-sensitive `Narrowing` (`!= null`, `is T`) established by `p`.
   * **Push filter below expensive `map` / `join`:** `xs.map(f).filter(p)` can invert/evaluate `p` on raw keys before a costly step (`xs.filter(x -> p'(x)).map(f)` or `join(xs, ys).filter(p_on_x)` $\to$ `join(xs.filter(p_on_x), ys)`) iff `f` and `p` are **`pure deterministic`** and **`nothrow`**. Inverted range predicates (`f(x) > c`) rely on **`monotone`**, and equality / `distinct` push-down (`f(x) == f(y) <=> x == y`) relies on **`injective`**.
   * **Push-up / Factorization:** `union(a.filter(p), b.filter(p))` $\leftrightarrow$ `union(a, b).filter(p)` iff `p` is **`pure deterministic nothrow`**.
4. **Zero-Copy Builders (`freeze`) and Functional-But-In-Place (FBIP):**
   * **`unique` $\to$ `freeze`:** Imperatively populate a local index/collection (`unique`: only owned or deeply immutable contents), then call `freeze(idx)` to produce a deeply immutable `frozen T` snapshot with **zero copies and zero runtime overhead**.
   * **FBIP in-place reuse:** When a caller passes a dead/moved `unique` collection into a pure transform (`sort(move xs)` / `xs.map(f)`), specialize to mutate backing storage in place with zero heap allocation while preserving referential transparency.
5. **Stack Unboxing / Scalar Replacement (`borrow`):**
   * Temporary `value class` arguments or closure captures passed into `borrow` parameters can be unboxed into primitive JVM locals/registers across call boundaries in `CodeGenerator`, avoiding heap allocation even when JIT inlining stops.
6. **Reassociation & Dead-Expression Elimination:**
   * Reassociate `(x + c1) + c2` $\to$ `x + (c1 + c2)` when `+` is **`pure deterministic nothrow associative`** (valid for `int` / `string`, skipped for IEEE-754 `double`!).
   * **Unused `pure nothrow` expressions are a compile-time error *(`BIP007-P2` / `RM004`)*:** When a `pure nothrow` expression (such as `s.trim()` or `a + b`) appears in a discarded position in source (`f(x); e`), its result is unused and it has no side effects—almost always a bug. In `BIP007-P2` (`RM004`), rather than silently discarding it, `TypeChecker` reports it as a compile-time error (`Unused pure expression`; `assert`/`suppose` are not `nothrow` and remain valid in statement position), whereas `BIP007-P1` (`RM002`) leaves discarded source statement expressions accepted for compatibility with existing scripts.
   * **Optimizer dead-expression elimination:** When prior passes (inlining, CSE, constant folding, dead-branch pruning) leave a `pure nothrow` (and `@TERMINATING`) expression unused, the optimizer safely eliminates it.

### 5.4 Property-Based Verification of `axiom`s *(`BIP007-P3` / `RM004`, Test / Debug Aid)*

While `axiom` tells the compiler to skip compile-time proof, semantic axioms (`deterministic`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `nothrow`) are ideal targets for **automated property-based testing** in a `babble.checkAxioms` test mode (`BIP007-P3`):

* **Input generators:** `checkAxioms` provides built-in boundary and pseudo-random generators for primitives and core types (`int`, `double`, `bool`, `string`, and standard Java collections of generator-supported elements) and recursively constructs Babble `value class` instances whose fields have generator-supported types; functions with unsupported parameter types either use a test-registered generator or skip fuzzing with a diagnostic.
* For `axiom commutative`: sample pairs $(a, b)$ and assert `f(a, b) == f(b, a)` (or post-state equality on fresh `mut` receivers).
* For `axiom associative`: sample triples $(a, b, c)$ and assert `f(f(a, b), c) == f(a, f(b, c))`.
* For `axiom idempotent`: assert `f(f(x)) == f(x)` (or post-state equality after 1 vs 2 calls on fresh `mut` state).
* For `axiom deterministic`: invoke twice with equal cloned inputs and assert identical outputs.

This turns unchecked mathematical assumptions into continuously fuzz-tested invariants in CI.

---

## 6. Java Interop and Bytecode Analysis

Java methods and classes default to **unconstrained** (`EffectSet.EMPTY`, conservative `mut` assumed on mutable receivers/args), **except** the standard value protocol (§4.3.2): on Java classes, `equals` and `compareTo` default to `pure deterministic`, and so do `hashCode` and `toString` when overridden (while the identity-derived `Object.hashCode`/`Object.toString` default to `pure` only). Additionally, **immutable `string` (`java.lang.String`) receivers never require `mut`** (and never trigger `mut` exclusivity conflicts), while un-axiomatized non-protocol `String` methods and all other JDK classes/methods (`List.size`, `List.get`, `Math.*`, etc.) remain unconstrained until declared with `axiom`/`export axiom` or registered on the host engine. Any default can be opted out via `impure`.

### 6.1 In-Babble `axiom` and `impure` Declarations

```babble
;; Method-level axioms:
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom pure deterministic nothrow associative commutative java.lang.Integer.sum
axiom pure deterministic commutative java.lang.Math.addExact  ;; throws on overflow (not `nothrow`, not associative across overflow)
axiom pure deterministic nothrow commutative java.lang.Math.max
axiom pure java.nio.file.Files.readString                 ;; pure read, NOT deterministic!
def mkdirs = axiom idempotent java.nio.file.Files.createDirectories
axiom hermetic mut idempotent commutative java.util.HashSet.add

;; Constructor-only axioms (`Class()` marks public constructors without marking mutators like `clear`/`remove` pure):
axiom pure deterministic java.util.HashMap()

;; Class-level axioms (marks all public constructors and methods; individual methods/constructors can refine):
def BigDecimal = axiom pure deterministic import("java.math.BigDecimal")

;; Module re-export & per-runtime isolation:
export axiom pure deterministic nothrow java.lang.Math.sin
export impure java.net.URL.equals

;; Opting out default-pure value protocol methods:
impure java.net.URL.hashCode
```

* **Compile-time resolution & cross-module/session contracts (`BIP007-D2`, `BIP007-D4`):** `TypeChecker` resolves all `axiom` / `impure` declarations purely at compile time via `ScopeAnalysis` (persisted across REPL inputs in `AnalysisSession` alongside frozen inferred `EffectSet`s, and across modules via `ModuleInterface`), never in `ScriptRuntime` or global singletons. Per `BIP007-D4`, `axiom` is valid only at declaration/function granularity (no inline `axiom ( ... )` expression blocks).
* **Order-independent within a file:** `TypeChecker` collects all top-level and `package`-level `axiom` / `impure` declarations in a pre-pass before building the SCC call graph, so declaring an axiom before or after a caller in the same file has the exact same effect. `axiom` declarations are forbidden inside verified `pure`/`deterministic` function bodies (must appear at top-level, `package`, or unconstrained scope).
* **Method, constructor (`Class()`), and bare class targets:** A method-level target (`java.lang.Math.max`, `java.util.HashMap.put`) applies uniformly to **all overloads** of that method name on the target Java class (for instance methods, `mut` applies to the receiver `this`, and `borrow` on the declaration applies to `this` and all reference parameters). A constructor-only target (`axiom ... java.util.HashMap()`) applies strictly to **public constructors** of that class so mutating methods are not marked `pure`. A bare class-level declaration (`def BigDecimal = axiom ... import("java.math.BigDecimal")` or `axiom ... java.math.BigDecimal`) applies to **all public constructors and public methods** of that class, while constructor-only and method-level overrides on the same class take priority (§6.2).
* **`export` contracts & `export axiom` in `ModuleInterface` (`BIP007-D2`):** Per `BIP007-D2`, an unannotated `export def` exports its inferred `EffectSet` and parameter flags in `ModuleInterface`, while exports with explicit effect modifiers or `impure` expose only their declared contract. Separately, `export axiom ... <target>` / `export impure <target>` do not introduce a runtime value binding; instead, `ModuleInterface` carries an exported axiom table mapping `(Class<?>, memberName | "<init>" | "*") -> EffectSet`, which `TypeChecker` imports into the caller's `ScopeAnalysis` on static `import("...ba")`.

### 6.2 Host-Side Java Annotations & `BabbleScriptEngine` API

Public package **`org.babblelang.effects`** defines the `Effect` enum (`PURE`, `HERMETIC`, `MUT`, `BORROW`, `DETERMINISTIC`, `NOTHROW`, `IDEMPOTENT`, `COMMUTATIVE`, `ASSOCIATIVE`, `MONOTONE`, `INJECTIVE`, `IMPURE`, adding `UNIQUE` / `@Unique` in `BIP007-P2` / `RM004`) and composable Java annotations (`@Pure`, `@Hermetic`, `@Mut`, `@Borrow`, `@Deterministic`, `@Nothrow`, `@Idempotent`, `@Commutative`, `@Associative`, `@Monotone`, `@Injective`, `@Impure`), keeping `org.babblelang.runtime` and `org.babblelang.engine.impl.natives` completely free of effect types (`RuntimeBoundaryTestCase`). Java libraries exposing APIs to Babble can annotate classes, constructors, or methods directly:

```java
import org.babblelang.effects.*;

public final class Geo {
    @Pure @Deterministic @Nothrow
    public static double haversine(double lat1, double lon1, double lat2, double lon2) { ... }

    @Idempotent
    public static void ensureOutputDir(String dir) { ... }
}
```

Embedded hosts can register trusted JDK/third-party methods, constructors, or classes programmatically on `BabbleScriptEngine` (isolated per engine instance and read by `TypeChecker` at compile time), and optionally forbid untrusted scripts from declaring in-script axioms via `allowAxioms(false)`:

```java
engine.allowAxioms(false); // optional sandbox policy: reject script-level axiom/impure declarations
engine.assumeEffects(Math.class, "sqrt", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
engine.assumeConstructorEffects(HashMap.class, Effect.PURE, Effect.DETERMINISTIC);
engine.assumeEffects(Files.class, "readString", Effect.PURE); // read-only, non-deterministic
engine.assumeEffects(Files.class, "createDirectories", Effect.IDEMPOTENT);
engine.markImpure(URL.class, "equals", "hashCode");
```

Compile-time resolution priority in `TypeChecker`:
1. File / imported `ModuleInterface` / `AnalysisSession` method or constructor (`Class()`) override (`axiom` / `impure`), else
2. Engine-level `BabbleScriptEngine` method or constructor override (`assumeEffects` / `assumeConstructorEffects` / `markImpure`), else
3. Java method / constructor annotations (`@Pure`, `@Deterministic`, ...), else
4. File / module / session or engine bare-class override, else
5. Java class annotations, else
6. Default value protocol (`equals`, `compareTo`, and overridden `hashCode`/`toString` $\to$ `{PURE, DETERMINISTIC}`; `Object`'s own `hashCode`/`toString` $\to$ `{PURE}`), else
7. Unconstrained (`EffectSet.EMPTY`).

### 6.3 Java Callbacks and Interface Proxies (`Faces`)

When a verified `pure deterministic` Babble function passes a Babble `Callable` or `BabbleInstance` (wrapped via `Faces.function` or `Faces.create` into `Function`, `Predicate`, `Comparator`, etc.) to an axiomatized Java higher-order function such as `list.stream().map(fn)`:

* **Checked purely statically (`TypeChecker`):** Functional-interface arguments inside constrained scopes must have a statically known `Type.Function` (or implementing `BabbleClass`) whose effects satisfy the enclosing call's requirements (e.g., passing an unconstrained `(x) -> ( println(x) )` or an `any` value to `stream().map(...)` inside a `pure` caller is rejected at compile time). `Faces.FunctionHandler` and `Faces.FaceHandler` require zero effect-checking logic or frame context.

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

* **`EffectSet`** (immutable bitmask / enum set over `{PURE, HERMETIC, DETERMINISTIC, NOTHROW, IDEMPOTENT, COMMUTATIVE, ASSOCIATIVE, MONOTONE, INJECTIVE}` + internal compiler flags `boolean mutThis`, `boolean borrowThis`, explicit `boolean impureOptOut`, and in `BIP007-P2` `boolean uniqueResult`).
* **`Type.Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow)`** *(`+ boolean unique` in `BIP007-P2`)*: the single representation of per-parameter mutability, escape guarantee, and (`P2`) ownership (`(mut borrow dst: Buffer, borrow src: java.util.List(string))`).
* **`Type.Frozen(Type of)` *(`BIP007-P2` / `RM004`)*:** The `frozen T` type produced by `freeze` (simplified to `of` when `of.isDeeplyImmutable()`, and distributing over `Type.Union` so `frozen (A | B)` becomes `frozen A | frozen B` and `frozen (T?)` simplifies to `(frozen T)?`). Member reads and non-`mut` `borrow` method calls on `Frozen(T)` wrap their result `U` in `Frozen(U)`; `Frozen(T)` is not assignable to `ANY`, `ANY?`, or plain `T`, `ANY`/`ANY?` is not assignable to `Frozen(T)`, and `Frozen(ANY)` / `Frozen(ANY?)` (from Java methods returning `Object`) can only be narrowed (`is`) or cast (`as`) to deeply immutable concrete types (verified at runtime by `Types.check`). `Type.isDeeplyImmutable()` holds for primitives, strings, `NULL`, `Frozen`, `value class`es whose field types all satisfy it, and `Union`s whose members all satisfy it.
* **`Type.Function(List<Parameter> parameters, Type result, EffectSet effects)`**:
  * Invariant: `effects.contains(PURE)` implies `effects.contains(HERMETIC) && !effects.mutThis` and no parameter in `parameters` has `mut`.
  * Assignability (`TypeChecker`): `actual.mut(i) => target.mut(i)` and `actual.mutThis => target.mutThis` hold uniformly across **all** `Type.Function`s (even when `target.effects` is empty, so absent `mut` always means read-only, §3.4). When `target` is unconstrained (empty `effects` and no `mut`/`borrow`/`unique`), it accepts any non-`mut` `Type.Function` with arbitrary global/non-deterministic effects. When `target` is constrained (non-empty `effects` or any parameter/`this` modifier), `actual` must additionally satisfy `actual.effects.satisfies(target.effects)`, `target.borrowThis => actual.borrowThis`, `target.borrow(i) => actual.borrow(i)`, and (in `P2`) `target.uniqueResult => actual.uniqueResult` and `actual.uniqueParam(i) => target.uniqueParam(i)`. Neither unconstrained nor constrained `Type.Function` accepts `ANY`, `ANY?`, or `NULL`.

### 7.2 Zero Runtime Effect Machinery (`Types`, `babble-runtime`)

Because Babble is statically typed (`bip008_static-typing.md`), `TypeChecker` disallows assigning (`P2`) `frozen T` to/from `any`/`any?` (§3.2) or `any`/`any?` to `Type.Function` (§3.4), and `any` cannot be called or have members accessed without narrowing (`is`) or casting (`as`). Consequently, **the entire effect and ownership system is enforced at compile time with zero effect code in `org.babblelang.runtime` or `org.babblelang.engine.impl.natives` (`babble-runtime.jar`, enforced by `RuntimeBoundaryTestCase`)**, while public Java annotations and `Effect` live in `org.babblelang.effects`:

* `Types.check` (retained only for `as` casts, Java boundaries, and `setMember`, `bip008_static-typing.md` §7) requires no `fx|` or `frozen` descriptors (`as` already rejects function types statically and in `P2` cannot unwrap `frozen T`).
* `Callable`, `Function`, `CompiledFunction`, `CompiledMethod`, `JavaMethod`, `BabbleInstance`, `ScriptRuntime`, `Bootstrap`, `Calls`, `Faces`, and `Operators` carry no runtime `EffectSet` fields or effect-checking hooks.

### 7.3 Back-End & Optimizer Integration

1. **`BIP007-P1` (`RM002`) — 100% front-end:** Neither `Interpreter` nor `CodeGenerator` is modified in `BIP007-P1`. `TypeChecker` records resolved `EffectSet` and `mut`/`borrow` profiles in `ScopeAnalysis` (and `ModuleInterface` / `AnalysisSession`).
2. **`BIP007-P2` (`RM004`) — `freeze` & `OptimizerBase`:** `Interpreter` and `CodeGenerator` evaluate `freeze(expr)` as a zero-cost identity no-op (`expr`), `TypeChecker` additionally publishes `unique` and `@TERMINATING` metadata in `ScopeAnalysis`, and bytecode optimizer passes (`org.babblelang.compiler.optimizer.OptimizerBase` extensions) query `ScopeAnalysis` directly without re-running analysis.

---

## 8. Examples

### 8.1 Verified Pure Deterministic Code vs. `axiom` Java Interop

```babble
;; Declare trusted host axioms explicitly:
axiom pure deterministic nothrow java.lang.Math.sin
axiom pure deterministic nothrow java.lang.Math.cos
axiom pure deterministic nothrow java.lang.Math.sqrt
axiom pure java.nio.file.Files.readString   ;; side-effect-free, NOT deterministic!
axiom pure deterministic java.nio.file.Path.of  ;; may throw InvalidPathException (not `nothrow`)

package math (
    ;; Verified: compiler checks body satisfies `pure deterministic`.
    ;; Because `/` on `double` is IEEE-754 division (`Operators.div`) and never throws, `TypeChecker` also
    ;; infers `nothrow` quietly (`BIP007-P2` intra-module optimizer use, while external callers
    ;; rely only on the explicitly declared `pure deterministic` signature, §3.1).
    export def sqrtApprox = pure deterministic (x: double): double -> (
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
    export def dot2 = (ax: double, ay: double, bx: double, by: double): double -> (
        ax * bx + ay * by
    )
)

;; Rejected: `Files.readString` is `pure` but NOT `deterministic`!
def badMemo = pure deterministic (p: string): string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)

;; Accepted as `pure` (safe to discard/reorder across memory, not memoized across I/O):
def loadConfig = pure (p: string): string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(p))
)
```

### 8.2 Argument & Receiver `mut`, Confined `hermetic`, and (`BIP007-P2`) `unique` Locals

```babble
def Counter = class (def count: int = 0) (
    def peek = pure deterministic nothrow (): int -> ( this.count )
    ;; Inferred `hermetic` (mutates ONLY `this`, zero globals/I/O), never marked `pure`:
    def add  = mut deterministic nothrow (d: int) -> ( this.count = this.count + d )
)

;; `BIP007-P2` (`RM004`) strictly `pure`: calling `tmp.add` on fresh `unique` local `tmp` requires `unique` ownership:
def previewNext = pure deterministic (c: Counter, delta: int): int -> (
    def tmp = Counter(c.peek())   ;; unique local instance (argument `c.peek()` is a primitive `int`)
    tmp.add(delta)                ;; OK in `BIP007-P2`: `Counter.add` is `hermetic` (disallowed in `P1`)
    tmp.peek()
)

;; `BIP007-P1` (`RM002`) confined `mut` helper (mutates ONLY `dst`, not `pure`):
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
def encodeScore = axiom pure deterministic nothrow monotone injective (raw: int): double -> (
    raw * 10.0 + 5.0
)

def isEligible = (score: double): bool -> ( score > 100.0 )       ;; inferred: pure deterministic nothrow
def nonNegative = (raw: int): bool -> ( raw >= 0 )                ;; inferred: pure deterministic nothrow

;; Because `encodeScore`, `isEligible`, and `nonNegative` are all `pure deterministic nothrow`:
;; 1) (`BIP007-P2`–`P3`) Predicates in `filter` can be reordered freely by cost/selectivity without changing errors.
;; 2) (`BIP007-P3`) Filters can be pushed past `map(encodeScore)`.
;; 3) (`BIP007-P3`) With `monotone`, `encodeScore(raw) > 100` can even be rewritten/pruned on raw sorted chunks!
```

### 8.4 Algebraic Properties: `associative`, `commutative`, and `idempotent`

```babble
axiom pure deterministic java.util.HashSet()                      ;; constructor-only target (`HashSet.add` stays mut!)
axiom hermetic mut idempotent commutative java.util.HashSet.add
axiom idempotent java.nio.file.Files.createDirectories

def sumInt = (a: int, b: int): int -> ( a + b )   ;; inferred: pure deterministic nothrow associative commutative (single-op `int +` wrapper)

def reduceInts = (xs: java.util.List(int), seed: int, op: pure deterministic associative (a: int, b: int): int): int -> ( ... )
def forEachDir = (mut acc: java.util.HashSet(string), xs: java.util.List(string), f: idempotent commutative (mut s: java.util.HashSet(string), x: string)) -> ( ... )

;; 1) Tree reduction (`BIP007-D3`: explicit `seed`; contextual lambda typing infers `x: int` and `: int`):
def totalSq = reduceInts(mapInts(items, (x) -> ( x * x )), 0, sumInt)

;; 2) Order-independent, retry-safe accumulation (mut target + idempotent + commutative):
def collector = java.util.HashSet(string)()
def ensureAndTrack = axiom idempotent commutative (mut s: java.util.HashSet(string), dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    s.add(dir)
)
forEachDir(collector, dirs, ensureAndTrack)
```

### 8.5 Exclusivity, `unique` / `move` / `freeze` (`BIP007-P2`), and `borrow` (`BIP007-P1`)

```babble
def Vec = class (def data: java.util.ArrayList(double)) ( ... )

;; 1) (`BIP007-P2` / `RM004`) Deep disjointness (no-alias) through an exclusive borrow (`unique borrow mut`):
def addScaled = deterministic (mut borrow unique dst: Vec, borrow src: Vec, k: double) -> (
    ... ;; `dst` owns its whole graph: it shares nothing with `src` -> safe invariant-load hoisting & redundant-load elimination!
)
def scaleDemo = (src: Vec) -> (
    def v = Vec(java.util.ArrayList(double)())  ;; unique local: a fresh Vec over a fresh list
    addScaled(v, src, 2.0)                      ;; exclusive borrow: `v` is usable (and still unique) afterwards
    ;; addScaled(v, v, 2.0)                     ;; REJECTED: `v` is exclusively borrowed and cannot be passed twice
    ;; addScaled(src, v, 2.0)                   ;; REJECTED: `src` is a parameter, not unique
    ;; def w = Vec(src.data)                    ;; `w` is not unique (shares `src.data`): not accepted as `dst` either
    v
)
;; With plain `(mut dst: Vec, src: Vec)` all of these calls except `(v, v)` would be accepted, and the
;; compiler would assume nothing about aliasing: two distinct Vecs may share one `data` list.

;; 2) (`BIP007-P2` / `RM004`) Imperative local builder -> zero-copy `freeze` -> deeply immutable snapshot:
axiom pure deterministic java.util.HashMap()          ;; constructor-only target (`HashMap.put`/`clear` stay mut!)
axiom hermetic mut deterministic java.util.HashMap.put  ;; mutates only `this` (zero globals/I/O); retains its arguments
axiom pure deterministic borrow java.util.HashMap.get   ;; read-only lookup
def buildLookup = pure deterministic (n: int): unique java.util.HashMap(int, int) -> (
    def m = java.util.HashMap(int, int)()
    def i: int = 0
    while i < n then (
        m.put(i, i * i)   ;; on unique `m` this preserves `pure deterministic`; ints are deeply
        i = i + 1         ;; immutable, so `m` also stays unique
    )
    m   ;; nothing mutable is shared with anyone -> verified `unique` return!
)
;; Had `buildLookup` stored elements of a caller-supplied list into `m`, `m` would be demoted from unique:
;; the `unique` return and the `freeze` below would both be rejected (the "frozen" map would still
;; share mutable objects with the caller).
def readOnlyLookup = freeze(buildLookup(1000))  ;; compile-time transition (zero-cost no-op); type `frozen java.util.HashMap(int, int)`
def squareOf42 = readOnlyLookup.get(42) as int  ;; `get` returns `int?`, cast to `int` (or handled via `!= null`)
;; readOnlyLookup.put(1, 2)                     ;; REJECTED statically: `put` is `mut` on `frozen HashMap`
;; def leaked: any = readOnlyLookup             ;; REJECTED statically: `frozen T` cannot assign to `any`

;; 3) (`BIP007-P2` / `RM004`) Functional-but-in-place (FBIP) & definite-unassignment with `move` (locals only):
def sortInPlace = deterministic (unique mut xs: java.util.List(int)): unique java.util.List(int) -> ( ... )
def sortedSquares = (n: int): unique java.util.List(int) -> (
    def a = makeSquares(n)       ;; `makeSquares` returns `unique java.util.List(int)`: `a` is a unique local
    def b = sortInPlace(move a)  ;; `a` is sorted in place without cloning
    ;; a.size()                  ;; REJECTED: `a` was moved and cannot be used afterward!
    b
)
;; def top = makeSquares(10)
;; sortInPlace(move top)         ;; REJECTED: `top` is a top-level binding, visible to other code

;; 4) `borrow` (`BIP007-P1` verification / `BIP007-P2` scalar replacement & stack unboxing):
def distSq = pure deterministic nothrow (borrow p: Point, borrow q: Point): double -> (
    def dx = p.x - q.x
    def dy = p.y - q.y
    dx * dx + dy * dy
)
;; Calling `distSq(Point(x1, y1), Point(x2, y2))` passes raw doubles without heap allocation in `BIP007-P2`!
```

---

## 9. Design Decisions & Phased Implementation Plan

### 9.1 Settled Design Decisions

* **`BIP007-D1` (resolves `BIP007-Q1`) — Guard-safe `Narrowing` (`!= null`, `is T`) and `nothrow`:** `TypeChecker`'s flow-sensitive `Narrowing` (`!= null`, `is T`) establishes `nothrow` on guarded `if`/`while` branches and short-circuit `and`/`or` right-hand sides (`a != null and a.x > 0`), and `RM004` (`BIP007-P2`) optimizers must never reorder a narrowed predicate ahead of its guard; unconditional reordering is restricted strictly to mutually `nothrow` predicates with no guard dependencies.
* **`BIP007-D2` (resolves `BIP007-Q2`) — Cross-module export contracts (`ModuleInterface`):** Unannotated `export def` exports its **inferred** `EffectSet` and parameter flags (`mut`/`borrow`, plus `unique` in `P2`) across modules via `ModuleInterface`, maximizing cross-module inference; exports carrying explicit effect modifiers or `impure` expose strictly their declared contract so external callers never depend on uncontracted implementation details.
* **`BIP007-D3` (resolves `BIP007-Q3`) — No `identity(...)` clause on `associative`:** Reduction combiners supply explicit seeds as in §8.4; no `identity(...)` annotation attribute is included in `BIP007-P1` (`RM002`), keeping the surface vocabulary minimal while leaving optional monoid identity deferred.
* **`BIP007-D4` (resolves `BIP007-Q4`) — Declaration-only `axiom` granularity:** `axiom` stays strictly at declaration and function-literal granularity (`def f = axiom ... ->`, `axiom ... Target`, `def C = axiom ... import("...")`, `export axiom ...`, plus `Class()` for constructor-only targets); inline `axiom ( ... )` expression blocks are forbidden so trust boundaries remain explicit and auditable.

### 9.2 Phased Implementation Roadmap

1. **`BIP007-P1` (`RM002`) — Core Effect Model, SCC Fixed-Point Inference, Verification & `axiom` *(Implemented)*:**
   * Grammar & Formatting (`Babble.g4`, `Formatter`): add disambiguated contextual `ID` predicates for `axiom`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `impure` on function literals, parameter declarations, function types, and declaration-level `axiom`/`impure` rules (`Class()` constructor-only targets vs bare `Class`, `export axiom`/`export impure`), following canonical tight-colon formatting (`(mut x: T): R -> (...)` and tight `()` on `Class()`). (`unique`, `move`, `freeze`, and `frozen` syntax await `BIP007-P2`.)
   * Type System, Inference & Verification (`EffectSet`, `Type.Parameter(..., mut, borrow)`, `Type.Function`, `TypeChecker`): enforce uniform absent `mut` across typed functions while inferring `mut` on parameters/`this` **only** on completely untyped functions (no effect modifiers, parameter types, or `: R`, migrating existing scripts/examples as needed); implement SCC bottom-up fixed-point inference and verification (integrated with `BIP008` bidirectional/call-site inference, generics, and `Narrowing` per `BIP007-D1`) for `{pure, hermetic, mut, borrow, deterministic, nothrow}`; `@PARAM`/`@THIS` local alias and derived sub-reference tracking in `TypeChecker` (local `def` reassignments stay valid in `pure`, while mutating fresh local objects in `pure`/`hermetic` awaits `unique` in `P2`); static-only call-site `mut` exclusivity checking in `TypeChecker` (same bindings, tracked aliases, identical member paths; immutable `string` receivers never require `mut`; runtime `Values.same(a, b)` fallback deferred to `P2`); compositional algebraic properties + signature applicability validation; frame-local `borrow` closure attribution for unescaped local `def`s; bounded call-site effect specialization for statically resolved concrete Babble callables; standard value protocol defaults (`equals`, `hashCode`, `toString`, `compareTo`) and immutable `string` non-`mut` receivers while other JDK classes/methods default unconstrained until axiomatized or host-registered; `value class` constructor checks; effect-constrained `Type.Function` assignability (100% front-end with zero `org.babblelang.runtime`, `org.babblelang.engine.impl.natives`, `Interpreter`, or `CodeGenerator` changes); interface conformance (`actual <: required`); and covariance/contravariance subtyping.
   * Public Java API, Engine & Persistence (`org.babblelang.effects`, `BabbleScriptEngine`, `ModuleInterface`, `AnalysisSession`, `ScopeAnalysis`): add public package `org.babblelang.effects` with `Effect` enum and composable annotations (`@Pure`, `@Hermetic`, `@Mut`, `@Borrow`, `@Deterministic`, `@Nothrow`, `@Idempotent`, `@Commutative`, `@Associative`, `@Monotone`, `@Injective`, `@Impure`); order-independent pre-pass collection of file/`package` `axiom` and `impure` declarations (`BIP007-D4`); compile-time per-engine registries (`allowAxioms(false)`, `assumeEffects`, `assumeConstructorEffects`, `markImpure`); cross-module `ModuleInterface` export (`BIP007-D2`, including `export axiom` / `export impure` tables) and `AnalysisSession` persistence; record resolved `EffectSet`/`mut`/`borrow` metadata in `ScopeAnalysis`.
2. **`BIP007-P2` (`RM004`) — Deep Ownership (`unique` / `move` / `freeze` / `frozen`), `@Unique`, `@TERMINATING`, and Core Optimizations:**
   * Add contextual `unique`, `move`, `freeze`, and `frozen` syntax (`Babble.g4`/`Formatter`), `Type.Parameter(..., unique)`, `Type.Frozen`, and `@Unique` in `org.babblelang.effects`; implement full `unique`/`@PARAM(i)`/`@THIS`/`@MOVED`/`@EXTERNAL` ownership lattice enabling mutation of fresh `unique` local objects in `pure`/`hermetic`, sub-reference propagation, control-flow merge joins, definite-unassignment, implicit last-use `move`, purely static `Type.Frozen` (`freeze` zero-cost identity no-op in `Interpreter`/`CodeGenerator`), optional runtime `Values.same(a, b)` exclusivity fallback, and `@TERMINATING` inference.
   * Publish `unique` and `@TERMINATING` metadata alongside `EffectSet`/`borrow` in `ScopeAnalysis` and wire `CodeGenerator` and bytecode passes in `org.babblelang.compiler.optimizer.OptimizerBase`: CSE / constant folding (`pure deterministic` on deeply immutable args/results), deep no-alias invariant load hoisting & redundant-load elimination (`unique mut` parameters), scalar replacement/unboxing (`borrow`), zero-copy `freeze` / FBIP reuse (`unique`), guard-safe predicate reordering & hoisting (`pure deterministic nothrow`, `BIP007-D1`), dead-expression elimination (`pure nothrow` + `@TERMINATING`, with unused `pure nothrow` source expressions reported as compile-time errors), and loop reassociation (`associative`).
3. **`BIP007-P3` (`RM004`) — Algebraic Rewrite Rules, ASM Bytecode Suggester & Axiom Fuzzing:**
   * Add `monotone` range inversion and `injective` projection push-down in pipeline optimizers.
   * Add opt-in ASM bytecode analyzer (`ClassLoader` + escape analysis + seeded JVM `native` intrinsics) to suggest/auto-derive `pure`/`mut`/`deterministic`/`nothrow` on closed Java helpers.
   * Add property-based test harness (`babble.checkAxioms`) with built-in primitive, collection, and recursive `value class` generators to fuzz-test `axiom commutative`, `associative`, `idempotent`, and `deterministic` declarations in CI.

---

## 10. Future Directions

* **Thread Safety & Optimized Parallelization:** Once thread-safety annotations (e.g., `threadsafe`) are added to the effect system, the existing effect and ownership guarantees in BIP007 will directly enable safe, optimized parallelization of work without locks or defensive copying:
  * **`parallelMap` / `parallelFilter`:** Evaluating `pure threadsafe` callbacks independently across worker threads, with shared inputs guaranteed race-free via deeply immutable types (`frozen T`, deep `value class`, primitives) or exclusive `unique borrow` ownership.
  * **`parallelReduce`:** Splitting reductions into balanced parallel tree folds whenever the combiner is `pure deterministic threadsafe associative` (preserving partition order when non-commutative, or using arbitrary work-stealing when also `commutative`).
  * **`parallelForEach` & Lock-Free Accumulation:** Performing unordered concurrent updates on `mut threadsafe` targets via `commutative` (and `idempotent` for safe retries) operations, and transferring owned `unique` buffers across threads zero-copy via `move`.

