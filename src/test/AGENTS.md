# Testing guidelines (`src/test/`)

This file is the authoritative guide for authoring, organizing, and verifying tests across Babble's test suite (`BIP009`, `BIP029`, `BIP037`). For `.batest.md` file syntax, size budgets, IR golden directives, and the manual review checklist, see [`batest/AGENTS.md`](batest/AGENTS.md).

## Default to `.batest.md` (Spec-Driven Development)

**Write `.batest.md` specification cases under `src/test/batest/<topic>/` by default** for every new language feature, bug fix, type/effect rule, module/session interaction, Java interop behavior, runtime edge case, compiler lowering, or optimizer transformation.

A single `.batest.md` case automatically verifies:

1. **All 4 JVM execution modes** (`interpret`, `compile -O0`, `compile -O1`, and `compile -O2` under `-PO2`/`-Pbatest`) with `HirVerifier`, `MirVerifier`, and ASM `CheckClassAdapter`.
2. **WebAssembly 3.0 parity** (`WasmBatestTestCase`, `wasm-js -O1`) for every portable case, plus optional `wat` golden checks (`BIP025-I5`).
3. **Canonical compiler IR invariants** (`hir -O0`, `hir -O1`, `mir -O2` `before`/`final`/`after:<Pass>`) verifying inferred types, effects, algebraic properties, optimizations, and lowering (`BIP029`).
4. **Grammar & formatter hygiene** — pure `PredictionMode.SLL` parsing (`ParserGrammarTestCase`) and idempotent formatting (`FormatterTestCase`).
5. **6-scope JaCoCo coverage gating** (`>= 80%` line and `>= 80%` branch across interpreter, `-O0`, `-O1`, `-O2`, shared front end, and shared runtime via `mvn -q verify -Pbatest`).

## When to write a Java JUnit test instead

Add or extend JUnit tests under `src/test/java/` **only for invariants that `.batest.md` cannot observe directly**:

| Category | Why Java JUnit is required | Where it lives |
|---|---|---|
| **JVM bytecode, reflection & classloader internals** | ASM opcode inspection (`INVOKEINTERFACE`, `INSTANCEOF`, `CHECKCAST`, zero `invokedynamic`, SROA/FBIP/GVN bytecode checks), `Class.getSuperclass()` / `Proxy.isProxyClass`, package-private `CompositeClassLoader` / `ScriptClassWriter`, synthetic child-`ClassLoader` isolation, stack-trace line numbers, method/constant chunking | `org.babblelang.compiler.*`, `BabbleCompilerTestCase`, `OptimizerTestCase` |
| **Exact `line:column` & multi-diagnostic goldens** | `.batest.md` normalizes line coordinates and stops on the first fatal error; exact coordinate tables and multi-error recovery need Java goldens | `TypeCheckerTestCase`, `ResolverTestCase` |
| **Formatter exact layout & CLI** | Exact whitespace/newline layout goldens and `-format` CLI flags (`-w`, `-l`, `-check`) | `FormatterTestCase`, `BabblefmtTestCase` |
| **CLI, AOT (`babblec`), REPL terminal, filesystem modules & examples** | Spawning fresh JREs/jars, real paths/symlinks, JLine terminal completion/transcripts, `ModuleSource` call recording, compiling `examples/` | `MainTestCase`, `BabblecTestCase`, `ReplTestCase`, `TerminalInputTestCase`, `FileModulesTestCase`, `ModuleSourcesTestCase`, `ExamplesTestCase` |
| **Internal data structures, axioms, printers, Wasm writer & architecture boundaries** | Direct Java API unit tests (`EGraph`, `TypeAlgebra`, `EffectCore`, `ModalKinds`, `JavaTypeBridge`, `RangeRuntimeTestCase`, `HirCanonicalPrinter`, `MirCanonicalPrinter`, `BytecodeEffectAnalyzer`, `AxiomsTestCase`, `AxiomCheckerTestCase`, `WasmWriterTestCase`, `InterpreterNamespaceTest`, `ArchitectureBoundaryTestCase`, `RuntimeBoundaryTestCase`, `tests/batest/*`) | `org.babblelang.analysis.*`, `org.babblelang.tests.*`, `org.babblelang.tests.batest.*` |

When writing an ASM/reflection or Java-API test, **keep it strictly focused on the JVM/API assertion** — do not duplicate 3-mode behavioral parity loops or runtime error matrices that belong in `.batest.md`.

## Legacy suites & anti-patterns

