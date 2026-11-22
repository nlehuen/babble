# Authoring and reviewing `.batest` files

This guide applies to all `.batest` specification files under `src/test/batest/` (defined in [BIP009](../../../docs/design/bip009_test-coverage.md) and executed by [`BatestTestCase`](../java/org/babblelang/tests/batest/BatestTestCase.java)).

## Why small, readable cases matter : four levels of verification

Every `.batest` case is checked automatically by a **3-way execution oracle** — `interpret`, `compile -O0`, and `compile -O1` (with `HirVerifier` and ASM's `CheckClassAdapter` in compiled modes) — and matched against its expected outcome.

That automated oracle catches regressions and disagreements *between* backends, **not** shared bugs where the interpreter and compiler make the same mistake, or tests whose expectations were silently wrong from the start. We therefore require a **4th level of verification : manual validation by a human or an LLM reviewer**.

A `.batest` file succeeds only when a reviewer can read the preamble once, glance at any single case, and verify by reasoning — without mental gymnastics — that the code should produce the expected result.

## Size budgets

A single `.batest` file may contain tens of cases and grow to ~1,000 lines overall. What matters is the working-memory budget for verifying *one* case (`preamble + case`), **enforced automatically by [`BatestParser`](../java/org/babblelang/tests/batest/BatestParser.java)** :

- **Preamble** (and each virtual module) : strictly **< 800 characters** of code (comments excluded).
- **Each test case** (code between `{{{{{` and `}}}}}`, excluding `----------` session splitters) : strictly **< 400 characters** of code (comments excluded).

**Comments (`;; ...` and `;;( ... ;;)`) do not count toward either limit**, so useful context is never squeezed out by the code budget. Keep comments concise and purposeful :
- **In the preamble** : state what is being tested and list relevant references (`BIP###` / sections, or target source classes/methods when covering specific compiler, analysis, or runtime behavior), plus short notes on shared helpers or rigs.
- **In test cases** : do **not** write trivial comments that can be inferred by reading the few lines of test code. Only add a comment when explaining a deviation from the norm, a subtlety, a surprise (e.g. a non-obvious spec rule, an IEEE-754 quirk, or why inputs go through a parameter to prevent `-O1` folding), or flagging suspect behavior with `;; QUESTIONABLE : ...`.

Keeping the executable code under budget guarantees that any case plus its preamble fits in under ~1,200 code characters and can be audited at a glance.

When you hit a limit :
- **Case >= 400 code chars** : split it into smaller cases (one property, branch, or error per case), or extract shared setup/assertions into a preamble helper or generic test rig.
- **Preamble >= 800 code chars** : drop unused declarations, simplify shared types, or split the file into two focused `.batest` files.
- **Virtual modules** (`========== name.ba ==========`) : keep each virtual module compact and focused on the single cross-module interaction under test.

### Overriding the limit (`;; LARGE: <reason>`)

When a preamble, virtual module, or case *genuinely* cannot fit under budget (for example, `functions/high_arity.batest` testing > 200 parameters or `objects/lowering_and_compound.batest` testing > 20,000-char string chunking and method splitting), place a `;; LARGE: <reason>` comment inside that section. A non-empty `<reason>` is required — bare `;; LARGE` or `;; LARGE:` is rejected by `BatestParser`.

> **Note on legacy files** : many older `.batest` files predate these guidelines and carry `;; LARGE: legacy test pending refactoring` markers or multi-column `println` goldens. Do **not** copy those patterns when writing new tests; when editing an existing file, prefer splitting or migrating touched cases so their `;; LARGE:` markers can be removed.

## Organization : coherent directories and focused files

Each `.batest` file should test **one coherent aspect of the language** and live in the matching topic directory under `src/test/batest/` :

| Directory | Focus |
|---|---|
| `syntax/` | Lexing, parsing, significant newlines, continuations, syntax errors |
| `primitives/` | Scalars (`byte`..`double`, `bool`, `char`, `string`), arithmetic, conversions |
| `control/` | Blocks, scoping, `if`/`else`, `while`, `for`, `break`/`continue`, `return` |
| `functions/` | Calls, arity, named/default parameters, closures, cells, recursion |
| `objects/` | Classes, records, objects, interfaces, `super`, operator methods, compound assignment |
| `types/` | Inference, widening, unions, nullables, narrowing, `?.`, generics, arrays, intersections, aliases, casts |
| `effects/` | Purity, `io`/`volatile`/`mut`/`borrow`/`unique`/`frozen`, `calling` HOF forwarding, axioms |
| `modules/` | `export`/`import`, virtual modules, cycles, cross-module types and effects |
| `sessions/` | Multi-input `BabbleSession` (`----------`), shadowing, cross-input caches |
| `java/` | Java packages, classes, overloads, fields, arrays, functional interfaces, exceptions, nullability |
| `compiler/` | JVM interface lowering, Java bridges, HIR optimizer passes, bytecode edge cases |
| `caches/` | Polymorphic and megamorphic `invokedynamic` inline caches |
| `runtime/` | Shared runtime helpers and boundary edge cases |

Name new files after the language feature (`types/type_aliases.batest`, `control/for_loops.batest`), not after implementation phases (`*_p1.batest`) or grab-bag buckets (`*_edges.batest`).

## Anatomy of a proper `.batest` file

1. **Preamble (< 800 code chars)** :
   - Start with a **top-level `;;` comment** explaining what is being tested, with relevant references (e.g. `BIP###` / sections, or target Java classes/methods where applicable).
   - Define a few **simple types, functions, classes/records, interfaces, and variables** reused across the cases.
   - Optionally add **test helpers** (to keep assertions concise with clear failure messages) or a **test rig** — e.g. a generic function instantiated with several types across the cases.
2. **Test cases (< 400 code chars each)** :
   - Define only the extra bindings specific to that case.
   - Omit trivial comments that just restate the code; only comment on deviations from the norm, subtleties, or surprises.
   - For happy-path cases, **verify behavior with `assert(...)` and print nothing** — leave the expected outcome block empty so only anomalies (warnings) or errors surface after `}}}}}`.

### Example

```babble
;; Generic Box container : construction, transformation, equality, and type errors
;; References : BIP008 (generics), BIP012 (`of` type application)

def Box of T = record (value: T) (
    def transform of U = fn (f: fn (T): U): Box of U -> ( Box(f(this.value)) )
)

;; Test helper : asserts value equality with a readable failure message
def expectEq of T = fn io (actual: T, expected: T) -> (
    assert(actual == expected, "expected " + expected + ", got " + actual)
)

;; Test rig : verifies identity and transformation for any element type T
def checkIdentity of T = fn io (v: T) -> (
    def b = Box(v)
    expectEq(b.value, v)
    expectEq(b.transform(fn (x: T): T -> ( x )), b)
)

{{{{{
checkIdentity of int(42)
checkIdentity of double(1.5)
checkIdentity of string("babble")
}}}}}

{{{{{
def doubled = Box(21).transform(fn (n: int): int -> ( n * 2 ))
def labelled = doubled.transform(fn (n: int): string -> ( "n=" + n ))
expectEq(doubled.value, 42)
expectEq(labelled.value, "n=42")
}}}}}

{{{{{
;; Surprise : scalar int widens to double, but Box of int does not widen to Box of double
def bi: Box of int = Box(1)
def bd: Box of double = bi
}}}}}
Type error : expected Box(double), got Box(int)
```

*(Preamble : 389 code chars; cases : 87, 193, and 54 code chars — comments excluded.)*

## Prefer silent `assert(...)` over `println` goldens

Avoid printing multiple values separated by colons and checking a dense golden string :

```babble
;; AVOID : hard to read, hard to verify, opaque on failure
{{{{{
def a = computeA()
def b = computeB()
def ok = check(a, b)
println(a, ":", b, ":", ok)
}}}}}
1:2:true
```

Instead, assert each invariant directly and print nothing — silence is the signal that everything succeeded, and only anomalies or errors appear after `}}}}}` :

```babble
;; PREFER : self-documenting, easy to verify by eye, silent on success, pinpoints failures
{{{{{
def a = computeA()
def b = computeB()
assert(a == 1)
assert(b == 2)
assert(check(a, b))
}}}}}
```

Why this is strictly better :
- **Inline verification** : a reviewer checks `a == 1` right where `a` is computed instead of counting colons across detached output lines.
- **Pinpoint failures** : `BatestRunner` uses Babble's real `AssertFunction`, so a failed `assert(b == 2)` immediately fails with `Assertion failed : b == 2` (or your custom 2nd-argument message `assert(b == 2, "got " + b)`).
- **Signal over noise** : leaving the expected block empty for passing cases means anything written after `}}}}}` immediately stands out as a warning, an expected error, or an explicit output test. (There is no need for a trailing `println("OK")`.)

### Babble `assert` notes

- `assert` requires a strict `bool` (no truthiness coercion : write `assert(x != null)`, not `assert(x)`).
- `assert` has signature `fn (c: bool, message: any? = ...): bool` — it is **pure** (callable inside pure helpers without `io`) and **throwing** (not `nothrow`, so standalone `assert(...)` statements never trigger the unused-pure check).
- If a generic helper formats an unconstrained type parameter `T` in a failure message (`"expected " + expected`), mark the helper `fn io`, since `toString` on an unknown `T` is not pure; a helper using `assert(actual == expected)` or a literal/string label stays pure.
- `==` checks value equality on primitives, strings, `record`s, and `array of T` (content equality). Use `same(a, b)` for reference identity, and `java.lang.Double.isNaN(x)` / `java.lang.Float.isNaN(x)` for `NaN`.

### When a non-empty expected block is appropriate

1. **Error cases** (compile-time, syntax, scope, type, effect, or runtime errors) : put the normalized primary error message after `}}}}}`.
2. **Warning cases** : put the expected `Warning : ...` lines after `}}}}}` (followed by any error line if the case also fails).
3. **Output and ordering tests** : cases specifically testing `print`/`println`, `to:`, `toString`, or exact side-effect execution order across branches, loops, or session inputs. (Even for `toString`, `assert(string(x) == "...")` with an empty expected block is often clearer.)

## Format and harness mechanics (quick reference)

- **Markers** ([`BatestParser`](../java/org/babblelang/tests/batest/BatestParser.java)) :
  - `{{{{{` / `}}}}}` (5+ braces on their own line) open and close a case's code block; text after `}}}}}` up to the next `{{{{{` or EOF is the expected outcome (trimmed; empty for silent passing cases).
  - `========== path.ba ==========` in the preamble declares a virtual module loaded via `MapModuleSource`; `==========` returns to the main preamble. Even without virtual modules, an empty `MapModuleSource` is active so missing imports fail portably.
  - `----------` (7+ hyphens on its own line) inside a case splits it into sequential `BabbleSession` inputs.
- **Preamble semantics** :
  - Normal cases concatenate `preamble + "\n" + caseCode` into a fresh, isolated compilation unit and `ScriptRuntime` (so call-site inference and intra-file lowering work normally, and cases cannot leak state).
  - Session (`----------`) cases evaluate a non-empty, non-comment-only preamble as input 0 of a fresh `BabbleSession`, then each section sequentially (each input commits and freezes inferred types).
- **Error normalization and `Kind`** ([`BatestRunner`](../java/org/babblelang/tests/batest/BatestRunner.java)) :
  - Wrapper prefixes (`BabbleException:`, `ScriptException:`), trailing `in <src> at line number N` suffixes, embedded line coordinates (`Lexical error : ...`, `Assertion failed : ...`, `because of the assignment/call)`), dynamic type IDs (`expected T#, got T#`), OS-locale `FileNotFoundException` reasons (`(<OS error>)`), JDK 22+ reflective receiver wording, and `InvocationTargetException` wrappers are stripped/normalized; `StackOverflowError` becomes `Stack overflow`.
  - `OK`, `USER_ERROR`, and `INTERNAL_CRASH` are tracked separately : `OK` stdout can never match a `USER_ERROR`, and `INTERNAL_CRASH` (`VerifyError`, uncaught internal NPE/ISE) fails unconditionally.
- **SLL grammar check** : `ParserGrammarTestCase` parses every valid `.batest` file with pure `PredictionMode.SLL`.
- **Determinism** : never rely on default `ClassName@<identityHash>`, timestamps, unordered `HashMap`/`HashSet` iteration order (use `sortedmap`/`sortedset` or check size/membership), host paths, or multiple threads.
- **Watch `-O1` constant folding** : if a case aims to test runtime execution rather than constant folding, feed inputs through a function parameter or mutable variable so `-O1` cannot fold the whole expression away.
- **Repo conventions** : put a space before colons in Babble-style messages (`Feature : detail`), and never mention unimplemented `RM###` identifiers in comments or filenames (use `BIP###` instead).

## Reviewing `.batest` files

When reviewing a new or modified `.batest` file (as a human or an LLM), walk through this checklist :

- [ ] **4th-level manual check** : read the preamble and every case and verify by reasoning that the expected silent pass, warning, or error follows from Babble's specification. Never rubber-stamp a test merely because `interpret`, `-O0`, and `-O1` agree.
- [ ] **Flag `;; QUESTIONABLE` behavior** : if a case passes all three modes yet exhibits doubtful or surprising behavior, annotate it with `;; QUESTIONABLE : <reason>` and report it explicitly (see below).
- [ ] **Size budgets** : enforced automatically by `BatestParser` (preamble & virtual modules < 800 code chars; cases < 400 code chars, comments excluded). Verify that any `;; LARGE: <reason>` override is genuinely necessary rather than a case that should be split or factored.
- [ ] **Silent `assert(...)` over golden blobs** : happy-path cases use `assert(...)` with no output (empty expected block) instead of `println("OK")` or unlabeled multi-value `println(a, ":", b, ...)` goldens (unless directly testing printing or output order).
- [ ] **Preamble clarity** : opens with a clear topic comment and relevant references (BIPs, or source classes/methods when relevant); defines only simple, actually-reused types, functions, variables, helpers, or generic rigs.
- [ ] **Case focus and comments** : each case tests one clear behavior, branch, or error, defines only case-specific extras, and omits trivial comments (commenting only on deviations from the norm, subtleties, or surprises).
- [ ] **Negative-case precision** : every error case fails for the *intended* reason (not an accidental earlier syntax/type error), tests one error per case, and expects the normalized diagnostic.
- [ ] **Coherent placement** : file sits in the right topic directory, stays focused on one aspect of the language, and uses a feature-based filename.
- [ ] **Determinism and hygiene** : no identity hashes, timestamps, unordered map iteration, host paths, or unimplemented `RM###` references; parses cleanly under pure SLL.
- [ ] **Coverage tests remain readable** : cases added to close JaCoCo branches (`-Pbatest`) still respect the 400-char limit and silent `assert(...)` style instead of packing many branches into one mega-case.

### Flagging questionable passing behavior (`;; QUESTIONABLE`)

When writing or reviewing a `.batest` case that **passes** (`interpret`, `-O0`, and `-O1` all agree) yet exhibits questionable, surprising, or potentially buggy language behavior (a suspected shared bug, spec gap, or unintended edge case) :

1. **Annotate the case** inside `{{{{{ ... }}}}}` with a `;; QUESTIONABLE : <concise reason>` comment explaining what currently happens and why it looks suspect (matching the repo's `;; QUESTIONABLE` convention in `src/main/java/org/babblelang/axioms/`).
2. **Keep the test pinning current behavior** unless fixing the underlying bug is in scope for the current change, so the behavior stays visible and searchable.
3. **Report it explicitly** to the user (or in the PR / review summary) with the file path, case number/line, observed behavior, and why it looks questionable, so a maintainer can decide whether to fix Babble or confirm the behavior as intentional.

## Commands

Find all cases currently tagged `;; QUESTIONABLE` or `;; LARGE:` :

```bash
rg -n ';; QUESTIONABLE' src/test/batest/
rg -n ';; LARGE' src/test/batest/
```

Run a single file, glob, or `#N` / `:line` case across all three modes (fast inner loop, no JaCoCo; also enforces the character limits) :

```bash
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=types/type_aliases.batest
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=types/type_aliases.batest#3
```

Debug compiled `-O1` mode alone :

```bash
mvn -q test-compile surefire:test@compiled-tests -Dtest=BatestTestCase -Dbatest.filter=types/type_aliases.batest#3
```

Verify pure `PredictionMode.SLL` parsing across all `.batest` files :

```bash
mvn -q test -Dtest=ParserGrammarTestCase
```

Check scoped coverage and inspect uncovered lines/branches of a target class :

```bash
mvn -q verify -Pbatest -Dbatest.filter=types/type_aliases.batest -Dbatest.inspect=TypeChecker
```

Run the full 3-JVM 5-scope JaCoCo coverage gate (`>= 75%` branch coverage per scope) :

```bash
mvn -q verify -Pbatest
```
