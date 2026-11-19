# BIP016: Consolidating Effect Annotations

| | |
|---|---|
| **BIP** | 016 |
| **Date** | 2026-11-19 |
| **Status** | Draft (Empirical Analysis & Design Proposal; Open Questions `BIP016-Q1`–`Q7`) |
| **Depends on** | Function effects and algebraic properties (`RM002` / `RM007`, [bip007_effects.md](bip007_effects.md)), core scalar & collection types (`RM005`, [bip011_core-types.md](bip011_core-types.md)), predicate-free LL(1)/LL(2) grammar (`RM006`, [bip012_grammar-improvements.md](bip012_grammar-improvements.md)), compiler optimization passes ([bip014_optimization-passes.md](bip014_optimization-passes.md)) |
| **Scope** | Surface ergonomics of Babble's effect and borrowing annotations: empirical audit of `examples/raytracer-argon/`, separating **internal orthogonal compiler traits** (`@UPPERCASE`, e.g. `@NO_GLOBAL_IO`, `@MUTABLE`, `@DETERMINISTIC`, `@NOTHROW`, `@NON_ESCAPING`, `@TERMINATING`) from **surface annotations defined as collections (profiles) of traits** so `pure` sits at the top of the hierarchy (`pure` implying at least `@DETERMINISTIC` + `@NOTHROW`), evaluating `borrow` polarity, and aligning with `EffectSet`, `Babble.g4` (`SLL`), `org.babblelang.effects`, and `BIP014` |

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

1. **1-to-1 coupling between internal compiler traits and surface keywords — with `pure` at the bottom instead of the top.** `BIP007` exposed almost every orthogonal internal bit as a separate opt-in keyword, and defined `pure` to mean *no writes only* (matching the Java Checker Framework's `@SideEffectFree`, not its `@Pure`). Consequently, `pure` alone was used **0 times** in Argon, while `pure deterministic nothrow` accounted for **70.5%** of all functions. Likewise, `hermetic` alone was used **0 times**, while `hermetic deterministic nothrow` accounted for **18.1%**.
2. **`borrow` has inverted polarity on annotated signatures.** In Argon, **88.9%** of non-`impure` instance methods do not escape `this` and **89.6%** of non-`impure` reference parameters do not escape. Yet because `BIP007-D2` locks explicitly annotated exports and interfaces strictly to their declared modifiers, writing `fn pure ...` drops inferred `borrow` on `this` and every parameter unless the author manually repeats `borrow` everywhere — even on deeply immutable `record` parameters (`Vec3`, `Ray`, `Interval`, `AABB`) and `mut` RNG parameters (`mut borrow rng: Rng`).

By cleanly decoupling **internal orthogonal compiler traits** (`@NO_GLOBAL_IO`, `@MUTABLE`, `@DETERMINISTIC`, `@NOTHROW`, `@NON_ESCAPING`, `@TERMINATING`, …) from **surface annotations defined as collections of traits** — with **`pure` topmost** and non-escaping **`@NON_ESCAPING` (`borrow`) as the default** inside effect-annotated contracts — the two functions above collapse to:

```babble
;; Proposed under BIP016:
def dot = fn pure (v: Vec3): double -> (
    this.x * v.x + this.y * v.y + this.z * v.z
)

def scatter = fn hermetic (rIn: Ray, rec: HitRecord, mut rng: Rng): ScatterResult? -> (
    ...
)
```

This proposal documents the complete Argon empirical data (§2), re-evaluates `BIP007` through the two-layer trait/annotation model (§3), surveys prior art across ten languages and academic effect systems (§4), compares four concrete consolidation designs (§5), details a recommended synthesis and trait-desugaring table (§6), and lists open decisions `BIP016-Q1`–`Q7` (§7) backed by a detailed bibliography (§8).

---

## 2. Empirical Audit of `examples/raytracer-argon/`

Every `fn` expression and function type across all 14 files of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) (`raytracer.ba` and `lib/*.ba`) was annotated with the strongest compiler-verified `BIP007` contract (zero `axiom` declarations, verified by `ExamplesTestCase`, `FormatterTestCase`, full dual-mode `mvn test`, and byte-for-byte `scene.ppm` rendering parity).

### 2.1 Function-Level Annotation Frequencies & Consolidation Table

The table below lists all **105 `fn` occurrences** in Argon, **sorted by decreasing frequency**, alongside their exact `BIP007` semantics and the proposed consolidated annotation under the recommended Option 1 design (§5.1, §6 — where `pure` and `hermetic` expand to trait collections including `@DETERMINISTIC` + `@NOTHROW`, and `@NON_ESCAPING` is the default on explicitly annotated signatures with `escape` clearing it):

