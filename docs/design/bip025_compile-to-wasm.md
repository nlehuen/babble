# BIP025: Compiling Babble to WebAssembly

| | |
|---|---|
| **BIP** | 025 |
| **Date** | 2026-11-22 |
| **Status** | Draft (`BIP025-Q1`–`Q10` open; architectural design and phased implementation plan) |
| **Depends on** | Bytecode compiler, `RM001` typed primitive lowering & `RM003` interfaces ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), object model & `record`s ([bip002_object-model.md](bip002_object-model.md)), modules ([bip005_modules.md](bip005_modules.md)), `ModuleSource` & `babblec` AOT compilation ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static effects, ownership & axioms (`RM002`/`RM007`/`RM008`/`RM009`, [bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md), [bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)), static typing, core types, arrays, optional/generic arguments, intersections & type aliases ([bip008_static-typing.md](bip008_static-typing.md), [bip011_core-types.md](bip011_core-types.md), [bip020_array-types.md](bip020_array-types.md), [bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md), [bip022_intersection-types.md](bip022_intersection-types.md), [bip023_type-aliases.md](bip023_type-aliases.md)), `.batest` differential parity harness ([bip009_test-coverage.md](bip009_test-coverage.md)), ANTLR-free `HIR` & Nanopass optimizer (`RM010`, [bip014_optimization-passes.md](bip014_optimization-passes.md) `BIP014-I0`–`I2`), and multi-target `babblec -target` CLI design ([bip018_tracking-java-improvements.md](bip018_tracking-java-improvements.md)) |
| **Scope** | Whole-program ahead-of-time compilation of Babble from ANTLR-free `HIR` to **WebAssembly 3.0** (`WasmGC`, typed function references, `try_table`/`exnref` exceptions, and tail calls); target-neutralizing `HIR` and defining Babble's portable core boundary; WasmGC lowering of all scalars, strings, arrays, core collections, closures, classes, `record`s, interfaces, intersections, unions, and exceptions; browser (`wasm-js`), CLI/server (`wasm-wasi`), and pure-compute (`wasm-bare`) target profiles; zero-dependency pure-Java `.wasm`/`.wat` emission; in-JVM differential testing against `Interpreter` and JVM `compile` via `.batest`; and a 6-phase rollout (`BIP025-P0`–`P5`) culminating in byte-identical `examples/raytracer-argon/scene.ppm` rendered in WebAssembly |

---

## 1. Executive Summary

Babble began as a JVM language with two execution modes: a tree-walking `Interpreter` and an ASM-based JVM bytecode compiler. Over the past ten milestones (`RM001`–`RM010` plus `BIP019`–`BIP023`), however, Babble has quietly acquired almost every structural prerequisite of a **multi-target compiled language**:

1. **An ANTLR-free, typed High-Level IR (`org.babblelang.ir.hir`, `BIP014-I1` / `RM010`):** `HirBuilder` lowers the analyzed parse tree into immutable Java 21 `record`s (`HirModule`, 46-variant sealed `HirExpr`, `HirFunction`, `HirClass`, `HirInterface`, `ExprMeta`), optimized by `org.babblelang.opt` (`ConstFoldPass`, `AlgebraicSimplifyPass`, `InlinePass`, `HirDcePass`, `HirLicmPass`) before `HirEmitter` ever touches JVM bytecode.
2. **Strict static typing & 8-primitive unboxing (`BIP008`, `BIP011`, `RM001`):** Every expression carries a converged static `Type` and `PrimitiveKind` (`BYTE`..`CHAR`, `NONE`), eliminating runtime type tags on hot numeric paths.
3. **Plain compiled classes & first-class arrays (`BIP020-D18`):** Compiled Babble classes and `record`s no longer inherit a JVM runtime base class (`InterpretedInstance` is interpreter-only), and `array of T` is a first-class language type.
4. **Pure-by-default effects, ownership & external axioms (`BIP007`, `BIP016`, `BIP017`, `BIP019`):** The compiler already knows statically which functions are pure, `nothrow`, `@TERMINATING`, or `io`, which values are `frozen` or `unique`, and which host operations are contracted via `axiom`s.
5. **A non-trivial pure-Babble application already in-repo:** [`examples/raytracer-argon/`](../../examples/raytracer-argon/) is a 14-module ray tracer written in **100% pure Babble with zero `java.*` or `javax.*` calls** — using only Babble's core scalars, `string`, `list`, `record`, `class`, `interface`, closures, `for`/`while`, `print`/`println`, and `assert`.

At the same time, **WebAssembly 3.0** became a W3C standard in September 2025 (`[WASM30]`), unifying **WasmGC**, **typed function references**, **`try_table`/`exnref` exception handling**, and **tail calls** into a single baseline now shipped across all major browsers (Chrome/V8, Firefox/SpiderMonkey, Safari/WebKit), standalone runtimes (**Wasmtime 47+**, July 2026), and even pure-Java in-JVM runtimes (**Endive 1.0**, June 2026, Bytecode Alliance continuation of Chicory).

Adding a **WebAssembly 3.0 backend** directly beneath Babble's `HIR` optimizer advances both of Babble's founding objectives (`README.md`):

* **Teaching beginners with zero friction:** A Babble program compiled with `babblec -target wasm-js` runs directly in any web browser — no JDK installation, no server round-trip, and direct access to HTML `<canvas>` / DOM playgrounds for visual exercises (such as rendering `raytracer-argon` live in a browser tab).
* **Compiler engineering & architectural hygiene:** Targeting a second, genuinely different VM (structured control flow, nominal GC structs/arrays with single inheritance, invariant GC arrays, no reflection or `invokedynamic`, explicit host imports) stress-tests Babble's `HIR` and forces a crisp boundary between *Babble the language* and *Babble's JVM host*.
* **Fast-starting, sandboxed AOT artifacts:** Sub-millisecond cold start and compact `.wasm` binaries (tens of KB rather than a JRE + fat jar) for CLI, edge, and embedded-plugin workloads (`-target wasm-wasi` and `-target wasm-bare`).

This proposal lays out the ecosystem baseline (§2), compares four compilation strategies (§3), audits every JVM leak in `HIR` and the front end (§4), specifies the exact lowering of every Babble construct to WebAssembly 3.0 (§5), defines the host interop and CLI surface (§6), designs an in-JVM differential test harness (§7), enumerates ten open design questions (`BIP025-Q1`–`Q10`, §8), and outlines a six-phase implementation plan (`BIP025-P0`–`P5`, §9).

---

## 2. State of the Art & Ecosystem Baseline (2025–2026)

### 2.1 WebAssembly 3.0 Core Specification

WebAssembly 3.0 (`[WASM30]`, W3C Release 3.0, September 2025) standardized the exact features that high-level garbage-collected languages previously lacked:

| Wasm 3.0 Feature | Key Instructions / Types | Direct Value to Babble |
|---|---|---|
| **Garbage Collection (`WasmGC`)** | `struct`, `array`, `i31ref`, `sub`, `sub final`, `rec`, `struct.new`, `struct.get`/`set`, `array.new`, `array.get`/`set`, `ref.test`, `ref.cast`, `br_on_cast`, `anyref`, `eqref` | Maps Babble classes, `record`s, closures, capture cells, boxed scalars, and `array of T` directly onto host-managed GC objects with zero custom GC or shadow stack. Packed `i8`/`i16` struct/array fields map `byte`, `short`, `bool`, `char` without waste. |
| **Typed Function References** | `(ref $fn_type)`, `call_ref`, `ref.func` | Direct typed indirect calls for closures, vtable methods, and itable interface dispatch without boxing arguments into linear memory or routing through a single untyped `funcref` table. |
| **Exception Handling (`exnref`)** | `tag`, `throw`, `throw_ref`, `try_table` | Zero-cost happy-path exception handling for `BabbleException`, `NullPointerException`, `ArrayIndexOutOfBoundsException`, `ArrayStoreException`, `AssertionError`, and `NumberFormatException` — replacing the legacy `try`/`catch` proposal across all browsers and Wasmtime. |
| **Tail Calls** | `return_call`, `return_call_ref`, `return_call_indirect` | Constant-stack tail recursion for Babble's `recurse(...)` and mutual tail calls — strictly stronger than JVM bytecode, which still lacks tail calls! |
| **Multiple Memories & Memory64** | `(memory $m ...)`, `i64` linear pointers | Isolates WASI/JS I/O staging buffers from user data if ever needed, without polluting GC heaps. |
| **Deterministic Profile & Relaxed SIMD** | Deterministic NaN canonicalization option | Aligns with Babble's strict differential parity (`Interpreter` vs JVM vs Wasm) and future vectorization (`Vec3` math). |

### 2.2 Runtime & Browser Support Matrix (as of November 2026)

