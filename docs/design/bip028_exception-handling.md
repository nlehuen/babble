# BIP028: Exception handling (`try`, `catch`, `finally`)

| | |
|---|---|
| **BIP** | 028 |
| **Date** | 2026-11-23 |
| **Status** | Draft (`BIP028-Q1`–`Q10` open; `BIP028-P1`–`P5` planned) |
| **Depends on** | Object model & plain compiled classes ([bip002_object-model.md](bip002_object-model.md), `RM003`, `BIP020-D18`), interactive mode ([bip004_repl.md](bip004_repl.md)), `java.base`-only runtime boundary ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static typing, unions, narrowing & `Type.NOTHING` ([bip008_static-typing.md](bip008_static-typing.md), [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), core types & Java nullability ([bip011_core-types.md](bip011_core-types.md)), predicate-free single-bracket `()` grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops & `break`/`continue` ([bip013_iterators.md](bip013_iterators.md)), ANTLR-free `HIR` & Nanopass optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md), [bip027_tackling-complexity-in-the-compiler.md](bip027_tackling-complexity-in-the-compiler.md)), pure-by-default effects, ownership & HOF forwarding ([bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md), [bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)), self tail calls & `forever` ([bip024_tail-calls.md](bip024_tail-calls.md)), WebAssembly 3.0 `try_table`/`exnref` alignment ([bip025_compile-to-wasm.md](bip025_compile-to-wasm.md)) |
| **Scope** | Expression-oriented `try`, `catch`, `finally`, and `throw` across `Babble.g4`, `Formatter`, REPL `BabbleSession.classify`, `BlockScopes`/`Resolver`/`TypeChecker`/`Narrowing`/`TailCalls`/`EffectChecker`/`LoweringAnalyzer`, direct JVM `Throwable` propagation & custom Babble exception values in `org.babblelang.runtime`, `Interpreter`, `HirExpr.Try`/`HirExpr.Throw` lowering & JVM `exception_table`/`finally` bytecode emission, `BIP014` Nanopass optimizer soundness, and `AutoCloseable` resource cleanup |

---

## 1. Summary & Motivation

### 1.1 What Babble has today

Runtime failures occur across every Babble program that talks to the outside world or validates input: Java library calls (`Integer.parseInt`, `Files.readString`, `List.get`, `Math.floorDiv`), Babble scalar conversions (`int("bad")`), checked `as` casts, non-null assertions (`e!`), `assert(...)`, array indexing (`ArrayIndexOutOfBoundsException`, `ArrayStoreException`), uninitialized local reads (`Name used before definition`), and dynamic `import(...)`.

Today, Babble has **no structured way to catch exceptions, guarantee cleanup, or throw domain errors**:

1. **No recovery.** Any exception aborts the entire script or REPL evaluation. Calling `java.lang.Integer.parseInt(s)` or `java.nio.file.Files.readString(path)` cannot recover from malformed input or missing files without writing a Java wrapper class.
2. **No guaranteed cleanup.** Code that opens a `java.io.Writer`, `InputStream`, `Socket`, or `ReentrantLock` cannot guarantee `.close()` or `.unlock()` runs when an exception, `return`, `break`, or `continue` exits a block.
3. **No structured throwing from Babble.** Code can only fail via `assert(false, "message")` (which always throws `BabbleException` with an unstructured string) or by delegating to a Java helper.
4. **Inconsistent Java exception wrapping.** Today, [`JavaMethod.invoke`](../../src/main/java/org/babblelang/engine/impl/natives/java/JavaMethod.java), [`JavaLinker`](../../src/main/java/org/babblelang/engine/impl/natives/java/JavaLinker.java), [`JavaClass`](../../src/main/java/org/babblelang/engine/impl/natives/java/JavaClass.java), and [`CallEmitter.emitWrappedJavaInvoke`](../../src/main/java/org/babblelang/compiler/CallEmitter.java) wrap every Java call inside a `try/catch (Throwable)` that routes non-`BabbleException`, non-`Error` throwables into [`JavaTypes.wrapInvocationException`](../../src/main/java/org/babblelang/runtime/JavaTypes.java), constructing `new BabbleException(name + " : " + msg, t)`. Meanwhile, direct JVM instructions emitted by compiled code (such as `ArrayOp` `<X>ALOAD` throwing `ArrayIndexOutOfBoundsException` or `ArrayStoreException`) throw the raw Java exception directly. Catching `java.io.IOException` or `java.lang.NumberFormatException` requires resolving this inconsistency cleanly so Java exceptions preserve their true runtime types across both modes.
5. **Internal interpreter control-flow exceptions.** [`Interpreter`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) implements `return`, `break`, and `continue` with zero-stacktrace `RuntimeException` subclasses ([`ReturnException`](../../src/main/java/org/babblelang/engine/impl/ReturnException.java), `Interpreter.BreakException`, `Interpreter.ContinueException`). Exception handling must guarantee that user `catch` clauses never intercept these internal signals, while `finally` blocks still execute as they unwind.

### 1.2 What this proposal introduces

`BIP028` adds expression-oriented `try`, `catch`, `finally`, and `throw` tailored to Babble's idioms — single bracket `()`, predicate-free `PredictionMode.SLL` grammar, native union types `A | B` for multi-catch, `Type.NOTHING` for `throw`, pure-by-default `BIP016` effects and ownership soundness, and 3-way `interpret` / `-O0` / `-O1` parity:

```babble
;; (Illustrative — using the recommended option package from §3–§8)

;; 1. Expression-oriented try / catch with native union multi-catch
def parsePort = fn nothrow (text: string): int -> (
    try (
        def n = java.lang.Integer.parseInt(text)
        if n < 1 or n > 65535 then (
            throw java.lang.IllegalArgumentException("port out of range : " + text)
        )
        n
    ) catch e: java.lang.NumberFormatException | java.lang.IllegalArgumentException then (
        8080
    )
)

;; 2. Custom Babble domain exceptions using records (also portable to BIP025 Wasm)
def ParseError = record (def line: int, def message: string) ()

def parseLine = fn (line: int, raw: string): int -> (
    try (
        java.lang.Integer.parseInt(raw)
    ) catch e: java.lang.NumberFormatException then (
        throw ParseError(line, "expected integer, got " + raw)
    )
)

def result = try (
    parseLine(4, "abc")
) catch e: ParseError then (
    println("line ", e.line, " : ", e.message)
    -1
) finally (
    println("done")
)

;; 3. try / finally for guaranteed cleanup (runs on normal exit, return, break, continue, and throw)
def readFirstLine = fn io (path: java.nio.file.Path): string? -> (
    def reader = java.nio.file.Files.newBufferedReader(path)
    try (
        reader.readLine()
    ) finally (
        reader.close()
    )
)
```

### 1.3 Goals and non-goals

**Goals:**
1. **Expression-oriented `try` / `catch` / `finally` and `throw`** consistent with `if`, `while`, `for`, `return`, `break`, and `continue`, preserving Babble's single-bracket `()` grammar and pure `PredictionMode.SLL` parsing with zero predicates (`ParserGrammarTestCase`).
2. **First-class Java exception interop** with **no checked-exception ceremony**: Java exceptions propagate with their true runtime type across both `Interpreter` and compiled bytecode, retiring the per-call `emitWrappedJavaInvoke` wrapper while keeping existing uncaught error formatting byte-identical.
3. **Ergonomic custom Babble exceptions** (`record`/`class`/`object` domain errors) that compose naturally with Babble's unions `A | B`, `is` narrowing, `frozen` immutability, and future WebAssembly 3.0 `try_table`/`exnref` lowering (`BIP025`).
4. **Sound integration with Babble's static type, effect, and ownership systems:** `Type.NOTHING` for `throw`, unreachable-catch rejection, `nothrow` restoration on exhaustive catch-all handlers, conservative `@MOVED`/`unique` joins across `try` → `catch`/`finally`, `@BORROWED` escape prevention on `throw`, and exact `BIP024` tail-call interaction.
5. **Verified JVM bytecode lowering & Nanopass soundness:** empty-operand-stack invariant for `try` inside compound expressions (`1 + try (...)`), native JVM `exception_table` dispatch, sound `finally` duplication with `try`-range splitting and non-local exit handling (`return`/`break`/`continue`), passing `HirVerifier` and ASM `CheckClassAdapter` across `-O0`, `-O1`, and `-O2`, with zero runtime dependencies outside `java.base` (`RuntimeBoundaryTestCase`).

**Non-goals:**
* **Java-style checked `throws` clauses on function types.** Babble already tracks throwability via opt-in `nothrow` (`BIP007` / `BIP016`) and `fn calling f` (`BIP017`). Adding checked exception lists to every function signature would conflict with Babble's lightweight, pure-by-default ergonomics; fine-grained typed throw-sets are noted only as a possible future extension (§6.1).
* **Algebraic resumable effect handlers** (Koka / OCaml 5 continuations). JVM bytecode has no delimited continuations, and `BIP028` targets standard unwind-only exceptions.
* **Built-in `Result of (T, E)` monad or `?` error-propagation operator.** `Result` can live cleanly in `std/` using Babble's existing generics and unions (`Ok of T | Err of E`); `BIP028` provides the underlying exception mechanism required for JVM interop, assertions, and resource cleanup.

---

## 2. State of the Art

| Language / Target | `try` form | Checked exceptions? | Multi-catch | Custom exceptions | `return`/`break` in `finally` | Resource management |
|---|---|---|---|---|---|---|
| **Java** (JLS §14.20) | Statement | Yes (`throws`) | `catch (A \| B e)` | Subclass `Throwable` | Allowed (silently swallows exceptions) | `try (var r = ...)` (`AutoCloseable` + `addSuppressed`) |
| **Kotlin** | Expression (`finally` discarded) | No | Not built-in | Subclass `Throwable` | Allowed | `resource.use { ... }` stdlib inline HOF |
| **Scala 3** | Expression (`finally` discarded) | Opt-in `CanThrow` capability | Pattern `case e: A \| B =>` | Subclass `Throwable` (`NonFatal` extractor) | Allowed (warns) | `Using(resource) { ... }` |
| **Python 3.14** | Statement (`else` + `finally`) | No | `except (A, B) as e:` | Subclass `BaseException` | Deprecated / `SyntaxWarning` (**PEP 765**) | `with open(...) as f:` |
| **C# / Swift** | Statement / `do`-`catch` + `defer` | No / typed `throws(E)` (Swift 6) | Guard filters | Subclass `Exception` / `Error` protocol | **Forbidden** at compile time | `using var r = ...` / `defer` |
| **WebAssembly 3.0** (`BIP025`) | `try_table` + `exnref` + `tag` | N/A (IR target) | Multiple `catch` clauses | Typed `tag` payload (`anyref`) | Structured blocks | Lowered from `finally` |