| Rank | Count (%) | Current `BIP007` Combination | Semantics & Argon Examples | Proposed New Annotation *(Option 1)* |
|---:|---:|---|---|---|
| **1** | **49** *(46.7%)* | `pure deterministic nothrow borrow` | Strictly side-effect-free, deterministic, exception-free instance method whose `this` does not escape (`Vec3.*`, `Ray.at`, `Interval.*`, `AABB.*`, `Describable.*`, `Material.kind`, `Hittable.boundingBox`/`kind`, `emptyHittable.*`, `Sphere.boundingBox`/`kind`/`describe`, `HittableList.boundingBox`/`countWhere`/`kind`, `BVHNode.boundingBox`/`kind`, `Color.linearToGamma`, `Scenes.threeSpheres`) | **`pure`** *(1 keyword; was 4)* |
| **2** | **25** *(23.8%)* | `pure deterministic nothrow` | **20** free/static functions & callbacks with no `this` (`lib/math.ba` ×13, `Interval.enclose`, `AABB.fromPoints`/`enclose`, `HitRecord.create`, `Dielectric.reflectance`, `countWhere` predicate type & lambda); **5** instance methods whose `this` (or sub-reference) escapes into the return value (`Hittable.hit`, `Sphere.hit`, `HittableList.hit`, `BVHNode.hit`, `HittableList.headNode`) | **`pure`** *(free/static, 20×)* · **`pure escape`** *(escaping `this`, 5×)* |
| **3** | **10** *(9.5%)* | `hermetic deterministic nothrow borrow` | No globals/I/O, deterministic, exception-free method whose `this` is read-only & non-escaping while mutating a `mut` parameter (`Material.scatter`, `Lambertian`/`Metal`/`Dielectric.scatter`, `Camera.sampleSquare`/`defocusDiskSample`/`getRay`/`rayColor`/`renderPixel`, `Scenes.finalScene`) | **`hermetic`** *(1 keyword; was 4)* |
| **4** | **5** *(4.8%)* | `hermetic deterministic nothrow` | Free/static functions (no `this`) with no globals/I/O, deterministic, exception-free, mutating a `mut` parameter (`Vec3.random`, `randomRange`, `randomUnitVector`, `randomOnHemisphere`, `randomInUnitDisk`) | **`hermetic`** *(1 keyword; was 3)* |
| **5** | **3** *(2.9%)* | `hermetic mut borrow deterministic nothrow` | No globals/I/O, deterministic, exception-free method mutating `this` without escaping `this` (`Rng.nextDouble`, `Rng.nextRange`, `HittableList.clear`) | **`hermetic mut`** *(2 keywords; was 5 — or `mutating` profile: 1)* |
| **6** | **3** *(2.9%)* | `impure` | Unconstrained I/O or test harness entry points (`Camera.render`, `runTests`, `renderMain`) | **`impure`** *(unchanged)* |
| **7** | **2** *(1.9%)* | `pure nothrow` | Side-effect-free and exception-free, but reads an outer non-`record` object singleton (`emptyHittable`), so not `deterministic` (`HittableList.first`, `HittableList.second`, both escaping `this`) | **`readonly escape`** *(or `pure volatile escape`)* |
| **8** | **2** *(1.9%)* | `pure deterministic` | Side-effect-free and deterministic free function that may throw at runtime via narrowing `int(x)` (`math.toByte`, `math.toInt`) | **`pure throws`** *(2 keywords; was 2, clearer intent)* |
| **9** | **2** *(1.9%)* | `nothrow` | Exception-free but unconstrained on purity/determinism (`BVHNode.fromList` reading `emptyHittable` & mutating local partition lists; frame-local accumulator closure in `HittableList.countWhere`) | **`nothrow`** *(unchanged)* |
| **10** | **1** *(1.0%)* | `pure deterministic borrow` | Side-effect-free, deterministic, non-escaping `this` method that may throw via `math.toByte` (`Color.formatPpm`) | **`pure throws`** *(2 keywords; was 3)* |
| **11** | **1** *(1.0%)* | `hermetic mut deterministic nothrow` | No globals/I/O, deterministic, exception-free method mutating `this` where `this.head` escapes into a newly allocated `HittableNode` (`HittableList.add`) | **`hermetic mut escape`** *(3 keywords; was 4)* |
| **12** | **1** *(1.0%)* | `borrow` | Higher-order method polymorphic in callback `f`'s effects whose `this` does not escape (`HittableList.forEach`) | **`borrow`** *(or omitted if default)* |
| **13** | **1** *(1.0%)* | *(none)* | Unconstrained higher-order callback type `fn (borrow item: Hittable): any?` inside `HittableList.forEach` | *(none)* |
| **Total** | **105** *(100%)* | **362 function-prefix tokens** *(3.45 / fn)* | **0** uses of standalone `pure`; **0** uses of standalone `hermetic` | **~120 function-prefix tokens** *(1.14 / fn, −67%)* |

### 2.2 Parameter & Return Modifier Frequencies

Sorted by decreasing frequency across all parameter lists and return clauses in `examples/raytracer-argon/`:

| Scope | Count | Current `BIP007` Modifier | Breakdown in Argon | Proposed New Annotation *(Option 1)* |
|---|---:|---|---|---|
| Parameter | **44** | `borrow` | **34** deeply immutable `record`s (`Vec3` ×12, `Ray` ×12, `Interval` ×8, `AABB` ×2), **4** `HitRecord`, **4** `Hittable`, **2** `fn` callbacks | *(omitted — default in annotated fns)* |
| Parameter | **16** | `mut borrow` | **16** `Rng` parameters (`Vec3.random*`, `Material.scatter`, `Camera.*`, `Scenes.finalScene`) | **`mut`** *(1 keyword; was 2)* |
| Parameter | **7** *(+1 in `impure`)* | *(none, ref param escaping)* | `p`, `outwardNormal`, `mat` in `HitRecord.create`; `item` in `HittableList.add`; `world` in `Camera.rayColor`/`renderPixel` (plus `Camera.render`); `mut items` in `BVHNode.fromList` | **`escape`** *(explicit on the 7 escaping ref params)* |
| Parameter | **1** | `mut` | `mut items: HittableList` in `BVHNode.fromList` (stored into local partition lists) | **`mut escape`** |
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

1. **88.6% of all functions are just two contracts:** `pure deterministic nothrow` (**74/105 = 70.5%**) and `hermetic deterministic nothrow` (**19/105 = 18.1%**, including 4 with `mut` `this`). Every one of those 93 functions currently requires **3 to 5 keywords**.
2. **Standalone `pure` and standalone `hermetic` are dead weight in normal code:** Neither appeared even **once** (`0/105`) in Argon. The only real-world cases for *non-deterministic, side-effect-free* code are environment/clock/file reads (`Instant.now()`, `Files.readString`, reading a mutable outer singleton) — an uncommon, explicit boundary (~2% in Argon), not the default meaning programmers expect from the word `pure`.
3. **Non-escaping (`borrow`) is the ~89% norm for both `this` and parameters:** Requiring explicit `borrow` produced **124 `borrow` tokens** across 105 functions to protect against only **8** escaping receivers and **7** escaping reference parameters. Worse, `100%` of `record` methods (`26/26`) and `100%` of `mut` `Rng` parameters (`16/16`) had to repeat `borrow`.
4. **Algebraic properties (`associative`, `commutative`, `idempotent`, `monotone`, `injective`) are NOT the noise source:** They appeared **0 times** in Argon (they belong on math/reduction/collection library APIs and `axiom`s). Consolidating the core effect & borrowing axes fixes the everyday ergonomics problem without touching the algebraic vocabulary.

---

## 3. Reconsidering `BIP007`: Internal Orthogonal `@TRAITS` vs. Surface Annotations

### 3.1 Decoupling the Two Layers (`@TRAITS` vs. Surface Collections)

`BIP007` §3 already introduced a clean notational convention: **lowercase** for surface modifiers written by programmers (`pure`, `mut`, `borrow`, …) and **`@UPPERCASE`** for internal compiler states and inferred-only guarantees (`@PARAM(i)`, `@THIS`, `@FROZEN`, `@MOVED`, `@EXTERNAL`, `@TERMINATING`, `@TOTAL`, `@AXIOMATIC`).

The core architectural insight of `BIP016` is to **complete that separation** into two distinct layers:

1. **Layer 1 — Internal Orthogonal Compiler Traits (`@UPPERCASE`):**
   Every atomic semantic fact tracked by `EffectChecker`, stored in `EffectSet` / `Type.Parameter` / `TypedModuleSummary`, checked by subtyping (`TypeAlgebra`), and consumed by `BIP014` optimizers is an **orthogonal internal trait** — never tied 1-to-1 to a surface keyword:

