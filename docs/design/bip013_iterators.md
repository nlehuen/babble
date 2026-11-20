# BIP013: Iterator support and `for` loops

| | |
|---|---|
| **BIP** | 013 |
| **Date** | 2026-11-19 |
| **Status** | Implemented in `RM009` (`BIP013-D1`–`D10` decided; `BIP013-P1`–`P4` implemented across `Babble.g4`, `Formatter`, `CoreCollections`, `BlockScopes`/`TypeChecks`/`Resolver`/`TypeChecker`/`EffectChecker`/`LoweringAnalyzer`, `Interpreter`, and `CodeGenerator`, with `Iterators.ba` and `for_loops.batest`) |
| **Depends on** | Bytecode compiler, `RM001` primitive lowering & `RM003` JVM/Java interface bridges ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) §19–§20), object model & operator protocol ([bip002_object-model.md](bip002_object-model.md)), function effects & `RM002`/`RM007`/`RM008` ([bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)), mandatory static typing, nullables & generics ([bip008_static-typing.md](bip008_static-typing.md)), `.batest` coverage harness ([bip009_test-coverage.md](bip009_test-coverage.md)), modular type-checker architecture (`RM004`, [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), core scalar & collection types and Java nullability alignment (`RM005`, [bip011_core-types.md](bip011_core-types.md)), predicate-free LL(1)/LL(2) grammar (`RM006`, [bip012_grammar-improvements.md](bip012_grammar-improvements.md)) |
| **Scope** | First-class iteration protocol (`java.lang.Iterable of T`, `java.util.Iterator of T`, `JavaArray of T`, and Babble custom iterables/iterators), `for <var> [: <type>] in <iterable> then ( ... )` loop expressions, `break [expr]` and `continue` across both `while` and `for` loops, static factory selection on specialized generic types (`(list of int).of(1, 2, 3)` / `list.of(1, 2, 3)` / `(java.util.List of int).of(...)`), `Babble.g4` & `Formatter`, `BlockScopes`/`Resolver`/`TypeChecker`/`EffectChecker`/`LoweringAnalyzer`, `Interpreter` & `CodeGenerator` lowering, and REPL multi-line classification |

---

## 1. Summary

This proposal adds **first-class iterator support and `for` loop expressions** to Babble so that idiomatic, statically typed, zero-wrapper iteration works seamlessly across `RM005` (`BIP011`) core collections, JDK iterables and iterators, Java arrays, and user-defined Babble iterables:

```babble
def foo = (list of int).of(1, 2, 3)   ;; or inferred: list.of(1, 2, 3)
def sum = 0
for value in foo then (
    sum += value
)
assert(sum == 6)
```

*(In pre-`BIP012` / `RM005` `C(T)` syntax this was written `def foo = list(int).of(1, 2, 3)`; under `BIP012-D4` standalone `expr of T` type application makes `(list of int).of(1, 2, 3)` and `(java.util.List of int).of(1, 2, 3)` work with zero parser predicates — see §3.2.)*

The design rests on six cohesive pillars aligned with Babble's existing architecture (`RM001`–`RM005`, `BIP012`):

1. **Familiar, predicate-free `for` expression syntax (`BIP013-D1`).**
   `for value in foo then ( ... )` (with an optional type annotation `for value: T in foo then ( ... )`) mirrors `while test then ( ... )` and `if test then ( ... )` while preserving Babble's single-bracket `()` design and `BIP012`'s zero-predicate LL(1)/LL(2) grammar. Both `for` and `in` are reserved keywords, while `in` remains valid after `.` / `?.` via `memberName` (preserving `java.lang.System.in`).
2. **Parameterized static factory calls (`(list of int).of(1, 2, 3)`, `BIP013-D2`).**
   Building directly on `RM005`'s core collections and `BIP012-D4`'s first-class standalone `expr of T` type application, static factory methods (`of`, `copyOf`, etc.) on both core collections (`list.of(1, 2, 3)`, `(list of int).of(1, 2, 3)`, `(set of string).of(...)`, `(map of (string, int)).of(...)`) and general Java/Babble generic classes (`(java.util.List of int).of(1, 2, 3)`) carry their explicit type arguments directly into `CallSolver`, complementing `RM005`'s mutable `list of int (1, 2, 3)` constructor.
3. **Zero-wrapper, multi-tier iteration protocol (`BIP013-D3`, `D4`, `D5`, `D9`).**
   `for` iterates natively over:
   * any `java.lang.Iterable of T` — covering all `RM005` core collections (`list`, `set`, `sortedset`, `map.entrySet()`/`keySet()`/`values()`), any JDK `Iterable`, and any Babble class `implements java.lang.Iterable of T` (compiled directly to a JVM `implements` bridge with zero `Proxy` allocation under `RM003`),
   * any bare `java.util.Iterator of T` directly (consuming one-shot iterators),
   * any `JavaArray of T` (`int[]`, `double[]`, `string[]`, ...) lowered directly to an indexed array loop with zero `Iterator` allocation, and
   * any Babble custom iterable or iterator (class, `record`, interface, or object defining `iterator()` returning an object with `hasNext(): bool` and `next(): T`, or exposing `hasNext(): bool` and `next(): T` directly), mirroring `BIP002`'s operator-method protocol and unlocking `$spec` unboxed primitive iteration.
   * `string` and `map of (K, V)` are intentionally **not** directly iterable: string iteration is ambiguous across UTF-16 code units vs Unicode code points/graphemes (use explicit methods/helpers like `.toCharArray()`), and maps use explicit `m.entrySet()` / `m.keySet()` / `m.values()` until destructuring `for (k, v) in m` (`BIP005-D3`) lands.
4. **Expression semantics & per-iteration lexical scoping (`BIP013-D6`).**
   Matching `while`, `for` is an expression that evaluates `iterable` once and yields the body's value from the last iteration (or `null` if 0 iterations, typed `join(bodyType, null)` when used). Each iteration binds a fresh, read-only `value` in its own block scope — so closures created inside the loop capture that iteration's value cleanly in both `Interpreter` and `CodeGenerator`.
5. **Sound static typing, nullability & `BIP007` effects (`BIP013-D8`).**
   Leveraging `RM005`'s parametric-nullness overlay (`BIP011` §6.4: `Iterable.iterator(): Iterator of T` and `Iterator.next(): T`), `for value in foo` infers `value: int` (non-nullable!) on `list of int` and `value: int?` on `list of int?`, rejects nullable iterables (`(list of int)?`) statically, and propagates `BIP007` (`RM002`) `hermetic deterministic` / `borrow` effects and `@PARAM`/`@THIS` element origins through `EffectChecker`.
6. **Direct unboxed bytecode lowering (`RM001`/`RM003`/`RM005`) with exact `Interpreter` parity.**
   `LoweringAnalyzer` (`RM004` / `BIP010-D5`) publishes a converged `ForLoopTarget` per `for` loop. In compiled mode, `JavaArray of T` compiles to an indexed `<X>ALOAD` loop, `java.lang.Iterable of T` / `java.util.Iterator of T` compiles to direct `INVOKEINTERFACE` calls with `RM005` (`BIP011` §6.6)'s single-`INSTANCEOF` boundary check and unboxed primitive `value` local across all 8 JVM primitives (`sum += value` lowers to raw `IADD`), and Babble custom iterables dispatch directly to `$spec` methods — all preserving HotSpot's empty-stack OSR invariant at the loop head.

---

## 2. Motivation, Goals & Non-Goals

### 2.1 Motivation

Today, Babble has only one loop construct: `while test then ( ... )`. Every traversal over a collection or array requires manual index or iterator plumbing:

```babble
;; Today: manual index loop (only works on List/array, easy to forget i += 1)
def i = 0
while i < foo.size() then (
    sum += foo.get(i)
    i += 1
)

;; Today: manual Iterator loop (verbose, leaks `it` into the enclosing scope)
def it = foo.iterator()
while it.hasNext() then (
    sum += it.next()
)
```

As documented in [README.md](../../README.md) (*Planned features* and *lessons from the ray tracer*), this creates three recurring friction points:

1. **Boilerplate and counter bugs.** Manual `i += 1` increments are easy to forget, and `set` / `map` collections cannot even be indexed by position.
2. **Scope leakage and closure hazards.** A manual `def it = ...` leaks into the surrounding scope, and a loop-carried variable mutated in a `while` header is shared across closures unless manually copied into a fresh inner `def`.
3. **Missed static optimizations.** A manual `while` loop cannot automatically specialize array traversal to `ARRAYLENGTH` + `<X>ALOAD` or keep the iterator local hidden and unescaped for `BIP007` `borrow` analysis.

With `RM001`–`RM005` landed (8-primitive unboxed lowering, `BIP007-P1` static effects, proxy-free JVM/Java interfaces, modular `BIP010` `TypeChecker` + `LoweringAnalyzer`, and `BIP011` core collections with the parametric `Iterable`/`Iterator` nullability overlay) and `BIP012` establishing a clean predicate-free LL(1)/LL(2) grammar, Babble has every piece needed to make `for ... in ... then (...)` both ergonomic and fast.

### 2.2 Goals and Non-Goals

**Goals:**

* **Zero-ceremony iteration** over `RM005` core collections (`list`, `set`, `sortedset`, `map` views), any `java.lang.Iterable of T`, any bare `java.util.Iterator of T`, any `JavaArray of T`, and user-defined Babble iterables/iterators.
* **Exact static typing and nullability:** `value` gets the exact parametric element type (`int` for `list of int`, `int?` for `list of int?`), with zero casts and full 8-primitive unboxing inside the loop body.
* **Motivating example works cleanly** in both `interpret` and `compile` modes, with zero `@parser::members` semantic predicates (`BIP012` SLL/LL gate preserved).
* **Closure-safe per-iteration binding** and full `BIP007` effect/alias soundness.
* **Zero runtime dependencies outside `java.base`** (`BIP006` §3, `RuntimeBoundaryTestCase`).

**Non-Goals (explicitly scoped to follow-on proposals / phases; see §8):**

* **`yield` generator functions:** Coroutine/state-machine compilation for `yield` is a substantial standalone feature. `BIP013` establishes the `Iterable`/`Iterator` consumption protocol that future `yield` functions will target.
* **`break` and `continue`:** Tracked as an orthogonal control-flow feature applying uniformly to both `while` and `for` loops (§8.1).
* **Destructuring loop variables (`for (k, v) in m.entrySet() then (...)`):** Composes naturally once destructuring `def` (`BIP005-D3`) lands (§8.2).
* **Range operator syntax (`0..10`) and `range(...)`:** Deferred to `std/` (`BIP013-D7`) so `BIP013` stays focused on `for` and the iteration protocol.

---

## 3. Syntax, Grammar & `Formatter`

### 3.1 `for` Expression Grammar (`Babble.g4`, `BIP013-D1`)

`Babble.g4` adds a strictly LL(1) `# forExpression` alternative directly alongside `# whileExpression`, using the same `THEN NL* block` structure as `if` and `while`:

```antlr
| FOR name=ID (':' type)? IN NL* iterable=expression THEN NL* forBlock=block   # forExpression
```

with reserved lexer tokens:

```antlr
FOR: 'for';
IN:  'in';
```

**Lexical & disambiguation details:**

* **Both `for` and `in` are reserved keywords** (`BIP013-D1`, matching `if`, `while`, `then`, `else`, `def`, `return`), keeping `Babble.g4` 100% predicate-free (`BIP012` SLL/LL gate).
* **`memberName` compatibility (`BIP011-D11`, `BIP012-D7`):** `IN` is included in `memberName` (`memberName: ID | coreTypeKeyword | effectKeyword | RECORD | OF | ON | IN;`), so standard Java fields/methods named `in` — notably `java.lang.System.in` — remain directly accessible after `.` / `?.` (and in named-arg labels / `effectTarget`).
* **Newline continuation rules:** Consistent with `Babble.g4`'s newline discipline (`CLAUDE.md` §Architecture), `NL*` is permitted after `in` and after `then` (both require a following expression/block), and never before `(` in a call. Both single-line and multi-line headers parse unambiguously:
  ```babble
  for value in foo then (
      sum += value
  )

  for value: double in
      foo then (
      sum += value
  )
  ```
* **Precedence:** Placed in the prefix control-flow group alongside `if` and `while`, so `for value in foo then ( ... )` is an expression that can appear anywhere an expression is valid (top level, inside blocks, as a `def` initializer, or as a function return value).

### 3.2 Parameterized Static Factory Calls (`(list of int).of(1, 2, 3)`, `BIP013-D2`)

In `RM005` (`BIP011`), core collections expose mutable constructors (`ArrayList`, `LinkedHashSet`, etc.), while JDK static factory methods (`java.util.List.of`, `Set.of`, `Map.of`, `List.copyOf`) return unmodifiable collections. Under `BIP012-D4`, `expr of T` (`# typeApplyExpr`) and `coreCollectionKeyword` (`# coreCollection`) are already **first-class expressions** in `Babble.g4`, and `of` is already in `memberName`!

Consequently, supporting static member selection on both bare and specialized core collections and generic Java/Babble classes requires **zero new parser predicates**:

* `list.of(1, 2, 3)` parses as `# selector(# coreCollection(list), of)` called with `(1, 2, 3)` — inferring `E = int` via `CallSolver`.
* `(list of int).of(1, 2, 3)` parses as `# selector(# parenExpr(# typeApplyExpr(# coreCollection(list), int)), of)` called with `(1, 2, 3)` — carrying the explicit type argument `[int]` from the specialized `JavaClassValue` directly into `CallSolver`!
* `(java.util.List of int).of(1, 2, 3)` works identically for any generic Java or Babble class (`BIP013-D2`).
* Even `def IntList = list of int` followed by `IntList.of(1, 2, 3)` works seamlessly because `IntList` holds the specialized `JavaClassValue` type (`BIP012-D4`)!

| Expression (`BIP012` Syntax) | Meaning | Result Type |
|---|---|---|
| `list of int (1, 2, 3)` | `RM005` mutable default constructor (`ArrayList`) | `list of int` |
| `list(1, 2, 3)` | `RM005` inferred mutable constructor (`ArrayList`) | `list of int` |
| `(list of int).of(1, 2, 3)` | `java.util.List.<Integer>of(1, 2, 3)` (unmodifiable, explicit `E = int`) | `list of int` |
| `list.of(1, 2, 3)` | `java.util.List.of(1, 2, 3)` (unmodifiable, inferred `E = int`) | `list of int` |
| `(list of int).of()` | `java.util.List.<Integer>of()` (empty unmodifiable `list of int`) | `list of int` |
| `(set of string).of("a", "b")` | `java.util.Set.<String>of("a", "b")` | `set of string` |
| `(map of (string, int)).of("k", 1)` | `java.util.Map.<String, Integer>of("k", 1)` | `map of (string, int)` |
| `(java.util.List of int).of(1, 2, 3)` | General Java generic class parameterized static selection | `list of int` |

*(If `BIP013` were ever implemented ahead of `BIP012`, the same semantics apply via `list(int).of(1, 2, 3)` / `java.util.List(int).of(1, 2, 3)`.)*

And because `RM005` (`BIP011` §6.4)'s JDK Nullability Overlay already specifies `List.of(e: T...): list of T` with `T <: any` (non-null), `(list of int?).of(1, null)` is rejected at compile time (matching `List.of`'s runtime `NullPointerException` contract), while `(list of int).of(1, 2, 3)` yields non-null `list of int` with zero runtime checks.

