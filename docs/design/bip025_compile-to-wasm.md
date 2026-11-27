# BIP025: Compiling Babble to WebAssembly

| | |
|---|---|
| **BIP** | 025 |
| **Date** | 2026-11-22 *(decisions settled 2026-11-24)* |
| **Status** | Implemented (`BIP025-Q1`–`Q10` settled as `BIP025-D1`–`D10`, plus `BIP025-D11`–`D13`; `BIP025-I0`–`I5` implemented with 100% portable `.batest.md` 4-mode parity, `wat` goldens, browser `<canvas>` demo, and byte-identical `examples/raytracer-argon/scene.ppm` rendered on WebAssembly 3.0 / Node.js V8) |
| **Depends on** | Bytecode compiler, `RM001` typed primitive lowering & `RM003` interfaces ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), object model & `record`s ([bip002_object-model.md](bip002_object-model.md)), modules ([bip005_modules.md](bip005_modules.md)), `ModuleSource` & `babblec` AOT compilation ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static effects, ownership & axioms (`RM002`/`RM007`/`RM008`/`RM009`, [bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md), [bip019_refactoring-axioms.md](bip019_refactoring-axioms.md), [bip030_axiom-nullability-and-inferred-effects.md](bip030_axiom-nullability-and-inferred-effects.md)), static typing, core types, arrays, optional/generic/overloaded arguments, intersections, type aliases & self tail calls ([bip008_static-typing.md](bip008_static-typing.md), [bip011_core-types.md](bip011_core-types.md), [bip020_array-types.md](bip020_array-types.md), [bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md), [bip022_intersection-types.md](bip022_intersection-types.md), [bip023_type-aliases.md](bip023_type-aliases.md), [bip024_tail-calls.md](bip024_tail-calls.md)), exception handling ([bip028_exception-handling.md](bip028_exception-handling.md)), `.batest.md` v2 differential & IR/WAT golden harness ([bip009_test-coverage.md](bip009_test-coverage.md), [bip029_batest-v2-and-ir-goldens.md](bip029_batest-v2-and-ir-goldens.md)), decoupled `HIR`, Nanopass optimizer, `ScalarReplacePass` & `RM012` compiler modularity ([bip014_optimization-passes.md](bip014_optimization-passes.md) `BIP014-I0`–`I2`, `BIP014-I4`, [bip027_tackling-complexity-in-the-compiler.md](bip027_tackling-complexity-in-the-compiler.md)), and multi-target `babblec -target` CLI design ([bip018_tracking-java-improvements.md](bip018_tracking-java-improvements.md)) |
| **Scope** | Whole-program ahead-of-time compilation of Babble from target-neutral structured `HIR` to **WebAssembly 3.0** (`WasmGC`, typed function references, `try_table`/`exnref` exceptions, and tail calls); target-neutralizing `HIR` and defining Babble's portable core & I/O boundary; WasmGC lowering of all scalars, strings, arrays, core collections, closures, classes, strictly immutable `record`s, interfaces, intersections, unions, self tail calls, and full `BIP028` `try`/`catch`/`finally`/`throw`; browser/Node.js (`wasm-js`) and pure-compute (`wasm-bare`) target profiles (deferring `wasm-wasi` until WasmGC Component Model Canonical ABI stabilizes); zero-dependency pure-Java `.wasm`/`.wat` emission & `WasmVerifier`; persistent Node.js (V8) differential testing against `Interpreter` and JVM `compile` via `.batest.md` (`wat` goldens included); and a 6-phase rollout (`BIP025-I0`–`I5`) culminating in byte-identical `examples/raytracer-argon/scene.ppm` rendered in WebAssembly |

---

## 1. Executive Summary

Babble began as a JVM language with two execution modes: a tree-walking `Interpreter` and an ASM-based JVM bytecode compiler. Over `RM001`–`RM012` plus `BIP014-I4`, `BIP019`–`BIP024`, `BIP026-I1`, and `BIP028`–`BIP030`, however, Babble has quietly acquired almost every structural prerequisite of a **multi-target compiled language**:

1. **A typed High-Level IR decoupled from the parsed AST (`org.babblelang.ir.hir`, `BIP014-I1`, `BIP014-I4`, `BIP027`, `BIP028`):** `HirBuilder` lowers the analyzed parse tree into immutable Java 21 `record`s (`HirModule`, sealed `HirExpr`, `HirFunction`, `HirClass`, `HirInterface`, `ExprMeta`), optimized by `org.babblelang.opt` (`HirEGraphSimplifyPass`, `InlinePass`, `ScalarReplacePass`, `HirDcePass`, `HirLicmPass`, `HirTryNormalize`) before `HirEmitter` ever touches JVM bytecode.
2. **Strict static typing & 8-primitive unboxing (`BIP008`, `BIP011`, `RM001`):** Every expression carries a converged static `Type` and `PrimitiveKind` (`BYTE`..`CHAR`, `NONE`), eliminating runtime type tags on hot numeric paths.
3. **Plain compiled classes, first-class arrays & portable exceptions (`BIP020-D18`, `BIP028-D2`):** Compiled Babble classes and `record`s no longer inherit a JVM runtime base class (`InterpretedInstance` is interpreter-only), `array of T` is a first-class language type, and `BIP028` designed core `exception` and open nominal Babble throws (`record ParseError(...)`) to avoid naming `java.*` or `org.babblelang.runtime.*`.
4. **Pure-by-default effects, ownership & external axioms (`BIP007`, `BIP016`, `BIP017`, `BIP019`, `BIP030`):** The compiler already knows statically which functions are pure, `nothrow`, `@TERMINATING`, or `io`, which values are `frozen` or `unique`, and which host operations are contracted via `axiom`s.
5. **A non-trivial pure-Babble application already in-repo:** [`examples/raytracer-argon/`](../../examples/raytracer-argon/) is a 14-module ray tracer written in **100% pure Babble with zero `java.*` or `javax.*` calls** — using only Babble's core scalars, `string`, `list`, `record`, `class`, `interface`, closures, `for`/`while`, `print`/`println` (with `out = STDOUT`), and `assert`.

At the same time, **WebAssembly 3.0** became a W3C standard in September 2025 (`[WASM30]`), unifying **WasmGC**, **typed function references**, **`try_table`/`exnref` exception handling**, and **tail calls** into a single baseline shipped across all major browsers (Chrome/V8, Firefox/SpiderMonkey, Safari/WebKit) and Node.js (V8).

Adding a **WebAssembly 3.0 backend** directly beneath Babble's `HIR` optimizer advances both of Babble's founding objectives (`README.md`):

* **Teaching beginners with zero friction:** A Babble program compiled with `babblec -target wasm-js` runs directly in any web browser or Node.js — no JDK installation, no server round-trip, and direct access to HTML `<canvas>` / DOM playgrounds for visual exercises (such as rendering `raytracer-argon` live in a browser tab).
* **Compiler engineering & architectural hygiene:** Targeting a second, genuinely different VM (structured control flow, nominal GC structs/arrays with single inheritance, invariant GC arrays, no reflection or `invokedynamic`, explicit host imports) stress-tests Babble's `HIR` and forces a crisp boundary between *Babble the language* and *Babble's JVM host*.
* **Fast-starting, sandboxed AOT artifacts:** Sub-millisecond cold start and compact `.wasm` binaries (tens of KB rather than a JRE + fat jar) for browser, Node.js, and pure-compute embedded/plugin workloads (`-target wasm-js` and `-target wasm-bare`).

This proposal lays out the ecosystem baseline (§2), compares four compilation strategies (§3), audits every JVM leak in `HIR` and the front end (§4), specifies the exact lowering of every Babble construct to WebAssembly 3.0 (§5), defines the host interop and CLI surface (§6), designs the verification and `.batest.md` differential test harness (§7), records the thirteen settled design decisions (`BIP025-D1`–`D13`, §8), and outlines the six-phase implementation plan (`BIP025-I0`–`I5`, §9).

---

## 2. State of the Art & Ecosystem Baseline (2025–2026)

### 2.1 WebAssembly 3.0 Core Specification

WebAssembly 3.0 (`[WASM30]`, W3C Release 3.0, September 2025) standardized the exact features that high-level garbage-collected languages previously lacked:

| Wasm 3.0 Feature | Key Instructions / Types | Direct Value to Babble |
|---|---|---|
| **Garbage Collection (`WasmGC`)** | `struct`, `array`, `i31ref`, `sub`, `sub final`, `rec`, `struct.new`, `struct.get`/`set`, `array.new`, `array.get`/`set`, `ref.test`, `ref.cast`, `br_on_cast`, `anyref`, `eqref` | Maps Babble classes, `record`s, closures, capture cells, boxed scalars, and `array of T` directly onto host-managed GC objects with zero custom GC or shadow stack. Packed `i8`/`i16` struct/array fields map `byte`, `short`, `bool`, `char` without waste. |
| **Typed Function References** | `(ref $fn_type)`, `call_ref`, `ref.func` | Direct typed indirect calls for closures, vtable methods, and itable interface dispatch without boxing arguments into linear memory or routing through a single untyped `funcref` table. |
| **Exception Handling (`exnref`)** | `tag`, `throw`, `throw_ref`, `try_table` (`catch`, `catch_ref`, `catch_all_ref`) | Zero-cost happy-path exception handling for `BIP028` `try`/`catch`/`finally`/`throw`, core `exception` (`BabbleException`), open nominal Babble throws, and runtime checks (`NullPointerException`, `ArrayIndexOutOfBoundsException`, `ArrayStoreException`, `AssertionError`, `NumberFormatException`) — replacing the legacy `try`/`catch` proposal across all browsers. |
| **Tail Calls** | `return_call`, `return_call_ref`, `return_call_indirect` | Constant-stack tail recursion for `BIP024` `SelfTailCall` (`recurse(...)`, self-recursive `def`, `this.m(...)`) and mutual tail calls — strictly stronger than JVM bytecode, which still lacks tail calls. |
| **Multiple Memories & Memory64** | `(memory $m ...)`, `i64` linear pointers | Available if a future target (`wasm-wasi`) needs isolated linear-memory I/O buffers without polluting GC heaps. |
| **Deterministic Profile & Relaxed SIMD** | Deterministic NaN canonicalization option | Aligns with Babble's strict differential parity (`Interpreter` vs JVM vs Wasm) and future vectorization (`Vec3` math). |

### 2.2 Runtime & Browser Support Matrix (as of November 2026)