| Trait Axis | Internal Orthogonal Traits (`@UPPERCASE`) | Consumed By |
|---|---|---|
| **Memory & Mutation** | `@NO_GLOBAL_IO` *(hermetic: no I/O or global/outer writes)*, `@NO_MUT_SLOTS` *(no mutated `this` or params)*, per-slot `@MUTABLE` (`@MUT_THIS`, `@MUT(i)`) | `EffectChecker` confinement, `BIP014` `O5`/`O6`/`O7` |
| **Evaluation & Safety** | `@DETERMINISTIC` *(depends only on visible inputs)*, `@NOTHROW` *(no runtime exceptions)*, `@TERMINATING` *(finite execution; `@NOTHROW + @TERMINATING` = `@TOTAL`)* | `BIP014` `O1` folding, `O4` DCE, `O5` CSE, `O6` LICM, `O11` reordering |
| **Escape & Ownership** | Per-slot `@NON_ESCAPING` (`@BORROW_THIS`, `@BORROW(i)`), `@UNIQUE(i)`, `@UNIQUE_RESULT`, `@FROZEN`, `@MOVED` | `RM005` covariance, `BIP007-D7` `frozen` calls, `BIP014` `O7`/`O9`/`O10` |
| **Algebraic Properties** | `@IDEMPOTENT`, `@COMMUTATIVE`, `@ASSOCIATIVE`, `@MONOTONE`, `@INJECTIVE` | `BIP014` `O2`/`O3`/`O12` algebraic & pipeline rewrites, `AxiomChecker` |
| **Provenance & Lock** | `@AXIOMATIC` *(trusted `axiom` boundary)*, `@IMPURE_LOCK` *(suppresses inference)* | Sandbox policy (`allowAxioms(false)`), `BIP007-D2` export rules |

2. **Layer 2 — Surface Annotations as Collections (Profiles) of Traits:**
   Surface keywords written by Babble programmers are **named trait collections** (plus optional trait-clearing modifiers such as `throws` or `escape`) that desugar directly into Layer 1's `@TRAITS` during `EffectChecker.parsePrefix` / `parseParamMods`:
   * Adding a new internal compiler trait (like `@TERMINATING` in `RM007`, or future `@THREADSAFE`) requires **zero** change to existing surface annotations unless we intentionally expose it.
   * Consolidating or evolving surface annotations (making `pure` topmost, bundling `@DETERMINISTIC` + `@NOTHROW`, defaulting `@NON_ESCAPING`) requires **zero** change to the fixed-point inference engine, `TypeAlgebra` subtyping, `LoweringAnalyzer`, `BIP014` optimizer passes, or `babble-runtime` (`RuntimeBoundaryTestCase`).
   * Compiler diagnostics can name the exact violated trait while recommending the concise surface annotation (e.g., `Function toInt declared pure violates @NOTHROW (int(x) may throw) : use pure throws`), and `EffectSet.toString()` / `:type` display can render the shortest canonical surface form covering the trait set.

### 3.2 What `BIP007` Got Right vs. Where Its Surface Syntax Overshot

`BIP007`'s Layer 1 model is sound and stays intact: separating write confinement, determinism, exception-freedom, escape-freedom, exclusivity, deep ownership, and termination is strictly necessary for `BIP014`, and rejecting D-style weak purity (`@NO_MUT_SLOTS` mutually exclusive with `@MUTABLE`) keeps `pure` unambiguous.

Where `BIP007` overshot was coupling Layer 2 **1-to-1** to Layer 1:

