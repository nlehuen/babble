# Babble Improvement Proposals (BIPs)

Design documents and language proposals for Babble, numbered chronologically by creation date in the git history.

## Naming convention

Items within each proposal use stable identifiers prefixed by the BIP number:

- **`BIP###-Q#`** — open questions (e.g., `BIP007-Q1`). Resolving a question gives a corresponding decision (`BIP###-D#`).
- **`BIP###-D#`** — decision points (e.g., `BIP001-D1`, `BIP002-D3`). Where a decision settles an earlier question, both are linked (e.g., `BIP005-Q1` → `BIP005-D1`, or `BIP001-Q1` → `BIP003-D1`).
- **`BIP###-P#`** — implementation phases (e.g., `BIP001-P0`–`BIP001-P4`, `BIP008-P1`–`BIP008-P7`; sub-phases may carry a letter suffix such as `BIP001-P0a` or `BIP008-P6a`).

## Index

| BIP | Title | Date | Status | Document |
|---|---|---|---|---|
| **BIP001** | Compiling Babble to JVM bytecode | 2026-10-11 | Draft (`BIP001-D1`–`BIP001-D8` decided; `BIP001-Q1`–`BIP001-Q9` → `BIP003-D1`–`BIP003-D9`; `BIP001-P0a`/`P0b`–`BIP001-P3` implemented apart from JVM interfaces in §17 and typed/unboxed codegen in §10.5/§18; `BIP001-P4` CLI done as `BIP006-P4`–`BIP006-P6`, Maven goal deferred to `BIP006-D8`) | [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) |
| **BIP002** | The Babble object model | 2026-10-11 | Implemented (`BIP002-D1`–`BIP002-D15` decided, resolving `BIP002-Q1`–`BIP002-Q9`; `BIP002-P1`–`BIP002-P6` implemented in interpreter and `BIP001-P1`/`P2`; JVM interfaces §7.3, direct Java-interface bridges §9.5–§9.6, and `LambdaMetafactory` callback adapters §9.3 remain) | [bip002_object-model.md](bip002_object-model.md) |
| **BIP003** | The type system | 2026-10-24 | Gradual rules, `dynamic`, and internal `BIP003-D9` checks replaced by `BIP008` (`BIP003-D1`–`BIP003-D8` settled `BIP001-Q1`–`BIP001-Q8`; runtime checks `BIP003-D9` remain at `as` casts, Java boundaries, and `setMember`; structural object types and publishing expression types to the compiler in §6 remain) | [bip003_type-system.md](bip003_type-system.md) |
| **BIP004** | The interactive mode | 2026-11-10 | Implemented (`BIP004-D1` nested session scopes; sessions keep one `Modules` per `BIP006-P1` and close inference per input per `BIP008-D5`; line editing/history/completion, `:load`, value type display, and cross-input direct field access in §8 remain) | [bip004_repl.md](bip004_repl.md) |
| **BIP005** | Modules | 2026-11-11 | Accepted & implemented (`BIP005-Q1`–`BIP005-Q8` → `BIP005-D1`–`BIP005-D8`; `BIP005-P1`–`BIP005-P5` implemented with `P4`–`P5` via `BIP006`; destructuring `BIP005-D3`, search path/stdlib `BIP005-D5`, and static-import slot optimization §8.3 deferred) | [bip005_modules.md](bip005_modules.md) |
| **BIP006** | Module sources and ahead-of-time compilation | 2026-11-14 | Accepted & implemented (`BIP006-Q1`–`BIP006-Q8` → `BIP006-D1`–`BIP006-D8`; `BIP006-P1`–`BIP006-P6` implemented, covering `BIP001-P4` and `BIP005-P4`–`P5`; `babblec` Maven plugin `BIP006-D8`, incremental `babblec`, and GraalVM native image §9 deferred) | [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md) |
| **BIP007** | Function Effects and Algebraic Properties in Babble | 2026-11-14 | Draft (not implemented; depends on `BIP008-P1`–`BIP008-P7`, now unblocked; `BIP007-Q1`–`BIP007-Q4` open; `BIP007-P1`–`BIP007-P3` planned; `threadsafe` & parallelization in §10) | [bip007_effects.md](bip007_effects.md) |
| **BIP008** | Removing dynamic typing | 2026-11-16 | Accepted & implemented (`BIP008-Q1`–`BIP008-Q7` → `BIP008-D1`–`BIP008-D7`; `BIP008-P1`–`BIP008-P5`, `BIP008-P6a`, `BIP008-P6b`, `BIP008-P7` implemented; unblocks typed/unboxed codegen §7.1 and `BIP007`; array indexing syntax §5.1 remains) | [bip008_static-typing.md](bip008_static-typing.md) |