- **Never recreate `src/test/babble/`** or add standalone `.ba` test scripts; end-to-end language tests live in `src/test/batest/` (`BIP037-D2`).
- **Do not add new behavioral/feature tests to legacy JUnit suites** (`DifferentialTestCase` inline snippets, legacy `Babble*TestCase` behavioral classes such as `BabbleExpressionsTestCase` / `BabbleFunctionsTestCase` / `BabbleObjectModelTestCase`, or old phase-named `Bip###P#*` / `Rm###P#*` classes). Put new behavioral and IR-verifiable tests in `src/test/batest/`.
- **Name files and classes after features or subsystems**, never after implementation phases (`Bip020P1*`, `Rm003P2*`), grab-bag buckets (`*_edges`), or numbered chunks (`*_1`, `*_2`). Never mention unimplemented `RM###` identifiers in test code, comments, filenames, or prose.

## Test suite layout

| Path | Contents |
|---|---|
| `src/test/batest/` | Primary specification corpus (`syntax/`, `primitives/`, `control/`, `functions/`, `objects/`, `types/`, `effects/`, `modules/`, `sessions/`, `java/`, `compiler/`, `caches/`, `runtime/`). See [`batest/AGENTS.md`](batest/AGENTS.md). |
| `src/test/java/org/babblelang/compiler/` | Package-private compiler internals & JVM bytecode/reflection invariants (`CompilerClassLoaderAndLcaTestCase`, `JvmClassAndInterfaceBytecodeTestCase`). |
| `src/test/java/org/babblelang/analysis/` | Direct unit tests of isolated front-end analysis cores (`TypeAlgebra`, `EffectCore`, `ModalKinds`, `JavaTypeBridge`, `AlgebraicAndTermination`, `Rm003P1AnalysisTestCase`). |
| `src/test/java/org/babblelang/tests/batest/` | `.batest.md` parser, runner, IR assertion checker, updater, Wasm parity runner (`WasmBatestTestCase`), coverage reporter (`BatestCoverageReporter`), and harness tests. |
| `src/test/java/org/babblelang/tests/` | Focused Java-API, CLI/AOT/REPL, formatter, parser-SLL, boundary, axiom, printer, and legacy suites, plus test fixtures (`JavaClassDemo`, `NodeWasmRunner`, `BabbleTestBase`). |
| `src/test/java/org/babblelang/repl/` | Terminal line-editing & completion tests (`TerminalInputTestCase`). |
| `src/test/java/org/babblelang/bench/` | Microbenchmarks (`Benchmarks`, not a JUnit test). |
| `src/test/resources/` | JUnit Jupiter concurrency config (`junit-platform.properties`) and test fixtures. |

## Concurrency, isolation & determinism

- **Parallel JUnit execution**: `src/test/resources/junit-platform.properties` runs test classes and methods concurrently inside each Surefire fork (`dynamic`, `factor = 1.0`). Any JUnit test that mutates process-global `System` properties or `System.out`/`System.err` **must** carry `@ResourceLock(Resources.GLOBAL)`.
- **Determinism**: never depend on default `ClassName@<identityHash>`, wall-clock timestamps, unordered `HashMap`/`HashSet` iteration order, host paths, or multi-threaded interpreter runs (the interpreter is single-threaded).
- **Cross-JDK parity**: CI runs JDK 21 (`release 21`). Avoid depending on JDK-version-specific reflective messages outside normalized patterns.
- **No concurrent Maven runs on one workspace**: never run two `mvn` commands simultaneously against the same `target/` directory.

## Fast inner loop vs full verification

Prefer **narrow tests first** while iterating; run the full multi-JVM Maven passes only at milestones or before finishing a task:

```bash
# Fast filtered .batest.md loop across interpret, -O0, -O1 (add -PO2 for -O2, add WasmBatestTestCase for Wasm)
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=types/arrays.batest.md
mvn -q test -PO2 -Dtest=BatestTestCase,WasmBatestTestCase -Dbatest.filter=compiler/jvm_interfaces.batest.md

# Bless stale exact-golden HIR/MIR blocks after intentional changes, then inspect git diff and re-run without flag
mvn -q test -PO2 -Dtest=BatestTestCase -Dbatest.filter=compiler/jvm_interfaces.batest.md -Dbatest.updateIr=true

# Single Java JUnit class or method
mvn -q test -Dtest=JvmClassAndInterfaceBytecodeTestCase
mvn -q test -Dtest=ParserGrammarTestCase,FormatterTestCase

# Full CI parity build & 4-mode test suite (run once after narrow checks pass)
mvn -B package -PO2

# Full 4-JVM 6-scope JaCoCo coverage gate (>= 80% line & branch per scope)
mvn -q verify -Pbatest

# Formatter cleanliness check on all remaining .ba files
java -jar target/babble-1.0-SNAPSHOT.jar -format -check examples src/main/java/org/babblelang/axioms
```
