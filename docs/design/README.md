# Babble Improvement Proposals (BIPs)

Design documents and language proposals for Babble, numbered chronologically by creation date in the git history.

## Naming convention

Items within each proposal use stable identifiers prefixed by the BIP number:

- **`BIP###-Q#`** — open questions (e.g., `BIP007-Q1`). Resolving a question gives a corresponding decision (`BIP###-D#`).
- **`BIP###-D#`** — decision points (e.g., `BIP001-D1`, `BIP002-D3`). Where a decision settles an earlier question, both are linked (e.g., `BIP005-Q1` → `BIP005-D1`, or `BIP001-Q1` → `BIP003-D1`).
- **`BIP###-P#`** — implementation phases (e.g., `BIP001-P0`–`BIP001-P4`, `BIP008-P1`–`BIP008-P7`; sub-phases may carry a letter suffix such as `BIP001-P0a` or `BIP008-P6a`).

Roadmap stages group related work across proposals into sequential milestones:

- **`RM###`** — transversal roadmap stages (e.g., `RM001`, `RM002`). Each `RM` stage bundles multiple BIP items across several topics into a cohesive delivery slice.

## Index

| BIP | Title | Date | Status | Document |
|---|---|---|---|---|
| **BIP001** | Compiling Babble to JVM bytecode | 2026-10-11 | Draft (implemented through `RM001`; `BIP001-D1`–`BIP001-D8` decided; `BIP001-Q1`–`BIP001-Q9` → `BIP003-D1`–`BIP003-D9`; `BIP001-P0a`/`P0b`–`BIP001-P3` and `RM001` typed/unboxed codegen & static dispatch in §10.5/§19 implemented; JVM interfaces in §17 remain for `RM003`; `BIP001-P4` CLI done as `BIP006-P4`–`BIP006-P6`, Maven goal deferred to `BIP006-D8`) | [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) |
| **BIP002** | The Babble object model | 2026-10-11 | Implemented (`BIP002-D1`–`BIP002-D15` decided, resolving `BIP002-Q1`–`BIP002-Q9`; `BIP002-P1`–`BIP002-P6` implemented in interpreter and `BIP001-P1`/`P2`, with §4.11 unboxed `classof` parity landed in `RM001`; JVM interfaces §7.3, direct Java-interface bridges §9.5–§9.6, and `LambdaMetafactory` callback adapters §9.3 remain for `RM003`) | [bip002_object-model.md](bip002_object-model.md) |
| **BIP003** | The type system | 2026-10-24 | Gradual rules, `dynamic`, and internal `BIP003-D9` checks replaced by `BIP008` (`BIP003-D1`–`BIP003-D8` settled `BIP001-Q1`–`BIP001-Q8`; runtime checks `BIP003-D9` remain at `as` casts, Java boundaries, and `setMember`; `RM001` now publishes expression types and primitive storage kinds to the compiler via `ScopeAnalysis`; structural object types in §6 remain) | [bip003_type-system.md](bip003_type-system.md) |
| **BIP004** | The interactive mode | 2026-11-10 | Implemented (`BIP004-D1` nested session scopes; sessions keep one `Modules` per `BIP006-P1`, close inference per input per `BIP008-D5`, and support cross-classloader unboxed fields & `$spec` typed calls across inputs per `RM001`; line editing/history/completion, `:load`, and value type display in §8 remain) | [bip004_repl.md](bip004_repl.md) |
| **BIP005** | Modules | 2026-11-11 | Accepted & implemented (`BIP005-Q1`–`BIP005-Q8` → `BIP005-D1`–`BIP005-D8`; `BIP005-P1`–`BIP005-P5` implemented with `P4`–`P5` via `BIP006`; `RM001` added indexed `Module.exportAt` reads for static imports in §8.3; destructuring `BIP005-D3` and search path/stdlib `BIP005-D5` remain) | [bip005_modules.md](bip005_modules.md) |
| **BIP006** | Module sources and ahead-of-time compilation | 2026-11-14 | Accepted & implemented (`BIP006-Q1`–`BIP006-Q8` → `BIP006-D1`–`BIP006-D8`; `BIP006-P1`–`BIP006-P6` implemented, covering `BIP001-P4` and `BIP005-P4`–`P5`; `babblec` Maven plugin `BIP006-D8`, incremental `babblec`, and GraalVM native image §9 deferred) | [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md) |
| **BIP007** | Function Effects and Algebraic Properties in Babble | 2026-11-14 | Draft (`BIP007-Q1`–`Q4` → `BIP007-D1`–`D4` decided; `BIP007-P1` specified for `RM002`; `BIP007-P2`–`P3` planned for `RM004`; `threadsafe` & parallelization in §10) | [bip007_effects.md](bip007_effects.md) |
| **BIP008** | Removing dynamic typing | 2026-11-16 | Accepted & implemented (`BIP008-Q1`–`BIP008-Q7` → `BIP008-D1`–`BIP008-D7`; `BIP008-P1`–`BIP008-P5`, `BIP008-P6a`, `BIP008-P6b`, `BIP008-P7` implemented; `RM001` delivered typed/unboxed codegen §7.1; `BIP007` is unblocked and array indexing syntax §5.1 remains) | [bip008_static-typing.md](bip008_static-typing.md) |
| **BIP009** | Systematic interpreter and compiler test coverage | 2026-11-17 | Draft (`BIP009-Q1`–`BIP009-Q8` → `BIP009-D1`–`BIP009-D8` proposed; `BIP009-P1`–`BIP009-P4` planned: `.batest` format, leak-free error normalization, and single-command `-Pbatest` dual-JVM JaCoCo coverage harness) | [bip009_test-coverage.md](bip009_test-coverage.md) |