## Cross-BIP baseline

With `BIP001-P0`–`BIP001-P3`, `BIP002-P1`–`BIP002-P6`, `BIP004`, `BIP005-P1`–`BIP005-P5`, `BIP006-P1`–`BIP006-P6`, and `BIP008-P1`–`BIP008-P7` landed, Babble has:

- A shared, `java.base`-only runtime (`BIP001-P0`, `BIP006-P1`) and dual-mode execution (parse-tree `Interpreter` + ASM `CodeGenerator` with `invokedynamic` call/select/Java inline caches).
- Classes, value classes, objects, interfaces with default methods, and two-way Java interop via `Proxy` faces (`BIP002`).
- Nested interactive sessions (`BIP004`), cross-file modules with `ModuleSource` abstractions (`BIP005`, `BIP006-P2`–`P3`), and reproducible AOT jars (`babblec` `-compile`, `BIP006-P4`–`P6`).
- Mandatory static typing (`BIP008`) with `any`/`any?`, `T?`, `A | B`, `?.`, `as`, flow-sensitive narrowing, generics (`(...)`), call-site parameter inference, and `int`→`double` conversion on numeric joins (`BIP008-D7`), eliminating internal `BIP003-D9` runtime checks.

Because `BIP008` guarantees trustworthy static types before code generation runs, two major areas are now directly unblocked: **typed/unboxed bytecode generation & JVM interface lowering** (`BIP001` §10.5/§17, `BIP002` §7.3/§9.3/§9.5, `BIP003` §6, `BIP008` §7.1) and **effect/ownership analysis & compiler optimizations** (`BIP007`).

## Roadmap

The stages below order the remaining work and planned phases across `BIP001`–`BIP008` by technical dependency so each step builds cleanly on the previous one while keeping `mvn package` green in both modes.

### Overview

| Stage | Phase / Item | Source BIPs | Prerequisite(s) | Status / Readiness |
|---|---|---|---|---|
| **1A** | Publish expression types & unboxed primitive codegen | `BIP001` §10.5/§18, `BIP003` §6, `BIP008` §7.1 | `BIP008-P4`–`P7` | Ready once §10.5 / `BIP008` §7.1 design note is added to BIP001 |
| **1B** | Compile-time Java dispatch & static-import specialization | `BIP001` §10.5, `BIP005` §8.3, `BIP004` §8 | Stage 1A, `BIP005-P3` | Ready after 1A |
| **1C** | JVM interfaces, direct Java-interface bridges & `LambdaMetafactory` | `BIP001` §17, `BIP002` §7.3/§9.3/§9.5–§9.6/`P6` | Stage 1A–1B | Ready after 1A–1B |
| **2A** | Settle open questions (`BIP007-Q1`–`Q4` → `D1`–`D4`) & `BIP007-P1` (Core effects, inference, verification, `axiom`) | `BIP007` §9.1–§9.2 | `BIP008-P1`–`P7` | Ready now (front-end only; can run in parallel with Stage 1 once Q1–Q4 are settled) |
| **2B** | `BIP007-P2` (Deep ownership `unique`/`move`/`freeze`, `@TERMINATING`, core optimizations) | `BIP007` §9.2 | `BIP007-P1`, Stage 1A | Unblocked after `BIP007-P1` + Stage 1A |
| **2C** | `BIP007-P3` (Algebraic rewrites, ASM suggester, `babble.checkAxioms`) | `BIP007` §9.2 | `BIP007-P2` | Unblocked after `BIP007-P2` |
| **3A** | Destructuring definitions (`def (a, b) = expr`) | `BIP005-Q3` → `BIP005-D3` | `BIP005-P3`, `BIP008-P7` | Needs small BIP; independent of Stages 1–2 |
| **3B** | Array/collection indexing & loop ergonomics | `BIP008` §5.1 (+ README) | `BIP008-P6b` | Needs BIP design; independent of Stages 1–2 |
| **3C** | Module search path (`babble.path`) & `std/...` library | `BIP005-Q5` → `BIP005-D5`, `BIP006-P3` | `BIP006-P3`; ideally after `BIP007-P1`–`P2` | Needs BIP design |
| **3D** | Structural object types | `BIP003` §6 (+ README) | Stage 1C, `BIP008` | Needs BIP design reconciling with `BIP002-D13` |
| **4A** | Interactive mode enhancements (line editing, `:load`, value types) | `BIP004` §8 | `BIP004`, `BIP008` | Ready anytime; independent |
| **4B** | `babble-maven-plugin` & incremental `babblec` | `BIP001` §9, `BIP006-D8`, `BIP006` §9 | `BIP006-P4`–`P6` | Ready anytime; separate Maven plugin module |
| **4C** | GraalVM native image support | `BIP006` §9 | Stage 1C, `BIP006-P4`–`P6` | Blocked on removing `Proxy` in Stage 1C |
| **5** | Thread-safe `Interpreter` & `threadsafe` parallel combinators | `BIP007` §10 (+ README / `CLAUDE.md`) | `BIP007-P1`–`P2` | Needs thread-safety design + `BIP007` extension |