1. **Semantic mismatch on `pure`:** In functional languages (`Flix`, `Haskell`), static analyzers (`Checker Framework @Pure`), and query optimizers (`PostgreSQL IMMUTABLE`), **`pure` means a referentially transparent mathematical function** — `{@NO_GLOBAL_IO, @NO_MUT_SLOTS, @DETERMINISTIC, @NOTHROW}` (`[Tae25]`, `[CF-Pure]`). `BIP007` instead mapped `pure` to just `{@NO_GLOBAL_IO, @NO_MUT_SLOTS}` (Checker Framework's `@SideEffectFree`), forcing programmers to spell `pure deterministic nothrow` every time they meant mathematical purity.
2. **Same under-bundling on `hermetic`:** `hermetic` is `pure`'s counterpart when a parameter or `this` has `@MUTABLE` (e.g., `Rng.nextDouble`, `Material.scatter`, `HittableList.add`). Mapping `hermetic` to `{@NO_GLOBAL_IO}` alone turned every locally-mutating pure-Babble function into `hermetic deterministic nothrow` (or `hermetic mut borrow deterministic nothrow` — **five** keywords!).
3. **Contract-closure friction on `@NON_ESCAPING` (`borrow`, `BIP007-D2`):** For unannotated functions, `EffectChecker` optimistically infers `@NON_ESCAPING` on `this` and all parameters. Once an author writes *any* explicit effect annotation on an exported function or interface method, `BIP007-D2` exposes strictly the declared traits — silently dropping `@NON_ESCAPING` unless `borrow` is repeated on `this` and every single parameter. Even worse:
   * Calling a method on a `frozen T` receiver requires `@BORROW_THIS` (`BIP007-D7`), so forgetting `borrow` on `Vec3.dot` breaks `frozen Vec3` callers.
   * Scalar replacement (`BIP014` `O9`) requires `@BORROW(i)` on `record` parameters (`Vec3`, `Ray`, `Interval`), so omitting `borrow` silently degrades AOT optimization across module boundaries.
   * Yet `@NON_ESCAPING` holds on **88.9%** of receivers and **89.6%** of reference parameters in Argon!

*(Side note on `BIP007-P2` precision surfaced by Argon:)* In `HittableList.first`/`second`, reading the stateless `object implements Hittable` singleton `emptyHittable` cleared `@DETERMINISTIC` because `EffectChecker` treats any non-`record` `object` singleton as potentially mutable outer state even when it defines zero fields. And in `BVHNode.fromList(items: HittableList)`, storing `cur.item` (derived from `items`) into a fresh local `leftList` demoted `leftList` with `@PARAM(items)` taint, forcing `@MUT(items)` (`mut items`) even though `items` itself is never mutated. Both are small `EffectChecker` precision improvements worth tracking independently of surface syntax (see `BIP016-Q7` / §6.4).

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

All four options below **keep `BIP007`'s internal `EffectSet` bitmask (`PURE`, `HERMETIC`, `DETERMINISTIC`, `NOTHROW`, algebraic flags, `mutThis`/`borrowThis`, `uniqueResult`, `@TERMINATING`) 100% intact** — they differ strictly in the surface vocabulary and defaults that desugar into `EffectSet`.

### Option 1 — Stratified Topmost `pure` / `hermetic` + Subtractive Relaxations + Default-`borrow` *(Recommended)*

Redefine `pure` and `hermetic` as **top-tier consolidated guarantees**, add small subtractive relaxations for the rare cases that drop one guarantee, and make `borrow` the default on explicitly effect-annotated signatures (with `escape` opting out):

* **Consolidated core tiers:**
  * **`pure`** $\equiv$ `{PURE, HERMETIC, DETERMINISTIC, NOTHROW}` + `borrow` on `this` and all parameters (unless opted out). Strictly forbidden with `mut`.
  * **`hermetic`** $\equiv$ `{HERMETIC, DETERMINISTIC, NOTHROW}` + `borrow` on `this` and all parameters (unless opted out). Compatible with `mut` on `this` (`fn hermetic mut`) and `mut` parameters (`mut rng: Rng`).
  * *(Optional 1-word shorthand under `BIP016-Q2`:)* **`mutating`** or **`local`** for `hermetic mut` — or keep `hermetic mut` (2 words, crystal clear, zero new synonym).
* **Relaxing individual guarantees (rare ~5% cases):**
  * **`throws`** clears `NOTHROW` from `pure` or `hermetic` (`fn pure throws (x: double): int` for `math.toInt`; `axiom pure throws on java.nio.file.Path.of`).
  * **`volatile`** (or **`readonly`** as a standalone base tier — see Option 2 & `BIP016-Q1`) clears `DETERMINISTIC` (`fn pure volatile` or `fn readonly` for `Instant.now()`, `HittableList.first`; `axiom readonly throws on java.nio.file.Files.readString`).
  * **`escape`** (on `fn` prefix for `this`, or on a parameter `escape p: T`) clears `borrow` on that slot (`fn pure escape (r: Ray, rayT: Interval): HitRecord?` for `Hittable.hit`; `fn hermetic mut escape (escape item: Hittable)` for `HittableList.add`).
* **Unannotated functions remain 100% backward compatible:** An unannotated `fn (x: int): int -> (...)` still runs full fixed-point inference (`BIP007` §3.1) and accepts arbitrary side effects / escaping without requiring `impure` or `escape`. Only **explicitly effect-annotated** signatures (`pure`, `hermetic`, `readonly`) activate the `borrow`-by-default rule (or optionally `borrow` remains explicit — see `BIP016-Q3`).
* **Low-level primitives (`nothrow`, `deterministic`, `borrow`):** `nothrow` remains useful standalone (e.g., `BVHNode.fromList`, `countWhere`'s frame-local closure); `borrow` remains valid on unannotated higher-order functions (`HittableList.forEach`); `deterministic` can either stay available for standalone use or be deprecated (`BIP016-Q4`).

* **Pros:**
  * Cuts Argon's total effect/aliasing tokens from **439 → ~147 (−67%)** and collapses **84.8%** of all `fn`s (89/105) to a **single keyword**.
  * Aligns `pure` with universal developer intuition (Checker Framework `@Pure`, Flix `Pure`, Koka `total`, mathematical purity).
  * Preserves 100% expressiveness of `BIP007`'s lattice (every combination remains expressible, including `pure throws`, `readonly`, `readonly throws`, `hermetic mut throws`, `nothrow`).
  * Preserves `Babble.g4`'s zero-predicate LL(1)/LL(2) pure-`SLL` grammar (`throws`, `escape`, `readonly`/`volatile` are simple `memberName`-exempted tokens).
* **Cons:**
  * **Breaking change** to existing `BIP007` semantics of `pure` and `hermetic` (existing tests/scripts writing `fn pure` on throwing or non-deterministic functions must change to `fn pure throws` or `fn readonly`, though Babble is pre-1.0 and already did a clean grammar break in `RM006` / `BIP012`).
  * Subtractive combinations like `pure throws` or `pure volatile` are **non-monotonic** (`pure throws` is *weaker* than `pure`), which may surprise readers expecting every added keyword to strengthen the contract (see Option 2 for a monotonic variant!).

---

### Option 2 — Strictly Monotonic Stratified Hierarchy (No Subtractive Modifiers)

Avoid subtractive modifiers (`pure throws`, `pure volatile`) entirely by naming each tier monotonically (PostgreSQL `IMMUTABLE`/`STABLE`/`VOLATILE` + C23 `[[unsequenced]]`/`[[reproducible]]` style):

| Tier Keyword | Internal `EffectSet` Bits | Example |
|---|---|---|
| **`pure`** | `{PURE, HERMETIC, DETERMINISTIC, NOTHROW}` | `math.sqrt`, `Vec3.dot`, `java.lang.Math.sin` |
| **`calc`** *(or **`fallible`** / **`total`** inverted)* | `{PURE, HERMETIC, DETERMINISTIC}` *(may throw)* | `math.toInt`, `Color.formatPpm`, `Path.of`, `assert` helpers |
| **`readonly`** *(or **`stable`**)* | `{PURE, HERMETIC}` *(may read mutable state / throw; add `nothrow` if needed)* | `Instant.now()`, `Files.readString`, `HittableList.first` |
| **`hermetic`** | `{HERMETIC, DETERMINISTIC, NOTHROW}` *(allows `mut`)* | `Rng.nextDouble`, `Material.scatter`, `Camera.rayColor` |
| **`local`** *(or **`confined`**)* | `{HERMETIC}` *(allows `mut`, may throw / read mutable state)* | `ArrayList.get` (throws on OOB), `HashMap.put` |

*(Or a minimalist monotonic variant: keep `pure` = `{PURE, HERMETIC, DETERMINISTIC, NOTHROW}`, **`readonly`** = `{PURE, HERMETIC}` (composable with `deterministic` and `nothrow`), and **`hermetic`** = `{HERMETIC, DETERMINISTIC, NOTHROW}` with **`local`** = `{HERMETIC}`!)*

* **Pros:**
  * **100% monotonic:** Adding a keyword *always* strengthens the guarantee (never subtracts from an earlier keyword).
  * `pure` and `hermetic` still cover 88.6% of Argon in **1 keyword**!
  * Clear distinction between `pure` (topmost) and `readonly` (non-mutating reader, matching Checker Framework's `@Pure` vs. `@SideEffectFree`).
* **Cons:**
  * Introduces extra tier names (`readonly` / `calc` / `local`) that programmers must memorize to express simple single-axis relaxations like "pure except it can throw" (where `pure throws` is self-explanatory to any Java/Swift developer!).
  * Doesn't solve `borrow` noise by itself — still needs a decision on `borrow` polarity (`BIP016-Q3`).

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
  * User-defined aliases let domain libraries name recurring algebraic bundles (e.g., `effect monoid = pure deterministic nothrow associative`).
* **Cons:**
  * **Leaves the best keyword (`pure`) wasted on the 0%-used meaning!** Every newcomer will still write `fn pure` expecting mathematical purity and hit confusing errors or miss `deterministic`/`nothrow` optimizations (`BIP014`).
  * **Does not fix parameter `borrow` noise:** A function-level profile cannot eliminate the 60 per-parameter `borrow` annotations (`borrow v: Vec3`, `mut borrow rng: Rng`) unless it also flips parameter-borrow polarity.
  * **Dialect fragmentation & indirection:** User-defined `effect` aliases require readers to jump to alias definitions to understand a signature, complicate `Type.Function` error printing and `Formatter` canonicalization, and add a new namespace/declaration kind to `Babble.g4`, `BlockScopes`, `TypedModuleSummary`, and `AnalysisSession`.

---

### Option 4 — Keep `BIP007` Keywords, Relax `BIP007-D2` (Partial Inference on Annotated Signatures)

Keep `BIP007`'s exact syntax, but change `BIP007-D2` so that writing `fn pure` on an exported function **still exports inferred `deterministic`, `nothrow`, and `borrow`** (only `impure` suppresses inference).

* **Pros:**
  * Zero syntax or grammar changes; Argon implementations could write `fn pure` and still get inferred `deterministic`, `nothrow`, and `borrow`.
* **Cons:**
  * **Fails completely on `interface` declarations and function types!** Abstract interface methods (`Describable.kind`, `Material.scatter`, `Hittable.hit`) and callback parameter types (`countWhere`'s `pred`) have **no body to infer from** — they would still need `fn pure deterministic nothrow borrow (borrow ...)`!
  * **Violates API stability (`BIP007` §3.1, `BIP007-D2`):** Callers across modules would silently depend on accidental `nothrow` or `borrow` properties that the author never promised; adding an `assert` or refactoring a helper could silently break downstream modules.
  * **Fails on `axiom` declarations:** Java `axiom`s have no Babble body, so `axiom pure deterministic nothrow on java.lang.Math.sqrt` stays verbose.

---

### 5.5 Comparative Summary Matrix

| Criterion | Current `BIP007` | Option 1 *(Topmost `pure`/`hermetic` + `throws`/`escape`)* | Option 2 *(Monotonic Tiers)* | Option 3 *(Profiles / Aliases)* | Option 4 *(Relax `BIP007-D2`)* |
|---|---|---|---|---|---|
| **Argon total tokens** *(fn + param)* | 439 *(4.18 / fn)* | **~147** *(−67%)* | **~150** *(−66%)* | ~240 *(−45%, param `borrow` remains)* | ~210 *(interfaces unchanged)* |
| **Argon 1-keyword `fn`s** | 8 / 105 *(7.6%)* | **89 / 105** *(84.8%)* | **89 / 105** *(84.8%)* | 84 / 105 *(80.0%)* | 74 / 105 *(bodies only)* |
| **Works on `interface` & `axiom`?** | Verbose | **Yes (1 keyword)** | **Yes (1 keyword)** | Partially | **No** |
| **Matches intuitive meaning of `pure`?** | No *(0% standalone)* | **Yes** | **Yes** | No *(needs new word like `total`)* | No |
| **Monotonic keyword composition?** | Yes | Subtractive `throws`/`escape` | **Yes** | Yes | Yes |
| **Fixes `borrow` explosion (124 tokens)?** | No | **Yes** *(124 → 15 `escape`)* | Needs `BIP016-Q3` | No | Only on bodies |
| **Grammar (`Babble.g4` pure `SLL`)** | 0 predicates | **0 predicates** | **0 predicates** | New `effect` decl | 0 predicates |
| **Breaking change to `BIP007` tests?** | None | Yes *(small, pre-1.0)* | Yes *(small, pre-1.0)* | None | Semantic change |

---

## 6. Recommended Synthesis & Concrete Semantics

We recommend **Option 1 with Option 2's `readonly` naming for non-deterministic readers** — combining the best ergonomics of Checker Framework (`@Pure` vs. `@SideEffectFree`), Swift (`throws`, `borrowing` default), and PostgreSQL (`IMMUTABLE` vs. `STABLE`) while keeping `BIP007`'s internal `EffectSet` untouched.

### 6.1 Consolidated Effect Vocabulary & Desugaring into Internal `@TRAITS`

Every surface annotation expands deterministically into a collection of Layer 1 orthogonal `@TRAITS` (stored in `EffectSet` and `Type.Parameter`):

| Surface Annotation | Internal `@TRAITS` Collection | Per-Slot `@NON_ESCAPING` Default *(if `BIP016-Q3` adopts default-`borrow`)* | Typical Use Case |
|---|---|---|---|
| `fn pure (...)` | `{@NO_GLOBAL_IO, @NO_MUT_SLOTS, @DETERMINISTIC, @NOTHROW}` | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | Mathematical functions, `record` methods, pure queries (70.5% of Argon) |
| `fn pure throws (...)` | `{@NO_GLOBAL_IO, @NO_MUT_SLOTS, @DETERMINISTIC}` *(clears `@NOTHROW`)* | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | Pure deterministic functions that may throw (`math.toInt`, `Color.formatPpm`, `Path.of`, `assert` helpers) |
| `fn readonly (...)` | `{@NO_GLOBAL_IO, @NO_MUT_SLOTS, @NOTHROW}` | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | Side-effect-free, non-throwing readers of clocks/mutable singletons (`Instant.now()`, `HittableList.first`) |
| `fn readonly throws (...)` | `{@NO_GLOBAL_IO, @NO_MUT_SLOTS}` | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | Side-effect-free, non-deterministic I/O readers (`Files.readString`) |
| `fn hermetic (...)` | `{@NO_GLOBAL_IO, @DETERMINISTIC, @NOTHROW}` | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | No globals/I/O, mutates `@MUTABLE` (`mut`) parameter(s) (`Material.scatter`, `Camera.rayColor`, `Vec3.random*`) |
| `fn hermetic mut (...)` | `{@NO_GLOBAL_IO, @MUT_THIS, @DETERMINISTIC, @NOTHROW}` | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | No globals/I/O, mutates `this` (`Rng.nextDouble`, `HittableList.clear`, `list.add`) |
| `fn hermetic [mut] throws (...)` | `{@NO_GLOBAL_IO, @DETERMINISTIC}` *(+ `@MUT_THIS`)* | `@BORROW_THIS`, `@BORROW(*)` *(unless `escape`)* | Bounded local mutation that may throw (e.g., `list.set(i, v)`) |
| `fn nothrow (...)` | `{@NOTHROW}` | Unchanged (opt-in `borrow`) | Exception-free but unconstrained on global/mutable state (`BVHNode.fromList`, frame-local closures) |
| `fn impure (...)` | `{@IMPURE_LOCK}` *(clears all inferred traits)* | None | Explicit unconstrained opt-out (`Camera.render`, `URL.equals`) |
| `fn (...)` *(unannotated)* | Full `BIP007` fixed-point `@TRAITS` inference | Inferred per `BIP007` | Ordinary Babble scripts and private helpers (zero migration cost!) |

Per-slot modifiers (`mut`, `borrow`, `escape`, `unique`) simply add or clear the corresponding slot-level traits (`@MUTABLE`, `@NON_ESCAPING`, `@UNIQUE`). Notice that **`@NO_MUT_SLOTS` and `@MUTABLE` remain mutually exclusive**, preserving `BIP007`'s zero weak-purity guarantee, while **`@TERMINATING`** stays an inferred-only compiler trait (optionally exposable later via a `total` profile = `pure` + `@TERMINATING` without touching the lattice).

**Algebraic properties** (`idempotent`, `commutative`, `associative`, `monotone`, `injective`) and **ownership modifiers** (`unique`, `frozen`, `move`, `freeze`, `discard`) compose identically as in `BIP007` — except they become dramatically cleaner:

```babble
;; Before (BIP007):
axiom pure deterministic nothrow on java.lang.Math.sqrt
axiom pure deterministic on java.nio.file.Path.of
axiom pure on java.nio.file.Files.readString
axiom hermetic mut deterministic idempotent commutative on java.util.HashSet.add
def addComm = fn pure deterministic nothrow commutative associative (a: int, b: int): int -> (a + b)

;; After (BIP016):
axiom pure on java.lang.Math.sqrt
axiom pure throws on java.nio.file.Path.of
axiom readonly throws on java.nio.file.Files.readString
axiom hermetic mut idempotent commutative on java.util.HashSet.add
def addComm = fn pure commutative associative (a: int, b: int): int -> (a + b)
```

Notice how **only `throws` is subtractive** (and `throws` is universally understood from Java, Swift, and Kotlin/C++), while `readonly` replaces `pure volatile` so the reader never sees contradictory words like `pure volatile`!

### 6.2 Escape Polarity: Default-`borrow` with Opt-Out `escape`

Under the recommended synthesis, **any function carrying a consolidated effect contract (`pure`, `readonly`, `hermetic`) defaults `this` and all non-`unique` parameters to `borrow`**, and authors write **`escape`** only on the ~10% of slots that intentionally store or return the reference:

* **Why `escape` on `this` and parameters works cleanly:**
  * On a method: `def hit = fn pure escape (r: Ray, rayT: Interval): HitRecord? -> (...)` makes it immediately visible to callers that `hit` may return references reachable from `this` (specifically `this.mat` inside `HitRecord`), while `r` and `rayT` are guaranteed borrowed (enabling `BIP014` `O9` scalar replacement of temporary `Ray` and `Interval` values!).
  * On a parameter: `def add = fn hermetic mut escape (escape item: Hittable) -> (...)` documents both that `this.head` escapes into a newly allocated node and that `item` is stored inside `this`.
  * On `mut` parameters: `mut rng: Rng` defaults to `mut borrow` (matching 16/16 occurrences in Argon), whereas `mut escape items: HittableList` marks a mutated parameter whose contents may also escape.
  * On `unique` parameters: plain `unique x: T` already means *consuming* (taking ownership, not borrowing), while `unique borrow x: T` remains an exclusive borrow (`BIP007` §3.2) — so `unique` semantics do **not** change!
  * On **unannotated functions** (`fn (x: T): R -> (...)`): nothing changes; `EffectChecker` infers `borrow` on unannotated bodies and leaves unannotated interface/function types unconstrained on escape so existing code never breaks.
* **Subtyping check:** Subtyping in `TypeAlgebra` is completely unaffected because `escape` simply sets `borrow = false` in `Type.Parameter` / `EffectSet` (`actual` with `borrow = true` still satisfies a target with `escape` / `borrow = false`, never the reverse).

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

;; lib/interfaces.ba — AFTER (BIP016):
export def Describable = interface (
    def kind: fn pure (): string
    def describe = fn pure (): string -> (
        "<" + this.kind() + ">"
    )
)
export def Material = interface extends Describable (
    def scatter: fn hermetic (rIn: Ray, rec: HitRecord, mut rng: Rng): ScatterResult?
    def kind = fn pure (): string -> ("material")
)
export def Hittable = interface extends Describable (
    def hit: fn pure escape (r: Ray, rayT: Interval): HitRecord?
    def boundingBox = fn pure (): AABB -> (AABB.empty)
    def kind = fn pure (): string -> ("hittable")
)
```

Across the entire Argon codebase:
* **Function-level tokens:** `362` → `~120` (**−67%**)
* **Parameter/return modifier tokens:** `79` → `~27` (**−66%**, `60 borrow` → `7 escape` + `17 mut` + `2 unique`)
* **Total effect/ownership tokens:** `439` → `~147` (**−67%**)
* **Single-keyword functions:** `8/105` (7.6%) → **`89/105` (84.8%)**

### 6.4 Zero Backend, Optimizer, or Runtime Boundary Churn

Because consolidation is purely a **front-end surface mapping** into `BIP007`'s existing `EffectSet` and `Type.Parameter`:

* **`org.babblelang.runtime` (`babble-runtime.jar`):** 0 lines changed (`RuntimeBoundaryTestCase` untouched).
* **`Interpreter` & `CodeGenerator`:** 0 lines changed.
* **`EffectChecker` fixed-point engine & `BIP014` optimizer passes:** 0 semantic changes (`EffectSet` still exposes `isPure()`, `isHermetic()`, `isDeterministic()`, `isNothrow()`, `borrowThis()`, `uniqueResult()`, `terminating()`).
* **Grammar (`Babble.g4`):** Stays 100% predicate-free LL(1)/LL(2) pure-`SLL` (`ParserGrammarTestCase`): add `READONLY`, `THROWS`, `ESCAPE` (exact spelling settled by `BIP016-Q1`/`Q3`), all exempted in `memberName` so `.readonly`, `.throws`, `.escape` remain valid member names.
* **Bonus precision cleanups surfaced by Argon (optional small companion fixes in `EffectChecker`):**
  1. Treat stateless `object` singletons (zero mutable fields, like `emptyHittable`) as deeply immutable for `deterministic` checks — upgrading `HittableList.first`/`second` from `readonly escape` to `pure escape`!
  2. Avoid tainting a fresh local container (`leftList` in `BVHNode.fromList`) as mutating `@PARAM(items)` when it only *stores* non-mutated elements read from `items` — allowing `BVHNode.fromList` to drop `mut` on `items` and become `fn pure escape (escape items: HittableList): Hittable`!

---

## 7. Open Questions (`BIP016-Q1`–`Q7`)

Before committing to implementation, the following design questions should be settled:

* **`BIP016-Q1` — Subtractive `throws` vs. strictly monotonic keywords:** Should a pure deterministic function that may throw be written `fn pure throws` (intuitive, 1 extra keyword, slightly non-monotonic) or a distinct monotonic tier keyword like `fn calc` / `fn fallible`? Similarly, should non-deterministic read-only functions use monotonic `fn readonly` (+ `fn readonly throws`) or subtractive `fn pure volatile`?
* **`BIP016-Q2` — `hermetic` bundling & `mut` shorthand:** Should `hermetic` imply `deterministic nothrow` symmetrically with `pure` (recommended — turns 15 Argon functions into `fn hermetic` and 3 into `fn hermetic mut`), and should `hermetic mut` stay two words or gain a 1-word shorthand (e.g., `mutating` / `local`)?
* **`BIP016-Q3` — `borrow` polarity scope:** Which of these three `borrow` rules should Babble adopt?
  1. **Default-`borrow` on effect-annotated signatures (`pure`, `readonly`, `hermetic`), opt-out with `escape`** *(recommended — cuts 124 `borrow`s to ~15 `escape`s while keeping unannotated scripts untouched)*.
  2. **Default-`borrow` only for deeply immutable types (`record`s, `frozen T`, scalars)**, keeping `borrow` explicit on mutable classes/interfaces (eliminates 34/44 param `borrow`s and 26/64 receiver `borrow`s = ~48% of `borrow`s, without introducing `escape`).
  3. **Keep `borrow` explicit everywhere** (accepting ~124 `borrow`s in Argon, relying purely on `pure`/`hermetic` consolidation for the ~55% function-prefix reduction).
* **`BIP016-Q4` — Fate of `deterministic` and `nothrow` keywords:** Once `pure` and `hermetic` imply `deterministic nothrow`, should `deterministic` and `nothrow` remain valid low-level modifiers (e.g., standalone `fn nothrow` or `fn readonly deterministic` rejected as redundant with `pure`), or should `deterministic` be retired from the surface grammar while keeping `nothrow`?
* **`BIP016-Q5` — User-defined effect aliases / profiles:** Should Babble stay with a small, fixed built-in vocabulary (preventing codebase-specific effect dialects), or also add Koka-style `effect Name = ...` aliases?
* **`BIP016-Q6` — Java host API alignment (`org.babblelang.effects`):** Should Java `@Pure` (`Effect.PURE`) and `engine.assumeEffects(..., Effect.PURE)` be updated to match surface `pure` (`PURE + DETERMINISTIC + NOTHROW`, with `@ReadOnly` / `@SideEffectFree` and `@Throws` for weaker Java methods), or should the Java enum remain atomic (`Effect.PURE`, `Effect.DETERMINISTIC`, `Effect.NOTHROW`) while `@Pure` is documented as the consolidated shorthand?
* **`BIP016-Q7` — Companion `EffectChecker` precision improvements & migration:** Should the two Argon-discovered `EffectChecker` refinements (stateless `object` singletons preserving `deterministic`, and distinguishing *storing* a parameter's element into a fresh local from *mutating* the parameter itself) land alongside `BIP016`, and should existing `.ba`/`.batest`/`examples/` files be migrated in a single clean pass (as in `RM006` / `BIP012`)?

---

## 8. Bibliography

### Languages, Compilers & Standards

- **`[D-Func]`** / **`[D-Style]`** D Language Specification & Style Guide. *[Functions — Function Attributes](https://dlang.org/spec/function.html)*, *[Attributes](https://dlang.org/spec/attribute.html)*, and *[The D Style — Attributes](https://dlang.org/dstyle.html)*.
  - **Summary:** Specifies D's additive function attributes (`pure`, `nothrow`, `@safe`, `@nogc`, `const`, `scope`) and documents the official style convention of annotating non-templated functions with `const @nogc nothrow pure @safe`, relying on template inference internally.
  - **Contribution to `BIP016`:** Primary cautionary case study of additive "attribute soup" at API/interface boundaries (§1, §4).

- **`[CF-Pure]`** / **`[CF-SEF]`** / **`[CF-Man]`** The Checker Framework (University of Washington). *[`@Pure` API](https://checkerframework.org/api/org/checkerframework/dataflow/qual/Pure.html)*, *[`@SideEffectFree` API](https://checkerframework.org/api/org/checkerframework/dataflow/qual/SideEffectFree.html)*, and *[The Checker Framework Manual](https://checkerframework.org/manual/)*.
  - **Summary:** Separates side-effect-freedom (`@SideEffectFree`) from determinism (`@Deterministic`), and defines **`@Pure`** explicitly as their conjunction (`@SideEffectFree` + `@Deterministic`).
  - **Contribution to `BIP016`:** Directly motivates placing `pure` at the top of Babble's purity hierarchy while naming the weaker non-deterministic read-only property separately (`readonly`) (§1, §3.2, §4, §6.1).

- **`[PG-Vol]`** PostgreSQL Global Development Group. *[PostgreSQL Documentation: Function Volatility Categories (`IMMUTABLE`, `STABLE`, `VOLATILE`)](https://www.postgresql.org/docs/current/xfunc-volatility.html)* and *[`CREATE FUNCTION`](https://www.postgresql.org/docs/current/sql-createfunction.html)*.
  - **Summary:** Classifies every SQL function into one of three stratified volatility tiers (`IMMUTABLE`, `STABLE`, `VOLATILE`) driving query-planner constant folding, index scans, and caching.
  - **Contribution to `BIP016`:** Demonstrates how a compact 1-word stratified hierarchy captures the common optimization contracts without multi-keyword stacks (§4, §5 Option 2).

- **`[GCC-Attr]`** / **`[C23-N2956]`** GNU Compiler Collection & ISO/IEC WG14 (Jens Gustedt). *[GCC Common Function Attributes (`const`, `pure`)](https://gcc.gnu.org/onlinedocs/gcc/Common-Attributes.html)* and *[WG14 N2956: Unsequenced functions (`[[reproducible]]`, `[[unsequenced]]` in C23)](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n2956.htm)*, April 2022.
  - **Summary:** Contrasts GCC's `const` vs. `pure` with C23's standardized `[[reproducible]]` and `[[unsequenced]]` attributes; N2956 explicitly documents how WG14 rejected exposing every underlying property (`effectless`, `idempotent`, `stateless`, `independent`) as separate surface attributes in favor of two bundled profiles, and discusses the historical ambiguity of the word `pure`.
  - **Contribution to `BIP016`:** Concrete industrial precedent for bundling fine-grained internal compiler properties into coarse surface profiles (§4, §5).

- **`[Koka-Book]`** / **`[Lei14]`** Daan Leijen (Microsoft Research). *[The Koka Programming Language — Effect Types](https://koka-lang.github.io/koka/doc/book.html)*, *[`std/core/types` Effect Aliases](https://koka-lang.github.io/koka/doc/std_core_types.html)*, and *"[Koka: Programming with Row Polymorphic Effect Types](https://arxiv.org/abs/1406.2061)"* (MSFP 2014 / EPTCS, [PDF](https://arxiv.org/pdf/1406.2061)).
  - **Summary:** Tracks effects (`exn`, `div`, `ndet`, `read`, `write`, `alloc`, `io`) via row polymorphism while giving programmers concise named aliases (`total` = empty row, `pure` = `<div, exn>`, `io` = all I/O effects) and defaulting unmarked functions to `total`.
  - **Contribution to `BIP016`:** Reference model for named effect profiles/aliases over a fine-grained internal effect algebra (§4, §5 Option 3).

- **`[Flix-Doc]`** / **`[MP20]`** Magnus Madsen and Jaco van de Pol (Aarhus University). *[Programming Flix — Effect System](https://doc.flix.dev/effect-system.html)* and *"[Polymorphic Types and Effects with Boolean Unification](https://cs.au.dk/~magnusm/papers/oopsla20/paper_b.pdf)"*, **OOPSLA 2020** (PACMPL 4).
  - **Summary:** Separates `Pure` (default, unmarked) from `IO` and user effects using Hindley-Milner extended with Boolean unification (`~1.1x` compilation overhead), allowing purity-driven compiler optimizations without per-function boilerplate.
  - **Contribution to `BIP016`:** Demonstrates the ergonomic payoff of pure-by-default polarity and strong mathematical `Pure` semantics (§3.2, §4).

- **`[Nim-Eff]`** Nim Programming Language. *[Nim Manual — Effect System](https://nim-lang.org/docs/manual.html)* and *[Effect System Documentation (`noSideEffect`, `func`, `raises`)](https://nim-docs.readthedocs.io/en/latest/manual/effect_system/)*.
  - **Summary:** Tracks side effects (`{.noSideEffect.}`) and exceptions (`{.raises: [].}`) with inference, and introduces the `func` keyword as syntactic shorthand for `proc ... {.noSideEffect.}`.
  - **Contribution to `BIP016`:** Illustrates how real-world imperative languages adopt concise single-keyword shorthands when effect pragmas become pervasive (§4).

- **`[SE-0377]`** / **`[Swift-Mut]`** Swift Evolution. *[SE-0377: `borrowing` and `consuming` parameter ownership modifiers](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0377-parameter-ownership-modifiers.md)* and *[Swift Forums: Borrowing `self` by default in Swift 6](https://forums.swift.org/t/pitch-dont-copy-self-when-calling-non-mutating-methods/61707)*.
  - **Summary:** Documents Swift's ownership polarity (`borrowing` as the default for non-mutating parameters and `self`, with `consuming` / `mutating` / `throws` written only when opting out of the safe baseline).
  - **Contribution to `BIP016`:** Directly informs `BIP016`'s polarity design (`throws` opt-out and default-`borrow` with opt-out `escape`) (§4, §5 Option 1, §6.2).

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
  - **Summary:** Formalize modular purity checking and inference for object-oriented languages using freshness (newly allocated local objects, cf. Babble's `unique`) and locality (non-escaping parameters, cf. Babble's `borrow`).
  - **Contribution to `BIP016`:** Grounds the relationship between purity, freshness, and parameter locality/borrowing in OO languages (§3.2, §6.2).

- **`[ROH12]`** Lukas Rytz, Martin Odersky, and Philipp Haller. *"[Lightweight Polymorphic Effects](https://infoscience.epfl.ch/entities/publication/553acaa9-4b8a-497a-8c78-96c1a80d9f65)."* In **ECOOP 2012**, LNCS 7313, pp. 258–282.
  - **Summary:** Argues that **syntactic verbosity is the primary obstacle preventing industrial adoption of effect systems**, and introduces lightweight default-based effect annotations and polymorphism across multiple effect domains in Scala.
  - **Contribution to `BIP016`:** Directly validates the motivation of `BIP016`: even a sound, expressive effect system fails in practice if everyday signatures are verbose (§1, §4).

- **`[GPP12]`** / **`[CDB15]`** Colin S. Gordon, Matthew J. Parkinson, Jared Parsons, Aleks Bromfield, and Joe Duffy. *"[Uniqueness and Reference Immutability for Safe Parallelism](https://www.cs.drexel.edu/~csg63/papers/oopsla12.pdf)"*, **OOPSLA 2012**; and Sylvan Clebsch, Sophia Drossopoulou, Sebastian Blessing, and Andy McNeil, *"Deny Capabilities for Safe, Fast Actors"* (**AGERE! 2015**, [Pony publications](https://wp.doc.ic.ac.uk/sd/publications/) & [reference capabilities overview](https://tamnd.github.io/brain/research/memory-management/ownership/06_pony_refcaps/)).
  - **Summary:** Establish the `unique`/`isolated` $\to$ `immutable`/`frozen` zero-copy permission lattice (`[GPP12]`) and Pony's 6-capability matrix (`[CDB15]`) — along with the usability lesson that pervasive per-reference capability qualifiers carry high annotation cost.
  - **Contribution to `BIP016`:** Contextualizes why Babble keeps `unique`/`frozen` minimal (`BIP007`) and must likewise keep `borrow` unobtrusive via sensible defaults (§3, §6.2).

- **`[BGO23]`** Aleksander Boruch-Gruszecki, Martin Odersky, Edward Lee, Ondřej Lhoták, and Jonathan Brachthäuser. *"[Capturing Types](https://abgru.me/publication/capturing-types/)."* **ACM TOPLAS** 45(4), 2023 (and [EPFL LAMP Capture Checking overview](https://chara.epfl.ch/~odersky/)).
  - **Summary:** Develops capture checking for Scala 3 where pure/non-escaping references are the concise unmarked default and capturing/escaping values are marked explicitly (`^`).
  - **Contribution to `BIP016`:** Modern blueprint for `BIP016`'s non-escaping-by-default polarity (`escape` opt-out instead of 124 `borrow` keywords) (§4, §6.2).

- **`[Tae25]`** *"Type, Ability, and Effect Systems: Perspectives on Purity, Semantics, and Expressiveness."* **[arXiv:2510.07582](https://arxiv.org/html/2510.07582v1)**, October 2025.
  - **Summary:** Establishes a typing-independent semantic definition of purity — mathematical functions that terminate, produce deterministic results, and exhibit no observable side effects — and compares effect systems vs. capability systems on completeness and usability.
  - **Contribution to `BIP016`:** Reinforces the central thesis of `BIP016`: `pure` should denote strong mathematical purity (deterministic, non-throwing, side-effect-free), not merely absence of writes (§1, §3.2).
