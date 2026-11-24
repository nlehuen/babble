# BIP018: Tracking Java Improvements

| | |
|---|---|
| **BIP** | 018 |
| **Date** | 2026-11-20 |
| **Status** | Decided / Draft (`BIP018-Q1`–`Q8` → `BIP018-D1`–`D8` decided; sequenced strictly after `BIP014` completes) |
| **Depends on** | Bytecode compiler (`RM001`/`RM003`, [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), object model & `record`s ([bip002_object-model.md](bip002_object-model.md)), module sources, `java.base`-only runtime boundary & `babblec` ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static effects, ownership & algebraic properties (`RM002`/`RM007`/`RM008`/`RM009`, [bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md)), static typing & core types (`RM005`, [bip008_static-typing.md](bip008_static-typing.md), [bip011_core-types.md](bip011_core-types.md)), `.batest` differential parity harness ([bip009_test-coverage.md](bip009_test-coverage.md)), compiler IR & `CodeGenerator` decomposition ([bip014_optimization-passes.md](bip014_optimization-passes.md) `BIP014-I0`–`I2`, `BIP014-P3`, `BIP014-I4`, `BIP014-P5`), and parser dependency evaluation ([bip015_down-with-the-ants.md](bip015_down-with-the-ants.md)) |
| **Scope** | Release-by-release audit of JDK 22 through JDK 28 (plus the JDK 29 LTS+ horizon) classified into **Migrate** (mandatory migration), **Adopt** (optional leverage), and **Extend** (Babble capabilities beyond Java unlocked by new JVM/JDK features); explicit portability vs. modernity trade-offs; a **`Babble N` ↔ `JDK N` release scheme**; a **branchless multi-module SPI monorepo** with `babblec -target N` cross-targeting; and concrete surface-language specifications for `lazy def`, `scoped def` + `with ... then (...)`, and target-gated `std/foreign` |

---

## 1. Executive Summary

Babble is deliberately pinned today to **JDK 21** (`<maven.compiler.release>21</maven.compiler.release>` in [`pom.xml`](../../pom.xml), `V21` emitted in [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java), and JDK 21 in [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml)). That baseline was chosen for good reason: JDK 21 is a widely deployed Long-Term Support (LTS) release that already gives Babble `sealed` hierarchies, `record`s, pattern matching for `switch`, `invokedynamic`, `MethodHandles`, `LambdaMetafactory`, and Virtual Threads.

Since JDK 21 reached General Availability in September 2023, six major JDK releases have shipped (**JDK 22** through **JDK 27**, including the next LTS, **JDK 25**), and **JDK 28** (GA March 2027, early access in November 2026) is bringing landmark platform shifts:

* **JDK 24 / 25 LTS** finalized the **Class-File API** (`[JEP484]`, capable of replacing Babble's `org.ow2.asm:asm` dependency entirely), **Scoped Values** (`[JEP506]`), **Stream Gatherers** (`[JEP485]`), **Virtual Thread non-pinning** (`[JEP491]`), **Compact Object Headers** (`[JEP519]`, on by default in **JDK 27** `[JEP534]`), **Flexible Constructor Bodies** (`[JEP513]`), and **Project Leyden AOT Class Loading & Method Profiling** (`[JEP483]`, `[JEP514]`, `[JEP515]`).
* **JDK 26** tightened JVM integrity via **Prepare to Make Final Mean Final** (`[JEP500]`) and extended Leyden AOT object caching to all GCs (`[JEP516]`).
* **JDK 28** finalizes **Structured Concurrency** (`[JEP543]`) and **Leyden Ahead-of-Time Code Compilation** (`[JEP544]`), and integrates Project Valhalla's **Value Objects** (`[JEP401]`, Preview) and **Strict Field Initialization in the JVM** (`[JEP539]`, Preview) — two JVM capabilities that match Babble's existing `record` (`BIP002`/`RM006`), non-null (`BIP008`/`RM005`), and `BIP007`/`BIP016` immutability invariants almost one-to-one.

Yet blindly bumping Babble's minimum JDK to 28 (or requiring `--enable-preview`) would break Babble's portability across the vast JDK 21 and JDK 25 LTS install base, while staying frozen on JDK 21 forever leaves transformative capabilities — zero-ASM bytecode emission, zero-identity flattened `record`s, JVM-verified strict finals, Leyden AOT code caches, and compile-time race-free structured concurrency — on the table.

This proposal settles `BIP018-D1`–`D8` across five pillars:

1. **A three-way JEP audit (§3–§4)** across **JDK 22 → JDK 28** and the **JDK 29+ horizon**, classifying every significant feature as **Migrate** (mandatory migration), **Adopt** (optional leverage), or **Extend** (Babble beyond Java).
2. **An explicit trade-off analysis (§5)** establishing the guiding rule: **One Core Language, Progressive Lowering, Additive Capability Tiers**.
3. **A `Babble N` release scheme (§6, `BIP018-D2`, `D3`, `D5`)** — active tiers (`Babble 21` Baseline LTS, `Babble 25` Modern LTS, `Babble 27`/`28` + `28-preview` Frontier/EA) with sparse floor inheritance, edition-qualified SemVer artifacts (`babble-21`, `babble-25`, `babble-28`, `babble-28-preview`), and upstream-JDK LTS EOL tracking.
4. **A branchless multi-module SPI monorepo (§7, `BIP018-D1`, `D4`, `D6`)** — sequenced strictly after `BIP014` completes, with zero-ASM `babble-25`/`babble-28` and `babblec -target N` cross-targeting (`BIP018-D8`'s field-free `abstract class BabbleInstance` was superseded by [`BIP020-D18`](bip020_array-types.md), which already made compiled classes and records extend `java.lang.Object` directly).
5. **Surface-language & stdlib "Extend" specifications (§8, `BIP018-D7`)** — predicate-free pure-`SLL` `lazy def` and `scoped def` + `with ... then (...)` polyfilled on `Babble 21` via `PlatformRuntime` (upgrading transparently to `ScopedValue` on 25+ and `LazyConstant` once final), plus target-gated `std/foreign` FFM wrappers (`-target 22+`).

---

## 2. Classification Framework & Codebase Baseline

### 2.1 The Three Action Categories

Every JDK feature reviewed in this document is assigned one (or more, when a feature has both a compliance and an opportunity dimension) of three classifications:

| Category | Meaning | Impact on Babble Users |
|---|---|---|
| **Migrate** *(Mandatory Migration)* | Breaking API removals, stricter JVM defaults (`Integrity by Default`), bytecode verifier changes, or semantic shifts in `java.base` classes that Babble *must* handle to compile, pass tests, and run warning-free on JDK $N$. | Invisible when handled cleanly; prevents runtime warnings, `IdentityException`, or `IllegalAccessException` failures on newer JVMs. |
| **Adopt** *(Optional)* | New JDK APIs, compiler/runtime primitives, or JVM optimizations that Babble can optionally use inside its own implementation (compiler, runtime, `babblec`, `BytecodeEffectAnalyzer`, REPL) or map cleanly in Java interop (`HostEffects`, `JavaNullability`). | Smaller dependencies (dropping ASM), faster startup/warmup (Leyden), lower memory (Lilliput), cleaner Java interop — with **zero** change to Babble's core semantics. |
| **Extend** *(Beyond Java)* | Capabilities Babble can deliver to Babble programmers that **do not exist in Java** (or that Java cannot statically enforce / ergonomically emit) by combining new JVM primitives with Babble's static type, ownership, and effect systems (`BIP002`, `BIP007`, `BIP008`, `BIP011`, `BIP016`, `BIP017`). | Makes Babble strictly safer or faster than equivalent idiomatic Java — e.g., automatic Valhalla `value class` lowering for `record`s, `ACC_STRICT_INIT` on normal classes, or compile-time race-free structured concurrency. |

### 2.2 Babble's Current JDK 21 Footprint (Baseline Audit)

Before reviewing JDK 22+, a direct audit of Babble's current codebase establishes an unusually clean starting point:

* **Bytecode & dependencies ([`pom.xml`](../../pom.xml)):** Only two non-JDK runtime dependencies — `org.antlr:antlr4-runtime:4.13.2` (evaluated in `BIP015`) and `org.ow2.asm:asm:9.11.1` (plus optional `asm-util` for `CheckClassAdapter`/`TraceClassVisitor`). Emitted class files target `V21` in [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) (`L730`, `L1078`, `L1686`), all setting `ACC_SUPER` on concrete classes.
* **ASM touchpoints:** Isolated to exactly three files: [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) (bytecode emission), [`Debugging.java`](../../src/main/java/org/babblelang/compiler/Debugging.java) (`TraceClassVisitor`, `CheckClassAdapter.verify`), and [`BytecodeEffectAnalyzer.java`](../../src/main/java/org/babblelang/analysis/BytecodeEffectAnalyzer.java) (`ClassReader`/`ClassVisitor` effect scanning).
* **Runtime boundary ([`RuntimeBoundaryTestCase.java`](../../src/test/java/org/babblelang/tests/RuntimeBoundaryTestCase.java)):** `org.babblelang.runtime` and `org.babblelang.engine.impl.natives` depend **exclusively on `java.base`** — zero ANTLR, zero ASM, zero `javax.script`.
* **Integrity-by-Default hygiene:** Static scans of `src/` show:
  * **0** uses of `sun.misc.Unsafe` or `jdk.internal.*`;
  * **0** uses of `SecurityManager` or `java.applet.*`;
  * **0** uses of JNI or native libraries;
  * **0** uses of `setAccessible` / `trySetAccessible` or reflective mutation of `final` fields ([`JavaClass.java`](../../src/main/java/org/babblelang/engine/impl/natives/java/JavaClass.java) enforces `Modifier.isFinal` before writing Java fields);
  * **0** uses of `WeakHashMap` or `java.lang.ref.*`;
  * **`IdentityHashMap` / `System.identityHashCode`:** Used in `org.babblelang.analysis` strictly on identity-bearing compiler nodes (`ParserRuleContext`, `TypeNode`, `Type.Function`, `ModuleInterface`, `ClassLoader`) — never on boxed primitives, `Optional`, or `java.time.*` — and in `runtime` on `BabbleInstance`, `BoundMethod`, and `Faces` proxies;
  * **`synchronized` locks:** Used in [`Bootstrap.java`](../../src/main/java/org/babblelang/runtime/Bootstrap.java) (`relink`), [`Overloads.java`](../../src/main/java/org/babblelang/runtime/Overloads.java) (`Cache.put`), [`Launcher.java`](../../src/main/java/org/babblelang/runtime/Launcher.java) (`loaded`), [`ModuleInterface.java`](../../src/main/java/org/babblelang/analysis/ModuleInterface.java), and [`BabbleScriptEngine.java`](../../src/main/java/org/babblelang/engine/BabbleScriptEngine.java) (`moduleLock`).

This means Babble has **remarkably low mandatory migration debt** across JDK 22–28, letting nearly all engineering effort focus on high-leverage **Adopt** and **Extend** capabilities.

---

## 3. Release-by-Release JDK Review (JDK 22 → JDK 28 & Horizon)

Each release below summarizes its release date, LTS/support status, and every JEP with concrete impact on Babble, grouped by **Migrate**, **Adopt**, and **Extend**. Preview/incubator JEPs are tracked at their *finalization* release unless their preview marks a strategic inflection point (notably JDK 28's Valhalla JEPs).

### 3.1 JDK 22 *(GA: March 2024 · Non-LTS · EOL)*

*Integrated JEPs: 423, 447 (P), 454, 456, 457 (P), 458, 459 (2P), 460 (7I), 461 (P), 462 (2P), 463 (2P), 464 (2P).*

* **Migrate (Mandatory):** None for Babble's codebase. (`JEP 423` G1 region pinning is transparent.)
* **Adopt (Optional):**
  * **`[JEP454]` Foreign Function & Memory (FFM) API *(Final)*:** Replaces fragile JNI with `java.lang.foreign` (`Arena`, `MemorySegment`, `Linker`) inside `java.base`.
    * *Interop:* Add `java.lang.foreign.*` nullability and `io`/`mut` effect profiles to `JavaNullability` and `HostEffects`.
    * *Tooling:* Unblocks pure-JDK native integration where needed (e.g., raw terminal line-editing in `Repl` without JNI, or `BIP015` Option 4 Tree-sitter if ever revisited — noting `java-tree-sitter` targets JDK 23+ FFM).
  * **`[JEP456]` Unnamed Variables & Patterns (`_`) *(Final)*:** Cleanups across Babble's own Java implementation (unused catch parameters, pattern-matching switch bindings in `TypeChecker`, `EffectChecker`, and `BIP014` HIR passes).
  * **`[JEP458]` Launch Multi-File Source-Code Programs *(Final)*:** No direct Babble requirement (`babble` already has `BIP005` modules).
* **Extend (Babble Beyond Java):**
  * **Lifetime-checked off-heap memory (`Arena` + `BIP007`/`BIP016`):** Java's FFM API relies on *runtime* liveness checks (`IllegalStateException: Already closed`) when a `MemorySegment` escapes a closed `Arena.ofConfined()`. Because Babble already tracks `@BORROWED` non-escape and `unique`/`move` ownership (`RM007`/`RM008`), a Babble `std/foreign` wrapper (e.g., `withArena(fn (mut seg: MemorySegment) -> ...)`) can **statically reject returning or storing an arena-scoped `MemorySegment` outside its confined block** — preventing use-after-close at compile time, something Java's type system cannot express.
  * **Unnamed discard patterns in Babble:** Aligns with Babble's future destructuring (`BIP005-D3`) and lambda parameters (pairing `_` with `BIP007-D8` `discard(expr)`).

---

### 3.2 JDK 23 *(GA: September 2024 · Non-LTS · EOL)*

*Integrated JEPs: 455 (P), 466 (2P), 467, 469 (8I), 471, 473 (2P), 474, 476 (P), 477 (3P), 480 (3P), 481 (3P), 482 (2P).*

* **Migrate (Mandatory):**
  * **`[JEP471]` Deprecate Memory-Access Methods in `sun.misc.Unsafe` for Removal:** Babble itself has **zero** `Unsafe` usage (clean). Audit transitive test/build dependencies (JUnit, JaCoCo, ANTLR4) on JDK 23+.
* **Adopt (Optional):**
  * **`[JEP474]` ZGC: Generational Mode by Default:** Free latency improvement when running interactive Babble services under `-XX:+UseZGC`.
  * **`[JEP467]` Markdown Documentation Comments (`///`):** Minor doc ergonomics for Babble's own Java sources (if the source baseline ever advances past 21).
* **Extend (Babble Beyond Java):** None — JDK 23 is almost exclusively a preview-iteration release (`Class-File API` 2P, `Primitive Patterns` 1P, `Stream Gatherers` 2P, `Scoped Values` 3P, `Structured Concurrency` 3P, `Flexible Constructor Bodies` 2P). **Important implication for the release scheme (§6):** `Babble 23` requires *zero* custom overlay code over `Babble 22`.

---

### 3.3 JDK 24 *(GA: March 2025 · Non-LTS · EOL)*

*Integrated JEPs: 404 (E), 450 (E), 472, 475, 478 (P), 479, 483, 484, 485, 486, 487 (4P), 488 (2P), 489 (9I), 490, 491, 492 (3P), 493, 494 (2P), 495 (4P), 496, 497, 498, 499 (4P), 501.*

JDK 24 is the **first major architectural inflection point** after JDK 21.

* **Migrate (Mandatory):**
  * **`[JEP486]` Permanently Disable the Security Manager:** Babble uses zero `SecurityManager` APIs; audit `HostEffects`/`JavaNullability` and any Java-interop tests to ensure nothing references `System.setSecurityManager` or `AccessController`.
  * **`[JEP472]` Prepare to Restrict the Use of JNI** & **`[JEP498]` Warn upon Use of Memory-Access Methods in `sun.misc.Unsafe`:** Babble itself is 100% clean (zero JNI, zero `Unsafe`); verify CI logs stay warning-free across transitive dependencies.
* **Adopt (Optional):**
  * **`[JEP484]` Class-File API (`java.lang.classfile`) *(Final)* — HIGHEST-VALUE `ADOPT` IN JDK 22–25:**
    * Replaces `org.ow2.asm:asm` and `org.ow2.asm:asm-util` across all three ASM touchpoints in Babble:
      1. **Bytecode emission (`CodeGenerator` / `BIP014-I1` `HirEmitter`):** `ClassFile.of(ClassHierarchyResolverOption.of(...))` + `ClassBuilder`/`CodeBuilder` generate bytecode natively with automatic stack-map computation, short/long branch offset replay, and `java.lang.constant` (`ClassDesc`, `MethodTypeDesc`, `DirectMethodHandleDesc`) descriptors instead of raw string descriptors.
      2. **Bytecode verification (`Debugging.java`):** `ClassFile.of(...).verify(bytes)` replaces `CheckClassAdapter.verify(...)`, eliminating the optional `asm-util` dependency!
      3. **Java bytecode effect scanning (`BytecodeEffectAnalyzer.java`):** Pattern matching over `CodeElement` (`case FieldInstruction f -> ...`, `case InvokeInstruction i -> ...`) replaces the verbose ASM `ClassVisitor`/`MethodVisitor` subclass.
    * **Eliminates ASM version lag:** As JEP 484's own motivation notes, third-party ASM always lags behind new JVM classfile versions (such as JDK 28's `72.65535` + `ACC_STRICT_INIT` + `EARLY_LARVAL` stack-map frames). `java.lang.classfile` ships inside `java.base` in exact lockstep with the JVM specification.
    * **Synergy with `BIP015`:** If `BIP015` ever replaces ANTLR4 with a hand-written parser *and* `BIP018` replaces ASM with `JEP 484` on Babble 24+/25+, **Babble 25+ becomes a 100% zero-third-party-dependency pure-JDK compiler and runtime**.
  * **`[JEP491]` Synchronize Virtual Threads without Pinning *(Final)*:** Eliminates carrier-thread pinning on `synchronized` monitors — immediately making Babble's existing `synchronized` blocks (`Bootstrap.relink`, `Overloads.Cache.put`, `Launcher.loaded`, `BabbleScriptEngine.moduleLock`) virtual-thread-friendly with zero lock rewrites!
  * **`[JEP483]` Ahead-of-Time Class Loading & Linking *(Final, Project Leyden)*:** First production Leyden release; `babblec` (`BIP006`) jars have deterministic class names and zero ANTLR/ASM/reflection startup overhead, making them ideal for AOT class-loading caches.
  * **`[JEP485]` Stream Gatherers *(Final)*:** Add `java.util.stream.Gatherer` and `Gatherers` to `JavaNullability` (parametric nullness) and `HostEffects`.
  * **`[JEP493]` Linking Run-Time Images without JMODs *(Final)*:** Pairs cleanly with `babblec` (`BIP006` §5) + Babble's `java.base`-only runtime to produce self-contained `jlink` distributions of compiled Babble applications from a standard JRE installation.
* **Extend (Babble Beyond Java):**
  * **Statically verified `pure` stateful Gatherers:** Java's `Gatherer<T, A, R>` allows arbitrary side effects inside `initializer`, `integrator`, and `finisher`, relying on programmer discipline. Babble's `BIP016`/`BIP017` `fn calling f` + `unique` local accumulator ownership lets Babble expose custom stream/iterator gatherers whose local mutable state is **statically verified not to escape**, preserving `pure` / `@HERMETIC` contracts across pipeline stages (directly feeding `BIP014` `O12` pipeline rewrites).

---

### 3.4 JDK 25 *(GA: September 2025 · **LTS** · Supported through 2030+)*

*Integrated JEPs: 470 (P), 502 (P), 503, 505 (5P), 506, 507 (3P), 508 (10I), 509 (E), 510, 511, 512, 513, 514, 515, 518, 519, 520, 521.*

**JDK 25 is the first Long-Term Support release since JDK 21.** It rolls up every finalized feature of JDK 22–24 (`FFM`, `Unnamed Patterns`, `Class-File API`, `Stream Gatherers`, `Virtual Thread non-pinning`, `Leyden AOT Class Loading`) and adds several high-impact capabilities of its own.

* **Migrate (Mandatory):**
  * **New `java.base` I/O entry point (`[JEP512]` `java.lang.IO`):** JDK 25 adds `java.lang.IO` (`println`, `print`, `readln`) to `java.base`. Babble's `HostEffects` and `JavaNullability` must register `java.lang.IO` methods as `io` (and `readln` as nullable `string?`) so Babble code calling `java.lang.IO` cannot bypass `BIP016` purity checks!
  * *Cumulative JDK 22–25 hygiene:* Security Manager permanently disabled (`JEP 486`), `Unsafe` warnings (`JEP 498`), JNI preparation (`JEP 472`) — all already clean in Babble.
* **Adopt (Optional):**
  * **`[JEP506]` Scoped Values *(Final)*:** Lightweight, immutable, bounded-lifetime dynamic context propagation optimized for virtual threads and structured tasks.
    * *Runtime adoption:* Ideal mechanism for propagating ambient `ScriptRuntime` (output writer, loaded modules, future cancellation tokens) across implicit Java callbacks, SAM adapters (`Faces.SamInvoker`), or multi-threaded interpreter evaluation frames (`BIP007` §10) without `ThreadLocal` memory leaks or mutable state.
  * **`[JEP514]` AOT Command-Line Ergonomics (`-XX:AOTCacheOutput`) & `[JEP515]` AOT Method Profiling *(Final, Project Leyden)*:** Single-command training run (`java -XX:AOTCacheOutput=app.aot -jar app.jar ...`) that pre-loads classes *and* captures C2 profiling data. `babblec` can directly document or orchestrate optional AOT cache generation for compiled Babble CLI apps (`examples/raytracer-argon`, `babblefmt`).
  * **`[JEP519]` Compact Object Headers *(Final, Project Lilliput — opt-in `-XX:+UseCompactObjectHeaders`)*:** Shrinks every JVM object header from 12–16 bytes to 8 bytes. Directly reduces heap footprint for Babble's small heap objects (`BabbleInstance`, `record`s like `Vec3`/`Ray`, `IntCell`/`DoubleCell`, `CompiledFunction`, `BoundMethod`) with zero code changes.
  * **`[JEP513]` Flexible Constructor Bodies *(Final)*:** Allows argument validation and local field initialization *before* `super(...)` in Babble's own Java codebase (`BabbleInstance`, `Type`, `HIR` nodes), and mirrors the JVM early-larval initialization model later formalized by `JEP 539`.
  * **`[JEP511]` Module Import Declarations & `[JEP512]` Compact Source Files:** Improves ergonomics for Java host applications embedding `BabbleScriptEngine`.
  * **`[JEP509]`/`[JEP518]`/`[JEP520]` JFR CPU-Time Profiling, Cooperative Sampling & Method Timing:** First-class profiling tools for measuring `BIP014` optimization passes and `Benchmarks` with low overhead.
* **Extend (Babble Beyond Java):**
  * **Effect-tracked, `frozen`-only Scoped Bindings:** Java's `ScopedValue<T>` guarantees the *binding* cannot be rebound mutably, but Java cannot prevent binding a *mutable* `ArrayList` or `AtomicReference` into a `ScopedValue`, silently smuggling shared mutable state across threads! Babble can expose a lexical `scoped` / `with` context facility backed by `ScopedValue` that:
    1. Statically requires bound values to be **`frozen T`** (or core scalar / deep `record`), guaranteeing **true deep immutability** across all inheriting tasks; and
    2. Integrates with `BIP016` effects (reading a dynamic scoped context is tracked distinctly from `@HERMETIC`, or explicitly declared in the function's effect signature), so pure functions cannot silently depend on ambient dynamic state.

---

### 3.5 JDK 26 *(GA: March 2026 · Non-LTS · EOL)*

*Integrated JEPs: 500, 504, 516, 517, 522, 524 (2P), 525 (6P), 526 (2P), 529 (11I), 530 (4P).*

* **Migrate (Mandatory):**
  * **`[JEP500]` Prepare to Make Final Mean Final *(Final)*:** Issues runtime warnings (default `--illegal-final-field-mutation=warn`) when deep reflection mutates `final` fields, preparing for `deny` by default in a future JDK.
    * *Audit:* Babble's own code performs **zero** reflective mutation of `final` fields. Babble's JDK 26+ CI job should run with **`--illegal-final-field-mutation=deny`** to guarantee neither Babble nor any transitive library (`antlr4-runtime`, JUnit, JaCoCo) silently introduces a violation.
    * *User-facing interop:* Document that Babble scripts using Java reflection (`java.lang.reflect.Field.set`) on `final` fields will trigger JDK 26+ warnings/errors unless the user passes `--enable-final-field-mutation=ALL-UNNAMED`.
  * **`[JEP504]` Remove the Applet API:** Zero impact on Babble (already zero references).
* **Adopt (Optional):**
  * **`[JEP516]` Ahead-of-Time Object Caching with Any GC *(Final, Leyden)*:** Removes the G1-only constraint on Leyden AOT caches, allowing `babblec` apps using ZGC or Generational Shenandoah to benefit from AOT caching.
  * **`[JEP517]` HTTP/3 for the HTTP Client API:** Available immediately to Babble programs via Java interop (`java.net.http`).
  * **`[JEP522]` G1 GC Synchronization Reduction:** Free throughput gain for allocation-heavy Babble workloads.
* **Extend (Babble Beyond Java):**
  * **Systematic `ACC_FINAL` emission for HotSpot trusted-final constant folding:** Once the JVM trusts `final` fields (`JEP 500`, culminating in `JEP 539`), HotSpot C2 can aggressively constant-fold reads of `final` fields. Idiomatic Java classes frequently leave fields non-`final` (for builders, frameworks, or convenience), whereas Babble's static analysis (`BlockScopes`, `TypeChecker`, `BIP002` `record`, `BIP007`) already knows **every** `record` member and **every** non-reassigned `class` header `def` is permanently immutable. Emitting `ACC_FINAL` on all such generated fields lets Babble harvest trusted-final optimizations automatically across user code.

---

### 3.6 JDK 27 *(GA: September 2026 · Non-LTS · Current GA as of Nov 2026)*

*Integrated JEPs: 523, 527, 531 (3P), 532 (5P), 533 (7P), 534, 536, 537 (12I), 538 (3P).*

* **Migrate (Mandatory):** None for Babble.
* **Adopt (Optional):**
  * **`[JEP534]` Compact Object Headers by Default *(Final, Lilliput)*:** Every Babble object gets 8-byte object headers out of the box with zero JVM flags.
  * **`[JEP523]` Make G1 the Default Garbage Collector in All Environments:** Consistent GC defaults even on single-core containers running `babblec` tools.
  * *Preview tracking:* `JEP 531` (Lazy Constants 3P), `JEP 532` (Primitive Types in Patterns 5P), `JEP 533` (Structured Concurrency 7P), `JEP 537` (Vector API 12I) all continue maturing toward finalization.
* **Extend (Babble Beyond Java):** None specific to JDK 27 beyond inheriting JDK 24–26. Like JDK 23, `Babble 27` requires **zero** custom source overlay over `Babble 25`/`26`.

---

### 3.7 JDK 28 *(Targeted / Early Access as of Nov 2026 · GA: March 2027 · Non-LTS)*

*Targeted/Integrated JEPs (as of Nov 2026): 401 (P), 535, 539 (P), 540 (I), 541, 542, 543, 544, 545, 546; Proposed: 547 (4P).*

JDK 28 is the **second major architectural inflection point** — arguably the most consequential JDK release for Babble since JDK 21.

* **Migrate (Mandatory / Preview-Aware Interop):**
  * **`[JEP401]` Value Objects *(Preview)* — Identity Semantics Audit:** Under `--enable-preview` on JDK 28 (and by default once finalized in a subsequent release), 30 `java.base` classes — including `Integer`, `Long`, `Double`, `Float`, `Byte`, `Short`, `Character`, `Boolean`, `Optional`, and `java.time.LocalDate`..`Duration` — become **value classes without identity**:
    * `==` compares by recursive field values instead of pointer identity (e.g., `Integer.valueOf(1996) == Integer.valueOf(1996)` becomes `true`!).
    * `synchronized(x)` and `java.lang.ref.*` / `WeakHashMap` throw `IdentityException`.
    * Classfile flag `ACC_SUPER` (`0x0020`) is reinterpreted as **`ACC_IDENTITY`**.
    * **Mandatory Babble audit:**
      1. [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) already sets `ACC_SUPER` on generated concrete classes (`L1078`, `L1686`), so existing compiled Babble `class`es remain identity classes cleanly.
      2. [`SameFunction` / `Operators`](../../src/main/java/org/babblelang/runtime/Operators.java): Document and test `same(a, b)` behavior on boxed primitives and `java.time` value objects (where `same(1996, 1996)` already compares value-equally in Babble or boxed identity depending on boxing; under Valhalla, boxed `Integer` identity disappears permanently, making `same` on value types consistently value-based!).
      3. `IdentityHashMap` in `org.babblelang.analysis`: Already audited clean (keys are strictly identity classes: `ParserRuleContext`, `TypeNode`, `Type.Function`, `ModuleInterface`, `ClassLoader`).
      4. `JavaNullability` / `JavaTypeBridge`: Reflect `AccessFlag.IDENTITY` / `Objects.hasIdentity` where relevant.
* **Adopt (Optional):**
  * **`[JEP543]` Structured Concurrency *(Final!)*:** Graduates after seven preview rounds (`java.util.concurrent.StructuredTaskScope`). Directly provides the cancellation-safe, leak-free, virtual-thread-friendly execution substrate for Babble's planned parallel combinators (`BIP007` §10: `parallelMap`, `parallelFilter`, `parallelReduce`, `parallelForEach`) while automatically propagating `ScopedValue` (`JEP 506`) bindings to subtasks!
  * **`[JEP544]` Ahead-of-Time Code Compilation *(Final, Project Leyden!)*:** Extends the AOT cache (`-XX:AOTCacheOutput` / `-XX:AOTCache`) to store **compiled C1/C2 native machine code** from a training run, delivering **65%–80% startup reduction** and instant warmup while retaining full HotSpot JIT agility and deoptimization.
    * *Strategic impact on `BIP006` §9 (GraalVM Native Image):* Leyden AOT Code Compilation gives `babblec` programs near-native cold-start speed **without** GraalVM Native Image's closed-world reflection configuration burden — dynamic `import()`, `Overloads`, `invokedynamic`, and `ScriptClassLoader` continue working out of the box!
  * **`[JEP547]` Lazy Constants *(4th Preview; formerly Stable Values `JEP 502`/`526`/`531`)*:** Once finalized, an ideal replacement for hand-rolled lazy caching in `Module` exports, `JavaClass.members`, and `Faces.SAM_FACTORIES`, allowing HotSpot C2 to constant-fold reads after initialization.
  * **`[JEP540]` Simple JSON API *(Incubator)*:** Watch for zero-dependency JSON support in Babble's standard library (`BIP005-D5`) once graduated.
  * **`[JEP401]` Runtime value wrappers *(Preview → Final)*:** Internal runtime wrappers (`BoundMethod`, `JavaObject`, `OpSite`, `CallSite`) and immutable compiler `Type` records become natural candidates for Java `value record` once JEP 401 finalizes.
* **Extend (Babble Beyond Java) — THREE LANDMARK OPPORTUNITIES:**
  1. **Zero-source-change Valhalla `value class` lowering for Babble `record`s (`[JEP401]` + `[JEP539]`):**
     * Unlike Java — where millions of existing `record` classes default to identity classes for backward compatibility and must explicitly opt into `value record` — **Babble's `record` (`BIP002` + `RM006`) is *already* defined as an immutable, identity-free value type** (`setMember` throws `readOnly`, equality/hashing are purely structural, and `BIP002` already forbade identity assumptions).
     * *Required runtime refactoring (`BIP018-D8`, clean and universal):* Today, compiled Babble `record`s extend concrete [`BabbleInstance`](../../src/main/java/org/babblelang/runtime/BabbleInstance.java) (which holds a mutable `private Object face` field and an unused `Object[] values` array), and ~22 sites across `runtime/` and `Interpreter` test `instanceof BabbleInstance`. Under `JEP 401`, an `abstract value class` with zero mutable fields (like `java.lang.Number`) can be extended by **both** identity classes and value classes! By refactoring `BabbleInstance` across **all editions** (`BIP018-P1`/`P2`) into a **field-free `abstract class BabbleInstance implements HasMembers`** (moving `values[]` and `face` into subclasses for interpreted/identity instances, and storing `BabbleClass` in a `static final` field on compiled `record` classes), every compiled `record` shrinks immediately even on `Babble 21`/`25`, every existing `instanceof BabbleInstance` check remains intact, and `Babble 28-preview` (`BIP018-P4`) simply marks `BabbleInstance` as `abstract value class BabbleInstance` and compiles every Babble `record` (`Vec3(double x, double y, double z)`, `Ray`, `Color` in `examples/raytracer-argon`) directly to a **real JVM `value class`** (omitting `ACC_IDENTITY`, marking all fields `ACC_FINAL | ACC_STRICT_INIT`).
     * *Payoff:* HotSpot automatically performs **reference scalarization** across non-inlined method calls (passing/returning `Vec3` in CPU registers without heap allocation, complementing `BIP014` `O9`) and **reference flattening** inside arrays and enclosing value objects — with **zero changes to existing Babble source code**!
  2. **JVM-verified Strict Field Initialization (`[JEP539]`) on non-value Babble `class`es:**
     * Notice `JEP 539`'s explicit **Non-Goal**: *"It is not a goal to introduce new Java language features, such as a strictly-initialized modifier for fields"* — and its explicit **Goal**: *"Offer designers of JVM-based programming languages a model for field initialization which has stronger integrity guarantees."*
     * Java programmers *cannot* mark fields of ordinary mutable classes as `ACC_STRICT_INIT` in Java source! **Babble's compiler can.** Babble's static analysis already separates definitely-initialized class header parameters (`class (x: int, y: int)`) from body `def`s. Emitting `ACC_STRICT_INIT | ACC_FINAL` (plus `EARLY_LARVAL` stack-map frames, natively supported by `java.lang.classfile`!) on immutable header fields of *every* Babble `class` gives HotSpot C2 **JVM-verified trusted final fields** (impossible to mutate even via `--enable-final-field-mutation`, and aggressively constant-folded) and eliminates the `$defined` bitmask for header fields!
  3. **Compile-time race-free Structured Concurrency (`[JEP543]` + `BIP007`/`BIP016`/`BIP017`):**
     * Java's `StructuredTaskScope` solves thread-lifetime structure and cancellation, **not data races**: nothing stops two forked Java subtasks from mutating the same `ArrayList` or object field concurrently.
     * Babble's `BIP007`/`BIP016`/`BIP017` effect & ownership system (`pure`-by-default, `frozen T`, `unique`/`move`, `fn calling f`, plus planned `threadsafe` from `BIP007` §10) lets Babble build a **`parallel` / `std/concurrent` layer directly on `StructuredTaskScope`** where the **compiler statically proves race freedom** (subtasks can only read `frozen` / immutable captures, move disjoint `unique` ownership in/out, or call `threadsafe` combinators) — delivering Rust-like fearless concurrency on top of JVM virtual threads!

---

### 3.8 The Horizon: JDK 29 LTS *(Expected Sept 2027)* & Beyond

Tracking OpenJDK's active projects reveals why laying clean multi-JDK groundwork now pays compounding dividends over the next 2–4 years:

| Upcoming Capability | OpenJDK Project / JEP | Classification | Why Babble Is Uniquely Positioned to **Extend** Beyond Java |
|---|---|---|---|
| **Value Objects & Strict Field Init *(Final)*** | Valhalla (`[JEP401]`, `[JEP539]`) | **Adopt / Extend** | Graduates Babble's preview `record` → `value class` & `ACC_STRICT_INIT` lowering into the **JDK 29 LTS** production track without `--enable-preview`! |
| **Null-Restricted Value Class Types & Nullable Types** | Valhalla (`[JEP8316779]`, `[JEP8303099]`) | **Extend** | **Babble already has non-null-by-default `T` vs. nullable `T?` (`BIP008`/`RM005`)!** Once the JVM exposes null-restricted storage (`!`), Babble compiles `Ray(origin: Vec3, dir: Vec3)` and `array of Vec3` directly to **null-free, maximally flattened heap storage** with zero null-flag bit overhead and **zero syntax migration** in existing Babble programs. |
| **Lazy Constants *(Final)*** | Core Libs (`[JEP547]`) | **Adopt / Extend** | Enables `lazy def` in Babble backed by `LazyConstant` with `BIP016`-verified `@HERMETIC @DETERMINISTIC` initializers (guaranteed race-free and constant-folded by C2). |
| **Primitive Types in Patterns *(Final)*** | Amber (`[JEP532]`) | **Adopt** | Simplifies Babble's own Java implementation (`TypeChecker`, `Values`, `Operators`, `HirEmitter`) when switching over primitive/boxed numeric kinds. |
| **Enhanced Primitive Boxing / Universal Generics** | Valhalla | **Extend** | Eliminates boxing in generic Babble types (`list of int`, `Box of double`) at the JVM level rather than requiring manual `$spec` entry points alone. |
| **Vector API *(Post-Valhalla Graduation)*** | Panama (`[JEP537]`, 12th Incubator) | **Adopt / Extend** | Once Valhalla unblocks Vector API finalization, `BIP014` HIR/MIR + `BIP016` `pure` loops over primitive arrays (`array of double`) can lower directly to portable SIMD (`DoubleVector` / `FloatVector`) — or expose a safe `std/simd` API. |
| **Code Reflection** | Babylon (`[JEP8361105]`) | **Extend** | Babble's `BIP014` typed HIR + `BIP016` `pure` effect proofs align naturally with Babylon's `CodeModel`, opening a future path to offload pure numeric Babble kernels (e.g., ray-tracer shaders) to GPUs via HAT. |

---

## 4. Consolidated Feature Matrix (JDK 21 → JDK 29+)

| JDK | Date / LTS | Key Feature / JEP | Class | Concrete Babble Action |
|---|---|---|---|---|
| **21** | Sep 2023 · **LTS** | Sealed classes, records, `switch` patterns, Virtual Threads | Baseline | Current Babble floor (`pom.xml` `--release 21`, `V21`, ASM 9.11.1) |
| **22** | Mar 2024 | `JEP 454` FFM API | **Adopt / Extend** | Interop profiles; optional lifetime-checked `unique`/`@BORROWED` `Arena` wrappers |
| **22** | Mar 2024 | `JEP 456` Unnamed Variables `_` | **Adopt** | Cleanup in Babble Java sources (when on 22+); mirrors `_` in `BIP005-D3` |
| **23** | Sep 2024 | `JEP 471` Deprecate `Unsafe` memory access; `JEP 474` GenZGC | **Migrate (Audit)** | Verified 0 `Unsafe` calls in Babble; no bespoke `Babble 23` overlay needed |
| **24** | Mar 2025 | `JEP 484` **Class-File API** (`java.lang.classfile`) | **Adopt** | **Drop `org.ow2.asm:asm` + `asm-util`** across emitter, verifier & `BytecodeEffectAnalyzer` |
| **24** | Mar 2025 | `JEP 485` Stream Gatherers | **Adopt / Extend** | Interop profiles; `unique`-verified `pure` stateful gatherers |
| **24** | Mar 2025 | `JEP 491` Unpinned Virtual Threads | **Adopt** | Existing `synchronized` caches (`Bootstrap`, `Overloads`, `Launcher`) become VT-safe |
| **24** | Mar 2025 | `JEP 483` Leyden AOT Class Loading; `JEP 493` JMOD-less `jlink` | **Adopt** | Faster `babblec` startup & tiny `java.base`-only `jlink` images |
| **24** | Mar 2025 | `JEP 486` Disable SecurityManager; `JEP 472`/`498` JNI/`Unsafe` | **Migrate (Audit)** | Verified 0 uses in Babble; keep CI warning-free |
| **25** | Sep 2025 · **LTS** | `JEP 506` **Scoped Values** | **Adopt / Extend** | Ambient `ScriptRuntime`/interpreter context; `frozen`-only effect-tracked `scoped` bindings |
| **25** | Sep 2025 · **LTS** | `JEP 514`/`515` Leyden AOT Ergonomics & Method Profiling | **Adopt** | 1-step `babblec` AOT cache training (`-XX:AOTCacheOutput`) |
| **25** | Sep 2025 · **LTS** | `JEP 519` Compact Object Headers; `JEP 513` Flexible Ctors | **Adopt** | Smaller `BabbleInstance`/`Cell` footprint; cleaner Java ctor validation |
| **25** | Sep 2025 · **LTS** | `JEP 512` `java.lang.IO` in `java.base` | **Migrate** | Register `java.lang.IO.*` as `io` in `HostEffects` & nullable `readln` in `JavaNullability` |
| **26** | Mar 2026 | `JEP 500` **Prepare to Make Final Mean Final** | **Migrate / Extend** | Gate CI with `--illegal-final-field-mutation=deny`; emit `ACC_FINAL` on all immutable Babble fields |
| **26** | Mar 2026 | `JEP 504` Remove Applet; `JEP 516` AOT Any GC; `JEP 517` HTTP/3 | **Migrate / Adopt** | Verified 0 Applet refs; Leyden AOT works with ZGC/Shenandoah |
| **27** | Sep 2026 | `JEP 534` Compact Object Headers Default; `JEP 523` G1 Default | **Adopt** | Free memory/GC win; no bespoke `Babble 27` overlay needed |
| **28** | Mar 2027 *(EA)* | `JEP 543` **Structured Concurrency** *(Final)* | **Adopt / Extend** | **Compile-time race-free structured parallelism** (`BIP007` §10 + `frozen`/`unique`) |
| **28** | Mar 2027 *(EA)* | `JEP 544` **Leyden AOT Code Compilation** *(Final)* | **Adopt** | Cached native C1/C2 code for `babblec` jars (65–80% faster startup, dynamic-friendly) |
| **28** | Mar 2027 *(EA)* | `JEP 401` **Value Objects** *(Preview)* & `JEP 539` **Strict Field Init** *(Preview)* | **Migrate / Extend** | Audit `same()`/`ACC_IDENTITY`; lower Babble `record` → JVM `value class` & emit `ACC_STRICT_INIT` on class header fields *(preview-gated)* |
| **28** | Mar 2027 *(EA)* | `JEP 547` Lazy Constants *(4P)*; `JEP 540` Simple JSON *(I)* | **Adopt / Extend** | Track for `Module`/`JavaClass` lazy caching, `lazy def`, and `std/json` |
| **29+** | Sep 2027+ · **LTS** | Valhalla Final + Null-Restricted Storage (`8316779`), Vector API, Babylon | **Extend** | Null-free flattened storage for Babble's existing `T` vs `T?`; SIMD & GPU lowering |

---

## 5. Strategic Trade-Offs: Older LTS vs. Modern LTS vs. Bleeding Edge

Choosing a single JDK version for Babble involves a fundamental three-way tension:

### 5.1 The Value of Staying on an Older LTS (JDK 21)

* **Maximum reach:** JDK 21 remains the dominant production LTS in enterprise servers, Linux distribution repositories (Ubuntu 24.04 LTS ships OpenJDK 21 by default), CI runners, and educational environments (important given Babble's teaching goal in [`README.md`](../../README.md)).
* **Stability & zero flags:** No `--enable-preview`, no incubator modules, and full compatibility with mature tooling (JaCoCo, CodeQL, IDEs, Maven plugins).
* **The cost of staying *only* on JDK 21:** Babble stays permanently coupled to `org.ow2.asm:asm`, cannot use `ScopedValue` or `StructuredTaskScope` natively, cannot emit Valhalla `value class` or `ACC_STRICT_INIT` bytecode, and misses Leyden AOT code caches — deferring the exact JVM capabilities that amplify Babble's unique `record`/`T` vs `T?`/`BIP016` design.

### 5.2 The Value of a More Recent LTS (JDK 25)

* **The pragmatic sweet spot as of late 2026:** JDK 25 has been GA for over a year (since September 2025) and is supported through 2030+. It consolidates **two full years of finalized Java progress** — `FFM`, `Class-File API` (zero ASM!), `Stream Gatherers`, `Virtual Thread non-pinning`, `Scoped Values`, `Flexible Constructor Bodies`, `Compact Object Headers`, and `Leyden AOT Class Loading + Method Profiling` — all with **zero preview flags**.
* **The cost of hard-migrating to JDK 25:** Drops every JDK 21 environment overnight, including default distro JDKs and older CI/container base images.

### 5.3 The Danger of Requiring Bleeding-Edge / Preview Releases (JDK 28+)

* **Preview churn & `--enable-preview` friction:** A language whose *core* semantics require JDK 28 `--enable-preview` (`JEP 401`/`539`) forces every user of `babble` and every `babblec`-compiled `app.jar` to install a non-LTS (or EA) JDK and pass `--enable-preview` at launch, while preview classfiles (`72.65535`) are strictly tied to one exact JDK version (a `72.65535` classfile will not even load on JDK 29!).
* **Fragmentation risk:** If Babble 28's *surface language* diverges from Babble 21's, tutorials, examples (`examples/raytracer-argon`), and `.batest` suites split into incompatible dialects.

### 5.4 Guiding Design Principle: *One Core Language, Progressive Lowering, Additive Capability Tiers*

To capture the benefits of **all three worlds without their downsides**, `BIP018` enforces three strict invariants:

1. **Invariant 1 — One Core Language Across All Editions:** Every core Babble feature — including `BIP001`–`BIP017` and `BIP018`'s new `lazy def` and `scoped def` + `with ... then (...)` (§8) — MUST compile and produce **identical observable results** on `Babble 21`, `Babble 25`, and `Babble 28` (verified differentially by `BIP009`'s `.batest` suite). Where a core construct uses a newer JVM primitive (`ScopedValue` on 25+, `LazyConstant` when final, `StructuredTaskScope` on 28+), `PlatformRuntime` supplies a semantically equivalent polyfill on `Babble 21` (`BIP018-D7`).
2. **Invariant 2 — Progressive Lowering (Invisible Upgrades):** Newer Babble editions transparently improve *how* core Babble features compile and run — e.g., `Babble 25` compiles via `java.lang.classfile` without ASM and runs with `ScopedValue`, Compact Object Headers, and Leyden profiling; `Babble 28-preview` (and future `Babble 29`) lowers `record` to a JVM `value class` and emits `ACC_STRICT_INIT` on immutable header fields — **without requiring a single syntax change in user code**.
3. **Invariant 3 — Strictly Additive, Target-Gated Native Libraries:** Only capabilities that genuinely cannot be polyfilled on JDK 21 (such as `std/foreign` FFM wrappers requiring `java.lang.foreign` on JDK 22+) live in **explicitly target-gated modules** whose minimum target is enforced statically at compile time (`Error : std/foreign requires target JDK 22 or higher (current target : 21)`).

---

## 6. Release Scheme: `Babble 21`, `Babble 25`, `Babble 28` (`BIP018-D2`, `D3`, `D5`)

### 6.1 Edition Naming & Sparse Floor Inheritance

Under this scheme, Babble's **edition number tracks the target JDK feature release**:

* **`Babble 21`** — Targets **JDK 21 (LTS)** (runs on JDK 21+). Uses the JDK 21 runtime + ASM bytecode backend.
* **`Babble 25`** — Targets **JDK 25 (LTS)** (runs on JDK 25+). Switches bytecode backend from ASM to `java.lang.classfile` (`JEP 484`, zero ASM!) + native `ScopedValue` runtime + Leyden AOT ergonomics + `java.lang.IO` host profile + `std/foreign` FFM support.
* **`Babble 27` / `Babble 28`** — Frontier track targeting **JDK 27 (GA)** / **JDK 28 (EA → GA)**. Inherits `Babble 25` + `JEP 543` `StructuredTaskScope` runtime + `JEP 544` Leyden AOT Code Compilation support; plus an opt-in **`Babble 28-preview`** edition unlocking `JEP 401` Valhalla `value class` `record` lowering and `JEP 539` `ACC_STRICT_INIT`.

#### Sparse Floor Inheritance (Avoiding Busywork)

Edition resolution uses **sparse floor inheritance**:

$$\text{Babble}(N) = \text{Core}_{21} \;\oplus\; \bigoplus_{\substack{k \le N \\ k \in \text{Overlays}}} \text{Overlay}_k$$

Where $\text{Overlays}$ contains **only** JDKs that introduce real capability steps — currently `{21, 22 (std/foreign), 24 (Class-File API), 25 (ScopedValue), 28 (StructuredTaskScope), 28-preview (Valhalla)}`. Intermediate releases without Babble-specific API overlays (such as JDK 23, 26, and 27) require **zero** overlay code.

### 6.2 Three-Tier Active Support & Upstream-JDK EOL Sunsetting (`BIP018-D3`, `D5`)

Per `BIP018-D3` and `BIP018-D5`, Babble actively builds, tests, and publishes **three tiers** rather than maintaining EOL non-LTS editions (`22`, `23`, `24`, `26`), and retires each Baseline LTS strictly **by tracking upstream OpenJDK LTS End-of-Life**:

| Tier | Current Editions (Nov 2026) | CI Coverage *(Post-`BIP014`, `D4`)* | Published Artifacts (`D2`) | Lifecycle Rule (`D5`) |
|---|---|---|---|---|
| **Tier 1 — Baseline LTS** | **`Babble 21`** | Every PR: full dual-mode (`interpret` + `compile`) + `-Pbatest` | `org.babblelang:babble-21:<ver>` + `babble-runtime-21` | Maintained for as long as upstream OpenJDK/Temurin actively supports `jdk21u` LTS updates; retired (along with `babble-backend-asm`) when JDK 21 reaches upstream EOL. |
| **Tier 2 — Modern LTS (Recommended)** | **`Babble 25`** | Every PR: full dual-mode (`interpret` + `compile`, zero ASM) + `-Pbatest` | `org.babblelang:babble-25:<ver>` + `babble-runtime-25` | Primary recommended release for new deployments; advances on the 2-year JDK LTS cadence (`25` → `29` → `33`), tracking each LTS's upstream lifecycle. |
| **Tier 3 — Frontier / EA Track** | **`Babble 27`** *(current GA)* & **`Babble 28`** *(EA + preview)* | Scheduled nightly + opt-in PR label / workflow dispatch (JDK 27 GA & JDK 28 EA + `--enable-preview`) | `org.babblelang:babble-28:<ver>` & `babble-28-preview:<ver>` *(snapshots/milestones)* | Continuously validates Babble against upcoming JEPs (`JEP 500`, `543`, `544`, `401`, `539`) so LTS promotions are smooth and surprise-free. |

### 6.3 Artifact & Version Coordinates (`BIP018-D2`)

Per `BIP018-D2`, editions use **edition-qualified Maven artifact IDs with shared SemVer**:

* `org.babblelang:babble-21:<version>` (depends on `babble-core`, `babble-runtime`, `babble-backend-asm` + `asm`)
* `org.babblelang:babble-25:<version>` (depends on `babble-core`, `babble-runtime`, `babble-runtime-jdk25`, `babble-backend-classfile` — **zero ASM!**)
* `org.babblelang:babble-28:<version>` (adds `babble-runtime-jdk28` — **zero ASM!**)
* `org.babblelang:babble-28-preview:<version>` (adds `babble-runtime-valhalla` + `babble-backend-valhalla`)

This builds all active editions cleanly inside a **single Maven reactor**, keeps SemVer / Dependabot resolution unambiguous (preventing accidental `21.x → 25.x` jumps), and gives each edition its exact minimal dependency graph.

---

## 7. Branchless Monorepo Architecture (`BIP018-D1`, `D6`, `D8`)

Maintaining separate Git branches (`jdk21`, `jdk25`, `jdk28`) would duplicate bugfixes and fracture tests. All Babble editions live on **`master` in a single branchless multi-module Maven monorepo**, sharing **100% of the language front end** (`parser`, `analysis`, `format`, `interpreter`, `BIP014` `ir`/`opt`, and all `.ba`/`.batest` tests).

### 7.1 Architectural Comparison & Selected Pattern (`BIP018-D1`)

| Pattern | How It Works | Pros | Cons / Blockers |
|---|---|---|---|
| **Option A: Single-Module Multi-Release JAR (`JEP 238`)** | Keep one `pom.xml`; compile `src/main/java24`, `java25`, `java28` into `META-INF/versions/N/` (`[MavenMRJAR]`). | Preserves `BIP006-D1` single Maven module; one universal JAR. | **(1) Dependency asymmetry:** Must keep `asm` in the POM for JDK 25 users. **(2) Preview crash:** Putting `72.65535` preview classes in `META-INF/versions/28/` crashes non-preview JDK 28 runs (`UnsupportedClassVersionError`). **(3) Build JDK & IDE friction.** |
| **Option B: Multi-Module Maven Monorepo with Capability SPIs *(Selected — `BIP018-D1`)*** | Split into `babble-runtime`, `babble-core` (JDK 21), sparse overlay modules (`babble-backend-asm`, `babble-backend-classfile`, `babble-runtime-jdk25`, `babble-runtime-jdk28`, `babble-backend-valhalla`), and thin edition distributions (`babble-21`, `babble-25`, `babble-28`, `babble-28-preview`). | **Zero ASM** in `babble-25`/`28`; **clean `--enable-preview` isolation**; native IDE, Maven reactor & Toolchains support; JDK 21 developers build `babble-21` cleanly via default profile; per-module `RuntimeBoundaryTestCase`. | Introduces a parent/reactor POM (revising `BIP006-D1`'s single-module choice). |
| **Option C: Option B + Universal MRJAR Assembly** | Option B plus an extra packaging module assembling a JEP 238 MRJAR for stable non-preview tracks (`21` + `25` + `28-ga`). | Adds a single universal JAR option. | Extra packaging complexity and still bundles ASM inside the universal JAR for JDK 21 fallback; kept as a **purely additive future option** if users ever request it. |
| **Option D: Runtime Reflection / `MethodHandle` Probing** | Keep one JDK 21 module and probe newer APIs reflectively. | Zero build changes for 1-method probes. | Unworkable for `java.lang.classfile` (~100+ sealed types), `StructuredTaskScope`, or Valhalla `abstract value class` hierarchies. |

### 7.2 Concrete Monorepo Directory Layout

```text
babble/                                      (parent/aggregator pom.xml)
├── pom.xml                                  (dependencyManagement, profiles: default=jdk21, -Pjdk25, -Pjdk28, -Pjdk28-preview)
├── babble-runtime/                          (--release 21, java.base ONLY — field-free abstract BabbleInstance + PlatformRuntime SPI)
│   └── src/main/java/
│       ├── org/babblelang/runtime/          (abstract BabbleInstance, IdentityBabbleInstance, CompiledRecordBase, etc.)
│       ├── org/babblelang/runtime/jdk/      (PlatformRuntime SPI + Jdk21PlatformRuntime: ThreadLocal scoped, DCL lazy, VT executor)
│       └── org/babblelang/engine/impl/natives/
├── babble-runtime-jdk25/                    (--release 25, java.base ONLY — activated by -Pjdk25+)
│   └── src/main/java/
│       └── org/babblelang/runtime/jdk25/    (Jdk25PlatformRuntime: ScopedValue + std/foreign FFM support)
├── babble-runtime-jdk28/                    (--release 28, java.base ONLY — activated by -Pjdk28+)
│   └── src/main/java/
│       └── org/babblelang/runtime/jdk28/    (Jdk28PlatformRuntime: StructuredTaskScope parallel runtime)
├── babble-runtime-valhalla/                 (--release 28 --enable-preview, java.base ONLY — -Pjdk28-preview)
│   └── src/main/java/
│       └── org/babblelang/runtime/          (abstract value class BabbleInstance — binary-compatible drop-in for Valhalla!)
├── babble-core/                             (--release 21 — ~90% of the codebase!)
│   ├── src/main/antlr4/                     (Babble.g4 — or hand-written parser post-BIP015)
│   └── src/main/java/
│       ├── org/babblelang/analysis/         (BlockScopes, TypeChecker, EffectChecker, HostEffects, JavaNullability, BytecodeInspector SPI)
│       ├── org/babblelang/ir/               (BIP014 HIR / MIR — 100% backend-agnostic!)
│       ├── org/babblelang/opt/              (BIP014 Nanopass optimizer — 100% backend-agnostic!)
│       ├── org/babblelang/compiler/         (BabbleCompiler, Babblec, TargetProfile, BytecodeBackend SPI)
│       ├── org/babblelang/engine/           (BabbleScriptEngine, Interpreter, BabbleSession, ModuleSources)
│       ├── org/babblelang/effects/          (public @Pure..@IO annotations)
│       └── org/babblelang/format/           (Formatter)
├── babble-backend-asm/                      (--release 21 — ONLY module that depends on org.ow2.asm:asm!)
│   └── src/main/java/
│       └── org/babblelang/compiler/asm/     (AsmBytecodeBackend, AsmVerifier, AsmBytecodeInspector)
├── babble-backend-classfile/                (--release 24 — ZERO external deps! uses java.lang.classfile)
│   └── src/main/java/
│       └── org/babblelang/compiler/cf/      (ClassFileBytecodeBackend, ClassFileVerifier, ClassFileInspector)
├── babble-backend-valhalla/                 (--release 28 --enable-preview — extends ClassFileBytecodeBackend)
│   └── src/main/java/
│       └── org/babblelang/compiler/valhalla/ (ValhallaRecordEmitter: JEP 401 value class + JEP 539 ACC_STRICT_INIT)
├── babble-21/                               (edition artifact: core + runtime + backend-asm; includes asm)
├── babble-25/                               (edition artifact: core + runtime + runtime-jdk25 + backend-classfile; ZERO asm!)
├── babble-28/                               (edition artifact: core + runtime + runtime-jdk25/28 + backend-classfile; ZERO asm!)
├── babble-28-preview/                       (edition artifact: babble-28 + runtime-valhalla + backend-valhalla)
└── babble-tests/                            (shared src/test/babble/, src/test/batest/, examples/, JUnit suite)
```

Notice how cleanly every architectural constraint snaps into place:

1. **Zero front-end duplication:** `babble-core` stays at `--release 21` and holds the grammar/parser, all of `org.babblelang.analysis`, `Formatter`, `Interpreter`, `BabbleSession`/`Repl`, `BIP014` HIR/MIR and optimizer passes, and `Babblec`.
2. **Exact alignment with `BIP014` (`BIP018-D4`):** Because `BIP018` starts strictly after `BIP014` completes, `BytecodeBackend` consumes `BIP014`'s ANTLR-free `HirModule`/`MirModule` directly — both `AsmBytecodeBackend` (JDK 21) and `ClassFileBytecodeBackend` (JDK 24+) lower the exact same verified IR!
3. **Clean dependency pruning:** `org.ow2.asm:asm` and `asm-util` exist **only** in `babble-backend-asm`. `babble-25` and `babble-28` do not even have ASM on their classpath.
4. **Universal `BabbleInstance` refactoring (`BIP018-D8`, superseded by [`BIP020-D18`](bip020_array-types.md)):** *Superseded : compiled classes extend no runtime class at all, and `BabbleInstance` becomes the interpreter's alone (BIP020 §2.10).* Refactoring `BabbleInstance` in `babble-runtime` into a **field-free `abstract class BabbleInstance implements HasMembers`** (moving `Object[] values` and mutable `Object face` into subclasses, while compiled `record`s hold only their payload fields + `static final BabbleClass`) saves 8–16 bytes per compiled `record` immediately on `Babble 21`/`25` and keeps all ~22 `instanceof BabbleInstance` checks across `runtime/` and `Interpreter` untouched when `babble-runtime-valhalla` promotes `BabbleInstance` to `abstract value class BabbleInstance`.
5. **Strict preview isolation:** `babble-runtime-valhalla` and `babble-backend-valhalla` compile only under `-Pjdk28-preview`, so `72.65535` preview classfiles can never leak into `babble-21`, `babble-25`, or non-preview `babble-28`.
6. **Zero friction for JDK 21 contributors:** Running plain `mvn package` on JDK 21 builds and tests `babble-21` identically to today.

### 7.3 Narrow SPI Boundaries

Four narrow SPI interfaces (discovered via `java.util.ServiceLoader`, already used by `Launcher` for `ModuleCompiler` in `BIP006`) isolate all JDK-specific code:

1. **`org.babblelang.compiler.TargetProfile` (record in `babble-core`):**
   ```java
   public record TargetProfile(
       int jdkVersion,          // 21, 22, ..., 25, ..., 28
       boolean previewEnabled,  // true only for e.g. 28-preview
       int classfileMajor       // 65 (V21), 69 (V25), 72 (V28)
   ) {
       public boolean supportsFfm()                  { return jdkVersion >= 22; }
       public boolean supportsClassFileApi()         { return jdkVersion >= 24; }
       public boolean supportsScopedValues()         { return jdkVersion >= 25; }
       public boolean supportsStructuredConcurrency(){ return jdkVersion >= 28; }
       public boolean supportsValueObjects()         { return jdkVersion >= 28 && previewEnabled; }
       public boolean supportsStrictFieldInit()      { return jdkVersion >= 28 && previewEnabled; }
       public boolean supportsLazyConstants()        { return jdkVersion >= 29; } // updated upon JEP 547 finalization
   }
   ```
   Capability queries always check feature methods, never scattered magic version numbers.
2. **`org.babblelang.compiler.BytecodeBackend` (in `babble-core`):**
   * `int minHostJdk()` / `boolean supportsTarget(TargetProfile target)`
   * `CompiledClasses emit(HirModule module, TargetProfile target)`
   * `List<String> verify(byte[] bytecode, ClassLoader loader)` (`CheckClassAdapter` in ASM backend; `ClassFile.of(...).verify(bytes)` in Class-File backend)
   * `String disassemble(byte[] bytecode)` (`TraceClassVisitor` vs `ClassModel` printer)
   * `ClassFileBytecodeBackend` (on host JDK 24+) emits any target classfile version down to `ClassFile.JAVA_21_VERSION` (`65`), enabling zero-ASM cross-compilation to JDK 21 (`BIP018-D6`).
3. **`org.babblelang.analysis.BytecodeInspector` (in `babble-core`):**
   * Abstracts bytecode reading inside [`BytecodeEffectAnalyzer.java`](../../src/main/java/org/babblelang/analysis/BytecodeEffectAnalyzer.java) (`AsmBytecodeInspector` on JDK 21 vs. `ClassFileInspector` on JDK 24+).
4. **`org.babblelang.runtime.jdk.PlatformRuntime` (in `babble-runtime`, `java.base`-only):**
   * Resolved once at startup via `ServiceLoader` (selecting the highest-JDK provider supported by `Runtime.version().feature()`, falling back to `Jdk21PlatformRuntime`).
   * Provides minimal runtime primitives:
     * `LazySlot<T> newLazy(Supplier<T> init)` (double-checked lock holder on 21 → `LazyConstant` once final);
     * `ScopedKey<T> newScoped(T defaultValue)` + `withScoped(...)` (`ThreadLocal` immutable stack on 21 → `ScopedValue` on 25+);
     * `StructuredExecutor` (virtual-thread `ExecutorService` on 21 → `StructuredTaskScope` on 28+);
     * `boolean hasIdentity(Object o)` (`true` on 21–27 → `Objects.hasIdentity(o)` on 28-preview+).

### 7.4 `babblec` Cross-Targeting (`BIP018-D6`, `BIP006`) & `RuntimeBoundaryTestCase`

1. **`babblec -target 21|25|28|28-preview` cross-compilation:**
   * [`Babblec`](../../src/main/java/org/babblelang/compiler/Babblec.java) defaults `-target` to the running edition (e.g., `25` in `babble-25`), and accepts `-target N` for any $21 \le N \le \text{edition}$ (e.g., `java -jar babble-25.jar -compile main.ba -target 21 -o app.jar`).
   * Emits classfiles at `target.classfileMajor()` via `BytecodeBackend` (e.g., `ClassFileBytecodeBackend` emitting `V21` with zero ASM).
   * Statically rejects imports or calls to target-gated stdlib modules requiring a JDK newer than `-target` (e.g., `std/foreign` when `-target 21`).
   * Bundles **only** the runtime overlay modules with `minJdk <= target.jdkVersion()` (so `-target 21` bundles `babble-runtime` alone, excluding `babble-runtime-jdk25`, and writes `Created-By: babblec (target JDK 21)` while preserving deterministic sorted entries and fixed `ENTRY_TIME` timestamps).
2. **`RuntimeBoundaryTestCase` per runtime module:**
   * [`RuntimeBoundaryTestCase.java`](../../src/test/java/org/babblelang/tests/RuntimeBoundaryTestCase.java) runs inside every `babble-runtime*` module against that module's JDK `java.base`, guaranteeing no runtime overlay ever leaks `antlr`, `asm`, `javax.script`, or non-`java.base` JDK modules.

### 7.5 Maven Profiles, Toolchains & CI Verification Matrix

* **Local Developer Profiles (`pom.xml`):**
  * On JDK 21: `mvn package` builds and tests `babble-21` cleanly with zero extra setup.
  * On JDK 25+: `mvn -Pjdk25 package` (or automatic `<jdk>[25,)</jdk>` activation) builds and tests both `babble-21` (`--release 21`) and `babble-25` (`--release 25`, zero ASM).
  * On JDK 28 EA: `mvn -Pjdk25,jdk28,jdk28-preview test` exercises `StructuredTaskScope` and Valhalla preview lowering.
* **GitHub Actions CI Matrix (`.github/workflows/maven.yml`, activated after `BIP014` completes per `BIP018-D4`):**

```yaml
strategy:
  fail-fast: false
  matrix:
    include:
      - name: "Babble 21 (Baseline LTS)"
        java: "21"
        distribution: "temurin"
        maven_args: "-B package"
        jvm_args: ""
      - name: "Babble 25 (Modern LTS · Zero ASM)"
        java: "25"
        distribution: "temurin"
        maven_args: "-B -Pjdk25 package"
        jvm_args: ""
      - name: "Babble 27 (Current GA · JEP 500 Deny)"
        java: "27"
        distribution: "temurin"
        maven_args: "-B -Pjdk25 package"
        jvm_args: "--illegal-final-field-mutation=deny"
      # Nightly / manual / opt-in label:
      - name: "Babble 28 (EA · Structured Concurrency + Valhalla Preview)"
        java: "28-ea"
        distribution: "jdkfile"
        maven_args: "-B -Pjdk25,jdk28,jdk28-preview test"
        jvm_args: "--enable-preview --illegal-final-field-mutation=deny"
```

Every matrix cell executes the **same** `.ba` scripts, `DifferentialTestCase`, `.batest` corpus, `RuntimeBoundaryTestCase`, `BabblecTestCase` (including cross-targeting `-target 21` smoke runs), and `ExamplesTestCase`, proving byte-identical `examples/raytracer-argon/scene.ppm` across `interpret`, `babble-21` (ASM), `babble-25` (`Class-File API`), `babble-28`, and `babble-28-preview` (Valhalla).

---

## 8. Surface-Language & Stdlib "Extend" Specifications (`BIP018-D7`)

All new syntax introduced by `BIP018` preserves Babble's `BIP012` (`RM006`) grammar invariants: **zero `@parser::members`, zero semantic predicates, single bracket type `()`, strict LL(1)/LL(2), pure `PredictionMode.SLL`, and every new keyword exempted in `memberName`**.

### 8.1 `lazy def` — Pure, Deeply Immutable Lazy Constants

* **Motivation & How It Extends Beyond Java:** Java's `LazyConstant<T>` (`[JEP547]`) allows arbitrary side effects inside its supplier lambda and mutable objects as values, making initialization timing observable and thread-safety manual. Babble's `lazy def` enforces **`BIP016` purity and deep immutability statically**, so evaluation order is unobservable, concurrent first-access races are impossible, `BIP014` optimizers can treat every read as a pure constant load, and HotSpot C2 constant-folds the slot once initialized.
* **Grammar (`Babble.g4`, pure `SLL`):**
  * Add token `LAZY: 'lazy';` and include `LAZY` in `memberName` (so `obj.lazy` keeps working).
  * Extend `# defExpression`:
    ```antlr
    | (EXPORT | PRIVATE)? LAZY? DEF name=ID (OF typeParameters)? (':' type)?
                 ('=' NL* (effectClause ON)? value=expression)?   # defExpression
    ```
* **Static Rules (`BlockScopes`, `TypeChecker`, `EffectChecker`):**
  * **Placement:** Allowed at module top level (`export lazy def`), class/object/static bodies (`private lazy def`, `lazy def`), and local blocks (`lazy def`). Forbidden on `classParameters` and `interface` abstract declarations.
  * **Mandatory initializer & read-only:** A `lazy def` MUST have an `= expr` initializer and is strictly read-only (reassigning it is a compile-time error: `Cannot assign to lazy def : <name>`).
  * **Pure initializer (`BIP016`):** The initializer expression MUST be **pure** (`@HERMETIC @DETERMINISTIC` — no `io`, no `volatile`, no `mut` writes to captured/outer state). It may still construct fresh `unique` local structures and `freeze(...)` them before returning!
  * **Deeply immutable type:** The inferred or declared type MUST be deeply immutable — one of the 9 core scalars, `null`, a deep `record`, `frozen T`, a pure `Type.Function`, or a nullable/union thereof. (Returning a mutable `list of int` without `freeze(...)` is rejected statically.)
  * **Read effect:** Reading a `lazy def` is **100% pure** (`@HERMETIC @DETERMINISTIC`). If the initializer is also `nothrow @TERMINATING`, reading it inherits `nothrow @TERMINATING` as well.
  * **Exceptions & cycles:** If a `lazy def` initializer throws, the slot remains uninitialized (matching `LazyConstant`); direct self-recursion inside a `lazy def` initializer is rejected or raises a clear runtime cycle error (`Lazy def cycle : <name>`).
* **Example:**
  ```babble
  export lazy def sinTable: frozen list of double = (
      def buf = list of double (capacity: 360)
      def i = 0
      while i < 360 then (
          buf.add(java.lang.Math.sin(double(i) * 3.141592653589793 / 180.0))
          i += 1
      )
      freeze(buf)
  )
  ```
* **Runtime Lowering (Polyfilled on 21 → Native `LazyConstant` When Final):**
  * `Babble 21`–`28`: `PlatformRuntime.newLazy(supplier)` backed by a lightweight double-checked-locking `LazySlot<T>` (volatile sentinel + synchronized compute-once).
  * `Babble 29+` (once `JEP 547` finalizes): `PlatformRuntime.newLazy(supplier)` delegates directly to `java.lang.LazyConstant.of(supplier)`, unlocking HotSpot C2 trusted constant-folding.

### 8.2 `scoped def` + `with ... then (...)` — `frozen`-Only, Effect-Tracked Dynamic Scoping

* **Motivation & How It Extends Beyond Java:** Java's `ScopedValue<T>` (`[JEP506]`) has two sharp edges: (1) nothing prevents binding a mutable `ArrayList` and causing data races across virtual threads, and (2) `ScopedValue.get()` throws `NoSuchElementException` at runtime if called outside a `where(...).call(...)` scope, while silently breaking referential transparency of any method that reads it. Babble's `scoped def` eliminates all three hazards at compile time:
  1. **Deeply immutable (`frozen`) values only** — impossible to smuggle mutable shared state across threads.
  2. **Zero `NoSuchElementException`** — a `scoped def` either declares a default value (`scoped def ctx: frozen Config = defaultCfg` → reads as `frozen Config`) or omits the default and types reads as nullable (`scoped def reqId: string?` → reads as `string?`, evaluating to `null` when unbound!).
  3. **Effect-tracked via `BIP016` `volatile`** — reading a `scoped def` requires `volatile` (still `@HERMETIC` / no I/O, but non-`@DETERMINISTIC` across call contexts), adding **zero** new effect keywords while preserving pure-by-default functions.
* **Grammar (`Babble.g4`, pure `SLL`):**
  * Add tokens `SCOPED: 'scoped';` and `WITH: 'with';` and include both in `memberName`.
  * Add two LL(1) alternatives to `expression`:
    ```antlr
    | (EXPORT | PRIVATE)? SCOPED DEF name=ID ':' type ('=' NL* defaultValue=expression)? # scopedDefExpression
    | WITH scopedBinding (NL* ',' NL* scopedBinding)* THEN NL* withBlock=block           # withExpression
    ```
    where `scopedBinding: target=expression '=' NL* value=expression;` (with `BlockScopes`/`TypeChecker` requiring `target` to resolve to a `scoped def`, supporting module-qualified `cfg.traceId = "abc"`!).
* **Static Rules:**
  * **Placement:** `scoped def` is allowed at module top level (`export scoped def`) and class/object `static` blocks (defining a stable scoped key identity).
  * **Type restriction:** The declared type `T` MUST be deeply immutable (core scalar, deep `record`, `frozen T`, pure `Type.Function`, or nullable/union thereof). If no `= defaultValue` is provided, `T` MUST be nullable (`T?`) — omitting a default on a non-nullable type is a compile-time error (`Scoped def without default must be nullable : <name>`).
  * **Read-only binding:** A `scoped def` cannot be assigned with `=` outside a `with ... then (...)` header (`Cannot assign to scoped def : <name>; use with ... then (...)`).
  * **Effect rules:**
    * Reading a `scoped def` injects `@VOLATILE` (non-`@DETERMINISTIC`, still `@HERMETIC`) into the enclosing function's inferred effects, so typed functions reading a `scoped def` must declare `fn volatile`.
    * `with k = v then ( body )` evaluates `v` in the outer scope, runs `body` in the dynamically rebound scope, and restores the outer binding on normal or exceptional exit; `with` itself is pure if `v` and `body` are pure (note that calling a `volatile` helper inside `body` still propagates `volatile` unless future context-discharge rules apply).
* **Example:**
  ```babble
  export scoped def locale: string = "en-US"
  export scoped def requestId: string?

  def formatGreeting = fn volatile (name: string): string -> (
      def tag = if requestId != null then ( "[" + requestId + "] " ) else ( "" )
      if locale == "fr-FR" then ( tag + "Bonjour, " + name ) else ( tag + "Hello, " + name )
  )

  def out = with locale = "fr-FR", requestId = "req-42" then (
      formatGreeting("Ada")
  )
  ;; out == "[req-42] Bonjour, Ada" ; outside `with`, locale is "en-US" and requestId is null
  ```
* **Runtime Lowering (Polyfilled on 21 → Native `ScopedValue` on 25+):**
  * `Babble 21`: `Jdk21PlatformRuntime` backs each `ScopedKey<T>` with a `ThreadLocal<T>` restored in a `try`/`finally` block (with an explicit capture helper when spawning virtual threads via `PlatformRuntime.StructuredExecutor` so child tasks inherit bindings identically to `ScopedValue`!).
  * `Babble 25+`: `Jdk25PlatformRuntime` backs each `ScopedKey<T>` directly with `java.lang.ScopedValue.newInstance()`, using `ScopedValue.where(k, v).call(...)` (nesting multiple bindings in `with a = ..., b = ... then (...)` via chained `.where(k1, v1).where(k2, v2)`) and `k.orElse(defaultValue)`.

### 8.3 Target-Gated `std/foreign` — Lifetime-Checked FFM Wrappers (`-target 22+`)

* **Motivation & How It Extends Beyond Java:** Java's Foreign Function & Memory API (`[JEP454]`, JDK 22+) protects against C use-after-free with *runtime* session checks (`IllegalStateException: Already closed`), which fail only when a leaked `MemorySegment` is dereferenced after `Arena.close()`. Babble's `BIP007`/`BIP016`/`BIP017` ownership and effect system can catch arena-escape bugs **at compile time**!
* **Target Gate (`Invariant 3`):** Importing `std/foreign` requires `TargetProfile.supportsFfm()` (`jdkVersion >= 22`). Compiling against `-target 21` fails cleanly at compile time:
  ```text
  Error : std/foreign requires target JDK 22 or higher (current target : 21)
  ```
* **Lifetime-Safe API Design (Zero New Grammar Needed!):**
  * Uses existing `fn calling f`, `@BORROWED` (the unmarked default on parameters in `BIP016`!), `mut`, and `unique`!
  * `std/foreign.withConfinedArena` takes a `fn calling f` callback whose `ArenaView` / `Segment` parameter is **borrowed** (`@BORROWED`, non-`shared`) and cannot be returned or stored into an outer variable:
    ```babble
    def foreign = import("std/foreign.ba")

    def checksum = fn (n: long): long -> (
        foreign.withConfinedArena(fn (mut arena: foreign.ConfinedArena): long -> (
            def seg = arena.allocateLongs(n)   ;; derived from @BORROWED arena -> cannot escape!
            seg.setAtIndex(0L, 42L)
            seg.getAtIndex(0L)                 ;; returning core scalar `long` is valid!
            ;; returning `seg` here is a COMPILE-TIME ERROR (cannot return @BORROWED derived value)
        ))
    )
    ```
  * Native C library calls via `java.lang.foreign.Linker` are marked `io` in `std/foreign`, while pure off-heap scratch-buffer allocations inside a confined arena stay `@HERMETIC` (allowing zero-GC native scratch buffers even inside pure numeric Babble routines!).

---

## 9. Interaction with Existing & Draft BIPs

* **`BIP006` (`BIP006-D1` Single-Module Preference & `babblec`):** `BIP006-D1` kept Babble as a single Maven module when there was only one JDK target and one bytecode backend. `BIP018-D1` revisits `BIP006-D1` cleanly once `BIP018-P1` begins, preserving `BIP006`'s `java.base`-only runtime boundary and deterministic `babblec` AOT jars while adding `-target N` cross-targeting (`BIP018-D6`).
* **`BIP014` (Compiler Optimization Passes — `BIP018-D4`):** **Strict sequencing gate!** All of `BIP018` (including `P0`) begins strictly **after `BIP014` completes**, keeping CI focused on JDK 21 during `BIP014` and letting `AsmBytecodeBackend` (JDK 21) and `ClassFileBytecodeBackend` (JDK 24+) both lower `BIP014`'s finalized, ANTLR-free `HirModule`/`MirModule`.
* **`BIP015` (Down with the Ants):** Complementary dependency reduction. Together, `BIP015` (if a non-ANTLR parser is chosen) + `BIP018` (`babble-25`+ dropping ASM via `JEP 484`) reduce Babble's entire compiler, formatter, REPL, and runtime to **zero third-party dependencies**.
* **`BIP007` §10 & `BIP016`/`BIP017` (Verified Parallelism):** `Babble 25` (`ScopedValue`) + `Babble 28` (`StructuredTaskScope`) transparently back `BIP007` §10's `std/` parallel combinators via `PlatformRuntime`, with virtual-thread executor fallback on `Babble 21`.
* **`BIP002` (`record`), `BIP011` (`T` vs. `T?`) & `BIP020` (`BIP020-D18`):** Because `BIP020-I1` (`BIP020-D18`, superseding `BIP018-D8`) already removed any runtime base class from compiled classes and records (`extends java.lang.Object`, with `InterpretedInstance` interpreter-only), compiled `record`s are already ready for zero-source-change Valhalla `value class` lowering (`JEP 401`/`539`) and future null-restricted storage (`JEP draft 8316779`).

---

## 10. Phased Rollout Plan (`BIP018-P0`–`P4`)

Per `BIP018-D4`, **`BIP018` begins strictly after `BIP014` completes**. Each phase is independently verifiable, keeps `babble-21` 100% green, and passes full dual-mode parity:

* **`BIP018-P0` — Multi-JDK CI Matrix & Interop Profiles *(First PR after `BIP014`)*:**
  * Add JDK 25 LTS and JDK 27 GA legs to [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml) running with `--illegal-final-field-mutation=deny` on JDK 26+ (`JEP 500`).
  * Add `java.lang.IO` (`JEP 512`), `java.util.stream.Gatherer`/`Gatherers` (`JEP 485`), and `java.lang.foreign.*` (`JEP 454`) entries to `HostEffects` and `JavaNullability`.
  * Document Leyden AOT cache usage (`-XX:AOTCacheOutput=app.aot`) with `babblec` jars on JDK 25+.
* **`BIP018-P1` — Multi-Module Monorepo Split & SPIs (`BIP018-D1`, `D2` ; `BIP018-D8` superseded by `BIP020-D18`):**
  * Split into `babble-runtime`, `babble-core`, `babble-backend-asm`, `babble-21`, and `babble-tests` with `TargetProfile`, `BytecodeBackend`, `BytecodeInspector`, and `PlatformRuntime` SPIs — **zero behavioral change** on JDK 21 (plain compiled classes without `BabbleInstance` already landed in `BIP020-I1`).
  * Wire `babblec -target N` (`BIP018-D6`) and per-module `RuntimeBoundaryTestCase`.
* **`BIP018-P2` — `Babble 25` (Modern LTS · Zero ASM) + `lazy def` & `scoped def` + `std/foreign` (`BIP018-D6`, `D7`):**
  * Add `babble-backend-classfile` (`--release 24`, `JEP 484` `java.lang.classfile` emitter, verifier, and `BytecodeEffectAnalyzer` inspector — **zero `org.ow2.asm:asm`**), `babble-runtime-jdk25` (`--release 25`, `JEP 506` `ScopedValue`), and `babble-25`.
  * Implement predicate-free pure-`SLL` **`lazy def`** (§8.1) and **`scoped def` + `with ... then (...)`** (§8.2) across grammar, analysis, `Interpreter`, `HIR`/`MIR`, and both backends (polyfilled on `babble-21`, native `ScopedValue` on `babble-25`, ready for `LazyConstant` once finalized), plus target-gated **`std/foreign`** (`-target 22+`, §8.3).
  * Emit `ACC_FINAL` systematically on all immutable `record` and non-reassigned `class` header fields (`JEP 500` synergy).
  * Verify 100% differential parity across `interpret`, `babble-21` (ASM), `babble-25` (`Class-File API`), and `babble-25 -target 21` cross-compiled jars, including byte-identical `examples/raytracer-argon/scene.ppm`.
* **`BIP018-P3` — `Babble 28` (Structured Concurrency & Leyden AOT Code Compilation):**
  * Add `babble-runtime-jdk28` (`--release 28`, `JEP 543` `StructuredTaskScope` backing `BIP007` §10's parallel combinators via `PlatformRuntime`) and `babble-28`.
  * Benchmark `babblec` + `JEP 544` Leyden AOT Code Compilation on CLI and ray-tracer startup/warmup.
* **`BIP018-P4` — `Babble 28-preview` → `Babble 29` LTS (Valhalla Value `record`s & `ACC_STRICT_INIT`):**
  * Add profile-gated `babble-runtime-valhalla` (`abstract value class BabbleInstance`) and `babble-backend-valhalla` (`-Pjdk28-preview`) emitting `JEP 401` value classes for Babble `record`s and `JEP 539` `ACC_STRICT_INIT | ACC_FINAL` for immutable class header fields, assembled in `babble-28-preview`.
  * Benchmark `examples/raytracer-argon` (`Vec3`/`Ray`/`Color` allocation & throughput) against `babble-21` and `babble-25` before promoting to non-preview when Valhalla finalizes (JDK 29 LTS+).

---

## 11. Design Decisions (`BIP018-Q1`–`Q8` → `BIP018-D1`–`D8`)

| Question | Decision | Resolution |
|---|---|---|
| `BIP018-Q1` | **`BIP018-D1`** | **Multi-module Maven monorepo with capability SPIs (`Option B`):** Split into `babble-runtime`, `babble-core` (`--release 21`), sparse JDK overlay modules (`babble-backend-asm`, `babble-backend-classfile`, `babble-runtime-jdk25`, `babble-runtime-jdk28`, profile-gated `babble-runtime-valhalla` / `babble-backend-valhalla`), and thin per-edition distributions (`babble-21`, `babble-25`, `babble-28`, `babble-28-preview`). Gives `babble-25`/`28` zero ASM dependency and isolates `--enable-preview` cleanly; a universal MRJAR assembly (`Option C`) remains a purely additive option later if desired. |
| `BIP018-Q2` | **`BIP018-D2`** | **Edition-qualified artifact IDs with shared SemVer:** `org.babblelang:babble-21:<ver>`, `babble-25:<ver>`, `babble-28:<ver>`, `babble-28-preview:<ver>`. Builds cleanly in a single Maven reactor, preserves standard SemVer ordering, and keeps per-edition POM dependencies exact. |
| `BIP018-Q3` | **`BIP018-D3`** | **Active tiers only with sparse floor inheritance:** Actively build, test, and publish Baseline LTS (`Babble 21`), Modern LTS (`Babble 25`), and Current Frontier / EA (`Babble 27` / `28` + `28-preview`). EOL non-LTS editions (`22`, `23`, `24`, `26`) require no separate releases or CI legs; overlays exist only for JDKs that introduce real capabilities (`21`, `22` `std/foreign`, `24` Class-File API, `25` `ScopedValue`, `28` `StructuredTaskScope`, `28-preview` Valhalla). |
| `BIP018-Q4` | **`BIP018-D4`** | **Sequenced strictly after `BIP014` completes:** Bundle all of `BIP018` (including `BIP018-P0` CI matrix & host profiles) strictly after `BIP014` finishes, keeping CI on JDK 21 during `BIP014` and ensuring `ClassFileBytecodeBackend` lowers `BIP014`'s finalized `HirModule`/`MirModule` directly. |
| `BIP018-Q5` | **`BIP018-D5`** | **Track upstream OpenJDK LTS End-of-Life:** Keep each Baseline LTS edition supported for as long as its upstream OpenJDK / Eclipse Temurin LTS update train (`jdk21u`, `jdk25u`, `jdk29u`, …) remains actively maintained, retiring `Babble N` (and legacy-only backends such as `babble-backend-asm` for `Babble 21`) once JDK $N$ reaches upstream EOL. |
| `BIP018-Q6` | **`BIP018-D6`** | **Support `babblec -target N` cross-targeting:** Newer editions (e.g., `babble-25`) support `-target 21` (emitting `V21` bytecode via `java.lang.classfile` with zero ASM, bundling only `babble-runtime`, and rejecting `22+`/`25+`-only target-gated stdlib calls statically at compile time). |
| `BIP018-Q7` | **`BIP018-D7`** | **Include polyfilled core grammar additions + target-gated `std/foreign` in `BIP018`:** Design predicate-free pure-`SLL` **`lazy def`** (pure `@HERMETIC @DETERMINISTIC`, deeply immutable, top-level/class-object/local blocks; DCL holder on 21 → `LazyConstant` once final) and **`scoped def` + `with ... then (...)`** (deeply immutable/`frozen`-only, mandatory default or nullable `T?` preventing `NoSuchElementException`, reads tracked via existing `BIP016` `volatile` effect; `ThreadLocal` stack on 21 → `ScopedValue` on 25+) with full `Babble 21` polyfills via `PlatformRuntime`, plus target-gated **`std/foreign`** (`-target 22+`, `@BORROWED`/`unique` lifetime-checked `Arena`/`MemorySegment`). `StructuredTaskScope` (JDK 28+) backs `BIP007` §10's `std/` parallel combinators transparently with no new `parallel` block syntax in `BIP018`. |
| `BIP018-Q8` | **`BIP018-D8`** | **Universal field-free `abstract class BabbleInstance` refactoring:** Refactor `BabbleInstance` across all editions (`BIP018-P1`) into a field-free `abstract class` (moving `Object[] values` and mutable `Object face` into subclasses), shrinking compiled `record` instances immediately on `Babble 21`/`25`, keeping all ~22 `instanceof BabbleInstance` sites intact, and letting `babble-runtime-valhalla` (`BIP018-P4`) simply flip it to `abstract value class BabbleInstance`. |

---

## 12. References

* **`[JDK22]`–`[JDK28]`** OpenJDK Release Project Pages: *[JDK 22](https://openjdk.org/projects/jdk/22/)*, *[JDK 23](https://openjdk.org/projects/jdk/23/)*, *[JDK 24](https://openjdk.org/projects/jdk/24/)*, *[JDK 25 (LTS)](https://openjdk.org/projects/jdk/25/)*, *[JDK 26](https://openjdk.org/projects/jdk/26/)*, *[JDK 27](https://openjdk.org/projects/jdk/27/)*, *[JDK 28](https://openjdk.org/projects/jdk/28/)*.
* **`[JEP238]`** Paul Sandoz. *[JEP 238: Multi-Release JAR Files](https://openjdk.org/jeps/238)* (JDK 9) and Apache Maven Compiler Plugin *[Multi Release Guide](https://maven.apache.org/plugins/maven-compiler-plugin/multirelease.html)*.
* **`[JEP401]`** Dan Smith. *[JEP 401: Value Objects (Preview)](https://openjdk.org/jeps/401)* (Integrated in JDK 28; Project Valhalla).
* **`[JEP454]`** Maurizio Cimadamore. *[JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)* (Final in JDK 22; Project Panama).
* **`[JEP456]`** Gavin Bierman. *[JEP 456: Unnamed Variables & Patterns](https://openjdk.org/jeps/456)* (Final in JDK 22; Project Amber).
* **`[JEP471]` / `[JEP472]` / `[JEP486]` / `[JEP498]`** Integrity-by-Default & Removals: *[JEP 471: Deprecate `sun.misc.Unsafe` Memory-Access Methods](https://openjdk.org/jeps/471)* (JDK 23), *[JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472)* (JDK 24), *[JEP 486: Permanently Disable the Security Manager](https://openjdk.org/jeps/486)* (JDK 24), *[JEP 498: Warn upon Use of `sun.misc.Unsafe` Memory-Access Methods](https://openjdk.org/jeps/498)* (JDK 24).
* **`[JEP483]` / `[JEP514]` / `[JEP515]` / `[JEP516]` / `[JEP544]`** Project Leyden AOT Series: *[JEP 483: AOT Class Loading & Linking](https://openjdk.org/jeps/483)* (JDK 24), *[JEP 514: AOT Command-Line Ergonomics](https://openjdk.org/jeps/514)* (JDK 25), *[JEP 515: AOT Method Profiling](https://openjdk.org/jeps/515)* (JDK 25), *[JEP 516: AOT Object Caching with Any GC](https://openjdk.org/jeps/516)* (JDK 26), *[JEP 544: Ahead-of-Time Code Compilation](https://openjdk.org/jeps/544)* (JDK 28).
* **`[JEP484]`** Brian Goetz & Adam Sotona. *[JEP 484: Class-File API](https://openjdk.org/jeps/484)* (Final in JDK 24; `java.lang.classfile`).
* **`[JEP485]`** Viktor Klang. *[JEP 485: Stream Gatherers](https://openjdk.org/jeps/485)* (Final in JDK 24).
* **`[JEP491]`** Patricio Chilano Mateo & Alan Bateman. *[JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)* (Final in JDK 24).
* **`[JEP493]`** Severin Gehwolf. *[JEP 493: Linking Run-Time Images without JMODs](https://openjdk.org/jeps/493)* (Final in JDK 24).
* **`[JEP500]`** Ron Pressler & Alex Buckley. *[JEP 500: Prepare to Make Final Mean Final](https://openjdk.org/jeps/500)* (Final in JDK 26).
* **`[JEP506]`** Andrew Haley. *[JEP 506: Scoped Values](https://openjdk.org/jeps/506)* (Final in JDK 25; Project Loom).
* **`[JEP511]` / `[JEP512]` / `[JEP513]`** JDK 25 Language JEPs: *[JEP 511: Module Import Declarations](https://openjdk.org/jeps/511)*, *[JEP 512: Compact Source Files and Instance Main Methods](https://openjdk.org/jeps/512)* (`java.lang.IO`), *[JEP 513: Flexible Constructor Bodies](https://openjdk.org/jeps/513)*.
* **`[JEP519]` / `[JEP534]`** Project Lilliput: *[JEP 519: Compact Object Headers](https://openjdk.org/jeps/519)* (Final opt-in in JDK 25), *[JEP 534: Compact Object Headers by Default](https://openjdk.org/jeps/534)* (JDK 27).
* **`[JEP532]` / `[JEP537]` / `[JEP540]` / `[JEP547]`** Active Preview & Incubator JEPs: *[JEP 532: Primitive Types in Patterns (5th Preview)](https://openjdk.org/jeps/532)* (JDK 27), *[JEP 537: Vector API (12th Incubator)](https://openjdk.org/jeps/537)* (JDK 27), *[JEP 540: Simple JSON API (Incubator)](https://openjdk.org/jeps/540)* (JDK 28), *[JEP 547: Lazy Constants (4th Preview)](https://openjdk.org/jeps/547)* (proposed JDK 28; formerly `JEP 502`/`526`/`531`).
* **`[JEP539]`** Dan Smith. *[JEP 539: Strict Field Initialization in the JVM (Preview)](https://openjdk.org/jeps/539)* (Integrated in JDK 28; Project Valhalla).
* **`[JEP543]`** Alan Bateman. *[JEP 543: Structured Concurrency](https://openjdk.org/jeps/543)* (Final in JDK 28; Project Loom).
* **`[JEP8303099]` / `[JEP8316779]` / `[JEP8361105]`** Horizon Draft JEPs: *[Null-Restricted and Nullable Types (Preview)](https://openjdk.org/jeps/8303099)*, *[Null-Restricted Value Class Types (Preview)](https://openjdk.org/jeps/8316779)* (Project Valhalla), and *[Code Reflection (Incubator)](https://openjdk.org/jeps/8361105)* (Project Babylon).
