# BIP018: Tracking Java Improvements

| | |
|---|---|
| **BIP** | 018 |
| **Date** | 2026-11-20 |
| **Status** | Draft (Strategic & Architectural Proposal; Open Questions `BIP018-Q1`–`Q8`) |
| **Depends on** | Bytecode compiler (`RM001`/`RM003`, [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), object model & `record`s ([bip002_object-model.md](bip002_object-model.md)), module sources, `java.base`-only runtime boundary & `babblec` ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static effects, ownership & algebraic properties (`RM002`/`RM007`/`RM008`/`RM009`, [bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md)), static typing & core types (`RM005`, [bip008_static-typing.md](bip008_static-typing.md), [bip011_core-types.md](bip011_core-types.md)), `.batest` differential parity harness ([bip009_test-coverage.md](bip009_test-coverage.md)), compiler IR & `CodeGenerator` decomposition ([bip014_optimization-passes.md](bip014_optimization-passes.md) `BIP014-P0`–`P1`), and parser dependency evaluation ([bip015_down-with-the-ants.md](bip015_down-with-the-ants.md)) |
| **Scope** | Release-by-release audit of JDK 22 through JDK 28 (plus the JDK 29 LTS+ horizon) classified into **Change** (mandatory migration), **Adopt** (optional leverage), and **Exceed** (Babble capabilities beyond Java unlocked by new JVM/JDK features); explicit portability vs. modernity trade-offs; a **`Babble N` ↔ `JDK N` release scheme**; and a **branchless monorepo architecture** supporting multi-JDK compilation, testing, and `babblec` cross-targeting |

---

## 1. Executive Summary

Babble is deliberately pinned today to **JDK 21** (`<maven.compiler.release>21</maven.compiler.release>` in [`pom.xml`](../../pom.xml), `V21` emitted in [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java), and JDK 21 in [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml)). That baseline was chosen for good reason: JDK 21 is a widely deployed Long-Term Support (LTS) release that already gives Babble `sealed` hierarchies, `record`s, pattern matching for `switch`, `invokedynamic`, `MethodHandles`, `LambdaMetafactory`, and Virtual Threads.

Since JDK 21 reached General Availability in September 2023, six major JDK releases have shipped (**JDK 22** through **JDK 27**, including the next LTS, **JDK 25**), and **JDK 28** (GA March 2027, early access in November 2026) is bringing landmark platform shifts:

* **JDK 24 / 25 LTS** finalized the **Class-File API** (`[JEP484]`, capable of replacing Babble's `org.ow2.asm:asm` dependency entirely), **Scoped Values** (`[JEP506]`), **Stream Gatherers** (`[JEP485]`), **Virtual Thread non-pinning** (`[JEP491]`), **Compact Object Headers** (`[JEP519]`, on by default in **JDK 27** `[JEP534]`), **Flexible Constructor Bodies** (`[JEP513]`), and **Project Leyden AOT Class Loading & Method Profiling** (`[JEP483]`, `[JEP514]`, `[JEP515]`).
* **JDK 26** tightened JVM integrity via **Prepare to Make Final Mean Final** (`[JEP500]`) and extended Leyden AOT object caching to all GCs (`[JEP516]`).
* **JDK 28** finalizes **Structured Concurrency** (`[JEP543]`) and **Leyden Ahead-of-Time Code Compilation** (`[JEP544]`), and integrates Project Valhalla's **Value Objects** (`[JEP401]`, Preview) and **Strict Field Initialization in the JVM** (`[JEP539]`, Preview) — two JVM capabilities that match Babble's existing `record` (`BIP002`/`RM006`), non-null (`BIP008`/`RM005`), and `BIP007`/`BIP016` immutability invariants almost one-to-one.

Yet blindly bumping Babble's minimum JDK to 28 (or requiring `--enable-preview`) would break Babble's portability across the vast JDK 21 and JDK 25 LTS install base, while staying frozen on JDK 21 forever leaves transformative capabilities — zero-ASM bytecode emission, zero-identity flattened `record`s, JVM-verified strict finals, Leyden AOT code caches, and compile-time race-free structured concurrency — on the table.

This proposal addresses both sides of that tension:

1. **A three-way JEP audit (§3–§4)** across **JDK 22 → JDK 28** and the **JDK 29+ horizon**, classifying every significant feature as:
   * **Change (Mandatory Migration)** — removals, warnings-becoming-errors, or JVM behavioral shifts Babble *must* adapt to;
   * **Adopt (Optional)** — Java/JVM improvements Babble can leverage internally or expose via interop;
   * **Exceed (Babble Beyond Java)** — language guarantees or JVM lowering wins Babble can offer *that Java itself cannot express*, because Babble already proves non-nullability, `record` identity-freedom, `frozen`/`unique` ownership, and pure-by-default effects statically.
2. **An explicit trade-off analysis (§5)** weighing older LTS reach (JDK 21), modern LTS ergonomics (JDK 25), and bleeding-edge/preview risk (JDK 28+), establishing the guiding rule: **One Core Language, Progressive Lowering, Additive Capability Tiers**.
3. **A `Babble N` release scheme (§6)** — `Babble 21`, `Babble 22`, …, `Babble 25`, …, `Babble 28` — using **sparse floor inheritance** (layers are only written for JDKs that add real capabilities) and three clear support tiers (Baseline LTS, Modern LTS, Frontier/Preview).
4. **A branchless monorepo architecture (§7)** comparing four concrete Maven/JVM patterns (Single-Module Multi-Release JAR, Multi-Module SPI Monorepo, Hybrid Multi-Module + Optional MRJAR + Isolated Preview Module, and Runtime Reflection Probing), detailing exact SPI boundaries, `babblec` cross-targeting (`-target 21|25|28`), `RuntimeBoundaryTestCase`, and CI matrix verification.

---

## 2. Classification Framework & Codebase Baseline

### 2.1 The Three Action Categories

Every JDK feature reviewed in this document is assigned one (or more, when a feature has both a compliance and an opportunity dimension) of three classifications:

| Category | Meaning | Impact on Babble Users |
|---|---|---|
| **Change** *(Mandatory Migration)* | Breaking API removals, stricter JVM defaults (`Integrity by Default`), bytecode verifier changes, or semantic shifts in `java.base` classes that Babble *must* handle to compile, pass tests, and run warning-free on JDK $N$. | Invisible when handled cleanly; prevents runtime warnings, `IdentityException`, or `IllegalAccessException` failures on newer JVMs. |
| **Adopt** *(Optional)* | New JDK APIs, compiler/runtime primitives, or JVM optimizations that Babble can optionally use inside its own implementation (compiler, runtime, `babblec`, `BytecodeEffectAnalyzer`, REPL) or map cleanly in Java interop (`HostEffects`, `JavaNullability`). | Smaller dependencies (dropping ASM), faster startup/warmup (Leyden), lower memory (Lilliput), cleaner Java interop — with **zero** change to Babble's core semantics. |
| **Exceed** *(Beyond Java)* | Capabilities Babble can deliver to Babble programmers that **do not exist in Java** (or that Java cannot statically enforce / ergonomically emit) by combining new JVM primitives with Babble's static type, ownership, and effect systems (`BIP002`, `BIP007`, `BIP008`, `BIP011`, `BIP016`, `BIP017`). | Makes Babble strictly safer or faster than equivalent idiomatic Java — e.g., automatic Valhalla `value class` lowering for `record`s, `ACC_STRICT_INIT` on normal classes, or compile-time race-free structured concurrency. |

### 2.2 Babble's Current JDK 21 Footprint (Baseline Audit)

Before reviewing JDK 22+, a direct audit of Babble's current codebase establishes an unusually clean starting point:

* **Bytecode & dependencies ([`pom.xml`](../../pom.xml)):** Only two non-JDK runtime dependencies — `org.antlr:antlr4-runtime:4.13.2` (evaluated in `BIP015`) and `org.ow2.asm:asm:9.11` (plus optional `asm-util` for `CheckClassAdapter`/`TraceClassVisitor`). Emitted class files target `V21` in [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) (`L730`, `L1078`, `L1686`), all setting `ACC_SUPER` on concrete classes.
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

This means Babble has **remarkably low mandatory migration debt** across JDK 22–28, letting nearly all engineering effort focus on high-leverage **Adopt** and **Exceed** capabilities.

---

## 3. Release-by-Release JDK Review (JDK 22 → JDK 28 & Horizon)

Each release below summarizes its release date, LTS/support status, and every JEP with concrete impact on Babble, grouped by **Change**, **Adopt**, and **Exceed**. Preview/incubator JEPs are tracked at their *finalization* release unless their preview marks a strategic inflection point (notably JDK 28's Valhalla JEPs).

### 3.1 JDK 22 *(GA: March 2024 · Non-LTS · EOL)*

*Integrated JEPs: 423, 447 (P), 454, 456, 457 (P), 458, 459 (2P), 460 (7I), 461 (P), 462 (2P), 463 (2P), 464 (2P).*

* **Change (Mandatory):** None for Babble's codebase. (`JEP 423` G1 region pinning is transparent.)
* **Adopt (Optional):**
  * **`[JEP454]` Foreign Function & Memory (FFM) API *(Final)*:** Replaces fragile JNI with `java.lang.foreign` (`Arena`, `MemorySegment`, `Linker`) inside `java.base`.
    * *Interop:* Add `java.lang.foreign.*` nullability and `io`/`mut` effect profiles to `JavaNullability` and `HostEffects`.
    * *Tooling:* Unblocks pure-JDK native integration where needed (e.g., raw terminal line-editing in `Repl` without JNI, or `BIP015` Option 4 Tree-sitter if ever revisited — noting `java-tree-sitter` targets JDK 23+ FFM).
  * **`[JEP456]` Unnamed Variables & Patterns (`_`) *(Final)*:** Cleanups across Babble's own Java implementation (unused catch parameters, pattern-matching switch bindings in `TypeChecker`, `EffectChecker`, and `BIP014` HIR passes).
  * **`[JEP458]` Launch Multi-File Source-Code Programs *(Final)*:** No direct Babble requirement (`babble` already has `BIP005` modules).
* **Exceed (Babble Beyond Java):**
  * **Lifetime-checked off-heap memory (`Arena` + `BIP007`/`BIP016`):** Java's FFM API relies on *runtime* liveness checks (`IllegalStateException: Already closed`) when a `MemorySegment` escapes a closed `Arena.ofConfined()`. Because Babble already tracks `@BORROWED` non-escape and `unique`/`move` ownership (`RM007`/`RM008`), a Babble `std/foreign` wrapper (e.g., `withArena(fn (mut seg: MemorySegment) -> ...)`) can **statically reject returning or storing an arena-scoped `MemorySegment` outside its confined block** — preventing use-after-close at compile time, something Java's type system cannot express.
  * **Unnamed discard patterns in Babble:** Aligns with Babble's future destructuring (`BIP005-D3`) and lambda parameters (pairing `_` with `BIP007-D8` `discard(expr)`).

---

### 3.2 JDK 23 *(GA: September 2024 · Non-LTS · EOL)*

*Integrated JEPs: 455 (P), 466 (2P), 467, 469 (8I), 471, 473 (2P), 474, 476 (P), 477 (3P), 480 (3P), 481 (3P), 482 (2P).*

* **Change (Mandatory):**
  * **`[JEP471]` Deprecate Memory-Access Methods in `sun.misc.Unsafe` for Removal:** Babble itself has **zero** `Unsafe` usage (clean). Audit transitive test/build dependencies (JUnit, JaCoCo, ANTLR4) on JDK 23+.
* **Adopt (Optional):**
  * **`[JEP474]` ZGC: Generational Mode by Default:** Free latency improvement when running interactive Babble services under `-XX:+UseZGC`.
  * **`[JEP467]` Markdown Documentation Comments (`///`):** Minor doc ergonomics for Babble's own Java sources (if the source baseline ever advances past 21).
* **Exceed (Babble Beyond Java):** None — JDK 23 is almost exclusively a preview-iteration release (`Class-File API` 2P, `Primitive Patterns` 1P, `Stream Gatherers` 2P, `Scoped Values` 3P, `Structured Concurrency` 3P, `Flexible Constructor Bodies` 2P). **Important implication for the release scheme (§6):** `Babble 23` requires *zero* custom overlay code over `Babble 22`.

---

### 3.3 JDK 24 *(GA: March 2025 · Non-LTS · EOL)*

*Integrated JEPs: 404 (E), 450 (E), 472, 475, 478 (P), 479, 483, 484, 485, 486, 487 (4P), 488 (2P), 489 (9I), 490, 491, 492 (3P), 493, 494 (2P), 495 (4P), 496, 497, 498, 499 (4P), 501.*

JDK 24 is the **first major architectural inflection point** after JDK 21.

* **Change (Mandatory):**
  * **`[JEP486]` Permanently Disable the Security Manager:** Babble uses zero `SecurityManager` APIs; audit `HostEffects`/`JavaNullability` and any Java-interop tests to ensure nothing references `System.setSecurityManager` or `AccessController`.
  * **`[JEP472]` Prepare to Restrict the Use of JNI** & **`[JEP498]` Warn upon Use of Memory-Access Methods in `sun.misc.Unsafe`:** Babble itself is 100% clean (zero JNI, zero `Unsafe`); verify CI logs stay warning-free across transitive dependencies.
* **Adopt (Optional):**
  * **`[JEP484]` Class-File API (`java.lang.classfile`) *(Final)* — HIGHEST-VALUE `ADOPT` IN JDK 22–25:**
    * Replaces `org.ow2.asm:asm` and `org.ow2.asm:asm-util` across all three ASM touchpoints in Babble:
      1. **Bytecode emission (`CodeGenerator` / `BIP014-P1` `HirEmitter`):** `ClassFile.of(ClassHierarchyResolverOption.of(...))` + `ClassBuilder`/`CodeBuilder` generate bytecode natively with automatic stack-map computation, short/long branch offset replay, and `java.lang.constant` (`ClassDesc`, `MethodTypeDesc`, `DirectMethodHandleDesc`) descriptors instead of raw string descriptors.
      2. **Bytecode verification (`Debugging.java`):** `ClassFile.of(...).verify(bytes)` replaces `CheckClassAdapter.verify(...)`, eliminating the optional `asm-util` dependency!
      3. **Java bytecode effect scanning (`BytecodeEffectAnalyzer.java`):** Pattern matching over `CodeElement` (`case FieldInstruction f -> ...`, `case InvokeInstruction i -> ...`) replaces the verbose ASM `ClassVisitor`/`MethodVisitor` subclass.
    * **Eliminates ASM version lag:** As JEP 484's own motivation notes, third-party ASM always lags behind new JVM classfile versions (such as JDK 28's `72.65535` + `ACC_STRICT_INIT` + `EARLY_LARVAL` stack-map frames). `java.lang.classfile` ships inside `java.base` in exact lockstep with the JVM specification.
    * **Synergy with `BIP015`:** If `BIP015` ever replaces ANTLR4 with a hand-written parser *and* `BIP018` replaces ASM with `JEP 484` on Babble 24+/25+, **Babble 25+ becomes a 100% zero-third-party-dependency pure-JDK compiler and runtime**.
  * **`[JEP491]` Synchronize Virtual Threads without Pinning *(Final)*:** Eliminates carrier-thread pinning on `synchronized` monitors — immediately making Babble's existing `synchronized` blocks (`Bootstrap.relink`, `Overloads.Cache.put`, `Launcher.loaded`, `BabbleScriptEngine.moduleLock`) virtual-thread-friendly with zero lock rewrites!
  * **`[JEP483]` Ahead-of-Time Class Loading & Linking *(Final, Project Leyden)*:** First production Leyden release; `babblec` (`BIP006`) jars have deterministic class names and zero ANTLR/ASM/reflection startup overhead, making them ideal for AOT class-loading caches.
  * **`[JEP485]` Stream Gatherers *(Final)*:** Add `java.util.stream.Gatherer` and `Gatherers` to `JavaNullability` (parametric nullness) and `HostEffects`.
  * **`[JEP493]` Linking Run-Time Images without JMODs *(Final)*:** Pairs cleanly with `babblec` (`BIP006` §5) + Babble's `java.base`-only runtime to produce self-contained `jlink` distributions of compiled Babble applications from a standard JRE installation.
* **Exceed (Babble Beyond Java):**
  * **Statically verified `pure` stateful Gatherers:** Java's `Gatherer<T, A, R>` allows arbitrary side effects inside `initializer`, `integrator`, and `finisher`, relying on programmer discipline. Babble's `BIP016`/`BIP017` `fn calling f` + `unique` local accumulator ownership lets Babble expose custom stream/iterator gatherers whose local mutable state is **statically verified not to escape**, preserving `pure` / `@HERMETIC` contracts across pipeline stages (directly feeding `BIP014` `O12` pipeline rewrites).

---

### 3.4 JDK 25 *(GA: September 2025 · **LTS** · Supported through 2030+)*

*Integrated JEPs: 470 (P), 502 (P), 503, 505 (5P), 506, 507 (3P), 508 (10I), 509 (E), 510, 511, 512, 513, 514, 515, 518, 519, 520, 521.*

**JDK 25 is the first Long-Term Support release since JDK 21.** It rolls up every finalized feature of JDK 22–24 (`FFM`, `Unnamed Patterns`, `Class-File API`, `Stream Gatherers`, `Virtual Thread non-pinning`, `Leyden AOT Class Loading`) and adds several high-impact capabilities of its own.

* **Change (Mandatory):**
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
* **Exceed (Babble Beyond Java):**
  * **Effect-tracked, `frozen`-only Scoped Bindings:** Java's `ScopedValue<T>` guarantees the *binding* cannot be rebound mutably, but Java cannot prevent binding a *mutable* `ArrayList` or `AtomicReference` into a `ScopedValue`, silently smuggling shared mutable state across threads! Babble can expose a lexical `scoped` / `with` context facility backed by `ScopedValue` that:
    1. Statically requires bound values to be **`frozen T`** (or core scalar / deep `record`), guaranteeing **true deep immutability** across all inheriting tasks; and
    2. Integrates with `BIP016` effects (reading a dynamic scoped context is tracked distinctly from `@HERMETIC`, or explicitly declared in the function's effect signature), so pure functions cannot silently depend on ambient dynamic state.

---

### 3.5 JDK 26 *(GA: March 2026 · Non-LTS · EOL)*

*Integrated JEPs: 500, 504, 516, 517, 522, 524 (2P), 525 (6P), 526 (2P), 529 (11I), 530 (4P).*

* **Change (Mandatory):**
  * **`[JEP500]` Prepare to Make Final Mean Final *(Final)*:** Issues runtime warnings (default `--illegal-final-field-mutation=warn`) when deep reflection mutates `final` fields, preparing for `deny` by default in a future JDK.
    * *Audit:* Babble's own code performs **zero** reflective mutation of `final` fields. Babble's JDK 26+ CI job should run with **`--illegal-final-field-mutation=deny`** to guarantee neither Babble nor any transitive library (`antlr4-runtime`, JUnit, JaCoCo) silently introduces a violation.
    * *User-facing interop:* Document that Babble scripts using Java reflection (`java.lang.reflect.Field.set`) on `final` fields will trigger JDK 26+ warnings/errors unless the user passes `--enable-final-field-mutation=ALL-UNNAMED`.
  * **`[JEP504]` Remove the Applet API:** Zero impact on Babble (already zero references).
* **Adopt (Optional):**
  * **`[JEP516]` Ahead-of-Time Object Caching with Any GC *(Final, Leyden)*:** Removes the G1-only constraint on Leyden AOT caches, allowing `babblec` apps using ZGC or Generational Shenandoah to benefit from AOT caching.
  * **`[JEP517]` HTTP/3 for the HTTP Client API:** Available immediately to Babble programs via Java interop (`java.net.http`).
  * **`[JEP522]` G1 GC Synchronization Reduction:** Free throughput gain for allocation-heavy Babble workloads.
* **Exceed (Babble Beyond Java):**
  * **Systematic `ACC_FINAL` emission for HotSpot trusted-final constant folding:** Once the JVM trusts `final` fields (`JEP 500`, culminating in `JEP 539`), HotSpot C2 can aggressively constant-fold reads of `final` fields. Idiomatic Java classes frequently leave fields non-`final` (for builders, frameworks, or convenience), whereas Babble's static analysis (`BlockScopes`, `TypeChecker`, `BIP002` `record`, `BIP007`) already knows **every** `record` member and **every** non-reassigned `class` header `def` is permanently immutable. Emitting `ACC_FINAL` on all such generated fields lets Babble harvest trusted-final optimizations automatically across user code.

---

### 3.6 JDK 27 *(GA: September 2026 · Non-LTS · Current GA as of Nov 2026)*

*Integrated JEPs: 523, 527, 531 (3P), 532 (5P), 533 (7P), 534, 536, 537 (12I), 538 (3P).*

* **Change (Mandatory):** None for Babble.
* **Adopt (Optional):**
  * **`[JEP534]` Compact Object Headers by Default *(Final, Lilliput)*:** Every Babble object gets 8-byte object headers out of the box with zero JVM flags.
  * **`[JEP523]` Make G1 the Default Garbage Collector in All Environments:** Consistent GC defaults even on single-core containers running `babblec` tools.
  * *Preview tracking:* `JEP 531` (Lazy Constants 3P), `JEP 532` (Primitive Types in Patterns 5P), `JEP 533` (Structured Concurrency 7P), `JEP 537` (Vector API 12I) all continue maturing toward finalization.
* **Exceed (Babble Beyond Java):** None specific to JDK 27 beyond inheriting JDK 24–26. Like JDK 23, `Babble 27` requires **zero** custom source overlay over `Babble 25`/`26`.

---

### 3.7 JDK 28 *(Targeted / Early Access as of Nov 2026 · GA: March 2027 · Non-LTS)*

*Targeted/Integrated JEPs (as of Nov 2026): 401 (P), 535, 539 (P), 540 (I), 541, 542, 543, 544, 545, 546; Proposed: 547 (4P).*

JDK 28 is the **second major architectural inflection point** — arguably the most consequential JDK release for Babble since JDK 21.

* **Change (Mandatory / Preview-Aware Interop):**
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
* **Exceed (Babble Beyond Java) — THREE LANDMARK OPPORTUNITIES:**
  1. **Zero-source-change Valhalla `value class` lowering for Babble `record`s (`[JEP401]` + `[JEP539]`):**
     * Unlike Java — where millions of existing `record` classes default to identity classes for backward compatibility and must explicitly opt into `value record` — **Babble's `record` (`BIP002` + `RM006`) is *already* defined as an immutable, identity-free value type** (`setMember` throws `readOnly`, equality/hashing are purely structural, and `BIP002` already forbade identity assumptions).
     * *Required runtime refactoring (clean and localized):* Today, compiled Babble `record`s extend concrete [`BabbleInstance`](../../src/main/java/org/babblelang/runtime/BabbleInstance.java) (which holds a mutable `private Object face` field and an unused `Object[] values` array). Under `JEP 401`, a value class can only extend `Object` or an `abstract value class` with strict final fields. By splitting a tiny `abstract value class BabbleValue implements HasMembers` (holding only `final BabbleClass babbleClass` and `final ScriptRuntime runtime`, or even storing `babbleClass` in a static field on the generated concrete `record` class so instances carry **zero** metadata pointers — only their payload fields!), every Babble `record` (`Vec3(double x, double y, double z)`, `Ray`, `Color` in `examples/raytracer-argon`) compiles directly to a **real JVM `value class`** (omitting `ACC_IDENTITY`, marking all fields `ACC_FINAL | ACC_STRICT_INIT`).
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

| Upcoming Capability | OpenJDK Project / JEP | Classification | Why Babble Is Uniquely Positioned to **Exceed** Java |
|---|---|---|---|
| **Value Objects & Strict Field Init *(Final)*** | Valhalla (`[JEP401]`, `[JEP539]`) | **Adopt / Exceed** | Graduates Babble's preview `record` → `value class` & `ACC_STRICT_INIT` lowering into the **JDK 29 LTS** production track without `--enable-preview`! |
| **Null-Restricted Value Class Types & Nullable Types** | Valhalla (`[JEP8316779]`, `[JEP8303099]`) | **Exceed** | **Babble already has non-null-by-default `T` vs. nullable `T?` (`BIP008`/`RM005`)!** Once the JVM exposes null-restricted storage (`!`), Babble compiles `Ray(origin: Vec3, dir: Vec3)` and `JavaArray of Vec3` directly to **null-free, maximally flattened heap storage** with zero null-flag bit overhead and **zero syntax migration** in existing Babble programs. |
| **Lazy Constants *(Final)*** | Core Libs (`[JEP547]`) | **Adopt / Exceed** | Enables `lazy def` in Babble backed by `LazyConstant` with `BIP016`-verified `@HERMETIC @DETERMINISTIC` initializers (guaranteed race-free and constant-folded by C2). |
| **Primitive Types in Patterns *(Final)*** | Amber (`[JEP532]`) | **Adopt** | Simplifies Babble's own Java implementation (`TypeChecker`, `Values`, `Operators`, `HirEmitter`) when switching over primitive/boxed numeric kinds. |
| **Enhanced Primitive Boxing / Universal Generics** | Valhalla | **Exceed** | Eliminates boxing in generic Babble types (`list of int`, `Box of double`) at the JVM level rather than requiring manual `$spec` entry points alone. |
| **Vector API *(Post-Valhalla Graduation)*** | Panama (`[JEP537]`, 12th Incubator) | **Adopt / Exceed** | Once Valhalla unblocks Vector API finalization, `BIP014` HIR/MIR + `BIP016` `pure` loops over primitive arrays (`JavaArray of double`) can lower directly to portable SIMD (`DoubleVector` / `FloatVector`) — or expose a safe `std/simd` API. |
| **Code Reflection** | Babylon (`[JEP8361105]`) | **Exceed** | Babble's `BIP014` typed HIR + `BIP016` `pure` effect proofs align naturally with Babylon's `CodeModel`, opening a future path to offload pure numeric Babble kernels (e.g., ray-tracer shaders) to GPUs via HAT. |

---

## 4. Consolidated Feature Matrix (JDK 21 → JDK 29+)

| JDK | Date / LTS | Key Feature / JEP | Class | Concrete Babble Action |
|---|---|---|---|---|
| **21** | Sep 2023 · **LTS** | Sealed classes, records, `switch` patterns, Virtual Threads | Baseline | Current Babble floor (`pom.xml` `--release 21`, `V21`, ASM 9.11) |
| **22** | Mar 2024 | `JEP 454` FFM API | **Adopt / Exceed** | Interop profiles; optional lifetime-checked `unique`/`@BORROWED` `Arena` wrappers |
| **22** | Mar 2024 | `JEP 456` Unnamed Variables `_` | **Adopt** | Cleanup in Babble Java sources (when on 22+); mirrors `_` in `BIP005-D3` |
| **23** | Sep 2024 | `JEP 471` Deprecate `Unsafe` memory access; `JEP 474` GenZGC | **Change (Audit)** | Verified 0 `Unsafe` calls in Babble; no bespoke `Babble 23` overlay needed |
| **24** | Mar 2025 | `JEP 484` **Class-File API** (`java.lang.classfile`) | **Adopt** | **Drop `org.ow2.asm:asm` + `asm-util`** across emitter, verifier & `BytecodeEffectAnalyzer` |
| **24** | Mar 2025 | `JEP 485` Stream Gatherers | **Adopt / Exceed** | Interop profiles; `unique`-verified `pure` stateful gatherers |
| **24** | Mar 2025 | `JEP 491` Unpinned Virtual Threads | **Adopt** | Existing `synchronized` caches (`Bootstrap`, `Overloads`, `Launcher`) become VT-safe |
| **24** | Mar 2025 | `JEP 483` Leyden AOT Class Loading; `JEP 493` JMOD-less `jlink` | **Adopt** | Faster `babblec` startup & tiny `java.base`-only `jlink` images |
| **24** | Mar 2025 | `JEP 486` Disable SecurityManager; `JEP 472`/`498` JNI/`Unsafe` | **Change (Audit)** | Verified 0 uses in Babble; keep CI warning-free |
| **25** | Sep 2025 · **LTS** | `JEP 506` **Scoped Values** | **Adopt / Exceed** | Ambient `ScriptRuntime`/interpreter context; `frozen`-only effect-tracked `scoped` bindings |
| **25** | Sep 2025 · **LTS** | `JEP 514`/`515` Leyden AOT Ergonomics & Method Profiling | **Adopt** | 1-step `babblec` AOT cache training (`-XX:AOTCacheOutput`) |
| **25** | Sep 2025 · **LTS** | `JEP 519` Compact Object Headers; `JEP 513` Flexible Ctors | **Adopt** | Smaller `BabbleInstance`/`Cell` footprint; cleaner Java ctor validation |
| **25** | Sep 2025 · **LTS** | `JEP 512` `java.lang.IO` in `java.base` | **Change** | Register `java.lang.IO.*` as `io` in `HostEffects` & nullable `readln` in `JavaNullability` |
| **26** | Mar 2026 | `JEP 500` **Prepare to Make Final Mean Final** | **Change / Exceed** | Gate CI with `--illegal-final-field-mutation=deny`; emit `ACC_FINAL` on all immutable Babble fields |
| **26** | Mar 2026 | `JEP 504` Remove Applet; `JEP 516` AOT Any GC; `JEP 517` HTTP/3 | **Change / Adopt** | Verified 0 Applet refs; Leyden AOT works with ZGC/Shenandoah |
| **27** | Sep 2026 | `JEP 534` Compact Object Headers Default; `JEP 523` G1 Default | **Adopt** | Free memory/GC win; no bespoke `Babble 27` overlay needed |
| **28** | Mar 2027 *(EA)* | `JEP 543` **Structured Concurrency** *(Final)* | **Adopt / Exceed** | **Compile-time race-free structured parallelism** (`BIP007` §10 + `frozen`/`unique`) |
| **28** | Mar 2027 *(EA)* | `JEP 544` **Leyden AOT Code Compilation** *(Final)* | **Adopt** | Cached native C1/C2 code for `babblec` jars (65–80% faster startup, dynamic-friendly) |
| **28** | Mar 2027 *(EA)* | `JEP 401` **Value Objects** *(Preview)* & `JEP 539` **Strict Field Init** *(Preview)* | **Change / Exceed** | Audit `same()`/`ACC_IDENTITY`; lower Babble `record` → JVM `value class` & emit `ACC_STRICT_INIT` on class header fields *(preview-gated)* |
| **28** | Mar 2027 *(EA)* | `JEP 547` Lazy Constants *(4P)*; `JEP 540` Simple JSON *(I)* | **Adopt / Exceed** | Track for `Module`/`JavaClass` lazy caching, `lazy def`, and `std/json` |
| **29+** | Sep 2027+ · **LTS** | Valhalla Final + Null-Restricted Storage (`8316779`), Vector API, Babylon | **Exceed** | Null-free flattened storage for Babble's existing `T` vs `T?`; SIMD & GPU lowering |

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

To capture the benefits of **all three worlds without their downsides**, `BIP018` proposes three strict invariants:

1. **Invariant 1 — One Core Language Across All Editions:** Every valid core Babble program (everything in `src/test/babble/`, `examples/`, and `BIP001`–`BIP017`) MUST compile and produce **identical observable results** on `Babble 21`, `Babble 25`, and `Babble 28` (verified differentially by `BIP009`'s `.batest` suite).
2. **Invariant 2 — Progressive Lowering (Invisible Upgrades):** Newer Babble editions transparently improve *how* core Babble features compile and run — e.g., `Babble 25` compiles via `java.lang.classfile` without ASM and runs with Compact Object Headers / Leyden profiling; `Babble 28-preview` (and future `Babble 29`) lowers `record` to a JVM `value class` and emits `ACC_STRICT_INIT` on immutable header fields — **without requiring a single syntax change in user code**.
3. **Invariant 3 — Strictly Additive, Capability-Gated Libraries:** Features that genuinely require a newer JDK API (e.g., `std/foreign` FFM wrappers on `22+`, `std/scoped` on `25+`, `std/concurrent` `StructuredTaskScope` combinators on `28+`) live in **explicitly version-gated capability modules** (or optional standard-library modules) whose minimum target is checked statically at compile time (`Error : std/concurrent requires target JDK 28 or higher (current target : 21)`).

---

## 6. Proposed Release Scheme: `Babble 21`, `Babble 22`, …, `Babble 25`, …, `Babble 28`

### 6.1 Edition Naming & Version Mapping

Under this scheme, Babble's **major edition number tracks the target JDK feature release**:

* **`Babble 21`** — Targets **JDK 21** (runs on JDK 21+). Uses the JDK 21 runtime + ASM bytecode backend.
* **`Babble 22`** — Targets **JDK 22** (runs on JDK 22+). Inherits `Babble 21` + optional FFM interop layer.
* **`Babble 23`** — Targets **JDK 23** (runs on JDK 23+). Pure floor inheritance (zero custom code over 22).
* **`Babble 24`** — Targets **JDK 24** (runs on JDK 24+). Switches bytecode backend from ASM to `java.lang.classfile` (`JEP 484`).
* **`Babble 25`** — Targets **JDK 25 (LTS)** (runs on JDK 25+). Zero ASM + `ScopedValue` runtime + Leyden AOT ergonomics + `java.lang.IO` host profile.
* **`Babble 26` / `Babble 27`** — Targets **JDK 26 / 27**. Pure floor inheritance over `Babble 25` + `JEP 500` strict-final CI verification + default Compact Object Headers.
* **`Babble 28`** — Targets **JDK 28** (runs on JDK 28+). Inherits `Babble 25` + `JEP 543` `StructuredTaskScope` runtime + `JEP 544` Leyden AOT Code Compilation support; plus an opt-in **`Babble 28-preview`** backend unlocking `JEP 401` Valhalla `value class` `record` lowering and `JEP 539` `ACC_STRICT_INIT`.

#### Sparse Floor Inheritance (Avoiding Busywork)

Crucially, **naming the scheme `Babble 21`, `Babble 22`, …, `Babble N` does NOT mean writing or maintaining $N - 20$ separate codebases.** Just like `JEP 238` Multi-Release JARs, edition resolution uses **floor inheritance**:

$$\text{Babble}(N) = \text{Core}_{21} \;\oplus\; \bigoplus_{\substack{k \le N \\ k \in \text{Overlays}}} \text{Overlay}_k$$

Where $\text{Overlays}$ is **sparse** — currently only `{21, 24, 25, 28}` (plus optional `22` if `std/foreign` is added, and isolated `28-preview`). An edition like `Babble 23` or `Babble 27` adds **zero files**; it is simply the floor stack (`21` or `21+24+25`) compiled/tested against JDK 23 or JDK 27!

### 6.2 Three-Tier Support & Publishing Policy

Rather than publishing and endlessly testing EOL non-LTS editions (like JDK 22, 23, 24, 26), the release pipeline defines **three active support tiers**:

| Tier | Current Editions (Nov 2026) | CI Coverage | Published Artifacts | Lifecycle Rule |
|---|---|---|---|---|
| **Tier 1 — Baseline LTS** | **`Babble 21`** | Every PR: full dual-mode (`interpret` + `compile`) + `-Pbatest` | `babble-21` + `babble-runtime-21` | Maintained as the universal floor until a deliberate deprecation window (e.g., 12 months after JDK 29 LTS ships). |
| **Tier 2 — Modern LTS (Recommended)** | **`Babble 25`** | Every PR: full dual-mode (`interpret` + `compile`, zero ASM) + `-Pbatest` | `babble-25` + `babble-runtime-25` (or MRJAR) | Primary recommended release for new users; advances on the 2-year LTS cadence (`25` → `29` → `33`). |
| **Tier 3 — Frontier / EA Track** | **`Babble 27`** *(current GA)* & **`Babble 28`** *(EA + preview)* | Scheduled nightly + opt-in PR label / workflow dispatch (JDK 27 GA & JDK 28 EA + `--enable-preview`) | Snapshot / milestone artifacts (`babble-28`, `babble-28-preview`) | Continuously validates Babble against upcoming JEPs (`JEP 500`, `543`, `544`, `401`, `539`) so LTS promotions are smooth and surprise-free. |

### 6.3 Artifact & Version Numbering Options (`BIP018-Q2`)

Two concrete ways to express `Babble N` in Maven coordinates and CLI releases:

1. **JDK-Major Versioning (`<jdk>.<babble-minor>.<patch>`):**
   * Example: `org.babblelang:babble:21.1.0`, `org.babblelang:babble:25.1.0`, `org.babblelang:babble:28.1.0` (where `.1.0` is the synchronized Babble language/feature version built from the exact same Git tag `v1.0`!).
   * *Trade-off:* Clean single artifactId (`babble`), immediately legible (`babble-25.1.0.jar`), matches the user's `Babble 21` / `Babble 25` intuition. Slightly unusual for Maven version-range resolvers (`25.1.0 > 21.2.0`), so dependency tools treat `25.x` as "newer" than `21.x`.
2. **Edition-Qualified Artifact IDs (`babble-jdk21`, `babble-jdk25`, `babble-jdk28`) with Shared SemVer (`1.2.0`):**
   * Example: `org.babblelang:babble-jdk21:1.2.0`, `org.babblelang:babble-jdk25:1.2.0`, `org.babblelang:babble-jdk28:1.2.0` (plus an optional universal Multi-Release `org.babblelang:babble:1.2.0` for stable non-preview JDKs 21–28).
   * *Trade-off:* Standard Maven/Dependabot SemVer behavior, crystal-clear dependency graph (no accidental upgrade from JDK 21 to JDK 25 via version ranges), and preserves distinct transitive dependency POMs (`babble-jdk21` depends on `asm`, while `babble-jdk25` has **zero** `asm` dependency!).

---

## 7. Branchless Monorepo Architecture

Maintaining separate Git branches (`jdk21`, `jdk25`, `jdk28`) would be disastrous for Babble: every parser edit, type-checker fix, effect rule, optimizer pass (`BIP014`), or `.batest` case would need cherry-picking across branches, causing chronic merge conflicts and semantic drift.

**Hard requirement:** All Babble editions MUST live on **`master` in a single branchless monorepo**, sharing **100% of the language front end** (`parser`, `analysis`, `format`, `interpreter`, `ir`, `opt`, and all `.ba`/`.batest` tests).

### 7.1 Architectural Comparison: Four Monorepo Patterns

| Pattern | How It Works | Pros | Cons / Blockers |
|---|---|---|---|
| **Option A: Single-Module Multi-Release JAR (`JEP 238`)** | Keep one `pom.xml`. Compile `src/main/java` with `--release 21`, and `src/main/java24`, `src/main/java25`, `src/main/java28` into `META-INF/versions/N/` via multiple `maven-compiler-plugin` executions (`[MavenMRJAR]`). | Preserves `BIP006-D1` single Maven module; ships one universal JAR that auto-adapts on JDK 21/25/28. | **(1) Dependency asymmetry:** Cannot omit `org.ow2.asm:asm` from the POM for JDK 25 users. **(2) Preview crash:** Putting JDK 28 `--enable-preview` classes (`72.65535`) in `META-INF/versions/28/` crashes `java -jar babble.jar` on JDK 28 *without* `--enable-preview` (`UnsupportedClassVersionError`)! **(3) Build JDK:** Requires JDK 28 locally for every build. **(4) IDE friction:** IntelliJ/VS Code handle multi-release source roots in one Maven module poorly. |
| **Option B: Multi-Module Maven Monorepo with Capability SPIs** | Split into `babble-runtime`, `babble-core` (JDK 21), sparse overlay modules (`babble-backend-asm`, `babble-backend-classfile`, `babble-runtime-jdk25`, `babble-runtime-jdk28`, `babble-backend-valhalla`), and thin distribution assemblies (`babble-dist-21`, `babble-dist-25`, `babble-dist-28`). | **Zero ASM** in `babble-dist-25`/`28`; **clean `--enable-preview` isolation**; native IDE & Maven Toolchains support; developers with only JDK 21 build `Babble 21` cleanly via default profile; clean `RuntimeBoundaryTestCase` per runtime module. | Introduces a multi-module parent/reactor POM (revising `BIP006-D1`'s single-module choice); users pick an editioned JAR unless Option C also assembles an MRJAR. |
| **Option C: Hybrid — Multi-Module Monorepo + Optional MRJAR for Stable Tracks + Profile-Gated Preview *(Recommended)*** | Use Option B's clean multi-module structure for compilation, dependencies, IDEs, and preview isolation, **and** optionally package a combined Multi-Release `babble.jar` / `babble-runtime.jar` covering *stable non-preview* tracks (`21` + `25` + `28-ga`), alongside edition-specific zero-ASM (`babble-25`, `babble-28`) and preview (`babble-28-preview`) artifacts! | Best of both worlds: clean per-module `--release` & IDE support, zero-ASM `Babble 25`/`28` distributions, safe preview isolation, optional single universal JAR for casual users, and `babblec -target 21\|25\|28` cross-compilation. | Slightly more packaging POM wiring than pure Option B (though isolated in `babble-dist-*` modules). |
| **Option D: Runtime Reflection / `MethodHandle` Probing** | Keep one JDK 21 module; reflectively probe `Class.forName("java.lang.ScopedValue")` or `Objects.hasIdentity` at runtime. | Zero build changes for tiny 1-method probes. | **Completely unworkable** for large compile-time APIs (`java.lang.classfile` has ~100+ sealed types/builders), `StructuredTaskScope`, or Valhalla `abstract value class` hierarchies (`BabbleValue`). Use only for trivial 1-line runtime probes, never as the primary architecture. |

**Recommendation:** **Option C (Hybrid Multi-Module Monorepo)** — or **Option B** first, adding the MRJAR assembly only if a single universal JAR is desired.

### 7.2 Concrete Monorepo Directory Layout

```text
babble/                                      (parent/aggregator pom.xml)
├── pom.xml                                  (dependencyManagement, profiles: default=jdk21, -Pjdk25, -Pjdk28, -Pjdk28-preview)
├── babble-runtime/                          (--release 21, java.base ONLY — zero external deps)
│   └── src/main/java/
│       ├── org/babblelang/runtime/          (existing runtime + PlatformRuntime SPI)
│       ├── org/babblelang/runtime/jdk/      (PlatformRuntime interface + Jdk21PlatformRuntime fallback)
│       └── org/babblelang/engine/impl/natives/
├── babble-runtime-jdk25/                    (--release 25, java.base ONLY — activated by -Pjdk25+)
│   └── src/main/java/
│       └── org/babblelang/runtime/jdk25/    (Jdk25PlatformRuntime: ScopedValue context propagation, etc.)
├── babble-runtime-jdk28/                    (--release 28, java.base ONLY — activated by -Pjdk28+)
│   └── src/main/java/
│       └── org/babblelang/runtime/jdk28/    (Jdk28PlatformRuntime: StructuredTaskScope parallel runtime)
├── babble-runtime-valhalla/                 (--release 28 --enable-preview, java.base ONLY — -Pjdk28-preview)
│   └── src/main/java/
│       └── org/babblelang/runtime/valhalla/ (abstract value class BabbleValue implements HasMembers)
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
├── babble-dist-21/                          (assembles babble-21.jar + babble-21-runtime.jar; includes asm)
├── babble-dist-25/                          (assembles babble-25.jar + babble-25-runtime.jar; ZERO asm!)
├── babble-dist-28/                          (assembles babble-28.jar + babble-28-runtime.jar; ZERO asm!)
├── babble-dist-28-preview/                  (assembles babble-28-preview.jar with Valhalla backend)
└── babble-tests/                            (shared src/test/babble/, src/test/batest/, examples/, JUnit suite)
```

Notice how cleanly every single Babble architectural constraint snaps into place:

1. **Zero front-end duplication:** `babble-core` stays at `--release 21` and holds the grammar/parser, all of `org.babblelang.analysis`, `Formatter`, `Interpreter`, `BabbleSession`/`Repl`, `BIP014` HIR/MIR and optimizer passes, and `Babblec`.
2. **Exact alignment with `BIP014-P0`/`P1`:** `BIP014-P1` already detaches bytecode emission from ANTLR4 by introducing an immutable `HirModule` (`org.babblelang.ir.hir`). Making `BytecodeBackend` consume `HirModule` (rather than raw ANTLR `ParserRuleContext`) means **`AsmBytecodeBackend` (JDK 21)** and **`ClassFileBytecodeBackend` (JDK 24+)** are clean, parallel backends over the exact same verified IR — instead of duplicating 6,000 lines of parse-tree traversal!
3. **Clean dependency pruning:** `org.ow2.asm:asm` and `asm-util` exist **only** in `babble-backend-asm`. `babble-dist-25` and `babble-dist-28` do not even have ASM on their classpath!
4. **Strict preview isolation:** `babble-runtime-valhalla` and `babble-backend-valhalla` are compiled only when `-Pjdk28-preview` is enabled, so preview classfiles (`72.65535`) can **never** leak into `Babble 21`, `Babble 25`, or non-preview `Babble 28`.
5. **Zero friction for JDK 21 contributors:** Running plain `mvn package` on a JDK 21 machine activates only the JDK 21 modules (`babble-runtime`, `babble-core`, `babble-backend-asm`, `babble-dist-21`, `babble-tests`) — identical developer experience to today!

### 7.3 Narrow SPI Boundaries

Keeping the versioned surface area tiny is critical so maintaining multiple editions stays effortless. Four narrow SPI interfaces (discovered via `java.util.ServiceLoader`, which `Launcher` already uses for `ModuleCompiler` in `BIP006`!) cover everything:

1. **`org.babblelang.compiler.TargetProfile` (enum / record in `babble-core`):**
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
   }
   ```
   *Note:* Capability queries always check **feature flags** (`supportsScopedValues()`), never scattered magic version numbers.
2. **`org.babblelang.compiler.BytecodeBackend` (in `babble-core`):**
   * `int minHostJdk()` / `boolean supportsTarget(TargetProfile target)`
   * `CompiledClasses emit(HirModule module, TargetProfile target)`
   * `List<String> verify(byte[] bytecode, ClassLoader loader)` (`CheckClassAdapter` in ASM backend; `ClassFile.of(...).verify(bytes)` in Class-File backend)
   * `String disassemble(byte[] bytecode)` (`TraceClassVisitor` vs `ClassModel` printer)
   * *Bonus:* `ClassFileBytecodeBackend` (on host JDK 24+) can emit classfile version `V21` (`ClassFile.JAVA_21_VERSION = 65`) just as easily as `V25` (`69`) or `V28` (`72`), enabling **zero-ASM cross-compilation down to JDK 21**!
3. **`org.babblelang.analysis.BytecodeInspector` (in `babble-core`):**
   * Abstracts the bytecode reader inside [`BytecodeEffectAnalyzer.java`](../../src/main/java/org/babblelang/analysis/BytecodeEffectAnalyzer.java) (`AsmBytecodeInspector` on JDK 21 vs. `ClassFileInspector` on JDK 24+).
4. **`org.babblelang.runtime.jdk.PlatformRuntime` (in `babble-runtime`, `java.base`-only):**
   * Selected once at startup by `Runtime.version().feature()` (or `ServiceLoader` / MRJAR class override).
   * Exposes minimal runtime hooks: ambient context binding (`ThreadLocal` fallback on 21 → `ScopedValue` on 25+), structured fork/join executor (`ForkJoinPool` / virtual threads fallback on 21 → `StructuredTaskScope` on 28+), and value-object identity helper (`a == b` vs `Objects.hasIdentity` on 28-preview).

### 7.4 `babblec` Cross-Targeting (`BIP006`) & `RuntimeBoundaryTestCase`

Two existing Babble mechanisms need explicit, clean handling under the multi-JDK layout:

1. **`babblec` target flag (`-target 21|25|28|28-preview`) and runtime bundling:**
   * Today, [`Babblec.runtimeClasses()`](../../src/main/java/org/babblelang/compiler/Babblec.java#L134-L166) copies `.class` files from `Launcher.class`'s own code source directly into `app.jar`.
   * Under `BIP018`, `babblec` defaults `-target` to the edition's target (e.g., `25` in `Babble 25`), and supports explicit cross-targeting (`java -jar babble-25.jar -compile main.ba -target 21 -o app.jar`):
     * Emits classfiles at `target.classfileMajor()` (e.g., `V21`) via `BytecodeBackend`.
     * Rejects at compile time any call to a capability-gated stdlib module requiring a JDK newer than `-target`.
     * Copies **only** the runtime classes for `target` (i.e., `babble-runtime` for `-target 21`; `babble-runtime` + `babble-runtime-jdk25` for `-target 25`; or preserves `META-INF/versions/N/org/babblelang/runtime/**` and sets `Multi-Release: true` in `app.jar`'s manifest when emitting a multi-release AOT jar).
     * Sets `Created-By: babblec (target JDK <N>)` in `MANIFEST.MF` while preserving `Babblec`'s deterministic sorted entries and fixed `ENTRY_TIME` timestamps.
2. **`RuntimeBoundaryTestCase` per runtime module:**
   * [`RuntimeBoundaryTestCase.java`](../../src/test/java/org/babblelang/tests/RuntimeBoundaryTestCase.java) already verifies that runtime `.class` files reference only `org/babblelang/runtime/`, `org/babblelang/engine/impl/natives/`, and `java.base` packages (`Object.class.getModule().getDescriptor().packages()`).
   * In the multi-module monorepo, the exact same test runs inside every `babble-runtime*` module (`babble-runtime` on JDK 21, `babble-runtime-jdk25` on JDK 25, `babble-runtime-jdk28` on JDK 28, `babble-runtime-valhalla` on JDK 28 `--enable-preview`), guaranteeing that **no runtime overlay ever leaks `antlr`, `asm`, `javax.script`, or non-`java.base` JDK modules**!

### 7.5 Maven Profiles, Toolchains & CI Verification Matrix

#### Local Developer Ergonomics (`pom.xml` Profiles)

Using JDK-version profile activation (`<activation><jdk>[25,)</jdk></activation>` plus explicit opt-in flags `-Pjdk25`, `-Pjdk28`, `-Pjdk28-preview`):

* **Developer on JDK 21:** `mvn package` automatically builds and tests `Babble 21` (100% green, zero extra setup).
* **Developer on JDK 25:** `mvn package` builds `Babble 21` (verified via `--release 21`) **and** `Babble 25` (via `--release 25`, zero ASM), running the shared test suite on both!
* **Developer on JDK 26/27/28 (like the maintainer's Homebrew JDK 26 workstation noted in `AGENTS.md`):** `mvn package` builds all editions $\le$ the local JDK, and `mvn -Pjdk28-preview test` exercises Valhalla preview lowering when running on JDK 28 EA.
* **Cross-JDK verification via `maven-toolchains-plugin` or containers:** When testing `Babble 21` bytecode on an *actual* JDK 21 JVM (not just `--release 21` API checking), CI runs a real matrix.

#### GitHub Actions CI Matrix (`.github/workflows/maven.yml`)

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
        distribution: "jdkfile" # or oracle/temurin EA
        maven_args: "-B -Pjdk25,jdk28,jdk28-preview test"
        jvm_args: "--enable-preview --illegal-final-field-mutation=deny"
```

Every matrix cell executes the **same** `.ba` scripts, `DifferentialTestCase`, `.batest` corpus (`987+` cases), `RuntimeBoundaryTestCase`, `BabblecTestCase`, and `ExamplesTestCase` — proving **cross-edition semantic parity** (`interpret` == `Babble 21 ASM` == `Babble 25 Class-File` == `Babble 28` == `Babble 28-preview Valhalla`) and verifying byte-identical `examples/raytracer-argon/scene.ppm` across editions!

---

## 8. Interaction with Existing & Draft BIPs

* **`BIP006` (`BIP006-D1` Single-Module Preference & `babblec`):** `BIP006-D1` originally kept Babble as a single Maven module (`babble-<version>-runtime.jar` via classifier) because there was only one JDK target and one bytecode backend. `BIP018` explicitly revisits `BIP006-D1` *only* when a second JDK backend/runtime overlay is introduced (`BIP018-P1`+), preserving `BIP006`'s core `java.base`-only runtime boundary and deterministic `babblec` AOT jars.
* **`BIP014` (Compiler Optimization Passes — `BIP014-P0` & `BIP014-P1`):** **Strong sequencing synergy!** Writing a second bytecode backend (`ClassFileBytecodeBackend` via `JEP 484`) against today's 6,269-line ANTLR-coupled `CodeGenerator.java` would duplicate massive parse-tree traversal logic. Waiting until **`BIP014-P1`** lands `HirModule` + `HirEmitter` lets `AsmBytecodeBackend` and `ClassFileBytecodeBackend` both lower the exact same clean, typed, ANTLR-free `HIR`/`MIR`!
* **`BIP015` (Down with the Ants):** Complementary dependency reduction. Today Babble has exactly **two** non-JDK runtime dependencies: `antlr4-runtime` (`BIP015`) and `asm` (`BIP018` `JEP 484`). Together, `BIP015` (if a non-ANTLR option is chosen) + `BIP018` (`Babble 25+`) reduce Babble's entire compiler, formatter, REPL, and runtime to **zero third-party dependencies**.
* **`BIP007` §10 & `BIP016`/`BIP017` (Verified Parallelism):** `Babble 25` (`ScopedValue`) + `Babble 28` (`StructuredTaskScope`) provide the exact JDK primitives needed to implement `threadsafe` parallel combinators cleanly without custom thread-pool/cancellation boilerplate.
* **`BIP002` (`record`) & `BIP011` (`T` vs. `T?`):** `Babble`'s existing `record` semantics and non-null-by-default types are **already** Valhalla-ready at the language level; `BIP018` only requires decoupling compiled `record`s from concrete `BabbleInstance` (replacing it with `abstract value class BabbleValue` or a field-only layout) to unlock `JEP 401`/`539` flattening and scalarization.

---

## 9. Phased Rollout Plan (`BIP018-P0`–`P4`)

Each phase is independently valuable, keeps `Babble 21` 100% green, and avoids premature refactoring:

* **`BIP018-P0` — Multi-JDK CI Hygiene & Interop Profiles *(Zero structural change, lands anytime)*:**
  * Add JDK 25 LTS and JDK 27 GA test legs to [`.github/workflows/maven.yml`](../../.github/workflows/maven.yml) running the existing `Babble 21` build on newer JVMs (with `--illegal-final-field-mutation=deny` on JDK 26+ per `JEP 500`).
  * Add `java.lang.IO` (`JEP 512`), `java.util.stream.Gatherer`/`Gatherers` (`JEP 485`), and `java.lang.foreign.*` (`JEP 454`) entries to `HostEffects` and `JavaNullability` (using name-based lookup so `--release 21` compilation stays untouched!).
  * Document Leyden AOT cache usage (`-XX:AOTCacheOutput=app.aot`) with `babblec` jars on JDK 25+.
* **`BIP018-P1` — Monorepo Module Split & SPI Extraction *(Recommended after `BIP014-P1` `HirModule`)*:**
  * Introduce parent POM + `babble-runtime`, `babble-core`, `babble-backend-asm`, `babble-dist-21`, `babble-tests` with `TargetProfile`, `BytecodeBackend`, `BytecodeInspector`, and `PlatformRuntime` SPIs — **zero behavioral change** on JDK 21.
  * Extend `RuntimeBoundaryTestCase` and `Babblec` (`-target`) to consume the runtime module cleanly.
* **`BIP018-P2` — `Babble 25` (Modern LTS · Zero ASM):**
  * Add `babble-backend-classfile` (`--release 24`, `JEP 484` `java.lang.classfile` emitter, verifier, and `BytecodeEffectAnalyzer` inspector — **zero `org.ow2.asm:asm`**) and `babble-runtime-jdk25` (`--release 25`, `JEP 506` `ScopedValue` hooks) + `babble-dist-25`.
  * Verify 100% differential parity across `interpret`, `Babble 21` (ASM), and `Babble 25` (`Class-File API`) on `.batest`, `Benchmarks`, and byte-identical `examples/raytracer-argon/scene.ppm`.
  * Emit `ACC_FINAL` systematically on all immutable `record` and non-reassigned `class` header fields (`JEP 500` synergy).
  * Optionally add `babblec --aot-cache` helper workflow targeting Leyden (`JEP 514`/`515`/`544`).
* **`BIP018-P3` — `Babble 28` (Structured Concurrency & Leyden AOT Code Compilation):**
  * Add `babble-runtime-jdk28` (`--release 28`, `JEP 543` `StructuredTaskScope` backing Babble's race-free parallel combinators from `BIP007` §10) and `babble-dist-28`.
  * Benchmark `babblec` + `JEP 544` Leyden AOT Code Compilation on CLI/ray-tracer startup and warmup.
* **`BIP018-P4` — `Babble 28-preview` → `Babble 29` LTS (Valhalla Value `record`s & `ACC_STRICT_INIT`):**
  * Decouple compiled `record`s from concrete `BabbleInstance` (via `abstract value class BabbleValue` in `babble-runtime-valhalla`).
  * Add profile-gated `babble-backend-valhalla` (`-Pjdk28-preview`) emitting `JEP 401` value classes for Babble `record`s and `JEP 539` `ACC_STRICT_INIT | ACC_FINAL` for immutable class header fields.
  * Benchmark `examples/raytracer-argon` (`Vec3`/`Ray`/`Color` allocation & throughput) against `Babble 21`/`25` to quantify Valhalla flattening/scalarization gains before promoting to non-preview when Valhalla finalizes (JDK 29 LTS+).

---

## 10. Open Questions (`BIP018-Q1`–`Q8`)

* **`BIP018-Q1` — Monorepo Structure (`Option A` vs. `Option B` vs. `Option C`):** Should Babble adopt a multi-module Maven monorepo with sparse JDK overlay modules (`Option B`), add an optional Multi-Release JAR assembly on top for stable non-preview tracks (`Option C`), or try to stay single-module with JEP 238 MRJAR (`Option A`) despite the ASM dependency and preview-classfile constraints?
* **`BIP018-Q2` — Artifact & Version Scheme:** Should editions be published as JDK-major versions (`org.babblelang:babble:21.1.0`, `25.1.0`, `28.1.0`) or edition-qualified artifact IDs with shared SemVer (`babble-jdk21:1.0.0`, `babble-jdk25:1.0.0`, `babble-jdk28:1.0.0`)?
* **`BIP018-Q3` — Active Edition Matrix:** Should Babble actively build and publish only **LTS + Current Frontier** (`21`, `25`, `27`/`28`), or also publish intermediate non-LTS editions (`22`, `23`, `24`, `26`) via floor inheritance?
* **`BIP018-Q4` — Sequencing Relative to `BIP014-P1` (`HirModule`):** Should `BIP018-P1`/`P2` (the `BytecodeBackend` SPI and `JEP 484` `Class-File API` backend) explicitly wait for `BIP014-P1` so `ClassFileBytecodeBackend` lowers `HirModule` instead of ANTLR4 parse trees? *(Note: `BIP018-P0` CI matrix + `HostEffects` updates can land immediately regardless.)*
* **`BIP018-Q5` — `Babble 21` Sunsetting Horizon:** Under what concrete policy should a baseline LTS edition (like `Babble 21`) eventually be retired — e.g., N months after two newer LTS releases exist (when JDK 29 LTS is widely adopted and JDK 25 becomes the new baseline floor)?
* **`BIP018-Q6` — Cross-Targeting in `babblec` (`-target N`):** Should `Babble 25+` support `babblec -target 21` (emitting `V21` bytecode via `java.lang.classfile` and bundling `babble-runtime` for JDK 21 JREs), or should each `Babble N` only target JDK $N$?
* **`BIP018-Q7` — Surface-Language "Exceed" Features (`scoped`, `lazy def`, `std/foreign`):** Which JDK-enabled "Exceed" features should stay purely **invisible lowering optimizations** (Valhalla `record`s, `ACC_STRICT_INIT`, Leyden AOT) vs. becoming **new Babble language/stdlib constructs** (`frozen` `scoped` bindings on 25+, `parallel` structured concurrency on 28+, lifetime-checked `std/foreign` on 22+)?
* **`BIP018-Q8` — `record` Superclass Refactoring (`BabbleInstance` vs. `BabbleValue`):** Should the decoupling of compiled `record`s from concrete `BabbleInstance` (eliminating the mutable `face` field and `Object[] values` slot on compiled `record`s) be done early across *all* editions (shrinking `record` heap size even on `Babble 21`/`25`!), or only inside the Valhalla overlay (`BIP018-P4`)?

---

## 11. References

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