| Runtime / Engine | WasmGC | `try_table` / `exnref` | Tail Calls | `wasm:js-string` | Notes for Babble |
|---|---|---|---|---|---|
| **Chrome / Edge / Node.js (V8)** | Shipped | Shipped | Shipped | Shipped | Primary browser, Node.js CLI/benchmark, and CI test engine (`BIP025-D10`); TurboFan optimizes immutable GC fields aggressively. |
| **Firefox (SpiderMonkey)** | Shipped | Shipped | Shipped | Shipped | Full Wasm 3.0 + JS string builtins parity. |
| **Safari (WebKit)** | Shipped | Shipped (2025) | Shipped | Shipped (2025) | Completed cross-browser WasmGC + `exnref` + `js-string-builtins` support (`[JSSTR]`). |
| **Wasmtime 47+** (Bytecode Alliance) | **Enabled by default** (Jul 2026) | **Enabled by default** (Jul 2026) | Shipped | N/A (host) | Reference WASI engine (`[WT47]`); relevant once deferred `wasm-wasi` is added (`BIP025-D8`). |
| **Endive 1.2+** (Bytecode Alliance, fork of Chicory) | **Shipped** (JVM GC host integration) | **Shipped** (`exnref`/`catch_ref`/`throw_ref` in 1.2.0, Oct 2026) | Shipped | N/A (host) | Pure-Java JVM Wasm runtime (`[ENDIVE]`), but does not execute JS (`app.mjs`) or `wasm:js-string`; not added as a dependency under `BIP025-D10`. |
| **GraalWasm** (Oracle) | Supported (`--wasm.GC`) | Supported | Supported | N/A (host) | Optional alternative JVM Wasm engine for Polyglot/GraalVM users. |

### 2.3 WASI 0.2 / 0.3 & the Component Model Boundary (`BIP025-D8`)

**WASI 0.3.0** (`[WASI03]`, launched June 2026, ratified October 2026) sits on the **WebAssembly Component Model** (WIT interfaces + native async). However, **WasmGC integration into the Component Model Canonical ABI** is still at pre-proposal stage (`[CM-GC]`, `WebAssembly/component-model#525`): Component Model interfaces currently pass strings, lists, and records across component boundaries via **linear memory** (`realloc` + pointer/length), not directly as WasmGC `struct`/`array` references.

Rather than maintaining throwaway `wasi_snapshot_preview1` linear-memory staging boilerplate during backend bring-up, **`BIP025-D8` focuses `BIP025-I0`–`I5` exclusively on `-target wasm-bare` and `-target wasm-js`** (which covers browsers, Node.js CLI/benchmarks, and zero-import compute modules with zero linear-memory glue), deferring `-target wasm-wasi` and WASI Component Model packaging until `[CM-GC]` stabilizes.

### 2.4 Lessons from Prior Art in GC-Language → WasmGC Compilers

| Compiler | Input → Output | Key Design Lessons for Babble |
|---|---|---|
| **Kotlin/Wasm** (`[KTWASM]`, Beta Sep 2025) | Kotlin IR → WasmGC + `exnref` (`wasm-js` & `wasm-wasi`) | Compiles directly from a typed high-level IR (not JVM bytecode); uses closed-world whole-program WasmGC compilation and compact interface dispatch tables; achieves ~3× speedup over Kotlin/JS on UI workloads. |
| **TeaVM WasmGC** (`[TEAVM]`) | JVM `.class` → WasmGC | Proves Java-like OOP (classes, multiple interfaces, virtual dispatch, exceptions, arrays) compiles cleanly to WasmGC with tiny binaries; demonstrates the **interface dispatch** and **array covariance** patterns needed because WasmGC structs are single-inheritance and WasmGC arrays are invariant. |
| **Dart WasmGC** (Flutter Web) | Dart Kernel IR → WasmGC + `wasm:js-string` | Highlights the `externref` vs `eqref` boundary (`any.convert_extern`) when using JS strings, and shows the dramatic performance gains of marking immutable fields non-`mut` in WasmGC structs (matching Babble `record`s and `frozen`!). |
| **J2Wasm** (Google Sheets) | Java source → WasmGC | Validates production WasmGC performance on numeric/object-heavy workloads; motivates `rec`-group minimization and devirtualization. |
| **MoonBit & OCaml (`wasm_of_ocaml`)** | Typed IR → WasmGC | Demonstrates clean direct binary `.wasm` emission without LLVM/Binaryen as a hard dependency, and shows how closed-world monomorphization/unboxing shrinks code size. |

---

## 3. Compilation Strategy: Options Evaluation

Before committing to a pipeline, we evaluated four concrete architectures for compiling Babble to WebAssembly:

| Criterion | **Option A: Direct `HIR` → Wasm 3.0 (`WasmWriter`)** *(Chosen — `BIP025-D1`, `D2`)* | **Option B: JVM `.class` → TeaVM WasmGC** | **Option C: `HIR` → Linear-Memory Wasm 2.0 + Custom GC/RC** | **Option D: `HIR` → LLVM IR / Binaryen C++** |
|---|---|---|---|---|
| **External build/runtime deps** | **Zero** (pure Java 21 binary emitter; optional `wasm-opt` if on `PATH`) | Heavy (TeaVM + JDK classlib emulation) | Zero (custom emitter) | Heavy (native LLVM/Binaryen binaries per OS/arch) |
| **Reuse of Babble front end & `org.babblelang.opt`** | **100%** (shares `BlockScopes`, `TypeChecker`, `EffectChecker`, `HirBuilder`, `PassManager`) | 100%, *plus* `HirEmitter` | 100% | 100% |
| **Compatibility with Babble's JVM runtime** | Does not rely on `invokedynamic` / `MethodHandles` / `LambdaMetafactory` / `ClassValue` / reflection | **Broken / high friction:** Babble's `Bootstrap`, `Overloads`, `Faces`, and `BabbleObjects` rely heavily on `invokedynamic`, `MethodHandles.privateLookupIn`, `LambdaMetafactory`, and `ClassValue` — unsupported or fragile in TeaVM | Independent of JVM runtime | Independent of JVM runtime |
| **GC & runtime footprint** | Host engine GC (tiny `.wasm`, ~5–30 KB for small programs) | Host engine GC, but dragged up by JDK classlib emulation (~150 KB–1 MB+) | Must ship & maintain custom GC or cycle-collecting RC + shadow stack | Must ship custom GC or emit WasmGC via Binaryen |
| **Effect & ownership leverage (`BIP007`/`BIP016`/`BIP014`)** | **Full:** immutable WasmGC fields (`record`/`frozen`), `nothrow`/`finallyNothrow` elision, `return_call` tail recursion, `ScalarReplacePass` | Erased at JVM bytecode boundary (one-way wall, `BIP014` §2.1) | Enables Perceus/FBIP RC on `unique`, but high engineering cost | Good, at the cost of native C++ toolchain coupling |
| **Learning & maintainability value** | **Very high:** ~2,500 lines of clean Java 21 matching `HirEmitter` + `ClassGen` | Low (black-box transpiler) | Very high complexity (GC bugs) | High operational friction (cross-platform native tools) |
| **Wasm engine reach (2026)** | All browsers, Node.js (V8), Wasmtime 47+, Endive 1.2+, GraalWasm | Same (WasmGC) | Universal (Wasm 1.0/2.0) | Depends on target |

**Decision (`BIP025-D1`, `BIP025-D2`):** **Option A — Direct `HIR` → WebAssembly 3.0 (WasmGC) via a pure-Java `WasmWriter`**.

Why this fits Babble like a glove:
1. `BIP014-I1` (`RM010`) and `BIP027-I2` (`RM012`) already detached compilation from ANTLR4 into a sealed `HirExpr` tree and modular emitter helpers. Writing `org.babblelang.wasm` against `HirModule` is architecturally symmetric to `HirEmitter` — and because `HIR` is structured, it maps 1-to-1 onto WebAssembly's structured control flow with zero Relooper/Stackifier (`BIP025-D3`).
2. Adding a pure-Java Wasm 3.0 binary encoder (`WasmWriter`), text printer (`WatPrinter`), and validator (`WasmVerifier`) adds **zero** compile-time, runtime, or test Maven dependencies (`BIP025-D2`, `BIP025-D10`), keeping `babble.jar` self-contained and portable across OSes.
3. Optional post-optimization with Binaryen's `wasm-opt -O3 --enable-gc --enable-exception-handling --enable-tail-call` can be offered as an opt-in flag (`-wasm-opt`) when `wasm-opt` is present on `PATH`, without ever making it mandatory.

---

## 4. Target-Neutralizing the Front End & `HIR` (`BIP025-I0`, `BIP025-D3`, `BIP025-D12`)

A direct audit of `src/main/java/org/babblelang/ir/hir/` and `src/main/java/org/babblelang/analysis/` reveals exactly where JVM assumptions currently live. Most of the front end is already target-neutral (especially after `BIP027-I1`–`I6` and `BIP028` kept `HirExpr.Try`/`Throw`/`CoreExceptionRef` target-neutral); the remaining JVM leaks are localized and small:

### 4.1 Codebase Audit of Current JVM Coupling

