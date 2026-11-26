---
name: periodic-refactor
description: >-
  Guides periodical (weekly) large-scale structural refactorings of Babble's
  src/main production codebase to enforce the 1,250-line soft limit and
  2,000-line hard physical-LOC limit while strictly protecting src/test/batest.
  Use when auditing src/main file sizes, planning or executing a weekly
  refactoring session, or bringing oversized production files below threshold.
---

# Periodic Refactoring (`src/main` File-Size Hygiene)

Use this skill for periodical (typically weekly) structural refactorings of Babble's production source tree under `src/main` (`*.java`, `*.g4`, `*.ba`; generated sources under `target/` are excluded).

## Thresholds (Physical LOC, including comments and blank lines)

Physical line count is a **signal for spotting insufficiently factored code** (copy-pasta, cookie-cutter branches, repeated boilerplate, or conflated responsibilities)—not a metric to game.

- **Hard limit — 2,000 LOC**: Enforced automatically by `ArchitectureBoundaryTestCase` across all production source files under `src/main`. Pre-existing files above 2,000 LOC are pinned in `GRANDFATHERED_CEILINGS` at their high-water mark so they can never grow, and their exemptions must be tightened (slack > 50 LOC fails) or removed as they shrink.
- **Soft limit — 1,250 LOC**: Signal to schedule structural refactoring during the next periodic session.
- **Near threshold — 1,100–1,250 LOC**: Flagged during audit for awareness and proactive design care.

## Hard Constraints

1. **Strictly protect `src/test/batest`.** Do not modify, move, or delete anything under `src/test/batest` (and never run `-Dbatest.updateIr=true`). These black-box specs and IR/WAT goldens are the immutable behavioral oracle.
2. **Adapt `src/test/java` only as needed.** Unit tests under `src/test/java` are out of scope for the 1,250-line limit, but should be updated or reorganized in lockstep with production changes (imports, signatures, splitting a test class when its production class is split). Never weaken assertions or drop coverage.
3. **No mechanical splitting or line-count gaming.**
   - Never satisfy a line limit by slicing files into `FooPart1`/`FooPart2` or dumping functions into generic `*Utils`/`*Helpers` buckets.
   - Never game physical LOC by compressing formatting: do **not** cram code onto super-long lines, collapse readable multi-line expressions/signatures/streams onto single lines, delete blank lines that separate logical sections, or strip useful comments/Javadoc.
   - Every reduction must come from genuine factoring and structural improvement (eliminating copy-pasta, cookie-cutter repetition, awkward state, or tangled concerns) while preserving or improving readability.
4. **Pure refactoring only.** Zero intended changes to runtime behavior, semantics, diagnostics, IR output, or external contracts.
5. **Respect Babble invariants.** Before designing splits or moving code across packages, read [references/babble-invariants.md](./references/babble-invariants.md) for `ArchitectureBoundaryTestCase`, `RuntimeBoundaryTestCase`, axioms, and performance hot-path rules.

---

## Phase 1: Audit & Diagnosis

1. Run the deterministic audit script from the repository root:
   ```bash
   python3 .agents/skills/periodic-refactor/scripts/audit_file_sizes.py --since "14 days ago"
   ```
2. Present the full ranked table of all files exceeding 1,250 lines (clearly separating **> 2,000 hard/grandfathered** from **1,251–2,000 soft**), list near-threshold files (**1,100–1,250**), and record the baseline **total `src/main` physical LOC**.
3. **Propose a focused session scope** (default: **1–3 cohesive files or one subsystem** per weekly PR, overridable by the user):
   - Prioritize any file violating or grandfathered above the **2,000-line hard limit** first.
   - Next, prioritize the highest-LOC and highest-churn (actively growing) files above 1,250 lines.
4. For each file in the proposed session scope, inspect the code and diagnose *why* it is large: tangled responsibilities, copy-pasted logic, verbose boilerplate, awkward data models, or sprawling imperative algorithms.

## Phase 2: Architectural Proposals

Before proposing concrete edits, analyze and discuss refactoring levers for each targeted file or subsystem:

- **Deduplication & consolidation**: Shared abstractions, genericized pipelines, or table-driven logic that reduce **total** codebase LOC, not just per-file LOC.
- **Data model adjustments**: Cleaner internal representations, algebraic/sealed types, or state simplifications that eliminate defensive branching.
- **Algorithmic improvements**: More expressive or declarative algorithms that replace sprawling imperative code.
- **Cohesive decomposition**: Splitting strictly along clear domain or responsibility boundaries only where a file genuinely conflates distinct concerns (checking [references/babble-invariants.md](./references/babble-invariants.md) so new classes respect ANTLR, `ScopeAnalysis`, reflection, Wasm, and runtime boundaries).

Estimate the expected per-file LOC and **net `src/main` LOC** impact for each proposal.

## Phase 3: Incremental Execution Roadmap

1. Sequence the approved scope into small, independent steps where **every step compiles cleanly and passes both `src/test/batest` (untouched) and `src/test/java` (updated to match structural changes)** before moving on.
2. List which `src/test/java` test classes will need corresponding updates or reorganization in each step.
3. Highlight high-risk areas: implicit ordering dependencies, reflection/bytecode assumptions, `ArchitectureBoundaryTestCase` / `RuntimeBoundaryTestCase` constraints, and performance-sensitive hot paths.

> **STOP GATE:** Stop after presenting Phases 1–3 for review. **Do not edit any files** until the user explicitly approves the plan (or adjusts the scope).

---

## Phase 4: Execution & Verification (Only After User Approval)

1. Ensure work happens on a dedicated branch created from fresh `origin/master` (never directly on `master`).
2. Execute the roadmap one step at a time:
   - After each step, run targeted `.batest.md` and JUnit tests plus `ArchitectureBoundaryTestCase` and `RuntimeBoundaryTestCase` (see [references/babble-invariants.md](./references/babble-invariants.md)).
   - Verify `git status -- src/test/batest` is completely empty.
   - If a grandfathered `> 2,000`-LOC file shrank, tighten its entry in `ArchitectureBoundaryTestCase.GRANDFATHERED_CEILINGS` (or remove it once `<= 2,000` LOC) in the same commit.
   - Review your diff to confirm reductions came from real factoring—not line-wrapping compression, removed blank lines, or stripped comments.
   - Commit each green step with a clear message (space before colon, e.g., `Refactor : decompose MirLowerer control-flow lowering`).
3. Before opening a PR / finishing the session, run the full verification gate:
   - `python3 .agents/skills/periodic-refactor/scripts/audit_file_sizes.py --check-hard-limit` (and report before/after per-file and **net `src/main` LOC**).
   - `git status -- src/test/batest` (must be empty).
   - `mvn -B package -PO2` (full 4-mode JVM + Wasm suite with verifiers).
   - `mvn -q verify -Pbatest` (6-scope `>= 80%` line and branch JaCoCo coverage gate).
   - `java -jar target/babble-1.0-SNAPSHOT.jar -format -check examples src/main/java/org/babblelang/axioms`.
   - If compiler/IR/runtime hot paths were touched, run the argon raytracer byte-for-byte check (`examples/raytracer-argon/scene.ppm`) and spot-check `org.babblelang.bench.Benchmarks` if warranted.
