# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Babble is a toy programming language running on the JVM, built as a learning project for parsing/interpreting/compiling. It uses ANTLR4 for parsing and runs either interpreted directly on the ANTLR parse tree (the default) or compiled to JVM bytecode with ASM (`-Dbabble.mode=compile`). Java 21 source level, JUnit 5 (Jupiter) for tests. The compiler follows `docs/design/bytecode-compiler.md` (phase 1 done; §16 there lists what landed).

## Commands

- Build & test: `mvn package` (CI runs `mvn -B package` on JDK 21). Surefire runs the suite twice: `default-test` (interpreter) and `compiled-tests` (`babble.mode=compile`, plus `babble.verify=true` so every generated class passes ASM's `CheckClassAdapter`; reports in `target/surefire-reports-compiled`).
- Run tests only: `mvn test`
- Run a single test class: `mvn test -Dtest=BabbleFunctionsTestCase`
- Run a single test method: `mvn test -Dtest=BabbleFunctionsTestCase#methodName` (runs in both modes)
- Compiler debugging switches (system properties): `babble.dumpClasses=<dir>` writes the generated `.class` files for `javap -c -p`, `babble.trace=true` prints an ASM listing, `babble.verify=true` runs `CheckClassAdapter`.
- Benchmarks (not a test): `org.babblelang.bench.Benchmarks` in the test sources, run from the repo root.

### Local toolchain, and podman for CI parity

Maven and a JDK are installed on the host via Homebrew (`/home/linuxbrew/.linuxbrew/bin`), so plain `mvn ...` works and is the fastest way to build and test.

Note the version gap: the host JDK is **26**, CI runs **21**. The build sets `release 21`, so compilation is checked against the right API level either way, but tests execute on whichever JVM runs them. To reproduce CI exactly — or to check a failure that might be JVM-version-specific — run Maven in a container instead, substituting any `mvn ...` command:

```
podman run --rm -v /var/home/nlehuen/babble:/work:z -v ~/.m2:/root/.m2:z -w /work \
    docker.io/library/maven:3.9-eclipse-temurin-21 mvn -B clean test
```

The image matches CI's JDK 21; mounting `~/.m2` caches dependencies between runs, and the `:z` volume flags are required for SELinux.

The ANTLR lexer/parser/visitor classes (`org.babblelang.parser.*`) are **generated** by the `antlr4-maven-plugin` into `target/generated-sources/antlr4` from `src/main/antlr4/org/babblelang/parser/Babble.g4`. Never edit generated parser code; change the grammar and rebuild. Grammar changes require `mvn generate-sources` (or any build) before the new contexts are visible to Java code.

## Architecture

The pipeline is: Babble source → ANTLR4 parse tree → block scope analysis → tree-walking interpreter *or* bytecode compiler. There is no separate AST; both backends work on ANTLR `ParserRuleContext` nodes directly.

- **Grammar** (`src/main/antlr4/.../Babble.g4`): ~100 lines defining the whole language. Everything is an `expression` (no statements); labeled alternatives (`# defExpression`, `# call`, etc.) become the visitor methods. Visitor generation is on, listener off. Newlines are significant: `NL` tokens (with `;`) separate statements, and `NL*` is permitted only after continuation tokens (binary operators, `,`, `=`, `->`, `then`, `else`) — never between an expression and a following `(`, which would recreate the call/juxtaposition ambiguity.
- **Engine entry point** (`org.babblelang.engine`): JSR-223 (`javax.script`) implementation. `BabbleScriptEngineFactory.INSTANCE` → `BabbleScriptEngine` (lexes/parses, runs the analysis, `Compilable`). `getMode()` picks `INTERPRET` (`BabbleCompiledScript`) or `COMPILE` (`compiler.CompiledBabbleScript`) from the `babble.mode` engine attribute or system property. The engine's constructor registers built-in "implicits": `print`/`println`, `assert`, `import`, and the `java`/`javax` root packages (several have French aliases, e.g. `suppose`, `affiche`). `createRuntime(ScriptContext)` builds each eval's namespaces: implicits → `STDOUT` (the context writer) → script root (ENGINE_SCOPE bindings + top-level defs).
- **Analysis** (`org.babblelang.analysis`): `BlockScopes` runs in both modes at `compile()` time. It computes each scope's `def`s (`ScopeAnalysis`) for hoisting, and rejects redefinitions, `def` in a `while` test, and `return` outside a function body or in a parameter default. Its errors become `ScriptException`s worded like runtime ones.
- **Block scope**: the file, function literals (parameters + body top level), packages, objects, and every other block (block expression, `if` branch, `while` body — fresh per iteration) are scopes. Names a scope defines are declared (unset) on entry; reading or assigning them before their `def` runs fails with `Name used before definition`. Blocks that define nothing get no namespace.
- **Runtime** (`org.babblelang.runtime`, no ANTLR dependency): everything both backends call — `Operators`, `Truth`, `Members`, `Arguments` (the one implementation of argument binding), `Calls`, the `Callable` calling convention `call(ScriptRuntime, Object[] args, String[] names, CallSite)`, `BabbleFunction`/`BoundMethod`, `CompiledFunction`/`FunctionBody`, `CallSite`/`OpSite` (source texts for error messages), and `ScriptRuntime` (per-eval root namespace, `ScriptContext`, engine).
- **Interpreter** (`org.babblelang.engine.impl.Interpreter`): extends the generated `BabbleBaseVisitor<Object>`; one `visitX` method per grammar alternative. It tracks the `last` visited context so `BabbleCompiledScript` can report the line number on error. `return` is a `ReturnException` caught by `Function.invoke`.
- **Compiler** (`org.babblelang.compiler`): `CodeGenerator` emits one class per script (`org/babblelang/generated/Script$N`): `run(ScriptRuntime, Namespace)` (split into `run$k` when large), one `fn$N$name` static method per function literal (wrapped as `FunctionBody` via `LambdaMetafactory`), and static constant fields built in `<clinit>` helpers. Invariant: every expression leaves exactly one `Object` on the stack. It keeps the interpreter's data model (`Namespace` lookups), compiles `return` to `ARETURN`, and loads through a per-compile `ScriptClassLoader`. Error lines come from the innermost script-class stack frame with a line number.
- **Scoping** (`impl` package): `Scope<T>` interface with `Slot` holders (`set` initialises, `assign` requires the slot to be set); `Namespace` is the main implementation (`child`, `enterPackage`, `declare`, `initialize`, `assign`, `lookup`). `BabbleObject` (object literals) and packages are namespaces too; `BabbleObject.get` binds functions found through its parent chain as `BoundMethod`s.
- **Natives**: built-ins implement `runtime.Callable`. Native functions live in `impl.natives` (`print` writes to the `ScriptContext` writer, or `to:` any `Appendable`); Java interop lives in `impl.natives.java` (`JavaPackage` → `JavaClass` → `JavaMethod`/`BoundJavaMethod`/`JavaObject`).
- **Optimizers** (`org.babblelang.engine.optimizer`): visitors that rewrite the parse tree in place. `OptimizerBase` provides a `replace()` helper to swap one context node for another; `SimpleBinaryOpsOptimizer` is the example (constant folding of binary ops). The engine does not run them.

## Tests

Tests live in `src/test/java/org/babblelang/tests`. Most extend `BabbleTestBase`, which offers `interpret(String)` for inline snippets and `interpretFile(String path)` for `.ba` scripts. End-to-end language tests are `.ba` files in `src/test/babble/` (referenced by relative path, so tests must run from the repo root, as Maven does). In-language `assert(...)` calls are the assertion mechanism inside `.ba` scripts. `DifferentialTestCase` runs every `.ba` file and a list of snippets in both modes and compares results, output and errors; `BabbleCompilerTestCase` covers compiled-mode specifics (method names, stack-trace lines, class verification, method/constant splitting).