| File / Subsystem | Current JVM-Specific Field or Behavior | Target-Neutral Refactoring (`BIP025-I0`, `BIP025-D3`) |
|---|---|---|
| [`HirModule.java`](../../src/main/java/org/babblelang/ir/hir/HirModule.java) | `className`, `allInterfaceJvmNames`, `allClassJvmNames`, `ownInterfaceJvmNames`, `ownClassJvmNames` | Replace `className` with target-neutral `moduleId` / `symbolPrefix`; move JVM internal name maps into an optional `JvmLoweringMetadata` record (or derive them inside `HirEmitter` from `TypeHierarchyInfo` + `moduleId`). |
| [`HirClass.java`](../../src/main/java/org/babblelang/ir/hir/HirClass.java) & [`HirInterface.java`](../../src/main/java/org/babblelang/ir/hir/HirInterface.java) | `jvmName`, `implementedJvmInterfaces`, `javaInterfaces`, `extendedJvmInterfaces`, `ClassShape` | Key classes/interfaces by their stable Babble `typeId` (`lib/vec3.ba#Vec3`, already used by `BIP008` `T17` and `typeHierarchies`). Keep `javaInterfaces` empty on non-JVM targets (rejected at type-check time). |
| [`HirFunction.java`](../../src/main/java/org/babblelang/ir/hir/HirFunction.java) | `jvmMethodName`, `hasSpec()` (`slots <= 250` JVM limit) | Rename `jvmMethodName` → `mangledName`; move the JVM 250-slot check into `HirEmitter` (Wasm supports up to 1,000+ parameters/locals without 2-slot `long`/`double` accounting). |
| [`HirExpr.java`](../../src/main/java/org/babblelang/ir/hir/HirExpr.java) | `JavaFieldGet`, `JavaFieldAssign`, `JavaConstructorCall`, `JavaMethodCall`, `JavaSamAdapt`; `ThisFieldGet`/`DirectThisMethodCall` `externalClassJvmName`; `RecordNew`/`RecordReuse` `classJvmName`; `ArrayCreate` & `ArrayOp` carry `Class<?> componentClass` / `Class<?> arrayClass` and `componentInternalName`; `CoreCollectionCall` & `CoreCollectionRef` | Key cross-module class references by `classTypeId` instead of `externalClassJvmName`/`classJvmName`; replace `Class<?>` on `ArrayCreate`/`ArrayOp` with a sealed `HirArrayElementType` (`Primitive(PrimitiveKind)`, `String`, `BabbleClass(typeId)`, `BabbleInterface(typeId)`, `Array(elem)`, `CoreCollection(kind)`, `Any`, `JavaClass(Class<?>)`). On Wasm targets, `Java*` nodes are forbidden by `TypeChecker` / `WasmPortabilityChecker` (except portable `string` / core-collection method calls lowered to dedicated `HirExpr.CoreMethodCall` nodes). |
| [`ExprMeta.java`](../../src/main/java/org/babblelang/ir/hir/ExprMeta.java) | `checkedType` (JVM/Babble descriptor string), `PrimitiveKind`, `stringType`, `deeplyImmutable`, `frozen`, `unique`, `EffectSet` | Already ~95% target-neutral. Pair `checkedType` with structured `HirTypeDesc` so Wasm never parses JVM internal names out of descriptor strings. |
| [`Type.java`](../../src/main/java/org/babblelang/analysis/Type.java) & [`BIP011`](bip011_core-types.md) | `string` resolves methods via `java.lang.String.class`; `list`/`set`/`sortedset`/`map`/`sortedmap`/`entry` resolve methods via `java.util.*` reflection; `Implicits` exposes `java`/`javax` packages | Introduce a `CompilationTarget` (`JVM`, `WASM_JS`, `WASM_BARE`, unified with `BIP018-P1`'s `TargetProfile`, with `WASM_WASI` reserved per `BIP025-D8`) passed into analysis. On Wasm targets: (1) `java`/`javax` roots are absent (producing a targeted portability error), and (2) `string` and core collections resolve their **Portable Core API** from `src/main/java/org/babblelang/axioms/portable/` (reusing `BIP019`/`BIP030` axiom syntax), guaranteeing identical signatures, nullability, and effects on both JVM and Wasm. |

### 4.2 The Portable Core Boundary

One of Babble's greatest strengths on the JVM — `BIP011`'s zero-wrapper transparency where `string` is `java.lang.String` and `list` is `java.util.List` — creates an obvious question on WebAssembly: *which methods exist on `string`, `list`, `set`, `sortedset`, `map`, `sortedmap`, and `entry` when there is no JDK?*

BIP025 enforces a strict-subset rule (`BIP025-D6`):

> **Portable Core Rule:** Every method in the Wasm Portable Core API of `string`, `array`, `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, and `exception` is an **exact semantic subset** of its JVM counterpart — same name, same parameter/return types, same nullability (`BIP030`), same `BIP016` effects, and same exception conditions. Any code that compiles under `-target wasm-*` is guaranteed to compile and behave identically on the JVM (unless it explicitly imports a Wasm-host-only API via `axiom wasm.*`).

In `HirBuilder`, calls on `string`, `exception`, or core collection receivers (`list.add`, `list.get`, `list.size`, `list.clear`, `string.length`, `string.substring`, `map.put`, `map.get`, `e.getMessage`, `e.getSuppressed`, etc.) are recognized cleanly:
* On `JVM`, they lower to `JavaMethodCall` (direct `INVOKEVIRTUAL`/`INVOKEINTERFACE` on `java.lang.String` / `java.util.List` / `BabbleException`, zero overhead, unchanged from today).
* On `WASM_*`, any method in the Portable Core contract lowers to `HirExpr.CoreMethodCall` (dispatched to the Wasm core runtime / Babble prelude), while any JVM-only method (e.g., `s.getBytes(Charset)`, `s.intern()`, `list.parallelStream()`) is rejected at compile time with a clear diagnostic:

```
Error : Not supported on target wasm : java.lang.String.intern (line 12, column 5)
Error : Not supported on target wasm : java.io.File (line 3, column 15)
```

### 4.3 Portable I/O Streams (`STDOUT`/`STDERR`/`to:`) & `builtins.ba` (`BIP025-D12`)

Auditing [`src/main/java/org/babblelang/axioms/builtins.ba`](../../src/main/java/org/babblelang/axioms/builtins.ba) and [`examples/raytracer-argon/lib/camera.ba`](../../examples/raytracer-argon/lib/camera.ba#L108-L115) uncovers two subtle JDK references inside Babble's built-in prelude:
1. `print`/`println`/`affiche`/`afficherc` declare `to: java.lang.Appendable = ...`, and `examples/raytracer-argon/lib/camera.ba` writes `def render = fn io (world: Hittable, mut rng: Rng = Rng(1337), out = STDOUT) -> (println("P3", to: out) ...)`.
2. Scalar conversion axioms (`byte`, `short`, `long`, `float`) include `java.lang.Number` in their union parameter types.

Under **`BIP025-D12`**:
* Babble defines a **portable output-sink contract** for `print`/`println`'s `to:` parameter: on `JVM`, it maps transparently to `java.lang.Appendable` (unchanged, zero wrapper); on `WASM_*`, it maps to a 1-method portable `Appendable` interface (`append(s: string)`) in the Wasm prelude, implemented by `STDOUT`, `STDERR`, and any user-defined Babble class or string buffer.
* `builtins.ba` provides a target-neutral view for Wasm targets that omits `java.lang.Number` from the scalar conversion unions and binds `to:` to the portable `Appendable` interface.
* As a result, all 14 modules of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) — which use `list()`, `list.add(x)`, `list.get(i)`, `list.size()`, `list.clear()`, `for x in xs`, `string` concatenation `+`, `out = STDOUT`, `println(..., to: out)`, `assert`, `int(x)`/`double(x)`/`string(x)`, and Babble classes/records/interfaces/closures — compile **100% unmodified** on both JVM and WebAssembly!

---

## 5. Lowering Babble Semantics to WebAssembly 3.0 (WasmGC)

### 5.1 Whole-Program Compilation & Module Linking

Following `babblec` (`BIP006`), compiling a Babble program to WebAssembly is **whole-program AOT**:

1. `BabbleScriptEngine.compileProgram(source, mainPath)` parses, type-checks, effect-checks, and builds `HirModule`s for the main script and every statically imported `.ba` module (`BIP005` M2/M3).
2. `PassManager.defaultFor(optLevel, target)` runs Babble's `HIR` Nanopass optimizer (`HirEGraphSimplifyPass`, `InlinePass` with cross-module small-export inlining, `ScalarReplacePass` (`O9`), `HirDcePass`, `HirLicmPass`, and `HirTryNormalize`) across all modules, **skipping `FbipReusePass`'s `RecordReuse` on Wasm targets** so `record` fields remain strictly immutable (`BIP025-D11`). `WasmEmitter` consumes this structured `HIR` directly without lowering through `BIP014-I3` CFG `MIR` (`BIP025-D3`).
3. `WasmLinker` merges the module graph into a **single closed-world `WasmProgram`**:
   * Assigns globally unique type indices, struct/array layouts, interface bit indices, vtable slots, and function indices across all modules in deterministic order (preserving `BIP006`'s reproducible-build guarantee: same sources → identical `.wasm` bytes!).
   * Each Babble module $M$ gets:
     * A Wasm `global` `$mod_state_M : (mut i32)` (`0` = uninitialized, `1` = initializing, `2` = initialized) — matching `BIP005`'s cyclic-import registration semantics.
     * Wasm `global`s for each exported `def` (unboxed primitive globals for non-nullable primitive exports, `(ref null $Any)` for references — zero hash-table lookup on `ModuleExportGet`, generalizing `RM001`'s `Module.exportAt`).
     * A `$mod_init_M` function executing `M.topLevelStatements` once on first import or entry.
   * Dynamic `import(nonLiteral)` fails at compile time on Wasm targets (`Error : Dynamic import is not supported on target wasm : ...`), matching standalone `babblec` without `FileModuleCompiler` (`BIP006`).
   * Dead-code tree shaking drops unreachable functions, classes, and core-prelude helpers before emitting the binary.

### 5.2 Type & Value Representation (Scalars, Nullability, Boxing, `any`)

Babble's strict static typing (`BIP008`) and 8-primitive unboxing (`BIP011`, `RM001`) map onto Wasm 3.0 value and storage types with almost no friction:

| Babble Type | Local / Param / Return (Unboxed) | Struct / Array Field Storage | Boxed (`any` / `T?` / Union / Generic) |
|---|---|---|---|
| `byte` | `i32` (sign-extended: `i32.extend8_s`) | `i8` (`struct.get_s`) | `(ref $BoxByte)` (`(struct (field i8))`) |
| `short` | `i32` (sign-extended: `i32.extend16_s`) | `i16` (`struct.get_s`) | `(ref $BoxShort)` (`(struct (field i16))`) |
| `int` | `i32` | `i32` | `(ref $BoxInt)` (`(struct (field i32))`) |
| `long` | `i64` | `i64` | `(ref $BoxLong)` (`(struct (field i64))`) |
| `float` | `f32` | `f32` | `(ref $BoxFloat)` (`(struct (field f32))`) |
| `double` | `f64` | `f64` | `(ref $BoxDouble)` (`(struct (field f64))`) |
| `bool` | `i32` (`0` or `1`) | `i8` (`struct.get_u`) | `(ref $BoxBool)` — **2 global singletons** (`$TRUE`, `$FALSE`, zero allocation!) |
| `char` | `i32` (`0..65535`, masked `& 0xFFFF`) | `i16` (`struct.get_u`) | `(ref $BoxChar)` (ASCII `0..127` singleton table) |
| `null` | `(ref null none)` (`ref.null none`) | `(ref null none)` | `ref.null eq` |
| `T?` (ref `T`) | `(ref null $T)` | `(ref null $T)` | `(ref null $T)` — **zero wrapper!** |
| `T?` (prim `T`) | `(ref null $BoxT)` | `(ref null $BoxT)` | `(ref null $BoxT)` |
| `string` | `(ref $String)` *(see §5.3 & `BIP025-D4`)* | `(ref $String)` | `(ref $String)` |
| `any` / `any?` | `(ref $Any)` / `(ref null $Any)` | `(ref $Any)` / `(ref null $Any)` | Root of Babble's WasmGC `sub` hierarchy |
| Union `A \| B` | `(ref $Lub)` (or `(ref $Any)`) | `(ref $Lub)` | Dispatched via `ref.test` / `br_on_cast` |
| `nothing` (`return`/`break`/`throw`) | Unreachable stack-polymorphic | N/A | N/A |

**Distinct `$Box*` structs over raw `i31ref` (`BIP025-D5`):**
WasmGC's `i31ref` stores an unboxed 31-bit signed integer inside a tagged pointer with zero allocation. However, Babble distinguishes `byte`, `short`, `int`, `char`, and `bool` at runtime (`x is byte`, `x is int`, `x is char`, `x is bool`, `classof(x)`), and `int` requires the full 32-bit signed range (`-2147483648..2147483647`). Storing raw `i31ref` would either truncate `int` to 31 bits or lose the type distinction between `byte(1)`, `short(1)`, `int(1)`, `char(1)`, and `true`.
* **Chosen design (`BIP025-D5`):** Every boxed primitive is a `sub final $Any` struct (`$BoxByte`..`$BoxDouble`), giving exact $O(1)$ `ref.test (ref $BoxInt)` semantics for `is`, `as`, and `classof`, with zero-allocation global singletons for `$TRUE`/`$FALSE` and small cached `byte`/`int`/`char` values (`-128..127`, matching JVM `Integer.valueOf`).
* **Optional later optimization:** Bit-tagged `i31ref` (using the top 3 bits as a type tag for sub-32-bit primitives `bool`, `byte`, `short`, `char` while keeping `$BoxInt` for 32-bit `int`) can be added transparently later if boxing ever profiles hot; because `RM001` already keeps non-nullable scalars unboxed in locals, parameters, returns, fields, and arrays, boxing only occurs at `any`/union/generic boundaries.

**Exact Babble Operator & Truthiness Parity (`Operators.java`, `Truth.java`):**
Subtle semantic differences between raw Wasm instructions and Babble's JVM semantics must be preserved identically so `.batest.md` differential tests never diverge:

1. **Floating-point `==`, `<>`, `<`, `<=`, `>`, `>=`:** Babble's [`Operators.java`](../../src/main/java/org/babblelang/runtime/Operators.java) compares floats/doubles via **`Double.compare`** (and `Float.compare`), **not** IEEE-754 `f64.eq`:
   * `NaN == NaN` is **`true`** in Babble, and `NaN > +Infinity`!
   * `-0.0 < +0.0`, so `-0.0 == +0.0` is **`false`** in Babble!
   * In Wasm, a small 10-instruction helper `$f64_compare(a: f64, b: f64) -> i32` using `f64.eq` / `f64.lt` / `f64.gt` plus `i64.reinterpret_f64` for signed zeros and NaN canonicalization reproduces `Double.compare` bit-for-bit (and for `a < b` when both are statically known non-NaN or inside fast paths, `f64.lt` + NaN check costs only 2 instructions).
2. **Division `/`:** Always floating-point in Babble (`int / int` widens to `f64.div`; `float / float` or `int / float` uses `f32.div`), never traps on division by zero (produces `±Infinity` or `NaN` per IEEE-754).
3. **Integer wrap-around:** `i32.add`/`sub`/`mul` and `i64.add`/`sub`/`mul` match Java two's-complement wrap-around natively. Negating `INT_MIN` (`-2147483648`) wraps to `-2147483648` via `i32.sub(0, x)`.
4. **Truthiness (`Truth.test`):** `bool` is itself; numbers are truthy iff `!= 0.0` (note: `NaN` is truthy because `NaN != 0.0` is `true` in Java); any reference is truthy iff `!ref.is_null`. When the static type is `bool`, `HirExpr.If`/`While`/`Logic` emits a direct `i32` branch with zero dispatch.
5. **String `+`:** When left operand is `string`, `a + b` formats `b` via `$format(b)` (including `ArraySupport.deepToString` semantics for arrays, `BIP020-D14`) and concatenates.

### 5.3 Strings (`string`, `BIP025-D4`)

Under **`BIP025-D4`**, all Wasm targets use a uniform WasmGC `$String` struct wrapping an immutable UTF-16 `(array i16)`:

```wat
(type $CharArray (array i16))               ;; immutable UTF-16 code units (matches JVM char / String!)
(type $String (sub final $Any
  (struct
    (field $chars (ref $CharArray))
    (field $hash (mut i32)))))              ;; lazily cached Java-compatible hashCode
```

* **`$String <: $Any <: eqref`:** Fits directly into `any`, `any?`, `string | int`, `list of string`, `ref.test (ref $String)`, and `same(a, b)` with zero `externref` wrapper friction.
* **Exact JVM parity across all targets:** One implementation of every portable `string` method (`length`, `charAt`, `substring`, `indexOf`, `split`, `trim`, `compareTo`, `hashCode`, `equals`) with exact UTF-16 `char` indexing and Java-compatible `hashCode`.
* **Fast JS boundary (`wasm-js`):** Crossing the JavaScript boundary (`console.log`, DOM/Canvas APIs, exported functions) uses **`wasm:js-string` `fromCharCodeArray` / `intoCharCodeArray`** (`[JSSTR]`), which reads/writes `(array i16)` directly inside V8, SpiderMonkey, and WebKit in a single engine builtin call!

### 5.4 Arrays (`array of T`, `BIP020`) & Core Collections (`BIP011`, `BIP025-D6`)

#### Arrays (`array of T`)
There is a critical difference between JVM arrays and WasmGC arrays:

> **WasmGC arrays are invariant**, whereas JVM arrays — and Babble's `BIP020-D8` — support **covariant reference arrays** with runtime `ArrayStoreException` checks, plus **`BIP020-D17` content equality (`==`) and `BIP020-D14` `[a, b]` printing**!

Furthermore, `array of int` (`int[]`) and `array of int?` (`Integer[]`) are distinct types at runtime (`BIP020-D5`).

The cleanest WasmGC lowering splits primitive arrays from reference arrays:

```wat
;; Primitive arrays: wrapped in a tiny final $Any subtype so they fit in `any` / `is array of int` / `==`
;; while unboxed hot loops access `$data` directly via a single struct.get (hoisted by LICM!).
(type $I8Arr  (array (mut i8)))
(type $I16Arr (array (mut i16)))
(type $I32Arr (array (mut i32)))
(type $I64Arr (array (mut i64)))
(type $F32Arr (array (mut f32)))
(type $F64Arr (array (mut f64)))

(type $ArrayBase (sub $Any (struct)))
(type $IntArray    (sub final $ArrayBase (struct (field $data (ref $I32Arr)))))
(type $DoubleArray (sub final $ArrayBase (struct (field $data (ref $F64Arr)))))
;; ... similarly $ByteArray, $ShortArray, $LongArray, $FloatArray, $BoolArray, $CharArray

;; Reference arrays: single covariant carrier with runtime element-type descriptor!
(type $AnyArr (array (mut (ref null $Any))))
(type $RefArray (sub final $ArrayBase
  (struct
    (field $elem_type (ref $TypeDesc))         ;; exact element type for ArrayStoreException & `is array of T`
    (field $data      (ref $AnyArr)))))
```

Look how cleanly every `BIP020` invariant falls out:
* **`a.size()`** → `array.len (struct.get $IntArray $data a)` — 2 instructions.
* **`a.get(i)`** → bounds check (throwing Babble's `ArrayIndexOutOfBoundsException` instead of an uncatchable Wasm trap) + `array.get` (+ `ref.cast` on `$RefArray` when element type is statically known, elided if proven safe).
* **`a.set(i, v)`** → bounds check + exact element-type check (`$is_instance(v, a.$elem_type)`, throwing `ArrayStoreException` on mismatch; elided completely when `v` has an exact final type matching a non-covariant array) + `array.set`.
* **`for x in a then (...)`** (`BIP013` tier 2): hoists `struct.get $data` and `array.len` out of the loop, indexing `$data` with zero per-element bounds checks inside the loop.
* **`a == b` and `string(a)`:** `$equal` and `$format` test `ref.test (ref $ArrayBase)` and dispatch to `$array_deep_equals` / `$array_deep_format` — exact `BIP020-D14`/`D17` parity.
* **Multi-dimensional arrays (`array of (array of double)`):** Simply a `$RefArray` whose `$elem_type` is `ArrayDesc(DOUBLE)` and whose elements are `$DoubleArray`s.

#### Core Collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, `BIP025-D6`)
Rather than hand-writing thousands of lines of raw Wasm opcodes for dynamic arrays, hash tables, linked hash maps, and red-black trees (`TreeSet`/`TreeMap`), **`BIP025-D6` writes the Wasm core collections and higher-level string ops in Babble itself**:

* **Implementation note (`BIP025-I3`):** In the shipped implementation, portable `list`/`set`/`sortedset`/`map`/`sortedmap`/`entry`, `string` operations, and `Appendable` (`STDOUT`/`STDERR`) are emitted directly as compact WasmGC runtime helpers by `WasmCollectionOpsGen`, `WasmStringOpsGen`, and `WasmCoreEmitter` (matching every `java.util.*` / `java.lang.String` / `Appendable` behavioral contract in `WasmBatestTestCase` without requiring a separate `.ba` resource prelude).

### 5.5 Functions, Closures, Captures, Optional/Overloaded Arguments (`BIP021`) & Tail Calls (`BIP024`)

#### Closures & Capture Cells
Every Babble function literal has a known signature and capture list (`HirFunction.capturedVars()`):
* **Mutable captured variables** (already identified by `Resolver` / `HirVar.captured()`): lowered to typed 1-field WasmGC structs (`$IntCell`, `$DoubleCell`, `$BoolCell`, ..., `$RefCell`), mirroring Babble's existing `IntCell`..`CharCell` / `Cell` in `org.babblelang.runtime`.
* **Read-only captured variables:** stored directly by value in the closure's environment struct — zero cell indirection.
* **First-class closure representation:**
  ```wat
  ;; Base callable struct for first-class function values
  (type $FnBase (sub $Any
    (struct
      (field $generic_call (ref $GenericCallSig)))))  ;; (self: (ref $FnBase), args: (ref $AnyArr)) -> (ref null $Any)

  ;; Typed closure for a specific signature (e.g., (double, double) -> double)
  (type $Fn_dd_d (sub $FnBase
    (struct
      (field $generic_call (ref $GenericCallSig))
      (field $typed_call   (ref $Sig_self_dd_d)))))

  ;; Concrete closure instance capturing e.g. (scale: f64, count: (ref $IntCell))
  (type $Closure_42 (sub final $Fn_dd_d
    (struct
      (field $generic_call (ref $GenericCallSig))
      (field $typed_call   (ref $Sig_self_dd_d))
      (field $cap_scale    f64)
      (field $cap_count    (ref $IntCell)))))
  ```
* **Zero-allocation non-capturing functions:** If `capturedVars` is empty, the closure struct is allocated once as a Wasm `global` constant (just like JVM's constant `CompiledFunction`).

#### Call Dispatch, Optional/Named/Overloaded Arguments (`BIP021`) & Tail Calls (`BIP024`)
Babble's `CallSolver` and `LoweringAnalyzer` already resolve almost all calls statically:
* **`DirectBabbleCall`:** Direct Wasm `call $fn_N_spec(env, p0, p1, ...)` — zero closure indirection, zero argument boxing.
* **`SelfTailCall` (`BIP024`) & `SelfRecurseCall`:** `HirExpr.SelfTailCall` (`recurse(...)`, self-recursive non-reassigned `def`, and `this.m(...)` in tail position) emits Wasm 3.0 **`return_call`** for guaranteed $O(1)$ stack space (even simpler than JVM's loop rewrite); non-tail `SelfRecurseCall` emits a direct `call`.
* **Optional & named parameters (`BIP021-I1`):** `BIP021` already resolves named arguments statically into positional order and encodes omitted optional parameters as an `i32` missing-argument bitmask (`HirFunction.hasOptionalMask()`). On Wasm, that bitmask is simply an `i32` parameter checked with `i32.and (local.get $mask) (i32.const (1 << i))` before evaluating default expressions in parameter order.
* **Overload sets & union splits (`BIP021-I5`):** `MakeOverloadSet` constructs a tiny WasmGC tuple struct of alternative closures, `OverloadSelect` reads the statically chosen slot, and `OverloadDispatch` lowers its `OverloadBranch` guards directly to `ref.test` / interface-bitset checks dispatching to direct `call` / `call_ref`.
* **Typed higher-order calls (`DynamicCall` with known `FunctionSignature`):** `call_ref $Sig_self_...` through the typed `$typed_call` field of `$Fn_<sig>` — unboxed primitives, zero `AnyArr` allocation.
* **Non-escaping `borrow` closures (`fn calling f`, `BIP017-D6`):** Eligible for `HIR` inlining (`InlinePass`) or flat-environment passing without heap allocation.
* **Truly dynamic / `any` calls:** Fall back to `$generic_call` (`call_ref $GenericCallSig`) with argument count/type checks.

### 5.6 Classes, `record`s, Objects, Interfaces & Intersections (`BIP002`, `RM003`, `BIP022`)

#### Classes, `record`s & Objects (`BIP025-D11`)
Every Babble `class`, `record`, or `object` (`HirClass`) lowers to a WasmGC `struct` extending `$Object`:

```wat
(type $Object (sub $Any
  (struct
    (field $vtable (ref $VTable)))))

;; Example: `def Vec3 = record (x: double, y: double, z: double) ( ... )`
;; Because Vec3 is a `record` and FbipReusePass RecordReuse is skipped on Wasm (BIP025-D11),
;; ALL record fields are strictly immutable (non-mut) in WasmGC!
(type $Cls_Vec3 (sub final $Object
  (struct
    (field $vtable (ref $VTable_Vec3))
    (field $x f64)
    (field $y f64)
    (field $z f64))))
```

* **Mutable vs. strictly immutable fields (`BIP025-D11`):** Mutable `class` fields are `(mut <storage>)`; **all `record` fields** (and `BIP007` provably never-reassigned `class` fields) are **immutable** (`<storage>` without `mut`). Because WasmGC forbids `struct.set` on immutable fields — and marking fields `(mut ...)` would defeat cross-call GVN in V8 TurboFan/Cranelift and introduce GC write barriers — **`BIP025-D11` skips `FbipReusePass`'s `RecordReuse` (`BIP014-I4` `O10`) on Wasm targets**, relying instead on `ScalarReplacePass` (`O9`) to eliminate short-lived `record` allocations at the `HIR` level.
* **`BIP034` immutable `def` bindings:** Header `def` parameters of regular `class`es are initialized directly by `struct.new` and emitted as immutable WasmGC fields (`(field $f_a i32)`), while header `var`s stay `(field (mut ...))`. Assigning a `def` member reports `Cannot assign to def member : <name>` (records keep `Cannot reassign field of record <Name> : <name>`). Body `def` fields that are assigned unconditionally by a constructor which never lets `this` escape are known to be defined after construction, so their `$defined`-bitmask / `$UNDEFINED` read checks are skipped (and the `$defMask` field is omitted when no mutable primitive field needs it).
* **`BIP034-I5` value capture:** Immutable, definitely-initialized captured `def`s are stored by value in closure, class (`$cap*` / hidden `$hc*`) and interface-capture structs (`(field $cap0 i32)`) and read with a single `struct.get`, with no `$Cell` allocation; mutable `var`s and forward/self-recursive captures keep `(ref $IntCell)` .. `(ref $Cell)` cells.
* **Definitely-initialized names (`BIP034-D6`):** `HirExpr.NameRead`s annotated `definitelyDefined` by `HirNameInitAnalysis` skip the `$UNDEFINED` check, fold constant top-level `def`s (`def K: double = 2.5` → `f64.const 2.5`, also through static imports and immutable `static` constants), read non-constant primitive top-level `def`s from an unboxed Wasm global (`M<k>_gp_<name>`) instead of unboxing the module slot, and enable direct `call`s of top-level functions, immutable `static` methods and, for capture-free regular classes, the typed constructor (`call $C0_Box$new`). Each immutable `static def` also gets its own Wasm global (`$G_ST_<Class>_<name>`), read with `global.get`.
* **Uninitialized body `def` tracking:** Just like `HirEmitter`/`ClassGen` (`HirClass.primDefCount()`), classes with uninitialized primitive body `def`s carry a `(field $defined (mut i32))` bitmask checked before read, throwing `Member used before definition : <name>`. Reference body `def`s use a sentinel global `$UNDEFINED` (`(ref $Undefined)`) distinct from `null`.
* **Constructors & `object` singletons:** Each class gets a `$new_Cls_N` constructor function (plus a `$ClassVal` singleton for first-class class values, `classof(x)`, and `static` fields stored in Wasm globals). An `object` allocates its singleton into a module global on first evaluation.
* **`record` structural protocol:** Each `record` generates direct `$equals_Vec3`, `$hashCode_Vec3`, and `$toString_Vec3` functions installed in its `$VTable` — matching `BIP020-D18`'s JVM generation with zero reflection.
* **Operator methods (`a + b` → `a.plus(b)`):** Already statically resolved by `OperatorTypes` / `LoweringAnalyzer` to direct/interface method calls on known Babble types.

#### Interfaces, Default Methods, `super(I).m()` & Intersections (`BIP022`, `BIP025-D7`)
Because WasmGC `struct` types only support **single inheritance** (unlike JVM classes implementing multiple JVM interfaces in `RM003`), Babble interfaces cannot be raw WasmGC super-structs. However, because `babblec -target wasm-*` performs **closed-world whole-program compilation**, every class and interface in the program is statically known.

Under **`BIP025-D7`**, this unlocks a fast, compact dispatch and type-test scheme (inspired by TeaVM WasmGC and Kotlin/Wasm):

1. **$O(1)$ `x is I` and `BIP022` `x is (A & B)` via Interface Bitsets:**
   * Assign every Babble interface in the closed program a sequential ID $0 \dots K-1$.
   * Each class's `$VTable` embeds a constant bitset (for $K \le 64$, a single **`i64` field** `$iface_mask` directly inside `$VTable`; for $K > 64$, a constant `(ref (array i64))` word array).
   * `x is I` compiles to:
     ```wat
     ;; Single load + bitmask test — 3 instructions, zero loops!
     (i64.ne
       (i64.and (struct.get $VTable $iface_mask (struct.get $Object $vtable x))
                (i64.const (1 << ID_I)))
       (i64.const 0))
     ```
   * **Intersection `x is (A & B)` (`BIP022-D5`):** A **single combined mask test** `(mask & ((1 << ID_A) | (1 << ID_B))) == ((1 << ID_A) | (1 << ID_B))` — checking *any number* of intersected interfaces in **one** bitwise `AND` + `EQ`.

2. **Fast `InterfaceCall` Dispatch via Closed-World Itables:**
   * Each interface `I` defines a dedicated WasmGC struct `$ITable_I` whose fields are typed `(ref $MethodSig)` function references for every method of `I` (including specialized unboxed `$spec` signatures).
   * Each class implementing `I` constructs a constant global `$itable_Cls_I : (ref $ITable_I)` containing either its own override function or `I`'s default method function (closed over `I`'s captured cells if any).
   * To look up `$ITable_I` from an arbitrary `$Object`'s `$VTable`:
     * **Small programs ($K \le 32$):** Direct indexed array `(ref (array (ref null $ITableBase)))` on `$VTable` — `array.get (struct.get $VTable $itables vt) (i32.const ID_I)` + `ref.cast (ref $ITable_I)` + `call_ref` ($O(1)$, 4 instructions, zero branching).
     * **Large programs ($K > 32$):** Graph-coloring / row-displacement compressed itable (Vitek/Krall & TeaVM technique) so sparse classes do not pay $O(C \times K)$ table space while keeping dispatch $O(1)$.
   * **`super(I).m(...)`:** Statically known target interface `I` → direct `call $iface_I_default_m(this, ...)` with **zero** dynamic dispatch.
   * **Single-implementer devirtualization:** Because the program is closed-world, whenever an interface `I` has only **one** concrete implementing class in the whole program, `WasmLinker` devirtualizes `InterfaceCall` into a direct `call`.

### 5.7 Control Flow, `for` Loops, `break`/`continue` & `BIP028` Exceptions (`BIP025-D13`)

* **Structured control flow (`If`, `While`, `For`, `Break`, `Continue`, `Return`):**
  Babble has no `goto` and `HIR` is already a structured tree — mapping 1-to-1 onto WebAssembly's structured `if`/`else`, `block`, `loop`, `br`, `br_if`, and `return` with **zero Relooper/Stackifier overhead** (`BIP025-D3`):
  * **`while` / `for` with value-carrying `break expr` (`BIP013-D10`):**
    ```wat
    (block $break (result <T>)           ;; `break expr` emits `expr; br $break`
      (loop $continue
        ;; condition / iterator hasNext check -> if false: (default_val; br $break)
        ;; loop body (`continue` emits `br $continue`)
        (br $continue))
      (unreachable))
    ```
  * **`for` loop tiers (`BIP013`):**
    * `array of T`: direct unboxed indexed loop over `$data` (zero iterator allocation).
    * `list of T`: specialized fast indexed loop over backing array (or standard `iterator()`/`hasNext()`/`next()`).
    * Babble custom `Iterable`/`Iterator`: direct or itable `call_ref` to unboxed `$spec` `iterator()` / `hasNext()` / `next()`.

* **`BIP028` Exception Handling (`try`, `catch`, `finally`, `throw`, core `exception`, `BIP025-D13`):**
  `BIP028` landed expression-oriented `try`/`catch [e] [: A | B]`/`finally`/`throw`, the lowercase core `exception` type (`BabbleException`), open nominal Babble throws (`record ParseError(...)`), and automatic `addSuppressed` when `finally` throws while unwinding — all explicitly designed to avoid `java.*` / `org.babblelang.runtime.*` (`BIP028` §7.5). Under **`BIP025-D13`**, `HirExpr.Try`, `HirExpr.Throw`, and `HirExpr.CoreExceptionRef` lower cleanly onto WebAssembly 3.0 `try_table` + `exnref`:

  1. **Single unified exception tag & `$BabbleException` struct:**
     ```wat
     (type $BabbleException (sub final $Any
       (struct
         (field $message    (ref null $String))
         (field $cause      (ref null $Any))
         (field $suppressed (mut (ref null $AnyArr))))))

     (tag $babble_exn (param (ref $Any) i32))      ;; (thrownValue, sourceLine)
     ```
     * Runtime errors (failed `assert`, null dereference / `e!`, failed `as` cast, array bounds/store error, uninitialized variable/member read) and `throw exception(msg)` construct a `$BabbleException` and emit `(throw $babble_exn (exn, line))`.
     * Open nominal Babble throws (`throw NotFound("x")`) emit `(throw $babble_exn (val, line))` directly — zero wrapper allocation!

  2. **`try` / `catch` dispatch (`catch_ref` + `ref.test`/bitset + `throw_ref`):**
     * A `try` with `catch` clauses wraps `tryBody` in `(try_table (catch_ref $babble_exn $on_catch) ...)` so `$on_catch` receives `(val: (ref $Any), line: i32, exn: (ref exn))`.
     * Each `HirCatch` tests `val` with `ref.test` (for `exception` / concrete `record` / `class`), interface bitset test (for Babble interfaces / `BIP022` intersections), or union disjunction (`A | B`), binding `val` to the catch variable on match.
     * Untyped `catch [e] then (...)` matches any `$babble_exn` directly.
     * If no `catch` clause matches, `(throw_ref (local.get $exn))` rethrows the original `exnref` intact (preserving `line` and payload).

  3. **Structured 2-copy `finally` with `finallyNothrow` suppression elision:**
     Unlike JVM bytecode (which must duplicate `finally` at every `return`/`break`/`continue` and manually split `exception_table` ranges, `BIP028-D9`), WebAssembly's lexically scoped `try_table` supports a cleaner **2-copy `finally`** with zero range-splitting and zero exponential nesting blowup:
     ```wat
     (block $finally_normal
       (block $on_unwind (result (ref $Any) i32 (ref exn))
         (try_table (catch_ref $babble_exn $on_unwind)
           ;; tryBody (+ catch handlers if present)
           ;; normal exit stores result in $resTmp, sets $exitTag = NORMAL, br $finally_normal
           ;; `return v` / `break v` / `continue` stores payload, sets $exitTag, br $finally_normal
         )
         (br $finally_normal)
       )
       ;; Copy 1 (unwind path): runs OUTSIDE try_table!
       ;; If !finallyNothrow: wrap finallyBody in inner try_table (catch $babble_exn $suppress)
       ;;   that appends secondary to primary.$suppressed (when primary is $BabbleException
       ;;   and primary != secondary); elided completely when finallyNothrow == true!
       ;; Rethrow original exception: (throw_ref (local.get $exn))
     )
     ;; Copy 2 (normal & non-local exit path): runs ONCE outside try_table!
     ;; Execute finallyBody, then dispatch on $exitTag (normal -> $resTmp, return, break, continue).
     ;; (When no return/break/continue targets escape the try, $exitTag is omitted completely.)
     ```

  4. **Top-level uncaught handler:** At the module entrypoint (`_start`), a single `try_table (catch $babble_exn $on_err)` formats the uncaught value + source line to `STDERR` (matching `Interpreter` and JVM `CompiledBabbleScript.lineOf`) and signals failure to `app.mjs` (or lets JS catch a `WebAssembly.Exception`).
  5. **Zero happy-path overhead:** `try_table` installs zero per-call frame cost on non-throwing paths in V8, SpiderMonkey, and WebKit.

### 5.8 Wasm-Specific Optimizations Unlocked by `BIP007`/`BIP016`/`BIP019`

Babble's static effect and ownership system gives the Wasm backend optimization power that generic Java-to-Wasm compilers cannot recover from erased bytecode:

1. **Strictly immutable WasmGC fields (`record`, `frozen`, `BIP034` header `def` fields of regular `class`es, `BIP025-D11`):** Emitting `(field f64)` instead of `(field (mut f64))` tells V8 TurboFan that a field can never be mutated by any aliased reference or unknown call — enabling cross-call load elimination and GVN inside the Wasm JIT while avoiding GC write barriers.
2. **`nothrow` & `finallyNothrow` check elision:** Expressions proven `nothrow` by `EffectChecker` (`BIP007-D1` `Narrowing` guards, verified non-null references, `record` field reads) omit null-check branches and `throw $babble_exn` sites; `finallyNothrow` omits the inner suppression `try_table` in `finally` unwind handlers (`BIP025-D13`).
3. **Closed-world devirtualization + `BIP014-I4` `ScalarReplacePass`:** Combining Babble's already-landed `ScalarReplacePass` (`O9`, unboxing `borrow` `record`s like `Vec3` in `raytracer-argon`) with closed-world interface devirtualization removes the primary GC allocation bottleneck before Wasm emission.
4. **Tail calls (`BIP024` `return_call` / `return_call_ref`):** Unlike JVM bytecode, WebAssembly 3.0 natively guarantees tail-call elimination — lowering `HirExpr.SelfTailCall` directly to `return_call` in $O(1)$ stack space.
5. **Capability-enforced `wasm-bare` exports:** Because Babble tracks `io` statically (`BIP016`), the compiler verifies at compile time that a `-target wasm-bare` library module exports only pure/hermetic functions and needs **zero host imports**.

---

## 6. Host Interop, Standard Capabilities & CLI Surface

### 6.1 Target Profiles (`BIP025-D8`)

Extending `BIP018`'s `-target` flag on `babblec`, `BIP025` implements two WebAssembly target profiles in `BIP025-I0`–`I5` and defers `-target wasm-wasi` until WasmGC Component Model Canonical ABI (`[CM-GC]`) stabilizes (`BIP025-D8`):

| CLI Target | Status | Host Environment | Standard Host Imports | Emitted Artifacts | Typical Use Case |
|---|---|---|---|---|---|
| `-target wasm-bare` | **`BIP025-I1`** | Any Wasm 3.0 engine (embedded, plugin, sandbox, Node.js) | **None** (zero imports; `print`/`println`/`STDOUT`/`args` disallowed unless explicitly declared via host `axiom`) | `app.wasm` | Pure-compute libraries, deterministic plugins, embedded evaluators. |
| `-target wasm-js` | **`BIP025-I1` (minimal), `BIP025-I4` (full)** | Browsers (Chrome/Firefox/Safari) & Node.js (V8) | `babble:env` (`write_stdout`, `write_stderr`, `args`, `now_ms`), `wasm:js-string` boundary helpers + user `axiom wasm.*` JS host imports | `app.wasm` + `app.mjs` (thin ~2–3 KB ES module loader) | Browser playgrounds, interactive `<canvas>` apps (Argon in the browser), Node.js CLI/benchmarks & CI test execution. |
| `-target wasm-wasi` | **Deferred (`BIP025-D8`)** | Wasmtime 47+, WASI runtimes | WASI Component Model (awaiting `[CM-GC]` WasmGC Canonical ABI) | `app.wasm` (component) | Standalone WASI CLI/server components once `[CM-GC]` standardizes. |

### 6.2 Exporting Babble Functions to the Host

Every top-level `export def` function in the entry module becomes a WebAssembly export:

```babble
export def renderPixel = fn (x: int, y: int, w: int, h: int): int -> ( ... )
export def dot = fn (ax: double, ay: double, az: double, bx: double, by: double, bz: double): double -> (
    ax * bx + ay * by + az * bz
)
```

* Functions whose parameter and return types are **Wasm-native scalars** (`byte`..`char`, `null`/`void`) export directly with their unboxed Wasm ABI (`(func (export "dot") (param f64 f64 f64 f64 f64 f64) (result f64))`) — callable from JS with zero marshalling.
* Functions taking or returning `string` or `array of <prim>` on `-target wasm-js` get lightweight JS glue wrappers in the generated `app.mjs` that convert JS `string`/`TypedArray` ↔ WasmGC `$String`/`$DoubleArray`.
* Top-level script statements execute in an exported `_start()` function invoked automatically by `app.mjs` for script-mode programs.

### 6.3 Importing Host Functions via `BIP019`/`BIP030` Axioms (`BIP025-D9`)

Under **`BIP025-D9`**, WebAssembly host imports reuse Babble's existing **`BIP019`/`BIP030` `axiom`** syntax (`axiomTarget: (ID | coreScalarKeyword) ('.' memberName)*` in [`Babble.g4`](../../src/main/antlr4/org/babblelang/parser/Babble.g4#L31-L49)) with **zero grammar changes** and **zero new keywords** (preserving `BIP012`'s predicate-free pure-`SLL` parser):

```babble
;; Declaring browser Canvas and math host functions on -target wasm-js (BIP025-D9)
axiom wasm.env.canvasSetPixel: fn io nothrow (x: int, y: int, rgba: int): null
axiom wasm.env.canvasPresent: fn io nothrow (): null
axiom wasm.math.sin: fn nothrow (x: double): double
axiom wasm.math.cos: fn nothrow (x: double): double
axiom wasm.math.sqrt: fn nothrow (x: double): double
```

* An `axiom wasm.<mod>.<name>: fn ...` declaration emits a Wasm import `(import "<mod>" "<name>" ...)` and exposes `wasm.<mod>.<name>` as a callable function on Wasm targets.
* Pure Wasm math intrinsics like `sqrt`, `abs`, `floor`, `ceil`, `min`, `max`, `nearest` can lower directly to hardware Wasm instructions (`f64.sqrt`, `f64.abs`, etc.).
* `io` host imports (`canvasSetPixel`, `fetch`, `nowMs`) preserve Babble's exact pure-by-default effect discipline (`BIP016`) and explicit nullability (`BIP030`): a pure Babble function cannot accidentally call an `io` JS host import without declaring `io`, and `BIP014` optimization passes remain 100% sound across host calls.
* Curated `std/` modules (`std/math`, `std/canvas`) can wrap these axioms ergonomically once the standard library lands.

### 6.4 Command-Line Interface & Debugging Switches

```bash
# Compile a Babble program to WebAssembly (browser / Node.js)
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js main.ba -o app.wasm [-root dir] [-O0|-O1|-O2]

# Compile pure compute library (zero host imports)
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-bare lib.ba -o lib.wasm [-root dir] [-O0|-O1|-O2]

# Debugging switches (mirroring babble.dumpIr / babble.trace / babble.verify)
-Dbabble.dumpWasm=wat        # prints human-readable WAT disassembly to stderr (also feeds BIP029 .batest.md `wat` blocks)
-Dbabble.verify=true         # validates emitted .wasm binary via pure-Java WasmVerifier against Wasm 3.0 spec rules
-Dbabble.wasmDebugNames=true # emits standard Wasm `name` custom section for browser DevTools
```

---

## 7. Verification, Testing & `.batest.md` Parity (`BIP009`, `BIP029`, `BIP025-D10`)

Babble's engineering discipline rests on **continuous differential verification** (`Interpreter` vs JVM `compile -O1` vs JVM `compile -O0`, verified by `CheckClassAdapter`, `HirVerifier`, and `2,060+` `.batest.md` cases). The WebAssembly backend meets the exact same standard without adding any new Maven dependencies (`BIP025-D10`):

### 7.1 Four-Layer Verification Strategy

1. **Structural Binary Validation (`babble.verify=true`):**
   Every generated `.wasm` binary passes Babble's pure-Java `org.babblelang.wasm.WasmVerifier` (checking section ordering, type indices, stack/type conformance, WasmGC `sub`/`rec` rules, `try_table` labels, and `return_call` signatures) — the exact analogue of ASM's `CheckClassAdapter`.
2. **Fast Differential Execution via a Persistent Node.js (V8) Worker (`BIP025-D10`):**
   JUnit (`WasmTestCase` and a fourth `BatestTestCase` execution mode) drives a single persistent **Node.js (V8)** worker process over stdio/IPC that instantiates and runs both `-target wasm-bare` and `-target wasm-js` modules (`app.wasm` + `app.mjs` + `wasm:js-string` + `try_table`/`exnref` + tail calls) in milliseconds per case:
   * **Zero extra `pom.xml` dependencies:** No JVM Wasm runtime dependency (such as Endive) is added to Maven.
   * **CI & local ergonomics:** Mandatory in GitHub Actions CI (where Node.js 22+ is preinstalled); gracefully skipped locally if `node` is not on `PATH` (while `WasmVerifier` and `WatPrinter` unit tests still run unconditionally on plain JDK 21).
   * **4-mode `.batest.md` parity & `wat` goldens (`BIP029`):** Any `.batest.md` case that does not reference `java.*`/`javax.*` (identified automatically by `WasmPortabilityChecker`) runs in **4 modes** — `interpret`, `compile -O1`, `compile -O0`, and **`wasm`** — asserting identical `stdout`, `stderr`, return values, and error messages, alongside `BIP029`'s reserved `wat` golden/`check` blocks.
3. **Browser Cross-Engine Verification (`BIP025-I4`):**
   The interactive browser `<canvas>` demo verifies `-target wasm-js` across Chrome (V8), Firefox (SpiderMonkey), and Safari (WebKit), ensuring we never depend on V8-only quirks.
4. **Flagship End-to-End Acceptance Milestone — `examples/raytracer-argon`:**
   ```bash
   java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js \
       examples/raytracer-argon/raytracer.ba -o target/argon.wasm
   node target/argon.mjs > target/argon-wasm.ppm
   cmp examples/raytracer-argon/scene.ppm target/argon-wasm.ppm   # 1,021,340 bytes, 0 diff!
   ```
   Byte-for-byte identical output of a 14-module, floating-point-heavy, polymorphic ray tracer (using `record`s, `class`es, `interface`s, closures, `list`s, `for` loops, `while`, `break`, `out = STDOUT`, and `fn calling f`) across JVM and WebAssembly — with **zero edits** to `examples/raytracer-argon/` (`BIP025-D12`) — proves both semantic fidelity and real-world performance.

### 7.2 Known divergences from the JVM

Where Wasm is known to behave differently from the JVM modes, the `.batest.md` case declares it with `# <kind> backend:jvm` and `# <kind> backend:wasm` outcome blocks (`BIP029-D11`, [bip029](bip029_batest-v2-and-ir-goldens.md) §3.4.1) instead of being skipped on Wasm ; the case fails once the backends agree again. `grep -rn 'backend:wasm' src/test/batest` is the authoritative list ; `src/test/batest/runtime/backend_divergences.batest.md` collects them. Today : a builtin conversion or `assert` called through a variable (`def conv = byte ; conv(b + 1)`) quotes the builtin's name instead of its argument's text, because the generic call signature `(self, args, names)` carries no call-site text.

---

## 8. Design Decisions (`BIP025-D1`–`D13`)

All ten original open questions (`BIP025-Q1`–`Q10`) plus three post-draft architectural decisions (`BIP025-D11`–`D13`, prompted by `BIP014-I4`, `BIP028`, and auditing `builtins.ba`/`raytracer-argon`) are settled:

| Decision | Resolves | Summary |
|---|---|---|
| **`BIP025-D1`** | `BIP025-Q1` | **WebAssembly 3.0 exclusively:** Target Wasm 3.0 (`WasmGC` + typed function refs + `try_table`/`exnref` + tail calls) with no Wasm 2.0 linear-memory GC fallback. |
| **`BIP025-D2`** | `BIP025-Q2` | **Pure-Java `WasmWriter`:** Emit `.wasm` directly from `HIR` via a zero-dependency pure-Java `WasmWriter`, paired with `WatPrinter` (for `-Dbabble.dumpWasm=wat` and `BIP029` `wat` blocks) and an optional `-wasm-opt` hook when Binaryen is on `PATH`. |
| **`BIP025-D3`** | `BIP025-Q3` | **Clean `HIR` target neutralization & structured emission:** Decouple JVM metadata (`jvmName`, `Class<?>`, `Java*Target`) out of `HIR` in `BIP025-I0` (sealed `HirArrayElementType`, `CoreMethodCall`, target-neutral IDs) and emit Wasm directly from structured `HIR` (no Relooper/Stackifier; no dependency on lowering through CFG `MIR`). |
| **`BIP025-D4`** | `BIP025-Q4` | **Uniform `(array i16)` `$String`:** Represent `string` uniformly as a `sub final $Any` WasmGC struct wrapping immutable UTF-16 `(array i16)` (+ cached Java-compatible hash) across all Wasm targets, bridging to JS strings via `wasm:js-string` `fromCharCodeArray`/`intoCharCodeArray` at the `wasm-js` boundary. |
| **`BIP025-D5`** | `BIP025-Q5` | **Distinct `$Box*` structs first:** Lower boxed primitives (`any`/`T?`/unions/generics) to `sub final $Any` box structs (`$BoxByte`..`$BoxDouble`) with global singletons for `bool` and small cached `byte`/`int`/`char` values; defer bit-tagged `i31ref` for sub-32-bit primitives as an optional later optimization. |
| **`BIP025-D6`** | `BIP025-Q6` | **Babble-written portable core prelude:** Implement portable core collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`) and higher-level `string` ops in Babble prelude modules compiled via `HIR` and tree-shaken by `WasmLinker`, with strict-subset contracts in `src/main/java/org/babblelang/axioms/portable/` and only tiny low-level intrinsics emitted in Java. |
| **`BIP025-D7`** | `BIP025-Q7` | **Closed-world interface bitsets & itables:** Embed an `i64` (or word-array for $K > 64$) interface bitset on `$VTable` for $O(1)$ `is` and single-mask `BIP022` `A & B`, paired with closed-world typed itables (direct-indexed for small $K$, row-displacement compressed for large $K$) and single-implementer devirtualization. |
| **`BIP025-D8`** | `BIP025-Q8` | **Focus on `wasm-bare` & `wasm-js`, defer `wasm-wasi`:** Ship `-target wasm-bare` and `-target wasm-js` across `BIP025-I0`–`I5` (pulling minimal `wasm-js` into `BIP025-I1`), and defer `-target wasm-wasi` and WASI Component Model packaging until WasmGC Component Model Canonical ABI (`[CM-GC]`) stabilizes. |
| **`BIP025-D9`** | `BIP025-Q9` | **Zero-grammar-change host imports via `axiom`:** Reuse `BIP019`/`BIP030` `axiom` syntax (`axiom wasm.<mod>.<name>: fn ...`) with zero changes to `Babble.g4` to declare Wasm imports `"<mod>"` `"<name>"` and expose `wasm.<mod>.<name>` on Wasm targets with full `BIP016`/`BIP030` effect and nullability enforcement, paired with curated `std/` modules on top. |
| **`BIP025-D10`** | `BIP025-Q10` | **Zero-dep `WasmVerifier` + persistent Node.js (V8) worker:** Validate binaries in-JVM with pure-Java `WasmVerifier` and execute `wasm-bare` and `wasm-js` (`app.mjs`, `wasm:js-string`, `exnref`, Argon, 4-mode `.batest.md`) via a persistent Node.js (V8) JUnit worker — zero extra `pom.xml` dependencies, mandatory in CI, gracefully skipped locally if `node` is absent. |
| **`BIP025-D11`** | *(post-`BIP014-I4`)* | **Strictly immutable WasmGC `record` fields:** Skip `FbipReusePass` `RecordReuse` (`BIP014-I4` `O10`) on Wasm targets so every `record` struct field remains non-`mut` (enabling TurboFan/Cranelift GVN and avoiding GC write barriers), relying on `ScalarReplacePass` (`O9`) to eliminate short-lived `record` allocations at `HIR`. |
| **`BIP025-D12`** | *(portable I/O)* | **Portable `Appendable` (`STDOUT`/`STDERR`/`to:`) & `builtins.ba`:** Back `print`/`println`'s `to:` parameter with `java.lang.Appendable` on JVM and a 1-method portable `Appendable` interface (`append(s: string)`) implemented by `STDOUT`/`STDERR` and user buffers on Wasm, paired with a target-neutral `builtins.ba` view (stripping `java.lang.Number`) so `examples/raytracer-argon` compiles unmodified. |
| **`BIP025-D13`** | *(post-`BIP028`)* | **Full `BIP028` exception & structured 2-copy `finally` lowering:** Use a single `(tag $babble_exn (param (ref $Any) i32))` for both core `$BabbleException` (`message`/`cause`/`suppressed`) and open nominal Babble throws, `catch_ref`/`throw_ref` with `ref.test`/bitset matching, and a structured 2-copy `finally` (one shared normal/exit-tag copy outside `try_table` + one `catch_ref`/`throw_ref` unwind copy with `finallyNothrow` suppression elision). |

---

## 9. Phased Implementation Plan (`BIP025-I0`–`I5`)

Each phase is independently shippable, preserves 100% green 3-mode JVM `mvn -B package` (`interpret`, `compile -O1`, `compile -O0`, `RuntimeBoundaryTestCase`, `ArchitectureBoundaryTestCase`, `-format -check`), and adds incremental Wasm test coverage. In the overall roadmap, `BIP025-I0` was sequenced after `BIP014-I3` (SSA `MIR`) settled the `HIR`/`MIR` boundary so `HIR` target neutralization happened once cleanly.

### `BIP025-I0` — Target-Neutral `HIR`, Target Profiles, `WasmWriter` & `BIP029` `wat` Plumbing
* **Deliverables:**
  * Introduce `CompilationTarget` (`JVM`, `WASM_JS`, `WASM_BARE`; unified with `BIP018-P1`'s `TargetProfile`, whichever lands first) in `org.babblelang.compiler` and `org.babblelang.analysis`.
  * Refactor JVM-specific metadata out of `HirModule`, `HirClass`, `HirInterface`, `HirFunction`, and `HirExpr` (`ArrayCreate`/`ArrayOp` → `HirArrayElementType`, `CoreMethodCall` for portable `string`/collection/`exception` methods, `classTypeId` replacing `externalClassJvmName`/`classJvmName`, `BIP025-D3`) with **zero behavioral change** on the JVM backend.
  * Add `WasmPortabilityChecker` rejecting `java.*`/`javax.*`, dynamic `import(expr)`, and non-portable JDK methods on `WASM_*` targets with clear `Error : Not supported on target wasm : ...` diagnostics.
  * Implement `org.babblelang.wasm`: pure-Java Wasm 3.0 binary encoder (`WasmModule`, `WasmType`, `WasmFunc`, `WasmCodeBuilder`, `WasmWriter` with LEB128 + WasmGC `0xFB` / `try_table` / tail-call opcodes), `WatPrinter` (`-Dbabble.dumpWasm=wat`), `WasmVerifier` (`babble.verify=true`), and `BIP029` `.batest.md` `wat` block plumbing.
* **Exit criteria:** Full JVM `mvn -B package` green with zero diffs; unit tests emitting and validating hand-constructed WasmGC/exception/tail-call `.wasm` modules via `WasmVerifier` and Node.js (V8).

### `BIP025-I1` — Core Language MVP (`wasm-bare` & Minimal `wasm-js`, `BIP028` Exceptions, Tail Calls)
* **Deliverables:**
  * `WasmEmitter` lowering all 8 primitives (`byte`..`char`), numeric promotions/widening, exact `Operators` (`Double.compare` parity, `/` float division, wrap-around, `Truth`), `If`, `While`, `Break` (with value), `Continue`, `Return`, local/global variables, direct calls, `BIP024` `SelfTailCall` (`return_call`), `SelfRecurseCall`, first-class closures & capture cells, `BIP021` optional/named/overloaded arguments, full `BIP028` `try`/`catch`/`finally`/`throw` + `$BabbleException` (`BIP025-D13`), and basic `print`/`println`/`assert` host bindings.
  * Wire `-target wasm-bare` and minimal `-target wasm-js` (`app.wasm` + minimal `app.mjs` for stdout/stderr/exceptions/`_start`) into `Babblec` and `Main` (`BIP025-D8`).
  * Add persistent Node.js (V8) JUnit worker (`BIP025-D10`) and `WasmTestCase` executing core `.batest.md` files (arithmetic, comparisons, control flow, functions, closures, tail recursion, optional/named/overloaded args, `try`/`catch`/`finally`/`throw`) against `Interpreter` and JVM `compile`.
* **Exit criteria:** Core scalar/control-flow/function/closure/exception `.batest.md` suites pass with 0 divergences between `interpret`, `compile -O1`, `compile -O0`, and `wasm`.

### `BIP025-I2` — Object Model, Immutable `record`s, Interfaces, Intersections, Arrays & Multi-Module Linking
* **Deliverables:**
  * Lower `class`, strictly immutable `record`s (skipping `FbipReusePass` `RecordReuse` per `BIP025-D11`, with generated `equals`/`hashCode`/`toString`), `object` singletons, static blocks, `this`, `super(I).m()`, `$defined` bitmasks, and operator methods.
  * Lower Babble `interface`s (default methods, closed-world interface bitsets for `is` and `BIP022` `A & B`, closed-world itables for `InterfaceCall`, single-implementer devirtualization, `BIP025-D7`).
  * Lower `array of T` (`$IntArray`..`$CharArray` + covariant `$RefArray` with `ArrayStoreException`, `ArrayIndexOutOfBoundsException`, `BIP020-D14` deep formatting, and `BIP020-D17` deep equality).
  * Implement `WasmLinker` whole-program multi-module compilation (`BIP005` static imports, cyclic-import `$mod_state` guards, unboxed export globals, dead-code tree shaking, reproducible binary output).
* **Exit criteria:** Object model, `record`, interface, intersection, array, and multi-module `.batest.md` suites pass differentially in Wasm.

### `BIP025-I3` — Strings, Portable Core Collections, Portable `STDOUT` & **Argon Ray Tracer Milestone**
* **Deliverables:**
  * Complete `$String` (`(array i16)` + cached hash, `BIP025-D4`) and portable `string`/`exception` API (declared in `src/main/java/org/babblelang/axioms/portable/`).
  * Ship portable `Appendable` (`STDOUT`/`STDERR`/`to:`) and target-neutral `builtins.ba` view (`BIP025-D12`), plus Babble-written Wasm prelude (`BIP025-D6`) for `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, and `for`-loop iteration (`BIP013` across `array`, `list`, and custom Babble iterables/iterators).
  * Compile all 14 unmodified modules of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) with `-target wasm-js` to `target/argon.wasm` + `target/argon.mjs`, and verify **byte-for-byte identical `scene.ppm` (`1,021,340` bytes)** against the JVM baseline via `node target/argon.mjs`!
  * Add Wasm benchmark entries to `Benchmarks` comparing `interpret`, JVM `-O1`, and Node.js (V8).
