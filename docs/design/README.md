# Babble Improvement Proposals (BIPs)

Design documents and language proposals for Babble, numbered in the order they were created. This page indexes them, records which milestones delivered them, and sequences what remains. The details live in each BIP.

## Conventions

Items inside a proposal carry stable identifiers prefixed by its number:

- **`BIP###-Q#`**: an open question. Settling it gives a decision.
- **`BIP###-D#`**: a decision. When it settles an earlier question, both are cited (`BIP005-Q1` → `BIP005-D1`, or across BIPs, `BIP001-Q1` → `BIP003-D1`).
- **`BIP###-P#`**: a **planned** feature or implementation phase (sub-phases take a letter: `BIP###-P0a`).
- **`BIP###-I#`**: an **implemented** one.

Always write identifiers in full (`BIP031-P2`, not `P2`) so they stay searchable. When a planned phase lands, rewrite every reference to it across the repository, from `BIP###-P#` to `BIP###-I#`, with the script:

```bash
python3 src/tools/mark_bip_implemented.py --dry-run --diff BIPNNN-P1..P3   # preview
python3 src/tools/mark_bip_implemented.py BIPNNN-P1 BIPNNN-P2              # apply
python3 src/tools/mark_bip_implemented.py --list-planned                    # what remains
```

It handles single references, sub-phases, ranges (splitting them when only part is promoted) and lists sharing a prefix, and leaves Java identifiers such as `Bip020P1PlainClassesTestCase` alone.

**`RM###`** milestones group work from several BIPs into one delivery. Outside this page, only **implemented** milestones may be mentioned, except in the commit or PR description that implements a milestone. Everywhere else (code, tests, other docs and BIPs, unrelated commits and PRs), refer to `BIP###` / `BIP###-P#` / `BIP###-I#` / `BIP###-D#` instead, so the future part of the roadmap can be merged, split or reordered freely.

## Where Babble stands

- **Execution:** a tree-walking interpreter (the reference implementation) and a compiler to JVM bytecode through a typed `HIR` (`-O0`, the `-O1` default) and an SSA `MIR` tier (`-O2`), ahead-of-time jars (`babblec`), and WebAssembly 3.0, all over a `java.base`-only runtime.
- **Language:** classes, `record`s, objects and interfaces with two-way Java interop; modules; `for` loops with `break` and `continue`; `try`/`catch`/`finally`; optional, named, variadic and overloaded arguments; self tail calls; immutable `def` and mutable `var`; multiline and raw strings.
- **Types:** mandatory static typing with nullables (`T?`, `T!`), unions, intersections, generics, aliases and flow-sensitive narrowing, over nine core scalars, five core collections and `array of T`.
- **Effects:** inferred, pure-by-default effects and algebraic properties, ownership (`unique`, `frozen`), termination checking, and curated or bytecode-inferred axioms for Java code.
- **Tooling:** an interactive mode, a formatter, and `.batest.md` specifications run in every mode, with IR goldens and a coverage gate of at least 80% of lines and branches.

## BIP index

### Implemented