### 3.3 `Formatter` and REPL (`BabbleSession.classify`)

* **`Formatter`:**
  * Single space around `for`, `in`, and `then`: `for value in foo then (`.
  * With type annotation: `for value: int in foo then (` (no space before `:`, one space after `:`, matching `def` and parameter declarations).
  * `forBlock` uses the exact same block-indentation anchor as `WhileExpressionContext` (`Formatter.java`: `parent instanceof BabbleParser.ForExpressionContext`).
* **`BabbleSession.classify`:**
  * Incomplete `for` inputs (`for value in foo then (`) end at EOF with an unclosed block or missing `then`, so `ParseFailure.atEnd` naturally classifies them as `INCOMPLETE` for multi-line REPL entry, matching `while`.

---

## 4. Iteration Protocol & Supported Iterables

Consistent with `BIP002` (operator methods + two-way Java interop), `RM003` (proxy-free Java interface implementation), and `RM005` (`BIP011` zero-wrapper `java.util` collections), `BIP013` defines a **three-tier static iteration protocol** resolved at compile time by `TypeChecker` / `LoweringAnalyzer`:

### 4.1 Tier 1: `java.lang.Iterable of T` & `java.util.Iterator of T` (`BIP013-D3`)

Any non-nullable type $S$ such that $S \mathrel{<:} \texttt{java.lang.Iterable of } T$ or $S \mathrel{<:} \texttt{java.util.Iterator of } T$ is iterable with element type $T$. This immediately covers:

