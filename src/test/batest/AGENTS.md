# Authoring and reviewing `.batest.md` files

This guide applies to all `.batest.md` specification files under `src/test/batest/` (defined in [BIP009](../../../docs/design/bip009_test-coverage.md) and redesigned in [BIP029](../../../docs/design/bip029_batest-v2-and-ir-goldens.md), executed by [`BatestTestCase`](../java/org/babblelang/tests/batest/BatestTestCase.java) and [`WasmBatestTestCase`](../java/org/babblelang/tests/batest/WasmBatestTestCase.java)). For overall test-suite policy (when to write `.batest.md` vs Java JUnit tests, legacy-suite rules, and test layout), see [`../AGENTS.md`](../AGENTS.md).

## Why small, readable cases matter : multi-backend oracle plus manual review

Every `.batest.md` case is checked automatically by a **multi-backend execution oracle** — `interpret`, `compile -O0`, `compile -O1`, `compile -O2` (under `-PO2` and `-Pbatest`, with `HirVerifier` and ASM's `CheckClassAdapter`, plus any `hir` IR golden blocks matching the active optimization level), and **WebAssembly 3.0** (`WasmBatestTestCase`, `wasm-js -O1` and `-O2`, plus `wat` blocks for portable cases) — matched against its expected `stdout`, `stderr`, and `error` blocks, and checked for pure-`SLL` parsing (`ParserGrammarTestCase`) and idempotent formatting (`FormatterTestCase`).

That automated oracle catches regressions and disagreements *between* backends, **not** shared bugs where the interpreter and compiler make the same mistake, or tests whose expectations were silently wrong from the start. We therefore require a **4th level of verification : manual validation by a human or an LLM reviewer**.

A `.batest.md` file succeeds only when a reviewer can read the preamble once, glance at any single `##` case, and verify by reasoning — without mental gymnastics — that the code should produce the expected result.

## Size budgets

All three readability budgets are **enforced automatically by [`BatestParser`](../java/org/babblelang/tests/batest/BatestParser.java)** :

- **Whole file** : at most **1,000 lines** (`BatestParser.MAX_FILE_LINES`, hard limit — no override). When a file exceeds 1,000 lines, split it into logically grouped chunks `< 1,000` lines each and give every chunk a descriptive feature name (not `foo_1.batest.md`, `foo_2.batest.md`, etc.).
- **Preamble** (`;; babble preamble`) and each **virtual module** (`;; babble module=<path>`) : strictly **< 800 characters** of Babble code (comments excluded).
- **Each test case** (combined `babble` / `;; babble session` code inside a `##` section) : strictly **< 400 characters** of Babble code (comments excluded).

**Markdown prose outside fences, non-Babble blocks (`stdout`, `stderr`, `error`, `hir`, `wat`), and Babble comments (`;; ...` and `;;( ... ;;)`) do not count toward the character budgets** (though all lines in the file count toward the 1,000-line file limit).

When you hit a limit :
- **File > 1,000 lines** : split the file into cohesive, descriptively named feature files (each `< 1,000` lines).
- **Case >= 400 code chars** : split it into smaller `##` cases (one property, branch, or error per case), or extract shared setup/assertions into `;; babble preamble`.
- **Preamble >= 800 code chars** : drop unused declarations, simplify shared types, or split the file into two focused `.batest.md` files.
- **Virtual modules** (`;; babble module=<path>`) : keep each virtual module compact and focused on the single cross-module interaction under test.

### Overriding the character budget (`large="<reason>"`)

When a preamble, virtual module, or case *genuinely* cannot fit under its character budget (for example, `functions/high_arity.batest.md` testing > 200 parameters or `objects/lowering_and_compound.batest.md` testing > 20,000-char string chunking and method splitting), put `large="<reason>"` in the first-line `;; babble` directive:

````markdown
```babble
;; babble large="intentional high-arity stress test"
...
```
````

A non-empty `<reason>` is required — bare `large` or `large=""` is rejected by `BatestParser`.

## Organization : coherent directories and focused files

Each `.batest.md` file should test **one coherent aspect of the language** and live in the matching topic directory under `src/test/batest/` :

| Directory | Focus |
|---|---|
| `syntax/` | Lexing, parsing, significant newlines, continuations, syntax errors |
| `primitives/` | Scalars (`byte`..`double`, `bool`, `char`, `string`), arithmetic, conversions |
| `control/` | Blocks, scoping, `if`/`else`, `while`, `for`, `break`/`continue`, `return`, `try`/`catch`/`finally` |
| `functions/` | Calls, arity, named/default parameters, closures, cells, recursion |
| `objects/` | Classes, records, objects, interfaces, `super`, operator methods, compound assignment |
| `types/` | Inference, widening, unions, nullables, narrowing, `?.`, generics, arrays, intersections, aliases, casts |
| `effects/` | Purity, `io`/`volatile`/`mut`/`borrow`/`unique`/`frozen`, `calling` HOF forwarding, axioms |
| `modules/` | `export`/`import`, virtual modules, cycles, cross-module types and effects |
| `sessions/` | Multi-input `BabbleSession` (`;; babble session`), shadowing, cross-input caches |
| `java/` | Java packages, classes, overloads, fields, arrays, functional interfaces, exceptions, nullability |
| `compiler/` | JVM interface lowering, Java bridges, HIR optimizer passes & IR goldens, bytecode edge cases |
| `caches/` | Polymorphic and megamorphic `invokedynamic` inline caches |
| `runtime/` | Shared runtime helpers and boundary edge cases |

Name new files after the language feature (`types/type_aliases.batest.md`, `control/for_loops.batest.md`), not after implementation phases (`*_p1.batest.md`), grab-bag buckets (`*_edges.batest.md`), or numbered chunks (`*_1.batest.md`, `*_2.batest.md`).

## Anatomy of a proper `.batest.md` file

1. **File header (before the first `##` heading)** :
   - Open with `# <Title>` and concise prose referencing relevant `BIP###` sections or target Java classes/methods.
   - Optionally define shared virtual modules (`;; babble module=<path>`) and at most one preamble (`;; babble preamble`, < 800 code chars).
2. **Test cases (`## <Unique Case Name>`)** :
   - Every `##` heading outside a code fence starts a named case (duplicate names in the same file are rejected).
   - Provide a `babble` block (or sequential `;; babble session` blocks), optional case-scoped `;; babble module=<path>` overrides, optional behavioral outcome blocks (`stdout`, `stderr`, `error`), and optional IR blocks (`hir`, `mir`).
   - For happy-path cases, **verify behavior with `assert(...)` and omit `stdout`, `stderr`, and `error`** — omitting them asserts empty stdout, empty stderr, and `Kind.OK` success!

### Example

````markdown
# Generic Box container : construction, transformation, equality, and type errors

References : `BIP008` (generics), `BIP012` (`of` type application).

```babble
;; babble preamble
def Box of T = record (value: T) (
    def transform of U = fn (f: fn (T): U): Box of U -> ( Box(f(this.value)) )
)

def expectEq of T = fn io (actual: T, expected: T) -> (
    assert(actual == expected, "expected " + expected + ", got " + actual)
)

def checkIdentity of T = fn io (v: T) -> (
    def b = Box(v)
    expectEq(b.value, v)
    expectEq(b.transform(fn (x: T): T -> ( x )), b)
)
```

## Identity transform across primitive and reference types

```babble
checkIdentity of int(42)
checkIdentity of double(1.5)
checkIdentity of string("babble")
```

## Chained generic transform

```babble
def doubled = Box(21).transform(fn (n: int): int -> ( n * 2 ))
def labelled = doubled.transform(fn (n: int): string -> ( "n=" + n ))
expectEq(doubled.value, 42)
expectEq(labelled.value, "n=42")
```

## Box of int does not widen to Box of double

```babble
def bi: Box of int = Box(1)
def bd: Box of double = bi
```

```error
Type error : expected Box(double), got Box(int)
```
````

## Prefer silent `assert(...)` over `stdout` golden blobs

Instead of printing colon-delimited strings and pinning a `stdout` block, assert each invariant inline with `assert(...)` and omit `stdout`/`stderr`/`error` completely. Silence is the signal that everything succeeded; non-empty outcome blocks stand out immediately as explicit `stdout` tests, `stderr` warnings, or `error` expectations.

### Babble `assert` notes

- `assert` requires a strict `bool` (write `assert(x != null)`, not `assert(x)`).
- `assert` has signature `fn (c: bool, message: any? = ...): bool` — it is **pure** and **throwing** (never triggers the unused-pure check).
- `==` checks value equality on primitives, strings, `record`s, and `array of T`. Use `same(a, b)` for reference identity, and `java.lang.Double.isNaN(x)` / `java.lang.Float.isNaN(x)` for `NaN`.

## Format and harness mechanics (quick reference)

- **Fenced blocks and first-line option directives** ([`BatestV2Parser`](../java/org/babblelang/tests/batest/BatestV2Parser.java)) :
  - Open with 3+ backticks and **only the block kind** (```` ```<kind> ````) and close with at least the same number of backticks. Use 4+ backticks if a block body contains ```` ``` ````.
  - Because Markdown renderers (GitHub, Antigravity) hide text after the language name on the opening fence line, **block options are placed on the first line inside the block** as `<comment> <kind> <options>` (options on the opening fence line are rejected) :
    - `;; <kind> <options>` for Babble-family blocks (`babble`, `hir`, `wat`).
    - `# <kind> <options>` for output/error blocks (`stdout`, `stderr`, `error`).
  - `babble` with `;; babble preamble [large="..."]` (file header only, at most one).
  - `babble` with `;; babble module=<path> [large="..."]` (file header or case-scoped; overrides file-level module of the same path).
  - `babble` (with optional `;; babble large="..."`) for a single compilation unit, or multiple `babble` blocks starting with `;; babble session [large="..."]` for sequential `BabbleSession` inputs.
  - `stdout` (optional `# stdout regex`) — expected normalized stdout (default `""`).
  - `stderr` (optional `# stderr regex`) — expected normalized non-fatal stderr such as `Warning : ...` (default `""`).
  - `error` (optional `# error regex`) — expected fatal compile-time or runtime error (`Kind.USER_ERROR`; omitting `error` asserts `Kind.OK`).
  - The `regex` flag compiles the block body with `Pattern.MULTILINE | Pattern.DOTALL` and matches the whole channel (`matcher(actual).matches()`), so `.*` spans multiple lines automatically. Use `regex` only for text that varies with JDK version, never to loosen a Babble message.
- **IR golden & check blocks (`BIP029`, `BIP039`)** :
  - Open with ```` ```hir ```` or ```` ```wat ```` and put the directive on line 1 : `;; <hir|wat> <opt-levels> <target> [stage=<before|final|before,final|after:PassName>] [in=<module.ba>] [regex|check]`.
  - `<opt-levels>` : `-O0`, `-O1`, `-O2`, or comma-separated combinations (`-O0,-O1`, `-O1,-O2`, `-O0,-O1,-O2`). Blocks whose opt-levels do not match the active run are skipped.
  - `<target>` : `fn=<name>`, `fn=<Type>.<method>`, `class=<name>`, `record=<name>`, `interface=<name>`, `top-level`, or `module`.
  - Default mode compares exact canonical IR (`HirCanonicalPrinter` / `WatPrinter`, preamble-independent and locally renumbered). `check` mode evaluates line predicates (`contains <s>`, `!contains <s>`, `count <N> <s>`, `regex <pat>`) and is only allowed when the actual canonical IR dump has at least `batest.minDumpSizeForUsingPartialChecks` lines (default `30`, `0` disables); shorter dumps must use exact goldens for readability.
  - Every non-error, non-session case under `src/test/batest/compiler/` must include at least one `hir` expectation applicable to `-O0`, at least one `hir` applicable to `-O1`, and at least one `hir` applicable to `-O2` (enforced by `BatestV2Parser` and `BatestV2HarnessTestCase`; combined headers such as `hir -O0,-O1` or `hir -O1,-O2` are allowed when canonical HIR is identical across levels).
  - Update stale exact-golden IR blocks safely after intentional optimizer/printer changes with `-Dbatest.updateIr=true` (rewrites only exact IR block bodies below their `;; hir` directive on behaviorally-passing verified cases and fails loudly afterward so CI can never pass with blessing enabled).
- **Error normalization and `Kind`** ([`BatestRunner`](../java/org/babblelang/tests/batest/BatestRunner.java)) :
  - Wrapper prefixes (`BabbleException:`, `ScriptException:`), trailing `in <src> at line number N`, embedded line coordinates, dynamic type IDs (`expected T#, got T#`), OS-locale `FileNotFoundException` reasons (`(<OS error>)`), JDK 22+ reflective receiver wording, and `InvocationTargetException` wrappers are normalized; `StackOverflowError` becomes `Stack overflow`.
  - `INTERNAL_CRASH` (`VerifyError`, uncaught internal NPE/ISE) fails unconditionally.
- **SLL grammar & formatter checks** : `ParserGrammarTestCase` parses every valid `.batest.md` snippet with pure `PredictionMode.SLL`, and `FormatterTestCase` verifies idempotent formatting across every valid `.batest.md` snippet.
- **Determinism** : never rely on default `ClassName@<identityHash>`, timestamps, unordered `HashMap`/`HashSet` iteration order, host paths, or multiple threads.
- **Watch `-O1` constant folding** : if a case aims to test runtime execution rather than constant folding, feed inputs through a function parameter or mutable variable.
- **Repo conventions** : put a space before colons in Babble-style messages (`Feature : detail`), and never mention unimplemented `RM###` identifiers in prose or filenames (use `BIP###` instead).

## Reviewing `.batest.md` files

- [ ] **4th-level manual check** : verify by reasoning that every case's expected pass, `stdout`, `stderr`, `error`, and IR golden follows from Babble's specification.
- [ ] **Flag `;; QUESTIONABLE` behavior** : annotate doubtful passing behavior with `;; QUESTIONABLE : <reason>` and report it explicitly.
- [ ] **Size budgets** : file <= 1,000 lines; preamble & virtual modules < 800 code chars; cases < 400 code chars; every `large="..."` override is justified.
- [ ] **Silent `assert(...)`** : happy-path cases omit `stdout`/`stderr`/`error` unless directly testing output or warnings.
- [ ] **Negative-case precision** : every `error` case fails for the intended reason and cannot pass by printing text to `stdout`.
- [ ] **Determinism & SLL hygiene** : passes `ParserGrammarTestCase` pure SLL and has no unimplemented `RM###` references.

## Commands

Find all cases currently tagged `;; QUESTIONABLE` or `large=` :

```bash
rg -n ';; QUESTIONABLE' src/test/batest/
rg -n 'large=' src/test/batest/
```

Run a single file, glob, `#N` / `:line`, or `@caseName` across all three modes (fast inner loop, no JaCoCo) :

```bash
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=types/type_aliases.batest.md
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=algebraic_simplify@idempotent
```

Bless stale exact-golden IR blocks on behaviorally-passing cases (inspect `git diff` and re-run without the flag) :

```bash
mvn -q test -Dtest=BatestTestCase -Dbatest.filter=compiler/ -Dbatest.updateIr=true
```

Verify pure `PredictionMode.SLL` parsing across all `.batest.md` files :

```bash
mvn -q test -Dtest=ParserGrammarTestCase
```

Run the full 4-JVM 6-scope JaCoCo coverage gate (`>= 80%` line and `>= 80%` branch coverage per scope) :

```bash
mvn -q verify -Pbatest
```

Re-render the report or inspect classes from existing JaCoCo XMLs without re-running tests :

```bash
mvn -q exec:java@batest-report -Pbatest -Dbatest.inspect=HirEmitter,ClassGen -Dbatest.minLinePct=0 -Dbatest.minBranchPct=0
```