---

### Stage 1 — Typed Bytecode Generation & JVM Interface Lowering

Now that `BIP008` provides non-`dynamic` static types everywhere, `CodeGenerator` can stop boxing every primitive and stop routing statically known receivers, Java calls, and interfaces through runtime fallbacks and dynamic proxies.

1. **1A — Publish expression types & unboxed primitive codegen** ([BIP001](bip001_bytecode-compiler.md) §10.5/§18, [BIP003](bip003_type-system.md) §6, [BIP008](bip008_static-typing.md) §7.1)
   - *Design step first:* Land the unboxed-arithmetic design section in [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) (per `BIP008` §7.1).
   - *Scope:* Publish resolved expression types / primitive storage kinds (`int`, `double`, `bool`) from `TypeChecker` via `ScopeAnalysis` (replacing a separate `Unboxing` pass). Unbox non-nullable `int`, `double`, and `bool` locals, parameters, and class fields; emit primitive JVM arithmetic (`IADD`, `IMUL`, `DADD`, etc., without `instanceof BabbleInstance` checks when the left operand is statically numeric) and comparisons (`IF_ICMPxx`, `Double.compare` for NaN/signed-zero parity with `Operators`); emit specialized primitive entry points next to boxed `Callable` adapters for typed functions and methods; restrict constant folding optimizations to provable `int`s (`BIP001` §10.5); preserve `classof(1)`/`classof(1.5)` semantics (`BIP002` §4.11).
   - *Exit criteria:* Dual-mode suite and `CheckClassAdapter` pass; `argon` renders `scene.ppm` byte for byte; measurable gains on `Benchmarks` and both ray tracers.
2. **1B — Compile-time Java dispatch & static-import specialization** ([BIP001](bip001_bytecode-compiler.md) §10.5, [BIP005](bip005_modules.md) §8.3, [BIP004](bip004_repl.md) §8)
   - *Depends on:* Stage 1A and `BIP005-P3`.
   - *Scope:* Emit direct `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE` and direct field reads/writes when `TypeChecker` resolves a Java overload or static/instance field statically, bypassing `JavaLinker`/`Overloads` reflection on typed call sites. Compile static module imports bound to never-reassigned `def`s (`m.x`, `m.f(...)`) to constant slot reads and guarded direct calls (`BIP005` §8.3); optionally evaluate direct member access across REPL session inputs (`BIP004` §8).
3. **1C — JVM interfaces, direct Java-interface bridges & `LambdaMetafactory` callbacks** ([BIP001](bip001_bytecode-compiler.md) §17, [BIP002](bip002_object-model.md) §7.3, §9.3, §9.5–§9.6, §12 `BIP002-P6`, §13)
   - *Depends on:* Stage 1A–1B.
   - *Scope:* Compile Babble `interface` expressions to real JVM interfaces with abstract accessors/methods and JVM `default` methods (`INVOKEINTERFACE`, `INSTANCEOF` for `is`, `INVOKESPECIAL` for `super(I).m()`, hoisting nested-lambda `super` calls into synthetic instance methods). Have generated JVM classes directly `implements` declared Babble and Java interfaces with exact-signature overload bridges (`BIP002` §9.5–§9.6), retiring `Proxy` faces for compiled instances. Replace `Proxy`-based functional-interface adapters in `Faces` with cached `LambdaMetafactory` adapters (`BIP002` §9.3).