* **Exit criteria:** Byte-identical `examples/raytracer-argon/scene.ppm` in WebAssembly with zero edits to Argon; all portable collection/iterator/string `.batest.md` cases green.

### `BIP025-I4` — Full Browser Interop (`wasm-js`), Host Axioms, Debugging & Interactive Demo
* **Deliverables:**
  * Complete `-target wasm-js` `app.mjs` glue (`wasm:js-string` `fromCharCodeArray`/`intoCharCodeArray`, `TypedArray` bridges for exported `array of <prim>`, stdout/stderr callbacks, `args`, and exception formatting).
  * Implement `axiom wasm.<mod>.<name>: fn ...` host-import bindings (`BIP025-D9`) with `BIP016`/`BIP030` effect and nullability enforcement (plus hardware Wasm math intrinsics).
  * Emit standard Wasm `name` custom section (function, local, and struct/field names) and optional source-map metadata for Chrome/Firefox/Safari DevTools debugging.
  * Ship an example browser page rendering `examples/raytracer-argon` progressively onto an HTML `<canvas>` using pure Babble + 2 Canvas host axioms.
* **Exit criteria:** Full `wasm-js` and host-axiom test suite green in Node.js (V8); browser `<canvas>` demo renders cleanly in Chrome, Firefox, and Safari.