| BIP | Title | Date | Landed in | Summary |
|---|---|---|---|---|
| **BIP001** | [Compiling Babble to JVM bytecode](bip001_bytecode-compiler.md) | 2026-10-11 | `RM001`, `RM003` | The ASM compiler, then typed unboxed code and static dispatch, and JVM interfaces. Its CLI landed as `BIP006-I4`–`I6`; the Maven goal is in the backlog (`BIP006-D8`). |
| **BIP002** | [The Babble object model](bip002_object-model.md) | 2026-10-11 | `RM003`, `RM006` | Classes, `record`s, objects, interfaces with default methods, and two-way Java interop. |
| **BIP003** | [The type system](bip003_type-system.md) | 2026-10-24 | — | Superseded by `BIP008`, except the runtime checks of `BIP003-D9` at `as` casts and Java boundaries. Structural object types (§6) are in the backlog. |
| **BIP004** | [The interactive mode](bip004_repl.md) | 2026-11-10 | — | Nested session scopes with per-input inference, line editing, history, completion, `:load` and `:type`. |
| **BIP005** | [Modules](bip005_modules.md) | 2026-11-11 | — | File modules with `export` and `import`. Destructuring (`BIP005-D3`) and a search path (`BIP005-D5`) are in the backlog. |
| **BIP006** | [Module sources and ahead-of-time compilation](bip006_module-sources-and-aot.md) | 2026-11-14 | — | `ModuleSource`s and `babblec`, whose reproducible jars run on a plain JRE. A Maven plugin, incremental builds and GraalVM native images are in the backlog. |
| **BIP007** | [Function effects and algebraic properties](bip007_effects.md) | 2026-11-14 | `RM002`, `RM007` | Inferred and checked effects (`pure`, `nothrow`, …) and algebraic properties (`idempotent`, `associative`, …), `axiom` contracts, `unique`/`frozen` ownership, `@TERMINATING`, and axiom fuzzing. `threadsafe` (§10) awaits `RM017`. |
| **BIP008** | [Removing dynamic typing](bip008_static-typing.md) | 2026-11-16 | — | Mandatory static typing: `any`, `T?`, unions, narrowing, generics and call-site inference. Indexing syntax (§5.1) is in the backlog. |
| **BIP009** | [Systematic interpreter and compiler test coverage](bip009_test-coverage.md) | 2026-11-17 | — | Differential specifications run in every mode, behind a JaCoCo coverage gate (`-Pbatest`). `BIP029` redesigned the format. |
| **BIP010** | [Simplify and refactor the type checker](bip010_type-checker-simplification.md) | 2026-11-18 | `RM004` | Split `TypeChecker` into focused collaborators (`TypeAlgebra`, `CallSolver`, `SlotInference`, …), from 6,828 to 2,154 lines, with no change in behavior. |
| **BIP011** | [Babble core types](bip011_core-types.md) | 2026-11-18 | `RM005` | Nine core scalars, five `java.util` collections plus `entry`, Java nullability alignment, and invariant mutable generics with `borrow` covariance. |
| **BIP012** | [Babble grammar improvements](bip012_grammar-improvements.md) | 2026-11-19 | `RM006` | A predicate-free LL(1)/LL(2) grammar (`fn`, `of` generics, `record`) parsed in pure `SLL`. |
| **BIP013** | [Iterator support and `for` loops](bip013_iterators.md) | 2026-11-19 | `RM009` | `for x in xs then (...)` over arrays, `Iterable`s, `Iterator`s and Babble iterables, with `break [expr]` and `continue`. |
| **BIP016** | [Consolidating effect annotations](bip016_consolidating-effect-annotations.md) | 2026-11-19 | `RM008` | Pure-by-default effects with a small opt-out vocabulary (`io`, `volatile`, `mut`, `shared`, `unique`), and return-through-borrow. |
| **BIP017** | [Higher-order function effects (`fn calling f`)](bip017_higher-order-function-effects.md) | 2026-11-19 | `RM009` | `fn calling f` forwards a callback's effects through higher-order functions, across modules, interfaces and function values. |
| **BIP019** | [Refactoring Babble axioms](bip019_refactoring-axioms.md) | 2026-11-20 | — | Every built-in assumption moved into `src/main/java/org/babblelang/axioms/`, with structured `axiom` syntax and a bytecode effect analyzer on by default. |
| **BIP020** | [Array types](bip020_array-types.md) | 2026-11-21 | — | `array of T` over plain JVM arrays, and compiled classes that extend `java.lang.Object`. |
| **BIP021** | [Optional, variable, generic and overloaded arguments](bip021_optional-variadic-overloaded-arguments.md) | 2026-11-21 | — | Optional, named and keyword-only arguments, Java varargs, generic functions, `xs: T...` and `...e` spread, and closed `overload` sets. |
| **BIP022** | [Intersection types](bip022_intersection-types.md) | 2026-11-21 | `RM011` | `A & B` over interfaces, with narrowing, overload meets and Java multi-bound type variables. |
| **BIP023** | [Type aliases](bip023_type-aliases.md) | 2026-11-21 | — | Transparent, generic `def Name = type T` aliases. |
| **BIP024** | [Self tail calls](bip024_tail-calls.md) | 2026-11-22 | — | Self tail calls in constant stack space, and `forever` to acknowledge code that never ends. |
| **BIP025** | [Compiling Babble to WebAssembly](bip025_compile-to-wasm.md) | 2026-11-22 | `RM018` | Whole-program compilation to WebAssembly 3.0 (WasmGC) for Node.js and browsers, passing every portable `.batest.md` case and rendering Argon byte for byte. |
| **BIP027** | [Tackling complexity in the compiler](bip027_tackling-complexity-in-the-compiler.md) | 2026-11-22 | `RM012` | Broke up the compiler's monoliths under 2,000-line ceilings and rebuilt effect, algebraic and termination analysis on a shared e-graph. |
| **BIP028** | [Exception handling (`try`, `catch`, `finally`)](bip028_exception-handling.md) | 2026-11-23 | — | Expression-oriented `try`/`catch`/`finally`/`throw`, a core `exception` type, and direct JVM exception propagation. |
| **BIP029** | [`.batest.md` v2 format and unified HIR/MIR golden testing](bip029_batest-v2-and-ir-goldens.md) | 2026-11-23 | — | The Markdown-based test format, with canonical `HIR`, `MIR` and `wat` golden assertions. |
| **BIP030** | [Axiom nullability and inferred effects](bip030_axiom-nullability-and-inferred-effects.md) | 2026-11-23 | — | `class notnull` and `T!` in axioms, so curated nullness no longer hides inferred effects. |
| **BIP034** | [Immutable `def` and mutable `var` bindings](bip034_immutable-def-and-mutable-var.md) | 2026-11-25 | — | Non-reassignable `def` and explicit `var`, which make top-level reads pure, let closures capture by value, and allow `ACC_FINAL` fields. |