## Cross-BIP baseline

With `BIP001-P0`–`BIP001-P3`, `BIP002-P1`–`BIP002-P6`, `BIP004`, `BIP005-P1`–`BIP005-P5`, `BIP006-P1`–`BIP006-P6`, `BIP008-P1`–`BIP008-P7`, and `RM001` landed, Babble has:

- A shared, `java.base`-only runtime (`BIP001-P0`, `BIP006-P1`) and dual-mode execution (parse-tree `Interpreter` + ASM `CodeGenerator` with `invokedynamic` call/select/Java inline caches).
- Classes, value classes, objects, interfaces with default methods, and two-way Java interop via `Proxy` faces (`BIP002`).
- Nested interactive sessions (`BIP004`), cross-file modules with `ModuleSource` abstractions (`BIP005`, `BIP006-P2`–`P3`), and reproducible AOT jars (`babblec` `-compile`, `BIP006-P4`–`P6`).
- Mandatory static typing (`BIP008`) with `any`/`any?`, `T?`, `A | B`, `?.`, `as`, flow-sensitive narrowing, generics (`(...)`), call-site parameter inference, and `int`→`double` conversion on numeric joins (`BIP008-D7`), eliminating internal `BIP003-D9` runtime checks.
- Typed primitive lowering & static dispatch (`RM001`): unboxed `int`/`double`/`bool` locals, closure cells (`IntCell`/`DoubleCell`/`BoolCell`), compiled class fields, `$spec` entry points, typed `invokedynamic` calls, direct static Java dispatch, indexed module exports, and cross-classloader primitive field inline caches.

Two major next areas remain open: **JVM interface lowering & proxy-free packaging (`RM003`)** (`BIP001` §17, `BIP002` §7.3/§9.3/§9.5, `BIP006` §9) and **effect/ownership analysis & compiler optimizations (`RM002`/`RM004`)** (`BIP007`).

## Roadmap

The stages below organize remaining work and planned phases from `BIP001`–`BIP008` (plus aligned backlog items from the [README](../../README.md)) into **transversal slices** (`RM001`–`RM007`). Rather than isolating work strictly by document, each `RM` stage groups closely coupled items spanning multiple BIPs so they can be designed, implemented, and verified together while keeping `mvn package` green in both execution modes.

### Overview