* All `RM005` sequence and set collections: `list of T`, `set of T`, `sortedset of T` (`ArrayList`, `LinkedList`, `LinkedHashSet`, `TreeSet`, `List.of(...)`, etc.).
* All `map of (K, V)` / `sortedmap of (K, V)` views: `m.entrySet()` (yields `entry of (K, V)`), `m.keySet()` (yields `K`), `m.values()` (yields `V`).
* Any bare `java.util.Iterator of T` directly (consuming one-shot iterators in place, `BIP013-D3`).
* Any other JDK or third-party `java.lang.Iterable<T>` or `java.util.Iterator<T>` (`ArrayDeque of T`, `PriorityQueue of T`, `java.nio.file.Path`, `DirectoryStream of Path`, etc.).
* Any Babble class or interface declaring `implements java.lang.Iterable of T` or `implements java.util.Iterator of T` (which `RM003-P3` compiles directly to a JVM `implements` bridge with zero `Proxy` allocation, making the Babble class iterable from **both** Babble and Java!).

**Element type extraction:** `JavaTypeBridge` projects $S$ onto its `java.lang.Iterable` or `java.util.Iterator` supertype to obtain the instantiated type argument $T$ (through `RM005` / `BIP011` §6.4's parametric-nullness overlay). For a raw Java `Iterable`/`Iterator` with no type argument, $T = \texttt{any?}$.

### 4.2 Tier 2: `JavaArray of T` (Java Arrays)

Any non-nullable `JavaArray of T` (`int[]`, `double[]`, `byte[]`, `char[]`, `string[]`, `Object[]`, etc.) is iterable with element type $T$:

* For all 8 primitive arrays (`byte[]`..`char[]`), $T$ is the non-nullable core scalar (`byte`..`char`) — **zero boxing** and **zero `Iterator` allocation**!
* For reference arrays (`string[]` from `"a,b,c".split(",")` under `RM005`'s overlay $\to$ `JavaArray of string`), $T$ is `string` (or `T?` for unannotated Java reference arrays).
* Lowered directly to an indexed `0 .. arr.length - 1` loop (`ARRAYLENGTH` + `<X>ALOAD`), matching Java's enhanced `for` loop on arrays.

### 4.3 Tier 3: Babble Custom Iterable & Iterator Protocol (`BIP013-D9`)

A Babble `class`, `record`, `object`, or `interface` does **not** have to `implements java.lang.Iterable of T` solely to be used in a Babble `for` loop (just as a Babble class does not have to implement a Java interface to overload `+` via `plus` in `BIP002`!).

A non-nullable Babble `Instance` type $S$ (not already implementing `java.lang.Iterable` or `java.util.Iterator`) satisfies the **Babble iteration protocol** if either:

* **Iterable form:** $S$ has a public, zero-argument callable member `iterator()` returning a non-nullable type $I$, where $I$ has public zero-argument `hasNext(): bool` and `next(): T`; or
* **Iterator form (`BIP013-D3`):** $S$ itself directly has public zero-argument `hasNext(): bool` and `next(): T`.

```babble
def Range = record (def start: int, def stop: int) (
    def iterator = fn () -> (
        def cur = this.start
        object (
            def hasNext = fn (): bool -> ( cur < this.stop )
            def next = fn (): int -> (
                def v = cur
                cur += 1
                v
            )
        )
    )
)

def sum = 0
for i in Range(1, 4) then (
    sum += i
)
assert(sum == 6)
```

**Major performance benefit of Tier 3:** Because `hasNext(): bool` and `next(): int` are statically resolved Babble methods, `RM001`/`RM005` compile them with `$spec` unboxed descriptors `()Z` and `()I` (and likewise for all 8 JVM primitives). Thus a custom Babble primitive iterator runs **completely unboxed** — something `java.util.Iterator<Integer>` cannot achieve due to JVM generic erasure!

And if the author *also* wants the class to be passed to Java APIs expecting `java.lang.Iterable<Integer>`, they simply add `implements java.lang.Iterable of int` and `RM003-P3` generates the direct JVM bridge automatically.

### 4.4 Union Iterables

If the iterable expression has a union type $A \mid B$ (e.g., `list of int | set of int` or `list of int | JavaArray of int`):

* If **every** member of the union is non-null and iterable with element types $T_1, \dots, T_k$, then the union is iterable with element type $\text{join}(T_1, \dots, T_k)$ (e.g., `list of int | set of int` yields `int`; `list of int | list of double` yields `double` per `TypeAlgebra.join`).
* If any union branch is `null` (`(list of int)?`) or non-iterable (`list of int | string`), `TypeChecker` rejects it at compile time (`Type error : cannot iterate over nullable type (list of int)?` or `Not iterable : <type>`).

### 4.5 Explicit Non-Iterables (`string`, `map`) & Deferred `range` (`BIP013-D4`, `D5`, `D7`)

* **`string` is not directly iterable (`BIP013-D4`):** Iterating over a string is inherently ambiguous (UTF-16 `char` code units vs Unicode code points vs grapheme clusters / runes). Matching Java, `for c in "abc"` is rejected statically (`Not iterable : string`); callers explicitly pick the representation via methods or helpers (e.g., `"abc".toCharArray()`, or future `std/` helpers returning the desired `Iterable`).
* **`map of (K, V)` is not directly iterable (`BIP013-D5`):** Matching Java, `for e in m` is rejected statically (`Not iterable : map of (K, V)`); callers iterate `m.entrySet()`, `m.keySet()`, or `m.values()` explicitly. Direct map iteration may be revisited once destructuring `for (k, v) in m` (`BIP005-D3`) lands.
* **`range(...)` deferred to `std/` (`BIP013-D7`):** Built-in numeric `range` helpers are deferred to `std/` rather than added as keywords or implicits in `BIP013`.

---

## 5. Semantics, Scoping & Type Checking

### 5.1 Runtime Evaluation Semantics

For `for name [: U] in iterable then ( body )`:

1. **Evaluate `iterable` exactly once**, in the enclosing scope (before the loop variable `name` is in scope).
2. **Acquire the iteration handle once** (array reference + length for `JavaArray`, `iterable.iterator()` for `Iterable` / Babble iterable, or `iterable` directly for `Iterator` / Babble iterator).
3. **Initialize `result = null`.**
4. **Loop:**
   * Check whether another element exists (`idx < len` or `it.hasNext()`). If not, exit the loop and return `result`.
   * Fetch the next element (`arr[idx]` or `it.next()`), applying any boundary check (`RM005` / `BIP011` §6.6) or widening conversion (`T -> U`) if an explicit annotation widens `int -> double` (etc.).
   * **Open a fresh block scope** for this iteration, declare and initialize `name` with the element value.
   * Evaluate `body` in that scope, assigning its result to `result` (applying any `NumericCoercion` if `forBlock` widens).
   * Repeat.
5. **Return `result`** (which is `null` if the iterable was empty, or the last iteration's `body` value).

**`return` inside `for`:** Just like `while`, `return expr` inside `forBlock` returns from the enclosing function immediately (subject to `BlockScopes` `returnTarget` checks).

### 5.2 Scoping, Shadowing & Closure Capture (`BIP013-D6`)

Babble's block-scoping discipline (`CLAUDE.md` §Block scope) applies cleanly to `for`:

* **`BlockScopes`:**
  * `iterable` is visited in the **enclosing scope** (NOT the `forBlock` scope). Therefore `for x in x then ( ... )` reads the outer `x` as the iterable and binds a new inner `x` inside `forBlock` — no self-shadowing hazard!
  * `forBlock` is a child `ScopeNode` whose declared names include `name` (pre-initialized on entry to each iteration, like a function parameter) plus any top-level `def`s inside `forBlock`.
  * Declaring `def <name> = ...` at the top level of `forBlock` is a compile-time error (`Name already defined : <name>`), matching function parameters vs function-body `def`s.
  * `name` is **not** visible after the `for` expression ends.
* **Per-iteration fresh binding (closure safety):**
  * If a function/class/object literal inside `forBlock` captures `name`, each iteration allocates a fresh `Cell` (or `ByteCell`..`CharCell` per `RM001`/`RM005`) in `CodeGenerator`, and a fresh child `Namespace` in `Interpreter`:
    ```babble
    def fns = list of (fn (): int) ()
    for v in (list of int).of(1, 2, 3) then (
        fns.add(fn (): int -> ( v ))
    )
    assert(fns.get(0)() == 1 and fns.get(1)() == 2 and fns.get(2)() == 3)
    ```
    Every closure captures its own iteration's `v` (`1`, `2`, `3`) — never the final value `3`!
* **Read-only loop variable (`BIP013-D6`):**
  * `name` is **read-only**: assigning `value = ...` or `value += ...` inside `forBlock` is rejected at compile time with `Cannot assign to loop variable : value`.
  * Rationale: (1) prevents the classic beginner mistake of thinking `value += 1` modifies the list element in place, (2) guarantees `name`'s static type never widens mid-body, and (3) keeps `Narrowing` and `EffectChecker` alias origins for `name` exact throughout each iteration. (If a user wants a mutable local copy, `def x = value` is one line.)

### 5.3 Type Checking (`TypeChecker`, `TypeAlgebra`, `Narrowing`, `SlotInference`)

Integrating `for` into `RM004`'s modular `org.babblelang.analysis` architecture (`BIP010`) requires zero new type kinds and reuses existing collaborators cleanly:

1. **Narrowing eviction (`Narrowing`):**
   Just like `visitWhileExpression`, before checking `forBlock`, evict from `TypeEnv` all outer locals assigned inside `forBlock` (`narrowing.assignedLocals(ctx)`), because earlier iterations may mutate those locals before later iterations read them!
   * Note: `iterable` is synthesized **before** loop-body eviction (since `iterable` runs once before the loop starts), so narrowing before the loop (e.g. `if xs != null then ( for v in xs then ( ... ) )`) naturally applies to `xs`!
2. **Iterable resolution:**
   Synthesize `Type iterType = synth(ctx.iterable)`.
   * If `iterType == Type.ERROR`, bind `name` to `Type.ERROR` and check `forBlock` to avoid cascading diagnostics.
   * If `algebra.admitsNull(iterType)`, emit `Type error : cannot iterate over nullable type <iterType>`.
   * Otherwise resolve the iteration protocol (§4) to obtain `(ForKind, elemType)`:
     * `JavaArray of T` $\to$ `elemType = T`
     * `iterType <: java.lang.Iterable of T` or `java.util.Iterator of T` $\to$ `elemType = T` (via `JavaTypeBridge` + `RM005` overlay)
     * Babble custom iterable/iterator (`iterator()` / `hasNext(): bool` / `next(): T`) $\to$ `elemType = T` (via `MemberTypes` + `CallSolver`)
     * Union of valid iterables $\to$ joined `elemType`
     * Anything else $\to$ emit `Not iterable : <iterType>`.
3. **Optional explicit loop variable annotation (`for name: U in iterable`):**
   * If `: type` is present, resolve `U = annotations.resolve(ctx.type())`.
   * Check `subsumes(ctx.iterable, elemType, U)` (allowing numeric widening like `for x: double in list.of(1, 2, 3)` and recording `NumericCoercion` on `ctx`!).
   * Bind `name` to `U`; otherwise bind `name` to `elemType`.
4. **Loop body & result type:**
   Identical to `visitWhileExpression`:
   * Type `forBlock` with `blockExpected = (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null` and `role`.
   * Join post-loop `TypeEnv` with pre-loop `TypeEnv` (`TypeEnv.joinBranches(beforeLoop, env, beforeLoop)` — because the loop may run 0 times!).
   * If `expected != null && expected != Type.ERROR && role.isUsed()`: check `subsumes(ctx.forBlock, bodyType, expected)` and verify `algebra.assignable(Type.NULL, expected)` (else `Type error : expected <expected>, got null`).
   * Otherwise compute `joined = algebra.join(bodyType, Type.NULL)`: if unused (`!role.isUsed()`), return `Type.NULL`; else coerce `ctx.forBlock` to `joined` and return `joined`.

### 5.4 Type Checking Static Member Selection on Specialized Types (`BIP013-D2`)

When `MemberTypes` resolves `.member` on a `JavaClassValue` or `ClassValue` (including a `RM005` core collection keyword `list`..`entry` or a `BIP012-D4` specialized `list of int` / `java.util.List of int`):

1. Map any core collection keyword (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`) to its underlying `JavaClassValue` (`java.util.List`, `java.util.Set`, `java.util.SortedSet`, `java.util.Map`, `java.util.SortedMap`, `java.util.Map.Entry`), preserving any explicit type arguments carried by `expr of T`.
2. Look up the static member `member` via `JavaTypeBridge` (with `RM005`'s JDK Nullability Overlay).
3. If the receiver carries explicit type arguments (`(list of int).of(...)` or `(java.util.List of int).of(...)`), `CallSolver` binds the static method's type parameters from those arguments up front (checking arity and upper bounds such as `E <: any` non-null on `List.of`) before checking value arguments `(1, 2, 3)`.
4. If no explicit type arguments were supplied (`list.of(1, 2, 3)`), `CallSolver.solveJava` infers `E = int` from `(1, 2, 3)` or contextual expected type (`def foo: list of int = list.of()`).

---

## 6. Static Effects & Alias Tracking (`BIP007` / `RM002`)

`EffectChecker` (`RM002`) integrates `for` loops cleanly alongside `evalWhile`:

1. **Effect attribution by iterable tier:**
   | Iterable Tier | Iteration Overhead Effects | Receiver Modifier |
   |---|---|---|
   | `JavaArray of T` | `pure deterministic nothrow` | `borrow` (read-only array indexing) |
   | `RM005` core collections (`list`, `set`, `sortedset`, `map` views) | `hermetic deterministic` *(not `nothrow` unless unaliased/unmodified, due to potential `ConcurrentModificationException` on concurrent mutation)* | **`borrow`** (non-`mut`!) |
   | Bare `java.util.Iterator of T` | `hermetic deterministic` on JDK core-collection iterators (otherwise per `HostEffects` / `axiom` / `@Pure`) | **`mut`** on the iterator handle (advancing `next()` mutates iterator state!) |
   | Other `java.lang.Iterable of T` | Inherited from `HostEffects` / `axiom` / `@Pure` annotations on `iterator()`, `hasNext()`, `next()` (defaults to `impure` if unannotated third-party Java) | Per host contract |
   | Babble custom iterable / iterator | Union of inferred/declared `EffectSet` of `iterator()` (if iterable), `hasNext()`, and `next()` | Inferred/declared on `iterator()`/`hasNext()`/`next()` |

2. **Element alias origin propagation:**
   * Let `iterOrigin = eval(ctx.iterable)`.
   * If `elemType` is a core scalar or immutable `record` (like `int` in `list of int`), `value` has `Origin.IMMUTABLE`.
   * Otherwise, `value` inherits a derived sub-reference origin from `iterOrigin` (so inside `for item in items then ( item.mutate() )`, `EffectChecker` accurately attributes `mut` back to `items`'s `@PARAM(i)` / `@THIS` origin!).

3. **Fixed-point body evaluation (`evalFor`):**
   * `evalFor` mirrors `evalWhile` (fixed-point over `env` up to convergence so loop-carried local reassignments and alias merges reach their least upper bound).
   * The loop's total effects are the union of `iterable`'s effects, the iteration protocol's effects, and `forBlock`'s effects.

4. **Concurrent-mutation protection (`BIP013-D8`):**
   * Because iterating a `RM005` collection holds a live `borrow` on `iterable` for the duration of `forBlock`, mutating the same collection inside its own `for` loop (`for x in xs then ( xs.add(x) )`) violates `mut` exclusivity against the active iterator `borrow` in effect-checked (`pure`/`hermetic`) functions — catching `ConcurrentModificationException` bugs statically while preserving unchecked Java idioms such as `CopyOnWriteArrayList`.

---

## 7. Lowering, Bytecode Generation & Interpreter Parity (`RM001` / `RM003` / `RM005`)

### 7.1 `LoweringAnalyzer` Metadata (`BIP010-D5`)

Following `BIP010-D5`, `TypeChecker` records no bytecode targets; instead, post-convergence `LoweringAnalyzer` inspects each `ForExpressionContext` once and publishes a `ScopeAnalysis.ForLoopTarget`:

* `ForKind`: `ARRAY`, `JAVA_ITERABLE`, `JAVA_ITERATOR`, `BABBLE_ITERABLE`, `BABBLE_ITERATOR`, or `DYNAMIC_UNION` (only when iterating a heterogeneous union like `list of int | JavaArray of int`).
* `PrimitiveKind elemKind`: the unboxed primitive kind of the loop variable `name` (`BYTE`, `SHORT`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `CHAR`, or `NONE` per `RM005` / `BIP011-D13`).
* `NumericCoercion elemCoercion`: optional widening (e.g. `I2D` for `for x: double in list.of(1, 2, 3)`).
* `RuntimeTypeCheck elemCheck`: `RM005` (`BIP011` §6.6) defensive boundary check for erased `Iterator.next()` returns (e.g. `INSTANCEOF java/lang/Integer` for `list of int`).
* For `BABBLE_ITERABLE` / `BABBLE_ITERATOR`: the resolved `BabbleCallTarget` / `InterfaceCallTarget` for `iterator` (if iterable), `hasNext`, and `next` (including `$spec` descriptors!).

### 7.2 Compiled Bytecode Lowering (`CodeGenerator`)

In every lowering below, **the JVM operand stack is empty at `head`** (preserving HotSpot OSR compilation, identical to `CodeGenerator.visitWhileExpression`). When `for` is in statement position (`!used`), no `result` local or `ran` flag is allocated; when its value is used, it reuses the exact `ran`/`result` pattern from `visitWhileExpression`.

#### Case A: `JAVA_ITERABLE` / `JAVA_ITERATOR` (Motivating Example!)

Consider the motivating snippet:

```babble
def foo = (list of int).of(1, 2, 3)
def sum = 0
for value in foo then (
    sum += value
)
assert(sum == 6)
```

* `foo` has type `list of int` (`PrimitiveKind.NONE`, JVM type `java/util/List`).
* `sum` has type `int` (`PrimitiveKind.INT`, unboxed JVM `I` local).
* `value` has type `int` (`PrimitiveKind.INT`, unboxed JVM `I` local — or `IntCell` if captured by a closure inside `forBlock`).
* Generated JVM bytecode for the `for` loop (statement position; for `JAVA_ITERATOR`, the initial `Iterable.iterator()` call is omitted and `foo` is stored directly into `it`):

```
  ALOAD foo
  INVOKEINTERFACE java/lang/Iterable.iterator ()Ljava/util/Iterator;
  ASTORE it
head:                                      ; stack is empty (HotSpot OSR!)
  ALOAD it
  INVOKEINTERFACE java/util/Iterator.hasNext ()Z
  IFEQ end
  ALOAD it
  INVOKEINTERFACE java/util/Iterator.next ()Ljava/lang/Object;
  DUP
  INSTANCEOF java/lang/Integer             ; RM005 (BIP011 §6.6) single-check boundary guard
  IFNE ok                                  ; (rejects both null and raw-Java heap pollution!)
  LDC "int"
  SWAP
  INVOKESTATIC org/babblelang/runtime/JavaTypes.unexpectedType (Ljava/lang/String;Ljava/lang/Object;)Lorg/babblelang/runtime/BabbleException;
  ATHROW
ok:
  CHECKCAST java/lang/Integer
  INVOKEVIRTUAL java/lang/Integer.intValue ()I
  ISTORE value                             ; unboxed int local!
  ILOAD sum
  ILOAD value
  IADD                                     ; raw unboxed IADD!
  ISTORE sum
  GOTO head
end:
```

Zero `invokedynamic`, zero `Overloads` reflection, zero boxing of `sum` or `value` in the body, and a single predicted `INSTANCEOF` at the erased Java boundary!

#### Case B: `JavaArray of T`

For `for v in arr then ( ... )` where `arr: JavaArray of int`:

```
  ALOAD arr
  ASTORE $arr
  ALOAD $arr
  ARRAYLENGTH
  ISTORE $len
  ICONST_0
  ISTORE $idx
head:                                      ; stack is empty
  ILOAD $idx
  ILOAD $len
  IF_ICMPGE end
  ALOAD $arr
  ILOAD $idx
  IALOAD                                   ; unboxed IALOAD (or BALOAD/SALOAD/LALOAD/FALOAD/DALOAD/CALOAD/AALOAD)
  ISTORE v
  <body...>
  IINC $idx, 1
  GOTO head
end:
```

Zero heap allocation, zero boxing, identical to `javac`'s enhanced-for bytecode on primitive arrays!

#### Case C: `BABBLE_ITERABLE` / `BABBLE_ITERATOR`

For a custom Babble iterable or iterator (not implementing `java.lang.Iterable` / `java.util.Iterator`):

* `iterator()` (for `BABBLE_ITERABLE`) invokes the resolved `BabbleCallTarget` / `InterfaceCallTarget` (direct `INVOKEVIRTUAL` / `INVOKEINTERFACE` or `invokedynamic` if polymorphic).
* `hasNext()` invokes the `$spec` `()Z` entry point directly — no `Boolean` boxing!
* `next()` invokes the `$spec` `()I` / `()J` / `()D` / ... entry point directly — **no primitive boxing at all**!

#### Case D: `RandomAccess` / `ArrayList` Fast-Path (Optional Future Optimization)

For `list of T` statically typed as `ArrayList of T` (or via a `RandomAccess` guard), `CodeGenerator` could optionally lower to an indexed `0 .. list.size() - 1` loop calling `list.get(i)` to avoid allocating `ArrayList$Itr`. Because HotSpot C2 already scalar-replaces short-lived `ArrayList$Itr` objects in most hot loops, `BIP013` starts with the simpler, universally correct `Iterable.iterator()` lowering (preserving fail-fast `ConcurrentModificationException` semantics) and leaves `RandomAccess` specialization as a benchmark-driven optimization.

### 7.3 `Interpreter` Parity

`Interpreter.visitForExpression(BabbleParser.ForExpressionContext ctx)` implements the exact same semantics on the parse tree:

1. Evaluates `Object iterable = visit(ctx.iterable)`.
2. Dispatches based on runtime/analyzed kind:
   * Java array (`iterable.getClass().isArray()`): loops `0 .. Array.getLength(iterable) - 1`, reading `Array.get(iterable, i)`.
   * `java.lang.Iterable<?>` or `java.util.Iterator<?>`: obtains `Iterator<?>` (`iterable instanceof Iterator<?> ? (Iterator<?>) iterable : ((Iterable<?>) iterable).iterator()`), looping `while (it.hasNext()) { Object raw = it.next(); ... }`, applying `JavaTypes.checkNotNull` / `Types.check` if `ForLoopTarget` specifies a boundary check, and `Values.fromJava(raw)` / numeric widening if marked.
   * Babble custom iterable/iterator (`BabbleInstance`): invokes `iterator` (if iterable), then loops on `Truth.test(hasNext)` / `next`.
3. On each iteration, creates a fresh child `Namespace` for `forBlock`, binds `name` to the element value, evaluates `forBlock` inside that namespace (restoring `namespace` in `finally`), and updates `result` (widening if marked).
4. Returns `result` (`null` if 0 iterations).

Because `Interpreter` uses only `java.lang.Iterable`, `java.util.Iterator`, `java.lang.reflect.Array`, and existing `org.babblelang.runtime` helpers, `RuntimeBoundaryTestCase` (`java.base`-only runtime boundary) stays 100% green with zero new runtime dependencies.

---

## 8. Composition with Follow-On Features

`BIP013` ships with `break [expr]` and `continue` (`BIP013-D10`) and interlocks cleanly with three follow-on language features:

1. **Destructuring loop variables (`BIP005-D3`):** Once `def (k, v) = e` lands for `HasMembers` and `entry of (K, V)`, the `for` header generalizes naturally from `name=ID` to `for (k, v) in m.entrySet() then ( ... )`.
2. **`yield` generator functions:** A future `yield` BIP can compile generator functions into lightweight `BabbleInstance`s implementing `java.lang.Iterable of T` / `java.util.Iterator of T` (or Tier-3 `$spec` iterators for primitive generators), plugging directly into `for ... in ... then (...)` with zero changes to `BIP013`.
3. **Standard library (`std/`) combinators:** Lazy `map`, `filter`, `flatMap`, `zip`, `enumerate`, `take`, and `range` combinators in `std/` will accept and return `Iterable of T` / `Iterator of T` directly (paired with `BIP017` `fn with f` for effect-polymorphic HOF combinators).

---

## 9. Open Questions & Decisions (`BIP013-Q1`–`BIP013-Q10` → `BIP013-D1`–`BIP013-D10`)

* **`BIP013-Q1` → `BIP013-D1` (`for` and `in` keyword status):** *Should `for` be reserved and `in` contextual, or should both be reserved?*
  **Decided (`BIP013-D1`):** Reserve both `FOR: 'for';` and `IN: 'in';` as lexer keywords, and include `IN` in `memberName` (`BIP011-D11`, `BIP012-D7`) so member accesses after `.` / `?.` such as `java.lang.System.in` remain valid while keeping `Babble.g4` 100% predicate-free (§3.1).
* **`BIP013-Q2` → `BIP013-D2` (Parameterized static factory selection scope):** *Should parameterized static method selection work only on `RM005` core collection keywords, or also on general Java/Babble generic classes?*
  **Decided (`BIP013-D2`):** Support static method selection on **both** `RM005` core collection keywords (`(list of int).of(1, 2, 3)`, `list.of(1, 2, 3)`, `(set of string).of(...)`, `(map of (string, int)).of(...)`) and general Java/Babble generic classes (`(java.util.List of int).of(1, 2, 3)`), naturally leveraging `BIP012-D4`'s first-class `expr of T` type application (§3.2, §5.4).
* **`BIP013-Q3` → `BIP013-D3` (Bare `java.util.Iterator of T` in `for`):** *Should `for x in it then (...)` accept a `java.util.Iterator of T` directly, or require an `Iterable of T`?*
  **Decided (`BIP013-D3`):** Accept bare `java.util.Iterator of T` directly in `for` (as well as Babble objects exposing `hasNext(): bool` and `next(): T` directly without `iterator()`), consuming one-shot iterators in place (§4.1, §4.3).
* **`BIP013-Q4` → `BIP013-D4` (Iterating over `string`):** *Should `for c in "hello" then (...)` iterate `char`s directly, or match Java?*
  **Decided (`BIP013-D4`):** Do **not** make `string` directly iterable — string iteration is ambiguous across UTF-16 `char` code units, Unicode code points, and runes/grapheme clusters. Match Java (`Not iterable : string`) and rely on explicit methods or helpers (e.g., `s.toCharArray()` or future `std/` helpers) to provide the desired `Iterable` (§4.5).
* **`BIP013-Q5` → `BIP013-D5` (Iterating over `map of (K, V)` directly):** *Should `for e in m then (...)` iterate `entry of (K, V)`, keys, or require explicit views?*
  **Decided (`BIP013-D5`):** Require explicit `m.entrySet()`, `m.keySet()`, or `m.values()` for now, revisiting once destructuring `for (k, v) in m` (`BIP005-D3`) lands (§4.5).
* **`BIP013-Q6` → `BIP013-D6` (Loop variable mutability):** *Should the loop variable `value` in `for value in foo then (...)` be read-only or reassignable?*
  **Decided (`BIP013-D6`):** Read-only (`Cannot assign to loop variable : value`), preventing accidental confusion with in-place collection mutation and keeping narrowing/alias tracking simple (§5.2).
* **`BIP013-Q7` → `BIP013-D7` (Built-in `range` helper):** *Should `BIP013` include a built-in `range(...)` helper or defer it to `std/`?*
  **Decided (`BIP013-D7`):** Defer `range(...)` to `std/`, keeping `BIP013` focused on `for` and the iteration protocol (§4.5, §8).
* **`BIP013-Q8` → `BIP013-D8` (Static check on mutating an iterated collection & `BIP016` alignment):** *How should mutating `foo` inside `for v in foo then ( foo.add(v) )` be checked?*
  **Decided (`BIP013-D8`):** Enforce via `EffectChecker`'s `@BORROWED`/`@MUTABLE` exclusivity in pure-by-default (`@HERMETIC`) functions (`RM008` / `BIP016`), while preserving unchecked Java idioms such as `CopyOnWriteArrayList` inside `fn io` (§6). Iterating over `frozen list of T` / `frozen set of T` binds `v` with `frozen` element type when `T` is mutable.
* **`BIP013-Q9` → `BIP013-D9` (Custom Babble iterable protocol):** *Should Babble support duck-typed custom iterables (`iterator()` / `hasNext()` / `next()`), or require `implements java.lang.Iterable of T`?*
  **Decided (`BIP013-D9`):** Support **both** (§4.3): `implements java.lang.Iterable of T` / `java.util.Iterator of T` for seamless two-way Java interop (`RM003-P3`), plus duck-typed Babble `iterator()` / `hasNext(): bool` / `next(): T` for ergonomics and unboxed `$spec` primitive iterators.
* **`BIP013-Q10` → `BIP013-D10` (`break` and `continue` loop control):** *Should `break` and `continue` ship alongside `for` loops, and should `break` carry an optional value expression?*
  **Decided (`BIP013-D10`):** Ship `break`, `break expr`, and `continue` uniformly for both `while` and `for` loops (no loop labels yet).
  * **Grammar (`Babble.g4`):** Reserve `BREAK: 'break';` and `CONTINUE: 'continue';` and exempt both in `memberName`; parse `BREAK expression? # breakExpression` and `CONTINUE # continueExpression` (100% predicate-free `SLL`, since `NL`/`RPAR`/`COMMA`/`EOF` are not in `FIRST(expression)`).
  * **Typing & semantics:** Both are only valid inside an enclosing `while` or `for` loop (not crossing function/class/object boundaries; static error otherwise) and have bottom/never control-flow behavior like `return`. `break expr` exits the enclosing loop yielding `expr`'s value (contributing `typeof(expr)` to the loop expression's join type); bare `break` yields `null` (contributing `null`); `continue` jumps to the next iteration test/step (advancing the `for` array index / iterator cleanly).
  * **Dual-mode execution:** `Interpreter` uses `BreakException(value)` / `ContinueException` (mirroring `ReturnException`); `CodeGenerator` stores the optional break value into the loop's result local (with unboxing/widening parity) and emits `GOTO end` / `GOTO step` while keeping the operand stack empty at loop headers and exits.

---

## 10. Phased Delivery Plan (`BIP013-P1`–`BIP013-P4`) & Verification

`BIP013` builds directly on landed `RM001`–`RM008` (`BIP010` + `BIP011` + `BIP012` + `BIP016`).

Every phase keeps `mvn -B package` (`interpret` + `compile` with `CheckClassAdapter`), `BatestTestCase` (`BIP009`), `RuntimeBoundaryTestCase`, `FormatterTestCase`, `BIP012`'s SLL/LL predicate-free parser gate, and `examples/raytracer-argon/scene.ppm` green:

| Phase | Deliverables |
|---|---|
| **`BIP013-P1`: Grammar, `Formatter`, REPL & Scope Analysis** | Reserve `FOR`, `IN`, `BREAK`, and `CONTINUE` in `Babble.g4` with `IN`/`BREAK`/`CONTINUE` in `memberName` (`BIP013-D1`, `D10`) and add strictly LL(1)/`SLL` `# forExpression`, `# breakExpression`, and `# continueExpression`; update `Formatter` (spacing, newlines, indentation, round-trip checks) and verify `BabbleSession.classify` multi-line REPL handling; implement `BlockScopes` loop-target stack (for `while`/`for`, rejecting `break`/`continue` outside loops or across `fn` boundaries), `BlockScopes.visitForExpression` (outer-scope iterable, fresh `forBlock` scope with read-only loop variable per `BIP013-D6`, duplicate-name check, `returnTarget` propagation), and `Resolver` (loop variable slot + per-iteration capture cell allocation). |
| **`BIP013-P2`: Core Typing, `break`/`continue`, Specialized Static Factories & `Iterable`/`Iterator`/`JavaArray` Backends** | Implement `visitForExpression`, `visitBreakExpression`, and `visitContinueExpression` in `TypeChecker`, `Narrowing` (`assignedLocals` eviction), and specialized static member selection (`(list of int).of(...)`, `list.of(...)`, `(java.util.List of int).of(...)`, `BIP013-D2`) in `MemberTypes`/`CallSolver`; add `ForLoopTarget` to `ScopeAnalysis` and `LoweringAnalyzer` (`BIP010-D5`); implement `Interpreter` and `CodeGenerator` lowering for `while`/`for` `break [expr]`/`continue`, `java.lang.Iterable of T`, bare `java.util.Iterator of T` (`BIP013-D3`) (direct `INVOKEINTERFACE` + `RM005` boundary check + 8-primitive unboxed `value` local/`*Cell`), and `JavaArray of T` (`ARRAYLENGTH` + `<X>ALOAD`). **Milestone:** the motivating example + `break`/`continue` run end-to-end in both modes! |
| **`BIP013-P3`: Babble Custom Iterable/Iterator Protocol & `BIP016` Effects** | Add Tier-3 duck-typed Babble custom iterable & iterator resolution (`iterator()`/`hasNext(): bool`/`next(): T`, `BIP013-D9`) in `TypeChecker` / `LoweringAnalyzer` with `$spec` unboxed primitive dispatch in `CodeGenerator` and parity in `Interpreter`; add `evalFor`, `evalBreak`, and `evalContinue` to `EffectChecker` (`RM008` / `BIP016`: `JavaArray` `@HERMETIC @DETERMINISTIC @NOTHROW`, `RM005` core collection `@HERMETIC @DETERMINISTIC` + `@BORROWED`, bare `Iterator` `mut`, `frozen` iterable element propagation, custom iterable effect propagation, `@PARAM`/`@THIS` element origin propagation, definite-unassignment across `break`/`continue` joins, and `@BORROWED`/`mut` concurrent-mutation enforcement per `BIP013-D8`). |
| **`BIP013-P4`: Systematic Tests, Coverage & Documentation** | Add `src/test/babble/Iterators.ba` (end-to-end differential & format-checked), `.batest` specs under `src/test/batest/control/for_loops.batest` (covering collections, bare `Iterator`, arrays, custom iterables/iterators, `break`, `break expr`, `continue` in both `while` and `for`, closures capturing loop variables, shadowing `for x in x`, empty iterables, expression value / `join` with `null`, explicit type annotations & widening, nullability errors, `Not iterable` including `string` and `map`, read-only assignment error, `java.lang.System.in` compatibility, and `BIP016` effects), `TypeCheckerTestCase` / `FormatterTestCase` / `EffectsTestCase` / `ReplTestCase` tests, and update [README.md](../../README.md) & [CLAUDE.md](../../CLAUDE.md). |

### Verification Criteria

* Dual-mode `mvn -B package` passes all JUnit and `.batest` suites in both `interpret` and `compile` (`CheckClassAdapter`) modes.
* `mvn -q verify -Pbatest` reports zero divergences and maintains the `BIP009` `>= 75%` branch coverage gate across `Interpreter`, `Compiler`, `Shared Front-End`, and `Shared Runtime`.
* `RuntimeBoundaryTestCase` confirms zero front-end or non-`java.base` references in the runtime boundary.
* `-format -check src/test/babble examples` passes cleanly.
* `examples/raytracer-argon/scene.ppm` remains byte-identical.
