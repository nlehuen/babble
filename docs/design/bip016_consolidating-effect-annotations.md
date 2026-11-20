# BIP016: Consolidating Effect Annotations

| | |
|---|---|
| **BIP** | 016 |
| **Date** | 2026-11-19 |
| **Status** | **Implemented** (`BIP016-Q1`–`Q7` → `BIP016-D1`–`D10` decided and implemented across `BIP016-P1`–`P4`; verified across 1,658 dual-mode tests, pure `SLL`, `-format -check`, and byte-for-byte Argon `scene.ppm` parity) |
| **Depends on** | Function effects and algebraic properties (`RM002` / `RM007`, [bip007_effects.md](bip007_effects.md)), core scalar & collection types (`RM005`, [bip011_core-types.md](bip011_core-types.md)), predicate-free LL(1)/LL(2) grammar (`RM006`, [bip012_grammar-improvements.md](bip012_grammar-improvements.md)), compiler optimization passes ([bip014_optimization-passes.md](bip014_optimization-passes.md)) |
| **Scope** | Surface ergonomics of Babble's effect and ownership annotations: empirical audit of `examples/raytracer-argon/`, separating **internal orthogonal compiler traits** (`@UPPERCASE`) from a **closed opt-out surface vocabulary** (`BIP016-D1`, `D7`), making **`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}` the unmarked default on typed `fn`s, `export`s, `interface`s, and function types** (`BIP016-D3`, `D4`), inferring `@NOTHROW` & `@TERMINATING` with opt-in `nothrow` (`BIP016-D2`), unifying `{@UNIQUE, @SHARED, @BORROWED}` + `frozen T` onto one ownership axis orthogonal to effects (`volatile` / `io`) and mutability (`@CONST` / `mut`) (`BIP016-D5`), higher-order specialization (`BIP016-D6`), `axiom pure` & `org.babblelang.effects` alignment (`BIP016-D8`), companion `EffectChecker` precision fixes + single-pass migration (`BIP016-D9`), and **return-through borrow** so `id`, `min`, and getters never require `shared` while closing both `unique`/`freeze` and `frozen` soundness holes (`BIP016-D10`) |

---

## 1. Motivation: The "Attribute Soup" Revealed by Argon

In [bip007_effects.md](bip007_effects.md) §2, Babble explicitly identified D's **"attribute soup"** (`pure @safe nothrow @nogc const`) as a major usability pitfall and relied on two defenses:

1. **Whole-module fixed-point inference** for unannotated scripts and private helpers.
2. **Orthogonal, composable keywords** (`pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, plus algebraic properties and `unique`/`frozen`) when authors write explicit contracts on exports, interfaces, or verified boundaries.

When we put `BIP007` through its first full-program stress test — annotating every function, method, interface signature, and callback across all 14 modules of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) (~1,050 lines of 100% pure Babble, zero `axiom` escapes) — inference worked well internally, **but explicit contracts suffered from severe attribute soup**:

```babble
;; Today under BIP007 (examples/raytracer-argon/lib/vec3.ba & lib/materials.ba):
def dot = fn pure deterministic nothrow borrow (borrow v: Vec3): double -> (
    this.x * v.x + this.y * v.y + this.z * v.z
)

def scatter = fn hermetic deterministic nothrow borrow (borrow rIn: Ray, borrow rec: HitRecord, mut borrow rng: Rng): ScatterResult? -> (
    ...
)
```

On a trivial 3D dot product, the effect and aliasing qualifiers (`pure deterministic nothrow borrow`, `borrow`) outnumber the actual signature (`dot`, `v: Vec3`, `double`). Across the 105 `fn` declarations in Argon, explicit annotations required **439 effect/aliasing tokens** (**4.18 tokens per `fn`**):

* **362 function-level modifier tokens** (average **3.45** per `fn`), and
* **77 parameter/return modifier tokens** (`44` `borrow`, `16` `mut borrow`, `1` `mut`, `2` `unique` returns — including **124 `borrow` tokens** total across receivers and parameters!).

Two structural causes account for nearly all of this noise:

1. **Inverted polarity across every axis.** In Argon, **95%+** of functions are `@HERMETIC` and `@DETERMINISTIC`, **88.9%** of instance methods do not escape `this`, and **89.6%** of reference parameters do not escape. Yet `BIP007` required authors to opt *into* every safe guarantee (`pure`, `hermetic`, `deterministic`, `nothrow`, `borrow`) one keyword at a time instead of making the safe, common case the unmarked default and annotating only the rare deviations.
2. **1-to-1 coupling between internal compiler traits and surface keywords — with `pure` at the bottom instead of the top.** `BIP007` defined `pure` to mean *no writes only* (matching the Java Checker Framework's `@SideEffectFree`, not its `@Pure`). Consequently, `pure` alone was used **0 times** in Argon, while `pure deterministic [nothrow]` accounted for **73.3%** of all functions. Likewise, `hermetic` alone was used **0 times**, while `hermetic deterministic nothrow` accounted for **18.1%**.

By cleanly decoupling **internal orthogonal compiler traits** (`@HERMETIC`, `@DETERMINISTIC`, `@NOTHROW`, `@TERMINATING`, per-slot `@CONST`/`@MUTABLE` and `@BORROWED`/`@SHARED`/`@UNIQUE`) from the surface vocabulary (`BIP016-D1`), making **`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}` the unmarked default** while inferring `@NOTHROW` & `@TERMINATING` (`BIP016-D2`–`D5`), separating **storing** (`shared`) from **returning** (`BIP016-D10`), and writing only the explicit opt-outs (**`io`**, **`volatile`**, **`mut`**, **`shared`**, **`unique`**, and optional **`nothrow`**), the two functions above collapse to:

```babble
;; Under BIP016 (pure, deterministic, const, borrowed by default!):
def dot = fn (v: Vec3): double -> (
    this.x * v.x + this.y * v.y + this.z * v.z
)