| Stage | Transversal Slice | Spans (BIPs / Areas) | Prerequisite(s) | Status & Parallelism |
|---|---|---|---|---|
| **RM001** | Typed Primitive Lowering & Static Dispatch | `BIP001` §10.5/§19, `BIP002` §4.11, `BIP003` §6, `BIP004` §8, `BIP005` §8.3, `BIP008` §7.1 | `BIP008-P4`–`P7` | **Implemented** |
| **RM002** | Core Effect & `axiom` Contract Layer | `BIP007` §9.1–§9.2 (`P1`), `BIP002`, `BIP004`, `BIP005`, `BIP006`, `BIP008` | `BIP008-P1`–`P7` | Front-end only (zero `babble-runtime` / `Interpreter` / `CodeGenerator` changes); `BIP007-D1`–`D4` decided; ready to implement |
| **RM003** | JVM Interfaces, Proxy-Free Adapters & Native Image Packaging | `BIP001` §17, `BIP002` §7.3/§9.3/§9.5–§9.6/`P6`/§13, `BIP006` §9 | `RM001`, `BIP006-P4`–`P6` | Unblocked by `RM001`; removing `Proxy` in interfaces & `Faces` directly unblocks GraalVM native images (`BIP006` §9) |
| **RM004** | Ownership Lattice, Bytecode Optimization & Axiom Verification | `BIP007` §9.2 (`P2`–`P3`), `BIP001` §10.5, `BIP003`/`BIP008` (`ScopeAnalysis`, `Narrowing`), `BIP006` (CI) | `RM001`, `RM002` | Connects typed codegen (`RM001`) with effects (`RM002`) |
| **RM005** | Language, Import & Collection Ergonomics + Structural Types | `BIP005-D3`, `BIP008` §5.1, `BIP002`, `BIP003` §6 (+ [README](../../README.md)) | `BIP005-P3`, `BIP008-P6b`–`P7`; `RM003` for structural types | Destructuring, array indexing & loop syntax need small BIPs and can be pulled ahead independently |
| **RM006** | Standard Library, Interactive Mode & Maven/Incremental Tooling | `BIP005-D5`, `BIP006-P3`/`D8`/§9, `BIP001` §9, `BIP004` §8, `BIP007`, `BIP008` | `BIP004`, `BIP006-P3`–`P6`; `RM002`/`RM004`/`RM005` for effect-contracted `std/` | Pure `Repl` UX and `babble-maven-plugin` can be pulled ahead independently |
| **RM007** | Thread-Safe Execution & Verified Parallelism | `BIP007` §10, `BIP001`/`Interpreter`, `BIP006` (+ [README](../../README.md) / [CLAUDE.md](../../CLAUDE.md)) | `RM004`, `RM006` (`std/`) | Interpreter thread-safety fix itself is independent and can land earlier |

---

### RM001 — Typed Primitive Lowering & Static Dispatch *(Implemented)*

Exploits `BIP008`'s non-`dynamic` static types across analysis publication, primitive codegen, Java interop dispatch, and module/session field specialization so `CodeGenerator` eliminates redundant boxing, reflection, and dynamic call fallbacks. See [bip001_bytecode-compiler.md §19](bip001_bytecode-compiler.md#19-design--implementation-notes-rm001--typed-primitive-lowering-and-static-dispatch) for design notes and benchmark results.

- **Spans:** [BIP001](bip001_bytecode-compiler.md) (§10.5, §19), [BIP002](bip002_object-model.md) (§4.11), [BIP003](bip003_type-system.md) (§6), [BIP004](bip004_repl.md) (§8), [BIP005](bip005_modules.md) (§8.3), [BIP008](bip008_static-typing.md) (§7.1)
- **Depends on:** `BIP008-P4`–`P7`
- **Landed across topics:**
  - *Analysis publication (`BIP003` §6, `BIP008` §7.1):* `TypeChecker` publishes converged `PrimitiveKind`, definite-initialization flags, and static Babble/Java/Module targets into `ScopeAnalysis` (and freezes types in `AnalysisSession`), superseding a standalone AST `Unboxing` pass.
  - *Primitive codegen (`BIP001` §10.5/§19, `BIP002` §4.11):* Unboxed non-nullable `int`, `double`, and `bool` locals, closure cells (`IntCell`/`DoubleCell`/`BoolCell`), and class fields (`$defined` bitmask for primitive body `def`s); primitive JVM arithmetic and comparisons (`Double.compare` for NaN/signed-zero parity); `$spec` entry points (`fn$N$name$spec`, `m$name$spec`) alongside `$direct` and `$call`; typed `Bootstrap.call` sites; type-guarded `int` constant folding; boxed `classof(1)`/`classof(1.5)`/`classof(true)` parity (`BIP002` §4.11).
  - *Static Java dispatch (`BIP001` §10.5):* Direct `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE`/`NEW`/`ARRAYLENGTH`/field `GET*`/`PUT*` with `Values.unwrap`, `JavaTypes.checkBoxed`/`wrapInvocationException`, and `Types.check` generic boundary parity.
  - *Static imports & session fields (`BIP005` §8.3, `BIP004` §8):* Indexed `Module.exportAt(index)` reads for non-cyclic static imports, and cross-classloader primitive field getters in `Bootstrap.SelectCache` (`MethodHandles.privateLookupIn`).