### Stage 2 — Function Effects, Ownership & Effect-Driven Optimizations (`BIP007`)

[BIP007](bip007_effects.md) builds directly on `BIP008-P1`–`BIP008-P7`. Because `BIP007-P1` is strictly a front-end analysis extension with zero changes to `org.babblelang.runtime` (`babble-runtime.jar`), it can be started alongside Stage 1 once its open questions are settled; `BIP007-P2` then integrates with Stage 1's typed codegen and bytecode optimization passes.

1. **Prerequisite — Resolve `BIP007-Q1`–`BIP007-Q4` → `BIP007-D1`–`BIP007-D4`** ([BIP007](bip007_effects.md) §9.1)
   - Decide short-circuit guard interaction with `Narrowing` (`BIP007-Q1`), cross-module export of inferred vs explicit effects (`BIP007-Q2`), optional `identity(...)` on `associative` (`BIP007-Q3`), and `axiom` granularity (`BIP007-Q4`).
2. **`BIP007-P1` — Core Effect Model, SCC Fixed-Point Inference, Verification & `axiom`** ([BIP007](bip007_effects.md) §9.2)
   - *Scope:* Contextual effect keywords in `Babble.g4` and `Formatter`; `EffectSet` and `Type.Parameter(..., mut, borrow, unique)`; SCC bottom-up fixed-point inference and verification for `{pure, hermetic, mut, borrow, deterministic, nothrow}`, compositional algebraic properties (`idempotent`, `commutative`, `associative`, `monotone`, `injective`), frame-local `borrow` closures, call-site specialization for small higher-order helpers, standard value protocol defaults, `value class` constructor checks, and effect-aware `Type.Function` subtyping and interface conformance; file/`package`/`ModuleInterface` `axiom` & `impure` declarations, Java annotations (`@Pure`, `@Hermetic`, `@Mut`, etc.), and `BabbleScriptEngine` registries (`assumeEffects`, `markImpure`, `allowAxioms(false)`).
   - *Constraint:* Zero effect machinery in `babble-runtime` (`RuntimeBoundaryTestCase` remains green).
3. **`BIP007-P2` — Deep Ownership (`unique` / `move` / `freeze`), `@TERMINATING`, and Core Optimizations** ([BIP007](bip007_effects.md) §9.2)
   - *Depends on:* `BIP007-P1` and Stage 1A (typed `ScopeAnalysis` and `CodeGenerator` lowering).
   - *Scope:* `unique`/`@PARAM(i)`/`@THIS`/`@MOVED`/`@EXTERNAL` ownership lattice, sub-reference tracking, merge joins, definite-unassignment, implicit last-use `move`, purely static `Type.Frozen` (`freeze` as an identity no-op in both backends), and `@TERMINATING` inference. Publish effect/ownership metadata in `ScopeAnalysis` and implement bytecode optimization passes (`OptimizerBase` / `CodeGenerator`): CSE & constant folding, `unique mut` no-alias load hoisting, `borrow` scalar replacement/unboxing, zero-copy `freeze` & FBIP in-place reuse, guard-safe predicate reordering/hoisting, dead-expression elimination (plus compile-time errors on unused `pure nothrow` source expressions), and `associative` loop reassociation.
4. **`BIP007-P3` — Algebraic Rewrite Rules, ASM Bytecode Suggester & Axiom Fuzzing** ([BIP007](bip007_effects.md) §9.2)
   - *Depends on:* `BIP007-P2`.
   - *Scope:* `monotone` range inversion and `injective` projection push-down; opt-in ASM bytecode analyzer (with escape analysis and seeded JVM `native` intrinsics) to suggest/certify effects on closed Java helpers; `babble.checkAxioms` property-based fuzzing of `axiom` declarations in CI.

### Stage 3 — Language & Standard Library Ergonomics (Deferred BIP Proposals)

These items were explicitly deferred by `BIP003`, `BIP005`, and `BIP008` into follow-up proposals. Items 3A and 3B are small enough to pull ahead independently; 3C benefits from landing after `BIP007-P1`–`P2`, and 3D after Stage 1C.