def scatter = fn (rIn: Ray, rec: HitRecord, mut rng: Rng): ScatterResult? -> (
    ...
)
```

This proposal documents the complete Argon empirical data (§2), re-evaluates `BIP007` through the two-layer trait/annotation model and `@NOTHROW` optimizer audit (§3), surveys prior art across ten languages and academic effect systems (§4), compares four concrete consolidation designs (§5), specifies the final consolidated semantics, ownership state machine, return-through-borrow design (`BIP016-D10`), and Argon impact (§6), records all settled decisions `BIP016-D1`–`D10` (resolving `BIP016-Q1`–`Q7`) plus the completed implementation phases `BIP016-P1`–`P4` (§7), and provides a verified bibliography (§8).

---

## 2. Empirical Audit of `examples/raytracer-argon/`

Every `fn` expression and function type across all 14 files of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) (`raytracer.ba` and `lib/*.ba`) was annotated with the strongest compiler-verified `BIP007` contract (zero `axiom` declarations, verified by `ExamplesTestCase`, `FormatterTestCase`, full dual-mode `mvn test`, and byte-for-byte `scene.ppm` rendering parity).

### 2.1 Function-Level Annotation Frequencies & Consolidation Table

The table below lists all **105 `fn` occurrences** in Argon, **sorted by decreasing frequency**, alongside their exact `BIP007` semantics and their final implemented `BIP016` annotation under `BIP016-D1`–`D10`:

| Rank | Count (%) | Prior `BIP007` Combination | Semantics & Argon Examples | Final `BIP016` Annotation *(`BIP016-D1`–`D10`)* |
|---:|---:|---|---|---|
| **1** | **49** *(46.7%)* | `pure deterministic nothrow borrow` | Strictly side-effect-free, deterministic, exception-free instance method whose `this` does not escape (`Vec3.*`, `Ray.at`, `Interval.*`, `AABB.*`, `Describable.*`, `Material.kind`, `Hittable.boundingBox`/`kind`, `emptyHittable.*`, `Sphere.boundingBox`/`kind`/`describe`, `HittableList.boundingBox`/`countWhere`/`kind`, `BVHNode.boundingBox`/`kind`, `Color.linearToGamma`, `Scenes.threeSpheres`) | *(none)* *(0 keywords; was 4 — all defaults, `@NOTHROW` inferred)* |
| **2** | **25** *(23.8%)* | `pure deterministic nothrow` | **20** free/static functions & callbacks with no `this` (`lib/math.ba` ×13, `Interval.enclose`, `AABB.fromPoints`/`enclose`, `HitRecord.create`, `Dielectric.reflectance`, `countWhere` predicate type & lambda); **5** instance methods returning references reachable from `this` without storing `this` (`Hittable.hit`, `Sphere.hit`, `HittableList.hit`, `BVHNode.hit`, `HittableList.headNode`) | *(none)* *(0 keywords; `D10` return-through-borrow eliminates `shared` on getters & `hit`!)* |
| **3** | **10** *(9.5%)* | `hermetic deterministic nothrow borrow` | No globals/I/O, deterministic, exception-free method whose `this` is `@CONST @BORROWED` while mutating a `mut` parameter (`Material.scatter`, `Lambertian`/`Metal`/`Dielectric.scatter`, `Camera.sampleSquare`/`defocusDiskSample`/`getRay`/`rayColor`/`renderPixel`, `Scenes.finalScene`) | *(none)* *(0 fn keywords; was 4 — `mut` lives on param `mut rng: Rng`)* |
| **4** | **5** *(4.8%)* | `hermetic deterministic nothrow` | Free/static functions (no `this`) with no globals/I/O, deterministic, exception-free, mutating a `mut` parameter (`Vec3.random`, `randomRange`, `randomUnitVector`, `randomOnHemisphere`, `randomInUnitDisk`) | *(none)* *(0 fn keywords; was 3)* |
| **5** | **3** *(2.9%)* | `hermetic mut borrow deterministic nothrow` | No globals/I/O, deterministic, exception-free method mutating `this` without escaping `this` (`Rng.nextDouble`, `Rng.nextRange`, `HittableList.clear`) | **`mut`** *(1 keyword; was 5)* |
| **6** | **3** *(2.9%)* | `impure` | Unconstrained I/O or test harness entry points (`Camera.render`, `runTests`, `renderMain`) | **`io`** *(1 keyword; replaces `impure`)* |
| **7** | **2** *(1.9%)* | `pure nothrow` | Side-effect-free and exception-free, previously non-deterministic only due to stateless `emptyHittable` (`HittableList.first`, `HittableList.second`, returning elements without storing `this`) | *(none)* *(0 keywords; `D9` restores `@DETERMINISTIC` and `D10` allows return-through-borrow!)* |
| **8** | **2** *(1.9%)* | `pure deterministic` | Side-effect-free and deterministic free function that may throw at runtime via narrowing `int(x)` (`math.toByte`, `math.toInt`) | *(none)* *(0 keywords; was 2 — default allows exceptions per `BIP016-D2`)* |
| **9** | **2** *(1.9%)* | `nothrow` | `BVHNode.fromList` (static; with `D9`/`D10`, pure & deterministic with 0 keywords!) and frame-local accumulator closure in `HittableList.countWhere` | *(none)* *(0 keywords; `@NOTHROW` inferred)* |
| **10** | **1** *(1.0%)* | `pure deterministic borrow` | Side-effect-free, deterministic, `@BORROWED` `this` method that may throw via `math.toByte` (`Color.formatPpm`) | *(none)* *(0 keywords; was 3)* |
| **11** | **1** *(1.0%)* | `hermetic mut deterministic nothrow` | No globals/I/O, deterministic, exception-free method mutating `this` where `this.head` is stored back into `this.head` via a fresh `HittableNode` (`HittableList.add`) | **`mut`** *(1 keyword; was 4 — `D10` recognizes `this.head = Node(item, this.head)` stays confined to `this` while requiring `shared item`)* |
| **12** | **1** *(1.0%)* | `borrow` | Higher-order method polymorphic in callback `f`'s effects whose `this` does not escape (`HittableList.forEach`) | **`io`** *(HOF with `f: fn io (...)`; call-site specialized per `BIP016-D6`)* |
| **13** | **1** *(1.0%)* | *(none)* | Unconstrained higher-order callback type inside `HittableList.forEach` | **`io`** *(`fn io (item: Hittable): any?`)* |
| **Total** | **105** *(100%)* | **362 function-prefix tokens** *(3.45 / fn)* | **0** uses of standalone `pure`; **0** uses of standalone `hermetic` | **9 function-prefix tokens** *(`5 io`, `4 mut`, `0 shared` — **0.09 / fn, −97.5%**; **96/105 = 91.4%** have **0** fn keywords and **105/105 = 100%** have 0–1!)* |

### 2.2 Parameter & Return Modifier Frequencies

Sorted by decreasing frequency across all parameter lists and return clauses in `examples/raytracer-argon/`:

| Scope | Count | Prior `BIP007` Modifier | Breakdown in Argon | Final `BIP016` Annotation *(`BIP016-D4`/`D5`/`D9`/`D10`)* |
|---|---:|---|---|---|
| Parameter | **44** | `borrow` | **34** deeply immutable `record`s (`Vec3` ×12, `Ray` ×12, `Interval` ×8, `AABB` ×2), **4** `HitRecord`, **4** `Hittable`, **2** `fn` callbacks | *(omitted — `@BORROWED` is the default)* |
| Parameter | **16** | `mut borrow` | **16** `Rng` parameters (`Vec3.random*`, `Material.scatter`, `Camera.*`, `Scenes.finalScene`) | **`mut`** *(1 keyword; was 2)* |
| Parameter | **1** *(was 6 pre-`D10`)* | *(none, ref param stored in heap)* | `item` in `HittableList.add` (`mat` in `HitRecord.create`, `world` in `Camera.*`, and `items` in `BVHNode.fromList` only return or read locally — **0 `shared`** via `BIP016-D10`!) | **`shared`** *(only **1** `shared` parameter in the entire 14-file ray tracer!)* |
| Return | **2** | `unique` | `Scenes.threeSpheres`, `Scenes.finalScene` (`: unique HittableList`) | **`unique`** *(unchanged)* |

Receiver `borrow` distribution across Argon's **72 non-`impure` instance methods**:

| Enclosing Construct | Non-escaping `this` (`borrow`) | Escaping `this` (no `borrow`) | Non-escaping share |
|---|---:|---:|---:|
| `record` (`Vec3`, `Ray`, `Interval`, `AABB`) | 26 | 0 | **100.0%** |
| `class` (`Rng`, `Lambertian`, `Metal`, `Dielectric`, `Sphere`, `HittableList`, `BVHNode`, `Camera`) | 25 | 7 | **78.1%** |
| `interface` (`Describable`, `Material`, `Hittable`) | 6 | 1 | **85.7%** |
| `object` (`emptyHittable`, `Color`, `Scenes`) | 7 | 0 | **100.0%** |
| **Total non-`impure` instance methods** | **64** | **8** | **88.9%** |

### 2.3 Four Empirical Takeaways

1. **91.4% of all functions are just two contracts:** `pure deterministic [nothrow]` (**77/105 = 73.3%**) and `hermetic deterministic nothrow` (**19/105 = 18.1%**, including 4 with `mut` `this`). Under `BIP007`, nearly all of those 96 functions required **3 to 5 keywords**.
2. **Standalone `pure` and standalone `hermetic` are dead weight in normal code:** Neither appeared even **once** (`0/105`) in Argon. The only real-world cases for *non-deterministic, side-effect-free* code are environment/clock/file reads (`Instant.now()`, `Files.readString`, reading a mutable outer singleton) — an uncommon boundary (~2% in Argon), not the default meaning programmers expect from the word `pure`.
3. **Non-escaping (`borrow`) is the ~89% norm for both `this` and parameters:** Requiring explicit `borrow` produced **124 `borrow` tokens** across 105 functions to protect against only **8** escaping receivers and **7** escaping reference parameters. Worse, `100%` of `record` methods (`26/26`) and `100%` of `mut` `Rng` parameters (`16/16`) had to repeat `borrow`.
4. **Algebraic properties (`associative`, `commutative`, `idempotent`, `monotone`, `injective`) are NOT the noise source:** They appeared **0 times** in Argon (they belong on math/reduction/collection library APIs and `axiom`s). Consolidating the core effect & borrowing axes fixes the everyday ergonomics problem without touching the algebraic vocabulary.

---

## 3. Reconsidering `BIP007`: Internal Orthogonal `@TRAITS` vs. Surface Annotations

### 3.1 The Minimal Orthogonal Basis (`BIP016-D1`)

`BIP007` §3 already introduced a clean notational convention: **lowercase** for surface modifiers written by programmers (`pure`, `mut`, `borrow`, …) and **`@UPPERCASE`** for internal compiler states and inferred-only guarantees (`@PARAM(i)`, `@THIS`, `@FROZEN`, `@MOVED`, `@EXTERNAL`, `@TERMINATING`, `@TOTAL`, `@AXIOMATIC`).

Auditing [`Effect.java`](../../src/main/java/org/babblelang/effects/Effect.java), [`EffectSet.java`](../../src/main/java/org/babblelang/analysis/EffectSet.java), [`Type.java`](../../src/main/java/org/babblelang/analysis/Type.java), and [`EffectChecker.java`](../../src/main/java/org/babblelang/analysis/EffectChecker.java) reveals that once derived concepts are factored out (notably `Effect.PURE` $\equiv$ `@HERMETIC` $\land$ `@CONST` on all slots), Babble's entire core effect and ownership system reduces to **4 function-level traits + 2 per-slot axes** (mutability and 3-state ownership), plus 5 optional algebraic traits and 2 control/metadata flags:

| Layer 1 Category | Internal Orthogonal Traits (`@UPPERCASE`) | Current Code Representation | Consumed By |
|---|---|---|---|
| **Function Effects (4)** | **`@HERMETIC`** *(no I/O or global/outer writes)*, **`@DETERMINISTIC`** *(depends only on visible inputs)*, **`@NOTHROW`** *(never throws on typed inputs)*, **`@TERMINATING`** *(finite execution; inferred-only)* | `Effect.HERMETIC`, `Effect.DETERMINISTIC`, `Effect.NOTHROW`, `EffectSet.terminating` | `EffectChecker` confinement, `BIP014` `O1`/`O4`/`O5`/`O6`/`O11`/`O12` |
| **Per-Slot Mutability Axis** | **`@CONST`** *(default, no keyword — read-only view through this slot, i.e., `!@MUTABLE`, distinct from deeply immutable `frozen T`)* vs. **`@MUTABLE`** *(`mut` — callee may mutate `this`/param `i`)* | `EffectSet.mutThis`, `Type.Parameter.mut` | Exclusivity, `pure` checking, `BIP007-D7` `frozen` calls, `BIP014` `O7` |
| **Per-Slot Ownership Axis** | **`@BORROWED`** *(default, no keyword — callee may manipulate the ref during the call, compiler guarantees it is no longer held when the callee terminates)* vs. **`@SHARED`** *(`shared` — ref may outlive the call)* vs. **`@UNIQUE`** *(`unique` — sole-owner graph transferred via `move` on param/return)* | `EffectSet.borrowThis`/`uniqueResult`, `Type.Parameter.borrow`/`unique` | Escape rejection, `RM005` covariance, `BIP007-D7` `frozen` calls, `BIP014` `O9`/`O10` |
| **Algebraic Traits (5)** | **`@IDEMPOTENT`**, **`@COMMUTATIVE`**, **`@ASSOCIATIVE`**, **`@MONOTONE`**, **`@INJECTIVE`** | `Effect.IDEMPOTENT`..`INJECTIVE` | `BIP014` `O2`/`O3`/`O12`, `AxiomChecker` |
| **Control / Provenance (2)** | **`@AXIOMATIC`** *(trusted `axiom` boundary)*, **`@IMPURE_LOCK`** *(locks function effects to `{}`)* | `EffectSet.axiomatic`, `EffectSet.impure` | `allowAxioms(false)`, `BIP007-D2` export rules |

*(Planned future function-level trait from `BIP007` §10: **`@THREADSAFE`**. Type-level **`frozen T`** / `Type.Frozen` and local flow states `@PARAM(i)`/`@THIS`/`@EXTERNAL`/`@MOVED` remain unchanged.)*

**Layer 2 — Surface Annotations as Trait Collections (`BIP016-D1`):** Surface keywords written by Babble programmers are **named collections (profiles) of Layer 1 traits** (desugared during `EffectChecker.parsePrefix` / `parseParamMods`):
* Adding a new internal compiler trait (like `@TERMINATING` in `RM007`, or future `@THREADSAFE`) does **not** pollute surface signatures.
* Evolving surface annotations requires **zero** change to the fixed-point inference engine, `TypeAlgebra` subtyping, `LoweringAnalyzer`, `BIP014` optimizer passes, or `babble-runtime` (`RuntimeBoundaryTestCase`).
* Diagnostics can name the exact violated `@TRAIT` while suggesting the concise surface annotation, and `EffectSet.toString()` / `:type` display can render the shortest canonical surface form covering the trait set.

### 3.2 Why Pure Functions Allow Exceptions (`@NOTHROW` & `@TERMINATING` Inferred — `BIP016-D2`)

A key reconsideration of `BIP007` is the role of **`@NOTHROW`** and **`@TERMINATING`** inside default pure functions:

* **Why `@TERMINATING` stays inferred-only:** Because of the Halting Problem, `EffectChecker` conservatively drops `@TERMINATING` on any `while` loop or `recurse` call (`BIP007` §5.1). In Argon, that includes `math.sqrt`, `math.sin`, `math.cos`, `math.powInt`, `Vec3.length`, `Sphere.hit`, `HittableList.hit`, and `BVHNode.hit`. Requiring `@TERMINATING` in the pure default would make all of those textbook pure functions unverifiable without `axiom`. Keeping `@TERMINATING` inferred-only lets straight-line functions (`Vec3.dot`, `Interval.clamp`, `AABB.hit`) receive `@TERMINATING` automatically while loop/recursive pure functions stay unmarked `fn (...)`.
* **Why `@NOTHROW` also stays inferred-by-default (opt-in `nothrow`):** Conceptually, an exception can be viewed as an alternate return channel (`T | Exception`). Auditing [`bip014_optimization-passes.md`](bip014_optimization-passes.md) shows:
  * **Does NOT need `@NOTHROW`:** CSE / GVN (`O5` — if the first `f(x)` threw, execution never reaches the second), constant folding on executed calls (`O1`), algebraic / `idempotent` collapse (`O2`), **guarded LICM** (`O6` — `if cond then (def tmp = f(inv); do-while ...)`), no-alias RLE (`O7`), inlining (`O8`), scalar replacement (`O9`), and FBIP reuse (`O10`).
  * **DOES need `@NOTHROW`:** Deleting an unused call (`O4` DCE / `f(x) * 0 -> 0`), unguarded speculative hoisting into a 0-iteration loop (`O6`), and reordering two calls/predicates (`O11` short-circuit reordering, `O12` filter push-down, `O3` reassociation).
  * Because `EffectChecker` already infers `@NOTHROW` automatically for every concrete Babble function (just like `@TERMINATING`), `BIP014` passes and `BIP007-D8` (`Unused pure expression`) retain **100% of their power** on concrete code **without** forcing `throws` or `nothrow` onto everyday signatures!
  * Defining the unmarked pure default as **`{@HERMETIC, @CONST, @DETERMINISTIC, @BORROWED}`** (and `axiom pure` / Java `@Pure` as the same bundle) aligns Babble directly with the Java Checker Framework (`@Pure` = `@SideEffectFree` + `@Deterministic`), Haskell `pure`, Koka `pure` (`<div, exn>`), and Flix's pure-by-default polarity, eliminates any `throws` keyword, keeps exception-freedom **100% monotonic** (`fn (...)` $\to$ `fn nothrow (...)`), and lets `math.toInt`, `math.toByte`, `Color.formatPpm`, and `Path.of` all work cleanly without extra exception annotations!

### 3.3 Remaining `BIP007` Surface Friction (`@BORROWED` & Precision Cleanups)

* **Contract-closure friction on `@BORROWED` (`borrow`, `BIP007-D2`):** For unannotated functions, `EffectChecker` optimistically infers `@BORROWED` on `this` and all parameters. Once an author wrote *any* explicit effect annotation on an exported function or interface method, `BIP007-D2` exposed strictly the declared traits — silently dropping `@BORROWED` unless `borrow` was repeated on `this` and every single parameter (breaking `frozen T` method calls per `BIP007-D7` and `BIP014` `O9` scalar replacement). Yet `@BORROWED` holds on **88.9%** of receivers and **89.6%** of reference parameters in Argon! **`BIP016-D4`** fixes this by inverting the polarity: `@BORROWED` (the callee may manipulate the reference during the call, and the compiler statically guarantees it is no longer held when the callee terminates) becomes the unmarked default with no user-facing `borrow` keyword, and `@SHARED` (`!@BORROWED`) is denominated explicitly with **`shared`**.
* **Precision cleanups surfaced by Argon (`BIP016-D9`):**
  1. In `HittableList.first`/`second`, reading the stateless `object implements Hittable` singleton `emptyHittable` cleared `@DETERMINISTIC` because `EffectChecker` treats any non-`record` `object` singleton as potentially mutable outer state even when it defines zero fields.
  2. In `BVHNode.fromList(items: HittableList)`, storing `cur.item` (derived from `items`) into a fresh local `leftList` demoted `leftList` with `@PARAM(items)` taint, forcing `@MUTABLE` (`mut items`) even though `items` itself is never mutated.

---

## 4. Cross-Language Comparative Analysis

Other languages and static-analysis tools have all confronted the exact tension between **fine-grained compiler facts** and **livable surface annotations**. Their solutions fall into four distinct patterns:

| Language / System | Surface Vocabulary | How Common Combinations Are Shortened | Key Lesson for Babble |
|---|---|---|---|
| **D** (`[D-Func]`, `[D-Style]`) | `pure`, `nothrow`, `@safe`, `@nogc`, `const`, `scope` | Additive opt-in keywords; D's own style guide mandates writing `const @nogc nothrow pure @safe` on non-templated functions. Template inference mitigates it internally, not on virtual/interface/API signatures. | **Cautionary tale:** 1-to-1 additive attributes inevitably create attribute soup at API and interface boundaries — exactly what happened in Argon. |
| **Java Checker Framework** (`[CF-Pure]`, `[CF-SEF]`, `[CF-Man]`) | `@Pure`, `@SideEffectFree`, `@Deterministic` | **`@Pure` is explicitly defined as `@SideEffectFree` + `@Deterministic`**, reserving the weaker name `@SideEffectFree` for non-deterministic readers (`System.nanoTime()`). | **Naming lesson:** `pure` must sit at the **top** of the purity ladder; the weaker "no writes, may read mutable/external state" property deserves its own distinct name (`readonly` / `stable` / `volatile`). |
| **PostgreSQL** (`[PG-Vol]`) | `IMMUTABLE`, `STABLE`, `VOLATILE` | Single-word 3-tier stratified hierarchy (`VOLATILE` default; `STABLE` = no writes, snapshot-consistent; `IMMUTABLE` = no writes, forever deterministic). | **Stratification lesson:** A tiny 2–3-level ladder covers 95%+ of functions with **one** word instead of intersecting independent boolean flags. |
| **GCC / Clang & C23** (`[GCC-Attr]`, `[C23-N2956]`) | GCC `const`/`pure`; C23 `[[unsequenced]]` / `[[reproducible]]` | Two coarse profiles instead of fine-grained flags: `[[reproducible]]` = effectless + idempotent (depends on memory); `[[unsequenced]]` = `reproducible` + stateless + independent (strongest). WG14 explicitly rejected adding fine-grained individual flags. | **Bundling lesson:** Standard C23 bundles orthogonal properties (`effectless`, `idempotent`, `stateless`, `independent`) into **two** surface profiles (`[[unsequenced]]`, `[[reproducible]]`) while the compiler reasons over the underlying properties. |
| **Koka** (`[Koka-Book]`, `[Lei14]`) | `total`, `pure` (`<div, exn>`), `exn`, `div`, `ndet`, `io`, plus `alias` | **Named effect aliases** over an underlying row algebra (`total` = empty row `<>`; `pure` = `<div, exn>`; `io` = `<exn, div, ndet, alloc, read, write, console, ...>`). Also omits `total` by default when no effects are written. | **Profile & alias lesson:** Named bundles over a fine-grained internal lattice give both concise 1-word signatures and full internal precision. *(Note: Koka's `total` corresponds to Babble's `pure deterministic nothrow @TERMINATING`, whereas Koka's `pure` allows `exn` and `div`.)* |
| **Flix** (`[Flix-Doc]`, `[MP20]`) | `Pure` (default), `IO`, effect sets `{Eff1, Eff2}` | Functions are **pure by default** (`a -> b`); only impure effects are annotated (`a -> b \ IO`). Boolean unification handles higher-order effect polymorphism automatically. | **Polarity lesson:** Annotating the *rare deviation* instead of the *common guarantee* cuts annotation volume dramatically — though Babble must preserve backward compatibility for unannotated scripts (`BIP007` §1.3). |
| **Nim** (`[Nim-Eff]`) | `func`, `{.noSideEffect.}`, `{.raises: [].}`, `{.tags: [...].}` | Introduces a dedicated keyword **`func`** as shorthand for `proc ... {.noSideEffect.}` because writing `{.noSideEffect.}` on every pure routine was too noisy. | **Shorthand lesson:** Even a single noisy pragma justified a 1-word shorthand; a 4-word chain (`pure deterministic nothrow borrow`) justifies consolidation even more strongly. |
| **Swift** (`[SE-0377]`, `[Swift-Mut]`) | `throws` (opt-in), `mutating` (opt-in), `borrowing` (default in Swift 6) / `consuming` | Non-throwing is the default (`throws` opts out); non-mutating is the default (`mutating` opts in); **borrowing is the default** for parameters and `self` (SE-0377 / Swift 6), with `consuming` opting out. | **Escape & exception polarity lesson:** Swift systematically chooses the **safe, common case as the unmarked default** (`nothrow`, non-`mut`, `borrowing`) and marks the exception (`throws`, `mutating`, `consuming`). |
| **Rust** (`[Rust-Ref]`, `[Kla20]`) | `const fn`, `&self` / `&mut self`, `&T` / `T` | Immutable by default; borrowing (`&T`) is a 1-character sigil rather than a 6-character keyword; no exceptions (`Result` in return type). | **Ergonomics lesson:** Frequent qualifiers must be either implicit defaults or 1-word/short tokens; multi-word phrases fail ergonomic adoption. |
| **Ada / SPARK** (`[SPARK-Sub]`) & **Scala 3 Capture Checking** (`[BGO23]`, `[ROH12]`) | SPARK `Global => null`, `Side_Effects`; Scala `A -> B` (pure) vs `A => B` (impure), `T^` (capturing/escaping) | Rytz–Odersky–Haller (`[ROH12]`) note that **verbosity is the primary barrier to industrial adoption of effect systems**. Scala 3 Capture Checking defaults function parameters to scoped/non-escaping (`borrow`) and marks escaping/capturing references with `^`. | **Adoption lesson:** Verbosity kills effect-system adoption; non-escaping-by-default with explicit escape marking is the modern state of the art for capability/borrow checking. |

---

## 5. Design Space: Four Consolidation Options

All four options below **keep `BIP007`'s internal `EffectSet` bitmask (`HERMETIC`, `DETERMINISTIC`, `NOTHROW`, algebraic flags, `mutThis`/`borrowThis`, `uniqueResult`, `@TERMINATING`) 100% intact** — they differ strictly in the surface vocabulary and defaults that desugar into `EffectSet`.

### Option 1 — Pure-by-Default Opt-Out Model (`io` / `volatile` / `mut` / `shared` / `unique` + Opt-In `nothrow`) *(Selected Design via `BIP016-D1`–`D9`)*

Make the safe, common case **`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}` the unmarked default** on all typed `fn`s, `export`s, `interface` methods, and function types, infer `@NOTHROW` and `@TERMINATING` automatically (with explicit `nothrow` strictly opt-in — zero `throws` keyword!), and write keywords **only** when opting out along one of three orthogonal axes:

* **Function-effect axis (`BIP016-D2`, `D3`, `D6`):**
  * *(unmarked)* $\equiv$ `{@HERMETIC, @DETERMINISTIC}` (allows exceptions `T | Exception` and loops/recursion; `@NOTHROW` & `@TERMINATING` inferred automatically).
  * **`volatile`** $\equiv$ `{@HERMETIC, !@DETERMINISTIC}` — side-effect-free non-deterministic readers (`Instant.now()`, `Files.readString`, reading mutable outer state); composes with `mut` (`fn volatile mut`).
  * **`io`** $\equiv$ `{!@HERMETIC, !@DETERMINISTIC}` — arbitrary I/O or global/outer writes (`io` implies `volatile`, retiring `impure`).
  * **`nothrow`** $\equiv$ `{@NOTHROW}` — purely additive/opt-in for APIs/interfaces requiring a strict no-exception contract.
  * Clean 3-tier subtyping order: **`fn ()`** $<:$ **`fn volatile ()`** $<:$ **`fn io ()`**.
* **Per-slot mutability axis (`BIP016-D5`):**
  * **`@CONST`** *(unmarked default, no keyword)* vs. **`mut`** (`@MUTABLE`) on `this` (`fn mut ()`) or parameters (`(mut rng: Rng)`).
* **Per-slot ownership axis (`BIP016-D4`, `D5`):**
  * **`@BORROWED`** *(unmarked default, no keyword — compiler guarantees no escape)* vs. **`shared`** (`@SHARED` — may outlive call) vs. **`unique`** (`@UNIQUE` — consuming `move`, invalidating source).
* **Scripting ergonomics preserved (`BIP016-D3`):**
  * Top-level script statements and REPL inputs allow `io` implicitly.
  * Completely untyped inline lambdas (no effect modifiers, no parameter `: Type`, no `: R` return type — e.g., `xs.forEach(fn (x) -> (println(x)))`) continue inferring `io`/`volatile`/`mut`/`shared` automatically (extending `BIP007`'s existing untyped-lambda `mut` rule).
  * Concrete `fn io` higher-order functions (e.g., `forEach(f: fn io (...))`) use `BIP007` §4.5 bounded call-site specialization (`BIP016-D6`).
* **Java `axiom` clarity (`BIP016-D8`):**
  * Retire `pure` on `fn`, `hermetic`, `deterministic`, `borrow`, and `impure`, while keeping explicit `pure` solely in `axiom pure on ...` / `fn axiom pure (...)` so `axiom` is never bare (`io on java.net.URL.equals` replaces `impure on ...`).

* **Pros:**
  * Cuts Argon's total effect/aliasing tokens from **439 → 41 (−90.7%!!)** and eliminates function-level keywords on **89/105 (84.8%)** of all `fn`s!
  * Aligns Babble with the best modern precedents: **Flix** (pure-by-default), **Scala 3 Capture Checking** (non-escaping default), **Swift** (`borrowing` default), and **Java Checker Framework / Koka** (pure allows exceptions & recursion).
  * **Tiny closed vocabulary (`BIP016-D7`):** just 5 everyday opt-out keywords (`io`, `volatile`, `mut`, `shared`, `unique`) + opt-in `nothrow` — **zero `throws` keyword**, **zero attribute soup**.
  * Preserves 100% expressiveness of `BIP007`'s internal lattice and `Babble.g4`'s zero-predicate LL(1)/LL(2) pure-`SLL` grammar (`IO`, `VOLATILE`, `SHARED`, replacing `HERMETIC`, `DETERMINISTIC`, `BORROW`, `IMPURE`, exempted in `memberName`).
* **Cons:**
  * **Breaking change** for existing typed Babble functions performing I/O or escaping mutable parameters (they add `io` / `shared`; migrated cleanly in a single pass while Babble is pre-1.0, per `BIP016-D9`).

---

### Option 2 — Explicit Consolidated `pure` / `hermetic` (Additive Tier Keywords)

Keep unannotated functions inferred (`BIP007` §3.1) and consolidate explicit keywords (`pure` = `{@HERMETIC, @CONST, @DETERMINISTIC}`, `hermetic` = `{@HERMETIC, @DETERMINISTIC}`, `pure volatile`, default `@BORROWED` + `shared`).

* **Pros:** Zero breaking changes for unannotated I/O scripts (`def greet = fn (s: string) -> (println(s))`); cuts Argon tokens from **439 → ~143 (−67%)**.
* **Cons:** Still forces authors to write `pure` or `hermetic` on **~92%** of every annotated function, method, and interface signature (143 tokens in Argon vs. 41 under Option 1), and keeps `pure` vs. `hermetic` vs. `impure` as extra concepts when `mut` on `this`/params already distinguishes pure from locally mutating (**superseded by `BIP016-D3`**).

---

### Option 3 — Predefined Effect Profiles & User-Defined Effect Aliases

Keep `BIP007`'s atomic keywords (`pure`, `hermetic`, `deterministic`, `nothrow`, `borrow`, `mut`) unchanged, and add **named profiles** — either built-in shorthands (e.g., `total` = `pure deterministic nothrow`, `stateful` = `hermetic deterministic nothrow`) and/or user-defined aliases (like Koka's effect aliases):

```babble
;; Built-in or user-defined profile aliases:
export effect total = pure deterministic nothrow borrow
export effect local = hermetic deterministic nothrow borrow

def dot = fn total (borrow v: Vec3): double -> ( ... )
```

* **Pros:**
  * **Zero breaking changes** to `BIP007` (`pure` keeps its exact `RM002` meaning).
  * User-defined aliases let domain libraries name recurring algebraic bundles (e.g., `effect monoid = nothrow associative`).
* **Cons:**
  * **Leaves the best keyword (`pure`) wasted on the 0%-used meaning** and does not fix parameter `borrow` noise.
  * **Dialect fragmentation & indirection:** User-defined `effect` aliases require readers to jump to alias definitions and add a new declaration/namespace kind across `Babble.g4`, `BlockScopes`, `TypedModuleSummary`, and `AnalysisSession` (**rejected in `BIP016-D7`**).

---

### Option 4 — Keep `BIP007` Keywords, Relax `BIP007-D2` (Partial Inference on Annotated Signatures)

Keep `BIP007`'s exact syntax, but change `BIP007-D2` so that writing `fn pure` on an exported function **still exports inferred `deterministic`, `nothrow`, and `borrow`** (only `impure` suppresses inference).

* **Pros:** Zero syntax changes; bodies could write `fn pure` and still get inferred properties.
* **Cons:** **Fails completely on `interface` declarations, function types, and `axiom` declarations** (none have Babble bodies to infer from!), and violates `BIP007-D2`'s API stability guarantee (callers silently depend on uncontracted properties).

---

### 5.5 Comparative Summary Matrix

| Criterion | Prior `BIP007` | Option 1 *(Implemented: Pure-by-Default `D1`–`D10`)* | Option 2 *(Explicit `pure` / `hermetic`)* | Option 3 *(Profiles / Aliases)* | Option 4 *(Relax `BIP007-D2`)* |
|---|---|---|---|---|---|
| **Argon total tokens** *(fn + param + ret)* | 439 *(4.18 / fn)* | **28** *(0.27 / fn, **−93.6%** — 1 `shared` total!)* | ~143 *(−67%)* | ~240 *(−45%, param `borrow` remains)* | ~210 *(interfaces unchanged)* |
| **Argon `fn`s with 0–1 fn keywords** | 8 / 105 *(7.6%)* | **105 / 105 *(100.0%)* — 96 (91.4%) have 0!** | 92 / 105 *(87.6%)* | 84 / 105 *(80.0%)* | 74 / 105 *(bodies only)* |
| **Works on `interface` & `axiom`?** | Verbose | **Yes (0 keywords for pure!)** | Yes (1 keyword) | Partially | **No** |
| **Needs `throws` keyword?** | No | **No** *(opt-in `nothrow`)* | No | No | No |
| **Fixes `borrow` explosion (124 tokens)?** | No | **Yes** *(`@BORROWED` default + `shared` + `D10`)* | Yes | No | Only on bodies |
| **Grammar (`Babble.g4` pure `SLL`)** | 0 predicates | **0 predicates** *(net −1 keyword!)* | 0 predicates | New `effect` decl | 0 predicates |

---

## 6. Consolidated Semantics (`BIP016-D1`–`D10`)

### 6.1 Surface Annotations as Layer 2 Mappings onto Layer 1 `@TRAITS`

Every typed Babble function, `export`, `interface` method, and function type defaults to **`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}`**, and every surface keyword cleanly flips or adds traits in Layer 1 (`EffectSet` / `Type.Parameter`):

| Surface Annotation | Internal `@TRAITS` Mapping | Per-Slot Defaults *(`BIP016-D4`/`D5`/`D10`)* | Typical Use Case |
|---|---|---|---|
| `fn (...)` *(typed / export / interface / type)* | `{@HERMETIC, @DETERMINISTIC}` *(+ `@NOTHROW`/`@TERMINATING` inferred on bodies)* | `@CONST @BORROWED` *(unless `mut`/`shared`/`unique`)* | Pure math, `record` methods, getters & return-through-borrow (`id`, `min`, `Hittable.hit`), throwing conversions (`math.toInt`), AND `mut`-param helpers (`Material.scatter`, `Vec3.random*` — **91.4%** of Argon!) |
| `fn nothrow (...)` | `{@HERMETIC, @DETERMINISTIC, @NOTHROW}` | `@CONST @BORROWED` | Strict no-exception API/interface contracts (concrete `fn (...)` bodies infer `@NOTHROW` automatically!) |
| `fn mut (...)` | `{@HERMETIC, @MUT_THIS, @DETERMINISTIC}` | `@BORROWED_THIS`, `@CONST @BORROWED(*)` | Locally mutating `this` with no globals/I/O (`Rng.nextDouble`, `HittableList.add`, `HittableList.clear`) |
| `fn shared (...)` | `{@HERMETIC, @DETERMINISTIC}` | `@CONST @SHARED_THIS`, `@CONST @BORROWED(*)` | Pure method storing `this` or capturing `this` in an escaping closure (`makeAdder`, bound-method return) |
| `fn mut shared (...)` | `{@HERMETIC, @MUT_THIS, @DETERMINISTIC}` | `@SHARED_THIS`, `@CONST @BORROWED(*)` | Locally mutating `this` that also stores/captures `this` past the call |
| `fn volatile [mut] (...)` | `{@HERMETIC}` *(clears `@DETERMINISTIC`; + `@MUT_THIS` if `mut`)* | `@CONST @BORROWED` *(unless `mut`/`shared`/`unique`)* | Side-effect-free non-deterministic readers (`Instant.now()`, `Files.readString`) or non-deterministic local mutators (`rng.seedFromClock()`) |
| `fn io [mut] [shared] (...)` | `{}` *(clears `@HERMETIC` and `@DETERMINISTIC`; + `@MUT_THIS` if `mut`)* | `@CONST @BORROWED` *(unless `mut`/`shared`/`unique`)* | I/O, global writes, API stubs (`println`, `Camera.render`), thread-spawning methods (`fn io shared` in Fable `Camera.render`), and HOFs accepting arbitrary callbacks (`forEach(f: fn io (...))`, call-site specialized per `BIP016-D6`) |
| `fn (x) -> (...)` *(completely untyped inline lambda)* | Infers `io`, `volatile`, `mut`, `shared` from body (`BIP016-D3`) | Inferred from body | Zero-boilerplate inline callbacks (`xs.forEach(fn (x) -> (println(x)))`, `count = count + 1`) |

**Function-effect subtyping (covariant):** **`fn nothrow ()`** $<:$ **`fn ()`** $<:$ **`fn volatile ()`** $<:$ **`fn io ()`**.
**Per-slot mutability & ownership subtyping (contravariant in parameters):** **`fn (x: T)`** $<:$ **`fn (mut x: T)`**, and **`fn (x: T)`** $<:$ **`fn (shared x: T)`** $<:$ **`fn (unique x: T)`**.

**Java `axiom` declarations (`BIP016-D8`)** keep explicit **`pure`** solely on `axiom` so declarations are never bare, and **`io on ...`** replaces `impure on ...`; unannotated Java methods remain conservatively unconstrained (`io`, `@SHARED`) until blessed:

```babble
;; Before (BIP007):
axiom pure deterministic nothrow on java.lang.Math.sqrt
axiom pure deterministic on java.nio.file.Path.of
axiom pure on java.nio.file.Files.readString
axiom hermetic mut deterministic idempotent commutative on java.util.HashSet.add
impure on java.net.URL.equals
def addComm = fn pure deterministic nothrow commutative associative (a: int, b: int): int -> (a + b)

;; After (BIP016-D1–D10):
axiom pure nothrow on java.lang.Math.sqrt
axiom pure on java.nio.file.Path.of
axiom volatile on java.nio.file.Files.readString
axiom mut idempotent commutative on java.util.HashSet.add
io on java.net.URL.equals
def addComm = fn commutative associative (a: int, b: int): int -> (a + b)
```

### 6.2 Escape Polarity (`BIP016-D4`), Unified Ownership Axis (`BIP016-D5`) & Return-Through Borrow (`BIP016-D10`)

Under `BIP016-D4` and `BIP016-D10`, `@BORROWED` is the unmarked default (with no user-facing `borrow` keyword): the callee may manipulate the reference during the call (and return it directly or inside a freshly returned value — see §6.2.1 below), **and the compiler statically guarantees that the callee does not store or retain the reference past the call**. Storing a `@BORROWED` reference (or any mutable sub-reference reachable from it) in a field of another object, a collection, an outer/global variable, an escaping closure, or passing it to a `shared` or `unique` parameter is a **compile-time error** enforced by `EffectChecker`. Authors write **`shared`** (`@SHARED`) only on the rare slots whose reference is genuinely retained in heap or closure state:

* **On a method:** `def makeAdder = fn shared (k: int): fn (): int -> ( fn (): int -> (this.base + k) )` or Fable's `Camera.render` (`fn io shared`, spawning worker threads capturing `this`) signals that `this` is captured/stored past the call. Pure getters and hit queries (`Hittable.hit`, `HittableList.first`) return borrowed sub-references without storing `this` and need **0 `shared` keywords** (`BIP016-D10`).
* **On a parameter:** `def add = fn mut (shared item: Hittable) -> (...)` documents that `item` is stored inside `this`'s linked list (`this.head = HittableNode(item, this.head)` stays confined to `this`, so `this` itself remains `@BORROWED`!).
* **On `mut` parameters:** `mut rng: Rng` defaults to `@MUTABLE + @BORROWED` (matching 16/16 occurrences in Argon), whereas `mut shared items: HittableList` marks a mutated parameter whose contents may also be stored elsewhere.
* **On `io` functions (`BIP016-D5`):** `io` lives strictly on the **function-effect axis** (clearing `@HERMETIC` and `@DETERMINISTIC`, and overriding built-in/axiom defaults via `io on ...`). Slot ownership (`@BORROWED` default, `shared`, `unique`) and mutability (`@CONST` default, `mut`) remain orthogonal, so `fn io (x: any?)` (like `println`) accepts `unique` locals and `frozen T` cleanly while `fn io (shared x: T)` explicitly marks retained parameters.
* **On completely untyped inline lambdas (`BIP016-D3`):** Inline lambdas with no effect modifiers, no parameter `: Type`, and no `: R` return type (e.g., `xs.forEach(fn (x) -> (println(x)))`) and functions with untyped parameters infer `io`, `volatile`, `mut`, and `shared` from their body, matching `BIP007`'s untyped-lambda rule. All named zero-parameter `def f = fn () -> (...)`, typed `fn`s, `export`s, `interface` methods, and function types enforce pure-by-default `{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}`.

#### Unified Ownership Axis (`@UNIQUE` / `@SHARED` / `@BORROWED`, plus `frozen` — `BIP016-D5`)

Because `unique` and `shared` are conceptual antonyms (writing `unique shared` for a consuming move would read like a contradiction), **`@UNIQUE` (`unique`), `@SHARED` (`shared`), and `@BORROWED` (default) unify onto a single mutually exclusive ownership axis**, orthogonal to both the function-effect axis (including `io`) and mutability (`@CONST` / `mut`), with **`frozen T`** as the immutable terminal state reached via `freeze(x)`:

* **Move semantics on `unique`:** Assigning a `unique` reference to a new binding, storing it into a `unique` container, passing it to a `unique` parameter, or returning it as `: unique T` **moves ownership to the destination and invalidates the source reference** (zero-cost identity at runtime; written explicitly with `move(x)` or inferred on a provable last use per `BIP007-D5`). **The compiler statically guarantees that the source reference is never used after the move** — any subsequent read or write of an invalidated (`@MOVED`) binding is a **compile-time error** enforced by `EffectChecker`'s definite-unassignment analysis.

```mermaid
stateDiagram-v2
    [*] --> unique : fresh alloc / pure ctor / unique return
    unique --> unique : move
    unique --> frozen : freeze(x) (consumes, irreversible)
    unique --> shared : pass to shared slot or alias (irreversible demotion)
    unique --> borrowed : pass to default slot (call-scoped loan, returns unique*)
    shared --> borrowed : pass to default slot (call-scoped loan, returns shared)
    frozen --> borrowed : pass to @CONST default slot (call-scoped loan, returns frozen)
    borrowed --> borrowed : re-borrow to nested default slot
```

*\*On `unique --> borrowed`: a `@CONST` borrow always returns `unique` to the caller intact; a `mut` borrow also stays `unique` when no `shared` value can be stored into it (e.g., `hermetic mut` with deeply immutable or `unique` arguments), and demotes to `shared` if a `shared` reference may be written into the graph.*

Complete ownership transition & compile-error matrix:

| Caller Has \ Target Slot | `unique` *(consuming)* | `shared` *(may escape)* | `@BORROWED` *(default, no escape)* | `frozen T` *(via `freeze`)* |
|---|---|---|---|---|
| **`unique`** *(sole owner)* | **Allowed (`move`)** — transfers ownership to destination and invalidates source (any later use is a compile error) | **Allowed** — one-way irreversible demotion to `shared` | **Allowed** — call-scoped loan; caller retains `unique` *(unless mutated with `shared` input)* | **Allowed** — `freeze(x)` consumes `x` (invalidating source) into deeply immutable `frozen T` |
| **`shared`** *(multi-owner)* | **Compile error** — other aliases may exist | **Allowed** — freely aliased, stored, or returned | **Allowed (call-scoped loan only)** — callee cannot hold ref past termination; caller's binding stays `shared` | **Compile error** — cannot freeze a shared mutable graph |
| **`@BORROWED`** *(frame-local loan)* | **Compile error** — callee does not own it | **Compile error** — `@BORROWED` reference MUST NOT escape | **Allowed** — re-borrow to nested `@BORROWED` callee | **Compile error** — callee does not own it |
| **`frozen T`** *(deeply immutable)* | **Compile error** — immutable forever | **Allowed only as `shared frozen T`** *(compile error to pass as unfrozen `shared T`, which would leak a mutable-typed ref!)* | **Allowed (`@CONST @BORROWED T`)** — callee cannot mutate or escape `this`/param, so `frozen T` safely lends as `@CONST @BORROWED T` (`BIP007-D7`) | **Allowed** — already `frozen T` |

**Orthogonality with mutability (`@CONST` default vs. `@MUTABLE` / `mut`):**
* **`@BORROWED` & `@SHARED`:** `@CONST` vs. `mut` (`@MUTABLE`) is **100% orthogonal** (`x: T` vs. `mut x: T`, `shared x: T` vs. `mut shared x: T`), because the caller (or other aliases) still hold the reference and can observe mutations (forbidding `pure`).
* **`@UNIQUE` (consuming `move`):** Because `move(x)` invalidates the caller's binding, the callee is the sole owner and may mutate the graph privately *even inside `pure`* (`EffectChecker.Origin.ofUniqueParam` leaves parameter-mutation bits empty when consuming), making `mut` redundant as a caller contract on consuming `unique` parameters.
* **`frozen T`:** `mut` is **forbidden** (compile error — deeply immutable forever), while every `@CONST @BORROWED T` method/function automatically accepts `frozen T` with zero extra annotations under `BIP016-D4`!

**Why `shared --> borrowed` and `frozen --> borrowed` are call-scoped loans (and must be allowed):** Neither `shared` nor `frozen T` permanently changes ownership state — the caller's binding stays `shared` or `frozen T`. However, lending either to a `@BORROWED` slot (`this.world.boundingBox()`, `item.boundingBox()`, or passing a `frozen list of int` to `fn (xs: list of int): int`) is 100% sound because `@BORROWED` constrains the *callee* (cannot retain the reference past termination — and for `frozen T`, `@CONST` ensures it cannot be mutated). This yields a clean capability order:

* **Value / argument capability order:** **`@UNIQUE`** $<:$ **`@SHARED`** $<:$ **`@BORROWED`** (and **`frozen T`** $<:$ **`@CONST @BORROWED T`** when the return type cannot leak a non-frozen alias; see below)
* **Function parameter subtyping (contravariant):** **`fn (x: T)`** $<:$ **`fn (shared x: T)`** $<:$ **`fn (unique x: T)`** *(matching `BIP007` §3.4: a callee promising not to retain `x` satisfies one that may retain `x`, which satisfies one demanding ownership of `x`)*.
* **Impact on `BIP007-P2`:** Making `{unique, shared, @BORROWED}` strictly mutually exclusive eliminates `unique shared` completely and collapses Layer 1's 3 per-slot booleans into **2 per-slot axes** (mutability + 3-state ownership), retiring explicit `unique borrow` (`@UNIQUE + @BORROWED`, 0 uses in Argon; single-target read-only and `hermetic mut` calls already preserve `unique` across `@BORROWED`).

#### Return-Through Borrow: Making `id` and `min` Work Without `shared` (`BIP016-D10`)

A naive definition of `@BORROWED` that treats **returning** a parameter the same as **storing** it creates a severe ergonomics bug — **viral `shared` propagation**:

```babble
;; If returning a parameter required `shared`:
def id of T = fn (shared x: T): T -> ( x )
def min of T = fn (shared a: T, shared b: T, cmp: fn (x: T, y: T): int): T -> (
    if cmp(a, b) < 0 then ( a ) else ( b )
)

;; Now ANY caller that merely inspects the result CANNOT borrow its own parameters!
def smallestX = fn (a: Box, b: Box): int -> (
    min(a, b, cmpBox).x   ;; ERROR if min took `shared`! Forces `shared a, shared b` up the entire call stack!
)
```

The exact same viral chain occurred in Argon: marking `Hittable.hit` as `fn shared` (solely because `HitRecord` returns `this.mat`) forced `Camera.rayColor`, `Camera.renderPixel`, and `Camera.render` to mark `world: Hittable` as `shared`, even though `rayColor` only reads `HitRecord` locally and returns a deeply immutable `Vec3`.

However, simply allowing `@BORROWED` parameters to be returned without extra rules opens **two soundness holes** in a language without Rust-style return lifetime annotations (`&'a T`):

1. **Hole 1 — Breaking `unique` / `freeze` & `mut` exclusivity (affects `id` directly):**
   ```babble
   def u = Box(1)          ;; u is @UNIQUE
   def alias = id(u)       ;; if caller ignores return-aliasing, u stays @UNIQUE
   def fu = freeze(u)      ;; succeeds because u is still thought to be @UNIQUE
   alias.x = 99            ;; UNSOUND: mutates frozen fu! (and swap(u, alias) bypasses exclusivity)
   ```
2. **Hole 2 — Breaking `frozen T <: @CONST @BORROWED T` (unfreezing via a monomorphic return):**
   ```babble
   def unwrap = fn (b: Box): Box -> ( b )
   def fb: frozen Box = freeze(Box(1))
   def m: Box = unwrap(fb) ;; UNSOUND if frozen Box <: @CONST @BORROWED Box applies unconditionally!
   m.x = 99                ;; mutates frozen fb!
   ```

Three sound designs were evaluated:
1. **Treat return as escape (`shared` required):** simplest, but breaks zero-keyword `id`/`min` and causes viral `shared` propagation on getters and `Hittable.hit`.
2. **Explicit return-provenance annotation (`: T from x`) or first-class `const T` view type:** signature-local, but adds a 4th ownership state / new type qualifier and still requires annotating `id` and `min`.
3. **Selected (`BIP016-D10`) — Separate *storing* (`shared`) from *returning* (`@BORROWED` may return) with return-origin tracking and a frozen-safe return gate:**
   * **Callee rule:** `@BORROWED` strictly forbids **retaining/storing** a reference past the call (outer/global variables, fields of other objects, escaping closures, `shared` parameters). **Returning** a `@BORROWED` parameter or `this` — directly (`id`, `min`, `HittableList.first`) or wrapped in a freshly constructed returned object (`HitRecord.create`, `BVHNode.fromList`) — is permitted with **0 `shared` keywords**. Storing `this` (or a sub-reference of `this`) back into `this`'s own fields (e.g. `this.head = HittableNode(item, this.head)`) also remains confined to `this` while still requiring `shared item`.
   * **Closing Hole 1 (`unique` / `freeze` / `mut` exclusivity):** `EffectChecker` records each concrete function's return provenance (`returnParams`, `returnThis`, `returnExternal`) on `CallableSummary` (and conservatively treats mutable non-`unique` returns of abstract interfaces/function types as potentially aliasing borrowed arguments and `this`). At any call returning a non-immutable, non-`unique` value:
     * The returned expression inherits the `Origin` (parameter bits, `this`, and direct alias keys) of the returned arguments, and demotes any `@UNIQUE` local variable that flows into it.
     * Consequently, `def alias = id(u); freeze(u)` is **rejected** (`Cannot freeze expression`), `swap(u, alias)` is **rejected** (`Exclusivity error`), `id(b).x = 99` on `@CONST b` is **rejected** (`Cannot mutate parameter b`), and `sink = id(b)` is **rejected** (`Borrowed parameter b escapes`) — while `min(a, b, cmpBox).x` (reading immutable `int`) compiles with **0 `shared`**, and `min(mut a, mut b, cmpBox).x = 99` succeeds when both caller slots are `mut`.
     * Functions returning fresh objects (`: unique R` on interfaces, or inferred `uniqueResult` on concrete bodies) guarantee no aliasing, so caller `@UNIQUE` bindings stay `@UNIQUE`.
   * **Closing Hole 2 (`frozen T <: @CONST @BORROWED T`):** Widening `frozen T` to a non-frozen `@CONST @BORROWED T` parameter (in `CallSolver` and `TypeAlgebra.functionRelates`) is enabled **only when the callee's return type cannot smuggle a non-frozen mutable alias** — i.e., when the return type is deeply immutable (`int`, `string`, `record`, `frozen ...`, stateless `object`) or `unique` (`uniqueResult`). Thus:
     * `id(fb)` and `min(fb1, fb2, cmpBox)` infer `T = frozen Box` directly (no widening needed; `cmpBox` returns `int` so `cmpBox <: fn (frozen Box, frozen Box): int` holds) and return **`frozen Box`**.
     * `unwrap(fb)`, explicit `id of Box (fb)`, and `Mapper.transform(fb)` (returning mutable non-`unique` `Box`) are all **rejected** (`Type error : expected Box, got frozen Box`).
     * `clone(fb)` (inferred `uniqueResult`) and `Cloner.clone(fb)` (`: unique Box`) **succeed**.
     * Method calls on a `frozen` receiver (`fb.self()`, `frozenWorld.hit(...)`) remain sound because `CallSolver.call` wraps their return type in `Type.frozen(result)`.

With `BIP016-D10`, `def id of T = fn (x: T): T -> ( x )` and `def min of T = fn (a: T, b: T, cmp: fn (x: T, y: T): int): T -> ( ... )` need **0 keywords**, and `shared` takes on its cleanest possible meaning: **"the callee may store/retain this reference in heap or closure state."**

### 6.3 Side-by-Side Impact on Argon (`examples/raytracer-argon/`)

```babble
;; lib/interfaces.ba — BEFORE (BIP007):
export def Describable = interface (
    def kind: fn pure deterministic nothrow borrow (): string
    def describe = fn pure deterministic nothrow borrow (): string -> (
        "<" + this.kind() + ">"
    )
)
export def Material = interface extends Describable (
    def scatter: fn hermetic deterministic nothrow borrow (borrow rIn: Ray, borrow rec: HitRecord, mut borrow rng: Rng): ScatterResult?
    def kind = fn pure deterministic nothrow borrow (): string -> ("material")
)
export def Hittable = interface extends Describable (
    def hit: fn pure deterministic nothrow (borrow r: Ray, borrow rayT: Interval): HitRecord?
    def boundingBox = fn pure deterministic nothrow borrow (): AABB -> (AABB.empty)
    def kind = fn pure deterministic nothrow borrow (): string -> ("hittable")
)

;; lib/interfaces.ba — AFTER (BIP016-D1–D10):
export def Describable = interface (
    def kind: fn (): string
    def describe = fn (): string -> (
        "<" + this.kind() + ">"
    )
)
export def Material = interface extends Describable (
    def scatter: fn (rIn: Ray, rec: HitRecord, mut rng: Rng): ScatterResult?
    def kind = fn (): string -> ("material")
)
export def Hittable = interface extends Describable (
    def hit: fn (r: Ray, rayT: Interval): HitRecord?
    def boundingBox = fn (): AABB -> (AABB.empty)
    def kind = fn (): string -> ("hittable")
)
```

Across the entire Argon codebase (verified under implemented `BIP016-D1`–`D10`):
* **Function-level tokens:** `362` → **`9`** (**−97.5%**: `5 io`, `4 mut`, **`0 shared`**!)
* **Parameter/return modifier tokens:** `77` → **`19`** (**−75.3%**: `16 mut`, **`1 shared`** on `HittableList.add(shared item: Hittable)`, `2 unique` returns)
* **Total effect/ownership tokens:** `439` → **`28`** (**−93.6%**, `4.18` → `0.27` tokens/`fn` — **15.7× reduction!**)
* **Zero-keyword functions (function-level):** `1/105` (1.0%) → **`96/105` (91.4%)**, and **`105/105` (100.0%)** have 0 or 1 function-level keyword!

### 6.4 Zero Backend, Optimizer, or Runtime Boundary Churn

Because consolidation is strictly a **Layer 2 front-end mapping** into Layer 1's existing `EffectSet` and `Type.Parameter`:

* **`org.babblelang.runtime` (`babble-runtime.jar`):** 0 lines changed (`RuntimeBoundaryTestCase` untouched).
* **`Interpreter` & `CodeGenerator`:** 0 lines changed.
* **`EffectChecker` fixed-point engine & `BIP014` optimizer passes:** Layer 1 orthogonal `@TRAITS` preserved intact (`EffectSet` still exposes all atomic traits plus `BIP016-D10` return-origin summaries).
* **Grammar (`Babble.g4`):** Stays 100% predicate-free LL(1)/LL(2) pure-`SLL` (`ParserGrammarTestCase`): add `IO`, `VOLATILE`, and `SHARED`, retire `HERMETIC`, `DETERMINISTIC`, `BORROW`, and `IMPURE` (keeping `PURE` solely for `axiom pure`), all exempted in `memberName` so `.io` / `.volatile` / `.shared` / `.pure` remain valid member names (net **−1** reserved keyword!).
* **Companion `EffectChecker` precision cleanups surfaced by Argon (`BIP016-D9`):**
  1. Treat stateless `object` singletons (zero mutable fields, like `emptyHittable`) as deeply immutable for `@DETERMINISTIC` checks — upgrading `HittableList.first`/`second` to pure default `fn ()` (with `D10`)!
  2. Avoid tainting a fresh local container (`leftList` in `BVHNode.fromList`) as mutating `@PARAM(items)` when it only *stores* non-mutated elements read from `items` — allowing `BVHNode.fromList` to drop `mut` on `items` and (with `D10`) become `fn (items: HittableList): Hittable` with 0 effect keywords!

---

## 7. Settled Decisions (`BIP016-D1`–`D10`) & Implementation Summary (`BIP016-P1`–`P4`)

### 7.1 Settled Decisions (`BIP016-Q1`–`Q7` → `BIP016-D1`–`D10`)

All design questions have been resolved into ten binding design decisions:

* **`BIP016-D1` — Two-layer architecture (Internal Orthogonal `@TRAITS` vs. Surface Opt-Out Vocabulary):** Complete `BIP007`'s `@UPPERCASE` vs. lowercase separation by modeling Babble's atomic compiler facts (4 function-level traits `@HERMETIC`, `@DETERMINISTIC`, `@NOTHROW`, `@TERMINATING`; 2 per-slot axes: mutability `@CONST` [default] / `@MUTABLE` and 3-state ownership `@BORROWED` [default] / `@SHARED` / `@UNIQUE`; plus 5 algebraic traits and `@AXIOMATIC`) as Layer 1 internal traits (`EffectSet` / `Type.Parameter`), decoupled from Layer 2's concise opt-out surface syntax.
* **`BIP016-D2` — Pure default allows exceptions & recursion (`@NOTHROW` & `@TERMINATING` inferred; opt-in `nothrow`):**
  * Default functions allow exceptions (`T | Exception`) and loops/recursion — matching Checker Framework `@Pure`, Haskell, and Koka's `pure = <div, exn>`.
  * Both **`@NOTHROW`** and **`@TERMINATING`** remain inferred automatically by `EffectChecker` for `BIP014` optimization passes and `BIP007-D8` unused-pure diagnostics; explicit **`nothrow`** stays purely additive/opt-in for APIs or interfaces requiring a strict no-exception contract. **No `throws` keyword** is added.
* **`BIP016-D3` — Effect-free by default (`@HERMETIC` & `@DETERMINISTIC` unmarked); `volatile` and `io` opt out (`Q1`, `Q2`, `Q4` resolved):**
  * All typed `fn`s, named zero-parameter `def f = fn () -> (...)`, `export`s, `interface` methods, and function types default to **`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}`** (Flix-style), retiring `pure` (on `fn`), `hermetic`, `deterministic`, `borrow`, and `impure`.
  * **`volatile`** clears `@DETERMINISTIC` only (`{@HERMETIC, !@DETERMINISTIC}` — side-effect-free non-deterministic readers like `Instant.now()` / `Files.readString`; composes with `mut` as `fn volatile mut`).
  * **`io`** clears BOTH `@HERMETIC` and `@DETERMINISTIC` (`{!@HERMETIC, !@DETERMINISTIC}` — `io` implies `volatile`, so authors write `fn io`, never `fn io volatile`, and `io` replaces `impure`).
  * **Clean 3-tier function-effect hierarchy:** `fn nothrow ()` $<:$ `fn ()` $<:$ `fn volatile ()` $<:$ `fn io ()`.
  * **Scripting ergonomics:** Top-level script statements and REPL inputs allow `io` implicitly; **completely untyped inline lambdas** and functions with untyped parameters (no effect modifiers, no parameter `: Type`, no `: R` return type — e.g., `xs.forEach(fn (x) -> (println(x)))`) continue inferring `io`, `volatile`, `mut`, and `shared` automatically (extending `BIP007`'s untyped-lambda `mut` rule).
* **`BIP016-D4` — `@BORROWED` is the unmarked default (no `borrow` keyword); `shared` denotes `@SHARED` (`!@BORROWED`):** Retire the user-facing `borrow` keyword. Receiver (`this`) and parameter slots default to `@BORROWED` (the callee may read/manipulate — and return per `BIP016-D10` — the reference during the call, and **the compiler statically guarantees the callee does not store/retain it in heap or closure state past the call — storing a `@BORROWED` reference is a compile-time error**); the inverse `@SHARED` is written explicitly with **`shared`** (`fn shared (...)`, `(shared item: Hittable)`).
* **`BIP016-D5` — Three orthogonal axes (Function Effects incl. `io`, Unified Ownership Axis, and Mutability `@CONST` / `@MUTABLE`):**
  * **Unified 3-state ownership axis (`@UNIQUE` $<:$ `@SHARED` $<:$ `@BORROWED`, plus `frozen T`):** `@UNIQUE` (`unique`, consuming `move`, invalidating source with compile-time use-after-move check), `@SHARED` (`shared`, may be stored/retained), and `@BORROWED` (default, never stored/retained) are mutually exclusive on one ownership axis (retiring `unique borrow` and eliminating `unique shared`). `frozen T` is reached irreversibly from `@UNIQUE` via `freeze(x)` and safely lends to any `@CONST @BORROWED T` slot whose return type cannot smuggle a non-frozen mutable alias (`BIP016-D10`), while rejecting `mut` and unfrozen `shared T`.
  * **Mutability axis (`@CONST` default vs. `@MUTABLE` / `mut`):** `@CONST` (read-only view through this slot, `!@MUTABLE`, distinct from deeply immutable `frozen T`) is the unmarked default, symmetric with `@BORROWED`. `@CONST` vs. `mut` is fully orthogonal on `@BORROWED` and `@SHARED`; consuming `@UNIQUE` already permits private mutation even inside default pure functions (making `mut` redundant on `unique`); `frozen T` forbids `mut`.
  * **`io` stays strictly on the function-effect axis:** `fn io (x: any?)` keeps `@CONST @BORROWED` defaults on `this` and parameters (so `println(uniqueBuf)` / `println(frozenMap)` work cleanly, while `globalCache.put` writes `fn io (shared x: T)`).
* **`BIP016-D6` — Higher-order functions via `fn io` + bounded call-site specialization (`Q3` resolved):** Higher-order functions accepting arbitrary callbacks declare `fn io (f: fn io (...))` (accepting pure, `volatile`, and `io` callbacks via subtyping), and concrete Babble HOF calls rely on `BIP007` §4.5 bounded call-site specialization to prove effect-freedom when given a pure callback — specializing only the effect contributed by `f` (never direct `io` or non-HOF `fn io` API stubs), with zero new effect-polymorphism syntax. *(Follow-up for misleading `fn io` HOF signatures and cross-module/interface effect forwarding tracked in [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md).)*
* **`BIP016-D7` — Closed built-in vocabulary (`Q5` resolved):** Keep a small, closed built-in vocabulary (`io`, `volatile`, `mut`, `shared`, `unique`, opt-in `nothrow`, plus `axiom pure` and algebraic traits) — no user-defined `effect Name = ...` aliases.
* **`BIP016-D8` — Java `axiom` syntax & `org.babblelang.effects` alignment (`Q6` resolved):**
  * Keep explicit **`pure`** solely on `axiom` declarations (`axiom pure on Path.of`, `axiom pure nothrow on Math.sqrt`, `fn axiom pure (...)`) so `axiom` is never bare, alongside `axiom volatile on ...`, `axiom mut ... on ...`, and `io on URL.equals` (replacing `impure on ...`). Unannotated Java methods remain conservatively unconstrained (`io`, `@SHARED`) until blessed.
  * In `org.babblelang.effects`, keep `Effect` aligned with Layer 1 atomic traits and update Java annotations so `@Pure` expands to `{@HERMETIC, @CONST, @DETERMINISTIC}` (with `@BORROWED` default unless `@Shared`), adding `@Volatile`, `@Shared`, and `@IO`.
* **`BIP016-D9` — Companion `EffectChecker` precision fixes & single-pass migration (`Q7` resolved):** Land both Argon-discovered `EffectChecker` precision fixes (§6.4: stateless `object` singletons preserving `@DETERMINISTIC`; storing parameter elements into a fresh local not tainting the parameter as `@MUTABLE`) alongside `BIP016`, and migrate all existing `.ba`, `examples/`, and `.batest` files in a single clean pass (as in `RM006` / `BIP012`) with no deprecated alias layer.
* **`BIP016-D10` — Return-through borrow (`id`, `min`, and getters without `shared`, §6.2.1):** Separate *storing/retaining* (`shared`) from *returning* (`@BORROWED` parameters and `this` may be returned directly or inside a freshly constructed returned object, and storing `this` back into `this`'s own fields stays confined to `this`). Close both soundness holes without adding any new keyword or lifetime syntax:
  1. `CallableSummary` tracks exact return provenance (`returnParams`, `returnThis`, `returnExternal`, `ctorStoredParams`) across concrete functions/constructors (and conservatively treats mutable non-`unique` returns of abstract interfaces/function types as potentially aliasing borrowed arguments and `this`), propagating alias origins to the caller and demoting `@UNIQUE` locals flowing into non-discarded mutable non-`unique` returns (preserving `freeze`, `unique`, and `mut` exclusivity soundness).
  2. `CallSolver` and `TypeAlgebra.functionRelates` gate `frozen T <: @CONST @BORROWED T` widening on `uniqueResult || isDeeplyImmutable(result)` so monomorphic return-through-borrow functions cannot silently unfreeze a `frozen` argument (while generic `id(fb)` / `min(fb1, fb2, cmp)` infer `T = frozen Box` directly and return `frozen Box`).

### 7.2 Implementation Status (`BIP016-P1`–`P4` — Completed)

* **`BIP016-P1` — Grammar, Formatter & Java Host API (`Babble.g4`, `Formatter`, `org.babblelang.effects`):** **Implemented.** Updated `Babble.g4` (`IO`, `VOLATILE`, `SHARED` added; `HERMETIC`, `DETERMINISTIC`, `BORROW`, `IMPURE` retired; `PURE` restricted to `axiom`; all tokens in `memberName`, 0 predicates, pure `PredictionMode.SLL`), `org.babblelang.effects` (`Effect`, `@Pure`, `@Volatile`, `@Shared`, `@IO`), `BabbleScriptEngine.markIO`, and `BytecodeEffectAnalyzer.suggestAxiom`.
* **`BIP016-P2` — `EffectChecker`, `Type.Parameter`, `EffectSet`, `CallSolver` & `TypeAlgebra`:** **Implemented.** Pure-by-default (`{@HERMETIC, @DETERMINISTIC, @CONST, @BORROWED}`), completely untyped inline-lambda inference (`BIP016-D3`), unified 3-state ownership axis (`BIP016-D4`/`D5`), bounded `fn io` HOF specialization (`BIP016-D6`), both Argon precision fixes (`BIP016-D9`), and return-through-borrow provenance + frozen-safe return gate (`BIP016-D10`).
* **`BIP016-P3` — Single-Pass Codebase & Test Migration:** **Implemented.** Migrated `examples/raytracer-argon/` (`439 → 28` tokens, **−93.6%**, only **1** `shared` token), `examples/raytracer-fable/`, `src/test/babble/`, all Java JUnit suites, and all 68 `.batest` suites (`940` cases), including comprehensive tests for `BIP016-D1`–`D10` (`EffectsTestCase`, `OwnershipTestCase`, `effects/*.batest`).
* **`BIP016-P4` — Dual-Mode Verification & Docs Alignment:** **Verified.** Full dual-mode `mvn -B test` (`1,658` tests, 0 failures, 0 errors across `interpret` + `compile` with `babble.verify=true`), `ParserGrammarTestCase` (pure `SLL`), `RuntimeBoundaryTestCase` (0 lines changed in `org.babblelang.runtime`), `-format -check` clean across `src/test/babble` and `examples/`, and byte-for-byte identical `examples/raytracer-argon/scene.ppm` (`1,021,340` bytes via `babblec` AOT jar); aligned `docs/design/bip007_effects.md`, `examples/raytracer-argon/README.md`, and `docs/design/README.md`.

---

## 8. Bibliography

### Languages, Compilers & Standards

- **`[D-Func]`** / **`[D-Style]`** D Language Specification & Style Guide. *[Functions — Function Attributes](https://dlang.org/spec/function.html)*, *[Attributes](https://dlang.org/spec/attribute.html)*, and *[The D Style — Attributes](https://dlang.org/dstyle.html)*.
  - **Summary:** Specifies D's additive function attributes (`pure`, `nothrow`, `@safe`, `@nogc`, `const`, `scope`) and documents the official style convention of annotating non-templated functions with `const @nogc nothrow pure @safe`, relying on template inference internally.
  - **Contribution to `BIP016`:** Primary cautionary case study of additive "attribute soup" at API/interface boundaries (§1, §4).

- **`[CF-Pure]`** / **`[CF-SEF]`** / **`[CF-Man]`** The Checker Framework (University of Washington). *[`@Pure` API](https://checkerframework.org/api/org/checkerframework/dataflow/qual/Pure.html)*, *[`@SideEffectFree` API](https://checkerframework.org/api/org/checkerframework/dataflow/qual/SideEffectFree.html)*, and *[The Checker Framework Manual](https://checkerframework.org/manual/)*.
  - **Summary:** Separates side-effect-freedom (`@SideEffectFree`) from determinism (`@Deterministic`), and defines **`@Pure`** explicitly as their conjunction (`@SideEffectFree` + `@Deterministic`).
  - **Contribution to `BIP016`:** Directly motivates `BIP016-D2` (`pure` = `@HERMETIC` + `@CONST` + `@DETERMINISTIC`, allowing exceptions) and `BIP016-D3` (`volatile` for non-deterministic side-effect-free readers) (§1, §3.2, §4, §6.1).

- **`[PG-Vol]`** PostgreSQL Global Development Group. *[PostgreSQL Documentation: Function Volatility Categories (`IMMUTABLE`, `STABLE`, `VOLATILE`)](https://www.postgresql.org/docs/current/xfunc-volatility.html)* and *[`CREATE FUNCTION`](https://www.postgresql.org/docs/current/sql-createfunction.html)*.
  - **Summary:** Classifies every SQL function into one of three stratified volatility tiers (`IMMUTABLE`, `STABLE`, `VOLATILE`) driving query-planner constant folding, index scans, and caching.
  - **Contribution to `BIP016`:** Demonstrates how a compact 1-word stratified hierarchy captures the common optimization contracts without multi-keyword stacks (§4, §5 Option 2).

- **`[GCC-Attr]`** / **`[C23-N2956]`** GNU Compiler Collection & ISO/IEC WG14 (Jens Gustedt). *[GCC Common Function Attributes (`const`, `pure`)](https://gcc.gnu.org/onlinedocs/gcc/Common-Attributes.html)* and *[WG14 N2956: Unsequenced functions (`[[reproducible]]`, `[[unsequenced]]` in C23)](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n2956.htm)*, April 2022.
  - **Summary:** Contrasts GCC's `const` vs. `pure` with C23's standardized `[[reproducible]]` and `[[unsequenced]]` attributes; N2956 explicitly documents how WG14 rejected exposing every underlying property (`effectless`, `idempotent`, `stateless`, `independent`) as separate surface attributes in favor of two bundled profiles, and discusses the historical ambiguity of the word `pure`.
  - **Contribution to `BIP016`:** Concrete industrial precedent for bundling fine-grained internal compiler properties into coarse surface profiles (§4, §5).

- **`[Koka-Book]`** / **`[Lei14]`** Daan Leijen (Microsoft Research). *[The Koka Programming Language — Effect Types](https://koka-lang.github.io/koka/doc/book.html)*, *[`std/core/types` Effect Aliases](https://koka-lang.github.io/koka/doc/std_core_types.html)*, and *"[Koka: Programming with Row Polymorphic Effect Types](https://arxiv.org/abs/1406.2061)"* (MSFP 2014 / EPTCS, [PDF](https://arxiv.org/pdf/1406.2061)).
  - **Summary:** Tracks effects (`exn`, `div`, `ndet`, `read`, `write`, `alloc`, `io`) via row polymorphism while giving programmers concise named aliases (`total` = empty row, `pure` = `<div, exn>`, `io` = all I/O effects) and defaulting unmarked functions to `total`.
  - **Contribution to `BIP016`:** Reference model for named effect profiles/aliases over a fine-grained internal effect algebra, and precedent for `pure` allowing exceptions and divergence (`BIP016-D2`) (§3.2, §4, §5 Option 3).

- **`[Flix-Doc]`** / **`[MP20]`** Magnus Madsen and Jaco van de Pol (Aarhus University). *[Programming Flix — Effect System](https://doc.flix.dev/effect-system.html)* and *"[Polymorphic Types and Effects with Boolean Unification](https://cs.au.dk/~magnusm/papers/oopsla20/paper_b.pdf)"*, **OOPSLA 2020** (PACMPL 4).
  - **Summary:** Separates `Pure` (default, unmarked) from `IO` and user effects using Hindley-Milner extended with Boolean unification (`~1.1x` compilation overhead), allowing purity-driven compiler optimizations without per-function boilerplate.
  - **Contribution to `BIP016`:** Demonstrates the ergonomic payoff of pure-by-default polarity and strong mathematical `Pure` semantics (§3.2, §4).

- **`[Nim-Eff]`** Nim Programming Language. *[Nim Manual — Effect System](https://nim-lang.org/docs/manual.html)* and *[Effect System Documentation (`noSideEffect`, `func`, `raises`)](https://nim-docs.readthedocs.io/en/latest/manual/effect_system/)*.
  - **Summary:** Tracks side effects (`{.noSideEffect.}`) and exceptions (`{.raises: [].}`) with inference, and introduces the `func` keyword as syntactic shorthand for `proc ... {.noSideEffect.}`.
  - **Contribution to `BIP016`:** Illustrates how real-world imperative languages adopt concise single-keyword shorthands when effect pragmas become pervasive (§4).

- **`[SE-0377]`** / **`[Swift-Mut]`** Swift Evolution. *[SE-0377: `borrowing` and `consuming` parameter ownership modifiers](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0377-parameter-ownership-modifiers.md)* and *[Swift Forums: Borrowing `self` by default in Swift 6](https://forums.swift.org/t/pitch-dont-copy-self-when-calling-non-mutating-methods/61707)*.
  - **Summary:** Documents Swift's ownership polarity (`borrowing` as the default for non-mutating parameters and `self`, with `consuming` / `mutating` / `throws` written only when opting out of the safe baseline).
  - **Contribution to `BIP016`:** Directly informs `BIP016-D4`'s ownership polarity (default-`@BORROWED` with `shared` for `!@BORROWED`) (§4, §5 Option 1, §6.2).

- **`[SPARK-Sub]`** AdaCore. *[SPARK 2014 Reference Manual — Subprograms & Global Aspects](https://docs.adacore.com/spark2014-docs/html/lrm/subprograms.html)* and *[SPARK User's Guide](https://docs.adacore.com/spark2014-docs/html/ug/)*.
  - **Summary:** Enforces side-effect freedom on SPARK functions by default (forbidding `Output`/`In_Out` global state unless explicitly annotated with `Side_Effects`), scaling static verification to safety-critical codebases.
  - **Contribution to `BIP016`:** High-assurance precedent for side-effect-free defaults with explicit effect opt-in (§4).

- **`[Rust-Ref]`** Rust Language. *[Rust `const fn` & Ownership/Borrowing Model](https://github.com/rust-lang/const-eval/issues/1)* and *[Rust Ownership, Borrowing, and Aliasing XOR Mutability](https://dev.to/amritsingh183/a-complete-guide-to-rust-ownership-lifetimes-and-memory-management-4j2a)*.
  - **Summary:** Uses concise borrowing syntax (`&self`, `&mut self`, `&T`), immutable-by-default bindings, and return-type error handling (`Result`) instead of multi-keyword function attribute chains.
  - **Contribution to `BIP016`:** Contrasts Rust's lightweight borrowing ergonomics against Babble's current 124 explicit `borrow` keywords in Argon (§4).

### Academic Foundations on Effect Systems, Purity & Capabilities

- **`[LG88]`** John M. Lucassen and David K. Gifford. *"Polymorphic Effect Systems."* In **POPL '88**, pp. 47–57 (overview at [PLS Lab: Effect Systems](https://www.pls-lab.org/effect-system)).
  - **Summary:** Foundational paper introducing type-and-effect systems, effect masking (hiding local allocation/mutation inside pure computations), and effect polymorphism.
  - **Contribution to `BIP016`:** Theoretical basis for keeping `BIP007`'s underlying effect lattice and local `unique`-mutation masking while streamlining surface syntax (§3.1).

- **`[SR05]`** / **`[Pea11]`** Alexandru Sălcianu and Martin C. Rinard. *"Purity and Side Effect Analysis for Java Programs"*, **VMCAI 2005** ([abstract interpretation follow-up PDF](https://www.microsoft.com/en-us/research/wp-content/uploads/2011/09/purityAbstInt.pdf)); and David J. Pearce, *[JPure: A Modular Purity System for Java](https://whileydave.com/projects/jpure/)*, **CC 2011**.
  - **Summary:** Formalize modular purity checking and inference for object-oriented languages using freshness (newly allocated local objects, cf. Babble's `unique`) and locality (non-escaping parameters, cf. Babble's `@BORROWED`).
  - **Contribution to `BIP016`:** Grounds the relationship between purity, freshness, and parameter locality/borrowing in OO languages (§3.2, §6.2).

- **`[ROH12]`** Lukas Rytz, Martin Odersky, and Philipp Haller. *"[Lightweight Polymorphic Effects](https://infoscience.epfl.ch/entities/publication/553acaa9-4b8a-497a-8c78-96c1a80d9f65)."* In **ECOOP 2012**, LNCS 7313, pp. 258–282.
  - **Summary:** Argues that **syntactic verbosity is the primary obstacle preventing industrial adoption of effect systems**, and introduces lightweight default-based effect annotations and polymorphism across multiple effect domains in Scala.
  - **Contribution to `BIP016`:** Directly validates the motivation of `BIP016`: even a sound, expressive effect system fails in practice if everyday signatures are verbose (§1, §4).

- **`[GPP12]`** / **`[CDB15]`** Colin S. Gordon, Matthew J. Parkinson, Jared Parsons, Aleks Bromfield, and Joe Duffy. *"[Uniqueness and Reference Immutability for Safe Parallelism](https://www.cs.drexel.edu/~csg63/papers/oopsla12.pdf)"*, **OOPSLA 2012**; and Sylvan Clebsch, Sophia Drossopoulou, Sebastian Blessing, and Andy McNeil, *"Deny Capabilities for Safe, Fast Actors"* (**AGERE! 2015**, [Pony publications](https://wp.doc.ic.ac.uk/sd/publications/) & [reference capabilities overview](https://tamnd.github.io/brain/research/memory-management/ownership/06_pony_refcaps/)).
  - **Summary:** Establish the `unique`/`isolated` $\to$ `immutable`/`frozen` zero-copy permission lattice (`[GPP12]`) and Pony's 6-capability matrix (`[CDB15]`) — along with the usability lesson that pervasive per-reference capability qualifiers carry high annotation cost.
  - **Contribution to `BIP016`:** Contextualizes why Babble keeps `unique`/`frozen` minimal (`BIP007`) and retires explicit `borrow` via default-`@BORROWED` (`BIP016-D4`) (§3, §6.2).

- **`[BGO23]`** Aleksander Boruch-Gruszecki, Martin Odersky, Edward Lee, Ondřej Lhoták, and Jonathan Brachthäuser. *"[Capturing Types](https://abgru.me/publication/capturing-types/)."* **ACM TOPLAS** 45(4), 2023 (and [EPFL LAMP Capture Checking overview](https://chara.epfl.ch/~odersky/)).
  - **Summary:** Develops capture checking for Scala 3 where pure/non-escaping references are the concise unmarked default and capturing/escaping values are marked explicitly (`^`).
  - **Contribution to `BIP016`:** Modern blueprint for `BIP016-D4`'s non-escaping-by-default polarity (`shared` instead of 124 `borrow` keywords) (§4, §6.2).

- **`[Tae25]`** *"Type, Ability, and Effect Systems: Perspectives on Purity, Semantics, and Expressiveness."* **[arXiv:2510.07582](https://arxiv.org/html/2510.07582v1)**, October 2025.
  - **Summary:** Establishes a typing-independent semantic definition of purity — mathematical functions that terminate, produce deterministic results, and exhibit no observable side effects — and compares effect systems vs. capability systems on completeness and usability.
  - **Contribution to `BIP016`:** Reinforces the central thesis of `BIP016`: `pure` should denote strong mathematical purity (deterministic, non-throwing, side-effect-free), not merely absence of writes (§1, §3.2).