### In progress

| BIP | Title | Date | Status | Summary |
|---|---|---|---|---|
| **BIP014** | [Compiler optimization passes](bip014_optimization-passes.md) | 2026-11-19 | Partially implemented | `HIR` and the `-O1` optimizer (`RM010`), the e-graph core (`RM012`), and `record` optimizations and the `-O2` SSA `MIR` tier (`RM013`) landed. Only `BIP014-P5` Half B, pipeline rewrites over an SSA `aegraph`, remains (`RM015`). |
| **BIP015** | [Down with the ants](bip015_down-with-the-ants.md) | 2026-11-19 | Draft (`BIP015-Q1`–`Q8` open) | Whether to keep ANTLR4, put a Babble-owned AST behind it, or replace it, for example with a hand-written parser. Decided first in `RM014`. |
| **BIP018** | [Tracking Java improvements](bip018_tracking-java-improvements.md) | 2026-11-20 | Decided (`BIP018-P0`–`P4` planned) | Babble editions that track JDK LTS releases, a multi-module split, a Class-File API backend and `babblec -target N`. Starts once `BIP014` is complete (`RM016`, `RM017`). |
| **BIP026** | [Multiline strings and string templates](bip026_multiline-strings-and-string-templates.md) | 2026-11-22 | Partially implemented | Multiline and raw strings landed (`BIP026-I1`). String templates (`t"..."`, lazy placeholders, evaluation policies) are open (`BIP026-Q4`–`Q10` → `BIP026-P2`–`P5`, `RM014`). |
| **BIP031** | [First-class ranges (`range`), modulo (`mod`) and integer division (`//`)](bip031_ranges.md) | 2026-11-24 | Decided (`BIP031-P1`–`P3` planned) | `range of T` (`lo..hi`, `lo..<hi`), floored `mod` and `//`, and allocation-free counted loops (`RM019`). |
| **BIP032** | [The Great Escape — adversarial search for inconsistencies in Babble types and effects](bip032_the-great-escape.md) | 2026-11-24 | Decided (`BIP032-P1`–`P4` planned) | Red-team probes, white-box property tests, runtime canary oracles and a delta-minimizing soundness fuzzer across types, effects, ownership, optimizer/Wasm parity, axioms and verifiers (`RM021`). |
| **BIP033** | [Tensors as core types with dimensional safety](bip033_tensors.md) | 2026-11-25 | Decided (`BIP033-P1`–`P6` planned) | Core `float16`/`bfloat16` scalars and immutable `tensor of (DType, ...)` types with static/symbolic/dynamic/labeled shapes, E-graph algebraic dim solving, scalar + labeled-subset broadcasting, `matmul`/`einsum`, `unique` FBIP reuse, and a `java.base` strided runtime behind `TensorBackend` SPI (`RM023`). |
| **BIP035** | [`babblebabble` : a Babble interpreter written in Babble](bip035_babblebabble.md) | 2026-11-25 | Draft (`BIP035-Q1`–`Q11` open ; `BIP035-P1`–`P6` planned) | First-class pure-Babble interpreter in `src/main/babble/babblebabble/` (zero `java.*` core, hand-written lexer/Pratt parser/evaluator, `Mode.BABBLEBABBLE`) integrated with `DifferentialTestCase` and `BatestTestCase`, targeting byte-identical Argon, meta-circular "`babblebabblebabble`", and `BIP025` Wasm playground compilation (`RM022`). |
| **BIP036** | [Compiler simplification, front-end data model and god-class splits](bip036_compiler-simplification-and-god-class-splits.md) | 2026-11-25 | Decided (`BIP036-P1`–`P9` planned) | A lifetime-separated front-end data model (`FileFacts`, single-source `SlotInference`, fresh-per-walk `TypingPass`, `DiagnosticSink`) that retires `TypeChecker.resetPass()`, pure splits of every monolith and god class (JVM and Wasm backends included), a repository-wide 1,500-line ceiling, and clean seams ahead of `BIP015` Option 2 (`RM020`). |

## Milestones done

Each milestone was verified in every execution mode of its time, with a byte-identical render of `examples/raytracer-argon/scene.ppm`.

#### RM001 — Typed primitive lowering & static dispatch