- **Verification:** Dual-mode `mvn -B package` (`interpret` + `compile`/`CheckClassAdapter`, including `RuntimeBoundaryTestCase`), bytecode/unit/differential/REPL tests, `-format -check`, byte-identical `examples/raytracer-argon/scene.ppm` (`59.3 s` → `46.1 s`), `raytracer-fable` (`72.9 s` → `67.9 s` wall / `65.9 s` render), and `Benchmarks` (`while loop` `0.1 ms` / `769.4x` vs interpret, `fib(25)` `1.7 ms` / `71.4x`, `ArrayList` `2.0 ms` / `13.3x`).


### RM002 — Core Effect & `axiom` Contract Layer

Lands the complete static effect & algebraic-property front end (`BIP007-P1`, `BIP007-D1`–`D4`) across grammar, formatting, `TypeChecker`, `value class` & interface conformance, cross-module export, interactive sessions, and host Java boundaries—100% front-end with zero `babble-runtime`, `Interpreter`, or `CodeGenerator` changes.

- **Spans:** [BIP007](bip007_effects.md) (§9.1, §9.2 `BIP007-P1`), [BIP002](bip002_object-model.md) (§4.6, §6.2), [BIP004](bip004_repl.md) (`AnalysisSession`), [BIP005](bip005_modules.md) (`ModuleInterface`), [BIP006](bip006_module-sources-and-aot.md) (`babble-runtime` boundary), [BIP008](bip008_static-typing.md) (`Narrowing`, call-site inference)
- **Depends on:** `BIP008-P1`–`P7` *(100% front-end; `unique`/`move`/`freeze`/`frozen`, `@TERMINATING`, bytecode optimizer passes, and unused `pure nothrow` compile errors are deferred to `RM004`)*
- **Decisions resolved (`BIP007-D1`–`D4`):**
  - `BIP007-D1` (`Q1`): Flow-sensitive `Narrowing` (`!= null`, `is T`) establishes `nothrow` on guarded branches and short-circuit `and`/`or` right-hand sides; `RM004` optimizers never reorder narrowed predicates ahead of their guards.
  - `BIP007-D2` (`Q2`): Unannotated `export def` exports its inferred `EffectSet` and parameter flags via `ModuleInterface`; exports with explicit effect modifiers or `impure` expose only their declared contract.
  - `BIP007-D3` (`Q3`): `associative` takes no `identity(...)` clause in `RM002` (combiners take explicit seeds as in `BIP007` §8.4).
  - `BIP007-D4` (`Q4`): `axiom` stays strictly at declaration/function granularity (`def f = axiom ... ->`, `axiom ... Target`, `def C = axiom ... import("...")`, `export axiom ...`); inline `axiom (...)` expression blocks are forbidden.
- **Deliverables across topics:**
  - *Syntax & formatting (`BIP007` §4.1):* Contextual `ID` predicates in `Babble.g4` (`axiom`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `impure`) on function literals, parameter declarations, function types, and declaration-level `axiom`/`impure` rules (`Class()` constructor-only targets vs bare `Class`), plus canonical tight-colon `Formatter` rules (`(mut x: T): R -> ...` and `Class()`). (`unique`, `move`, `freeze`, and `frozen` syntax await `RM004`.)
  - *Effect & algebraic checker (`BIP007-P1`, `BIP008`, `BIP002`):* `EffectSet` and `Type.Parameter(..., mut, borrow)`; SCC bottom-up fixed-point inference and verification for `{pure, hermetic, mut, borrow, deterministic, nothrow}` integrated with `BIP008` call-site inference, generics, and `Narrowing`; infer `mut` on `this` and parameters **only** on completely untyped functions (no effect modifiers, parameter types, or `: R` return type)—typed functions require explicit `mut`, migrating existing `.ba` scripts/examples; `@PARAM`/`@THIS` local alias tracking in `TypeChecker` (local `def` reassignments stay valid in `pure`, while mutating fresh local objects in `pure`/`hermetic` awaits `unique` in `RM004`); static-only call-site `mut` exclusivity in `TypeChecker` (same bindings, tracked aliases, identical member paths; runtime `Values.same(a, b)` fallback deferred to `RM004`); compositional algebraic properties and signature checks; frame-local `borrow` closures attributing unescaped local `def`s; bounded call-site effect specialization for statically resolved concrete Babble callables; `value class` constructor & standard protocol (`equals`/`hashCode`/`toString`/`compareTo`) defaults + immutable non-`mut` `string` receivers (other JDK methods remain unconstrained unless declared via `axiom` or host registration); effect-aware `Type.Function` subtyping and interface override conformance (`actual <: required`).
  - *Module, session & Java boundaries (`BIP005`, `BIP004`, `BIP006`, `BIP007` §6):* File/`package` `axiom` and `impure` declarations (`Class()` constructor-only targets vs bare `Class` for all public constructors and methods); exported `axiom` and effect tables in `ModuleInterface`; session persistence in `AnalysisSession`; public `org.babblelang.effects` package (`Effect` enum and `@Pure`, `@Hermetic`, `@Mut`, `@Borrow`, `@Deterministic`, `@Nothrow`, `@Idempotent`, `@Commutative`, `@Associative`, `@Monotone`, `@Injective`, `@Impure`) and per-engine `BabbleScriptEngine` registries (`allowAxioms`, `assumeEffects`, `assumeConstructorEffects`, `markImpure`).
