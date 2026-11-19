# BIP014: Compiler Optimization Passes

| | |
|---|---|
| **BIP** | 014 |
| **Date** | 2026-11-19 |
| **Status** | Draft (`BIP014-Q1`–`Q7` open) |
| **Depends on** | Bytecode compiler, `RM001` primitive lowering & `RM003` JVM/Java interface bridges ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) §19–§20), object model & `record`s ([bip002_object-model.md](bip002_object-model.md)), function effects, algebraic properties & ownership ([bip007_effects.md](bip007_effects.md), `BIP007-P1`–`P2`, `BIP007-D10`), mandatory static typing ([bip008_static-typing.md](bip008_static-typing.md)), `.batest` differential harness ([bip009_test-coverage.md](bip009_test-coverage.md)), modular front end & `LoweringAnalyzer` (`RM004`, [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), 8-primitive unboxing & core collections (`RM005`, [bip011_core-types.md](bip011_core-types.md)), predicate-free grammar (`RM006`, [bip012_grammar-improvements.md](bip012_grammar-improvements.md)), and optionally `for` loops ([bip013_iterators.md](bip013_iterators.md)) |
| **Scope** | In-scope optimization catalogue unlocked by `BIP007` annotations, SOTA compiler pass architecture survey, introduction of a typed compiler IR (`org.babblelang.ir`) and pass pipeline (`org.babblelang.opt`), local vs. graph-level optimization boundary, interaction with HotSpot C2 and the `Interpreter` reference oracle, and an incremental migration path from the current parse-tree `CodeGenerator` |

---

## 1. Executive Summary

`BIP007` (`BIP007-P1` already landed in `RM002`, with `BIP007-P2` adding `unique`, `frozen`, `move`, `freeze`, `discard`, and `@TERMINATING`) equips Babble with a rich, statically verified vocabulary of **memory effects**, **ownership guarantees**, **evaluation safety**, and **algebraic properties** (`pure`, `hermetic`, `mut`, `borrow`, `unique`, `frozen`, `deterministic`, `nothrow`, `@TERMINATING`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`). Per `BIP007-D10`, all bytecode and pipeline optimizations consuming those contracts were deferred to this dedicated proposal.

Today, Babble compiles directly from the ANTLR4 parse tree (`BabbleParser.FileContext`) plus side-table maps (`ScopeAnalysis`, `Resolution`) to ASM JVM bytecode in a single 5,996-line visitor (`CodeGenerator.java`). That architecture was ideal for reaching `RM001`–`RM006` quickly, and it can host a handful of **local, peephole-style** optimizations (such as literal constant folding). However, almost every high-value `BIP007` optimization—**common subexpression elimination (CSE), loop-invariant code motion (LICM), no-alias load hoisting, dead-code elimination (DCE), small-function & closure inlining, `borrow` scalar replacement, functional-but-in-place (FBIP) reuse, and algebraic/predicate rewrites**—requires inspecting and rewriting the surrounding control-flow and data-flow graph.

Drawing on state-of-the-art compiler engineering—specifically the **Nanopass Framework** (`[SWD05]`, `[KD13]`, Chez Scheme), **SSA CFG compilers** (`[LA04]` LLVM, `[Mer25]` **V8 Turboshaft**'s departure from Sea-of-Nodes), **multi-level IRs** (`[LAB+21]` MLIR, Rust HIR/MIR), and **acyclic e-graphs** (`[WNW+21]`, `[Fal22]`, `[Fal26]` Cranelift `aegraph`)—this BIP proposes:

1. A concrete, prioritized **catalogue of 12 optimizations** unlocked by `BIP007`, with exact soundness contracts and JVM/HotSpot justification.
2. A **progressive two-tier typed IR** (`org.babblelang.ir`) and **small-pass pipeline** (`org.babblelang.opt`) written with Java 21 `sealed interface`s and `record`s:
   * **Tier 1 — High-Level Typed IR (`HIR`):** A structured, self-contained expression/statement tree detached from ANTLR4 `ParserRuleContext`, carrying resolved types, `PrimitiveKind`s, `EffectSet`s, ownership tags, and line numbers. Hosts Nanopass-style passes (constant folding, algebraic simplification, inlining, `borrow` scalar replacement, FBIP reuse, predicate/pipeline rewrites, structured DCE).
   * **Tier 2 — Per-Function SSA CFG IR (`MIR`):** Basic blocks with block parameters (not Sea-of-Nodes), explicit effect barriers keyed on `BIP007` contracts, and a clean SSA-to-ASM backend. Hosts dataflow passes (SCCP, GVN/CSE, LICM, `unique mut`/`frozen` redundant-load elimination, SSA DCE).
3. An **incremental, six-phase rollout (`BIP014-P0`–`P5`)** that delivers measurable wins immediately, detaches `CodeGenerator` from ANTLR4 with zero behavioral change first, and verifies every optimization against the untouched `Interpreter` via `DifferentialTestCase`, `.batest` (`BIP009`), ASM `CheckClassAdapter`, and byte-identical `examples/raytracer-argon/scene.ppm`.

---

## 2. Motivation: Why Optimize Ahead of HotSpot C2?

A natural question for any JVM-targeting compiler is: *doesn't HotSpot's C2 (or Graal) JIT already optimize JVM bytecode?*

HotSpot C2 is extraordinarily good at low-level machine optimizations (register allocation, instruction scheduling, branch prediction, devirtualization of monomorphic call sites, and intra-method scalar replacement of small objects). Babble should **never** duplicate those. However, C2 operates on **erased JVM bytecode** and lacks critical semantic knowledge that Babble's front end already proves:

1. **`BIP007` contracts are invisible to C2:** C2 cannot know that an `axiom pure deterministic nothrow` Java call (`Math.sin`, `Geo.haversine`, `String.trim`) or a `pure deterministic` Babble function can be folded, CSE'd, or hoisted out of a loop—especially across separate classes, `invokedynamic` sites, or native/JNI boundaries.
2. **Deep aliasing (`unique mut`, `frozen T`) is invisible to C2:** In JVM bytecode, `BabbleInstance` fields and `ArrayList` backing arrays are ordinary mutable heap references. C2 must conservatively assume two references of the same type may alias unless it can inline both producer and consumer within its bytecode budget. `BIP007-P2`'s `unique mut` and `frozen T` prove deep disjointness and immutability *across call boundaries*.
3. **Algebraic properties (`associative`, `commutative`, `idempotent`, `monotone`, `injective`) are invisible to C2:** C2 will never collapse `norm(norm(s))` to `norm(s)`, match `f(b, a)` with `f(a, b)`, reassociate custom combiners, or push filters past `map` projections.
4. **Babble abstractions inflate C2's inlining budget:** Higher-order lambdas (`CompiledFunction`, `FunctionBody`, `LambdaMetafactory`), `record` wrappers (`Vec3` in `examples/raytracer-argon`), `Cell` captures, and `invokedynamic` call sites consume HotSpot's `MaxInlineSize` (35 bytes) and `FreqInlineSize` (325 bytes) budgets. Inlining small Babble functions, eliminating dead branches, and scalar-replacing `borrow` `record`s *before* bytecode emission shrinks hot methods so C2 optimizes the remaining loop dramatically better.
5. **Cold & startup performance (AOT `babblec` and scripts):** Short-lived scripts and CLI tools compiled with `babblec` (`BIP006`) often finish before C2 finishes tier-4 compilation; compile-time optimizations pay off from the very first iteration.

---

## 3. Catalogue of Optimizations in Scope

Every optimization below is strictly gated on `BIP007` static contracts and must preserve **exact observable equivalence** with the `Interpreter` (same return value, same side-effect order, same exception type/message/line number, and same termination behavior).

### 3.1 Summary Matrix

| # | Optimization | Required `BIP007` Contract | `BIP007` Phase | Granularity |
|---|---|---|---|---|
| **O1** | Constant Folding & Constant Propagation | Built-in ops; for calls: `pure deterministic` + deeply immutable args & result (+ `nothrow` if guarded) | `P1` (`P2` for `frozen`) | Local (literals) / Pass (propagation) |
| **O2** | Algebraic Identities, Strength Reduction & `idempotent` Collapse | `pure deterministic` (+ `idempotent` for `f(f(x)) -> f(x)`; effect `idempotent` for discarded duplicate) | `P1` | Local / Pass |
| **O3** | Associative Reassociation & `commutative` Canonicalization | `pure deterministic nothrow` + `associative` / `commutative` (never IEEE-754 `float`/`double` without `axiom`) | `P1` | Pass |
| **O4** | Dead-Code & Dead-Expression Elimination (DCE) | Unreachable CFG always; dead expression requires `pure nothrow @TERMINATING` | `P1` (CFG) / `P2` (`@TERMINATING`) | Pass |
| **O5** | Common Subexpression Elimination (CSE / GVN) | `pure deterministic` + deeply immutable result + no intervening write to reachable arg state (`commutative` merges `f(a,b)`/`f(b,a)`) | `P1` (`P2` for `frozen`) | Pass (CFG/SSA) |
| **O6** | Loop-Invariant Code Motion (LICM / Loop Hoisting) | `pure deterministic` + deeply immutable result (+ `nothrow @TERMINATING` when loop may run 0 times or across guard) | `P1` / `P2` | Pass (CFG/SSA) |
| **O7** | No-Alias (`unique mut`) & `frozen` Redundant-Load Elimination (RLE) | `unique mut` parameter (deep disjointness) or `frozen T` / deep `record` field reads | `P2` (deep `record`s in `P1`) | Pass (CFG/SSA) |
| **O8** | Small-Function & `borrow`-Lambda Inlining | Static Babble call target (`ScopeAnalysis.babbleCallFunctions`); `borrow` guarantees closure non-escape | `P1` | Pass (`HIR`) |
| **O9** | Scalar Replacement / Stack Unboxing of `borrow` `record`s & Captures | `borrow` parameter + statically known `record` / fresh allocation | `P1` | Pass (`HIR`) |
| **O10** | Functional-But-In-Place (FBIP) Reuse | `unique` parameter + `move(x)` (single live owner) + `hermetic mut` core collection/class | `P2` | Pass (`HIR`) |
| **O11** | Guard-Safe Predicate Reordering & Short-Circuit Simplification | `pure nothrow` on both predicates + no `BIP007-D1` `Narrowing` guard dependency | `P1` | Pass (`HIR`/`MIR`) |
| **O12** | Pipeline Push-Down / Push-Up & Tree Reduction | `pure deterministic nothrow` + `monotone` / `injective` / `associative` | `P1` (+ `std/` combinators) | Pass (`HIR`) |

---

### 3.2 Detailed Optimization Specifications

#### O1 — Constant Folding & Constant Propagation
* **What it does:** Evaluates compile-time-known expressions across all 8 `RM005` JVM primitives (`byte`..`char`), `string` concatenation, unary/binary operators, constant `if (true)` / `if (false)` branch selection, and calls to `pure deterministic` functions with constant deeply immutable arguments (e.g., `Math.sqrt(2.0)`, `int("42")`, `s.length()`, or small `@TERMINATING` Babble helpers).
* **Before / After:**
  ```babble
  ;; Before
  def tau = 2.0 * 3.141592653589793
  def diag = java.lang.Math.sqrt(3.0 * 3.0 + 4.0 * 4.0)
  def label = "v" + "1"

  ;; After
  def tau = 6.283185307179586
  def diag = 5.0
  def label = "v1"
  ```
* **Soundness rules:**
  * Integer arithmetic respects exact two's-complement wrap-around (`int`/`long`), and division `/` is IEEE-754 `float`/`double` (never throws in Babble).
  * Calls that are not `nothrow` (e.g., `int("abc")`) are **never** folded speculatively into an unreachable branch, and if folded in an executed position must either be left to throw at runtime with the exact line number or folded only when guaranteed not to throw.
  * Never fold a `pure deterministic` call that returns a **mutable** object (e.g., `list of int ()` or a mutable class constructor), because two evaluations must produce two distinct mutable instances (`BIP007` §3.2).

#### O2 — Algebraic Identities, Strength Reduction & `idempotent` Collapse
* **What it does:** Applies local algebraic simplifications:
  * Integer identities: `x + 0 -> x`, `x - 0 -> x`, `x * 1 -> x`, `x * 0 -> 0` (when `x` is `pure nothrow @TERMINATING`), `x - x -> 0`.
  * Boolean identities: `not (not b) -> b`, `b and true -> b`, `b or false -> b`, `b == true -> b`, `b == false -> not b`.
  * Value `idempotent` collapse (`BIP007` Axis 3): `f(f(x)) -> f(x)` for any `pure deterministic idempotent` unary endofunction (e.g., `Math.abs(Math.abs(x)) -> Math.abs(x)`, `s.trim().trim() -> s.trim()`).
  * Effect `idempotent` duplicate suppression: consecutive identical `idempotent` calls `s.add(x); s.add(x)` with discarded second result and no intervening write collapse to a single `s.add(x)`.
* **Soundness rules:**
  * **Strict IEEE-754 discipline:** For `float`/`double`, `x * 0.0` is **not** `0.0` (`NaN * 0.0 = NaN`, `Inf * 0.0 = NaN`, `-1.0 * 0.0 = -0.0`), `x + 0.0` is **not** `x` (`-0.0 + 0.0 = +0.0`), and `x == x` is **not** `true` (`NaN == NaN` is `false`). Floating-point identities are strictly excluded unless mathematically exact under IEEE-754 (e.g., `x * 1.0` preserves `NaN` and signed zero, though signaling NaNs are quieted).

#### O3 — Associative Reassociation & `commutative` Canonicalization
* **What it does:** Reassociates `pure deterministic nothrow associative` chains so constants group together and loop-invariant terms separate from loop-variant terms (`(x + 1) + 2 -> x + 3`, `(inv + i) + inv2 -> (inv + inv2) + i`), and canonicalizes operand order for `commutative` operations (e.g., constants on the right, lower value-number on the left) so CSE/GVN automatically recognizes `a + b` and `b + a` or `min(x, y)` and `min(y, x)` as identical.
* **Soundness rules:** Applies to `int`/`long` `+`, `*`, `string +` (associative, non-commutative), and any function annotated `axiom pure deterministic nothrow associative` / `commutative`. Never reassociates `float`/`double` `+` or `*` (IEEE-754 rounding is non-associative).

#### O4 — Dead-Code & Dead-Expression Elimination (DCE)
* **What it does:** Removes unreachable basic blocks (after `return`, or pruned constant `if`/`while` branches), unused local `def` bindings whose initializer is `pure nothrow @TERMINATING`, and dead `pure nothrow @TERMINATING` expressions exposed by inlining, CSE, or constant folding.
* **Relationship to `BIP007-D8`:** `BIP007-D8` makes unused `pure nothrow` expressions written directly in *source* a compile-time error (unless wrapped in `discard(expr)`). Optimizer DCE handles dead expressions *created synthetically* by earlier passes (e.g., after inlining a helper whose return value is unused, or `discard(pureExpr)` itself, which becomes a no-op when `pureExpr` is `pure nothrow @TERMINATING`).
* **Soundness rules:** Dropping a call that might loop forever would change observable termination; hence `@TERMINATING` (`BIP007-P2`) is mandatory before eliminating any non-trivial call.

#### O5 — Common Subexpression Elimination (CSE) & Global Value Numbering (GVN)
* **What it does:** Computes each `pure deterministic` expression or call once and reuses its result in a JVM local whenever dominated by an earlier identical evaluation and no intervening operation could modify the observable state reachable from its arguments.
* **Before / After:**
  ```babble
  ;; Before (e.g. normalize is pure deterministic, Vec3 is a deep record)
  def a = normalize(dir).x * scale + normalize(dir).y * scale

  ;; After
  def $cse0 = normalize(dir)
  def a = ($cse0.x + $cse0.y) * scale   ;; (or $cse0.x * scale + $cse0.y * scale for double)
  ```
* **Soundness rules:**
  * Result type must be **deeply immutable** (`Type.isDeeplyImmutable()`: 9 core scalars, `null`, `frozen T`, deep `record`, deep `entry`). Never CSE a call returning a mutable class or `unique` collection!
  * If arguments are deeply immutable, the value remains valid across arbitrary intervening code (even impure calls).
  * If an argument is a mutable reference (e.g., `list of int`), the cached value is invalidated at any intervening call that may mutate it—*unless* the intervening call is `pure`, or mutates only a `unique mut` parameter provably disjoint from the argument (`O7`).
  * `commutative` functions canonicalize `(b, a)` to `(a, b)` in the GVN table.

#### O6 — Loop-Invariant Code Motion (LICM / Loop Variable Hoisting)
* **What it does:** Identifies expressions, immutable field loads, and `pure deterministic` calls inside a `while` (or `BIP013` `for`) loop whose operands are invariant across iterations, and hoists them into a loop preheader so they execute once instead of $N$ times.
* **Before / After:**
  ```babble
  ;; Before
  def i = 0
  while i < n then (
      def factor = java.lang.Math.cos(angle) * radius
      total += xs.get(i) * factor
      i += 1
  )

  ;; After
  def $licm0 = java.lang.Math.cos(angle) * radius
  def i = 0
  while i < n then (
      total += xs.get(i) * $licm0
      i += 1
  )
  ```
* **Soundness rules:**
  * If the loop is **not** statically guaranteed to execute at least once (the common case for `while`), hoisting unconditionally into the preheader requires **`nothrow` and `@TERMINATING`** (otherwise a zero-iteration loop could spuriously throw or hang!).
  * If an invariant expression is `pure deterministic` but *may* throw (not `nothrow`, e.g. `xs.get(0)` or `x!`), it can still be hoisted safely via **loop guarding** (`if i < n then ( def $licm0 = ...; do-while ... )`), which preserves exact exception semantics on zero-iteration loops.
  * Never hoist an expression that allocates a mutable object (`Counter(0)`), since each iteration expects a fresh instance.
  * Must preserve HotSpot's empty-operand-stack invariant at loop heads (`CodeGenerator` invariant).

#### O7 — No-Alias (`unique mut`) & `frozen` Redundant-Load Elimination (RLE)
* **What it does:** Eliminates redundant `GETFIELD` / `list.get` reads across mutations when `BIP007-P2` proves disjointness or deep immutability:
  * **Via `unique mut`:** Inside `fn (unique borrow mut dst: Vec, borrow src: Vec)`, writes to `dst` (or `dst.data`) **cannot** alias `src` or any field reachable from `src` (`BIP007` §3.2). Therefore, reads of `src.len` or `src.scale` can be kept in JVM locals and hoisted out of loops that mutate `dst`, with zero runtime alias checks.
  * **Via `frozen T` and deep `record`s:** Reading any field of a `frozen T` or deep `record` is permanently invariant for the lifetime of the reference—even across arbitrary impure calls.
* **Soundness rules:** Keyed strictly on `unique mut` (never plain `mut`, which is a shallow identity check per `BIP007-D9`) and `Type.isDeeplyImmutable()`.

#### O8 — Small-Function & `borrow`-Lambda Inlining
* **What it does:** Inlines small, statically resolved Babble functions (`ScopeAnalysis.babbleCallFunctions`) and higher-order lambda arguments (`xs.map(fn (x) -> ( x * 2 ))`), especially when the callee parameter is `borrow` (guaranteeing the lambda itself does not escape and its `CompiledFunction` allocation disappears completely).
* **Why it is pivotal:** Inlining is the **enabling pass** for almost everything else. In `examples/raytracer-argon`, `Vec3.plus`, `Vec3.times`, `Vec3.dot`, and `Ray.at` are tiny 1–3 line methods. Inlining them exposes temporary `Vec3` allocations to `O9` (scalar replacement), exposes repeated subexpressions to `O5` (CSE), and eliminates `invokedynamic` / `$spec` call overhead.
* **Soundness rules:**
  * Bounded by an AST/IR node budget and recursion-depth limit (never infinitely inline recursive or mutually recursive SCCs).
  * Respects `BIP007-D2` cross-module boundaries (see `BIP014-Q2`).
  * Preserves `return` semantics (converting inner `return` into a jump to the inlined call's merge block) and preserves call-site + callee line numbers for stack traces.

#### O9 — Scalar Replacement / Stack Unboxing of `borrow` `record`s & Captures
* **What it does:** When a `record` (e.g., `record (x: double, y: double, z: double)`) is constructed locally and only read (or passed to `borrow` parameters, or inlined), replaces the heap-allocated `BabbleInstance` with its individual unboxed primitive/reference fields in JVM locals—across all 8 `RM005` primitive types.
* **Before / After (ray-tracer hot loop):**
  ```babble
  ;; Before: allocates temporary Vec3 objects on every ray step
  def p = Vec3(ox + t * dx, oy + t * dy, oz + t * dz)
  def d = distSq(p, center)   ;; distSq takes `borrow p: Vec3`

  ;; After inlining / scalar replacement: 0 heap allocations, pure JVM `double` locals
  def $p_x = ox + t * dx
  def $p_y = oy + t * dy
  def $p_z = oz + t * dz
  def $dx = $p_x - center.x
  def $dy = $p_y - center.y
  def $dz = $p_z - center.z
  def d = $dx * $dx + $dy * $dy + $dz * $dz
  ```
* **Soundness rules:**
  * Requires that the `record` reference never escapes (no assignment to an outer variable, field, collection, `any`, or non-`borrow` parameter) and never has its identity observed (`same(a, b)` on a `record` or `classof` if polymorphic—note `record` has value equality, and `same` is identity, so `same` disqualifies scalar replacement).
  * For non-inlined `borrow` callees, the compiler can emit a specialized flattened `$unboxed` entry point taking `(double p_x, double p_y, double p_z, ...)` alongside `$spec`.

#### O10 — Functional-But-In-Place (FBIP) Reuse (`unique` + `move(...)`)
* **What it does:** When a function takes a `unique` parameter (or a caller passes `move(xs)` on a `unique` local) and returns a transformed collection/record of the same shape (e.g., `sort(move(xs))`, `mapInPlace(move(xs), f)`), lowers the operation to mutate the uniquely owned buffer in place instead of allocating and copying—preserving external `pure`/`hermetic` semantics with zero GC pressure (inspired by Koka's Perceus `[RXdML21]` and the fully in-place calculus `[LLS23]`).
* **Soundness rules:** Strictly requires `BIP007-P2` `unique` ownership + definite-unassignment (`move(xs)` or implicit last-use move), guaranteeing refcount = 1 statically with zero runtime refcounting overhead.

#### O11 — Guard-Safe Predicate Reordering & Short-Circuit Simplification
* **What it does:** Reorders cheap `pure nothrow` predicates ahead of expensive `pure nothrow` predicates in conjunctions (`expensive(x) and cheap(x) -> cheap(x) and expensive(x)`) and chained collection filters (`xs.filter(expensive).filter(cheap)`).
* **Soundness rules (`BIP007-D1`):**
  * Both predicates must be `pure nothrow` (and `@TERMINATING` if reordering could skip a non-terminating left-hand side).
  * **Never** reorder a narrowed predicate ahead of its `Narrowing` guard: in `a != null and a.x > 0` or `x is Circle and x.r > 0`, `a.x` depends on the narrowing established by `a != null`. The IR must carry an explicit guard/narrowing dependency edge (or restrict reordering to predicates whose `Narrowing` input environment is unchanged by the preceding predicate).

#### O12 — Pipeline Push-Down / Push-Up & Algebraic Tree Reduction
* **What it does:** Once `std/` higher-order combinators (`map`, `filter`, `reduce`, `distinct`) land, rewrites high-level pipelines using `BIP007` Axis 3 algebraic properties:
  * **Filter fusion & push-down:** `xs.filter(p).filter(q)` → single fused pass; `xs.map(f).filter(p)` → push `p(f(x))` before `map` (or fuse into a single loop with zero intermediate `ArrayList`).
  * **`monotone` range inversion / pruning:** On sorted/chunked collections, push `f(x) > c` across a `monotone` projection.
  * **`injective` push-down:** Rewrite `f(x) == f(y)` → `x == y` (when `f` is `pure deterministic nothrow injective`), and push `distinct` ahead of `map(f)`.
  * **`associative` tree reduction / loop reassociation:** Break loop-carried dependencies in `reduce(xs, seed, op)` for `pure deterministic associative` combiners (enabling unrolled multi-accumulator loops that hide CPU latency—and laying the groundwork for future parallel reductions).

### 3.3 Explicit Non-Goals

* **Machine-level register allocation, instruction selection, or SIMD vectorization:** HotSpot C2 handles CPU-specific lowering far better than static bytecode generation can.
* **Speculative profile-guided optimizations (PGO) or deoptimization:** HotSpot's tiered JIT already collects branch/type profiles at runtime. Babble's compiler performs **sound, static, semantics-preserving** optimizations only.
* **Automatic parallelization:** Deferred until `threadsafe` lands (`BIP007` §10).
* **Optimizing the `Interpreter`:** The parse-tree `Interpreter` remains deliberately simple and unoptimized so it continues to serve as Babble's trusted reference semantics for `DifferentialTestCase` and `.batest` (`BIP009`).

---

## 4. State of the Art in Compiler Pass Architecture

Compiler intermediate representations and pass pipelines have evolved rapidly over the past decade. Evaluating the major SOTA paradigms clarifies what fits Babble—a statically typed, effect-annotated JVM language implemented in Java 21—and what to avoid.

| Paradigm / System | Representative Compilers | Core IR & Pass Model | Key Strengths (`+`) | Trade-Offs / Lessons for Babble (`-`) |
|---|---|---|---|---|
| **Classic SSA CFG + PassManager** | **LLVM** `[LA04]`, GCC (GIMPLE/SSA) | Mutable linear basic blocks in SSA (`phi` nodes); modular analysis/transform passes with explicit invalidation (`PreservedAnalyses`) | `+` Textbook GVN, SCCP `[WZ91]`, LICM, DCE, RLE map cleanly.<br>`+` Easy to print, verify, and debug.<br>`+` Scales to production workloads. | `-` Classic **phase-ordering problem** (pass order affects results; requires iterating or fixed pipelines).<br>`-` Low-level SSA erases high-level constructs (`for`, `record`, pipelines) unless paired with a higher-level IR. |
| **Nanopass Framework** | **Chez Scheme** (`[SWD05]`, `[KD13]`), Racket | **50+ tiny, single-task passes**, each transforming from one formally defined IR grammar (`define-language`) to a simpler one (`define-pass` with automatic catamorphism boilerplate) | `+` Dramatic complexity reduction: each pass does *one* thing and is trivial to test/reason about.<br>`+` Chez Scheme replaced 5 monolithic passes with 50+ nanopasses: **15–27% faster output**, compile time well within 2× `[KD13]`.<br>`+` Output grammar enforces invariants structurally. | `-` Relies on Scheme's hygienic macros (`define-language` delta inheritance) to avoid boilerplate.<br>`-` In Java, 30 distinct AST class hierarchies would be prohibitively verbose; better to adopt the **small single-task pass + IR verifier** discipline over **1–2** typed IRs! |
| **Sea of Nodes (SoN)** | HotSpot C2 `[Cli95]`, Graal, V8 TurboFan *(legacy)* | Control, data, and effect edges unified in one floating graph; no fixed basic-block instruction order until late scheduling | `+` Unifies GVN, LICM, and global code motion `[Cli95]`.<br>`+` Proven in HotSpot C2 and Graal. | `-` **V8's 2025 retrospective `[Mer25]`**: V8 migrated TurboFan to a **CFG-based SSA IR (Turboshaft)**, **roughly halving compile time**, cutting L1 dcache misses 3–7×, and greatly simplifying debugging—because effectful languages chain most nodes anyway! Clear warning **against** SoN for Babble. |
| **Compact CFG SSA with Copying Reducers** | **V8 Turboshaft** `[Mer25]`, Maglev, Swift SIL | Linear basic blocks in SSA with **block parameters** (replacing `phi` nodes); passes stream/copy old IR → new IR while fusing multiple local reductions in one traversal | `+` Cache-friendly, linear, easy to read and debug.<br>`+` Block parameters unify loop-carried values and branch merges cleanly.<br>`+` Copying/rewriting avoids stale-pointer bugs of in-place graph mutation. | `+` **Strong fit for Babble's Tier 2 (`MIR`)**, especially combined with Java 21 immutable `record`s and block parameters. |
| **E-Graphs & Equality Saturation** | **egg** `[WNW+21]`, **Cranelift `aegraph` + ISLE** (`[Fal22]`, `[Fal26]`), Julia e-graph IR | Equivalence graph storing multiple equivalent expressions simultaneously in e-classes; declarative rewrite rules fire without destroying earlier forms, followed by cost-based extraction | `+` Eliminates phase-ordering for algebraic rewrites (`associative`, `commutative`, `idempotent`, strength reduction).<br>`+` Cranelift's **acyclic e-graph (`aegraph`)** with a CFG skeleton makes it fast enough for JIT compilation (`[Fal22]`, `[Fal26]`). | `-` Overkill as the *only* IR (still needs a CFG skeleton for control flow and side effects, plus extraction heuristics).<br>`+` **Ideal optional fit for `BIP014-P5`** to execute `BIP007` Axis 3 algebraic rewrites declaratively without hand-coding every rule combination. |
| **Multi-Level IRs & JVM Compilers** | **MLIR** `[LAB+21]`, **Rust (HIR → MIR)**, **Kotlin IR**, **Scala 3** | Separate high-level semantic IR (preserving language abstractions, types, ownership) from low-level CFG/SSA IR; small composable passes + optional final bytecode peephole | `+` High-level passes (inlining, `borrow` scalar replacement, FBIP, pipeline push-down) are natural on HIR.<br>`+` Low-level passes (GVN, LICM, RLE) are natural on SSA MIR.<br>`+` Matches JVM constraints cleanly. | `-` Maintaining two IRs adds conversion code (`HIR -> MIR`), so the boundary must be crisp and introduced **incrementally** (`HIR` first, `MIR` second). |

### Key Architectural Takeaways for Babble

1. **Adopt the Nanopass philosophy, not Scheme's 50 grammars:** Every optimization should be a small, single-responsibility pass (`HirPass` / `MirPass`) that is independently unit-tested, toggleable, and followed by an automated IR verifier in test mode (`babble.verify=true`).
2. **Use CFG SSA with block parameters—not Sea of Nodes:** V8's Turboshaft experience confirms that for an imperative/functional language with effects and exceptions, explicit basic blocks with SSA block parameters are dramatically simpler, faster to compile, and easier to debug than Sea of Nodes.
3. **Separate High-Level Semantic IR (`HIR`) from SSA Control-Flow IR (`MIR`):** Trying to do `borrow` `record` unboxing or `map`/`filter` push-down on low-level basic blocks is painful; trying to do GVN/LICM/RLE on an ANTLR parse tree is equally painful. A two-tier IR—introduced incrementally—gives each optimization its natural home.
4. **Reserve e-graphs / `aegraph` for where they shine:** Don't build the whole compiler around equality saturation on day one; keep it as a targeted option in `BIP014-P5` for `BIP007`'s algebraic axioms (`associative`, `commutative`, `idempotent`, `monotone`, `injective`).

---

## 5. Current Babble Compiler Architecture & Constraints

To design an incremental path forward, we must account for the exact structure of Babble today:

```
Babble source
  └─▶ ANTLR4 (Babble.g4, SLL → LL)
        └─▶ BabbleParser.FileContext (Parse Tree)
              ├─▶ Analysis (BlockScopes → TypeChecks → Resolver → TypeChecker + EffectChecker → LoweringAnalyzer)
              │     └─▶ ScopeAnalysis + Resolution (IdentityHashMap / ParseTreeProperty keyed by ParserRuleContext!)
              │
              ├─▶ Interpreter (BabbleBaseVisitor<Object>, 826 lines — reference oracle)
              └─▶ BabbleCompiler → CodeGenerator (BabbleBaseVisitor<Void>, 5,996 lines)
                                       └─▶ ASM ClassWriter → byte[] → ScriptClassLoader / babblec jar
```

### Why Optimizations Cannot Live on the ANTLR4 Parse Tree

1. **Side-table fragility:** `ScopeAnalysis` (767 lines) and `Resolution` (133 lines) store over 25 `ParseTreeProperty` / `IdentityHashMap<ParserRuleContext, ...>` tables (`expressionKinds`, `declarationKinds`, `javaCallTargets`, `babbleCallFunctions`, `interfaceCallTargets`, `numericWidenings`, `checkedTypes`, `callableEffects`, `callEffects`, `bindings`, etc.). Mutating, cloning, or synthesizing a `ParserRuleContext` (e.g., during inlining, LICM, or constant folding) immediately breaks identity lookups across all 25 tables.
2. **Concrete syntax noise:** ANTLR4 nodes encode grammar artifacts (parentheses, `NL` tokens, `memberName` wrappers, compound assignment tokens) rather than semantic operations.
3. **Monolithic single-pass `CodeGenerator`:** At **5,996 lines**, `CodeGenerator.java` currently interleaves five distinct concerns in one class:
   * Script/class/interface/constant-pool scaffolding (`ClassGen`, `InterfaceGen`, `Constant`, `<clinit>` splitting)
   * Method-body local-slot & `Cell` frame management
   * Direct parse-tree lowering (operators, `if`/`while`, calls, `is`/`as`, Java interop, `RM001`/`RM003` bridges)
   * Ad-hoc local optimizations (e.g., `RM001` type-guarded `int` constant folding)
   * ASM bytecode emission and line-number tracking

### Architectural Invariants That Must Be Preserved

1. **Zero runtime boundary leakage (`BIP006` §3, `RuntimeBoundaryTestCase`):** `org.babblelang.runtime` and `org.babblelang.engine.impl.natives` must remain 100% free of IR and optimizer classes (`java.base`-only `babble-runtime.jar`).
2. **Dual-mode differential parity (`BIP009`):** `Interpreter` stays untouched. Every `.ba` and `.batest` test must produce identical values, stdout, and error messages/line numbers across `interpret`, `compile -O0`, and `compile -O1`/`-O2`.
3. **Bytecode verification & OSR safety:** Every generated `.class` must pass ASM `CheckClassAdapter` (`babble.verify=true`), keep the operand stack empty at loop headers (HotSpot OSR), and stay within JVM 64 KB method / constant-pool limits (`MAX_NODES_PER_RUN_METHOD`, `<clinit>` helper splitting).
4. **AST-detached module & REPL summaries (`BIP010-D3`):** `TypedModuleSummary` and `AnalysisSession` must never retain ANTLR parse trees of imported modules.

---

## 6. Proposed Compiler Architecture

### 6.1 Local vs. Pass-Based Optimization Boundary

Not every optimization needs a full graph pass. We draw a clean architectural boundary:

| Layer | Where It Lives | What Belongs Here | What Does *Not* Belong Here |
|---|---|---|---|
| **1. Local / Emission-Time Peepholes** | `CodeGenerator` (ASM backend) | Literal constant emission (`ICONST_0`..`LDC`), branch inversion (`if (not cond)` → single `IFNE`), `x + 0` / `x * 1` on immediate literals, eliminating trivial `DUP; POP` or box-then-unbox pairs at expression boundaries | Anything requiring def-use chains, variable tracking, alias/effect analysis, loop structure, or cloning code |
| **2. Structured Tree Passes (`HIR`)** | `org.babblelang.opt.hir` | Constant folding & propagation (`O1`), algebraic & `idempotent` rewrites (`O2`, `O3`), small-function & `borrow`-lambda inlining (`O8`), `borrow` `record` scalar replacement (`O9`), FBIP reuse (`O10`), pipeline/predicate push-down (`O11`, `O12`), structured DCE (`O4`) | General CSE across diamond branches, complex loop-invariant hoisting across arbitrary `while` control flow, alias-aware RLE |
| **3. Per-Function SSA CFG Passes (`MIR`)** | `org.babblelang.opt.mir` | Sparse Conditional Constant Propagation (SCCP), GVN/CSE (`O5`) with `commutative` canonicalization, general LICM (`O6`) with preheaders & guarded loops, `unique mut`/`frozen` RLE & load hoisting (`O7`), SSA DCE (`O4`), guard-safe predicate reordering (`O11`) | High-level `record` / closure / collection pipeline structure (already lowered) |

### 6.2 Proposed Pipeline

```
Babble source
  └─▶ ANTLR4 Parse Tree ──▶ Front-End Analysis (BlockScopes .. LoweringAnalyzer)
                                │
                                ├──▶ Interpreter (untouched reference oracle)
                                │
                                └──▶ Compiler Pipeline (org.babblelang.compiler / ir / opt)
                                       │
                                       ├── 1. HirBuilder (ParseTree + ScopeAnalysis + Resolution ──▶ HirModule)
                                       │       (self-contained, immutable Java 21 sealed records; zero ParseTree refs!)
                                       │
                                       ├── 2. HIR Nanopass Pipeline (org.babblelang.opt.hir)
                                       │       ├─ ConstFoldPass / AlgebraicSimplifyPass (O1, O2, O3)
                                       │       ├─ InlinePass (O8: small fns & borrow lambdas)
                                       │       ├─ ScalarReplacePass (O9: borrow records & captures)
                                       │       ├─ FbipReusePass (O10: unique + move)
                                       │       ├─ PipelineRewritePass (O11, O12)
                                       │       └─ HirDcePass (O4)
                                       │       [HirVerifier after each pass when babble.verify=true]
                                       │
                                       ├── 3. MirLowerer (per-function HIR ──▶ SSA CfgFunction, when -O2)
                                       │       ├─ SccpPass (O1)
                                       │       ├─ GvnCsePass (O5 + O3 commutative)
                                       │       ├─ LicmPass (O6)
                                       │       ├─ AliasRlePass (O7: unique mut / frozen)
                                       │       └─ MirDcePass (O4)
                                       │       [MirVerifier after each pass when babble.verify=true]
                                       │
                                       └── 4. Bytecode Emitter (ASM ClassWriter)
                                               ├─ Class/Interface/ConstantPool scaffolding (reused from CodeGenerator)
                                               ├─ HirEmitter (used at -O0 / -O1, and for top-level/class init)
                                               └─ MirEmitter (used at -O2 for SSA-optimized function/method bodies)
```

### 6.3 Tier 1: High-Level Typed IR (`org.babblelang.ir.hir`)

`HIR` is a compact, self-contained, immutable tree built with **Java 21 `sealed interface`s and `record`s** (enabling exhaustive pattern-matching `switch` expressions with zero visitor boilerplate).

Every `HirExpr` carries a lightweight `ExprMeta` record:
```java
public record ExprMeta(
    int line,                          // source line for exact stack traces
    Type type,                         // converged static Type (including Frozen, Record, etc.)
    ScopeAnalysis.PrimitiveKind kind,  // BYTE..CHAR or NONE (8-primitive RM005 unboxing)
    EffectSet effects,                 // BIP007 converged effects for this node
    boolean terminating,               // @TERMINATING flag (BIP007-P2)
    boolean deeplyImmutable            // cached Type.isDeeplyImmutable()
) {}
```

Core node families (all sealed):
* **Declarations & containers:** `HirModule`, `HirFunction` (parameters with `mut`/`borrow`/`unique` flags, captures, body `HirExpr`, `EffectSet`), `HirClass` / `HirRecord` / `HirInterface` / `HirObject`.
* **Local bindings & state:** `HirVarRef(VarId, ExprMeta)`, `HirDef(VarId, HirExpr init, ExprMeta)`, `HirAssign(VarId, HirExpr value, ExprMeta)`, `HirNamespaceRead/Write` (for file/interface namespace scopes).
* **Literals & conversions:** `HirConst(Object value, ExprMeta)`, `HirWiden(HirExpr, PrimitiveKind target, ExprMeta)`, `HirCast(HirExpr, String checkedType, ExprMeta)`, `HirNonNull(HirExpr, ExprMeta)`, `HirIs(HirExpr, IsTarget, ExprMeta)`.
* **Operations:** `HirUnary(Op, HirExpr, ExprMeta)`, `HirBinary(Op, HirExpr left, HirExpr right, ExprMeta)`, `HirLogic(And|Or, HirExpr left, HirExpr right, boolean rightNarrowed, ExprMeta)`.
* **Control flow:** `HirBlock(List<HirExpr> stmts, boolean opensScope, ExprMeta)`, `HirIf(HirExpr cond, HirExpr thenBranch, HirExpr elseBranch, boolean narrowed, ExprMeta)`, `HirWhile(HirExpr cond, HirExpr body, ExprMeta)`, `HirFor(...)` (for `BIP013`), `HirReturn(HirExpr value, ExprMeta)`.
* **Calls & members:** `HirBabbleCall(FnId target, List<HirExpr> args, FunctionSignature sig, ExprMeta)`, `HirJavaCall(JavaCallTarget, HirExpr recv, List<HirExpr> args, ExprMeta)`, `HirJavaNew(...)`, `HirInterfaceCall(...)`, `HirDynamicCall(...)`, `HirFieldGet/Set(...)`, `HirRecordNew(ClassId, List<HirExpr> fields, ExprMeta)`.
* **Ownership markers (`BIP007-P2`):** `HirMove(VarId, ExprMeta)`, `HirFreeze(HirExpr, ExprMeta)`, `HirDiscard(HirExpr, ExprMeta)`.

**Why `HIR` is immediately valuable even before any optimizer runs:**
* Every `CodeGenerator` lookup into `ScopeAnalysis` and `Resolution` moves **once** into `HirBuilder`.
* `HirEmitter` becomes a clean, exhaustive pattern-matching emitter over `HirExpr`—dramatically simplifying maintenance of `CodeGenerator`.
* Because `HIR` nodes are immutable records with structural sharing, Nanopass-style passes only allocate nodes along rewritten paths (copy-on-write), avoiding aliasing bugs while staying fast and GC-friendly on JDK 21.

### 6.4 Tier 2: Per-Function SSA CFG IR (`org.babblelang.ir.mir`)

For function and method bodies that benefit from dataflow optimizations (loops, repeated calls, `unique mut` / `frozen` field loads), `MirLowerer` converts a single `HirFunction` into a compact **SSA Control-Flow Graph with Block Parameters** (following Swift SIL, MLIR, Cranelift, and V8 Turboshaft):

```
bb0(p0: double, p1: double):
    v0 = const.double 0.5
    br bb1(v0, 0)                      ;; loop preheader enters header with initial args

bb1(g: double, i: int):                ;; block parameters replace phi nodes!
    v1 = cmp.lt.int i, 12
    br_if v1, bb2(), bb3()

bb2():
    v2 = div.double p0, g              ;; pure deterministic nothrow
    v3 = add.double g, v2
    v4 = mul.double v0, v3
    v5 = add.int i, 1
    br bb1(v4, v5)

bb3():
    ret g
```

**Representing `BIP007` Effects & Aliasing in `MIR`:**
Rather than Sea-of-Nodes' complex effect-chain graphs, `MIR` keeps instructions ordered inside basic blocks and attaches a compact **Effect & Alias Summary** to each instruction:
* **Pure / No-Effect (`EffectKind.PURE`):** Floatable for GVN/CSE and LICM; has no memory dependencies if all operands are deeply immutable.
* **Read-Only (`EffectKind.READ(AliasSet)`):** Reads heap state (e.g., `GETFIELD` on mutable object, `list.get`, or `pure deterministic` call taking mutable args). Invalidated only by writes whose `AliasSet` may overlap:
  * `AliasClass.FROZEN`: **Never** invalidated by any write (`frozen T`, deep `record` fields).
  * `AliasClass.UNIQUE_PARAM(k)`: Disjoint from every other `UNIQUE_PARAM(j)` ($j \ne k$), from all `SHARED` references, and from globals!
  * `AliasClass.SHARED`: Conservative heap class; invalidated by any plain `mut` or unconstrained call.
* **Effectful / Barrier (`EffectKind.WRITE(AliasSet)` or `EffectKind.IMPURE`):** Anchored in block order; never reordered across other barriers or exception points.
* **Guard / Narrowing Anchor (`BIP007-D1`):** Narrowing branches (`!= null`, `is T`) produce a typed SSA value (`v_narrowed = narrow.nonnull v`) consumed by the guarded branch—so **by SSA data dependence alone**, no pass (GVN, LICM, or predicate reordering) can ever hoist a narrowed operation above its guard!

**SSA-to-ASM Lowering (`MirEmitter`):**
* Standard dominator-tree / RPO block ordering, edge-splitting for block-parameter parallel copies (resolved via standard cycle-breaking temp local), and local-slot liveness coloring (reusing JVM local indices for non-overlapping SSA values of the same `PrimitiveKind`, keeping frame sizes small).
* Because every block boundary carries values in JVM locals (not on the operand stack), **HotSpot's empty-stack loop-head OSR invariant holds automatically by construction**.

### 6.5 Pass Manager, Verification & Debugging

* **`PassManager` (`org.babblelang.opt`):** Sequences small, single-purpose passes (`HirPass` / `MirPass`), supports bounded fixed-point iteration (e.g., `ConstFold` + `Inline` + `DCE` repeating until no change or a max iteration cap of 3), and tracks per-pass rewrite counts.
* **Optimization levels:** Controlled via CLI (`babble -O0/-O1/-O2`, `babblec -O0/-O1/-O2`), engine attribute, or system property `-Dbabble.opt=0|1|2`:
  * `-O0`: `HIR` direct emission only (zero optimization passes; fastest compile time, easiest debugging).
  * `-O1` (default candidate — see `BIP014-Q1`): Fast `HIR` Nanopass pipeline (`ConstFold`, `AlgebraicSimplify`, `Inline`, `HirDce`, `ScalarReplace`).
  * `-O2`: Full `HIR` + per-function SSA `MIR` pipeline (`SCCP`, `GVN/CSE`, `LICM`, `AliasRle`, `MirDce`, plus `FBIP` and pipeline rewrites).
* **IR Verifiers (`HirVerifier`, `MirVerifier`):** Whenever `babble.verify=true` (always on in Maven's `compiled-tests`!), the verifier runs **after every single pass**, checking:
  * Type & `PrimitiveKind` consistency on every node/instruction
  * SSA dominance (every use dominated by its definition) and block-parameter arity/type matching
  * Effect/narrowing invariants (no narrowed value used outside its dominating guard)
  * Valid line numbers on every throwing/calling instruction
* **Diagnostics & Dumping:** `-Dbabble.dumpIr=<dir|stderr>` prints human-readable `HIR` and `MIR` before/after each pass (complementing existing `babble.dumpClasses` and `babble.trace`).

---

## 7. Cross-Module, REPL & Verification Strategy

### 7.1 Cross-Module Optimization & `TypedModuleSummary` (`BIP007-D2`, `BIP010-D3`)
* **Effect contracts:** Cross-module optimizations (CSE, LICM, `idempotent`/`commutative`/`associative` rewrites, `unique mut` RLE) already have everything they need from `TypedModuleSummary` (`BIP007-D2`): unannotated `export def` exports its inferred `EffectSet`, while explicitly annotated exports expose only their declared contract.
* **Cross-module inlining:** By default, inlining operates **intra-module** only (so modules remain independently compilable and `TypedModuleSummary` stays tiny). Under `babblec` AOT (`BIP006`), where the full closed module graph is compiled into a single jar, an optional small-inline `HirFunction` summary (e.g., functions $\le$ 25 `HIR` nodes, such as `Vec3` helpers in `examples/raytracer-fable/vec3.ba`) can be attached to `TypedModuleSummary` for cross-module inlining (see `BIP014-Q2`).

### 7.2 Interactive Mode / REPL (`BIP004`)
* Each REPL input compiles independently against `AnalysisSession` (which persists committed `EffectSet`s). Intra-input optimizations (`-O1`/`-O2`) work out of the box; top-level session variables live in `Namespace` scopes and are conservatively treated as `AliasClass.SHARED` (never assumed invariant across REPL inputs).

### 7.3 Differential & Performance Testing (`BIP009`)
Every phase of `BIP014` is guarded by four automated gates:
1. **Three-way differential parity:** Run the entire `.ba` + `.batest` corpus (`67+` files, `869+` cases) across `interpret`, `compile -O0`, and `compile -O1`/`-O2` with `babble.verify=true` (verifying `HirVerifier`, `MirVerifier`, and ASM `CheckClassAdapter` on every generated class).
2. **Golden IR unit tests (`OptimizerTestCase`):** Focused unit tests asserting exact before/after `HIR`/`MIR` transformations for each pass (e.g., verifying that `Math.cos(angle)` is actually hoisted out of a `while` loop, that `a != null and a.x > 0` is *not* reordered, and that `float` `a + b + c` is *not* reassociated).
3. **Runtime boundary gate (`RuntimeBoundaryTestCase`):** Asserts zero `org.babblelang.ir` / `org.babblelang.opt` references in `babble-runtime.jar`.
4. **End-to-end correctness & benchmarks:** Byte-identical `examples/raytracer-argon/scene.ppm` (and timing comparison on `raytracer-argon`, `raytracer-fable`, and `org.babblelang.bench.Benchmarks`).

---

## 8. Incremental Implementation Roadmap

Each phase leaves `mvn -B package` and `mvn -q verify -Pbatest` 100% green and delivers standalone value:

### `BIP014-P0` — `CodeGenerator` Seam Extraction, Opt Flags & Local Peepholes
* Extract `ClassGen`, `InterfaceGen`, and `ConstantPool` out of the 5,996-line `CodeGenerator.java` into package-private collaborators inside `org.babblelang.compiler` (zero behavioral change, mirroring `BIP010`'s decomposition of `TypeChecker.java`).
* Add `-Dbabble.opt=0|1|2` and `-Dbabble.dumpIr` plumbing, plus optimizer microbenchmarks in `org.babblelang.bench.Benchmarks`.
* Expand existing local constant folding (`RM001` `int` folding) to all 8 primitives, `string`, boolean identities, and constant `if (true)`/`if (false)` pruning.

### `BIP014-P1` — `HIR` Introduction & Parse-Tree Detachment (Zero-Behavior-Change Parity)
* Create `org.babblelang.ir.hir` (`HirModule`, `HirFunction`, `HirExpr` sealed hierarchy, `ExprMeta`, `HirPrinter`, `HirVerifier`).
* Implement `HirBuilder` translating `BabbleParser.FileContext` + `ScopeAnalysis` + `Resolution` into `HirModule`.
* Implement `HirEmitter` emitting ASM method bodies from `HirModule`, replacing `CodeGenerator`'s parse-tree expression visitor while reusing `P0`'s class/interface/constant scaffolding.
* **Exit criterion:** 100% dual-mode `mvn package` + `-Pbatest` + `CheckClassAdapter` + byte-identical `raytracer-argon/scene.ppm` at `-O0` with `HirVerifier` enabled.

### `BIP014-P2` — `HIR` Nanopass Pipeline (`BIP007-P1` Optimizations)
* Create `org.babblelang.opt` (`PassManager`, `HirPass`, fixed-point driver) and `OptimizerTestCase` golden IR tests.
* Implement the first wave of `BIP007-P1`-unlocked passes:
  * `ConstFoldPass` (constant propagation + folding of built-ins and safe `pure deterministic` calls — `O1`)
  * `AlgebraicSimplifyPass` (integer/bool identities, `idempotent` collapse, `associative`/`commutative` canonicalization — `O2`, `O3`)
  * `InlinePass` (intra-module small functions & `borrow` lambdas — `O8`)
  * `HirDcePass` (unreachable branches & dead pure expressions — `O4`)
  * Structured `HirLicmPass` (hoisting simple `pure deterministic nothrow` loop invariants out of `while` / `BIP013` `for` loops — `O6` subset)

### `BIP014-P3` — Per-Function SSA `MIR` & Dataflow Optimizations
* Create `org.babblelang.ir.mir` (`MirFunction`, `BasicBlock`, `BlockParam`, `MirInst`, `AliasClass`, `MirLowerer`, `MirPrinter`, `MirVerifier`, `MirEmitter`).
* Implement SSA dataflow passes under `-O2`:
  * `SccpPass` (Sparse Conditional Constant Propagation — `O1` + `O4`)
  * `GvnCsePass` (dominator-based GVN/CSE with `commutative` canonicalization and effect invalidation — `O5`)
  * `LicmPass` (general loop-nest preheader hoisting + guarded hoisting for may-throw invariants — `O6`)
  * `AliasRlePass` (`frozen` / deep `record` / `unique mut` redundant-load elimination & field load hoisting — `O7`, full `unique mut` active once `BIP007-P2` lands)
  * `MirDcePass` (SSA dead instruction & dead block-parameter elimination — `O4`)
  * Narrowing-aware `PredicateReorderPass` (`O11`, enforced structurally by SSA `narrow` dependencies)

### `BIP014-P4` — Ownership & Allocation Optimizations (`BIP007-P2` Unlocked)
* `ScalarReplacePass` (`O9`): stack-unbox non-escaping `record` allocations and `borrow` parameters across all 8 `RM005` primitives (both after inlining and via flattened `$unboxed` entry points for non-inlined `borrow` callees).
* `FbipReusePass` (`O10`): specialize `unique` + `move(...)` collection/buffer operations to in-place reuse.
* Complete `@TERMINATING`-gated dead-call elimination (`O4`) and full `unique mut` RLE (`O7`).

### `BIP014-P5` — High-Level Pipeline Rewrites & Optional Declarative Rewrite Engine
* `PipelineRewritePass` (`O12`): filter fusion, filter reordering, `map`+`filter` push-down, `monotone` range pruning, `injective` equality/`distinct` push-down, and `associative` loop/fold reassociation over `std/` combinators.
* Evaluate a bounded **acyclic e-graph (`aegraph`)** rewrite pass (inspired by Cranelift) for composing `BIP007` Axis 3 algebraic axioms (`associative`, `commutative`, `idempotent`, `monotone`, `injective`) without phase-ordering sensitivity.

---

## 9. Open Questions

* **`BIP014-Q1` — Default optimization level:** Should `-O1` (`HIR` Nanopass pipeline) be the default for `compile` mode (with `-O0` available for debugging and `-O2` opt-in initially), or should all optimizations remain opt-in until `P2`/`P3` stabilize?
* **`BIP014-Q2` — Cross-module inlining scope:** Should `InlinePass` stay strictly intra-module in `P2`, and enable cross-module inlining of small `export`ed functions only in `babblec` AOT (`BIP006`) where the module set is closed, or also across dynamically cached static imports?
* **`BIP014-Q3` — Two-tier (`HIR` + SSA `MIR`) vs. `HIR`-only first:** Is committing to `HIR` (`P1`–`P2`) first and gating `MIR` (`P3`) on benchmark measurements from `raytracer-argon` / `raytracer-fable` the right checkpoint?
* **`BIP014-Q4` — Compile-time evaluation of user Babble functions (`O1`):** Should `ConstFoldPass` fold only built-in operators and whitelisted JDK `axiom pure deterministic` methods (e.g., `Math.*`, `String.*`), or also evaluate small `@TERMINATING` user-defined `pure deterministic` Babble functions with a step-count fuel budget?
* **`BIP014-Q5` — Stack-trace line fidelity under inlining and LICM:** When an inlined function or hoisted expression throws an exception, should emitted `LineNumberNode`s preserve the original expression's line (matching the `Interpreter` for single-line errors, though without the inlined frame), or should `InlinePass` skip inlining calls that are not `nothrow` when exact stack-frame parity is required?
* **`BIP014-Q6` — Declarative `aegraph` vs. hand-written algebraic passes (`P5`):** Is a lightweight bounded acyclic e-graph worth introducing in `P5` for `BIP007` Axis 3 algebraic axioms, or are hand-written Nanopass rewriters sufficient for Babble's scale?
* **`BIP014-Q7` — `CodeGenerator` refactoring boundary (`P0`):** Should `P0` extract `ClassGen`/`InterfaceGen`/`ConstantPool` into separate files immediately before `P1`, or perform the extraction alongside `HirEmitter` in `P1`?

---

## 10. Bibliography

- **`[LA04]`** Chris Lattner and Vikram Adve. *"[LLVM: A Compilation Framework for Lifelong Program Analysis & Transformation](https://doi.org/10.1109/CGO.2004.1281665)."* In **Proceedings of the International Symposium on Code Generation and Optimization (CGO '04)**, pp. 75–86, Palo Alto, CA, USA, March 2004. DOI: [`10.1109/CGO.2004.1281665`](https://doi.org/10.1109/CGO.2004.1281665) ([Technical Report PDF](https://llvm.org/pubs/2003-09-30-LifelongOptimizationTR.pdf)).
  - **Summary:** `[LA04]` introduces the LLVM compiler infrastructure and its typed, language-independent SSA intermediate representation, demonstrating how a compact CFG-based SSA IR with explicit types and modular analysis/transformation passes supports lifelong optimization across compile, link, and runtime stages.
  - **Contribution to `BIP014`:** `[LA04]` serves as the baseline SSA CFG + `PassManager` model for `BIP014`'s Tier 2 (`MIR`) pipeline (§4, §6.4–§6.5), while clarifying why a single low-level IR loses high-level Babble abstractions (`record`, `borrow`, `unique`, pipelines) and should be paired with `HIR` (§6.3).

- **`[WZ91]`** Mark N. Wegman and F. Kenneth Zadeck. *"[Constant Propagation with Conditional Branches](https://doi.org/10.1145/103135.103136)."* **ACM Transactions on Programming Languages and Systems (TOPLAS)**, 13(2):181–210, April 1991. DOI: [`10.1145/103135.103136`](https://doi.org/10.1145/103135.103136).
  - **Summary:** `[WZ91]` presents Sparse Conditional Constant Propagation (SCCP), combining constant propagation with unreachable-branch elimination in a single lattice worklist over SSA def-use edges and CFG reachability edges, proving it strictly dominates running constant propagation and dead-code elimination separately.
  - **Contribution to `BIP014`:** `[WZ91]` directly supplies the algorithm for `SccpPass` in `BIP014-P3` (§6.2, §8), unifying `O1` constant propagation and `O4` unreachable-branch pruning over `MIR`.

- **`[SWD05]`** Dipanwita Sarkar, Oscar Waddell, and R. Kent Dybvig. *"[Educational Pearl: A Nanopass Framework for Compiler Education](https://doi.org/10.1017/S0956796805005605)."* **Journal of Functional Programming**, 15(5):653–667, September 2005. DOI: [`10.1017/S0956796805005605`](https://doi.org/10.1017/S0956796805005605). *(Preliminary version: "[A Nanopass Infrastructure for Compiler Education](https://doi.org/10.1145/1016848.1016878)," ICFP '04, ACM SIGPLAN Notices 39(9):201–212, September 2004.)*
  - **Summary:** `[SWD05]` introduces the nanopass compiler methodology, decomposing compilation into many small, single-purpose passes operating between formally specified intermediate languages (`define-language` with delta inheritance and `define-pass` with auto-generated traversal boilerplate) so each pass is easy to understand, test, and verify.
  - **Contribution to `BIP014`:** `[SWD05]` inspires `BIP014`'s small, single-responsibility `HirPass`/`MirPass` design and mandatory post-pass verification (`HirVerifier` / `MirVerifier` under `babble.verify=true`, §4, §6.5), adapted to Java 21 `sealed interface`s and `record`s without proliferating dozens of separate AST hierarchies.

- **`[KD13]`** Andrew W. Keep and R. Kent Dybvig. *"[A Nanopass Framework for Commercial Compiler Development](https://doi.org/10.1145/2500365.2500618)."* In **Proceedings of the 18th ACM SIGPLAN International Conference on Functional Programming (ICFP '13)**, pp. 343–350, Boston, MA, USA, September 2013. DOI: [`10.1145/2500365.2500618`](https://doi.org/10.1145/2500365.2500618) ([Preprint PDF](https://andykeep.com/pubs/np-preprint.pdf)).
  - **Summary:** `[KD13]` evaluates the nanopass architecture in a production setting by replacing five monolithic passes of the commercial Chez Scheme compiler with over 50 nanopasses, demonstrating 15–27% faster generated code with compile times well within a factor of two of the original compiler.
  - **Contribution to `BIP014`:** `[KD13]` validates that decomposing Babble's monolithic 5,996-line `CodeGenerator` into a modular IR and many small optimization passes (§4, §6, §8) improves code quality and maintainability without unacceptable compile-time overhead.

- **`[Cli95]`** Cliff Click. *"[Global Code Motion / Global Value Numbering](https://doi.org/10.1145/223428.207154)."* In **Proceedings of the ACM SIGPLAN 1995 Conference on Programming Language Design and Implementation (PLDI '95)**, pp. 246–257, La Jolla, CA, USA, June 1995. DOI: [`10.1145/223428.207154`](https://doi.org/10.1145/223428.207154).
  - **Summary:** `[Cli95]` introduces the Sea-of-Nodes IR (later adopted in HotSpot C2) and combines Global Value Numbering (GVN) with Global Code Motion (GCM) by detaching pure instructions from basic blocks and scheduling them into loop-nest-aware dominator positions.
  - **Contribution to `BIP014`:** `[Cli95]` establishes both the classical Sea-of-Nodes design point evaluated in §4 and the dominator/loop-nest placement principles underlying `GvnCsePass` (`O5`) and `LicmPass` (`O6`).

- **`[Mer25]`** Darius Mercadier (V8 Team). *"[Land ahoy: leaving the Sea of Nodes](https://v8.dev/blog/leaving-the-sea-of-nodes)."* **V8 Blog**, March 25, 2025.
  - **Summary:** `[Mer25]` documents V8's multi-year migration of TurboFan off Sea-of-Nodes onto **Turboshaft**, a compact CFG-based SSA IR, reporting that for effectful languages most operations end up serialized on control/effect chains anyway, and that returning to CFG SSA roughly halved compile time, reduced L1 data-cache misses by 3–7×, and dramatically simplified debugging and pass authoring (e.g., load elimination up to 190× faster).
  - **Contribution to `BIP014`:** `[Mer25]` is the decisive empirical evidence for choosing an explicit **CFG SSA IR with block parameters** over Sea-of-Nodes for `BIP014`'s Tier 2 `MIR` (§4, §6.4).

- **`[WNW+21]`** Max Willsey, Chandrakana Nandi, Yisu Remy Wang, Oliver Flatt, Zachary Tatlock, and Pavel Panchekha. *"[egg: Fast and Extensible Equality Saturation](https://doi.org/10.1145/3434304)."* **Proceedings of the ACM on Programming Languages (PACMPL)**, 5(POPL), Article 23, pp. 1–29, January 2021. DOI: [`10.1145/3434304`](https://doi.org/10.1145/3434304) ([`arXiv:2004.03082`](https://arxiv.org/abs/2004.03082)).
  - **Summary:** `[WNW+21]` makes e-graph equality saturation practical for rewrite-driven optimization via amortized congruence-closure *rebuilding* and extensible *e-class analyses*, allowing algebraic rewrite rules to fire without destructive phase-ordering conflicts before cost-based extraction.
  - **Contribution to `BIP014`:** `[WNW+21]` frames the equality-saturation design point surveyed in §4 and evaluated in `BIP014-P5` / `BIP014-Q6` for composing `BIP007` Axis 3 algebraic axioms (`associative`, `commutative`, `idempotent`, `monotone`, `injective`).

- **`[Fal22]`** Chris Fallin. *"[Cranelift: Using E-Graphs for Verified, Cooperating Middle-End Optimizations](https://github.com/bytecodealliance/rfcs/blob/main/accepted/cranelift-egraph.md)."* **Bytecode Alliance Accepted RFC**, 2022.
  - **Summary:** `[Fal22]` designs Cranelift's e-graph mid-end optimizer, introducing **acyclic e-graphs (`aegraph`)** coupled to a CFG side-effect skeleton and declarative ISLE rewrite rules to unify GVN, LICM, constant folding, and algebraic simplification in a fast compiler.
  - **Contribution to `BIP014`:** `[Fal22]` shows how to combine a conventional CFG skeleton (preserving side effects and control flow) with e-graph rewrites over pure subexpressions, informing `BIP014-P5` and `BIP014-Q6` (§4, §8, §9).

- **`[Fal26]`** Chris Fallin. *"[The acyclic e-graph: Cranelift's mid-end optimizer](https://cfallin.org/blog/2026/04/09/aegraph/)."* Technical Blog, April 9, 2026.
  - **Summary:** `[Fal26]` retrospectively explains Cranelift's production `aegraph` architecture—embedding append-only union nodes directly in SSA value space, eagerly rewriting pure nodes in a single domtree-preorder pass without parent pointers, and returning to linear CFG via scoped elaboration (subsuming GVN, DCE, and LICM).
  - **Contribution to `BIP014`:** `[Fal26]` clarifies the exact compile-time trade-offs between full equality saturation (`[WNW+21]`) and single-pass `aegraph` rewriting, reinforcing why `BIP014` starts with straightforward CFG SSA (`P3`) and treats `aegraph` as a targeted `P5` option.

- **`[LAB+21]`** Chris Lattner, Mehdi Amini, Uday Bondhugula, Albert Cohen, Andy Davis, Jacques Pienaar, River Riddle, Tatiana Shpeisman, Nicolas Vasilache, and Oleksandr Zinenko. *"[MLIR: Scaling Compiler Infrastructure for Domain Specific Computation](https://doi.org/10.1109/CGO51591.2021.9370308)."* In **2021 IEEE/ACM International Symposium on Code Generation and Optimization (CGO '21)**, pp. 2–14, February 2021. DOI: [`10.1109/CGO51591.2021.9370308`](https://doi.org/10.1109/CGO51591.2021.9370308). *(Earlier preprint: "[MLIR: A Compiler Infrastructure for the End of Moore's Law](https://arxiv.org/abs/2002.11054)," [`arXiv:2002.11054`](https://arxiv.org/abs/2002.11054), February 2020.)*
  - **Summary:** `[LAB+21]` introduces MLIR, demonstrating progressive lowering across multiple levels of abstraction with first-class source-location tracking, SSA block arguments, and IR verification so high-level domain optimizations run before lowering erases semantic structure.
  - **Contribution to `BIP014`:** `[LAB+21]` motivates `BIP014`'s two-tier progressive lowering (`HIR` → `MIR` → ASM bytecode, §4, §6.2–§6.4), mandatory `ExprMeta.line` source-location tracking, and SSA block parameters.

- **`[RXdML21]`** Alex Reinking, Ningning Xie, Leonardo de Moura, and Daan Leijen. *"[Perceus: Garbage Free Reference Counting with Reuse](https://doi.org/10.1145/3453483.3454032)."* In **Proceedings of the 42nd ACM SIGPLAN International Conference on Programming Language Design and Implementation (PLDI '21)**, pp. 96–111, June 2021. DOI: [`10.1145/3453483.3454032`](https://doi.org/10.1145/3453483.3454032) ([PDF](https://xnning.github.io/papers/perceus.pdf)).
  - **Summary:** `[RXdML21]` formalizes precise reference counting and reuse analysis in Koka, showing how *functional-but-in-place* (FBIP) transformations turn purely functional updates on uniquely owned values into zero-allocation in-place mutations.
  - **Contribution to `BIP014`:** `[RXdML21]` provides the conceptual foundation for `O10` (`FbipReusePass`, §3.2, `BIP014-P4`), which exploits `BIP007-P2`'s static `unique` + `move(...)` ownership to perform in-place reuse on the JVM without runtime reference counting.

- **`[LLS23]`** Anton Lorenzen, Daan Leijen, and Wouter Swierstra. *"[FP²: Fully in-Place Functional Programming](https://doi.org/10.1145/3607840)."* **Proceedings of the ACM on Programming Languages (PACMPL)**, 7(ICFP), Article 198, pp. 275–304, August 2023. DOI: [`10.1145/3607840`](https://doi.org/10.1145/3607840) ([PDF](https://webspace.science.uu.nl/~swier004/publications/2023-icfp.pdf)).
  - **Summary:** `[LLS23]` defines a linear fully in-place (FIP/FBIP) calculus distinguishing uniquely owned and borrowed parameters alongside unboxed tuples, proving that functions in the FIP fragment execute with zero heap allocation and no dynamic reference counting.
  - **Contribution to `BIP014`:** `[LLS23]` directly connects `BIP007-P2`'s static `unique` vs. `borrow` parameter distinction to allocation-free in-place reuse (`O10`) and stack unboxing (`O9`) in `BIP014-P4`.