Static types drive unboxed locals, fields and closure cells, `$spec` entry points and direct Java dispatch: Argon rendered in 46.1 s instead of 59.3 s. Notes in [BIP001 §19](bip001_bytecode-compiler.md#19-design--implementation-notes-rm001--typed-primitive-lowering-and-static-dispatch).

- Spans: `BIP001`, `BIP002`, `BIP003`, `BIP004`, `BIP005`, `BIP008`

#### RM002 — Core effect & `axiom` contract layer

Effect and algebraic-property inference and checking (`BIP007-I1`, `BIP007-D1`–`D4`), entirely in the front end.

- Spans: `BIP007`, `BIP002`, `BIP004`, `BIP005`, `BIP006`, `BIP008`

#### RM003 — JVM interfaces & proxy-free adapters

Babble interfaces compile to real JVM interfaces, compiled classes implement Java interfaces directly, and cached `LambdaMetafactory` adapters replace `Proxy`. Notes in [BIP001 §20](bip001_bytecode-compiler.md#20-design--implementation-notes-rm003--jvm-interfaces-java-interface-bridges-and-lambdametafactory-sam-adapters).

- Spans: `BIP001`, `BIP002`, `BIP006`

#### RM004 — Type-checker simplification & modular architecture

`TypeChecker` split into focused collaborators with no change in behavior (`BIP010-I1`–`I6`). Notes in [BIP010 §7](bip010_type-checker-simplification.md#7-implementation-notes-rm004).

- Spans: `BIP010`, `BIP001`, `BIP004`, `BIP005`, `BIP007`, `BIP008`, `BIP009`

#### RM005 — Core scalar & collection types, Java nullability

The core type roster, Java nullability alignment and unboxing of all eight JVM primitives (`BIP011-I1`–`I6`).

- Spans: `BIP011`, `BIP001`, `BIP002`, `BIP007`, `BIP008`, `BIP009`, `BIP010`

#### RM006 — Predicate-free grammar

Removed all 10 semantic predicates and the custom lookahead code from `Babble.g4`, for pure `SLL` parsing (`BIP012-I1`–`I4`).

- Spans: `BIP012`, `BIP002`, `BIP007`, `BIP008`, `BIP009`, `BIP011`

#### RM007 — Ownership, `frozen` types & axiom verification

`unique` and `frozen`, `move`/`freeze`/`discard`, `@TERMINATING`, errors for unused pure expressions, the bytecode effect analyzer and axiom fuzzing (`BIP007-I2`–`I3`).

- Spans: `BIP007`, `BIP006`, `BIP008`, `BIP010`, `BIP011`, `BIP012`

#### RM008 — Pure-by-default effects & return-through-borrow

The pure-by-default effect syntax (`BIP016-I1`–`I4`), which took Argon from 439 effect keywords to 28.

- Spans: `BIP016`, `BIP006`, `BIP007`, `BIP009`, `BIP010`, `BIP011`, `BIP012`

#### RM009 — Iterators, loop control & higher-order function effects

`for` loops, `break [expr]` and `continue` (`BIP013-I1`–`I4`), and `fn calling f` (`BIP017-I1`–`I3`).

- Spans: `BIP013`, `BIP017`, `BIP010`, `BIP011`, `BIP012`, `BIP016`

#### RM010 — Typed `HIR` compiler & Nanopass optimizer

Replaced `CodeGenerator` with a typed `HIR` and `HirEmitter`, and made `-O1` the default (`BIP014-I0`–`I2`): 3.8× faster on small helper calls, 2.1× on loop-invariant pure calls.

- Spans: `BIP014`, `BIP001`, `BIP006`, `BIP007`, `BIP009`, `BIP010`, `BIP011`, `BIP013`, `BIP016`, `BIP017`

#### RM011 — Intersection types & multiline strings

Completed intersection types (`BIP022-I2`) and added multiline and raw strings (`BIP026-I1`).

- Spans: `BIP022`, `BIP026`, `BIP012`, `BIP021`

#### RM012 — Compiler modularity & algorithmic modernization

Broke up `CallSolver`, `HirEmitter` and `EffectChecker`, added the shared e-graph (Half A of `BIP014-P5`), and modernized effect, algebraic and termination analysis (`BIP027-I1`–`I6`).

- Spans: `BIP027`, `BIP014`, `BIP007`, `BIP009`, `BIP016`, `BIP017`, `BIP019`

#### RM013 — `record`-centric optimizations & SSA `MIR`

Scalar replacement and reuse of `record`s at `-O1` (`BIP014-I4`), and the per-function SSA `MIR` tier at `-O2` (`BIP014-I3`): a `record` dot-product loop runs 1.8× faster than at `-O1`.

- Spans: `BIP014`, `BIP002`, `BIP007`, `BIP011`, `BIP016`, `BIP024`, `BIP028`, `BIP029`

#### RM018 — WebAssembly 3.0 backend

Whole-program compilation from `HIR` to WebAssembly 3.0 (`BIP025-I0`–`I5`), with every portable `.batest.md` case passing on Node.js and Argon rendered byte for byte.

- Spans: `BIP025`, `BIP009`, `BIP014`, `BIP019`, `BIP020`, `BIP022`, `BIP024`, `BIP028`, `BIP029`, `BIP030`, `BIP034`

## Roadmap

The milestones below sequence what remains: `BIP014-P5` (Half B), `BIP015-Q1`–`Q8`, `BIP018-P0`–`P4`, `BIP026-P2`–`P5`, `BIP031-P1`–`P3`, `BIP032-P1`–`P4`, `BIP033-P1`–`P6`, `BIP035-Q1`–`Q11` / `BIP035-P1`–`P6`, and `BIP036-P1`–`P9`. Work that no BIP specifies yet is in the [backlog](#backlog-awaiting-a-bip); a milestone that needs one of those items starts by writing its BIP.

Every milestone ends with `mvn -B package -PO2` green in all execution modes (`interpret`, `-O0`, `-O1`, `-O2`, `wasm-js -O1`), `ParserGrammarTestCase`, `RuntimeBoundaryTestCase`, `ArchitectureBoundaryTestCase`, `AxiomsTestCase`, `-format -check`, the `-Pbatest` coverage gate, and a byte-identical Argon render. The exit criteria below list only what each milestone adds.

The order follows constraints recorded in the BIPs:

- **Simplify before replacing the AST.** `BIP036` (`RM020`) retires `TypeChecker`'s mutable blackboard and splits every god class under 1,500 lines, giving `BIP015` Option 2 clean seams.
- **Harden soundness early.** `BIP032` (`RM021`) can run now, catching type, effect, ownership, optimizer and Wasm escapes before `BIP014-P5` pipeline rewrites and `RM017` parallelism rely on stronger static guarantees.
- **Measure, then optimize.** Only `BIP014-P5` Half B remains, and it needs a standard library to rewrite.
- **Choose the parser before the largest grammar change.** `BIP026-P2` splits `Babble.g4` into lexer modes, and `BIP015-Q3` asks whether a parser change should come first.
- **`BIP018` after `BIP014`** (`BIP018-D4`).
- **Ranges, tensors and `babblebabble` are independent.** `BIP031` (`RM019`) is a predicate-free grammar addition that gives the standard library ranges if it lands before `RM015`; `BIP033` (`RM023`) is likewise predicate-free and independent of `BIP015` and `BIP018` (plugging into `BIP018` SIMD/FFM backends later via its `TensorBackend` SPI); `BIP035` (`RM022`) builds on the landed `BIP025` Wasm backend and `BIP034` bindings as an independent differential oracle and symbolic benchmark.

| Milestone | Slice | Remaining BIP work | Prerequisites | Notes |
|---|---|---|---|---|
| **RM014** | Front-end decision & string templates | `BIP015-Q1`–`Q8` (and the migration it picks); `BIP026-Q4`–`Q10` → `BIP026-P2`–`P5` | `RM012` (`RM020` recommended ahead of `BIP015` Option 2) | A parser migration, if chosen, lands before `BIP026-P2`. |
| **RM015** | Standard library & pipeline rewrites | `BIP014-P5` (Half B) | `RM013`; a standard library BIP | Completes `BIP014`, which opens `BIP018`. |
| **RM016** | `Babble 21` & `Babble 25` editions | `BIP018-P0`–`P2` | `RM015` | See the note on `BIP018-P0`. |
| **RM017** | Verified parallelism, `Babble 28` & Valhalla | `BIP018-P3`–`P4`, `BIP007` §10 | `RM016`; a parallelism BIP | A thread-safe `Interpreter` can land any time before. |
| **RM019** | Ranges (`range`, `mod`, `//`) | `BIP031-P1`–`P3` | `RM013` | Can run now, alongside the others. |
| **RM020** | Compiler simplification & god-class splits | `BIP036-P1`–`P9` | `RM013`, `RM018` | Can run now; shrinks the blast radius of `RM014` (`BIP015` Option 2). |
| **RM021** | Adversarial soundness (*The Great Escape*) | `BIP032-P1`–`P4` | `RM013`, `RM018` | Can run now, alongside the others; hardens soundness ahead of `RM015` and `RM017`. |
| **RM022** | `babblebabble` pure-Babble interpreter | `BIP035-Q1`–`Q11` → `BIP035-P1`–`P6` | `RM018` | Draft; independent of `BIP015` and `BIP018`, can run alongside the others. |
| **RM023** | Tensors with dimensional safety | `BIP033-P1`–`P6` | `RM013`, `RM018` | Independent of `BIP015` and `BIP018` (`RM020` recommended ahead of large front-end additions). |

### RM014 — Front-end decision & string templates

Settle [BIP015](bip015_down-with-the-ants.md) before the largest grammar change, then build string templates on the chosen front end.

- **Spans:** `BIP015`, `BIP026`, `BIP012`, `BIP014`, `BIP016`, `BIP017`, `BIP019`, `BIP029`
- **Decision first:** settle `BIP015-Q1`–`Q8`: keep ANTLR4, put a Babble-owned AST behind it (building on `RM020`'s `BIP036` seams), or replace it. A migration, if chosen, lands first, with identical parses and `BabbleSession.classify` results on every source, so `BIP026-P2` is written once.
- **Deliverables:**
  - `BIP026-P2`: template syntax (`t"..."`, `t"""..."""`, `rt"..."`, `t$$"""..."""`, `$(expr)`) through lexer modes, with formatter and `classify` support.
  - `BIP026-P3`–`P4`: `java.base`-only `Template` and `Placeholder` with lazy memoization, `template`/`placeholder` core types, evaluation policies (`tmpl.eval(policy)`) and effect tracking.
  - `BIP026-P5`: constant folding and `StringConcatFactory` lowering of direct `.render()` calls at `-O1`.
- **Exit criteria:** template specifications and effect tests, no `Template` allocation on a direct `.render()`, and the coverage gate extended to the parser if ANTLR4 goes (`BIP015-Q8`).

### RM015 — Standard library & pipeline rewrites

Give `BIP014-P5` something to rewrite: an effect-annotated `std/...` library of higher-order combinators. This completes `BIP014`.

- **Spans:** `BIP014`, `BIP005`, `BIP006`, `BIP007`, `BIP011`, `BIP016`, `BIP017`, `BIP031`
- **Depends on:** `RM013`, and a standard library BIP covering `std/...` and the module search path (`BIP005-D5`).
- **Deliverables:**
  - The standard library: `babble.path` search roots and `std/...` modules (`map`, `filter`, `flatMap`, `reduce`, `groupBy`, `zip`, `forEach`, range combinators, math and I/O helpers) with effect and algebraic contracts.
  - `BIP014-P5` Half B: an SSA `aegraph` over `MIR` and a `PipelineRewritePass` (`O12`) for filter fusion, push-downs, `monotone` range pruning and `associative` fold reassociation.
- **Exit criteria:** `std/...` specifications, `babble.path` tests in `FileModulesTestCase` and `BabblecTestCase`, an `OptimizerTestCase` per rewrite, and benchmarks of fused pipelines.

### RM016 — `Babble 21` & `Babble 25` editions

Start [BIP018](bip018_tracking-java-improvements.md) once `BIP014` is complete: a multi-JDK CI matrix, the module split, and a `Babble 25` edition without ASM.

- **Spans:** `BIP018`, `BIP006`, `BIP014`, `BIP019`, `BIP034`
- **Deliverables:**
  - `BIP018-P0`: JDK 25 and JDK 27 CI legs, and axioms for `java.lang.IO`, `Gatherer`s and `java.lang.foreign`.
  - `BIP018-P1`: the `babble-runtime` / `babble-core` / `babble-backend-asm` / `babble-21` split behind SPIs, with no change in behavior, and `babblec -target N`.
  - `BIP018-P2`: a Class-File API backend, `ScopedValue`, `lazy def`, `scoped def`, target-gated `std/foreign`, and JEP 500 trusted-final `ACC_FINAL` on immutable fields (building on `BIP034-I5`).
- **Note on `BIP018-P0`:** JDK 27 already changed what the bytecode analyzer infers: since JDK 22, `Throwable` constructors read a Flight Recorder field, which made every exception constructor `io` until curated axioms restored the JDK 21 effects. A JDK 25/27 CI leg would have caught this at once, which argues for pulling `BIP018-P0` ahead of `RM015`.
- **Exit criteria:** identical results across `interpret`, `babble-21`, `babble-25` and `babble-25 -target 21`, and a boundary test per module.

### RM017 — Verified parallelism, `Babble 28` & Valhalla

Make Babble safe on several threads in both backends, add effect-verified parallel combinators, and back them with structured concurrency and value classes on newer JDKs.

- **Spans:** `BIP007`, `BIP018`, `BIP002`, `BIP011`, `BIP016`, `BIP020`
- **Depends on:** `RM016`, `std/...`, and a parallelism BIP for the `threadsafe` effect and the combinators. A thread-safe `Interpreter` is independent.
- **Deliverables:**
  - A thread-safe `Interpreter` (no mutable current-scope field, audited runtime caches), so `examples/raytracer-fable` runs interpreted.
  - `threadsafe` and parallel `std/` combinators (`parallelMap`, `parallelFilter`, `parallelReduce`, `parallelForEach`) over `frozen` or `unique` inputs (`BIP007` §10).
  - `BIP018-P3`: `StructuredTaskScope`-backed combinators and `babble-28`.
  - `BIP018-P4`: `babble-28-preview` with `record`s as value classes (JEP 401).
- **Exit criteria:** multi-threaded differential tests, race-freedom checks on the combinators, and Argon allocation and throughput benchmarks on each edition.

### RM019 — Ranges (`range`, `mod`, `//`)

Deliver [BIP031](bip031_ranges.md) on top of the implemented `BIP034` bindings.

- **Spans:** `BIP031`, `BIP012`, `BIP013`, `BIP014`, `BIP016`, `BIP019`, `BIP025`, `BIP027`, `BIP029`, `BIP034`
- **Depends on:** `RM013`. Independent of `BIP015` and `BIP018`.
- **Deliverables:** `BIP031-P1`–`P3`: `range of T`, stepped and reversed progressions, floored `mod` and `//`, the `Range` runtime and axioms, and allocation-free counted loops in `HIR`, `MIR` and Wasm.
- **Exit criteria:** range and division specifications in every mode, with `HIR`, `MIR` and `wat` goldens.

### RM020 — Compiler simplification & god-class splits

Deliver [BIP036](bip036_compiler-simplification-and-god-class-splits.md) with zero behavioral change, dismantling every remaining compiler god class and tightening the repository-wide file ceiling to 1,500 lines ahead of `BIP015` Option 2.

- **Spans:** `BIP036`, `BIP010`, `BIP014`, `BIP015`, `BIP019`, `BIP025`, `BIP027`
- **Depends on:** `RM013`, `RM018`.
- **Deliverables:**
  - `BIP036-P1`: lifetime-separated `TypeChecker` data model (`FileFacts`, single-source `SlotInference`, fresh-per-walk `TypingPass`, `DiagnosticSink`) retiring `TypeChecker.resetPass()`.
  - `BIP036-P2`–`P7`: pure structural splits of `MirLowerer`, `HirBuilder`, `MirEmitter`, `LoweringAnalyzer`, `EffectBodyWalker`/`CallEffectAnalyzer`/`EffectAstExtractors`, and `CallEmitter`/`HirEmitter`.
  - `BIP036-P8`–`P9`: Wasm backend splits (`WasmFuncEmitter`, `WasmCallEmitter`, `WasmObjectSystem`, `WasmRuntimeGen`), `AxiomParser`/`AxiomData` AST isolation, `ScalarReplacePass` split, and repository-wide `<= 1,500`-line lock-in.
- **Exit criteria:** `ArchitectureBoundaryTestCase` enforcing `<= 1,500` LOC on every `.java` file in `src/main/java` and `AxiomData` in `ANTLR_FREE_ANALYSIS_CORES`, with zero semantic regressions.

### RM021 — Adversarial soundness (*The Great Escape*)

Deliver [BIP032](bip032_the-great-escape.md), turning ad-hoc bug hunting into a permanent adversarial defense layer across types, effects, ownership, optimizer/Wasm parity, Java axioms and algebraic/termination verifiers.

- **Spans:** `BIP032`, `BIP007`, `BIP008`, `BIP014`, `BIP016`, `BIP017`, `BIP019`, `BIP025`, `BIP027`, `BIP028`, `BIP029`, `BIP030`, `BIP034`
- **Depends on:** `RM013`, `RM018`. Independent of `RM014` and `RM020`; can run in parallel.
- **Deliverables:**
  - `BIP032-P1`: Wave 1 red-team probes (including the 5 static-audit candidate escapes), package-private white-box property tests (`TypeAlgebra`, `EffectSet`, `Reachability.Origin`, axiom monotonicity), and permanent `src/test/batest/soundness/` `.batest.md` regressions.
  - `BIP032-P2`–`P3`: per-engine `BabbleCompiler.resolveVerify(engine)`, Tier 1 runtime canary oracles, parallel-safe Tier 2 multi-mode differential/verifier oracle, layered combinatorial-matrix + AST + corpus/EMI generators, parse-tree-guided `.batest.md`-emitting delta-minimizer, `<= 5s` `EscapeSmokeTestCase`, and opt-in `-Pescape` soak profile.
  - `BIP032-P4`: deep `-Pescape` soak campaigns across all 6 surfaces (including `BIP034` `def`/`var` and portable Wasm parity) and full remediation of confirmed escapes (`BIP032-D7`).
- **Exit criteria:** `EscapeSmokeTestCase` (`<= 5s` deterministic single-leg smoke in `mvn test`), `0` untriaged `S0`/`S1` escapes from `-Pescape` soak, and every confirmed escape locked in `src/test/batest/soundness/*.batest.md`.

### RM022 — `babblebabble` pure-Babble interpreter

Settle [BIP035](bip035_babblebabble.md) (`BIP035-Q1`–`Q11`) and build a first-class pure-Babble interpreter under `src/main/babble/babblebabble/` as a symbolic compiler stress test, independent differential oracle and browser Wasm playground.

- **Spans:** `BIP035`, `BIP012`, `BIP013`, `BIP015`, `BIP020`, `BIP021`, `BIP024`, `BIP025`, `BIP026`, `BIP028`, `BIP029`, `BIP034`
- **Depends on:** `RM018` (`BIP025` Wasm portability & backend). Independent of `RM014` and `RM016`.
- **Deliverables:**
  - `BIP035-P1`–`P3`: zero-`java.*` hand-written lexer, JEP 378 string decoder, LL(1)/LL(2) recursive-descent + Pratt parser, pre-evaluation scope/tail-call pass, and evaluator covering Babble's full pure-Babble runtime semantics (`BIP002`–`BIP034`).
  - `BIP035-P4`–`P5`: modules, sessions, `BabbleScriptEngine.Mode.BABBLEBABBLE` host adapter (keeping `babble-runtime.jar` untouched), `DifferentialTestCase`/`BatestTestCase` integration, byte-identical Argon rendered by `babblebabble`, meta-circular "`babblebabblebabble`" self-hosting, and `babblec -target wasm-js` playground compilation.
  - `BIP035-P6` *(optional)*: self-hosted static type checker (`typechecker.ba`).
- **Exit criteria:** `BabbleBabblePortabilityTestCase` (0 `java.*` imports, `WasmPortabilityChecker` clean), 100% parse parity across all `.ba` and `.batest.md` sources, `0` divergences on portable specs, byte-identical Argon via `babblebabble`, and verified meta-circular & Wasm playground runs.

### RM023 — Tensors with dimensional safety

Deliver [BIP033](bip033_tensors.md), adding ML-first core `float16`/`bfloat16` scalars and immutable, dimensionally-safe `tensor of (DType, ...)` types across Babble's front end, `java.base`-only runtime, and `HIR`/`MIR`/Wasm backends.

- **Spans:** `BIP033`, `BIP011`, `BIP012`, `BIP013`, `BIP014`, `BIP016`, `BIP017`, `BIP019`, `BIP020`, `BIP021`, `BIP023`, `BIP025`, `BIP027`, `BIP028`, `BIP029`, `BIP034`
- **Depends on:** `RM013`, `RM018` (`RM020` recommended ahead of large front-end additions). Independent of `BIP015` and `BIP018`.
- **Deliverables:**
  - `BIP033-P1`: full core `float16` & `bfloat16` scalars (`Float16`/`BFloat16 extends Number`, `PrimitiveKind.binaryPromote` DAG, unboxed `short` carrier with `Bootstrap.typedCallTagged` disambiguation, contextual & suffix literals, axioms).
  - `BIP033-P2`–`P4`: predicate-free pure-`SLL` `tensor` grammar & unified `of (...)` type/shape application, `DimAlgebra` (polynomial + `org.babblelang.ir.egraph` solver with `D == H * K` constraints), directional label-preserving axis labels, `dyn` narrowing / `withDims`, `java.base`-only strided runtime & `TensorBackend` SPI, strict linear `unique` tracking on immutable tensors, scalar + labeled-subset broadcasting, reductions, `matmul`/`matmulAccum`, and compile-time checked `einsum`/`contract`.
  - `BIP033-P5`–`P6`: structured `HIR` tensor nodes, `-O1`/`-O2` elementwise loop fusion, E-graph view simplification, small-tensor SROA (`O9`), FBIP `TensorReuse` (`O10`), and a dimensionally-safe micro-Transformer forward-pass milestone in `examples/`.
- **Exit criteria:** 100% pure-`SLL` parsing, `.batest.md` differential & `HIR`/`MIR` goldens across all modes, `RuntimeBoundaryTestCase` (`java.base`-only), and byte-identical micro-Transformer output across `interpret`, `-O0`, `-O1`, `-O2`, and `babblec` AOT jar.

### Backlog awaiting a BIP

Planned work no BIP specifies yet, gathered from decisions of implemented BIPs, the [README](../../README.md)'s planned features and ray-tracer lessons, and [AGENTS.md](../../AGENTS.md)'s known issues. An item a milestone needs gets its BIP when that milestone starts; the others can be proposed at any time.

| Item | Source | Needed by |
|---|---|---|
| Standard library `std/...` and a module search path (`babble.path`) | `BIP005-D5`, `BIP006-I3`, [README](../../README.md) | `RM015`, `RM016` (`std/foreign`), `RM017` |
| `threadsafe` effect and parallel combinators | `BIP007` §10 | `RM017` |
| Thread-safe `Interpreter` | [AGENTS.md](../../AGENTS.md) known issues | `RM017` (any time) |
| Destructuring definitions, including `for (k, v) in m` | `BIP005-D3`, `BIP013` §8 | — |
| `yield` generator functions | `BIP013`, [README](../../README.md) | — |
| Bare names for a class's own members in its body | [README](../../README.md) ray-tracer lessons | — |
| Structural object types | `BIP003` §6, `BIP002-D13` | — |
| Indexing and slice syntax on `array of T`, `list`, `map` and `string` | `BIP008` §5.1 (`BIP020` has `get`/`set`; `BIP031` has ranges for slices) | — |
| More user-friendly error reports | [README](../../README.md) | — |
| `babblec` Maven goal and incremental `babblec` | `BIP006-D8`, `BIP001` §9, `BIP010-D3` | — |
| GraalVM native-image packaging | `BIP006` §9 | — |