### `BIP025-I5` — Wasm Optimizations, `wat` Goldens & Full Portable `.batest.md` Parity
* **Deliverables:**
  * Exploit `BIP007`/`BIP016`/`BIP028` (`frozen`, `nothrow`, `finallyNothrow`, `unique`) for immutable WasmGC fields, check elision, and `BIP014-I4` `ScalarReplacePass` synergy; add optional `-wasm-opt` hook when Binaryen is installed.
  * Add canonical `wat` golden/`check` assertions in `src/test/batest/compiler/*.batest.md` (`BIP029`) verifying immutable `record` fields, single-mask `A & B` checks, `return_call` tail calls, and `finallyNothrow` elision.
  * Integrate 4-mode portable `.batest.md` execution into GitHub Actions CI (`interpret`, `compile -O1`, `compile -O0`, `wasm` on Node.js V8).
* **Exit criteria:** 100% of portable `.batest.md` cases pass identically across all four execution modes; measurable speedup on `argon.wasm` from immutable fields + `ScalarReplacePass`.

### Deferred / Future Work (Post-`BIP025-I5`)
* **`-target wasm-wasi` & WASI Component Model (`BIP025-D8`):** Add standalone WASI CLI/server component output (with WIT export generation) once the WebAssembly Component Model WasmGC Canonical ABI (`[CM-GC]`) stabilizes, avoiding throwaway `wasi_snapshot_preview1` linear-memory staging.
* **Tagged `i31ref` boxing (`BIP025-D5`):** Optional internal optimization bit-packing boxed `bool`/`byte`/`short`/`char` into `i31ref` if boxing ever shows up in profiles.

