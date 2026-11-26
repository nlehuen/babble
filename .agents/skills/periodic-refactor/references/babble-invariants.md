# Babble Refactoring Invariants & Verification Reference

Read this reference before designing decompositions in Phase 2 or executing edits in Phase 4.

## 1. Architecture Boundaries (`ArchitectureBoundaryTestCase`)

`src/test/java/org/babblelang/tests/ArchitectureBoundaryTestCase.java` enforces bytecode-level architectural boundaries on every test run:

- **No reverse dependencies into interpreter**: `analysis`, `ir`, `opt`, `compiler`, and `wasm` must never reference `org.babblelang.engine.impl.*` (outside `org.babblelang.runtime.*`).
- **ANTLR isolation**: Zero `org.antlr.*` or `org.babblelang.parser.*` references in:
  - `org.babblelang.ir.*` (except `HirBuilder`),
  - `org.babblelang.opt.*`,
  - `org.babblelang.compiler.*` (except `BabbleCompiler`),
  - `org.babblelang.wasm.*`,
  - any class listed in `ANTLR_FREE_ANALYSIS_CORES`.
  - *Implication for refactoring `HirBuilder`*: any extracted helper that touches ANTLR parse-tree nodes must stay out of ANTLR-free packages or be explicitly accounted for without weakening ANTLR isolation for the rest of `ir`.
- **`ScopeAnalysis` isolation**: Zero `ScopeAnalysis` references in `ir` (except `HirBuilder`), `opt`, `compiler` (except `BabbleCompiler`), or `wasm`.
- **`EffectChecker` reflection isolation**: `EffectChecker` must not reference `java.lang.reflect.*` directly (keep reflection in `JavaEffectResolver` / `BytecodeEffectAnalyzer`).
- **Wasm backend ASM isolation**: `org.babblelang.wasm.*` must never reference `org.objectweb.asm.*`.
- **Hard 2,000-LOC ratchet**: Every `*.java`, `*.g4`, `*.ba` file under `src/main` must be `<= 2,000` physical LOC, except files pinned in `GRANDFATHERED_CEILINGS`. When refactoring shrinks a grandfathered file:
  - If still `> 2,000` LOC: lower its recorded ceiling in `GRANDFATHERED_CEILINGS` to the new line count (slack `> 50` lines fails the test).
  - If `<= 2,000` LOC: delete its entry from `GRANDFATHERED_CEILINGS` (the test fails on stale exemptions).
- **Adjacent-layer sharing vs. forbidden leaks**: The rules above forbid *reverse* or *leaky* dependencies (parse trees past `HirBuilder`, ASM in `wasm`, compiler types in `runtime`, etc.), not intentional sharing across adjacent pipeline interfaces. When refactoring a layer (e.g., HIR or MIR), check the layers immediately above and below—improving or sharing clean data structures over the interface between adjacent layers is an acceptable trade-off when it eliminates redundant copying or translation boilerplate.

## 2. Runtime & AOT Boundary (`RuntimeBoundaryTestCase`)

- `org.babblelang.runtime.*` is packaged into standalone `babblec` AOT jars (`-target jvm`) and must remain free of compiler, analysis, ANTLR, ASM, and interpreter dependencies.
- Run `mvn -q test -Dtest=ArchitectureBoundaryTestCase,RuntimeBoundaryTestCase` after any cross-package move or class extraction.

## 3. Axioms Rule (`AxiomsTestCase`)

- Built-in assumptions about Java classes/methods/constructors, nullability annotations, optimizer `foldable`/`idempotent` methods, and Babble built-ins belong strictly in `src/main/java/org/babblelang/axioms/`—never hardcoded in `analysis`, `opt`, or `compiler`.

## 4. Performance-Sensitive Hot Paths

Treat these subsystems as performance-sensitive when simplifying or decomposing:

- **Interpreter**: `org.babblelang.engine.impl.Interpreter`
- **IR lowering & codegen**: `HirBuilder`, `MirLowerer`, `HirEmitter`, `MirEmitter`, `WasmFuncEmitter`
- **Runtime dispatch & collections**: `org.babblelang.runtime.*`

Avoid introducing unnecessary boxing, extra per-node/per-call allocations, deep virtual/megamorphic dispatch layers, or redundant map lookups inside hot loops. When restructuring compiler/IR/runtime hot paths:

- Verify `examples/raytracer-argon/scene.ppm` still renders byte-for-byte identically.
- Spot-check `org.babblelang.bench.Benchmarks` if hot-loop structure changed significantly.

## 5. Tiered Verification Ladder

### Fast inner loop (after each incremental step)
```bash
# Boundary & architecture checks
mvn -q test -Dtest=ArchitectureBoundaryTestCase,RuntimeBoundaryTestCase

# Targeted .batest.md specs across interpret, -O0, -O1, -O2, and Wasm
mvn -q test -PO2 -Dtest=BatestTestCase,WasmBatestTestCase -Dbatest.filter=<topic-or-file>

# Affected focused JUnit tests
mvn -q test -Dtest=<AffectedTestCase>

# Confirm src/test/batest is completely untouched
git status -- src/test/batest
```

### Milestone / PR gate (end of weekly session)
```bash
# One-command local parallel CI gate (add --container for JDK 21 + Node 22 parity, --argon for raytracer check)
./src/tools/ci_gate.sh --protect-batest --container

# Or individual steps:
# Hard-limit & net-LOC audit
python3 .agents/skills/periodic-refactor/scripts/audit_file_sizes.py --check-hard-limit

# Immutable oracle check (must produce no output)
git status -- src/test/batest

# Full 4-mode JVM + Wasm build & test suite
mvn -B package -PO2

# 6-scope >= 80% line & branch JaCoCo coverage gate
mvn -q verify -Pbatest

# Formatter cleanliness on repository .ba files
java -jar target/babble-1.0-SNAPSHOT.jar -format -check examples src/main/java/org/babblelang/axioms
```