1. **3A — Destructuring definitions (`def (Circle, unit) = expr`)** ([BIP005](bip005_modules.md) §13 `BIP005-Q3` → `BIP005-D3`)
   - *Scope:* Separate small BIP adding destructuring `def` over any `HasMembers` value (modules, objects, packages), solving selective import ergonomics without adding a new import statement.
2. **3B — Array/collection indexing & loop ergonomics** ([BIP008](bip008_static-typing.md) §5.1)
   - *Scope:* Dedicated BIP for indexing syntax on `JavaArray(T)` (replacing `java.lang.reflect.Array.get(a, i)`) and Java/Babble collections, alongside iteration/control-flow ergonomics (`for` loops, `break`/`continue`, bare member names in class bodies).
3. **3C — Module search path (`babble.path`) & `std/...` standard library** ([BIP005](bip005_modules.md) §13 `BIP005-Q5` → `BIP005-D5`, [BIP006](bip006_module-sources-and-aot.md) §4.2 `BIP006-P3`)
   - *Depends on:* `BIP006-P3` (`ClassPathModuleSource`), and ideally `BIP007-P1`–`P2`.
   - *Scope:* Configurable module search roots (`babble.path`) and a built-in `std/` library shipped in the jar, offering Babble-idiomatic collection wrappers and higher-order combinators (`map`, `filter`, `reduce`) annotated with `BIP007` effect and algebraic contracts.
4. **3D — Structural object types** ([BIP003](bip003_type-system.md) §6)
   - *Depends on:* `BIP008` and Stage 1C.
   - *Scope:* Dedicated BIP specifying structural member-set types and reconciling them with nominal `ClassShape`, `is` checks (`BIP002-D13`), and JVM interface lowering.

### Stage 4 — Interactive Mode, Build Tooling & Native Packaging

Tooling and packaging increments building on `BIP004` and `BIP006`.

1. **4A — Interactive mode enhancements** ([BIP004](bip004_repl.md) §8)
   - *Scope:* Line editing, history, and completion inside `Repl`; `:load` command to run a file into the session; `:type` / showing the static type of evaluated values (leveraging `BIP008`). Independent of Stages 1–3.
2. **4B — `babble-maven-plugin` & incremental `babblec`** ([BIP001](bip001_bytecode-compiler.md) §9, [BIP006](bip006_module-sources-and-aot.md) §9 & §10 `BIP006-Q8` → `BIP006-D8`)
   - *Depends on:* `BIP006-P4`–`P6`.
   - *Scope:* Separate `babble-maven-plugin` artifact (preserving the single-module classifier `babble-runtime.jar` structure of `BIP006-D1`) exposing `babblec` as a Maven build goal, plus incremental module compilation in `babblec`.
3. **4C — GraalVM native image support** ([BIP006](bip006_module-sources-and-aot.md) §9)
   - *Depends on:* Stage 1C (replacing `Proxy`-based faces and callbacks in compiled code) and `BIP006-P4`–`P6`.
   - *Scope:* Reflection metadata configuration for `Overloads` / Java interop and verification that `babblec`-compiled jars compile to native executables.

### Stage 5 — Thread Safety & Verified Parallelism

Builds on `BIP007`'s effect and ownership guarantees to enable safe parallel execution across both backends.

1. **5A — Thread-safe `Interpreter` & multi-threaded test coverage**
   - *Scope:* Remove `Interpreter`'s mutable `current` scope field so interpreted execution is thread-safe alongside compiled mode, audit runtime caches, and add multi-threaded script tests (allowing multi-threaded scripts such as `examples/raytracer-fable` to run in both modes).
2. **5B — `threadsafe` effect & parallel collection combinators** ([BIP007](bip007_effects.md) §10)
   - *Depends on:* `BIP007-P1`, `BIP007-P2`, Stage 3C (`std/`), and Stage 5A.
   - *Scope:* Add `threadsafe` to `EffectSet` and provide race-free `parallelMap`, `parallelFilter`, `parallelReduce` (`pure deterministic threadsafe associative`, plus `commutative` for work-stealing), and `parallelForEach` (`mut threadsafe commutative idempotent`, plus zero-copy `unique`/`move` channel transfers).