Key lessons for Babble:
1. **Expression-oriented `try` with discarded `finally` (Kotlin / Scala 3)** matches Babble's "everything is an expression" rule (`if`, `while`, `for`).
2. **Native union multi-catch (`A | B`)** requires zero special grammar in Babble because `type` already supports unions, and `Narrowing` already narrows union values via `if e is A then (...)`.
3. **No checked `throws` (Kotlin / Scala default)** avoids viral signature friction on Java interop while letting Babble's existing `nothrow` + `fn calling f` effect layer enforce exception-freedom where demanded (e.g., optimizer gates and pure `nothrow` APIs).
4. **Forbidding `return` / `break` / `continue` escaping `finally` (C# / Swift / Python PEP 765)** eliminates one of the JVM's most notorious bug sources (silently swallowing an in-flight exception or overwriting a pending return value) and dramatically simplifies `HirEmitter`.
5. **Fatal `Error` separation (Java / Scala `NonFatal` / Python `BaseException` vs `Exception`)** ensures a casual catch-all does not accidentally swallow `OutOfMemoryError` or `StackOverflowError`.
6. **HOF resource cleanup (`using` via `BIP017` `fn calling f`)** achieves Kotlin's `.use` ergonomics with full effect forwarding (`io`, `mut`, `volatile`, `nothrow`) and zero extra syntax, while leaving room for `try`-with-resources syntax if ever wanted (§8).

---

## 3. Syntax, Grammar, Formatting & REPL

### 3.1 Keyword audit

Four new lexer tokens are proposed:

```antlr
TRY:     'try';
CATCH:   'catch';
FINALLY: 'finally';
THROW:   'throw';
```

All four are exempted in `memberName` (`memberName: ... | TRY | CATCH | FINALLY | THROW;`), following `BIP011-D11`, `BIP012-D7`, `BIP013-D1`, and `BIP024-D9`, so Java methods/fields or named arguments with those names continue to work after `.` / `?.` and in `name: value` call arguments.

**Corpus impact check:** Searching all `.ba` scripts (`src/test/babble/`, `examples/`, `src/main/java/org/babblelang/axioms/`) and all `.batest` files (`src/test/batest/`) confirms **zero identifier collisions** for `try`, `catch`, `finally`, and `throw` (every existing match is inside comments or prose).

### 3.2 `try` / `catch` / `finally` and `throw` grammar (`BIP028-Q1`)

A `try` expression must have at least one `catch` clause, a `finally` block, or both (bare `try (...)` with neither is a syntax error). Two candidate syntaxes for `catch` clauses fit Babble's single-bracket `()` grammar with **zero predicates and pure `PredictionMode.SLL`**:

* **Option 1A (Recommended) — `then`-delimited header, mirroring `for name (: type)? in ... then (...)` and `if ... then (...)`:**
  ```antlr
  expression:
            ...
            | TRY NL* tryBlock=block
              ( (NL* catchClause)+ (NL* FINALLY NL* finallyBlock=block)?
              | NL* FINALLY NL* finallyBlock=block
              )                                               # tryExpression
            ...
            | RETURN expression                               # returnExpression
            | FOREVER expression                              # foreverExpression
            | BREAK expression?                               # breakExpression
            | CONTINUE                                        # continueExpression
            | THROW expression                                # throwExpression
            ;

  catchClause: CATCH NL* name=ID (':' type)? THEN NL* catchBlock=block;
  ```
  Example:
  ```babble
  def x = try (
      risky()
  ) catch e: java.io.IOException | java.lang.IllegalArgumentException then (
      fallback(e)
  ) catch e then (
      recover(e)
  ) finally (
      cleanup()
  )
  ```
  *Why Option 1A is recommended:*
  - Matches `if test then (...)`, `while test then (...)`, and `for name: T in xs then (...)` uniformly — every control-flow construct uses `then (...)` rather than introducing a second parenthesis group.
  - Strictly **LL(1)** and **pure `SLL`**: `THEN` cannot appear anywhere inside `type` (`nullableType (('|' | '&') NL* nullableType)*`), so `name=ID` → `':'` vs `THEN` is a single-token decision.
  - Keeps parentheses strictly for blocks, call arguments, function/class parameter lists, and type grouping — avoiding `catch (e: T) (...)` which visually resembles a curried call.
  - Note on unnamed/discarded catch variable: if a user does not need the exception object, should `catch _: T then (...)` (or `catch : T then (...)` / `catch then (...)`) also be supported? Adding `catchClause: CATCH (NL* name=ID)? (':' type)? THEN NL* catchBlock=block;` stays strictly LL(1) (`ID` vs `':'` vs `THEN`) and lets users write `catch : java.lang.NumberFormatException then (0)` or `catch then (0)` without unused-binding noise!

* **Option 1B — Arrow-delimited parenthesized parameter, mirroring `fn (e: T) -> (...)`:**
  ```antlr
  catchClause: CATCH NL* '(' NL* name=ID (':' type)? NL* ')' NL* '->' NL* catchBlock=block;
  ```
  Example:
  ```babble
  def x = try (
      risky()
  ) catch (e: java.io.IOException) -> (
      fallback(e)
  ) finally (
      cleanup()
  )
  ```
  *Trade-offs:* Familiar to Java/Kotlin developers and stresses that a catch handler is like a 1-parameter lambda, but adds two tokens (`(`, `)`, `->`) compared to Option 1A and diverges from `for x: T in xs then (...)`.

* **Option 1C — Parenthesized parameter + `then` (`catch (e: T) then (...)`) or bare block (`catch (e: T) (...)`):**
  Both are LL(1)/SLL, but `catch (e: T) (...)` puts two `(...)` groups side by side (which `BIP012` intentionally eliminated from Babble), and `catch (e: T) then (...)` adds redundant parentheses around a single binding.

**`throw` precedence:**
`THROW expression` sits at the bottom of `expression` alongside `RETURN expression`, `FOREVER expression`, `BREAK expression?`, and `CONTINUE`. Thus `throw IllegalArgumentException("bad: " + x)` parses as `throw (IllegalArgumentException("bad: " + x))`, while `if bad then (throw err) else (ok)` and `def x = if ok then (1) else (throw err)` parse naturally. Unlike `break expression?`, `throw` always requires an expression (`throw e` to rethrow), eliminating any newline-termination ambiguity.

**Unambiguous newline continuation:**
Because `CATCH` and `FINALLY` are reserved keywords that can never begin a standalone `expression`, `(NL* catchClause)+` and `(NL* FINALLY NL* finallyBlock=block)?` can never conflict with a following statement on the next line — identical to `(NL* ELSE NL* ...)?` in `ifExpr` and `(NL* STATIC NL* staticBlock=block)?` in `classExpression`.

### 3.3 `Formatter` (`babblefmt`) rules

Following [`Formatter.java`](../../src/main/java/org/babblelang/format/Formatter.java)'s existing rules for `if`/`else`/`static` and `for`/`then`:
* `try (` keeps its opening `(` on the same line as `try` (dropped newline before block `(`, unless a comment intervenes).
* `) catch ... then (` and `) finally (` are pulled back onto the line of the preceding block's closing `)`, exactly like `) else (` and `) else if ... then (` and `) static (`.
* Single spaces around `catch`, `then`, `finally`, `throw`, and `|` in multi-catch types; no space before `:`, one space after `:` (`catch e: A | B then (`).
* Anchoring: the `try`, `catch`, and `finally` blocks all anchor their indentation to the line starting the `try` expression (so bodies indent by 4 spaces relative to `try`, not relative to `catch` or `finally`).
* Round-trip parse-tree equivalence (`shape()`) continues to hold unchanged.

### 3.4 REPL (`BabbleSession.classify`)

[`BabbleSession.classify`](../../src/main/java/org/babblelang/engine/BabbleSession.java) classifies inputs by checking `ParseFailure.atEnd()`:
* Typing `try (\n    risky()\n)` and pressing Enter fails at `EOF` expecting `catch` or `finally` → `ParseFailure.atEnd() == true` → **`INCOMPLETE`** (REPL continues reading).
* Typing `try (\n    risky()\n) catch e: java.lang.Exception then` and pressing Enter fails at `EOF` expecting `(` → **`INCOMPLETE`**.
* Typing `try (\n    risky()\n) catch e: java.lang.Exception then (\n    0\n)` is a complete `tryExpression` → **`COMPLETE`** (same rule as `if c then (a)` without `else`: to chain another `catch` or `finally` interactively, place `) catch` or `) finally` on the closing `)` line, matching canonical `babblefmt` style).
* `Completions` adds `try`, `catch`, `finally`, and `throw` to its keyword list.

---

## 4. Exception Model, Throwing & Java Interop

### 4.1 Retiring per-call Java exception wrapping (`BIP028-Q3`)

Today, Java exceptions are wrapped eagerly at **every** Java call boundary:
* `JavaMethod.invoke` catches `Throwable` and calls `JavaTypes.wrapInvocationException(t)`.
* `JavaLinker` attaches `MethodHandles.catchException(..., FAIL)` delegating to `JavaTypes.wrapInvocationException`.
* `JavaClass` wraps constructor/field reflection errors via `JavaTypes.wrapInvocationException`.
* `CallEmitter.emitWrappedJavaInvoke` wraps **every** statically lowered Java method/constructor call in bytecode with a synthetic `visitTryCatchBlock(..., "java/lang/Throwable")` calling `JavaTypes.wrapInvocationException` and rethrowing `BabbleException`.

That design made sense before `try`/`catch` existed because it unified uncaught formatting and bypassed `javac`'s checked-exception rules on `Callable.call`. With `try`/`catch`, eager wrapping is actively harmful:
1. Catching `java.io.IOException` or `java.lang.NumberFormatException` would fail on a naive JVM `exception_table` because the thrown object is actually a `BabbleException` whose `getCause()` is the `IOException`!
2. Compiled Babble classes implementing Java interfaces (`RM003` direct `implements` bridges) would leak `BabbleException` wrappers back into Java callers expecting `IOException` or `UnsupportedOperationException`.
3. Every statically lowered Java call pays bytecode size and exception-table bloat for `emitWrappedJavaInvoke`.

**Proposed architecture (Option 3A — Recommended: direct `Throwable` propagation with boundary formatting):**

```mermaid
flowchart TD
    JCall["Java call / constructor / field"] -->|"throws Throwable t"| Unwrap["Unwrap InvocationTargetException only\n(reflection path: JavaMethod / JavaClass)"]
    Unwrap -->|"Throwables.sneakyThrow(t)"| Propagate["Raw JVM Throwable propagates on stack"]
    BThrow["Babble throw expr"] -->|"Java Throwable: direct ATHROW\nBabble object: new ThrownException(val)"| Propagate
    BRuntime["Babble runtime error\n(assert, as, e!, int('bad'), ...)"] -->|"throws BabbleException"| Propagate
    Propagate --> Catch{"Enclosing Babble\ntry / catch?"}
    Catch -->|"Yes (matches type)"| Handle["Bind e (unwrapped if ThrownException)\nRun catch block"]
    Catch -->|"No (uncaught at script exit)"| Boundary["Script boundary formatter\n(BabbleCompiledScript / CompiledBabbleScript / Launcher)"]
    Boundary --> Format["Format identically to today:\nBabbleException -> message\nNPE -> 'java.lang.NullPointerException'\nJava Throwable -> 'ClassName : msg'"]
```

Let's verify every consequence of Option 3A:
1. **`org.babblelang.runtime.Throwables`** (tiny `java.base`-only utility in `org.babblelang.runtime`):
   * `public static RuntimeException sneakyThrow(Throwable t)`: standard type-erased generic rethrow (`throw (T) t;`), allowing checked exceptions (such as `IOException`) to propagate unmodified through `Interpreter`, `JavaMethod.invoke`, `Callable.call`, and `JavaLinker` with zero wrapping!
   * `public static Throwable unwrapReflection(Throwable t)`: peels `InvocationTargetException` (and `Error` passes straight through) and calls `sneakyThrow(t)`.
2. **`CallEmitter.emitWrappedJavaInvoke` is deleted!** Direct Java `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE`/`NEW` simply emits the instruction directly. If Java throws `NumberFormatException` or `IOException`, the JVM propagates the exact exception object with zero wrapper allocation.
3. **Top-level uncaught formatting stays 100% backwards-compatible:**
   `ArraySupport.formatThrowable(Throwable t)` (called by `BabbleCompiledScript.eval`, `CompiledBabbleScript.eval`, and `Launcher.message`) formats uncaught exceptions identically to today:
   * `BabbleException` → `org.babblelang.runtime.BabbleException: <msg>` (or `<msg>` in `Launcher`)
   * `ThrownException` → `org.babblelang.runtime.BabbleException: <ArraySupport.format(value)>` (consistent uncaught formatting for custom Babble exceptions)
   * `NullPointerException` → `java.lang.NullPointerException` (omitting JDK 14+ Helpful NPE text that leaks synthetic compiler local names, preserving `BIP009` parity!)
   * `ArrayStoreException` → existing compiled-class-name normalizer in `ArraySupport.formatThrowable`
   * Any other non-`Error` `Throwable` → `t.getMessage() == null ? name : name + " : " + t.getMessage()` (exact space-before-colon match with `src/test/batest/java/exceptions_and_nulls.batest`!).
4. **`PrintFunction` (`print`/`println` to a custom `Appendable`):** Currently wraps `IOException` into `new BabbleException(e)`. Under Option 3A, rethrowing `IOException` directly via `Throwables.sneakyThrow(e)` allows `try (println("hi", to: brokenWriter)) catch e: java.io.IOException then (...)` to catch it naturally.
5. **Alternative (Option 3B — Keep `BabbleException` wrapping, unwrap inside `catch`):** Keeps `emitWrappedJavaInvoke` and unwraps `BabbleException.getCause()` at every `catch` handler. Rejected as primary recommendation because it bloats bytecode, breaks direct JVM `exception_table` matching (forcing every `catch e: IOException` to catch `BabbleException` + `IOException` and inspect `getCause()`), and breaks Java callers of `RM003` bridges.

### 4.2 Custom Babble exceptions & what `throw` accepts (`BIP028-Q2`)

Because Babble classes are plain JVM classes extending `java.lang.Object` (`BIP020-D18`) with no class inheritance (only interface `implements`), Babble cannot simply tell users "subclass `java.lang.Exception`". Three design options exist:

| Option | What `throw expr` accepts | How custom Babble errors are written | JVM representation | `BIP025` Wasm compatibility |
|---|---|---|---|---|
| **Option 2A — `java.lang.Throwable` only** | `expr: java.lang.Throwable` (incl. `BabbleException`) | Cannot define custom Babble exception classes; must use Java exception classes or `BabbleException("msg")` | Direct `ATHROW` | Weak (ties user errors to `java.lang.*`) |
| **Option 2B (Recommended) — `java.lang.Throwable` OR Babble nominal/object values via `ThrownException` carrier** | Any non-null `java.lang.Throwable`, Babble `class` / `record` / `object` / `interface` instance (and unions thereof; optionally restricted to a marker interface `Exception` — see sub-question below) | `def ParseError = record (def line: int, def msg: string) ()` → `throw ParseError(4, "bad")` | Java `Throwable`: direct `ATHROW`; Babble object: `new ThrownException(val)` (`extends RuntimeException`) + `ATHROW` | **Native fit:** maps 1-to-1 to Wasm `tag $babble_exn (param anyref)` + `br_on_cast` |
| **Option 2C — Dedicated `exception class` / `class ... extends RuntimeException`** | `java.lang.Throwable` + `exception` classes | `def ParseError = exception (def line: int, def msg: string) ()` | Compiled mode: subclass of `RuntimeException`; Interpreter: still requires `ThrownException(InterpretedInstance)` carrier! | Requires special runtime hierarchy |

**Why Option 2B is recommended:**
1. **Zero new class-declaration syntax** and preserves `BIP020-D18` (all compiled Babble classes stay plain `extends java.lang.Object`).
2. **Works seamlessly with `record`, interfaces, unions, and `frozen`:**
   ```babble
   def AppError = interface (def code: fn (): int)
   def NotFound = record (def id: string) implements AppError (
       def code = fn (): int -> (404)
   )
   def Denied = record (def user: string) implements AppError (
       def code = fn (): int -> (403)
   )
   ```
   A caller can catch `e: NotFound`, `e: NotFound | Denied`, `e: AppError`, or `e: AppError | java.io.IOException`!
3. **Both backends stay symmetric:** `ThrownException` is a tiny `final class` in `org.babblelang.runtime` (`extends RuntimeException`, holding `private final Object value`, overriding `getMessage()` via `ArraySupport.format(value)`). Java callers catching `RuntimeException` or `ThrownException` can inspect `getValue()`, while Babble `catch` clauses see the unwrapped Babble object directly (`e: ParseError`).
4. **Static guard against nonsense throws:** `throw` **always** rejects `null` and nullable types `T?` at compile time (`Type error : cannot throw nullable type ...`), as well as `void`, primitives (`int`, `bool`, ...), functions, classes/modules/packages (values of `Type.ClassValue`/`Type.Module`/`Type.Package`), and arrays. Two sub-variants remain open inside **`BIP028-Q2`**:
   * **2B-i (Open nominal):** Allow throwing any `java.lang.Throwable` OR any Babble instance (`class`, `record`, `object`, `interface`, or union/intersection thereof — plus `string`? No: keep `string` disallowed so users throw structured types or `BabbleException("msg")`, avoiding JS-style `throw "oops"`).
   * **2B-ii (Marker interface):** Require Babble thrown objects to implement a built-in marker interface (e.g., `Error` or `Failure`) so ordinary domain objects like `Vec3` cannot be accidentally thrown.

### 4.3 Exposing `BabbleException` and built-in Babble failures

Babble's own runtime errors — `assert(false, ...)`, `x as T`, `e!`, `int("bad")`, `Name used before definition`, dynamic `import(...)` failures — all throw `org.babblelang.runtime.BabbleException` (which extends `java.lang.RuntimeException`).

* **Note on `int("bad")` / `double("bad")`:** Look at [`ConvertFunction.java`](../../src/main/java/org/babblelang/engine/impl/natives/ConvertFunction.java) and `src/test/batest/runtime/values_and_operators.batest`: `int("bad")` calls `Integer.parseInt` and throws `NumberFormatException` (currently wrapped in `BabbleException`, becoming direct `NumberFormatException` under Option 3A, or `BabbleException` for range/type failures like `int(1e30)`). We should audit `ConvertFunction` in `BIP028-P1` so every conversion failure's exception class is documented and tested identically in all 3 modes!
* **How users name `BabbleException` in `catch`:**
  1. `catch e: java.lang.RuntimeException` and `catch e: java.lang.Exception` already catch `BabbleException` (since `BabbleException extends RuntimeException`).
  2. Users can write `org.babblelang.runtime.BabbleException` directly (just like any Java class), OR Babble can expose a short implicit/core alias (e.g., `def BabbleException` in `Implicits` / prelude, or a `std/errors` re-export — tracked in **`BIP028-Q2`**). Note: `org.babblelang.runtime.BabbleException` is already part of the public `java.base`-only runtime boundary (`BIP006` §3), so exposing it does not widen `RuntimeBoundaryTestCase`.

### 4.4 Fatal JVM `Error`s, wildcard `catch`, and internal signals (`BIP028-Q4`)

Three distinct categories of `Throwable` can fly past a `try` block on the JVM:

| Category | Examples | Caught by `catch e: T` (explicit `T`)? | Caught by untyped `catch e then (...)` / `catch then (...)`? | Runs `finally`? |
|---|---|---|---|---|
| **Interpreter control-flow signals** | `ReturnException`, `BreakException`, `ContinueException` | **NEVER** (even `catch e: java.lang.Throwable`) | **NEVER** | **YES** (for `finally` blocks between jump and target) |
| **Normal exceptions** | `java.lang.Exception`, `RuntimeException`, `IOException`, `BabbleException`, Babble `ThrownException` | Yes, if runtime value matches `T` | **YES** | **YES** |
| **JVM `Error`s** | `OutOfMemoryError`, `StackOverflowError`, `LinkageError`, `AssertionError` | Yes **only** if `T` explicitly accepts `Error` (e.g., `java.lang.Error`, `java.lang.StackOverflowError`, `java.lang.Throwable`) | **NO** (recommended — see `BIP028-Q4`) | **YES** (standard JVM `catch-all` `finally`) |

**Why untyped `catch e then (...)` should NOT catch `java.lang.Error` (`BIP028-Q4`):**
* Today, [`CompiledBabbleScript.java`](../../src/main/java/org/babblelang/compiler/CompiledBabbleScript.java) and [`JavaTypes.wrapInvocationException`](../../src/main/java/org/babblelang/runtime/JavaTypes.java) deliberately let `java.lang.Error` escape unwrapped.
* If untyped `catch e then (...)` binds `e: java.lang.Exception` (or `java.lang.Exception | any` excluding `java.lang.Error` — specifically, catching `java.lang.Exception` and `ThrownException` in Option 2B, giving `e: any` or `e: java.lang.Exception` depending on `BIP028-Q2`), then:
  - Beginners writing `try (...) catch e then (...)` catch every Java `Exception`, every `BabbleException`, and every thrown Babble object, while **never** silently masking an `OutOfMemoryError` or `StackOverflowError`.
  - Expert code that genuinely wants to intercept `AssertionError` or `StackOverflowError` or every `Throwable` (such as `using` in §8) writes `catch e: java.lang.Throwable then (...)` or `catch e: java.lang.AssertionError then (...)` explicitly.
* **Sub-question inside `BIP028-Q4`:** Should untyped `catch e then (...)` even be allowed, or should `catch` always require an explicit type (e.g., `catch e: java.lang.Exception then (...)`)?
  - Allowing untyped `catch e then (...)` (defaulting to non-`Error` exceptions) is very friendly for beginners — one of Babble's core goals in [`README.md`](../../README.md) — while still keeping `e` statically typed (`java.lang.Exception` under Option 2A, or `any` / `java.lang.Exception | Error` under Option 2B).

---

## 5. Static Semantics: Typing, Scoping, Control Flow & Reachability

### 5.1 `throw` expression typing

* `throw expr`:
  * Checks `expr` against the throwable constraint (§4.2): non-nullable `java.lang.Throwable` (or Babble nominal instance under Option 2B). Rejects `null`, `T?`, primitives, `void`, functions, arrays, modules, packages, and class values with a clear diagnostic (`Type error : cannot throw <type>`).
  * **Expression type is `Type.NOTHING`** (Babble's existing bottom type in [`Type.java`](../../src/main/java/org/babblelang/analysis/Type.java), already used for `return`, `break`, and `continue`).
  * Because `Type.NOTHING` is a subtype of every type `T` (`TypeAlgebra.subsumes(T, NOTHING) == true`) and `join(T, NOTHING) == T`, idioms like the following type-check with zero casts:
    ```babble
    def requirePos = fn (n: int): int -> (
        if n > 0 then (n) else (throw java.lang.IllegalArgumentException("must be positive"))
    )
    def nonNullName: string = if maybe != null then (maybe) else (throw MissingName())
    ```
  * Statements following an unconditional `throw` in the same block are unreachable (same handling as after `return`/`break`/`continue`).

### 5.2 `try` / `catch` / `finally` expression typing (`BIP028-Q5`)

Consistent with `if` / `else`, `while`, and `for`:

1. **Bidirectional type flow (`BIP010`):**
   * The enclosing `expected` type flows into `tryBlock` AND into every `catchBlock`.
   * `finallyBlock` (if present) is checked in **discarded statement position** (`Role.STATEMENT`, no `expected` type) — its result value is always discarded and never contributes to the `try` expression's type.
2. **Result type:**
   * Let $T_0$ be the type of `tryBlock` and $T_1, \dots, T_k$ be the types of the `catch` blocks.
   * The type of `try ... catch ... finally` is the join of all non-`NOTHING` branches:
     $$T_{\text{try}} = \text{join}(T_0, T_1, \dots, T_k)$$
     (if all branches have type `Type.NOTHING` — e.g., every branch returns or throws — then $T_{\text{try}} = \text{Type.NOTHING}$).
   * If there are **no `catch` clauses** (`try (...) finally (...)`), $T_{\text{try}} = T_0$.
   * **Numeric widening (`BIP008-D7`, `BIP011-D4`):** If `tryBlock` yields `int` and a `catchBlock` yields `double`, `join(int, double) == double`, and `TypeChecker.coerce` records widening on the `int` branch just as it does for `if`/`else`!
   * **Unboxed primitive results (`RM001` / `RM005`):** When $T_{\text{try}}$ is a non-nullable core primitive (`byte`..`char`), `LoweringAnalyzer` gives the `HirExpr.Try` that `PrimitiveKind`, so `def n: int = try (parseInt(s)) catch e: java.lang.NumberFormatException then (0)` never boxes the `int` result!
   * **Edge case — `finallyBlock` has type `Type.NOTHING` (always throws):**
     If `finallyBlock` unconditionally throws (e.g., `finally (throw Fatal())`), then the `try` expression can never complete normally, so $T_{\text{try}} = \text{Type.NOTHING}$ (and `TypeChecker` may emit a warning if a non-`NOTHING` `try`/`catch` result is unconditionally masked by a throwing `finally`).

### 5.3 Catch-type validity, multi-catch unions, and unreachable catches

For every `catchClause` with type annotation `C`:
1. **Valid catch types:**
   * Each member of `C` (after flattening `Type.Union`) must be a reifiable catchable type: a `java.lang.Throwable` class (or under Option 2B, also a Babble `class`, `record`, `object`, or `interface` — or `A & B` intersection of Babble/Java interfaces!).
   * **Rejected statically** (mirroring `resolveIsTarget` in [`TypeChecker.java`](../../src/main/java/org/babblelang/analysis/TypeChecker.java)):
     - `null` or nullable `T?` (`Cannot catch nullable type : ...`)
     - `void`, primitives (`int`, `double`, ...), `string`, `array of T`, function types, overload sets (`Cannot catch non-exception type : ...`)
     - Erased generic type arguments (`catch e: Box of int` → `Cannot catch erased type arguments : use Box`)
     - Type variables `T` (`Cannot catch type parameter : T` — erased at runtime)
     - `frozen T` (`Cannot catch 'frozen' type : ...` — matching `Cannot test 'frozen' with 'is'`)
     - Non-`Throwable` Java classes (e.g., `catch e: java.lang.String` or `catch e: java.util.List` — `Type error : java.lang.String is not a Throwable`).
2. **Multi-catch unions (`A | B`):**
   * `catch e: java.io.IOException | java.lang.IllegalArgumentException then (...)` requires zero new syntax.
   * Inside `catchBlock`, `e` has static type `java.io.IOException | java.lang.IllegalArgumentException`. Common supertypes/members work through `MemberTypes`, and `if e is java.io.IOException then (...)` narrows `e` using Babble's existing `Narrowing` pass!
   * Redundant union members within one `catch` clause (`catch e: java.lang.Exception | java.io.IOException`) are rejected (or flagged): `Redundant catch type : java.io.IOException is already a subtype of java.lang.Exception`.
3. **Unreachable catch clauses (compile-time error):**
   * Clauses are checked in source order. Maintaining the accumulated union $U_{<i}$ of types caught by clauses $1 \dots i-1$:
     - If every member of clause $i$'s type $C_i$ is subsumed by $U_{<i}$, report a compile-time error: `Unreachable catch clause : <C_i> is already caught by <earlier>`.
     - If a member $M$ of a union catch $C_i = M \mid N$ is already subsumed by $U_{<i}$, report `Unreachable catch type : <M> is already caught by <earlier>`.

### 5.4 Block scoping, read-only binding, narrowing & definite initialization

* **Scopes (`BlockScopes` & `Resolver`):**
  * `tryBlock`, each `catchClause` (its binding `e` + `catchBlock`), and `finallyBlock` are separate lexical block scopes (child `ScopeNode`s).
  * `e` is bound in the `catchClause`'s scope and is **read-only** (`Assigning to catch parameter : e` is a compile-time error, matching `for`'s loop variable `BIP013-D6`).
  * `e` may be captured by closures inside `catchBlock` (promoted to a `Cell` by `Resolver` if captured, just like any block local).
  * Names `def`'d inside `tryBlock` are not visible in `catchBlock` or `finallyBlock`.
* **Narrowing (`Narrowing` / `TypeEnv`):**
  * **Into `catchBlock` and `finallyBlock`:** Start from the `TypeEnv` **before `try`** (NOT the `TypeEnv` at the end of `tryBlock`, because an exception may have been thrown on the very first line of `tryBlock` before any `!= null` or `is T` guard executed!). Furthermore, invalidate any narrowing on variables that are reassigned anywhere inside `tryBlock` (or `catchBlock` when entering `finallyBlock`), just like `while`/`for` loop invalidation.
  * **After `try` / `catch` / `finally`:** Intersect the post-narrowings of `tryBlock` and all non-`NOTHING` `catchBlock`s (re-applying any unconditional narrowing established in `finallyBlock`), dropping facts on variables reassigned in any branch.
* **Definite initialization:**
  * A `def x: T` declared outside `try` without an initializer and assigned only inside `tryBlock` is **NOT** definitely initialized inside `catchBlock`, `finallyBlock`, or after `try` (unless also assigned in every non-`NOTHING` `catchBlock`, or in `finallyBlock`), keeping `mayBeUndefined = true` for `HirVar` so `Name used before definition` behaves identically in both backends.

### 5.5 Non-local exits (`return`, `break`, `continue`) & `finally` (`BIP028-Q6`)

1. **`return`, `break`, `continue` inside `try` or `catch`:**
   * **Allowed** (whenever inside an enclosing function / loop, per `BlockScopes` placement rules).
   * The `return e` or `break e` value expression `e` is evaluated **before** `finally` runs; mutations inside `finally` do **not** alter the already-evaluated return/break value (matching Java, Kotlin, and Python).
   * All enclosing `finally` blocks between the jump and its target (enclosing function for `return`; enclosing loop for `break`/`continue`) execute innermost-to-outermost before control transfers.
2. **`return`, `break`, `continue` inside `finally` (`BIP028-Q6`):**
   * **Option 6A (Recommended — C# / Swift / Python PEP 765 rule): Statically forbidden** when targeting outside the `finally` block!
     - `BlockScopes` tracks `finallyDepth` (and resets it inside a nested `fn` literal or nested loop inside `finally`, so a `return` inside a closure or a `break` inside a loop *contained within* `finally` remains 100% legal!).
     - Diagnostics: `Cannot return from a finally block`, `Cannot break out of a finally block`, `Cannot continue out of a finally block`.
     - *Rationale:* Prevents silently swallowing in-flight exceptions or clobbering pending return/break values, avoids subtle interpreter/compiler divergence, and removes an entire class of nasty control-flow bugs.
   * **Option 6B (Java / Kotlin rule):** Allow `return` / `break` / `continue` escaping `finally`, discarding any pending exception or return value (with a compile-time warning).
   * Note: `throw` inside `finally` is always legal (a `finally` block calling `stream.close()` can naturally throw `IOException`), and replaces the pending completion just as in Java/Kotlin.

### 5.6 Interaction with self tail calls & `forever` (`BIP024`)

[`TailCalls`](../../src/main/java/org/babblelang/analysis/TailCalls.java) must respect exception handlers and cleanup:
* **`tryBlock` is NEVER in tail position:** `try (recurse(n - 1)) catch ...` and `try (recurse(n - 1)) finally (...)` must keep the stack frame active so the `catch`/`finally` handler executes if `recurse` throws or returns.
* **`catchBlock` without `finally` IS in tail position** (when the `try` expression itself is in tail position): once inside `catch`, the `try` handler is no longer active and there is no cleanup left to run, so `try (...) catch e: E then (recurse(n - 1))` can tail-call cleanly!
* **`catchBlock` WITH `finally` is NEVER in tail position:** `finally` must run after `catch` finishes.
* **`finallyBlock` is NEVER in tail position** (its result is discarded).
* **`return e` inside `try` or `catch` when a `finally` is active is NEVER in tail position** (because `finally` must run after `e` returns).
* **`BIP024-D7` & `BIP024-D8` (`forever`):** A `throw` inside a `while true` loop or recursive function makes it NOT `nothrow`, so `while true then (if done then (throw Stop()))` does not require `forever` (consistent with `BIP024-D8`).

---

## 6. Effect, Ownership & Axiom Integration

Babble's static effect & ownership system (`BIP007`, `BIP016`, `BIP017`, `BIP019`, `BIP027`) interacts with `try`/`catch`/`finally`/`throw` in five concrete ways. Getting these right makes `BIP028` feel native to Babble rather than bolted on.

### 6.1 `nothrow` inference & verification (`BIP028-Q7`)

* **`throw expr`:** Immediately sets `walker.nothrow = false` (in [`EffectBodyWalker`](../../src/main/java/org/babblelang/analysis/EffectBodyWalker.java)) and marks `stmtHasSideEffect = true` (so `throw` is never flagged as `Unused pure expression`).
* **`try` / `catch` / `finally`:**
  Save and isolate the `nothrow` flag across `tryBlock` (`tryNothrow`), each `catchBlock` (`catchNothrow[i]`), and `finallyBlock` (`finallyNothrow`):
  * If `tryNothrow == true`: `tryBlock` cannot throw, so the `try` expression's `nothrow` depends only on `finallyNothrow` (Wait: what about `catch` blocks? They are unreachable at runtime, though still type- and effect-checked!).
  * If `tryNothrow == false`:
    * **Option 7A (Recommended for initial implementation — Sound catch-all restoration):**
      The `try` expression restores `nothrow = true` iff:
      1. The `catch` clauses collectively cover **all catchable non-`Error` exceptions** (i.e., an untyped catch-all `catch e then (...)`, or `java.lang.Throwable`, or `java.lang.Exception` plus all Babble thrown types — matching `BIP007`/`BIP016`'s existing JVM `nothrow` model, which treats asynchronous `VirtualMachineError`s like `OutOfMemoryError`/`StackOverflowError` as outside `nothrow`), **AND**
      2. Every `catchBlock` has `catchNothrow[i] == true`, **AND**
      3. `finallyBlock` (if present) has `finallyNothrow == true`.
      Otherwise, `walker.nothrow = false`.
    * **Option 7B (Future extension — Typed throw-set inference):**
      Track a conservative set of potentially thrown exception types per expression/callable (plus `throws` axioms in `curated.ba`), so `try (java.lang.Integer.parseInt(s)) catch e: java.lang.NumberFormatException then (0)` is inferred `nothrow` even without a catch-all!
      *Why Option 7A first:* In Java, unchecked `RuntimeException`s are omitted from bytecode `Exceptions` attributes (`List.get` throws `IndexOutOfBoundsException` without declaring it), so Option 7B would either be unsound on unannotated Java calls or require annotating exception sets across all of `curated.ba`. Option 7A is 100% sound today with zero axiom churn, and Option 7B can strictly relax it later without breaking any existing program!
  * **Dead `try` / unreachable `catch` warning (`BIP028-Q7` sub-question):**
    When `tryBlock` is statically proven `nothrow` (and does not depend on a `calling` callback parameter in `fn calling f`, `BIP017`), should `EffectChecker` emit a non-fatal warning (`Warning : try block cannot throw; catch clause is unreachable`)? Recommended: **yes (warning, not error)**, skipping functions with `calling` clauses where a callback may throw at some call sites.

### 6.2 Purity, hermeticity & determinism (`BIP028-Q8`)

* **Pure `try`/`catch` is pure and deterministic:** Catching an exception thrown by a pure, deterministic computation (e.g., `Integer.parseInt(s)` throwing `NumberFormatException`) performs no I/O and reads no external mutable state. Therefore, `try`/`catch`/`finally` **preserves `pure` (`@HERMETIC`, `@DETERMINISTIC`)** as long as `tryBlock`, all `catchBlock`s, and `finallyBlock` are themselves `@HERMETIC` and `@DETERMINISTIC`!
* **Union of side effects across all clauses:** Every `io`, `volatile`, `mut` (on `this`, parameters, or captured variables), `readsThis`, `readParams`, and callback invocation in `tryBlock`, ANY `catchBlock`, or `finallyBlock` joins into the enclosing callable's effect summary.
* **Stack-trace inspection subtlety (`BIP028-Q8`):**
  * `Throwable.printStackTrace()` writes to `System.err` → already `io` via bytecode analysis / `curated.ba`.
  * `Throwable.getMessage()`, `getCause()`, `getSuppressed()` → pure getters (already deterministic on immutable/local exceptions, or read-only on the caught exception).
  * `Throwable.getStackTrace()` reflects the call stack (which varies with caller context and inlining); marking `getStackTrace` / `fillInStackTrace` appropriately in `src/main/java/org/babblelang/axioms/java/lang/curated.ba` ensures pure functions cannot observe optimizer inlining differences!
  * `finallyBlock` statements are **all** in discarded position, so `Unused pure expression` (`BIP007-D8`) checks every statement in `finallyBlock` (catching bugs like `finally (x + 1)` that forgot to assign or call `close()`).

### 6.3 Ownership (`unique` / `@MOVED`) across `try` / `catch` / `finally`

An exception can be thrown at **any potentially-throwing expression** inside `tryBlock`, transferring control immediately to `catchBlock` and `finallyBlock`. [`EffectBodyWalker`](../../src/main/java/org/babblelang/analysis/EffectBodyWalker.java) must therefore preserve ownership soundness across all possible unwind points:

1. **Snapshot before `try`:** Save `preEnv = copy(env)` before walking `tryBlock`.
2. **Track intermediate moves & demotions inside `tryBlock`:** Any variable moved (`move(x)` / `freeze(x)`) or demoted from `unique` to `@EXTERNAL`/`@PARAM` *anywhere* inside `tryBlock` may or may not have happened when an exception fires:
   * On entry to each `catchBlock`, initialize `env` by **joining `preEnv` with the cumulative move/demotion state of `tryBlock`** (specifically: if `x` was moved at any point in `tryBlock`, `x` is `@MOVED` in `catchBlock` and cannot be used unless reassigned first; if `x` was demoted in `tryBlock`, `x` is demoted in `catchBlock`).
   * On entry to `finallyBlock`, join `preEnv`, `tryBlock`'s cumulative state, and every `catchBlock`'s state (with the same conservative `@MOVED` and demotion join).
   * After `try`/`catch`/`finally`, join the exit states of `tryBlock` and all non-`NOTHING` `catchBlock`s, followed by `finallyBlock`.
3. **Mutating a fresh `unique` local in `try`:** Remains completely legal inside a `pure` function — `tryBlock` mutates the local, and if it throws, `catchBlock`/`finallyBlock` sees the mutated local (still 100% frame-local and hermetic).

### 6.4 Exception-escape provenance (`@BORROWED` soundness)

**Critical soundness invariant:** Under `BIP016`, parameters are `@BORROWED` by default — a function may not let a `@BORROWED` parameter escape its dynamic extent unless annotated `shared`.

Consider:
```babble
def Leak = record (def box: Box) ()
def bad = fn (b: Box): int -> (   ;; b is @BORROWED by default!
    throw Leak(b)                 ;; ESCAPES b out of `bad`!
)
```
If `bad` throws `Leak(b)`, an arbitrary caller further up the call stack can catch `Leak` and store `e.box` in a global field — violating `b`'s `@BORROWED` contract!

**Rule:** `throw expr` **escapes `expr`** (calling `recordEscape(origin, exprType)` in `EffectBodyWalker`, exactly like storing into an external field or global):
* Deeply immutable / `frozen` values (`int`, `string`, `frozen T`, `record` of primitives, standard Java exceptions carrying only a `string` message) have `Origin.IMMUTABLE` and escape freely with zero annotations!
* Throwing a value whose provenance contains a `@BORROWED` parameter or `@BORROWED` `this` (e.g., `Leak(b)` where `Box` is mutable) is **rejected at compile time** unless the parameter is marked `shared` (or `unique` and moved in via `move(b)`), OR unless proven caught locally (conservative escape-always is recommended in `BIP028-P2` because it is simple, local, and 100% sound!).
* Similarly, a caught exception variable `e` in `catch e: T` starts with `Origin.EXTERNAL` (unless `T` is deeply immutable, e.g., `record ParseError(line: int, msg: string)`, which gets `Origin.IMMUTABLE` via `ModalKindTable`!).

---

## 7. Backend Lowering, `HIR`, Optimizer & WebAssembly

### 7.1 ANTLR-free `HIR` representation

`org.babblelang.ir.hir.HirExpr` gains two immutable sealed record variants (bringing `HirExpr` from 51 to 53 variants), keeping `ir`, `opt`, and `compiler` 100% detached from ANTLR4 and `ScopeAnalysis` (`ArchitectureBoundaryTestCase`):

```java
record Throw(
        HirExpr value,
        boolean isJavaThrowable,
        ExprMeta meta
) implements HirExpr {}

record HirCatch(
        HirVar var,                   // null if unnamed catch (: T or bare catch)
        String typeDescriptor,        // runtime Types descriptor (for Babble / union / interface tests)
        List<String> jvmCatchTypes,   // JVM internal names for direct visitTryCatchBlock (e.g. "java/io/IOException")
        boolean catchesBabble,        // true if any target member can match ThrownException
        boolean catchesNonErrorAny,   // true for untyped wildcard catch (Exception | ThrownException, excluding Error)
        List<HirExpr> body,
        List<HirVar> declaredVars,
        boolean opensNamespace,
        int wideningRank
) {}

record Try(
        List<HirExpr> tryBody,
        List<HirVar> tryDeclaredVars,
        boolean tryOpensNamespace,
        int tryWideningRank,
        List<HirCatch> catches,
        List<HirExpr> finallyBody,    // empty list if no finally clause
        List<HirVar> finallyDeclaredVars,
        boolean finallyOpensNamespace,
        ExprMeta meta
) implements HirExpr {}
```

`HirVerifier` checks:
* `Throw.value` is non-null.
* `Try` has at least one catch clause or non-empty `finallyBody`.
* Every `HirCatch.var` (if non-null) belongs to the enclosing function/method's frame and is properly declared.
* `HirPrinter` formats `throw <expr>` and `try (...) catch <v>:<desc> (...) finally (...)` cleanly for `-Dbabble.dumpIr`.

### 7.2 Compiled JVM bytecode lowering (`ControlAndOpEmitter` & `HirEmitter`)

#### 7.2.1 The JVM empty-operand-stack invariant (expression-oriented `try`)

On the JVM, entering an exception handler **clears the entire operand stack** and pushes only the caught `Throwable`. When `try` appears as a subexpression with live values already on the operand stack — for example:

```babble
def a = 1 + try (parse(s)) catch e: java.lang.NumberFormatException then (0)
def b = compute(firstArg(), try (secondArg()) catch e: java.lang.Exception then (fallback))
```

emitting `1` onto the operand stack and then entering `try (...)` would cause a `VerifyError` (`CheckClassAdapter` / StackMapTable frame mismatch between normal exit `[int, int]` and catch exit `[int]`).

**Solution (Operand-stack spilling before `Try`):**
Either during `HirBuilder` lowering or as a tiny ANTLR-free normalization pass (`HirTryNormalize`, running unconditionally before `HirEmitter` at every `-O` level including `-O0`):
* Whenever a compound expression (binary op, unary op, call arguments, array creation/op, field assign, `as`/`is`, etc.) contains a `HirExpr.Try` anywhere in a non-first operand, hoist all preceding sibling subexpressions (and the `Try` itself) into sequential synthetic temp `HirVar`s in a wrapping `HirExpr.Block` — preserving strict left-to-right evaluation order and exact `ExprMeta.line` coordinates!
* **Invariant guaranteed to `HirEmitter`:** Every `HirExpr.Try` is emitted with an **empty JVM operand stack** (just like loop heads for HotSpot OSR). Both the normal `tryBody` exit and every `catch` exit store their result into a dedicated `resultLocal` (typed with `try.meta().kind()`, unboxed for primitives!) and `GOTO tryEnd` with an empty stack, after which `tryEnd` loads `resultLocal`.

#### 7.2.2 Native `exception_table` & Babble `ThrownException` dispatch

```mermaid
flowchart TD
    Start["tryStart:\nemit tryBody -> store resultLocal"] -->|"normal exit"| FinN["emit finally (normal copy)\nGOTO done"]
    Start -->|"JVM exception_table\n(source order)"| H1["Java catch (e.g. java/io/IOException)\nstore e, run catchBody -> store resultLocal"]
    Start -->|"JVM exception_table\n(org/babblelang/runtime/ThrownException)"| HB["Babble catch dispatcher\nval = t.value()\nINSTANCEOF / Types.isInstance per clause"]
    H1 -->|"normal exit"| FinC["emit finally (catch copy)\nGOTO done"]
    HB -->|"matched"| FinC
    HB -->|"no clause matched"| Rethrow["ATHROW original ThrownException"]
    Start -->|"uncaught Throwable\n(catch-all for finally)"| FinE["store exLocal\nemit finally (abnormal copy)\nALOAD exLocal ; ATHROW"]
    H1 -->|"throws inside catch"| FinE
    HB -->|"throws inside catch"| FinE
    FinN --> Done["done:\nload resultLocal"]
    FinC --> Done
```

Key bytecode details:
* **Pure Java catches** (`catch e: java.io.IOException`, or multi-catch `IOException | IllegalArgumentException`): emit one `mv.visitTryCatchBlock(tryStart, tryEnd, catchLabel, internalName)` per union member — **zero runtime dispatch overhead**.
* **Babble catches** (`catch e: ParseError`, `catch e: AppError`, or mixed `ParseError | java.io.IOException`):
  * Emit `visitTryCatchBlock` for `"org/babblelang/runtime/ThrownException"` (and any Java `Throwable` members).
  * In the `ThrownException` handler, call `ThrownException.value()`, test against each matching clause in source order using direct `INSTANCEOF` (for statically known compiled Babble classes and JVM interfaces, `RM003`/`BIP020`) or `Types.isInstance` (for intersections/dynamic types), bind `e`, and execute the clause; if none match, rethrow the original `ThrownException` (preserving its stack trace!).
  * **Order preservation:** When a Java `catch` (e.g., `catch e: java.lang.RuntimeException`) precedes a Babble `catch` (e.g., `catch e: ParseError`), `ThrownException` must NOT be accidentally swallowed by `java.lang.RuntimeException`!
    * **Clean fix:** Make `ThrownException` extend `java.lang.Throwable` directly (not `RuntimeException` — JVM bytecode allows catching/throwing any `Throwable` without checked-exception restrictions, and `Throwables.sneakyThrow` handles Java callers!), OR filter `if (ex instanceof ThrownException) goto nextHandler` in `RuntimeException`/`Exception` handlers!
    * **Wait — making `ThrownException extends Throwable` (or `RuntimeException`?):** Let's check `BabbleCompiledScript.eval` and `Java` callers!
      - If `ThrownException extends RuntimeException`: a Babble `catch e: java.lang.Exception` would match `ThrownException` unless `ThrownException` is excluded, AND `catch e: java.lang.RuntimeException` followed by `catch e: ParseError` would either swallow `ParseError` or require a guard!
      - If `ThrownException extends Throwable` directly (package-scoped or `public final class ThrownException extends Throwable`):
        1. `java.lang.Exception` and `java.lang.RuntimeException` NEVER accidentally match a Babble `ParseError`!
        2. `catch e: java.lang.Throwable` DOES match `ThrownException` if desired (or only Java `Throwable`s — a crisp choice under `BIP028-Q2`!).
        3. Uncaught `ThrownException` is caught by `CompiledBabbleScript.eval` (`catch (Throwable t)`), `BabbleCompiledScript.eval` (widened to `catch (Throwable t)`), and `Launcher.run` (`catch (Throwable t)`).
        4. What if a Java caller invokes a Babble function implementing `java.lang.Runnable` that throws `ParseError`? If `ThrownException extends RuntimeException`, Java's `catch (RuntimeException e)` catches it naturally without sneaky checked-throw surprises.
      - This is an important JVM interop detail — we document both options cleanly in **`BIP028-Q2`** (with a guard on `Exception`/`RuntimeException` if `ThrownException extends RuntimeException`, vs `extends Throwable`).
* **ASM `visitTryCatchBlock` ordering:** All inner `try` blocks inside `tryBody` MUST register their `visitTryCatchBlock` entries before the enclosing `try` registers its own entries, because the JVM searches the `exception_table` linearly from first to last. Emitting child `try` blocks during `tryBody` emission and registering the enclosing `try`'s `visitTryCatchBlock` entries right after (or via a scoped list) guarantees correct innermost-first ordering automatically!

#### 7.2.3 `finally` emission, range splitting & code-size guard (`BIP028-Q9`)

Because JVM class files ≥ Java 6 prohibit `JSR`/`RET` (Babble targets Java 21 = class version 65 with `COMPUTE_FRAMES`), `finally` is lowered via **path duplication** (identical to `javac`, `kotlinc`, and `scalac`):

1. **`MethodEmitContext.activeFinallies` stack:**
   ```java
   record ActiveFinally(HirExpr.Try tryNode, int loopDepthAtEntry, List<Label> rangeSplits) {}
   ```
   When entering `tryBody` (or a `catchBody`) of a `Try` with a non-empty `finallyBody`, push an `ActiveFinally` onto `ctx.activeFinallies`.
2. **Non-local exit interception (`return`, `break`, `continue`):**
   * `emitReturn(r)`: evaluate `r.value()` into a fresh temp local `retTmp` (typed `methodReturnKind`); then for each `ActiveFinally` in `ctx.activeFinallies` (innermost to outermost):
     - **Range splitting:** emit a `splitEnd` label closing the active `try` range before the inline `finally` copy, emit `finallyBody` (with `ctx.activeFinallies` popped to that level so inner `finally` doesn't recursively cover itself!), and emit a `splitStart` label resuming the `try` range afterwards!
     - Finally, load `retTmp` and emit `returnInsn(methodReturnKind)`.
   * `emitBreak(b)` / `emitContinue()`: evaluate `b.value()` (if any) into `loop.resultSlot()`; emit all `ActiveFinally` entries whose `loopDepthAtEntry >= currentLoopDepth` (innermost to outermost, with range splitting); then `GOTO breakLabel`/`continueLabel`.
3. **Normal exit & abnormal `catch-all` exit:**
   * Normal exit of `tryBody` and each `catchBody` emits one `finallyBody` copy and jumps to `done`.
   * Abnormal exit registers `visitTryCatchBlock(segStart, segEnd, finallyExLabel, null)` across every split segment `[segStart, segEnd)` of `tryBody` and every `catchBody`, stores the caught `Throwable` in `exLocal`, emits `finallyBody`, reloads `exLocal`, and emits `ATHROW`.
4. **Nested-`finally` exponential-blowup guard (`BIP028-Q9`):**
   Duplicating a `finally` block $k$ times across nested `try`-`finally` levels can grow bytecode as $O(2^d)$ for nesting depth $d$. If a `finallyBody` exceeds a modest threshold (e.g., `> 32` HIR nodes and `activeFinallies.size() >= 2` or `>= 3` exit sites) and contains no non-local jumps (already guaranteed by Option 6A!), `HirEmitter` can outline `finallyBody` into a private synthetic helper method `fin$N(...)` taking the referenced locals/cells as parameters (exactly as `kotlinc` does for large `finally` blocks) — keeping JVM method size well below the 64 KB limit!

### 7.3 Interpreter parity (`Interpreter.java`)

[`Interpreter.visitThrowExpression`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) and `visitTryExpression` mirror the compiled semantics directly:
* `visitThrowExpression`: evaluates `ctx.expression()`, invokes `Throwables.toThrowable(val)`, and throws via `Throwables.sneakyThrow`.
* `visitTryExpression`:
  ```java
  ParserRuleContext failingAt = null;
  Throwable uncaught = null;
  Object result = null;
  try {
      result = runBlock(ctx.tryBlock);
  } catch (ReturnException | BreakException | ContinueException control) {
      throw control;   // bypasses all catch clauses; finally still runs!
  } catch (Throwable t) {
      failingAt = last;
      Match match = matchCatch(ctx.catchClause(), t);
      if (match != null) {
          failingAt = null;
          result = runCatchBlock(match.clause(), match.boundValue());
      } else {
          uncaught = t;
          throw Throwables.sneakyThrow(t);
      }
  } finally {
      if (ctx.finallyBlock != null) {
          runBlock(ctx.finallyBlock);
          if (uncaught != null && failingAt != null) {
              // Restore the original throwing line if finally completed normally
              last = failingAt;
          }
      }
  }
  return result;
  ```
* **Line-number fidelity:** Saving and restoring `last = failingAt` when `finally` completes normally on an uncaught exception guarantees that `BabbleCompiledScript.eval` reports the exact line inside `try` (or `catch`) that threw — matching compiled mode's stack-trace line (`CompiledBabbleScript.lineOf`) 100%! If `finally` itself throws, `last` stays at the throwing line inside `finally`, again matching compiled mode.

### 7.4 Nanopass optimizer (`BIP014` / `BIP027`) soundness

Every pass in `org.babblelang.opt` updates cleanly via exhaustive sealed-switch matching on `HirExpr`:
* **[`HirPurity`](../../src/main/java/org/babblelang/opt/HirPurity.java):**
  * `HirExpr.Throw` → `isPureNothrowTerminating` returns `false`.
  * `HirExpr.Try` → `isPureNothrowTerminating` returns `true` iff `tryBody`, every `catch.body`, and `finallyBody` are all `isPureNothrowTerminating` (and none opens a namespace scope).
* **`HirEGraphSimplifyPass` (`BIP027-I2`):** Rewrites inside `tryBody`, each `catch.body`, `finallyBody`, and `Throw.value` independently; never merges or moves expressions across a `Try` boundary.
* **`HirDcePass`:**
  * Truncates dead statements after an unconditional `HirExpr.Throw` in a block (matching `Return`/`Break`/`Continue`).
  * **Dead-catch elimination (at `-O1`):** When `tryBody` is proven `isPureNothrowTerminating`, no exception can ever be thrown from `tryBody` — `-O1` simplifies `try (body) catch ... finally (fin)` directly to `(body)` (if `fin` is empty) or `(def $r = body ; fin ; $r)`, eliminating the JVM `exception_table` completely!
* **`HirLicmPass`:** Already gates hoisting strictly on `isPureNothrowTerminating`, so throwing operations inside a `try` are **never** hoisted out of `try` (and `Try`/`Throw` themselves are never hoisted unless completely pure+nothrow+terminating).
* **`InlinePass`:**
  * In `BIP028-P3`, `isInlineCandidate` conservatively skips functions whose body contains `HirExpr.Try` (matching `SelfTailCall` in `BIP024`) while still allowing calls *inside* a `try` block to be inlined (preserving `ExprMeta.line` per `BIP014-D5`, and keeping inlined code inside the `try` range so exceptions thrown by the inlined body are caught identically!).
  * Functions containing `HirExpr.Throw` (without `Try`) CAN still be inlined safely because `Throw` has no handler range and preserves `ExprMeta.line`!
* **3-way parity:** `interpret`, `compile -O0`, and `compile -O1` (plus `-O2` when `BIP014-P3` SSA `MIR` lands, where `Try` maps to CFG exception edges / landing pads) must produce identical results, outputs, and error messages on every test case.

### 7.5 WebAssembly 3.0 alignment (`BIP025`)

`BIP025` targets WebAssembly 3.0's `try_table` + `exnref` + `tag` instructions. `BIP028`'s design aligns with `BIP025` by construction:
* `HirExpr.Try` and `HirExpr.Throw` are completely target-neutral (no ASM or `java.lang.reflect` types inside `HirExpr`).
* Custom Babble exceptions (`record ParseError(...)`, Option 2B) do not depend on `java.lang.Throwable`, lowering cleanly to Wasm `tag $babble_exn (param anyref)` + `throw $babble_exn` + `try_table (catch $babble_exn $handler)` + `br_on_cast`!
* Portable Babble programs that catch their own domain exceptions (or Babble runtime errors via `BabbleException`) work identically on both JVM and WebAssembly.

---

## 8. Resource Management (`AutoCloseable` & `using`, `BIP028-Q10`)

One of the top real-world uses of `try`/`finally` on the JVM is deterministic cleanup of `java.lang.AutoCloseable` resources (`BufferedReader`, `InputStream`, `Socket`, `Connection`).

With `BIP028`'s `try`/`catch`/`finally` combined with Babble's existing `BIP021` bounded generics (`of (R: java.lang.AutoCloseable, T)`) and `BIP017` callback-effect forwarding (`fn calling f`), Kotlin-style `.use` is already expressible in **plain Babble** with full effect forwarding and `Throwable.addSuppressed`:

```babble
export def using = fn of (R: java.lang.AutoCloseable, T) calling f (
    resource: R,
    f: fn (r: R): T
): T -> (
    def primary: java.lang.Throwable? = null
    try (
        f(resource)
    ) catch e: java.lang.Throwable then (
        primary = e
        throw e
    ) finally (
        try (
            resource.close()
        ) catch closeErr: java.lang.Throwable then (
            if primary != null then (
                primary.addSuppressed(closeErr)
            ) else (
                throw closeErr
            )
        )
    )
)

;; Caller:
def text = using(java.nio.file.Files.newBufferedReader(path), fn (r) -> (
    r.readLine()
))
```

Notice what works out of the box here:
* `primary != null` narrows `primary` from `java.lang.Throwable?` to `java.lang.Throwable` (`BIP008`).
* `calling f` forwards `f`'s `io`/`volatile`/`mut` effects honestly (`BIP017`).
* Note one subtle `BIP016` detail: `resource.close()` is `io` on `java.lang.AutoCloseable`, so `using` itself is `fn io calling f` (unless an `axiom` or a specialized pure closeable interface is used).

**`BIP028-Q10` asks:** Should `BIP028` stop at `try`/`catch`/`finally`/`throw` (leaving `using` to the future standard library), OR should `BIP028` also add dedicated `try`-with-resources syntax (e.g., `try (def r = open()) then (...)` or `try with r = open() then (...)`)?
* **Recommendation:** Keep `BIP028` focused on `try`/`catch`/`finally`/`throw` and leave `using` to `std/` (or a small follow-up), because `using` needs zero new syntax, handles `addSuppressed`, and avoids adding a fourth `try` clause form to `Babble.g4`.

---

## 9. Implementation Plan & Phases

`BIP028` splits into five self-contained, independently verifiable phases, each keeping 3-way `mvn package` (`interpret`, `compile -O1`, `compile -O0`) and `-Pbatest` green:

* **`BIP028-P1` — Runtime exception model & Java interop cleanup.**
  * Add `org.babblelang.runtime.Throwables` (`sneakyThrow`, `unwrapReflection`, `toThrowable`, `unwrapForCatch`) and `org.babblelang.runtime.ThrownException` (staying strictly inside `java.base`, verified by `RuntimeBoundaryTestCase`).
  * Retire `JavaTypes.wrapInvocationException` and `CallEmitter.emitWrappedJavaInvoke` (unwrapping only `InvocationTargetException` in reflective `JavaMethod`/`JavaClass`/`JavaLinker`); move uncaught exception formatting (`NPE` Helpful-message stripping, `ClassName : msg` space-before-colon) into `ArraySupport.formatThrowable` / script boundaries (`BabbleCompiledScript`, `CompiledBabbleScript`, `Launcher`).
  * Audit `ConvertFunction`, `PrintFunction`, and `JavaDefaultCall` so every existing test passes identically in all 3 modes before any new syntax lands.
* **`BIP028-P2` — Grammar, `Formatter`, REPL & front-end analysis.**
  * Add `TRY`, `CATCH`, `FINALLY`, `THROW` tokens (exempted in `memberName`), `# tryExpression`, `catchClause`, and `# throwExpression` to `Babble.g4`; verify 0 predicates and pure `PredictionMode.SLL` (`ParserGrammarTestCase`).
  * Update `Formatter` (`try (`, `) catch ... then (`, `) finally (`, `throw `) and `Completions` / `BabbleSession.classify`.
  * Implement static checks across `BlockScopes` (scopes, read-only catch variable, forbidding `return`/`break`/`continue` escaping `finally`), `Resolver` (catch local / capture cell), `TypeChecker` & `Narrowing` (`Type.NOTHING` for `throw`, `join` & numeric widening for `try`, catch-type validity, multi-catch unions, unreachable-catch diagnostics, narrowing & definite-init resets), `TailCalls` (`BIP024` tail-position & `forever` rules), `EffectBodyWalker` / `EffectChecker` (`nothrow` restoration, purity, `@MOVED`/`unique` joins, `throw` escape provenance, `finally` unused-pure checks), `curated.ba` `Throwable` axioms, and `LoweringAnalyzer` (target descriptors & JVM catch class names).
* **`BIP028-P3` — `Interpreter`, `HIR`, `HirEmitter` & Nanopass optimizer.**
  * Implement `visitThrowExpression` and `visitTryExpression` in `Interpreter` (protecting `ReturnException`/`BreakException`/`ContinueException`, restoring `last` across normal `finally` on uncaught exceptions).
  * Add `HirExpr.Throw`, `HirExpr.Try`, and `HirCatch`; update `HirBuilder` (including empty-operand-stack temp spilling for `Try` inside compound expressions), `HirVerifier`, `HirPrinter`, and `HirRewriter`.
  * Implement `emitThrow` and `emitTry` in `ControlAndOpEmitter` / `MethodEmitContext` (`activeFinallies`, `try`-range splitting on `return`/`break`/`continue`, native JVM `visitTryCatchBlock` + `ThrownException` dispatch, nested-`finally` outlining guard), verified by `CheckClassAdapter`.
  * Update `HirPurity`, `HirEGraphSimplifyPass`, `HirDcePass` (dead code after `Throw` and dead-catch elimination when `tryBody` is pure+nothrow+terminating), `HirLicmPass`, and `InlinePass`.
* **`BIP028-P4` — Comprehensive testing & coverage.**
  * Add end-to-end `src/test/babble/Exceptions.ba` (auto-run in all 3 modes, type-checked, and format-checked).
  * Add systematic `.batest` suites under `src/test/batest/` (e.g., `control_flow/try_catch_finally.batest` and `effects/exceptions_effects.batest`): Java exceptions (checked `IOException`, unchecked `NumberFormatException`, `ArrayIndexOutOfBoundsException`, `NullPointerException`), custom Babble `record`/`class`/`interface` exceptions, multi-catch unions + `is` narrowing, expression values & primitive unboxing, `1 + try (...)` stack spilling, nested `try`/`catch`/`finally`, `return`/`break`/`continue` through multiple `finally` blocks, `Interpreter.last` line parity, `BIP024` tail calls inside `try` vs `catch`, `nothrow`/`pure`/`unique`/`@BORROWED` effect diagnostics, unreachable catch errors, and `finally` jump errors.
  * Add targeted JUnit tests in `TypeCheckerTestCase`, `EffectsTestCase`, `FormatterTestCase`, `ReplTestCase`, `BabbleCompilerTestCase` (verifying `visitTryCatchBlock` and no `emitWrappedJavaInvoke` overhead), `OptimizerTestCase` (dead-catch elimination, LICM barrier, inlining inside `try`), `BabblecTestCase` (AOT jar uncaught & caught exceptions), and confirm `mvn -q verify -Pbatest` meets `>= 75%` branch coverage across all 5 scopes and `ArchitectureBoundaryTestCase` stays green.
* **`BIP028-P5` — Documentation & examples.**
  * Update [`README.md`](../../README.md) (tour section & *Implemented Features* checklist), [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md), [`docs/design/README.md`](README.md), and promote `BIP028-P1`–`P5` to `BIP028-I1`–`I5` via `src/tools/mark_bip_implemented.py`.

---

## 10. Open Questions

Each open question (`BIP028-Q1`–`Q10`) will be settled into a numbered decision (`BIP028-D#`) before implementation starts:

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **`BIP028-Q1`** | What is the exact syntax of `catch` clauses? | **1A:** `catch e: T then (...)` (and unnamed `catch : T then (...)` / `catch then (...)`), mirroring `for`/`if`; **1B:** `catch (e: T) -> (...)`, mirroring `fn`; **1C:** `catch (e: T) (...)` | **1A** (`catch e: T then (...)`, strictly LL(1)/SLL and consistent with `for`/`if`/`while`) |
| **`BIP028-Q2`** | What values can `throw` raise, how are custom Babble exceptions represented, and how is `BabbleException` named? | **2A:** `java.lang.Throwable` only; **2B:** `java.lang.Throwable` OR Babble nominal instances via `ThrownException` carrier (sub-choice: **2B-i** any Babble class/record/object/interface vs **2B-ii** marker interface; and `ThrownException extends Throwable` vs `RuntimeException`); **2C:** `exception class` extending `RuntimeException` | **2B-i** (open Babble nominal instances via `ThrownException` carrier + `java.lang.Throwable`, plus `BabbleException` exposed in prelude; aligns with `BIP020-D18` and `BIP025` Wasm) |
| **`BIP028-Q3`** | Should Java exceptions propagate directly (retiring `JavaTypes.wrapInvocationException` & `CallEmitter.emitWrappedJavaInvoke`) or remain wrapped and be unwrapped at `catch`? | **3A:** Direct JVM `Throwable` propagation (`Throwables.sneakyThrow` on reflection paths) + boundary formatting in `ArraySupport.formatThrowable`; **3B:** Keep wrapping and unwrap in `catch` | **3A** (native JVM `exception_table` dispatch, smaller bytecode, clean `RM003` Java-interface bridges, 100% preserved uncaught formatting) |
| **`BIP028-Q4`** | Should untyped `catch e then (...)` be supported, and should it exclude `java.lang.Error`? | **4A:** Allow untyped `catch e then (...)` matching all non-`Error` exceptions (`Exception` + Babble thrown values), requiring explicit `java.lang.Error` / `java.lang.Throwable` to catch JVM errors; **4B:** Untyped catch matches all `Throwable`; **4C:** Always require an explicit catch type | **4A** (beginner-friendly catch-all that never silently swallows `OutOfMemoryError`/`StackOverflowError`) |
| **`BIP028-Q5`** | Should `try` without `catch` (`try (...) finally (...)`) and discarded `finally` value match Kotlin/Scala expression rules? | **5A:** Expression-oriented: result is `join(try, catches...)`, `finally` value discarded, `throw` has type `Type.NOTHING`; **5B:** Statement-only `try` returning `null` | **5A** (consistent with Babble's "everything is an expression" design across `if`/`while`/`for`) |
| **`BIP028-Q6`** | Should `return`, `break`, and `continue` targeting outside a `finally` block be forbidden at compile time? | **6A:** Statically forbidden in `BlockScopes` (C# / Swift / Python PEP 765 rule; inner `fn`/loops inside `finally` still allowed); **6B:** Allowed with warning (Java/Kotlin rule) | **6A** (eliminates silent exception swallowing and simplifies `HirEmitter`) |
| **`BIP028-Q7`** | How should `try`/`catch` interact with `nothrow` inference and dead `try` blocks? | **7A:** Restore `nothrow` on exhaustive non-`Error` catch-all when all `catch` and `finally` blocks are `nothrow`, plus a warning when `tryBody` is statically `nothrow` (outside `calling` HOFs); **7B:** Full static throw-set inference per function/Java method | **7A** now (100% sound with zero `curated.ba` churn; **7B** remains a clean backwards-compatible future refinement) |
| **`BIP028-Q8`** | How should `throw` handle `@BORROWED` parameters and `Throwable` stack-trace purity? | **8A:** `throw` conservatively escapes its operand (requiring `shared`, `unique`+`move`, or deeply immutable/`frozen` contents) and `curated.ba` marks `getStackTrace`/`fillInStackTrace` appropriately; **8B:** Local escape analysis allowing `@BORROWED` inside locally-caught `throw` | **8A** (simple, local, 100% sound for `BIP016` ownership; **8B** can relax locally-caught throws later) |
| **`BIP028-Q9`** | How should `finally` handle deep nesting / code duplication in `HirEmitter`? | **9A:** Inline `finally` duplication on every exit path with `try`-range splitting, plus automatic helper-method outlining above a size/duplication threshold; **9B:** Pure inline duplication only | **9A** (matches `javac`/`kotlinc`, zero call overhead for typical small `finally` blocks, safe against 64 KB JVM method overflow on deep nesting) |
| **`BIP028-Q10`** | Should `BIP028` add dedicated `try`-with-resources syntax for `AutoCloseable`, or rely on a `BIP017` `using` helper in `std/`? | **10A:** Defer `using` HOF (with `fn calling f` and `addSuppressed`) to `std/`, keeping `BIP028` syntax minimal; **10B:** Add `try (def r = ...) then (...)` syntax in `BIP028` | **10A** (`using` requires zero new syntax and composes cleanly with `BIP017` `fn calling f`) |