- **Exit criteria:** Zero effect machinery in `org.babblelang.runtime` or `org.babblelang.engine.impl.natives` (`RuntimeBoundaryTestCase` stays green); zero `Interpreter`/`CodeGenerator` changes; existing `.ba` scripts/examples migrated where typed functions mutate parameters/`this`; `FormatterTestCase`, `TypeCheckerTestCase`, `BabbleModulesTestCase`, `ReplTestCase`, and `DifferentialTestCase` pass.

### RM003 — JVM Interfaces, Proxy-Free Adapters & Native Image Packaging

Lowers Babble interfaces and Java interop adapters from runtime wrappers and `java.lang.reflect.Proxy` objects to native JVM interfaces, direct `implements` bridges, and `LambdaMetafactory` adapters—removing the main runtime reflection/proxy barrier to GraalVM native images.

- **Spans:** [BIP001](bip001_bytecode-compiler.md) (§17), [BIP002](bip002_object-model.md) (§7.3, §9.3, §9.5–§9.6, §12 `BIP002-P6`, §13), [BIP006](bip006_module-sources-and-aot.md) (§9)
- **Depends on:** `RM001` and `BIP006-P4`–`P6`
- **Deliverables across topics:**
  - *JVM interface compilation (`BIP001` §17, `BIP002` §7.3):* Compile Babble `interface` expressions to real JVM interfaces with abstract member accessors/methods and JVM `default` methods (`INVOKEINTERFACE` dispatch, `INSTANCEOF` for `is`, `INVOKESPECIAL` for `super(I).m()`, hoisting nested-lambda `super` calls into synthetic instance methods).
  - *Direct class `implements` & callback adapters (`BIP002` §9.3, §9.5–§9.6, `BIP002-P6`, §13):* Emit direct `implements` clauses and exact-signature overload bridge methods on generated Babble JVM classes for both Babble and Java interfaces, retiring `Proxy` faces for compiled instances; replace `Proxy`-based functional-interface adapters in `Faces` with cached `LambdaMetafactory` adapters (`BIP002` §9.3).
  - *GraalVM native image support (`BIP006` §9):* Pair the removal of compiled `Faces` `Proxy` objects with GraalVM reflection metadata configuration for `Overloads` / Java interop, and verify that `babblec`-compiled jars build into standalone native executables.
- **Exit criteria:** Dual-mode `BabbleObjectModelTestCase` and `BabbleJavaObjectsTestCase` parity preserved; `CheckClassAdapter` clean; `babblec`-produced jars pass native-image smoke checks.

### RM004 — Ownership Lattice, Bytecode Optimization & Axiom Verification

Joins the typed compilation pipeline (`RM001`) with the static effect/axiom model (`RM002`) to deliver `unique`/`move`/`freeze` ownership tracking, `@TERMINATING`, effect- and type-driven bytecode optimization passes (`BIP007-P2`), algebraic rewrites, ASM Java certification, and CI axiom fuzzing (`BIP007-P3`).