---

## 10. Non-Goals & Boundaries

To keep `BIP025` focused and sustainable for a minimalist language project:

1. **No JDK class library emulation:** Babble will *not* re-implement `java.io`, `java.nio`, `java.awt`, `javax.imageio`, `java.net`, `java.lang.reflect`, or `java.util.concurrent` in WebAssembly. Programs targeting Wasm use Babble's Portable Core, `std/` modules, and explicit host axioms; JVM-specific programs (`examples/raytracer-fable` using `BufferedImage` and `Thread`) remain JVM targets.
2. **No `RuntimeBoundaryTestCase` regression & zero new Maven deps:** `babble-runtime.jar` remains strictly `java.base`-only; `org.babblelang.wasm` lives strictly on the compiler/front-end side (matching `org.babblelang.compiler`, `org.babblelang.ir`, and `org.babblelang.opt`), and neither main nor test scopes add new external Wasm dependencies (`BIP025-D2`, `BIP025-D10`).
3. **No dynamic runtime compilation inside `.wasm` guests:** `FileModuleCompiler` and REPL evaluation inside a Wasm module are out of scope (running the Babble compiler itself in a browser is a separate future concern, e.g., once `BIP015` removes ANTLR4 or via a browser-hosted compiler).
4. **No Wasm 1.0/2.0 linear-memory GC fallback (`BIP025-D1`):** WebAssembly 3.0 WasmGC is the single supported Wasm target model.
5. **No `wasm-wasi` before `[CM-GC]` (`BIP025-D8`):** Initial targets are strictly `wasm-bare` and `wasm-js`.
6. **No Wasm threads (`shared` GC structs) in initial phases:** Multi-threaded WasmGC awaits both Babble's `threadsafe` concurrency model (`BIP007` §10) and stabilization of the WebAssembly Shared-Everything Threads proposal.