| Runtime / Engine | WasmGC | `try_table` / `exnref` | Tail Calls | `wasm:js-string` | Notes for Babble |
|---|---|---|---|---|---|
| **Chrome / Edge / Node.js (V8)** | Shipped | Shipped | Shipped | Shipped | Primary browser & Node CLI/benchmark engine; TurboFan optimizes immutable GC fields aggressively. |
| **Firefox (SpiderMonkey)** | Shipped | Shipped | Shipped | Shipped | Full Wasm 3.0 + JS string builtins parity. |
| **Safari (WebKit)** | Shipped | Shipped (2025) | Shipped | Shipped (2025) | Completed cross-browser WasmGC + `exnref` + `js-string-builtins` support (`[JSSTR]`). |
| **Wasmtime 47+** (Bytecode Alliance) | **Enabled by default** (Jul 2026) | **Enabled by default** (Jul 2026) | Shipped | N/A (host) | Reference CLI/server/WASI engine (`[WT47]`); Cranelift compiles WasmGC + `try_table` natively. |
| **Endive 1.0** (Bytecode Alliance, fork of Chicory) | **Shipped** (Jun 2026, JVM GC host integration) | In progress / check exact coverage | Shipped | N/A (host) | **100% pure Java, zero native deps (`[ENDIVE]`).** Runs `.wasm` directly inside `mvn test` on plain JDK 21, delegating WasmGC objects to the JVM GC! |
| **GraalWasm** (Oracle) | Supported (`--wasm.GC`) | Supported | Supported | N/A (host) | Optional alternative JVM Wasm engine for Polyglot/GraalVM users. |

### 2.3 WASI 0.2 / 0.3 & the Component Model Boundary

**WASI 0.3.0** (`[WASI03]`, launched June 2026, ratified October 2026) sits on the **WebAssembly Component Model** (WIT interfaces + native async). However, **WasmGC integration into the Component Model Canonical ABI** is still at pre-proposal stage (`[CM-GC]`, `WebAssembly/component-model#525`): Component Model interfaces currently pass strings, lists, and records across component boundaries via **linear memory** (`realloc` + pointer/length), not directly as WasmGC `struct`/`array` references.

Consequently, every production WasmGC compiler today (Kotlin/Wasm, TeaVM-WASI, Dart) bridges WasmGC heaps to WASI by allocating a small linear-memory scratch buffer for UTF-8 I/O (`stdout`, `stderr`, `args`, file paths) while keeping all language objects in WasmGC structs and arrays. Babble should adopt the exact same pragmatic boundary and upgrade to direct GC-Component lowering once `[CM-GC]` standardizes.

### 2.4 Lessons from Prior Art in GC-Language → WasmGC Compilers

| Compiler | Input → Output | Key Design Lessons for Babble |
|---|---|---|
| **Kotlin/Wasm** (`[KTWASM]`, Beta Sep 2025) | Kotlin IR → WasmGC + `exnref` (`wasm-js` & `wasm-wasi`) | Compiles directly from a typed high-level IR (not JVM bytecode); uses closed-world whole-program WasmGC compilation, compact interface dispatch tables, and linear-memory staging for WASI; achieves ~3× speedup over Kotlin/JS on UI workloads. |
| **TeaVM WasmGC** (`[TEAVM]`) | JVM `.class` → WasmGC | Proves Java-like OOP (classes, multiple interfaces, virtual dispatch, exceptions, arrays) compiles cleanly to WasmGC with tiny binaries; demonstrates the **interface dispatch** and **array covariance** patterns needed because WasmGC structs are single-inheritance and WasmGC arrays are invariant. |
| **Dart WasmGC** (Flutter Web) | Dart Kernel IR → WasmGC + `wasm:js-string` | Highlights the `externref` vs `eqref` boundary (`any.convert_extern`) when using JS strings, and shows the dramatic performance gains of marking immutable fields non-`mut` in WasmGC structs (matching Babble `record`s and `frozen`!). |
| **J2Wasm** (Google Sheets) | Java source → WasmGC | Validates production WasmGC performance on numeric/object-heavy workloads; motivates `rec`-group minimization and devirtualization. |
| **MoonBit & OCaml (`wasm_of_ocaml`)** | Typed IR → WasmGC | Demonstrates clean direct binary `.wasm` emission without LLVM/Binaryen as a hard dependency, and shows how closed-world monomorphization/unboxing shrinks code size. |

---

## 3. Compilation Strategy: Options Evaluation

Before committing to a pipeline, we evaluate four concrete architectures for compiling Babble to WebAssembly.

| Criterion | **Option A: Direct `HIR` → Wasm 3.0 (`WasmWriter`)** *(Recommended)* | **Option B: JVM `.class` → TeaVM WasmGC** | **Option C: `HIR` → Linear-Memory Wasm 2.0 + Custom GC/RC** | **Option D: `HIR` → LLVM IR / Binaryen C++** |
|---|---|---|---|---|
| **External build/runtime deps** | **Zero** (pure Java 21 binary emitter; optional `wasm-opt` if on `PATH`) | Heavy (TeaVM + JDK classlib emulation) | Zero (custom emitter) | Heavy (native LLVM/Binaryen binaries per OS/arch) |
| **Reuse of Babble front end & `org.babblelang.opt`** | **100%** (shares `BlockScopes`, `TypeChecker`, `EffectChecker`, `HirBuilder`, `PassManager`) | 100%, *plus* `HirEmitter` | 100% | 100% |
| **Compatibility with Babble's JVM runtime** | Does not rely on `invokedynamic` / `MethodHandles` / `LambdaMetafactory` / `ClassValue` / reflection | **Broken / high friction:** Babble's `Bootstrap`, `Overloads`, `Faces`, and `BabbleObjects` rely heavily on `invokedynamic`, `MethodHandles.privateLookupIn`, `LambdaMetafactory`, and `ClassValue` — unsupported or fragile in TeaVM | Independent of JVM runtime | Independent of JVM runtime |
| **GC & runtime footprint** | Host engine GC (tiny `.wasm`, ~5–30 KB for small programs) | Host engine GC, but dragged up by JDK classlib emulation (~150 KB–1 MB+) | Must ship & maintain custom GC or cycle-collecting RC + shadow stack | Must ship custom GC or emit WasmGC via Binaryen |
| **Effect & ownership leverage (`BIP007`/`BIP016`/`BIP014`)** | **Full:** immutable WasmGC fields (`frozen`/`record`), `nothrow` elision, `return_call` tail recursion, `ScalarReplace` | Erased at JVM bytecode boundary (one-way wall, `BIP014` §2.1) | Enables Perceus/FBIP RC on `unique`, but high engineering cost | Good, at the cost of native C++ toolchain coupling |
| **Learning & maintainability value** | **Very high:** ~2,500 lines of clean Java 21 matching `HirEmitter` + `ClassGen` | Low (black-box transpiler) | Very high complexity (GC bugs) | High operational friction (cross-platform native tools) |
| **Wasm engine reach (2026)** | All browsers, Node.js, Wasmtime 47+, Endive 1.0, GraalWasm | Same (WasmGC) | Universal (Wasm 1.0/2.0) | Depends on target |

**Recommendation (`BIP025-Q1`, `BIP025-Q2`):** **Option A — Direct `HIR` → WebAssembly 3.0 (WasmGC) via a pure-Java `WasmWriter`**.

Why this fits Babble like a glove:
1. `BIP014-I1` (`RM010`) already did the hard work of detaching compilation from ANTLR4 into a 46-variant sealed `HirExpr` tree. Writing a Wasm emitter against `HirModule` is architecturally symmetric to `HirEmitter`.
2. Babble's `pom.xml` currently has only two compiler dependencies (`antlr4-runtime`, `asm`). A pure-Java Wasm 3.0 binary encoder (`org.babblelang.wasm.WasmWriter`) adds **zero** compile-time or runtime dependencies, keeping `babble.jar` self-contained and portable across OSes.
3. Optional post-optimization with Binaryen's `wasm-opt -O3 --enable-gc --enable-exception-handling --enable-tail-call` can be offered as an opt-in flag (`-wasm-opt`) when `wasm-opt` is present on `PATH`, without ever making it mandatory.

---

## 4. Target-Neutralizing the Front End & `HIR` (`BIP025-P0`)

A direct audit of `src/main/java/org/babblelang/ir/hir/` and `src/main/java/org/babblelang/analysis/` reveals exactly where JVM assumptions currently live. Most of the front end is already target-neutral; the JVM leaks are localized and small:

### 4.1 Codebase Audit of Current JVM Coupling