- **Spans:** [BIP007](bip007_effects.md) (§9.2 `BIP007-P2` & `BIP007-P3`), [BIP001](bip001_bytecode-compiler.md) (§10.5), [BIP003](bip003_type-system.md) (`ScopeAnalysis`), [BIP008](bip008_static-typing.md) (`Narrowing` guard rules & `Type.Frozen`), [BIP006](bip006_module-sources-and-aot.md) (CI verification)
- **Depends on:** `RM001` and `RM002` *(also leverages `RM003` where interface methods carry effect/ownership contracts)*
- **Deliverables across topics:**
  - *Ownership, Java annotation & termination analysis (`BIP007-P2`):* Add contextual `unique`, `move`, `freeze`, and `frozen` syntax (`Babble.g4`/`Formatter`), `Type.Parameter(..., unique)`, `Type.Frozen`, and `@Unique` in `org.babblelang.effects`; full `unique`/`@PARAM(i)`/`@THIS`/`@MOVED`/`@EXTERNAL` lattice enabling mutation of fresh `unique` local objects inside `pure`/`hermetic` functions, sub-reference tracking, control-flow merge joins, definite-unassignment, implicit last-use `move`, purely static `Type.Frozen` (`freeze` zero-cost identity no-op in both backends), optional runtime `Values.same(a, b)` `mut` exclusivity fallback, and `@TERMINATING` inference.
  - *Effect-driven bytecode optimizer & diagnostics (`BIP007-P2`, `BIP001` §10.5, `BIP008`):* Publish `EffectSet`, `borrow`, `unique`, and `@TERMINATING` metadata in `ScopeAnalysis`; add bytecode passes in `CodeGenerator` / compiler optimizer passes: CSE & typed/effect-guarded constant folding (`BIP001` §10.5), `unique mut` no-alias load hoisting, `borrow` scalar replacement/unboxing, zero-copy `freeze` & FBIP in-place reuse, guard-safe predicate reordering/hoisting (`BIP007-D1`), dead-expression elimination (plus compile-time errors on unused `pure nothrow` source expressions), and `associative` loop reassociation.
  - *Algebraic rewrites, ASM suggester & fuzzing (`BIP007-P3`):* `monotone` range inversion and `injective` projection push-down; opt-in ASM bytecode analyzer (with escape analysis and seeded JVM `native` intrinsics) to suggest/certify effects on closed Java helpers; `babble.checkAxioms` property-based fuzzing of `axiom` declarations in CI.
- **Exit criteria:** Compile-error goldens in `TypeCheckerTestCase`, bytecode differential parity across unoptimized vs optimized builds, `argon` byte-identical `scene.ppm`, and passing `babble.checkAxioms` fuzz runs.

### RM005 — Language, Import & Collection Ergonomics + Structural Types

Lands the deferred follow-up language proposals from `BIP003`, `BIP005`, `BIP008`, and the ray-tracer backlog to streamline selective module imports, array/collection indexing, loops, class-body member references, structural typing, and error reporting.

- **Spans:** [BIP005](bip005_modules.md) (§13 `BIP005-Q3` → `BIP005-D3`), [BIP008](bip008_static-typing.md) (§5.1), [BIP002](bip002_object-model.md) (class body & iteration ergonomics), [BIP003](bip003_type-system.md) (§6), [README](../../README.md) (planned language items & ray-tracer lessons)
- **Depends on:** `BIP005-P3` and `BIP008-P6b`–`P7`; `RM003` specifically for structural object types *(destructuring `def`, array indexing, and loop/member ergonomics are independent of `RM001`–`RM004` and can be pulled ahead anytime)*
- **Design step first:** Small follow-up BIPs for destructuring `def` (`BIP005-D3`), indexing & iteration syntax (`BIP008` §5.1), and structural object types (`BIP003` §6 reconciled with `BIP002-D13` & `RM003`).
- **Deliverables across topics:**
  - *Destructuring definitions (`BIP005-D3`):* `def (Circle, unit) = expr` over any `HasMembers` value (`Module`, `BabbleInstance`, package `Namespace`), solving selective module import ergonomics without adding an import declaration.
  - *Indexing, loops & class-body ergonomics (`BIP008` §5.1, `BIP002`, [README](../../README.md)):* Indexing syntax on `JavaArray(T)` (replacing `java.lang.reflect.Array.get(a, i)`) and Java/Babble collections, `for` loops over ranges/iterators, `yield` iterator functions, `break`/`continue`, and bare member names inside class bodies.
  - *Structural object types & clearer diagnostics (`BIP003` §6, `BIP008`, [README](../../README.md)):* Structural member-set types aligned with nominal `ClassShape`, runtime `is` checks (`BIP002-D13`), and JVM interface lowering (`RM003`), plus more user-friendly syntax/type error messages.