---

## 11. References

* **`[WASM30]`** WebAssembly Community Group, *WebAssembly Specification, Release 3.0* (completed September 17, 2025; updated November 2026): <https://webassembly.org/news/2025-09-17-wasm-3.0/> and <https://webassembly.github.io/spec/core/>
* **`[JSSTR]`** WebAssembly Community Group, *JS String Builtins Proposal* (`wasm:js-string`, shipped across Chrome, Firefox, and Safari): <https://github.com/WebAssembly/js-string-builtins> and <https://developer.mozilla.org/en-US/docs/WebAssembly/Guides/JavaScript_builtins>
* **`[WASI03]`** Bytecode Alliance, *WASI 0.3 Launched* (June 2026; WASI 0.3.0 ratified October 2026): <https://bytecodealliance.org/articles/WASI-0.3> and <https://wasi.dev/releases/wasi-p3>
* **`[CM-GC]`** WebAssembly Component Model, *Pre-Proposal: Wasm GC Support in the Canonical ABI* (`#525`): <https://github.com/WebAssembly/component-model/issues/525>
* **`[WT47]`** Bytecode Alliance, *GC and Exceptions in Wasmtime* (Wasmtime 47, July 2026): <https://bytecodealliance.org/articles/wasmtime-gc> and *Exceptions in Cranelift and Wasmtime* (November 2025): <https://bytecodealliance.org/articles/wasmtime-exceptions>
* **`[ENDIVE]`** Bytecode Alliance, *Endive: JVM Native WebAssembly Runtime* (pure-Java continuation of Chicory with WasmGC host integration in 1.0 and `exnref`/`catch_ref`/`throw_ref` conformance in 1.2.0, October 2026; evaluated and not adopted as a Maven test dependency under `BIP025-D10`): <https://endive.run/> and <https://github.com/bytecodealliance/endive>
* **`[TEAVM]`** TeaVM, *WebAssembly GC Backend Documentation*: <https://teavm.org/docs/wasm-gc-backend/loader.html>
* **`[KTWASM]`** JetBrains, *Kotlin/Wasm Documentation* (WasmGC & `wasm-wasi` target): <https://kotlinlang.org/docs/wasm-overview.html>
* **Internal Babble BIPs:** [`BIP001`](bip001_bytecode-compiler.md), [`BIP002`](bip002_object-model.md), [`BIP006`](bip006_module-sources-and-aot.md), [`BIP007`](bip007_effects.md), [`BIP008`](bip008_static-typing.md), [`BIP009`](bip009_test-coverage.md), [`BIP011`](bip011_core-types.md), [`BIP013`](bip013_iterators.md), [`BIP014`](bip014_optimization-passes.md), [`BIP016`](bip016_consolidating-effect-annotations.md), [`BIP017`](bip017_higher-order-function-effects.md), [`BIP018`](bip018_tracking-java-improvements.md), [`BIP019`](bip019_refactoring-axioms.md), [`BIP020`](bip020_array-types.md), [`BIP021`](bip021_optional-variadic-overloaded-arguments.md), [`BIP022`](bip022_intersection-types.md), [`BIP023`](bip023_type-aliases.md), [`BIP024`](bip024_tail-calls.md), [`BIP027`](bip027_tackling-complexity-in-the-compiler.md), [`BIP028`](bip028_exception-handling.md), [`BIP029`](bip029_batest-v2-and-ir-goldens.md), [`BIP030`](bip030_axiom-nullability-and-inferred-effects.md)
