# BIP015: Down with the ants

| | |
|---|---|
| **BIP** | 015 |
| **Date** | 2026-11-19 |
| **Status** | Draft (Exploratory / Neutral Options Analysis; Open Questions `BIP015-Q1`–`Q8`) |
| **Depends on** | Bytecode compiler ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), interactive mode ([bip004_repl.md](bip004_repl.md)), module sources & AOT runtime boundary ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), `.batest` differential & coverage harness ([bip009_test-coverage.md](bip009_test-coverage.md)), modular type-checker architecture (`RM004`, [bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), predicate-free LL(1)/LL(2) grammar (`RM006`, [bip012_grammar-improvements.md](bip012_grammar-improvements.md)), compiler optimization passes & `HIR` (`BIP014-D1`–`D7`, [bip014_optimization-passes.md](bip014_optimization-passes.md)) |
| **Scope** | Non-committal architectural evaluation of keeping vs. replacing ANTLR4 (`antlr4-maven-plugin` and `antlr4-runtime`) across six concrete options — including an audit of ANTLR4 4.13.2 vs. newer releases and forks — comparing pros and cons across the Babble **Interpreter** vs. **Compiler** (both pre-`BIP014` and post-`BIP014-P1`), `Formatter` trivia fidelity, future IDE/LSP error resilience (`BIP004` §8), and future Babble macros (`BIP014-D4`) |

---

## 1. Purpose & Non-Committal Scope

Following `BIP012` (`RM006`) and `BIP007-P2` (`RM007`), [`Babble.g4`](../../src/main/antlr4/org/babblelang/parser/Babble.g4) is now a **242-line, zero-predicate, LL(1)/LL(2) grammar** that parses every `.ba` and `.batest` source in the repository in pure `PredictionMode.SLL` with zero `LL` fallback (verified by [`ParserGrammarTestCase`](../../src/test/java/org/babblelang/tests/ParserGrammarTestCase.java)). In parallel, `BIP014` (`BIP014-D1`–`D7` decided) plans to introduce a Babble-owned, Java 21 sealed-record High-Level Typed IR (`org.babblelang.ir.hir`) in `BIP014-P1` that detaches the compiler backend from ANTLR4's parse tree.

Together, `BIP012` and `BIP014` reopen a foundational front-end question: **should Babble keep ANTLR4, isolate it behind a Babble-owned AST, or remove the dependency on ANTLR4 altogether?**

This document **intentionally does not commit to an option yet**. Its goal is to lay out:

1. An exact inventory of how ANTLR4 is coupled to Babble today (§2), including an audit of ANTLR4 **4.13.2** against newer upstream releases and active forks (§3).
2. A neutral, state-of-the-art (SOTA) review of **six concrete options** with verified literature and pros/cons (§4–§5):
   * **Option 1:** Status quo — ANTLR4 4.13.2 (and upstream/fork comparison).
   * **Option 2:** Keep ANTLR4 behind a Babble-owned Java 21 sealed-record AST.
   * **Option 3:** Alternative LL/LALR JVM parser generator (JavaCC / CongoCC, JFlex + CUP / BYacc/J).
   * **Option 4:** Tree-sitter (incremental GLR C runtime via Java FFM bindings).
   * **Option 5:** PEG library or generator (Parboiled, Mouse, PetitParser, Autumn, or custom `pegen`-style generator).
   * **Option 6:** Hand-written lexer + recursive-descent & Pratt parser producing a Java 21 sealed-record AST.
3. A side-by-side analysis of the **asymmetric impact on the Babble Interpreter vs. the Babble Compiler**, evaluated across **both timelines**: **pre-`BIP014`** (monolithic `CodeGenerator.java`) and **post-`BIP014-P1`** (`HIR` + `HirBuilder` + `HirEmitter`/`MirEmitter`) (§6).
4. Dedicated comparisons across three high-leverage front-end dimensions (§7):
   * `Formatter` trivia preservation & round-trip fidelity (`Formatter.java`),
   * Future IDE / LSP support & error-resilient parsing (`BIP004` §8),
   * Future Babble macros & syntactic extensibility (`BIP014-D4`).
5. Neutral decision criteria and open questions (`BIP015-Q1`–`Q8`) to settle before choosing a direction (§8).

---

## 2. Current State: ANTLR4's Footprint in Babble

### 2.1 Build & Dependency Footprint

In [`pom.xml`](../../pom.xml):

* **Runtime/compiler dependency:** `org.antlr:antlr4-runtime:4.13.2` is one of Babble's only two non-JDK runtime dependencies (alongside `org.ow2.asm:asm:9.11`).
* **Build plugin:** `org.antlr:antlr4-maven-plugin:4.13.2` generates `BabbleLexer`, `BabbleParser`, `BabbleVisitor`, and `BabbleBaseVisitor` (`<visitor>true</visitor>`, `<listener>false</listener>`) from the 242-line [`Babble.g4`](../../src/main/antlr4/org/babblelang/parser/Babble.g4).
* **AOT runtime boundary (`babble-runtime.jar`):** Already **100% free of ANTLR4** (and ASM). `RuntimeBoundaryTestCase` (`BIP006` §3) verifies that compiled AOT programs depend only on `java.base`.
* **Coverage exclusion:** Under `-Pbatest` (`BIP009`), JaCoCo excludes `org.babblelang.parser.*` because the parser is generated code.

### 2.2 Codebase Coupling Inventory

A static scan of `src/main/java` and `src/test/java` finds ANTLR4 and generated `org.babblelang.parser.*` types referenced across **41 Java files**:

| Subsystem | Key Files | Nature of ANTLR4 Coupling |
|---|---|---|
| **Engine & REPL** | [`BabbleScriptEngine.java`](../../src/main/java/org/babblelang/engine/BabbleScriptEngine.java) (788 lines), [`BabbleSession.java`](../../src/main/java/org/babblelang/engine/BabbleSession.java) (117 lines) | Two-stage `PredictionMode.SLL` → `LL` parsing with `BailErrorStrategy`, `CommonTokenStream`, `CharStreams`. `BabbleSession.classify` detects incomplete REPL inputs via `ParseFailure.atEnd()` plus a second `BabbleLexer` pass filtering `HIDDEN`-channel comments, `NL`, and `;`. |
| **Formatter** | [`Formatter.java`](../../src/main/java/org/babblelang/format/Formatter.java) (472 lines) | Consumes the full token list including `HIDDEN`-channel `COMMENT` and `MULTILINECOMMENT` tokens; walks `BabbleParser.FileContext` to classify every `NL`, `;`, `(`, and unary `-`; re-parses formatted output to verify parse-tree equivalence. |
| **Static Analysis (`BIP010`)** | `BlockScopes`, `Resolver`, `TypeChecker`, `EffectChecker`, `CallSolver`, `LoweringAnalyzer`, `TypeAnnotations`, `MemberTypes`, `OperatorTypes`, `Narrowing`, `SlotInference`, `StaticRefs`, `ScopeAnalysis`, `TypedProgram`, `TypedModuleSummary`, `AnalysisSession` (~16 files) | Walks `BabbleParser.*Context` directly. Stores over 25 side tables (`IdentityHashMap<ParserRuleContext, ...>` and `ParseTreeProperty<...>`) keyed on ANTLR4 CST node identity. *(Note: `BIP010-P4` already removed the ANTLR visitor pattern from `TypeChecker` in favor of explicit `typeOf(ctx, expected, role)` dispatch.)* |
| **Interpreter** | [`Interpreter.java`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) (827 lines), `BabbleCompiledScript`, `Function` | Extends `BabbleBaseVisitor<Object>`. Evaluates scripts by **walking ANTLR4 CST nodes at runtime**, caching `OpSite` / `CallSite` / `String[]` in `ParseTreeProperty<...>` and tracking `last` (`ParserRuleContext`) for runtime error line reporting. |
| **Compiler (pre-`BIP014`)** | [`CodeGenerator.java`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) (5,996 lines), `BabbleCompiler` | Walks `BabbleParser.*Context` **once** at compile time to emit ASM bytecode. Emitted `.class` files retain zero ANTLR4 or parse-tree references. *(Post-`BIP014-P1`, this coupling shrinks to `HirBuilder` alone.)* |
| **Tests** | `ParserGrammarTestCase`, `ResolverTestCase`, `TypeCheckerTestCase`, `EffectsTestCase`, `ScriptTestCase`, `InterpreterNamespaceTest`, `Rm003P1AnalysisTestCase`, `BatestCoverageReporter` | Direct SLL/predicate grammar invariants and AST/scope unit tests. |

### 2.3 Grammar Simplicity After `BIP012` (`RM006`)

Before `BIP012`, `Babble.g4` relied on 172 lines of `@parser::members` Java lookahead and 10 semantic predicates. Today:

* **Zero predicates, zero `@parser::members`.**
* **Strictly LL(1)/LL(2):** The only 2-token lookahead points are named call arguments (`(memberName ':')? expression`), optional parameter names in function types (`(ID ':')? type`), and `effectTarget` (`ID '(' STRING ')'` vs. `memberName`).
* **Textbook operator precedence** (`-` unary > `as` > `*`/`/` > `+`/`-` > `is` > comparisons > `not` > `and` > `or` > right-associative `=`/`+=`/`-=`/`*=`/`/=`, plus `return` prefix at lowest precedence).
* **Localized significant newlines (`NL`):** `sep` separates expressions; `NL*` is permitted only after continuation tokens and before `.`/`?.`/`else`/`static`, never between an expression and `(` or unary `-`.

---

## 3. ANTLR4 Version & Ecosystem Audit (`4.13.2` vs. Newer Versions & Forks)

A natural first question before replacing ANTLR4 is whether a newer release of ANTLR4 (or ANTLR 5) already addresses its runtime, memory, or maintenance trade-offs.

| Distribution | Latest Release | Status (as of Nov 2026) | Comparison vs. Babble's `4.13.2` |
|---|---|---|---|
| **Upstream `org.antlr:antlr4` / `antlr4-runtime`** (`[ANTLR4]`) | **4.13.2** (August 3, 2024) | **Babble is already on the latest official release.** No `4.14.0` or `5.0` release has shipped in over two years. | Nothing newer to upgrade to upstream. Minor releases also break runtime/generated-code binary compatibility (`4.x` → `4.y` requires regeneration). |
| **Upstream Security & Maintenance Signal** (`[GHSA-ANTLR]`) | Affects `<= 4.13.2` (unpatched upstream) | `CVE-2026-13503` / `GHSA-w346-wxvq-p3xr` (June 2026, unreviewed): path traversal in `TokenVocabParser.getImportedVocabFile` inside the **build-time tool** (`tool/`), with no vendor response noted. | **Does not affect `antlr4-runtime` or Babble at runtime** (and Babble does not use `tokenVocab` with untrusted grammars), but it is a concrete data point on upstream `antlr/antlr4` maintenance velocity. |
| **`tunnelvisionlabs/antlr4`** ("Optimized Fork", `[TVL]`) | Diverged Java fork (Sam Harwell) | Separate repository and release cadence; not published under `org.antlr` coordinates. | Features a fully-reduced `PredictionContextCache` (dramatically lower DFA memory on large ambiguous grammars, `[ANTLR4-Mem]`) and concurrent parsing improvements (`[ANTLR4-Conc]`). Less impactful for Babble's already-small pure-`SLL` grammar, and introduces a non-standard dependency fork. |
| **`antlr-ng`** (`mike-lischke/antlr-ng`, `[ANTLR-NG]`) | Active TypeScript port (launched 2025) | Rewrite of the ANTLR4 tool and ecosystem (`antlr4ng`, `antlr4-c3`) in TypeScript/Node.js. | Confirms community concern about upstream `antlr/antlr4` stagnation, but requires a Node.js/npm toolchain rather than a drop-in Maven plugin for a pure-Java JDK 21 build. |

**Takeaway:** Babble cannot solve ANTLR4's structural trade-offs by upgrading to a newer upstream ANTLR release, because **4.13.2 is already the newest official release** and upstream release cadence has slowed markedly since August 2024.

---

## 4. Detailed Pros & Cons of All Six Options

### Option 1 — Status Quo: Keep ANTLR4 4.13.2 (Direct CST)

Keep `Babble.g4`, `antlr4-maven-plugin:4.13.2`, and `antlr4-runtime:4.13.2`, continuing to have `analysis`, `Formatter`, `Interpreter`, and `CodeGenerator` (or `HirBuilder` post-`BIP014-P1`) consume `BabbleParser.*Context` directly.

* **Pros:**
  * **Zero migration cost** (~41 files untouched); preserves full engineering velocity for `BIP013`, `BIP014`, and upcoming syntax ergonomics.
  * **Declarative single-file grammar** (`Babble.g4`, 242 lines) doubles as human-readable syntax documentation and has mature IDE support (`vscode-antlr4`, IntelliJ plugin).
  * **Proven `ALL(*)` foundation (`[PHF14]`):** Won the 2024 ACM SIGPLAN Most Influential OOPSLA Paper Award; handles future grammar ambiguities gracefully via two-stage `SLL` → `LL` (`[ANTLR4-SLL]`).
  * **Zero AOT runtime penalty:** `babble-runtime.jar` already excludes ANTLR4 (`BIP006` §3).
* **Cons:**
  * **External runtime & build dependency:** Retains `antlr4-runtime` in `babble.jar` / `target/lib` and `antlr4-maven-plugin` in `pom.xml`, tied to an upstream project with no release since August 2024.
  * **Heavy CST & no static exhaustiveness:** Every pass navigates CST noise (`ctx.expression(0)`, `ctx.NL()`, `ParserRuleContext` casts) and risks silent `BabbleBaseVisitor.visitChildren` fall-through when new rules are added.
  * **Dynamic ATN/DFA cache overhead:** Cold-start ATN deserialization, lazily growing `PredictionContextCache` / DFA tables in long-lived processes (`[ANTLR4-Mem]`), and potential lock contention under concurrent parsing (`[ANTLR4-Conc]`).
  * **Generic fail-fast diagnostics:** Under `BailErrorStrategy`, syntax errors are reduced to `"Syntax error : unexpected ..."`.

---

### Option 2 — Keep ANTLR4 Behind a Babble-Owned Java 21 Sealed-Record AST

Keep `Babble.g4` and ANTLR4 purely as an internal parsing front end, and add a thin CST → AST lowering pass (`AstBuilder`) producing an immutable Java 21 sealed-record AST (`org.babblelang.ast`, using `[JEP395]`, `[JEP409]`, `[JEP440]`, `[JEP441]`). All downstream consumers (`analysis`, `Interpreter`, `HirBuilder`/`CodeGenerator`) consume `org.babblelang.ast` and never import `org.antlr.*`.

* **Pros:**
  * **Decouples AST design from parser choice:** Once downstream passes depend only on `org.babblelang.ast`, swapping ANTLR4 out later (for Option 3, 5, or 6) becomes a localized ~1-package change instead of a ~41-file refactor.
  * **Immediate static exhaustiveness & cleaner `Interpreter` / `analysis`:** Gains pre-parsed literals, flat typed records, exhaustive `switch` dispatch (`[JEP441]`), and optional dense `int nodeId` arrays without writing a lexer or parser yet.
  * **Retains declarative `Babble.g4`** and ANTLR4 tooling during active grammar evolution (`BIP013`, destructuring, indexing).
* **Cons:**
  * **Pays the ~41-file migration cost *without* removing ANTLR4:** Still ships `antlr4-runtime`, still runs `antlr4-maven-plugin`, still pays ATN/DFA cold-start cost, and still allocates the ANTLR4 CST before immediately discarding it after lowering to AST (double tree allocation at parse time).
  * **Two-step maintenance on grammar edits:** Every syntax change touches both `Babble.g4` and `AstBuilder` (plus `org.babblelang.ast`).
  * **`Formatter` still coupled to ANTLR4 tokens** (unless `Formatter` also migrates to a custom lexer later).

---

### Option 3 — Alternative LL/LALR JVM Parser Generator (JavaCC / CongoCC / JFlex + CUP)

Replace ANTLR4 with a zero- or minimal-runtime JVM parser generator:
* **JavaCC** (`[JavaCC]`) — classic LL(k) generator with `JJTree` (`[JJTree]`) and zero runtime dependency.
* **CongoCC** (`[CongoCC]`, successor to JavaCC 21) — modern fork generating dependency-free Java parsers with context-sensitive lexing and tree building.
* **JFlex + CUP / BYacc/J** (`[JFlex]`) — DFA lexer + LALR(1) parser generator.

* **Pros:**
  * **Zero (JavaCC/CongoCC) or tiny (CUP) runtime dependency** — eliminates `antlr4-runtime` from `babble.jar`.
  * **Static code generation:** No runtime ATN deserialization or growing DFA/prediction caches; fast cold start.
  * **Declarative grammar file** remains separate from parser boilerplate.
* **Cons:**
  * **Same ~41-file migration cost as hand-written code**, while still keeping a third-party generator in the build pipeline.
  * **Weaker tooling & ergonomics than ANTLR4:** Classic JavaCC embeds Java actions inside `.jj` files and has a dated `JJTree` AST model; CongoCC has a small community and non-standard Maven workflow; JFlex+CUP uses LALR(1), which introduces shift/reduce conflict debugging and weaker top-down error recovery / REPL incomplete-input handling.
  * **No compile-time sealed-record AST out of the box:** Unless custom Java 21 record-building actions are hand-written in the grammar, downstream code still faces generated CST/node wrappers.

---

### Option 4 — Tree-sitter (Incremental GLR C Runtime + Java FFM Bindings)

Write a Tree-sitter grammar (`grammar.js`) and invoke it from Java via [`java-tree-sitter`](https://github.com/tree-sitter/java-tree-sitter) (`[TreeSitter]`, `[JavaTreeSitter]`).

* **Pros:**
  * **Best-in-class incremental parsing & error recovery for editors/IDEs:** Sub-millisecond re-parsing on keystrokes, lossless CST, and instant syntax highlighting/folding across Neovim, Zed, Helix, VS Code, and GitHub.
  * **GLR power:** Handles complex or evolving syntax without grammar factoring constraints.
* **Cons:**
  * **Native C shared library + JDK 23+ FFM requirement (`[JavaTreeSitter]`):** Requires compiling/bundling platform-specific native binaries (`libtree-sitter`, `libtree-sitter-babble`) per OS/arch and currently targets JDK 23+ / `jextract` — directly conflicting with Babble's pure-Java JDK 21 (`<maven.compiler.release>21</maven.compiler.release>`) single-jar portability.
  * **FFI boundary overhead & generic CST:** Crossing JNI/FFM to build Java AST/analysis objects adds marshaling overhead and loses Java-native type safety.
  * **Bottom-up GLR recovery caveats for incomplete code:** As noted by `matklad` (`[Kla23]`), GLR excels at recognizing valid small fragments inside broken code, whereas top-down LL parsers more naturally recognize incomplete enclosing declarations as a developer types left-to-right.
  * *(Note: A standalone `tree-sitter-babble` grammar can still be published for editor syntax highlighting independently of Babble's compiler front end!)*

---

### Option 5 — PEG Library or Generator (Parboiled, Mouse, PetitParser, Autumn, or Custom `pegen`)

Adopt a Parsing Expression Grammar (`[For04]`) / Packrat (`[For02]`) library or generator:
* **Parboiled (`parboiled-java`, `[Parboiled]`):** Runtime ASM bytecode rewriting of Java DSL rules. **Disqualified on Java 21:** broke under Java 16+ strong encapsulation, and its author formally declared it at the end of its natural lifecycle in `[Parboiled-EOL]`.
* **Mouse (`[Mouse]`):** Pure-Java PEG-to-recursive-descent generator with optional bounded memoization and zero runtime jar; no built-in left recursion, small/niche ecosystem.
* **PetitParser (`[PetitParser]`) & Autumn (`[Autumn]`):** Java PEG parser-combinator libraries; dynamic object graphs at runtime.
* **Custom `pegen`-style generator:** Modeled on CPython's `[PEP617]` (Python 3.9, 2020), generating Java code directly from a PEG grammar.

* **Pros:**
  * **Unambiguous by definition (`[For04]`):** Ordered choice (`e1 / e2`) guarantees at most one parse tree per input.
  * **Unlimited lookahead / backtracking** without manual factoring — the exact reason CPython adopted PEG in `[PEP617]` when Python's grammar outgrew LL(1).
  * **Scannerless option:** Can unify lexing and parsing (useful for embedded DSLs or context-sensitive tokens).
* **Cons:**
  * **Architectural mismatch with `BIP012`:** CPython moved *from* LL(1) *to* PEG to lift LL(1) limits; Babble just invested `RM006` (`BIP012`) to move in the *opposite* direction (strict predicate-free LL(1)/LL(2)). Adopting PEG pays PEG's complexity for backtracking power Babble deliberately designed away.
  * **Silent prefix shadowing:** Ordered choice gives no grammar-time conflict warning if a general rule accidentally shadows a more specific alternative.
  * **Left-recursion & memory overhead:** Plain PEGs loop on left recursion; supporting it requires specialized memoization (`[WDM08]`, `[MMI14]`), and full packrat memoization consumes $O(N \times |R|)$ memory.
  * **Weak Java ecosystem & diagnostics:** Parboiled is EOL; combinator libraries (`PetitParser`, `Autumn`) keep a runtime dependency and incur object-graph dispatch overhead; backtracking obscures exact syntax error locations without explicit cut operators.

---

### Option 6 — Hand-Written Lexer + Recursive-Descent & Pratt Parser (Java 21 Sealed-Record AST)

Write a self-contained pure-Java lexer (`BabbleLexer`), recursive-descent parser for declarations/blocks/types, and Pratt / binding-power expression parser (`[Pra73]`, `[Nys11]`, `[Kla20]`) emitting a Java 21 sealed-record AST (`[JEP395]`, `[JEP409]`, `[JEP440]`, `[JEP441]`). This is the architecture used by GCC (`[GCC34]`), Clang (`[Clang]`), OpenJDK `javac` (`[Javac]`), Go (`[GoParser]`), Rust (`[Rustc]`), V8, Roslyn, TypeScript, Kotlin, and Swift.

* **Pros:**
  * **Zero third-party parser dependencies & zero codegen:** Removes `antlr4-runtime` and `antlr4-maven-plugin` completely; plain `javac` compiles the entire front end.
  * **Exact 1:1 fit for `BIP012`'s LL(1)/LL(2) grammar:** Estimated ~900–1,300 lines total across lexer, parser, and sealed-record AST; near-zero cold-start latency, no static DFA caches, minimal heap allocation.
  * **Compile-time exhaustiveness (`[JEP409]`, `[JEP441]`):** `javac` statically enforces that `BlockScopes`, `Resolver`, `TypeChecker`, `EffectChecker`, `LoweringAnalyzer`, `Interpreter`, and `CodeGenerator`/`HirBuilder` handle every AST variant.
  * **Full control over diagnostics, error recovery & REPL classification:** Context-aware error messages, optional multi-error recovery (`[Kla23]`), and single-pass `BabbleSession.classify`.
  * **Optimal for GraalVM Native Image (`BIP006` §9):** Plain static Java with zero ATN/reflection machinery.
* **Cons:**
  * **Full migration cost (~41 files pre-`BIP014`, fewer post-`BIP014-P1`).**
  * **Manual parser maintenance:** Grammar changes are code edits rather than editing a 242-line `.g4` file; precedence, `NL` rules, and LL(2) lookahead must be disciplined by unit tests (and optionally a kept-in-sync EBNF doc).
  * **Parser code enters the coverage/testing surface:** Unlike generated `BabbleParser.java` (currently excluded from `-Pbatest` JaCoCo), hand-written lexer/parser code must be owned, fuzzed, and tested for edge cases (malformed escapes, deep nesting / stack overflow, EOF handling).

---

## 5. Master Comparison Matrix

| Criterion | Opt 1: ANTLR4 4.13.2 (CST) | Opt 2: ANTLR4 + Sealed AST | Opt 3: JavaCC / CongoCC / CUP | Opt 4: Tree-sitter (FFM) | Opt 5: PEG (Mouse / Comb. / `pegen`) | Opt 6: Hand-Written RD + Pratt |
|---|---|---|---|---|---|---|
| **Runtime dependency** | `antlr4-runtime` | `antlr4-runtime` | None (JavaCC/CongoCC) / tiny (CUP) | Native C libs + FFM (JDK 23+) | None (Mouse/`pegen`) or lib jar | **Zero** (`java.base` only) |
| **Build-time codegen** | `antlr4-maven-plugin` | `antlr4-maven-plugin` | Plugin / custom build step | Native C + JS build | Plugin / custom tool | **None** (`javac` only) |
| **Fit for `BIP012` LL(1)/LL(2)** | Overkill (`ALL(*)` unused) | Overkill (`ALL(*)` unused) | Good (LL(k)) / Poor (LALR) | Overkill (GLR) | Mismatch (backtracking unused) | **Exact 1:1 fit** |
| **Downstream tree model** | Heavy CST (`ParserRuleContext`) | Compact sealed-record AST | CST / custom AST actions | Generic FFM CST | Combinator result / custom AST | **Compact sealed-record AST** |
| **Cold-start & memory** | ATN load + growing DFA cache | ATN load + double tree alloc | Fast, low memory | Native init + FFM marshaling | Packrat $O(N \cdot |R|)$ / combinators | **Fastest, lowest memory** |
| **Static exhaustiveness (`javac`)** | No (visitor fall-through) | **Yes** (sealed records) | Only if custom AST written | No | Only if custom AST written | **Yes** (sealed records) |
| **Syntax diagnostics** | Generic (`BailErrorStrategy`) | Generic (`BailErrorStrategy`) | Moderate | Good for IDE, weak for compiler | Weak (backtracking obscures site) | **Full control** |
| **Migration blast radius** | **Zero** | High (~41 files / fewer post-`BIP014-P1`) | High | Very high (+ native packaging) | High | High (~41 files / fewer post-`BIP014-P1`) |

---

## 6. Asymmetric Impact: Interpreter vs. Compiler (Pre-`BIP014` vs. Post-`BIP014-P1`)

Because Babble runs both a parse-tree [`Interpreter`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) (reference oracle) and a bytecode [`CodeGenerator`](../../src/main/java/org/babblelang/compiler/CodeGenerator.java) (default execution mode), **and** because `BIP014-P1` already plans to introduce `org.babblelang.ir.hir` (`HIR`), the impact of removing ANTLR4 differs sharply by backend and by sequencing.

### 6.1 Impact on the Babble Interpreter

Today, [`Interpreter.java`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) (827 lines) extends `BabbleBaseVisitor<Object>` and evaluates programs by walking the ANTLR4 CST at runtime:

* Every expression evaluation incurs `visit(ctx)` → `ctx.accept(this)` → `visitXxx(ctx)` virtual double-dispatch.
* Hot loops perform repeated `ParseTreeProperty` / `IdentityHashMap` lookups (`opSites`, `callSites`, `callNames`, `scopes.*`) and navigate CST wrappers (`ctx.expression(0)`, `ctx.op.getType()`).
* Every `BabbleCompiledScript` (interpreted mode) and `BabbleSession` pins the entire ANTLR4 `FileContext` (tokens, `children` lists, `parent` pointers) in heap memory.

**Effect of the options on `Interpreter`:**

* **Options 1, 3 (CST), 4, 5 (CST):** Leave the interpreter walking a generated CST (or add FFI/combinator overhead).
* **Options 2 & 6 (Sealed-Record AST):** Deliver a **direct runtime speed and memory win** for the interpreter:
  * Pre-parsed literals and operator enums eliminate runtime token inspection.
  * `JEP 441` pattern-matching `switch (expr)` replaces visitor double-dispatch and enforces exhaustiveness.
  * Optional dense `int nodeId` fields on AST nodes allow O(1) array-indexed side tables (`opSites[expr.id()]`, `callSites[expr.id()]`) in place of `IdentityHashMap` hashing.
  * **Option 6 additionally enables a zero-dependency embedded interpreter** (no `antlr4-runtime` and no ASM required when running in `Mode.INTERPRET`).
* **Interaction with `BIP014` (`HIR`) and the Reference Oracle Invariant:**
  * `BIP014` §3.3 specifies that `Interpreter` remains deliberately simple and unoptimized as Babble's reference semantics for `BIP009` differential testing.
  * Post-`BIP014-P1`, one might ask: *could `Interpreter` simply interpret unoptimized `-O0` `HIR` instead of introducing a separate `org.babblelang.ast`?*
  * **Trade-off:** Interpreting `-O0` `HIR` avoids maintaining a second tree representation, **but weakens differential testing** — any lowering bug in `HirBuilder` (e.g., wrong `PrimitiveKind`, bad desugaring, or missed narrowing) would then affect *both* `Interpreter` and `CodeGenerator`, hiding divergences from `DifferentialTestCase` and `.batest`. Keeping `Interpreter` on `org.babblelang.ast` (independent of `HirBuilder`) preserves full two-path oracle independence.

### 6.2 Impact on the Babble Compiler

* **At AOT runtime (`babble-runtime.jar`):** **Zero impact across all six options.** `BIP006` (`RuntimeBoundaryTestCase`) already guarantees compiled Babble executables depend only on `java.base`. Peak throughput of compiled code (`raytracer-argon`, `raytracer-fable`, `Benchmarks`) is completely unaffected by the parser choice.
* **At compile time (`babblec` and JIT `Mode.COMPILE`):**
  * **Cold-start & memory:** Options 3 and 6 eliminate ANTLR4's ATN loading and static `PredictionContextCache`/DFA memory growth across long-lived `BabbleScriptEngine` instances.
  * **Packaging:** Option 6 (and 3) leaves OW2 ASM as the **sole** non-JDK dependency of `babble.jar`, simplifying distribution and future GraalVM Native Image builds of `babblec` (`BIP006` §9).
  * **Codegen & analysis ergonomics:** Strongly dependent on whether `BIP015` runs **before** or **after** `BIP014-P1` (see §6.3).

### 6.3 Side-by-Side Timeline Comparison: Pre-`BIP014` vs. Post-`BIP014-P1`

| Dimension | Timeline A: `BIP015` **Before** `BIP014-P1` | Timeline B: `BIP015` **After** `BIP014-P1` |
|---|---|---|
| **Compiler files coupled to ANTLR4** | **`CodeGenerator.java` (5,996 lines)** + `BabbleCompiler` | **Only `HirBuilder`** (the entire mid-end `org.babblelang.opt.*`, `HirEmitter`, `MirEmitter`, `ClassGen`, `InterfaceGen`, `ConstantPool` are already 100% detached from ANTLR4!) |
| **Total migration blast radius** | ~41 files (including rewriting all 5,996 lines of `CodeGenerator.java` from `BabbleParser.*Context` to AST) | ~25–30 files (`Formatter`, `BabbleSession`, `analysis`, `Interpreter`, `HirBuilder`, tests) — **avoids touching `CodeGenerator` / `HirEmitter` twice** |
| **Impact on `BIP014-P1` (`HirBuilder`)** | `HirBuilder` is written directly against the clean sealed-record AST (no CST noise, exhaustive `switch`) | `HirBuilder` is written first against `BabbleParser.*Context`, then migrated once to the new AST |
| **Value of Option 2 (ANTLR4 + AST)** | Higher before `BIP014-P1` (protects `CodeGenerator`) | Lower after `BIP014-P1` (compiler backend is *already* isolated behind `HIR`; only `analysis` and `Interpreter` still see CST) |
| **Impact on `BIP013` / syntax velocity** | Delays `BIP014` and language features until parser/AST migration finishes | Lets `BIP013` and `BIP014-P0`–`P2` land first on the working `SLL` ANTLR4 grammar, then shrinks `BIP015`'s compiler touch-points to `HirBuilder` |

---

## 7. Additional Evaluation Dimensions

### 7.1 `Formatter` Trivia Preservation & Round-Trip Fidelity (`Formatter.java`)

[`Formatter.java`](../../src/main/java/org/babblelang/format/Formatter.java) (472 lines) relies on three parser/lexer properties:
1. A complete, ordered token list containing both code tokens and `HIDDEN`-channel comments (`COMMENT`, `MULTILINECOMMENT`).
2. Mapping parse-tree structures onto token indices (distinguishing call `(` from block `(`, unary `-` from binary `-`, separating `NL` vs. continuation `NL` vs. list `NL` vs. joined `NL`, and kept vs. dropped `;`).
3. Re-parsing the formatted output and asserting parse-tree equivalence.

* **Option 1 (ANTLR4 CST):** Works today via `CommonTokenStream` + `HIDDEN` channel; requires zero changes.
* **Option 2 (ANTLR4 + AST):** `Formatter` either stays on ANTLR4's CST/token stream or reads `SourceSpan(startToken, endToken)` on AST nodes paired with ANTLR4's token list.
* **Option 3 (JavaCC/CongoCC/CUP):** Requires configuring special-token (`SPECIAL_TOKEN`) chains (JavaCC/CongoCC) or custom trivia buffering in JFlex; round-trip CST comparison must be re-implemented.
* **Option 4 (Tree-sitter):** Lossless CST preserves all comments/whitespace natively, which is a strong fit for formatting, though FFI traversal adds boilerplate.
* **Option 5 (PEG):** Scannerless PEGs interleave whitespace/comments directly into grammar rules, making token-index-based formatting like `Formatter.java` notably more awkward unless a separate tokenizer is retained.
* **Option 6 (Hand-Written RD + Pratt):** Cleanest custom fit: `BabbleLexer` emits a flat token list with comments tagged (or on a side channel), and every AST node carries a lightweight `SourceSpan(int line, int col, int startToken, int endToken)` — preserving `Formatter`'s exact single-stack indentation and comment-alignment algorithm with round-trip AST structural equality (`record.equals`!).

### 7.2 Future IDE / LSP Support & Error-Resilient Parsing (`BIP004` §8)

`BIP004` §8 notes future interactive/editor ergonomics (completion, hover, diagnostics), which naturally extend toward a Language Server Protocol (LSP) server where input is **constantly incomplete or syntactically broken**.

* **Option 1 & 2 (ANTLR4):** ANTLR4 supports `DefaultErrorStrategy` recovery and has an external code-completion engine (`antlr4-c3`, though TypeScript-focused under `antlr-ng`). However, Babble currently uses `BailErrorStrategy` for speed; enabling resilient recovery requires switching strategies, handling null/missing children across `analysis`, and managing ANTLR4's DFA memory in a long-lived LSP daemon (`[ANTLR4-Mem]`, `[ANTLR4-Conc]`).
* **Option 3 (JavaCC/CongoCC/CUP):** Weakest LSP story; LALR(1) (CUP) in particular is notoriously difficult to make resilient on incomplete top-down edits.
* **Option 4 (Tree-sitter):** Purpose-built for incremental IDE parsing and error recovery, though GLR bottom-up recovery can struggle to assemble incomplete outer declarations compared to top-down LL (`[Kla23]`), and a separate typed AST/analyzer is still needed for Babble's `TypeChecker`/`EffectChecker`.
* **Option 5 (PEG):** Backtracking ordered choice makes partial-tree error recovery difficult without explicit recovery rules and cut operators.
* **Option 6 (Hand-Written RD + Pratt):** Industry SOTA for IDE-grade language servers (`rust-analyzer`, `javac`, `gopls`, Roslyn, TypeScript). Following `matklad`'s resilient LL + Pratt pattern (`[Kla23]`), a hand-written LL(1)/LL(2) parser can emit `ErrorExpr` / `ErrorType` sentinel nodes (similar to Go's `ast.Bad*`, `[GoParser]`), recover at synchronization tokens (`NL`, `;`, `)`), and feed partial ASTs directly into `TypeChecker` for completion and hover — and also makes `BabbleSession.classify` a clean single-pass check.

### 7.3 Future Babble Macros & Syntactic Extensibility (`BIP014-D4`)

`BIP014-D4` (§7.3) outlines Babble's direction for **Babble macros written in Babble**, compiled via `BabbleCompiler` (`-O1`) under `BIP007` `pure deterministic` sandboxing and expanding cleanly into `HIR` (or AST).

* **Option 1 (ANTLR4 CST):** Poor ergonomic fit for macros. Exposing ANTLR4's mutable `ParserRuleContext` hierarchy to Babble macro code leaks third-party internals, makes constructing synthetic syntax nodes painful, and breaks `IdentityHashMap<ParserRuleContext, ...>` side tables (`BIP014` §5).
* **Option 2 (ANTLR4 + Sealed AST) & Option 6 (Hand-Written RD + Pratt + Sealed AST):** **Strongest fit for AST-based / quasiquoted macros.** Immutable Java 21 sealed records (`org.babblelang.ast`) provide a clean, stable, Babble-owned syntax-tree API that macro functions can inspect and construct without touching parser internals. Option 6 additionally makes **token-tree macros** (Rust `macro_rules!` / proc-macro style, operating on Babble's balanced-`()` token stream!) straightforward because Babble owns the lexer and `Token` type directly.
* **Options 3, 4, 5:** Neutral to awkward; generated CSTs or FFM nodes still require a Babble-owned AST/token-tree abstraction layer before exposing a stable macro API.

---

## 8. Decision Criteria & Open Questions (`BIP015-Q1`–`Q8`)

Rather than prescribing a choice now, this proposal frames the decision around eight open questions. Settling them into `BIP015-D1`–`D8` will determine whether Babble stays on ANTLR4 or transitions to another option.

* **`BIP015-Q1` (Primary Option Selection):** Which of Options 1–6 best fits Babble's long-term priorities (dependency minimalism, interpreter speed, compiler/front-end exhaustiveness, LSP/macro readiness, vs. near-term feature velocity)?
* **`BIP015-Q2` (Sequencing with `BIP014`):** If an AST or parser migration (Options 2–6) is chosen, should it execute **before `BIP014-P1`** (so `HirBuilder` is written once against the new AST, at the cost of migrating `CodeGenerator.java` or deferring `BIP014`) or **after `BIP014-P1`** (shrinking the compiler touch-point from 5,996 lines of `CodeGenerator` to `HirBuilder` alone)?
* **`BIP015-Q3` (Sequencing with `BIP013` & upcoming syntax):** Should any parser/AST transition happen *before* adding `BIP013` (`for`/`in`) and destructuring/indexing syntax, or *after* those grammar additions settle?
* **`BIP015-Q4` (Tree Model for `Formatter` & Future LSP):** If moving to a Babble-owned tree (Options 2 or 6), is a typed sealed-record AST carrying token-index `SourceSpan`s sufficient for `Formatter` and future LSP/macros, or should Babble adopt a lossless green/red CST architecture (`[Kla23]`)?
* **`BIP015-Q5` (`Interpreter` Representation Post-`BIP014-P1`):** If both `org.babblelang.ast` and `BIP014`'s `HIR` exist, should `Interpreter` walk `org.babblelang.ast` (preserving full differential-testing independence from `HirBuilder`) or interpret `-O0` `HIR`?
* **`BIP015-Q6` (Side-Table Indexing):** Should a Babble-owned AST assign dense sequential `int id`s so `ScopeAnalysis` and `Interpreter` can replace `IdentityHashMap` / `ParseTreeProperty` with O(1) arrays, or retain `IdentityHashMap` initially to minimize diff size?
* **`BIP015-Q7` (Syntax Error Compatibility vs. Resilience):** Should any new parser preserve exact fail-fast `ParseFailure` wording for 100% `.batest` compatibility first, or immediately introduce richer contextual diagnostics and multi-error recovery (updating syntax-error `.batest` goldens)?
* **`BIP015-Q8` (Coverage Gate & Reference Grammar Doc):** If ANTLR4 is removed (Options 3, 5, or 6), should `org.babblelang.parser` / `org.babblelang.ast` be added to `BIP009`'s `-Pbatest` JaCoCo `>= 75%` branch gate (removing the `org.babblelang.parser.*` exclusion in [`pom.xml`](../../pom.xml)), and should a canonical EBNF grammar reference be maintained in `docs/`?

---

## 9. Bibliography

- **`[PHF14]`** Terence Parr, Sam Harwell, and Kathleen Fisher. *"[Adaptive LL(\*) Parsing: The Power of Dynamic Analysis](https://2014.splashcon.org/details/splash2014-artifacts/15/Adaptive-LL-Parsing-The-Power-of-Dynamic-Analysis)."* In **OOPSLA '14**, October 2014 (recipient of the [2024 ACM SIGPLAN Most Influential OOPSLA Paper Award](https://www.sigplan.org/Awards/OOPSLA/)).
  - **Summary:** Introduces the `ALL(*)` parsing algorithm underlying ANTLR4, moving grammar analysis to parse-time lookahead DFAs to handle arbitrary non-left-recursive CFGs and direct left recursion with linear performance in practice.
  - **Contribution to `BIP015`:** Explains both why ANTLR4 was valuable pre-`BIP012` and why its dynamic ATN/DFA machinery is largely unneeded once `BIP012` reduced `Babble.g4` to pure `SLL` LL(1)/LL(2) (§2.3, §4 Option 1).

- **`[ANTLR4]`** ANTLR4 Official Project. *[Download ANTLR (v4.13.2)](https://www.antlr.org/download.html)* and *[GitHub Releases](https://github.com/antlr/antlr4/releases)*.
  - **Summary:** Documents ANTLR 4.13.2 (released August 3, 2024) as the latest official release, its 10 target runtimes, and its versioning policy requiring parser regeneration across minor updates.
  - **Contribution to `BIP015`:** Establishes that Babble is already on the latest upstream ANTLR4 version with no newer 4.x/5.x release available (§3).

- **`[GHSA-ANTLR]`** GitHub Advisory Database. *[GHSA-w346-wxvq-p3xr / CVE-2026-13503: Path traversal in ANTLR4 `TokenVocabParser`](https://github.com/advisories/ghsa-w346-wxvq-p3xr)*, June 28, 2026.
  - **Summary:** Unreviewed advisory reporting a path traversal in `tool/src/org/antlr/v4/parse/TokenVocabParser.java` affecting ANTLR4 up to 4.13.2, noting no upstream response or patch.
  - **Contribution to `BIP015`:** Provides a concrete, accurately scoped maintenance-velocity signal for upstream `antlr/antlr4` while clarifying that `antlr4-runtime` is unaffected (§3).

- **`[ANTLR4-SLL]`** ANTLR4 Documentation & Issue Tracker. *[Issue #192: Two-stage `SLL` → `LL` parsing](https://github.com/antlr/antlr4/issues/192)* and *[ANTLR4 General FAQ](https://github.com/antlr/antlr4/blob/master/doc/faq/general.md)*.
  - **Summary:** Describes ANTLR4's recommended two-stage `PredictionMode.SLL` → `LL` parsing strategy to avoid full `LL` prediction cost on deterministic grammars.
  - **Contribution to `BIP015`:** Contextualizes Babble's current `BabbleScriptEngine.parse` fast path and `ParserGrammarTestCase` pure-`SLL` invariant (§2.2, §4 Option 1).

- **`[ANTLR4-Mem]`** ANTLR4 Issue Tracker. *[Issue #499: Memory Leak in `PredictionContextCache`](https://github.com/antlr/antlr4/issues/499)* and *[Issue #2182: Cache keeps growing across multiple files](https://github.com/antlr/antlr4/issues/2182)*.
  - **Summary:** Documents how ANTLR4's static lookahead DFA and `PredictionContextCache` accumulate states across parses in long-lived processes unless cleared via `clearDFA()`.
  - **Contribution to `BIP015`:** Grounds the long-lived engine/LSP memory trade-off for Options 1 and 2 (§4, §6.2, §7.2).

- **`[ANTLR4-Conc]`** ANTLR4 Issue Tracker. *[Issue #4344: Concurrent Parsing Bottlenecks](https://github.com/antlr/antlr4/issues/4344)*.
  - **Summary:** Reports synchronization contention in shared `PredictionContextCache` / DFA structures during multi-threaded parsing in Java.
  - **Contribution to `BIP015`:** Highlights concurrency considerations for future multi-threaded compilation or LSP servers under ANTLR4 (§3, §4, §7.2).

- **`[TVL]`** Sam Harwell / Tunnel Vision Laboratories. *[`tunnelvisionlabs/antlr4`: Optimized Fork of ANTLR 4](https://github.com/tunnelvisionlabs/antlr4)*.
  - **Summary:** An optimized Java fork of ANTLR4 featuring fully-reduced `PredictionContextCache` graphs and multi-threaded performance improvements.
  - **Contribution to `BIP015`:** Evaluated in the ANTLR4 version/fork audit (§3) as an alternative to upstream 4.13.2.

- **`[ANTLR-NG]`** Mike Lischke. *[`antlr-ng/antlr-ng`: Next Generation ANTLR](https://github.com/mike-lischke/antlr-ng)* and *[`antlr4-c3`: Code Completion Core](https://github.com/mike-lischke/antlr4-c3)*.
  - **Summary:** A TypeScript/Node.js port and continuation of the ANTLR4 tool and ecosystem (`antlr4ng`, `antlr4-c3`), launched in 2025.
  - **Contribution to `BIP015`:** Evaluated in §3 and §7.2 as both an ecosystem maintenance signal and a reference point for ANTLR-based code completion.

- **`[JavaCC]`** / **`[JJTree]`** JavaCC Project. *[JavaCC Home](https://javacc.github.io/javacc/)*, *[GitHub](https://github.com/javacc/javacc)*, and *[JJTree Documentation](https://javacc.github.io/javacc/documentation/jjtree.html)*.
  - **Summary:** Classic LL(k) parser generator for Java producing standalone, zero-runtime-dependency parsers and optional `JJTree` parse trees.
  - **Contribution to `BIP015`:** Baseline LL(k) alternative generator evaluated in Option 3 (§4, §5).

- **`[CongoCC]`** Jonathan Revusky / Congo Community. *[`congo-cc/congo-parser-generator`](https://github.com/congo-cc/congo-parser-generator)* and *[legacy `javacc21/javacc21`](https://github.com/javacc21/javacc21)*.
  - **Summary:** Modernized fork/successor to JavaCC generating dependency-free Java parsers with context-sensitive lexing and built-in tree generation.
  - **Contribution to `BIP015`:** Modernized JavaCC alternative evaluated in Option 3 (§4, §5).

- **`[JFlex]`** JFlex Project. *[JFlex: The Fast Scanner Generator for Java](https://www.jflex.de/)*, *[GitHub](https://github.com/jflex-de/jflex)*, and *[Parser Integration (`CUP`, `BYacc/J`)](https://github.com/jflex-de/jflex/blob/master/docs/md/porting-and-parsers.md)*.
  - **Summary:** DFA-based Java lexer generator paired with LALR(1) parser generators (CUP / BYacc/J).
  - **Contribution to `BIP015`:** Represents the classic LALR(1) JVM parser-generator option evaluated in Option 3 (§4, §5, §7.2).

- **`[TreeSitter]`** / **`[JavaTreeSitter]`** Tree-sitter Project. *[Tree-sitter](https://tree-sitter.github.io/tree-sitter/)* and *[`tree-sitter/java-tree-sitter`](https://github.com/tree-sitter/java-tree-sitter)*.
  - **Summary:** Incremental GLR parser generator written in C/Rust for editors and tooling, with official Java bindings built on JDK 23+ FFM/`jextract` and native shared libraries.
  - **Contribution to `BIP015`:** Evaluated as Option 4 (§4, §5, §7.1, §7.2), clarifying the sharp distinction between editor-tooling suitability and pure-Java JDK 21 compiler-front-end portability.

- **`[For02]`** Bryan Ford. *"[Packrat Parsing: Simple, Powerful, Lazy, Linear Time](https://arxiv.org/abs/cs/0603077)."* In **ICFP '02**, October 2002.
  - **Summary:** Introduces packrat parsing — linear-time top-down backtracking via full memoization across input positions and grammar rules.
  - **Contribution to `BIP015`:** Establishes the $O(N \times |R|)$ memory trade-off of memoized PEG parsers evaluated in Option 5 (§4, §5).

- **`[For04]`** Bryan Ford. *"[Parsing Expression Grammars: A Recognition-Based Syntactic Foundation](https://bford.info/pub/lang/peg.pdf)."* In **POPL '04**, January 2004 (see also [Ford's Packrat/PEG hub](https://bford.info/packrat/)).
  - **Summary:** Formalizes PEGs with ordered choice (`/`), syntactic predicates (`&`, `!`), and unified scannerless recognition.
  - **Contribution to `BIP015`:** Grounds the ordered-choice semantics, silent prefix-shadowing risk, and scannerless formatting trade-offs of Option 5 (§4, §7.1).

- **`[WDM08]`** Alessandro Warth, James R. Douglass, and Todd Millstein. *"[Packrat Parsers Can Support Left Recursion](https://web.cs.ucla.edu/~todd/research/pepm08.pdf)."* In **PEPM '08**, January 2008.
  - **Summary:** Extends packrat memoization to support direct and indirect left recursion in PEGs.
  - **Contribution to `BIP015`:** Documents the extra algorithmic complexity required for left-recursive expressions in PEG parsers (§4 Option 5).

- **`[MMI14]`** Sérgio Medeiros, Fabio Mascarenhas, and Roberto Ierusalimschy. *"[Left Recursion in Parsing Expression Grammars](https://arxiv.org/html/1207.0443)."* **Science of Computer Programming**, 2014.
  - **Summary:** Provides a conservative formal semantics for left-recursive PEGs.
  - **Contribution to `BIP015`:** Complements `[WDM08]` on the theoretical and practical complexity of left-recursive PEGs (§4 Option 5).

- **`[PEP617]`** Guido van Rossum, Pablo Galindo Salgado, and Lysandros Nikolaou. *[PEP 617 — New PEG parser for CPython](https://peps.python.org/pep-0617/)* (2020) and *[CPython `InternalDocs/parser.md`](https://github.com/python/cpython/blob/main/InternalDocs/parser.md)*.
  - **Summary:** Details CPython 3.9's migration from its original LL(1) parser (`pgen`) to a custom PEG parser generator (`pegen`) to lift LL(1) grammar restrictions.
  - **Contribution to `BIP015`:** Serves as the key industry counter-example clarifying *when* a PEG migration makes sense (outgrowing LL(1)) vs. Babble's opposite `BIP012` trajectory (enforcing LL(1)/LL(2)) (§4 Option 5).

- **`[Parboiled]`** / **`[Parboiled-EOL]`** Mathias Doenitz (`sirthias`). *[`sirthias/parboiled`](https://github.com/sirthias/parboiled)* and *[Issue #175: Parboiled stopped working in Java 16](https://github.com/sirthias/parboiled/issues/175)* (comment of Jan 27, 2022).
  - **Summary:** Java PEG library using runtime ASM class rewriting; broke under Java 16+ strong encapsulation, with the author concluding it is at the end of its natural lifecycle.
  - **Contribution to `BIP015`:** Eliminates Parboiled as a viable Java 21 PEG option (§4 Option 5).

- **`[Mouse]`** Roman R. Redziejowski. *[Mouse PEG Parser Generator for Java](https://www.romanredz.se/Mouse/index.htm)* and *"[Mouse: From Parsing Expressions to a Practical Parser](https://www.romanredz.se/papers/CSP2009.Mouse.pdf)"* (CSP 2009).
  - **Summary:** Transcribes PEGs into standalone recursive-descent Java procedures with optional light memoization instead of full packrat tables.
  - **Contribution to `BIP015`:** Evaluated as the zero-runtime Java PEG generator in Option 5 (§4, §5).

- **`[PetitParser]`** / **`[Autumn]`** Java PEG Combinator Libraries: *[`petitparser/java-petitparser`](https://github.com/petitparser/java-petitparser)* and Nicolas Laurent, *[`norswap/autumn`](https://github.com/norswap/autumn)* (*"[Parsing Expression Grammars Made Practical](https://arxiv.org/html/1509.02439v2)"*, 2015).
  - **Summary:** Object-oriented Java parser-combinator libraries implementing PEG-based parsing with dynamic grammar composition and left-recursion support (`Autumn`).
  - **Contribution to `BIP015`:** Evaluated as Java PEG runtime libraries in Option 5 (§4, §5).

- **`[Pra73]`** Vaughan R. Pratt. *"[Top Down Operator Precedence](https://www.semanticscholar.org/paper/Top-down-operator-precedence-Pratt/d7acf96501efafae1bac9b7975d6cb8eeb86a9c6)."* In **POPL '73**, October 1973.
  - **Summary:** Introduces top-down operator precedence (Pratt parsing), handling prefix, infix, postfix, and associativity in a compact table-driven recursive loop.
  - **Contribution to `BIP015`:** Theoretical foundation for expression parsing in Option 6 (§4 Option 6).

- **`[Nys11]`** Bob Nystrom. *"[Pratt Parsers: Expression Parsing Made Easy](https://journal.stuffwithstuff.com/2011/03/19/pratt-parsers-expression-parsing-made-easy/)"* (2011) and *Crafting Interpreters*: *[Parsing Expressions](https://craftinginterpreters.com/parsing-expressions.html)* / *[Compiling Expressions](https://craftinginterpreters.com/compiling-expressions.html)*.
  - **Summary:** Accessible Java-grounded exposition of combining recursive descent with Pratt parsing for production languages and interpreters.
  - **Contribution to `BIP015`:** Practical Java implementation reference for Option 6 (§4 Option 6).

- **`[Kla20]`** / **`[Kla23]`** Aleksey Kladov (`matklad`). *"[Simple but Powerful Pratt Parsing](https://matklad.github.io/2020/04/13/simple-but-powerful-pratt-parsing.html)"* (2020) and *"[Resilient LL Parsing Tutorial](https://matklad.github.io/2023/05/21/resilient-ll-parsing-tutorial.html)"* (2023).
  - **Summary:** Formulates Pratt parsing via left/right binding-power pairs and demonstrates how hand-written top-down LL + Pratt parsers achieve IDE-grade error resilience and partial syntax trees.
  - **Contribution to `BIP015`:** Direct architectural reference for Option 6's expression loop, error recovery, and future LSP suitability (§4 Option 4 & Option 6, §7.2, `BIP015-Q4`).

- **`[GCC34]`** / **`[Clang]`** / **`[Javac]`** / **`[GoParser]`** / **`[Rustc]`** Production Hand-Written Recursive-Descent Parsers:
  *[GCC 3.4 Release Notes](https://gcc.gnu.org/gcc-3.4/changes.html)* · *[Clang Features and Goals](https://clang.llvm.org/features.html)* · *[OpenJDK `JavacParser.java`](https://github.com/openjdk/jdk/blob/master/src/jdk.compiler/share/classes/com/sun/tools/javac/parser/JavacParser.java)* · *[Go `go/parser`](https://pkg.go.dev/go/parser)* · *[Rust `rustc-dev-guide`](https://github.com/rust-lang/rustc-dev-guide/blob/main/src/overview.md?plain=1)*.
  - **Summary:** Documents why major production compilers either started with or migrated from parser generators (GCC 3.4 from Yacc/Bison) to hand-written recursive descent: diagnostics, error recovery, performance, and maintainability.
  - **Contribution to `BIP015`:** Establishes real-world compiler precedent for Option 6 and error-resilient `Bad*` AST nodes (§4 Option 6, §7.2).

- **`[JEP395]`** / **`[JEP409]`** / **`[JEP440]`** / **`[JEP441]`** OpenJDK Java 21 Algebraic Data Type JEPs:
  *[JEP 395: Records](https://openjdk.org/jeps/395)* · *[JEP 409: Sealed Classes](https://openjdk.org/jeps/409)* · *[JEP 440: Record Patterns](https://openjdk.org/jeps/440)* · *[JEP 441: Pattern Matching for `switch`](https://openjdk.org/jeps/441)*.
  - **Summary:** Finalized in Java 16–21, providing compact immutable records, closed sealed hierarchies, deconstruction patterns, and exhaustiveness-checked `switch` expressions.
  - **Contribution to `BIP015`:** Key language enabler for a Babble-owned sealed-record AST in Options 2 and 6, directly paralleling `BIP014`'s `HIR` design (§4, §6.1, §7.3).