| File / Subsystem | Current JVM-Specific Field or Behavior | Target-Neutral Refactoring (`BIP025-P0`) |
|---|---|---|
| [`HirModule.java`](../../src/main/java/org/babblelang/ir/hir/HirModule.java) | `className`, `allInterfaceJvmNames`, `allClassJvmNames`, `ownInterfaceJvmNames`, `ownClassJvmNames` | Replace `className` with target-neutral `moduleId` / `symbolPrefix`; move JVM internal name maps into an optional `JvmLoweringMetadata` record (or derive them inside `HirEmitter` from `TypeHierarchyInfo` + `moduleId`). |
| [`HirClass.java`](../../src/main/java/org/babblelang/ir/hir/HirClass.java) & [`HirInterface.java`](../../src/main/java/org/babblelang/ir/hir/HirInterface.java) | `jvmName`, `implementedJvmInterfaces`, `javaInterfaces`, `extendedJvmInterfaces`, `ClassShape` | Key classes/interfaces by their stable Babble `typeId` (`lib/vec3.ba#Vec3`, already used by `BIP008` `T17` and `typeHierarchies`!). Keep `javaInterfaces` empty on non-JVM targets (rejected at type-check time). |
| [`HirFunction.java`](../../src/main/java/org/babblelang/ir/hir/HirFunction.java) | `jvmMethodName`, `hasSpec()` (`slots <= 250` JVM limit) | Rename `jvmMethodName` → `mangledName`; move the JVM 250-slot check into `HirEmitter` (Wasm supports up to 1,000+ parameters/locals without 2-slot `long`/`double` accounting). |
| [`HirExpr.java`](../../src/main/java/org/babblelang/ir/hir/HirExpr.java) | `JavaFieldGet`, `JavaFieldAssign`, `JavaConstructorCall`, `JavaMethodCall`; `ArrayCreate` & `ArrayOp` carry `Class<?> componentClass` / `Class<?> arrayClass` and `componentInternalName`; `CoreCollectionCall` & `CoreCollectionRef` | Replace `Class<?>` on `ArrayCreate`/`ArrayOp` with a sealed `HirArrayElementType` (`Primitive(PrimitiveKind)`, `String`, `BabbleClass(typeId)`, `BabbleInterface(typeId)`, `Array(elem)`, `CoreCollection(kind)`, `Any`, `JavaClass(Class<?>)`). On Wasm targets, `Java*` nodes are forbidden by `TypeChecker` / `WasmPortabilityChecker` (except portable `string` / core-collection method calls lowered to dedicated `HirExpr.CoreMethodCall` nodes!). |
| [`ExprMeta.java`](../../src/main/java/org/babblelang/ir/hir/ExprMeta.java) | `checkedType` (JVM/Babble descriptor string), `PrimitiveKind`, `stringType`, `deeplyImmutable`, `EffectSet` | Already ~95% target-neutral! Replace opaque string-encoded `checkedType` or pair it with structured `HirTypeDesc` so Wasm doesn't have to parse JVM internal names out of descriptor strings. |
| [`Type.java`](../../src/main/java/org/babblelang/analysis/Type.java) & [`BIP011`](bip011_core-types.md) | `string` resolves methods via `java.lang.String.class`; `list`/`set`/`sortedset`/`map`/`sortedmap`/`entry` resolve methods via `java.util.*` reflection; `Implicits` exposes `java`/`javax` packages | Introduce a `CompilationTarget` (`JVM`, `WASM_JS`, `WASM_WASI`, `WASM_BARE`) passed into analysis. On Wasm targets: (1) `java`/`javax` roots are absent (or produce a targeted portability error), and (2) `string` and core collections resolve their **Portable Core API** from `src/main/java/org/babblelang/axioms/portable/` (reusing `BIP019`'s axiom syntax!), guaranteeing that portable methods have identical signatures and effects on both JVM and Wasm! |

### 4.2 The Portable Core Boundary

One of Babble's greatest strengths on the JVM — `BIP011`'s zero-wrapper transparency where `string` is `java.lang.String` and `list` is `java.util.List` — creates an obvious question on WebAssembly: *which methods exist on `string`, `list`, `set`, `sortedset`, `map`, `sortedmap`, and `entry` when there is no JDK?*

BIP025 proposes a clean, **strict-subset** rule:

> **Portable Core Rule:** Every method in the Wasm Portable Core API of `string`, `array`, `list`, `set`, `sortedset`, `map`, `sortedmap`, and `entry` is an **exact semantic subset** of its JVM counterpart — same name, same parameter/return types, same nullability, same `BIP016` effects, and same exception conditions. Any code that compiles under `-target wasm-*` is guaranteed to compile and behave identically on the JVM (unless it explicitly imports a Wasm-host-only API such as `wasm:js`).

In `HirBuilder`, calls on `string` or core collection receivers (`list.add`, `list.get`, `list.size`, `list.clear`, `string.length`, `string.substring`, `map.put`, `map.get`, etc.) are recognized cleanly:
* On `JVM`, they lower to `JavaMethodCall` (direct `INVOKEVIRTUAL`/`INVOKEINTERFACE` on `java.lang.String` / `java.util.List`, zero overhead, unchanged from today).
* On `WASM_*`, any method in the Portable Core contract lowers to `HirExpr.CoreMethodCall` (dispatched to the Wasm core runtime / Babble prelude), while any JVM-only method (e.g., `s.getBytes(Charset)`, `s.intern()`, `list.parallelStream()`) is rejected at compile time with a clear diagnostic:

```
Error : Not supported on target wasm : java.lang.String.intern (line 12, column 5)
Error : Not supported on target wasm : java.io.File (line 3, column 15)
```

Notably, auditing [`examples/raytracer-argon/`](../../examples/raytracer-argon/) shows that its 14 modules use **only** `list()`, `list.add(x)`, `list.get(i)`, `list.size()`, `list.clear()`, `for x in xs`, `string` concatenation `+`, `print`/`println`, `assert`, `int(x)`/`double(x)`/`string(x)`, and Babble classes/records/interfaces/closures — 100% inside the smallest possible Portable Core!

---

## 5. Lowering Babble Semantics to WebAssembly 3.0 (WasmGC)

### 5.1 Whole-Program Compilation & Module Linking

Following `babblec` (`BIP006`), compiling a Babble program to WebAssembly is **whole-program AOT**:

1. `BabbleScriptEngine.compileProgram(source, mainPath)` parses, type-checks, effect-checks, and builds `HirModule`s for the main script and every statically imported `.ba` module (`BIP005` M2/M3).
2. `PassManager.defaultFor(optLevel)` runs Babble's HIR Nanopass optimizer (`ConstFoldPass`, `AlgebraicSimplifyPass`, `InlinePass` with cross-module small-export inlining, `HirDcePass`, `HirLicmPass`) across all modules.
3. `WasmLinker` merges the module graph into a **single closed-world `WasmProgram`**:
   * Assigns globally unique type indices, struct/array layouts, interface bit indices, vtable slots, and function indices across all modules in deterministic order (preserving `BIP006`'s reproducible-build guarantee: same sources → identical `.wasm` bytes!).
   * Each Babble module $M$ gets:
     * A Wasm `global` `$mod_state_M : (mut i32)` (`0` = uninitialized, `1` = initializing, `2` = initialized) — matching `BIP005`'s cyclic-import registration semantics!
     * Wasm `global`s for each exported `def` (unboxed primitive globals for non-nullable primitive exports, `(ref null $Any)` for references — zero hash-table lookup on `ModuleExportGet`, generalizing `RM001`'s `Module.exportAt`!).
     * A `$mod_init_M` function executing `M.topLevelStatements` once on first import or entry.
   * Dynamic `import(nonLiteral)` fails at compile time on Wasm targets (`Error : Dynamic import is not supported on target wasm : ...`), matching standalone `babblec` without `FileModuleCompiler` (`BIP006`).
   * Optional dead-code tree shaking drops unreachable functions, classes, and core-prelude helpers before emitting the binary.

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
| `char` | `i32` (`0..65535`, masked `& 0xFFFF`) | `i16` (`struct.get_u`) | `(ref $BoxChar)` (ASCII `0..127` singleton table optional) |
| `null` | `(ref null none)` (`ref.null none`) | `(ref null none)` | `ref.null eq` |
| `T?` (ref `T`) | `(ref null $T)` | `(ref null $T)` | `(ref null $T)` — **zero wrapper!** |
| `T?` (prim `T`) | `(ref null $BoxT)` | `(ref null $BoxT)` | `(ref null $BoxT)` |
| `string` | `(ref $String)` *(see §5.3 & `BIP025-Q4`)* | `(ref $String)` | `(ref $String)` |
| `any` / `any?` | `(ref $Any)` / `(ref null $Any)` | `(ref $Any)` / `(ref null $Any)` | Root of Babble's WasmGC `sub` hierarchy |
| Union `A \| B` | `(ref $Lub)` (or `(ref $Any)`) | `(ref $Lub)` | Dispatched via `ref.test` / `br_on_cast` |
| `nothing` (`return`/`break`) | Unreachable stack-polymorphic | N/A | N/A |

**Why distinct `$Box*` structs instead of raw `i31ref` (`BIP025-Q5`)?**
WasmGC's `i31ref` stores an unboxed 31-bit signed integer inside a tagged pointer with zero allocation. However, Babble distinguishes `byte`, `short`, `int`, `char`, and `bool` at runtime (`x is byte`, `x is int`, `x is char`, `x is bool`, `classof(x)`), and `int` requires the full 32-bit signed range (`-2147483648..2147483647`). Storing raw `i31ref` would either truncate `int` to 31 bits or lose the type distinction between `byte(1)`, `short(1)`, `int(1)`, `char(1)`, and `true`.
* **Baseline design:** Every boxed primitive is a `sub final $Any` struct (`$BoxByte`..`$BoxDouble`), giving exact $O(1)$ `ref.test (ref $BoxInt)` semantics for `is`, `as`, and `classof`, with zero-allocation global singletons for `$TRUE`/`$FALSE` and small cached `int`/`byte`/`char` values (`-128..127`, matching JVM `Integer.valueOf`).
* **Optional future optimization:** Tag-pack `bool`, `byte`, `short`, and `char` into `i31ref` using the top bits as a 3-bit type tag (`i31ref` has 31 bits, while `short`/`char` only need 16 bits!), reserving `$BoxInt` only for `int`. Because `RM001` already keeps non-nullable scalars unboxed in locals, parameters, returns, fields, and arrays, boxing only occurs at `any`/union/generic boundaries anyway.

**Exact Babble Operator & Truthiness Parity (`Operators.java`, `Truth.java`):**
Subtle semantic differences between raw Wasm instructions and Babble's JVM semantics must be preserved identically so `.batest` differential tests never diverge:

1. **Floating-point `==`, `<>`, `<`, `<=`, `>`, `>=`:** Babble's [`Operators.java`](../../src/main/java/org/babblelang/runtime/Operators.java) compares floats/doubles via **`Double.compare`** (and `Float.compare`), **not** IEEE-754 `f64.eq`:
   * `NaN == NaN` is **`true`** in Babble, and `NaN > +Infinity`!
   * `-0.0 < +0.0`, so `-0.0 == +0.0` is **`false`** in Babble!
   * In Wasm, a small 10-instruction helper `$f64_compare(a: f64, b: f64) -> i32` using `f64.eq` / `f64.lt` / `f64.gt` plus `i64.reinterpret_f64` for signed zeros and NaN canonicalization reproduces `Double.compare` bit-for-bit (and for `a < b` when both are statically known non-NaN or inside fast paths, `f64.lt` + NaN check costs only 2 instructions).
2. **Division `/`:** Always floating-point in Babble (`int / int` widens to `f64.div`; `float / float` or `int / float` uses `f32.div`), never traps on division by zero (produces `±Infinity` or `NaN` per IEEE-754).
3. **Integer wrap-around:** `i32.add`/`sub`/`mul` and `i64.add`/`sub`/`mul` match Java two's-complement wrap-around natively. Negating `INT_MIN` (`-2147483648`) wraps to `-2147483648` via `i32.sub(0, x)`.
4. **Truthiness (`Truth.test`):** `bool` is itself; numbers are truthy iff `!= 0.0` (note: `NaN` is truthy because `NaN != 0.0` is `true` in Java!); any reference is truthy iff `!ref.is_null`. When the static type is `bool`, `HirExpr.If`/`While`/`Logic` emits direct `i32` branch with zero dispatch.
5. **String `+`:** When left operand is `string`, `a + b` formats `b` via `$format(b)` (including `ArraySupport.deepToString` semantics for arrays, `BIP020-D14`) and concatenates.

### 5.3 Strings (`string`)

String representation is one of the highest-leverage design choices in WasmGC (`BIP025-Q4`). There are two clean options:

1. **Option S1 — Uniform WasmGC String Struct (`$String`):**
   ```wat
   (type $CharArray (array i16))               ;; immutable UTF-16 code units (matches JVM char / String!)
   (type $String (sub final $Any
     (struct
       (field $chars (ref $CharArray))
       (field $hash (mut i32)))))              ;; lazily cached Java-compatible hashCode
   ```
   * **Pros:**
     * `$String` is a normal `sub final $Any` (`eqref` subtype) — zero friction with `any`, `any?`, `string | int`, `list of string`, `ref.test (ref $String)`, and `same(a, b)`!
     * **100% identical across `wasm-js`, `wasm-wasi`, and `wasm-bare`** — one implementation of every `string` method (`length`, `charAt`, `substring`, `indexOf`, `split`, `trim`, `compareTo`, `hashCode`, `equals`) with exact UTF-16 `char` indexing matching Babble's JVM `string` semantics!
     * Zero dependency on `wasm:js-string` (runs identically on Wasmtime 47+ and Endive 1.0 as well as browsers).
   * **Cons:** Crossing the JS boundary (`wasm-js` DOM/Canvas calls or `console.log`) requires copying code units into a JS string (fast via `wasm:js-string`'s `fromCharCodeArray` in browsers, which directly reads `(array i16)` in a single engine builtin call!).

2. **Option S2 — `wasm:js-string` (`externref`) on `wasm-js`, `$String` on `wasm-wasi`:**
   * **Pros:** Zero-copy JS string interop in browsers; delegates `concat`/`length`/`equals`/`substring` to V8/SpiderMonkey/WebKit native string engines.
   * **Cons:** In WasmGC, `externref` is **not** a subtype of `eqref`/`anyref` without `any.convert_extern` (which produces an opaque `anyref` that cannot be `ref.cast` to a Babble `$Any` struct without wrapping in a `$JsStringBox` or widening Babble's root type from `$Any` to `anyref`); splits codegen and runtime between `wasm-js` and `wasm-wasi`; JavaScript string comparison/hashing differs from Java's `String.hashCode()`.

**Leaning recommendation (`BIP025-Q4`):** Use **Option S1 (`$String` wrapping immutable `(array i16)`)** as the uniform internal representation across all Wasm targets, and use **`wasm:js-string` `fromCharCodeArray` / `intoCharCodeArray`** at the `wasm-js` host boundary so JS interop is still fast without complicating the WasmGC type hierarchy or diverging between browser and WASI!

### 5.4 Arrays (`array of T`, `BIP020`) & Core Collections (`BIP011`)

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
* **`a.get(i)`** → bounds check (throwing Babble's `ArrayIndexOutOfBoundsException` instead of an uncatchable Wasm trap!) + `array.get` (+ `ref.cast` on `$RefArray` when element type is statically known, elided if proven safe).
* **`a.set(i, v)`** → bounds check + exact element-type check (`$is_instance(v, a.$elem_type)`, throwing `ArrayStoreException` on mismatch; elided completely when `v` has an exact final type matching a non-covariant array!) + `array.set`.
* **`for x in a then (...)`** (`BIP013` tier 2): hoists `struct.get $data` and `array.len` out of the loop, indexing `$data` with zero per-element bounds checks inside the loop!
* **`a == b` and `string(a)`:** `$equal` and `$format` test `ref.test (ref $ArrayBase)` and dispatch to `$array_deep_equals` / `$array_deep_format` — exact `BIP020-D14`/`D17` parity!
* **Multi-dimensional arrays (`array of (array of double)`):** Simply a `$RefArray` whose `$elem_type` is `ArrayDesc(DOUBLE)` and whose elements are `$DoubleArray`s.

#### Core Collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`)
Rather than hand-writing thousands of lines of raw Wasm opcodes for dynamic arrays, hash tables, linked hash maps, and red-black trees (`TreeSet`/`TreeMap`), **BIP025 proposes writing the Wasm core collections in Babble itself** (`BIP025-Q6`)!

* A small internal prelude under `src/main/resources/org/babblelang/wasm/prelude/`:
  * `entry.ba`: `record` / `class` `Entry of (K, V)` (`getKey()`, `getValue()`, `equals`, `hashCode`, `toString`).
  * `list.ba`: `ArrayList of T` backed by `array of T?` with capacity doubling, `add`, `get`, `set`, `remove`, `size`, `isEmpty`, `clear`, `contains`, `indexOf`, `iterator()`, `from:`, `capacity:`, `.of(...)`.
  * `map.ba` & `set.ba`: insertion-ordered `LinkedHashMap of (K, V)` and `LinkedHashSet of T` (matching `BIP011-D1`'s deterministic iteration order!).
  * `sorted.ba`: `TreeMap of (K, V)` and `TreeSet of T` (left-leaning red-black tree with optional `comparator:`).
  * `string_ops.ba`: pure-Babble implementations of `split`, `replace`, `trim`, `indexOf`, `startsWith`, `endsWith`, `contains` on top of intrinsic `length`/`charAt`/`substring`.
* **Why this is huge:**
  1. Dogfoods Babble itself (and tests the Wasm compiler on real data-structure code!).
  2. Automatically benefits from every `org.babblelang.opt` HIR pass (`ConstFold`, `Inline`, `LICM`, `DCE`).
  3. Can even be unit-tested on the JVM `Interpreter` against `java.util.*` to verify 100% behavioral equivalence before Wasm emission!
  4. Dead-code elimination (`WasmLinker`) only includes the collections a program actually uses (e.g., `raytracer-argon` only pulls in `list`, never `TreeMap` or `LinkedHashSet`!).

### 5.5 Functions, Closures, Captures, Optional Arguments (`BIP021`) & Tail Calls

#### Closures & Capture Cells
Every Babble function literal has a known signature and capture list (`HirFunction.capturedVars()`):
* **Mutable captured variables** (already identified by `Resolver` / `HirVar.captured()`): lowered to typed 1-field WasmGC structs (`$IntCell`, `$DoubleCell`, `$BoolCell`, ..., `$RefCell`), mirroring Babble's existing `IntCell`..`CharCell` / `Cell` in `org.babblelang.runtime`!
* **Read-only captured variables:** stored directly by value in the closure's environment struct — zero cell indirection!
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

#### Call Dispatch & Optional / Named Arguments (`BIP021`)
Babble's `CallSolver` and `LoweringAnalyzer` already resolve almost all calls statically:
* **`DirectBabbleCall`:** Direct Wasm `call $fn_N_spec(env, p0, p1, ...)` — zero closure indirection, zero argument boxing!
* **`SelfRecurseCall`:** Direct `call` — and when in tail position (e.g., `return recurse(...)` or tail expression of `if`/function body), emits Wasm 3.0 **`return_call`** for guaranteed $O(1)$ stack space!
* **Optional & named parameters (`BIP021-I1`):** `BIP021` already resolves named arguments statically into positional order and encodes omitted optional parameters as an `i32` missing-argument bitmask (`HirFunction.hasOptionalMask()`). On Wasm, that bitmask is simply an `i32` parameter checked with `i32.and (local.get $mask) (i32.const (1 << i))` before evaluating default expressions in parameter order!
* **Typed higher-order calls (`DynamicCall` with known `FunctionSignature`):** `call_ref $Sig_self_...` through the typed `$typed_call` field of `$Fn_<sig>` — unboxed primitives, zero `AnyArr` allocation!
* **Non-escaping `borrow` closures (`fn calling f`, `BIP017-D6`):** Eligible for HIR inlining (`InlinePass`) or flat-environment passing without heap allocation.
* **Truly dynamic / `any` calls:** Fall back to `$generic_call` (`call_ref $GenericCallSig`) with argument count/type checks.

### 5.6 Classes, `record`s, Objects, Interfaces & Intersections (`BIP002`, `RM003`, `BIP022`)

#### Classes, `record`s & Objects
Every Babble `class`, `record`, or `object` (`HirClass`) lowers to a WasmGC `struct` extending `$Object`:

```wat
(type $Object (sub $Any
  (struct
    (field $vtable (ref $VTable)))))

;; Example: `def Vec3 = record (x: double, y: double, z: double) ( ... )`
;; Because Vec3 is a `record` (all fields immutable), WasmGC marks them non-mut!
(type $Cls_Vec3 (sub final $Object
  (struct
    (field $vtable (ref $VTable_Vec3))
    (field $x f64)
    (field $y f64)
    (field $z f64))))
```

* **Mutable vs. immutable fields:** Class fields are `(mut <storage>)`; `record` fields (and `BIP007` provably never-reassigned fields) are **immutable** (`<storage>` without `mut`), letting V8 TurboFan and Wasmtime Cranelift hoist and GVN field reads freely!
* **Uninitialized body `def` tracking:** Just like `HirEmitter`/`ClassGen` (`HirClass.primDefCount()`), classes with uninitialized primitive body `def`s carry a `(field $defined (mut i32))` bitmask checked before read, throwing `Member used before definition : <name>`. Reference body `def`s use a sentinel global `$UNDEFINED` (`(ref $Undefined)`) distinct from `null`.
* **Constructors & `object` singletons:** Each class gets a `$new_Cls_N` constructor function (plus a `$ClassVal` singleton for first-class class values, `classof(x)`, and `static` fields stored in Wasm globals). An `object` allocates its singleton into a module global on first evaluation.
* **`record` structural protocol:** Each `record` generates direct `$equals_Vec3`, `$hashCode_Vec3`, and `$toString_Vec3` functions installed in its `$VTable` — matching `BIP020-D18`'s JVM generation with zero reflection.
* **Operator methods (`a + b` → `a.plus(b)`):** Already statically resolved by `OperatorTypes` / `LoweringAnalyzer` to direct/interface method calls on known Babble types!

#### Interfaces, Default Methods, `super(I).m()` & Intersections (`BIP022`)
Because WasmGC `struct` types only support **single inheritance** (unlike JVM classes implementing multiple JVM interfaces in `RM003`), Babble interfaces cannot be raw WasmGC super-structs. However, because `babblec -target wasm` performs **closed-world whole-program compilation**, every class and interface in the program is statically known!

This unlocks an **exceptionally fast, compact dispatch and type-test scheme** (inspired by TeaVM WasmGC and Kotlin/Wasm):

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
   * **Intersection `x is (A & B)` (`BIP022-D5`)** is even better: a **single combined mask test** `(mask & ((1 << ID_A) | (1 << ID_B))) == ((1 << ID_A) | (1 << ID_B))` — checking *any number* of intersected interfaces in **one** bitwise `AND` + `EQ`!

2. **Fast `InterfaceCall` Dispatch via Closed-World Itables (`BIP025-Q7`):**
   * Each interface `I` defines a dedicated WasmGC struct `$ITable_I` whose fields are typed `(ref $MethodSig)` function references for every method of `I` (including specialized unboxed `$spec` signatures!).
   * Each class implementing `I` constructs a constant global `$itable_Cls_I : (ref $ITable_I)` containing either its own override function or `I`'s default method function (closed over `I`'s captured cells if any).
   * To look up `$ITable_I` from an arbitrary `$Object`'s `$VTable`:
     * **Small programs ($K \le 32$):** Direct indexed array `(ref (array (ref null $ITableBase)))` on `$VTable` — `array.get (struct.get $VTable $itables vt) (i32.const ID_I)` + `ref.cast (ref $ITable_I)` + `call_ref`! (Strictly $O(1)$, 4 instructions, zero branching!)
     * **Large programs ($K > 32$):** Graph-coloring / row-displacement compressed itable (classic Vitek/Krall & TeaVM technique) so sparse classes do not pay $O(C \times K)$ table space while keeping dispatch $O(1)$!
   * **`super(I).m(...)`:** Statically known target interface `I` → direct `call $iface_I_default_m(this, ...)` with **zero** dynamic dispatch!
   * **Single-implementer devirtualization:** Because the program is closed-world, whenever an interface `I` has only **one** concrete implementing class in the whole program, `WasmLinker` devirtualizes `InterfaceCall` into a direct `call`!

### 5.7 Control Flow, `for` Loops, `break`/`continue` & Exceptions

* **Structured control flow (`If`, `While`, `For`, `Break`, `Continue`, `Return`):**
  Babble has no `goto` and `HIR` is already a structured tree — mapping 1-to-1 onto WebAssembly's structured `if`/`else`, `block`, `loop`, `br`, `br_if`, and `return` with **zero Relooper/Stackifier overhead**!
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
    * `array of T`: direct unboxed indexed loop over `$data` (zero iterator allocation!).
    * `list of T`: specialized fast indexed loop over backing array (or standard `iterator()`/`hasNext()`/`next()`).
    * Babble custom `Iterable`/`Iterator`: direct or itable `call_ref` to unboxed `$spec` `iterator()` / `hasNext()` / `next()`.

* **Exceptions (`try_table` / `exnref`):**
  Babble has no user-facing `try`/`catch` syntax today, but runtime failures (`BabbleException`, `NullPointerException`, `ArrayIndexOutOfBoundsException`, `ArrayStoreException`, `AssertionError`, `NumberFormatException`) **must** abort execution with the exact Babble error message and source line number (required by `DifferentialTestCase` and `.batest`!).
  * Declare one module-level Wasm 3.0 exception tag:
    ```wat
    (tag $babble_exn (param (ref $String) i32))   ;; (message, sourceLine)
    ```
  * Any runtime error (failed `assert`, null dereference / `e!`, failed `as` cast, array bounds/store error, uninitialized variable/member read) throws `(throw $babble_exn (msg, line))`!
  * At the module's top-level entrypoint, a single `try_table (catch $babble_exn $on_err)` catches unhandled Babble exceptions, reports the formatted message + line to the host error sink (matching `Interpreter`), and returns exit code `1` (or lets JS catch a `WebAssembly.Exception` in `wasm-js`).
  * **Zero happy-path overhead:** `try_table` installs zero per-call frame cost on non-throwing paths in V8, SpiderMonkey, WebKit, and Wasmtime.

### 5.8 Wasm-Specific Optimizations Unlocked by `BIP007`/`BIP016`/`BIP019`

Babble's static effect and ownership system gives the Wasm backend optimization power that generic Java-to-Wasm compilers cannot recover from erased bytecode:

1. **Immutable WasmGC fields (`record`, `frozen`, non-reassigned `class` fields):** Emitting `(field f64)` instead of `(field (mut f64))` tells V8 TurboFan and Cranelift that a field can never be mutated by any aliased reference or unknown call — enabling cross-call load elimination and GVN inside the Wasm JIT!
2. **`nothrow` check elision:** Expressions proven `nothrow` by `EffectChecker` (`BIP007-D1` `Narrowing` guards, verified non-null references, `record` field reads) omit null-check branches and never emit `throw $babble_exn` sites, shrinking `.wasm` binary size and freeing registers.
3. **Closed-world devirtualization + `BIP014` `ScalarReplace`:** Combining `BIP014`'s planned `ScalarReplacePass` (`O9`, unboxing `borrow` `record`s like `Vec3` in `raytracer-argon`) with closed-world interface devirtualization removes the primary GC allocation bottleneck before Wasm emission.
4. **Tail calls (`return_call` / `return_call_ref`):** Unlike JVM bytecode, WebAssembly 3.0 natively guarantees tail-call elimination — making idiomatic recursive Babble functions (`recurse(...)`) run in $O(1)$ stack space with zero stack overflow risk.
5. **Capability-enforced `wasm-bare` exports:** Because Babble tracks `io` statically (`BIP016`), the compiler can verify at compile time that a `-target wasm-bare` library module exports only pure/hermetic functions and needs **zero host imports**.

---

## 6. Host Interop, Standard Capabilities & CLI Surface

### 6.1 Target Profiles

Extending `BIP018`'s `-target` flag on `babblec`, BIP025 introduces three WebAssembly target profiles:

| CLI Target | Host Environment | Standard Host Imports | Emitted Artifacts | Typical Use Case |
|---|---|---|---|---|
| `-target wasm-bare` | Any Wasm 3.0 engine (embedded, plugin, sandbox) | **None** (zero imports; `print`/`println`/`args` disallowed unless explicitly declared via host `axiom`) | `app.wasm` | Pure-compute libraries, deterministic plugins, embedded evaluators. |
| `-target wasm-js` | Browsers (Chrome/Firefox/Safari) & Node.js | `babble:env` (`write_stdout`, `write_stderr`, `args_count`, `arg_len`, `arg_copy`, `now_ms`, optional `wasm:js-string` boundary helpers + user JS host axioms) | `app.wasm` + `app.mjs` (thin ~2–3 KB ES module loader) | Browser playgrounds, interactive `<canvas>` apps (e.g., Argon ray tracer in the browser), Node.js CLI/tooling. |
| `-target wasm-wasi` | Wasmtime 47+, Endive 1.0, WASI runtimes | WASI stdout/stderr/args/clock (`wasi_snapshot_preview1` initially, WASI 0.2/0.3 Component wrapper in `P5`, `BIP025-Q8`) bridged via a small linear-memory I/O buffer | `app.wasm` | Standalone fast-starting CLI executables, server/edge Wasm workloads, in-JVM Endive testing. |

### 6.2 Exporting Babble Functions to the Host

Every top-level `export def` function in the entry module becomes a WebAssembly export:

```babble
export def renderPixel = fn (x: int, y: int, w: int, h: int): int -> ( ... )
export def dot = fn (ax: double, ay: double, az: double, bx: double, by: double, bz: double): double -> (
    ax * bx + ay * by + az * bz
)
```

* Functions whose parameter and return types are **Wasm-native scalars** (`byte`..`char`, `null`/`void`) export directly with their unboxed Wasm ABI (`(func (export "dot") (param f64 f64 f64 f64 f64 f64) (result f64))`) — callable from JS or Wasmtime with zero marshalling!
* Functions taking or returning `string` or `array of <prim>` on `-target wasm-js` get lightweight JS glue wrappers in the generated `app.mjs` that convert JS `string`/`TypedArray` ↔ WasmGC `$String`/`$DoubleArray`.
* Top-level script statements execute in an exported `_start()` function (standard WASI CLI convention and invoked automatically by `app.mjs` for script-mode programs).

### 6.3 Importing Host Functions via `BIP019` Axioms (`BIP025-Q9`)

Rather than inventing new keywords (preserving `BIP012`'s zero-predicate pure-`SLL` grammar!), WebAssembly host imports can reuse Babble's existing **`BIP019` `axiom`** syntax and **`BIP016` effect annotations**:

```babble
;; Declaring browser Canvas host functions on -target wasm-js (illustrative syntax — see BIP025-Q9)
axiom wasm.env.canvasSetPixel: fn io nothrow (x: int, y: int, rgba: int): null
axiom wasm.env.canvasPresent: fn io nothrow (): null
axiom wasm.math.sin: fn pure nothrow (x: double): double
axiom wasm.math.cos: fn pure nothrow (x: double): double
axiom wasm.math.sqrt: fn pure nothrow (x: double): double
```

* Pure Wasm math functions like `sqrt` can even lower directly to hardware Wasm instructions (`f64.sqrt`, `f64.abs`, `f64.floor`, `f64.ceil`, `f64.min`, `f64.max`, `f64.nearest`, `i32.clz`/`ctz`/`popcnt`)!
* `io` host imports (`canvasSetPixel`, `fetch`, `nowMs`) preserve Babble's exact pure-by-default effect discipline: a pure Babble function cannot accidentally call an `io` JS host import without declaring `io`, and `BIP014` optimization passes remain 100% sound across host calls!

### 6.4 Command-Line Interface & Debugging Switches

```bash
# Compile a Babble program to WebAssembly (browser / Node.js)
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js main.ba -o app.wasm [-root dir] [-O0|-O1|-O2]

# Compile to standalone WASI executable
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-wasi main.ba -o app.wasm [-root dir] [-O0|-O1|-O2]

# Compile pure compute library (zero host imports)
java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-bare lib.ba -o lib.wasm [-root dir] [-O0|-O1|-O2]

# Debugging switches (mirroring babble.dumpIr / babble.trace / babble.verify)
-Dbabble.dumpWasm=wat        # prints human-readable WAT disassembly to stderr
-Dbabble.verify=true         # validates emitted .wasm binary against Wasm 3.0 spec rules
-Dbabble.wasmDebugNames=true # emits standard Wasm `name` custom section for browser DevTools
```

---

## 7. Verification, Testing & `.batest` Parity (`BIP009`)

Babble's engineering discipline rests on **continuous differential verification** (`Interpreter` vs JVM `compile -O1` vs JVM `compile -O0`, verified by `CheckClassAdapter`, `HirVerifier`, and `1,100+` `.batest` cases). The WebAssembly backend must meet the exact same standard:

### 7.1 Four-Layer Verification Strategy

1. **Structural Binary Validation (`babble.verify=true`):**
   Every generated `.wasm` binary passes a Wasm 3.0 validator (both our own structural verifier in `org.babblelang.wasm.WasmVerifier` and, in tests, Endive's / `wasm-tools validate` spec checker) — the exact analogue of ASM's `CheckClassAdapter`.
2. **In-JVM Differential Execution via Endive 1.0 (`BIP025-Q10`):**
   Adding **Endive 1.0** (`[ENDIVE]`, pure Java, zero native deps, Apache-2.0, Bytecode Alliance) as a `<scope>test</scope>` dependency in `pom.xml` enables JUnit (`WasmTestCase` and a fourth `BatestTestCase` execution mode) to compile Babble programs to `.wasm` and execute them **directly inside `mvn test` on any JDK 21+ machine without installing Node.js or Wasmtime**!
   * Any `.batest` case that does not reference `java.*`/`javax.*` (tagged automatically by `WasmPortabilityChecker` — or explicitly marked `portable`) runs in **4 modes**: `interpret`, `compile -O1`, `compile -O0`, and **`wasm`**, asserting identical stdout, return values, and error messages!
   * *(Fallback / complement if Endive's `try_table`/`exnref` coverage lags any edge case: detect `node` / `wasmtime` on `PATH` and run a CI matrix job executing the full portable `.batest` suite on both **Node.js (V8)** and **Wasmtime 47+**!)*
3. **Cross-Engine CI Verification:**
   GitHub Actions runs an additional fast job validating and executing compiled `.wasm` suites on **Node.js (V8)** (`-target wasm-js`) and **Wasmtime 47+** (`-target wasm-wasi`), guaranteeing we never accidentally depend on a single engine's quirks.
4. **Flagship End-to-End Acceptance Milestone — `examples/raytracer-argon`:**
   ```bash
   java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-wasi \
       examples/raytracer-argon/raytracer.ba -o target/argon.wasm
   wasmtime target/argon.wasm > target/argon-wasm.ppm
   cmp examples/raytracer-argon/scene.ppm target/argon-wasm.ppm   # 1,021,340 bytes, 0 diff!
   ```
   Byte-for-byte identical output of a 14-module, floating-point-heavy, polymorphic ray tracer (using `record`s, `class`es, `interface`s, closures, `list`s, `for` loops, `while`, `break`, and `fn calling f`) across JVM and WebAssembly proves both semantic fidelity and real-world performance!

---

## 8. Open Questions (`BIP025-Q1`–`Q10`)

The following ten decisions should be settled before `BIP025-P0` begins implementation. Each lists the options and a current leaning recommendation.

* **`BIP025-Q1` — Minimum WebAssembly specification baseline:**
  * *Options:* (a) **WebAssembly 3.0** (`WasmGC` + typed function refs + `try_table`/`exnref` + tail calls); (b) WebAssembly 2.0 linear memory + custom GC.
  * *Leaning:* **(a) WebAssembly 3.0**. Standardized in September 2025 and shipped in Chrome, Firefox, Safari, Node.js, Wasmtime 47+, and Endive 1.0.

* **`BIP025-Q2` — Emitter architecture & external toolchain policy:**
  * *Options:* (a) Direct `HIR` → pure-Java `WasmWriter` binary encoder (zero mandatory external deps, optional `wasm-opt` hook); (b) emit `.wat` text and shell out to `wat2wasm`/`wasm-tools`; (c) transpile JVM `.class` via TeaVM.
  * *Leaning:* **(a) Pure-Java `WasmWriter`**, plus a `WatPrinter` for `-Dbabble.dumpWasm=wat` debugging. Zero external binaries needed to build or run `babblec -target wasm-*`.

* **`BIP025-Q3` — `HIR` target neutralization scope (`P0`):**
  * *Options:* (a) Cleanly separate JVM-specific fields (`jvmName`, `Class<?>`, `Java*Target`) out of `HirModule`/`HirClass`/`HirExpr` before writing `WasmEmitter`; (b) leave `HIR` untouched and work around JVM fields inside `WasmEmitter`.
  * *Leaning:* **(a) Clean `P0` refactoring first.** Keeps `HIR` honest as a true multi-target IR and also benefits `BIP018`'s JDK Class-File API backend.

* **`BIP025-Q4` — `string` representation on WasmGC:**
  * *Options:* (a) Uniform WasmGC `$String` wrapping immutable `(array i16)` across all targets, bridging to JS strings via `wasm:js-string` `fromCharCodeArray`/`intoCharCodeArray` at the `wasm-js` boundary; (b) native `wasm:js-string` (`externref`) on `wasm-js` and `(array i16)` on `wasm-wasi`; (c) UTF-8 `(array i8)` everywhere.
  * *Leaning:* **(a) Uniform `(array i16)` `$String`**. Preserves exact UTF-16 `char`/`length()`/`charAt()` parity with JVM Babble, keeps `$String <: $Any <: eqref` with zero `externref` wrapping friction, and uses one codegen path across browsers and WASI.

* **`BIP025-Q5` — Primitive boxing vs. `i31ref`:**
  * *Options:* (a) Distinct `sub final $Any` box structs (`$BoxByte`..`$BoxDouble`) with global singletons for `bool` and small `int`/`byte`/`char`; (b) bit-tagged `i31ref` for sub-32-bit primitives (`bool`, `byte`, `short`, `char`) + `$BoxInt` for 32-bit `int`.
  * *Leaning:* **(a) First, (b) as an internal optimization later.** Both preserve exact `is`/`classof` semantics; (a) is simpler to verify for `P1`–`P3`.

* **`BIP025-Q6` — Implementing portable core collections (`list`..`sortedmap`, `entry`) & `string` helpers:**
  * *Options:* (a) Write the Wasm portable core runtime in **Babble itself** (compiled as an internal prelude on top of `array of T` + primitives); (b) hand-emit Wasm instructions in Java inside `WasmRuntimeGen`.
  * *Leaning:* **(a) Write core collections in Babble prelude modules**, with only tiny low-level intrinsics (e.g., `$f64_compare`, `$format_double`, `$array_copy`, host I/O) emitted directly in Wasm. Dramatically reduces `WasmEmitter` complexity and dogfoods the compiler.

* **`BIP025-Q7` — Interface dispatch (`InterfaceCall`) & `is` representation:**
  * *Options:* (a) Closed-world `i64`/word-array interface bitset on `$VTable` for `is`/`A & B` + direct-indexed (small $K$) or row-displacement compressed (large $K$) itables; (b) linear scan of `(iface_id, itable)` pairs on `$VTable`; (c) single-table `call_indirect` with runtime slot lookup.
  * *Leaning:* **(a) Interface bitset + closed-world itables** (starting with direct-indexed array in `P2`, adding row-displacement compression if $K > 64$). Gives $O(1)$ `is`, $O(1)$ `A & B` intersection checks, and $O(1)$ typed `call_ref` dispatch.

* **`BIP025-Q8` — Initial `wasm-wasi` ABI version:**
  * *Options:* (a) Start with `wasi_snapshot_preview1` (`fd_write`, `args_sizes_get`, `args_get`, `clock_time_get`, `proc_exit` — 5 imports supported by Wasmtime, Endive, Node, Wasmer, and browsers via tiny shims) and add WASI 0.2/0.3 Component Model packaging in `P5`; (b) target WASI 0.3 Component Model directly from `P1`.
  * *Leaning:* **(a) `wasi_snapshot_preview1` first, WASI 0.2/0.3 Component packaging in `P5`**. Since WasmGC Canonical ABI (`[CM-GC]`) is still pre-proposal, preview1 avoids Component-Model tooling boilerplate during early backend bring-up while running everywhere immediately.

* **`BIP025-Q9` — Host interop syntax for Wasm imports (Browser DOM/Canvas, WASI, custom host functions):**
  * *Options:* (a) Reuse `BIP019` `axiom` declarations under a target host namespace (e.g., `axiom wasm.env.draw: fn io (x: int): null` or `axiom "module"."name": fn ...`) with zero new keywords; (b) add a new `extern` keyword in `Babble.g4`; (c) provide only built-in `import("std/...")` modules with no user-declared Wasm imports initially.
  * *Leaning:* **(a) Reuse `BIP019` `axiom`**, keeping the grammar strictly predicate-free and adding zero new keywords, paired with curated `std/` modules (`std/math`, `std/canvas`) built on top of those axioms.

* **`BIP025-Q10` — Primary test runner for CI & local `mvn test`:**
  * *Options:* (a) **Endive 1.0** (`<scope>test</scope>` pure-Java dependency in `pom.xml`) for universal `mvn test` execution + GitHub Actions cross-engine matrix on Node.js & Wasmtime 47+; (b) shell out to Node.js or Wasmtime directly from JUnit (skipping if absent locally, mandatory in CI).
  * *Leaning:* **(a) Endive 1.0 in Maven + Node.js & Wasmtime 47+ in CI**, verifying at `P1` kickoff that Endive 1.0's `try_table`/`exnref` implementation covers Babble's exception tag cleanly (falling back to Node.js/Wasmtime for exception-heavy `.batest` cases if Endive lags on any `exnref` edge case).

---

## 9. Phased Implementation Plan (`BIP025-P0`–`P5`)

Each phase is independently shippable, preserves 100% green 3-mode JVM `mvn -B package` (`interpret`, `compile -O1`, `compile -O0`, `RuntimeBoundaryTestCase`, `-format -check`), and adds incremental Wasm test coverage.

### `BIP025-P0` — Target-Neutral `HIR`, Target Profiles & Pure-Java `WasmWriter`
* **Deliverables:**
  * Introduce `CompilationTarget` (`JVM`, `WASM_JS`, `WASM_WASI`, `WASM_BARE`) in `org.babblelang.compiler` and `org.babblelang.analysis`.
  * Refactor JVM-specific metadata out of `HirModule`, `HirClass`, `HirInterface`, `HirFunction`, and `HirExpr` (`ArrayCreate`/`ArrayOp` → `HirArrayElementType`, `CoreMethodCall` for portable `string`/collection methods) with **zero behavioral change** on the JVM backend.
  * Add `WasmPortabilityChecker` rejecting `java.*`/`javax.*`, dynamic `import(expr)`, and non-portable JDK methods on `WASM_*` targets with clear `Error : Not supported on target wasm : ...` diagnostics.
  * Implement `org.babblelang.wasm`: pure-Java Wasm 3.0 binary encoder (`WasmModule`, `WasmType`, `WasmFunc`, `WasmCodeBuilder`, `WasmWriter` with LEB128 + WasmGC `0xFB` / `try_table` / tail-call opcodes), `WatPrinter` (`-Dbabble.dumpWasm=wat`), and `WasmVerifier`.
* **Exit criteria:** Full JVM `mvn -B package` green with zero diffs; unit tests emitting and validating hand-constructed WasmGC/exception/tail-call `.wasm` modules via Endive 1.0 / `wasm-tools validate`.

### `BIP025-P1` — Core Language MVP (Scalars, Control Flow, Closures, Exceptions)
* **Deliverables:**
  * `WasmEmitter` lowering all 8 primitives (`byte`..`char`), numeric promotions/widening, exact `Operators` (`Double.compare` parity, `/` float division, wrap-around, `Truth`), `If`, `While`, `Break` (with value), `Continue`, `Return`, local/global variables, direct calls, tail-recursive `SelfRecurseCall` (`return_call`), first-class closures & capture cells, optional/named arguments (`BIP021` `$spec` bitmask), `try_table`/`exnref` runtime exceptions, and `print`/`println`/`assert` host bindings.
  * Wire `-target wasm-bare` and `-target wasm-wasi` (`wasi_snapshot_preview1` stdout/exit) into `Babblec` and `Main`.
  * Add `WasmTestCase` executing core `.batest` files (arithmetic, comparisons, control flow, functions, closures, recursion, optional/named args) against `Interpreter` and JVM `compile`.
* **Exit criteria:** Core scalar/control-flow/function/closure `.batest` suites pass with 0 divergences between `interpret`, `compile -O1`, `compile -O0`, and `wasm`.

### `BIP025-P2` — Object Model, `record`s, Interfaces, Intersections, Arrays & Multi-Module Linking
* **Deliverables:**
  * Lower `class`, `record` (with immutable WasmGC fields and generated `equals`/`hashCode`/`toString`), `object` singletons, static blocks, `this`, `super(I).m()`, `$defined` bitmasks, and operator methods.
  * Lower Babble `interface`s (default methods, closed-world interface bitsets for `is` and `BIP022` `A & B`, closed-world itables for `InterfaceCall`, single-implementer devirtualization).
  * Lower `array of T` (`$IntArray`..`$CharArray` + covariant `$RefArray` with `ArrayStoreException`, `ArrayIndexOutOfBoundsException`, `BIP020-D14` deep formatting, and `BIP020-D17` deep equality).
  * Implement `WasmLinker` whole-program multi-module compilation (`BIP005` static imports, cyclic-import `$mod_state` guards, unboxed export globals, reproducible binary output).
* **Exit criteria:** Object model, `record`, interface, intersection, array, and multi-module `.batest` suites pass differentially in Wasm.

### `BIP025-P3` — Strings, Portable Core Collections & **Argon Ray Tracer Milestone**
* **Deliverables:**
  * Complete `$String` (`(array i16)` + cached hash) and portable `string` API (declared in `src/main/java/org/babblelang/axioms/portable/`).
  * Ship Babble-written Wasm prelude for `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, and `for`-loop iteration (`BIP013` across `array`, `list`, and custom Babble iterables/iterators).
  * Compile all 14 modules of [`examples/raytracer-argon/`](../../examples/raytracer-argon/) to `argon.wasm` and verify **byte-for-byte identical `scene.ppm` (`1,021,340` bytes)** against the JVM baseline across Wasmtime 47+ and Node.js/V8!
  * Add Wasm benchmark entries to `Benchmarks` comparing `interpret`, JVM `-O1`, Wasmtime 47+, and Node.js/V8.
* **Exit criteria:** Byte-identical `examples/raytracer-argon/scene.ppm` in WebAssembly; all portable collection/iterator/string `.batest` cases green.

### `BIP025-P4` — Browser Interop (`wasm-js`), JS Glue, Debugging & Interactive Demo
* **Deliverables:**
  * Complete `-target wasm-js` emitting `app.wasm` + thin ES module `app.mjs` loader (handling `wasm:js-string` `fromCharCodeArray`/`intoCharCodeArray`, `TypedArray` bridges, stdout/stderr callbacks, `args`, and exception formatting).
  * Implement host-import `axiom` bindings (`BIP025-Q9`) with `BIP016` effect enforcement.
  * Emit standard Wasm `name` custom section (function, local, and struct/field names) and optional source-map metadata for Chrome/Firefox DevTools debugging.
  * Ship an example browser page rendering `examples/raytracer-argon` progressively onto an HTML `<canvas>` using pure Babble + 2 Canvas host axioms!
* **Exit criteria:** `wasm-js` test suite green in Node.js (V8); browser `<canvas>` demo renders cleanly in Chrome, Firefox, and Safari.

### `BIP025-P5` — Wasm Optimizations, WASI 0.2/0.3 Component Packaging & Full `.batest` Parity
* **Deliverables:**
  * Exploit `BIP007`/`BIP016` (`frozen`, `nothrow`, `unique`) for immutable WasmGC fields, check elision, and `BIP014` `ScalarReplace` synergy; add optional `-wasm-opt` hook when Binaryen is installed.
  * Add WASI 0.2/0.3 Component Model packaging (with WIT export generation for Babble `export def` signatures, tracking `[CM-GC]` for native WasmGC Canonical ABI lowering when standardized).
  * Integrate portable `.batest` coverage tracking and cross-engine CI matrix (Endive + Node.js + Wasmtime).
* **Exit criteria:** 100% of portable `.batest` cases pass identically across all four execution modes; measurable speedup on `argon.wasm` from immutable fields + `ScalarReplace`.

---

## 10. Non-Goals & Boundaries

To keep BIP025 focused and sustainable for a minimalist language project:

1. **No JDK class library emulation:** Babble will *not* re-implement `java.io`, `java.nio`, `java.awt`, `javax.imageio`, `java.net`, `java.lang.reflect`, or `java.util.concurrent` in WebAssembly. Programs targeting Wasm use Babble's Portable Core, `std/` modules, and explicit host axioms; JVM-specific programs (`examples/raytracer-fable` using `BufferedImage` and `Thread`) remain JVM targets.
2. **No `RuntimeBoundaryTestCase` regression:** `babble-runtime.jar` remains strictly `java.base`-only; `org.babblelang.wasm` lives strictly in the compiler/front-end side (just like `org.babblelang.compiler`, `org.babblelang.ir`, and `org.babblelang.opt`).
3. **No dynamic runtime compilation inside `.wasm` guests:** `FileModuleCompiler` and REPL evaluation inside a Wasm module are out of scope (running the Babble compiler itself in a browser is a separate future concern, e.g., once `BIP015` removes ANTLR4 or via a browser-hosted compiler).
4. **No Wasm 1.0/2.0 linear-memory GC fallback:** WebAssembly 3.0 WasmGC is the single supported Wasm target model.
5. **No Wasm threads (`shared` GC structs) in initial phases:** Multi-threaded WasmGC awaits both Babble's `threadsafe` concurrency model (`BIP007` §10) and stabilization of the WebAssembly Shared-Everything Threads proposal.

---

## 11. References

* **`[WASM30]`** WebAssembly Community Group, *WebAssembly Specification, Release 3.0* (completed September 17, 2025; updated November 2026): <https://webassembly.org/news/2025-09-17-wasm-3.0/> and <https://webassembly.github.io/spec/core/>
* **`[JSSTR]`** WebAssembly Community Group, *JS String Builtins Proposal* (`wasm:js-string`, shipped across Chrome, Firefox, and Safari): <https://github.com/WebAssembly/js-string-builtins> and <https://developer.mozilla.org/en-US/docs/WebAssembly/Guides/JavaScript_builtins>
* **`[WASI03]`** Bytecode Alliance, *WASI 0.3 Launched* (June 2026; WASI 0.3.0 ratified October 2026): <https://bytecodealliance.org/articles/WASI-0.3> and <https://wasi.dev/releases/wasi-p3>
* **`[CM-GC]`** WebAssembly Component Model, *Pre-Proposal: Wasm GC Support in the Canonical ABI* (`#525`): <https://github.com/WebAssembly/component-model/issues/525>
* **`[WT47]`** Bytecode Alliance, *GC and Exceptions in Wasmtime* (Wasmtime 47, July 2026): <https://bytecodealliance.org/articles/wasmtime-gc> and *Exceptions in Cranelift and Wasmtime* (November 2025): <https://bytecodealliance.org/articles/wasmtime-exceptions>
* **`[ENDIVE]`** Bytecode Alliance, *Endive 1.0: WebAssembly on the JVM* (pure-Java zero-native-dependency Wasm runtime with WasmGC host integration, fork of Chicory, June 2026): <https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm> and <https://github.com/bytecodealliance/endive>
* **`[TEAVM]`** TeaVM, *WebAssembly GC Backend Documentation*: <https://teavm.org/docs/wasm-gc-backend/loader.html>
* **`[KTWASM]`** JetBrains, *Kotlin/Wasm Documentation* (WasmGC & `wasm-wasi` target): <https://kotlinlang.org/docs/wasm-overview.html>
* **Internal Babble BIPs:** [`BIP001`](bip001_bytecode-compiler.md), [`BIP002`](bip002_object-model.md), [`BIP006`](bip006_module-sources-and-aot.md), [`BIP007`](bip007_effects.md), [`BIP008`](bip008_static-typing.md), [`BIP009`](bip009_test-coverage.md), [`BIP011`](bip011_core-types.md), [`BIP013`](bip013_iterators.md), [`BIP014`](bip014_optimization-passes.md), [`BIP016`](bip016_consolidating-effect-annotations.md), [`BIP017`](bip017_higher-order-function-effects.md), [`BIP018`](bip018_tracking-java-improvements.md), [`BIP019`](bip019_refactoring-axioms.md), [`BIP020`](bip020_array-types.md), [`BIP021`](bip021_optional-variadic-overloaded-arguments.md), [`BIP022`](bip022_intersection-types.md), [`BIP023`](bip023_type-aliases.md)
