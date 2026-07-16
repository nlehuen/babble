# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Babble is a toy programming language running on the JVM, built as a learning project for parsing/interpreting. It uses ANTLR4 for parsing and currently runs in interpreted mode directly on the ANTLR parse tree (no bytecode compilation yet). Java 8 source level, JUnit 4 for tests.

## Commands

- Build & test: `mvn package` (CI runs `mvn -B package` on JDK 11)
- Run tests only: `mvn test`
- Run a single test class: `mvn test -Dtest=BabbleFunctionsTestCase`
- Run a single test method: `mvn test -Dtest=BabbleFunctionsTestCase#methodName`

### No local Java/Maven: use podman

The host (immutable Fedora) has no Java or Maven installed. Run Maven in a container instead, substituting any `mvn ...` command:

```
podman run --rm -v /var/home/nlehuen/babble:/work:z -v ~/.m2:/root/.m2:z -w /work \
    docker.io/library/maven:3.9-eclipse-temurin-11 mvn -B clean test
```

The image matches CI's JDK 11; mounting `~/.m2` caches dependencies between runs, and the `:z` volume flags are required for SELinux.

The ANTLR lexer/parser/visitor classes (`org.babblelang.parser.*`) are **generated** by the `antlr4-maven-plugin` into `target/generated-sources/antlr4` from `src/main/antlr4/org/babblelang/parser/Babble.g4`. Never edit generated parser code; change the grammar and rebuild. Grammar changes require `mvn generate-sources` (or any build) before the new contexts are visible to Java code.

## Architecture

The pipeline is: Babble source → ANTLR4 parse tree → tree-walking interpreter. There is no separate AST; the interpreter visits ANTLR `ParserRuleContext` nodes directly.

- **Grammar** (`src/main/antlr4/.../Babble.g4`): ~100 lines defining the whole language. Everything is an `expression` (no statements); labeled alternatives (`# defExpression`, `# call`, etc.) become the visitor methods. Visitor generation is on, listener off. Newlines are significant: `NL` tokens (with `;`) separate statements, and `NL*` is permitted only after continuation tokens (binary operators, `,`, `=`, `->`, `then`, `else`) — never between an expression and a following `(`, which would recreate the call/juxtaposition ambiguity.
- **Engine entry point** (`org.babblelang.engine`): JSR-223 (`javax.script`) implementation. `BabbleScriptEngineFactory.INSTANCE` → `BabbleScriptEngine` (lexes/parses, `Compilable`) → `BabbleCompiledScript.eval()` which creates an `Interpreter` and visits the file. The engine's constructor registers built-in "implicits": `print`/`println`, `assert`, `import`, and the `java`/`javax` root packages (several have French aliases, e.g. `suppose`, `affiche`).
- **Interpreter** (`org.babblelang.engine.impl.Interpreter`): extends the generated `BabbleBaseVisitor<Object>`; one `visitX` method per grammar alternative. It tracks the `last` visited context so `BabbleCompiledScript` can report the line number on error.
- **Scoping** (`impl` package): `Scope<T>` interface with `Slot` holders; `Namespace` is the main implementation supporting `enter()`/`leave()` for nested scopes and closures. `BabbleObject` (object literals) and packages are namespaces too. `Function` captures its defining `Namespace` as a closure and defines `$recurse` for the `recurse` keyword.
- **Callables**: the `Callable` interface unifies Babble `Function`s, `BoundMethod`, and natives. Native functions live in `impl.natives`; Java interop (calling Java classes from Babble) lives in `impl.natives.java` (`JavaPackage` → `JavaClass` → `JavaMethod`/`BoundJavaMethod`/`JavaObject`).
- **Optimizers** (`org.babblelang.engine.optimizer`): visitors that rewrite the parse tree in place. `OptimizerBase` provides a `replace()` helper to swap one context node for another; `SimpleBinaryOpsOptimizer` is the example (constant folding of binary ops).

## Tests

Tests live in `src/test/java/org/babblelang/tests`. Most extend `BabbleTestBase`, which offers `interpret(String)` for inline snippets and `interpretFile(String path)` for `.ba` scripts. End-to-end language tests are `.ba` files in `src/test/babble/` (referenced by relative path, so tests must run from the repo root, as Maven does). In-language `assert(...)` calls are the assertion mechanism inside `.ba` scripts.