- **Exit criteria:** New `.ba` specs, `FormatterTestCase` round-trips, `TypeCheckerTestCase` diagnostics, and `DifferentialTestCase` parity across both modes.

### RM006 — Standard Library, Interactive Mode & Maven/Incremental Tooling

Brings together shipped stdlib modules, search-path resolution, interactive session workflow, and Maven/incremental build tooling into a cohesive developer experience.

- **Spans:** [BIP005](bip005_modules.md) (§13 `BIP005-Q5` → `BIP005-D5`), [BIP006](bip006_module-sources-and-aot.md) (§4.2 `BIP006-P3`, §9, §10 `BIP006-Q8` → `BIP006-D8`), [BIP001](bip001_bytecode-compiler.md) (§9), [BIP004](bip004_repl.md) (§8), [BIP007](bip007_effects.md) (effect contracts), [BIP008](bip008_static-typing.md)
- **Depends on:** `BIP004` and `BIP006-P3`–`P6`, plus `RM002`/`RM004`/`RM005` for an effect-contracted `std/...` collection library *(`Repl` UX upgrades and `babble-maven-plugin` are independent and can be pulled ahead anytime)*
- **Deliverables across topics:**
  - *Module search path & `std/...` library (`BIP005-D5`, `BIP006-P3`, `BIP007`):* Configurable module search roots (`babble.path`) and a built-in `std/...` standard library loaded via `ClassPathModuleSource`, providing mapped collection wrappers and higher-order combinators (`map`, `filter`, `reduce`) annotated with `RM002`/`RM004` effect and algebraic contracts.
  - *Interactive mode upgrades (`BIP004` §8, `BIP008`, `RM002`):* Line editing, history, and tab completion inside `Repl`; `:load` command to execute a file into the live session; `:type` / value type display surfacing inferred `BIP008` types (and `RM002` effects when available).
  - *Maven plugin & incremental AOT builds (`BIP001` §9, `BIP006-D8`, `BIP006` §9):* Separate `babble-maven-plugin` artifact (preserving the single-module `babble-<version>-runtime.jar` classifier split of `BIP006-D1`) exposing `babblec` as a Maven goal, alongside incremental module compilation in `babblec`.
- **Exit criteria:** `ReplTestCase` transcripts, `BabblecTestCase` / `FileModulesTestCase` tests covering `babble.path`, `std/...`, and incremental rebuilds, plus `babble-maven-plugin` integration verification.

### RM007 — Thread-Safe Execution & Verified Parallelism

Eliminates shared mutable state in the interpreter and extends `BIP007` + `std/...` with effect-verified, lock-free parallel combinators across both backends.

- **Spans:** [BIP007](bip007_effects.md) (§10), [BIP001](bip001_bytecode-compiler.md) / `Interpreter` parity, [BIP006](bip006_module-sources-and-aot.md) (runtime thread safety), [README](../../README.md) & [CLAUDE.md](../../CLAUDE.md) (ray-tracer concurrency findings)
- **Depends on:** `RM004` and `RM006` (`std/`) *(making `Interpreter` thread-safe and testing runtime caches concurrently can be pulled ahead independently at any point)*
- **Deliverables across topics:**
  - *Thread-safe `Interpreter` & concurrency suite ([CLAUDE.md](../../CLAUDE.md), [README](../../README.md)):* Remove `Interpreter`'s mutable `current` scope field so interpreted evaluation is thread-safe alongside compiled mode, audit `Bootstrap`/`Overloads`/`Modules` caches under concurrent execution, and add multi-threaded dual-mode tests (enabling multi-threaded scripts such as `examples/raytracer-fable` to run in both modes).
  - *`threadsafe` effect & parallel `std/` combinators (`BIP007` §10):* Add `threadsafe` to `EffectSet` and ship race-free `parallelMap`/`parallelFilter` (`pure threadsafe` callbacks over `frozen T`, deep `value class`, or `unique borrow` inputs), `parallelReduce` (`pure deterministic threadsafe associative`, plus `commutative` for work-stealing), and `parallelForEach` (`mut threadsafe commutative idempotent` updates plus zero-copy `unique`/`move` transfers).
- **Exit criteria:** Multi-threaded `DifferentialTestCase` coverage, verified race-freedom checks on `threadsafe` parallel combinators, and full dual-mode `mvn package` green.
