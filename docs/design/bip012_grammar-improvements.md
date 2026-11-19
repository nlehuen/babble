# BIP012: Babble grammar improvements

| | |
|---|---|
| **BIP** | 012 |
| **Date** | 2026-11-19 |
| **Status** | Draft (`BIP012-Q1`–`Q9` → `BIP012-D1`–`D9` decided) |
| **Depends on** | Object model ([bip002_object-model.md](bip002_object-model.md)), interactive mode ([bip004_repl.md](bip004_repl.md)), modules ([bip005_modules.md](bip005_modules.md)), function effects & `RM002` ([bip007_effects.md](bip007_effects.md)), mandatory static typing & generics ([bip008_static-typing.md](bip008_static-typing.md)), core types ([bip011_core-types.md](bip011_core-types.md)) |
| **Scope** | Comprehensive audit of ambiguities, context-sensitivities, semantic predicates, custom lookahead, and precedence/lexer defects in `src/main/antlr4/org/babblelang/parser/Babble.g4` (and `BIP011` / `BIP007-P2` syntax), plus a concrete, predicate-free LL(1)/LL(2) grammar redesign under Babble's single-bracket `()` constraint, along with `Formatter`, `BabbleSession`, and migration impact |

---

## 1. Summary

Following the landing of `RM005` (`BIP011`), `src/main/antlr4/org/babblelang/parser/Babble.g4` has grown from a small, clean expression grammar into a **386-line, highly context-sensitive grammar** in which **172 lines (45% of the file)** are custom Java lookahead code inside `@parser::members` (lines 3–174) backing **10 semantic predicates**. In particular, `RM005` had to add **two more** predicates—`isTypeApplicationCall()` (58 lines of token-stream scanning) plus a `ScopeAnalysis.typeApplyCalls` / `CallSolver` / `Interpreter` / `CodeGenerator` disambiguation fallback to tell `Box(T)(x)` apart from curried calls `f(a)(b)`, and `isCoreTypeAfterIs()` to resolve the collision between `# coreScalarRef` (`int`) and `x is int`—while `BIP007-P2` would add 4 more contextual `ID` keywords (`unique`, `move`, `freeze`, `frozen`).

This proposal presents:

1. **A complete, line-by-line audit (§3)** of every ambiguity, context-sensitivity, unbounded lookahead, precedence trap, and lexer defect in post-`RM005` `Babble.g4` and its planned `BIP007-P2` extensions—including active bugs (`(Box)` in type position parsing as a function type instead of a parenthesized type, split precedence between annotated and unannotated function literals, `class (x: int)\n()` failing on newlines, asymmetric `as` and `is` precedence, and single-line `;;( ... ;;)` comments being swallowed by `COMMENT`).
2. **A principled, predicate-free grammar redesign (§4–§5, `BIP012-D1`–`D9`)** under one **hard constraint**: **Babble keeps a single bracket type, `()`** (no `{}`, `[]`, or `<>`). Rather than introducing new brackets, Babble uses **explicit keywords** to disambiguate every overloaded construct:
   * **`of` for generics (`BIP012-D2`–`D4`)** across types (`list of int`, `list of int?`, `map of (string, int)`), declarations (`def Box of T = ...`, `def Pair of (A, B) = ...`), and first-class type application expressions (`def IntBox = Box of int`, `list of int (1, 2, 3)`, `Box of int? (null)`), eliminating `RM005`'s `isTypeApplicationCall()` predicate, `ScopeAnalysis.typeApplyCalls`, and the curried-call fallback completely.
   * **`fn` for function literals and function types (`BIP012-D1`)** (`fn (x: int): int -> (x + 1)`, `fn pure deterministic (x: int): int -> (...)`, `fn (x: int): int`), eliminating unbounded lookahead against blocks `(...)`, resolving the `(Box)` type ambiguity, unifying function literal precedence, and removing `isFunctionLiteralStart()`, `isFunctionTypeStart()`, and `isDefEffectStart()`.
   * **Reserved effect & ownership keywords** (`axiom`, `impure`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, plus `BIP007-P2`'s `unique`, `move`, `freeze`, `frozen`) exempted after `.`/`?.` via `memberName` (extending `BIP011-D11`), paired with **`on`** (`BIP012-D7`) for external effect declarations (`axiom pure deterministic on java.lang.Math.sqrt`).
   * **Mandatory `class` parameters (`BIP012-D5`) and `record` replacing `value class` (`BIP012-D6`)**, **factored `ifExpr`** (removing `{_input.LA(1) == IF}?`), **unified assignment**, **split parameter rules**, **consistent `is`/`as`/`not` precedence (`BIP012-D8`, also removing `RM005`'s `isCoreTypeAfterIs()`)**, and a **fixed `COMMENT`/`MULTILINECOMMENT` lexer rule**.
   * **Sequencing (`BIP012-D9`)**: `RM005` (`BIP011`) has now landed with `C(T)(...)`, so `BIP012-P1`–`P4` executes directly on top of `RM005`, migrating the full grammar (including `RM005` core collections, deleting both `isTypeApplicationCall()` and `isCoreTypeAfterIs()`).

The resulting grammar (§5) has **0 lines of `@parser::members`**, **0 semantic predicates**, and **0 active syntactic ambiguities**, while keeping Babble's signature single-bracket aesthetic, preserving all `RM005` core types and literals, and enabling ANTLR4's fast `PredictionMode.SLL` path.

---

## 2. Hard Constraint & Guiding Principles

1. **Single bracket type: `()` only (hard constraint).**
   Babble intentionally avoids `{}`, `[]`, and `<>`. Keeping a single delimiter pair preserves Babble's uniform, expression-oriented visual identity and keeps `Formatter`'s single-stack indentation model simple. Whenever `(...)` becomes syntactically ambiguous between two constructs, Babble disambiguates with a **keyword**, never with a second bracket type.
2. **Keywords are cheap; contextual predicates are expensive.**
   Because Babble is currently self-contained with no external ecosystem frozen on identifier names, reserving keywords is dramatically cleaner than lexing them as `ID` and bolting Java lookahead onto the parser. Compatibility with Java method/field names (`.set`, `.map`, `.list`, `.pure`, `.move`, `.record`) is preserved cleanly at the grammar level via `memberName` after `.` and `?.` (established in `BIP011-D11` / `RM005`).
3. **Preserve significant newlines (`NL`) without brittle traps.**
   Babble's semicolon-optional line syntax (newlines separate expressions except after continuation tokens and before `.`/`?.`, and never between an expression and `(` or unary `-`) is a core ergonomic feature and stays intact. The redesign removes accidental newline traps (such as `class (x: int)\n(...)` failing) rather than abandoning significant newlines.
4. **Enforce structural boundaries in the grammar, semantic boundaries in analysis.**
   Constructs with genuinely distinct syntax (`fnTypeParameters` vs `fnParameters` vs `classParameters`, `ifExpr`, `is` RHS as a type) should be distinct grammar rules so invalid forms fail immediately with clear syntax errors instead of leaking into `BlockScopes`/`TypeAnnotations`. Conversely, assignment LHS (`lhs=expression`) should stay general in the grammar to avoid unbounded lookahead and produce clearer diagnostics (`Cannot assign to expression`).

---

## 3. Comprehensive Audit of Current Ambiguities & Context-Sensitivities

### 3.1 The 12-Way Overload of `(...)`

Because `()` is the only delimiter pair in Babble, `(` currently carries **11 distinct syntactic roles** in post-`RM005` `Babble.g4`, with **1 more** planned in `BIP005-D3`:

| # | Syntactic Role | Current Syntax | Collides With |
|---|---|---|---|
| 1 | Expression grouping / block | `(a + b)`, `(def x = 1; x)`, `()` | #3 (function literal), #2 (call across `NL` suppressed) |
| 2 | Function call arguments | `f(a, b)`, `f(x: 1)` | #1 (suppressed across `NL`), #11 (`C(T)(...)` vs `f(a)(b)`) |
| 3 | Function literal parameters | `(x: int): int -> (x + 1)` | #1 (`(...)` block / empty `()`) |
| 4 | Function type parameters | `(x: int): int` | #5 (`(Box)` parenthesized type — **active ambiguity**) |
| 5 | Parenthesized type | `(int \| string)?`, `((x: int): int)?` | #4 (function type) |
| 6 | Class constructor header | `class (def x: int) (...)` | #1 (`class (...)` body when header omitted) |
| 7 | Generic type parameters on `def` | `def Box(T) = ...` | #12 (destructuring `def (a, b) = ...`) |
| 8 | Generic type arguments in types | `Box(int)`, `Map(string, int)` | #2 / #11 in expression position |
| 9 | `super` interface qualifier | `super(I).m()` | None (`super` is a keyword, never called) |
| 10 | Constructor-only `axiom` target | `axiom pure java.util.HashMap()` | None (only at end of `effectDeclaration`) |
| 11 | Explicit type application (`RM005`) | `Box(int?)(null)`, `list(int)(1)` | #2 (curried value calls `f(a)(b)` — **unresolvable syntactically**) |
| 12 | *(BIP005-D3)* Destructuring `def` | `def (Circle, unit) = import(...)` | Visually adjacent to #7 (`def Box(T)`) |

Every major parser hack in `Babble.g4` traces back to either **this `(...)` overload** or **contextual `ID` keywords**.

### 3.2 Inventory of Custom Lookahead & Semantic Predicates (`@parser::members`)

Lines 3–174 of post-`RM005` `Babble.g4` contain **172 lines** of hand-written Java token-stream scanning implementing **10 semantic predicates**:

| # | Predicate / Helper | `Babble.g4` Lines | Lookahead Bound | Why It Exists |
|---|---|---|---|---|
| P1 | `{isEffectDeclarationStart()}?` | 91–99, 176–177, 270–271 | **Unbounded** (calls P3) | `axiom`/`impure` are `ID`s; must distinguish `axiom pure M.f` from `axiom pure (x) -> (...)` and from variable `axiom`. |
| P2 | `{isDefEffectStart()}?` | 84–89, 214 | **Unbounded** (calls P3) | After `def x =`, must distinguish `axiom pure import("...")` (`targetEffectPrefix`) from `axiom pure (x) -> (...)` (`# functionLiteral`). |
| P3 | `{isFunctionLiteralStart()}?` | 36–71, 226 | **Unbounded** (scans balanced `(...)`) | Effect words are `ID`s and functions have no leading keyword; scans arbitrarily deep `(...)`, skips `NL`, checks for `:` or `->` after `)`, and contains a fragile `Token.EOF` heuristic (lines 54–57) solely so REPL `BabbleSession.classify` detects incomplete inputs like `pure (mut `. |
| P4 | `{isFunctionTypeStart()}?` | 73–82, 296 | **Unbounded** (scans `ID*`) | Effect words are `ID`s; scans `effectWord+` in `primaryType` to see if `(` follows, distinguishing `pure (x: int): int` from a type named `pure`. |
| P5 | `{isEffectOrAxiomWord(_input.LT(1))}?` | 20–30, 199 | 1 token (contextual) | Lexes 13 effect words as `ID`, then filters by string comparison in the parser. |
| P6 | `{isTargetEffectWord()}?` | 101–103, 203 | 2 tokens (contextual) | Prevents `targetEffectWord+` from consuming the final `ID` that is actually the `effectTarget` name (`axiom pure foo`). |
| P7 | `{isParamModifier()}?` | 32–34, 277 | 2 tokens (contextual) | Distinguishes `(mut x: int)` (modifier + name) from `(mut: int)` (parameter literally named `mut`). |
| P8 | `{_input.LA(1) == IF}?` | 212 | 1 token | Forces `elseIf=expression` in the second `# ifExpression` alternative to start with `IF` while remaining at `ifExpression`'s precedence. |
| P9 | `{isTypeApplicationCall()}?` (`RM005`) | 105–162, 233 | **Unbounded** (scans `(...)` + `(`) | Distinguishes `Box(int?)(null)` from `f(x)(y)` (with a `COLON && depth == 1` named-arg heuristic), and still cannot resolve `f(X)(y)` without `ScopeAnalysis.typeApplyCalls` + `CallSolver`/`Interpreter`/`CodeGenerator` fallbacks. |
| P10 | `{!isCoreTypeAfterIs()}?` (`RM005`) | 164–173, 246 | **Unbounded** (skips `NL*`) | Because `RM005` made `coreScalarKeyword` an expression (`# coreScalarRef`) while keeping `isExpression`'s fallback RHS as `expression`, `x is int` collided between lines 245 and 246. |

### 3.3 Active Syntactic Ambiguities, Precedence Traps & Lexer Defects

Beyond the predicates themselves, `Babble.g4` contains **10 concrete structural and lexical defects**:

#### A1. `primaryType` Ambiguity: `(Box)` Parses as a Function Type, Not a Parenthesized Type
In `Babble.g4` lines 296–301:
```antlr
primaryType: {isFunctionTypeStart()}? effectPrefix parametersDeclaration ( ':' type | )   # functionType
           | (coreTypeKeyword | ID ('.' memberName)*) typeArguments?                      # simpleType
           | parametersDeclaration ( ':' type | )                                         # functionType
           | NULL                                                                         # nullType
           | '(' NL* type NL* ')'                                                         # parenType
           ;
```
Because `parameterDeclaration` makes `: type` optional (`(x)` is valid) and `# functionType` makes `: type` optional, any parenthesized user-defined type `(Box)` matches **both** `# functionType` (a 1-parameter function whose untyped parameter is named `Box` and whose return type is omitted) **and** `# parenType` (parenthesized `Box`). Because `# functionType` appears first, `(Box)` parses as `# functionType`, whereas `(int)` (since `RM005` reserved `int`), `(Box?)`, and `(Box | string)` fail `parametersDeclaration` and fall through to `# parenType`.

#### A2. Function Literal vs Block Ambiguity & Split-Precedence Bug
`# blockExpression` (line 235) and `# functionLiteral` (line 249) both start with `(`. ANTLR4 must perform unbounded ALL(*) lookahead past the entire parenthesized region to see `:` or `->`. Empty `()` is valid as both an empty block (`null`) and an empty parameter list (`() -> (...)`).

Worse, `# functionLiteral` is split across **two different precedence levels** in `expression`:
* Line 226 (`{isFunctionLiteralStart()}? effectPrefix parametersDeclaration ... -> block`) sits **above** `# selector` (line 228) and `# call` (line 234).
* Line 249 (`parametersDeclaration ... -> block`) sits **below** `# booleanOp` (`or`, line 248).

Consequently, `pure (x: int) -> (x)(1)` and `(x: int) -> (x)(1)` parse at **different precedences**: an effect-annotated function literal can be immediately called or selected without outer parentheses, whereas an unannotated function literal cannot!

#### A3. Generics `C(T)` & `RM005` `C(T)(...)` Collide with Calls & Curried Calls
Because generics use `(...)`, `f(X)(y)` is **syntactically indistinguishable** between:
* Explicit type application of generic `f` with type argument `X` and value argument `y`, and
* Curried value call `f(X)` returning a callable invoked with `y`.

`RM005` (`BIP011-P4`) had to implement an unbounded lookahead predicate `isTypeApplicationCall()` (58 lines in `@parser::members`), a `ScopeAnalysis.typeApplyCalls` side-table (`markTypeApplyCall` / `isTypeApplyCall`), special handling in `BlockScopes.visitTypeApplyCall`, and fallback evaluation in `CallSolver`, `Interpreter.visitTypeApplyCall`, and `CodeGenerator.visitTypeApplyCall` to re-interpret a parsed `TypeApplyCallContext` as a curried value call whenever `f` is non-generic. This leaks syntax disambiguation across the entire pipeline and makes the parse tree lie about program structure.

#### A4. `class` Optional Header `(...)` vs Body `(...)` & the Newline Trap
In `Babble.g4` lines 216–220:
```antlr
| modifier=ID? CLASS parametersDeclaration? (IMPLEMENTS typeList)? classBlock=block
      (NL* STATIC NL* staticBlock=block)?           # classExpression
```
Because `parametersDeclaration?` is optional and both header and `classBlock` start with `(`, ANTLR4 must scan past the entire first `(...)` to see whether `implements` or another `(` follows. Worse, `NL*` **cannot** be permitted before `classBlock`—so:
```babble
def C = class (x: int)
(
)
```
is a **syntax error** (`unexpected end of line`, tested in `src/test/batest/syntax/syntax_errors.batest` lines 29–33), even though `interface` and `object` allow `NL*` before their blocks and every multi-line class in `src/test/babble/` already writes `class () (...)` when it has no constructor parameters.

#### A5. Contextual `modifier=ID? CLASS`
Any identifier before `class` (`foo class ()`) parses as a `# classExpression` with `modifier="foo"` and is only rejected later by `BlockScopes` checking `"value".equals(modifier)`.

#### A6. `else if` Predicate Hack & Duplicated Prefix
Lines 206–212 duplicate `IF test=expression THEN NL* thenBlock=block` across two `# ifExpression` alternatives and rely on `{_input.LA(1) == IF}?` solely because `if` is inlined into left-recursive `expression` rather than factored into a helper rule `ifExpr`.

#### A7. `assignExpression` vs `selector` / `ID` Unbounded Lookahead
Lines 228–229 (`expression NL* '.' memberName` and `ID`) and lines 252–253 (`namespace=expression NL* '.' member=memberName op=... value` and `name=ID op=... value`) share arbitrarily long prefixes (`a.b.c.d.e = 1` vs `a.b.c.d.e`). ANTLR4 must look ahead past the entire selector chain to see whether an assignment operator follows.

#### A8. Flat Left-Recursive `expression` & Precedence Traps
Jamming all expression forms into one flat left-recursive rule introduces four subtle precedence traps:
1. **`is` splits across two predicate-guarded alternatives and binds looser than `==`:**
   Lines 245–246 (`left=expression IS NL* coreType=coreTypeKeyword typeArguments?` and `left=expression {!isCoreTypeAfterIs()}? IS NL* right=expression`) sit *below* `==` (line 243). Therefore:
   * `x is Foo == true` parses as `x is (Foo == true)` instead of `(x is Foo) == true`.
   * `x is 1 + 2` parses without a syntax error and only fails later in `TypeChecker`.
   * `RM005` had to introduce `isCoreTypeAfterIs()` (P10) solely to stop `x is int` from matching `# coreScalarRef` in the second alternative.
2. **`as` binds looser than `+`/`-`, creating asymmetric parsing:**
   `left=expression AS NL* right=type` (line 242) is a postfix alternative *below* `+`/`-` (line 241). Under ANTLR4 precedence climbing:
   * `boxed as int + 5` parses as `(boxed as int) + 5` (see `src/test/babble/AnyAndCasts.ba` line 68),
   * `1 + 2 as double` parses as `(1 + 2) as double` (line 69), and
   * `a + b as int + c` parses as `((a + b) as int) + c` rather than `a + (b as int) + c`!
3. **`not` binds tighter than comparisons and `is`:**
   `NOT expression` (line 236) sits above `'-'`, `*`, `+`, `<`, `==`, `is`. Thus `not a == b` parses as `(not a) == b`, forcing parentheses (`not (a == b)`, `not (1 is C)`) across the test suite.
4. **Accidental prefix precedence on `def` vs `return`:**
   `RETURN expression` (line 266) must remain the very last alternative because ANTLR4 parses a prefix alternative's trailing `expression` at that alternative's precedence. `# defExpression` (line 213) avoids this trap *only* because `('=' ... value=expression)?` is wrapped in `(...)?`, making it non-prefix by accident.

#### A9. Over-Permissive Shared `parametersDeclaration`
`parametersDeclaration` (lines 273–277) is shared verbatim by `classExpression`, `functionLiteral`, and `functionType`. Consequently, the grammar accepts `private def mut x: int = 1` inside function types and function literals, delegating rejection to `BlockScopes` and `TypeAnnotations`.

#### A10. Lexer Defects
1. **Single-line `MULTILINECOMMENT` swallowed by `COMMENT`:**
   Lines 382–383 define `MULTILINECOMMENT: ';;(' .*? ';;)'` and `COMMENT: ';;' ~[\r\n]*`. On `;;( note ;;) def x = 1`, `COMMENT` produces a longer match (22 chars vs 12 chars) and **silently comments out `def x = 1`** (noted as a known quirk in `CLAUDE.md`).
2. **Leading-dot floats (`FLOAT_LIT` / `DOUBLE_LIT`):**
   `RM005` upgraded exponents (`[eE] [+-]? [0-9]+`) and `STRING`/`CHAR_LIT` escapes, but lines 305–309 still accept leading-dot `.5` (`[0-9]* '.' [0-9]+`), preventing `.0` positional selectors if ever desired.
3. **17 contextual `ID` keywords across `BIP007-P1` and `BIP007-P2`:**
   Keeping 13 (`BIP007-P1`) + 4 (`BIP007-P2`) effect/ownership keywords as `ID` is the sole reason predicates P1–P7 exist.

### 3.4 Clean Constructs Verified Safe

Not everything in `Babble.g4` is problematic. The audit confirms the following constructs are already clean and should be preserved:
* **Named call arguments** (`callParameter: (memberName ':')? expression`): clean LL(2) because `:` never follows a bare identifier in expression position.
* **`super(I)` qualifier**: clean LL(1) after `SUPER` because `super` is never called directly (Babble has no class inheritance, only interface defaults).
* **Significant-newline call & unary-minus separation**: `f\n(x)` as two expressions and `a\n-b` as two expressions are clean, intentional, and require no predicates.

---

## 4. Proposed Grammar Improvements

Every change below strictly preserves **Babble's single bracket type `()`** and eliminates predicates by introducing clear, readable keywords.

### 4.1 Keyword-Introduced Generics with `of` (`BIP012-D2`–`D4`)

Replace `(...)` type parameter lists, type argument lists, and call-site type applications with the keyword **`of`**:

* **Type position (`BIP012-D2`):**
  * Single type argument (no parens needed): `list of int`, `list of int?`, `Box of string`, `list of list of int`, `Box of (int | string)`.
  * Multiple type arguments (grouped with `()`): `map of (string, int)`, `Pair of (A, B)`, `java.util.Map of (string, list of int)`.
* **Expression position — first-class type application (`BIP012-D4`):**
  * Standalone instantiation: `def idInt = id of int`, `def IntBox = Box of int`, `def IntList = list of int` (zero-cost compile-time specialization in `TypeChecker`, erased at runtime).
  * Immediate call: `list of int (1, 2, 3)`, `list of int ()`, `map of (string, int) (entry("a", 1))`, `Box of int? (null)`, `java.util.LinkedList of int ()`, `id of string ("hello")`.
* **Declaration position (`def`, `BIP012-D3`):**
  * `def id of T = fn (x: T): T -> (x)`
  * `def first of (A, B) = fn (a: A, b: B): A -> (a)`
  * `def Box of T = class (def value: T) (...)`
  * `def Pair of (A, B) = record (a: A, b: B) (...)`
  * `def Container of T = interface (...)`

**Why `of` completely eliminates generic ambiguity:**
1. In expression position, `expr OF typeArguments` is introduced by the keyword `OF`. `f(a)(b)` is **always** a curried value call; `f of A (b)` is **always** a generic type application. `BIP011`'s `isTypeApplicationCall()` predicate and `TypeChecker` fallback disappear entirely.
2. After `Box of int` or `Box of int?`, the type argument cannot be followed by `(...)` (since type arguments no longer use `(...)`), so a following `(42)` on the same line is **unambiguously** `callParameters`—100% LL(1)!
3. Because `of` is uniform across `def`, types, and expressions (`BIP012-D3`), `def (Circle, unit) = import("shapes.ba")` (`BIP005-D3` destructuring) is 100% LL(1) (`DEF LPAREN` vs `DEF ID`) and never visually confused with generic declarations.
4. Because Babble's generics are erased at runtime, standalone `expr of T` (`BIP012-D4`) requires zero runtime representation changes: `TypeChecker` specializes the generic `Type.Function`, `Type.ClassValue`, `Type.JavaClass`, or core collection constructor type, while both backends simply evaluate `expr`.

**Precedence of `of` vs `?` (`BIP012-D2`):**
`?` binds tighter than `of` on bare single type arguments, while parenthesized type-argument lists attach `?` to the outer generic type:
* `list of int?` parses as `list of (int?)` (non-null list of nullable `int?`),
* `list of list of int?` parses as `list of (list of (int?))`,
* `(list of int)?` parses as a nullable list of non-null `int`,
* `map of (string, int)?` parses as `(map of (string, int))?` (nullable map),
* `list of (int | string)?` parses as `(list of (int | string))?` (nullable list of union; a non-null list of nullable unions is `list of ((int | string)?)`),
* `list of int??` is a syntax error (write `(list of int?)?`), enforced cleanly in the factored LL(1) `nullableType` rule in §5.

### 4.2 Keyword-Introduced Functions with `fn` (`BIP012-D1`)

Introduce **`fn`** as the leading keyword for both **function literals** and **function types**, matching `class`, `record`, `interface`, and `object` (which all start with a keyword):

* **Function literals:**
  ```babble
  def square = fn (x: int): int -> (x * x)
  def hypot = fn pure deterministic (a: double, b: double): double -> (
      java.lang.Math.sqrt(a * a + b * b)
  )
  def add = fn mut (delta: int): int -> (
      this.value += delta
      this.value
  )
  def fastFib = fn axiom pure deterministic (n: int): int -> (...)
  def debugLog = fn impure (msg: string) -> (...)
  ```
* **Function types:**
  ```babble
  def apply of (A, B) = fn (x: A, f: fn (a: A): B): B -> (f(x))
  def mapInts = fn (items: list of int, f: fn pure (x: int): int): list of int -> (...)
  def maybeFn: (fn (x: int): int)? = null
  ```

**Why `fn` goes *before* effect modifiers (`fn pure (...)`, not `pure fn (...)`):**
* Putting `FN` first makes both `# functionLiteral` and `# functionType` **100% LL(1)** on a single token (`FN`)!
* In `primaryType`, `# functionType` starts with `FN` and `# parenType` starts with `(`. Thus `(int)` is **always** `# parenType` (resolving **A1**).
* In `expression`, `# functionLiteral` starts with `FN` and `# blockExpression` starts with `(`. Zero lookahead against blocks or empty `()` (resolving **A2**), and **one single alternative** handles both annotated and unannotated functions at a single consistent precedence!
* In `def x = ...`, `def f = fn axiom pure (x: int): int -> (...)` starts with `FN`, while `def M = axiom pure on import("...")` starts with `AXIOM`—eliminating `isDefEffectStart()` and `isFunctionLiteralStart()` (including its `Token.EOF` REPL hack!).
* In `fnTypeParameters`, parameter names can even become optional (`fn (int, int): int` alongside `fn (a: int, b: int): int`) with clean LL(2) disambiguation on `ID ':'`.

### 4.3 Reserved Effect & Ownership Keywords + `memberName` + `on` (`BIP012-D7`)

1. **Reserve all `BIP007-P1` and `BIP007-P2` effect/ownership words as lexer tokens:**
   * `BIP007-P1` (13): `axiom`, `impure`, `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`.
   * `BIP007-P2` (4, reserved now or in `BIP007-P2`): `unique`, `move`, `freeze`, `frozen`.
   * Exempt them in **`memberName`** (alongside `BIP011-D11`'s 17 core type keywords and `record`) so `.set`, `.map`, `.list`, `.pure`, `.move`, `.freeze`, `.record` remain valid member names after `.` and `?.`, in named arguments, and in `effectTarget` member segments!
   * Audit of the entire repository (`src/test/`, `examples/`): **zero** production/example scripts use any of the 13 effect words as variable names; only one dedicated test (`src/test/babble/Effects.ba` line 107) intentionally uses `pure`, `axiom`, and `borrow` as identifiers to test contextual predicates.
2. **Introduce `on` in `effectDeclaration` (`BIP012-D7`):**
   ```babble
   axiom pure deterministic nothrow on java.lang.Math.sqrt
   axiom pure deterministic on java.util.HashMap()
   export axiom hermetic mut on java.util.List.set
   impure on java.net.URL.equals
   def math = axiom pure deterministic on import("math.ba")
   ```
   With `AXIOM`, `IMPURE`, effect modifiers, and `ON` all as real lexer tokens:
   * `effectDeclaration` is **100% LL(1)** (`EXPORT AXIOM` / `EXPORT IMPURE` vs `EXPORT DEF`; `AXIOM` / `IMPURE` at statement start).
   * `targetEffectWord+` needs no `{isTargetEffectWord()}?` lookahead because `ON` unambiguously terminates the effect modifier list—even if the target begins with a core type keyword like `list` or `string`!
   * Inside `def x = axiom pure on expr`, `AXIOM` + modifiers + `ON` is completely disjoint from `FN` (`fn axiom pure (...) -> (...)`).

### 4.4 Mandatory `class` Parameters (`BIP012-D5`) & `record` Replacing `value class` (`BIP012-D6`)

1. **Mandatory `classParameters` (resolving A4, `BIP012-D5`):**
   ```antlr
   | (CLASS | RECORD) classParameters (IMPLEMENTS typeList)? NL* classBlock=block
         (NL* STATIC NL* staticBlock=block)?                                       # classExpression
   ```
   * The first `(...)` is **always** `classParameters`; the next `(...)` is **always** `classBlock`.
   * `NL*` is now allowed before `classBlock`, fixing `class (x: int)\n(...)`!
   * **100% LL(1)**, zero extra body keyword, and matches every existing class in `src/test/babble/` and `examples/` (which already write `class () (...)` for parameterless classes).
2. **`record` replaces `value class` (resolving A5, `BIP012-D6`):**
   * `record (x: double, y: double) (...)` replaces `value class (x: double, y: double) (...)`, eliminating `modifier=ID? CLASS` completely.
   * `value` remains a completely ordinary `ID` everywhere (preserving all 23 existing `def value` declarations and `this.value` fields without change), while `RECORD` is exempted in `memberName` so Java/Babble methods like `.record(...)` keep working.

### 4.5 Factored `ifExpr`, Unified Assignment, Split Parameters & Precedence Fixes (`BIP012-D8`)

1. **Factor `ifExpr` (resolving A6):**
   ```antlr
   ifExpr
       : IF test=expression THEN NL* thenBlock=block
         (NL* ELSE NL* (elseIf=ifExpr | elseBlock=block))?
       ;
   ```
   Placed as a primary expression atom in `expression`, `ifExpr` is **100% LL(1)** (after `ELSE`, `IF` enters `elseIf`, `(` enters `elseBlock`), removes `{_input.LA(1) == IF}?` and the duplicated `IF ... THEN ...` prefix, and preserves `if a then (1) else if b then (2) else (3) + 1` meaning `(if ...) + 1`.
2. **Unify `assignExpression` (resolving A7):**
   ```antlr
   | <assoc=right> lhs=expression op=('=' | '+=' | '-=' | '*=' | '/=') NL* value=expression  # assignExpression
   ```
   Eliminates unbounded lookahead over `a.b.c.d = 1` vs `a.b.c.d`, supports future indexed LHS automatically, and lets `BlockScopes`/`TypeChecker` report `Cannot assign to expression` instead of `Syntax error : unexpected '='`.
3. **Split `parametersDeclaration` into three targeted rules (resolving A9):**
   * `classParameters` / `classParam`: `PRIVATE? DEF? paramModifier* ID (':' type)? ('=' NL* expression)?`
   * `fnParameters` / `fnParam`: `paramModifier* ID (':' type)? ('=' NL* expression)?`
   * `fnTypeParameters` / `fnTypeParam`: `paramModifier* (ID ':')? type` (no `private`, no `def`, no defaults; optional parameter names!).
4. **Fix `is`, `as`, and `not` precedence (resolving A8, `BIP012-D8`):**
   * Change `# isExpression` RHS from `expression` to `type` (`left=expression IS NL* right=type`), aligned with `BIP011` §7.4 (`x is int`, `x is list`, `x is mod.Circle`).
   * Place `as` **above** `*`/`/` and `+`/`-` (just below unary `-`), so `a + b as int + c` parses naturally as `a + (b as int) + c` (matching Kotlin, Swift, Rust, TypeScript, and C#).
   * Place `is` **above** comparisons (`<`, `<=`, `==`, `!=`, `>=`, `>`), so `x is Foo == true` parses as `(x is Foo) == true`.
   * Place `not` **below** comparisons and `is` (just above `and`), so `not a == b` and `not x is Foo` work without mandatory parentheses (matching Python and SQL).
   * Keep `RETURN expression` and `defExpr` at the lowest statement-expression level so precedence never depends on `(...)?` accidents.

### 4.6 Lexer Fixes (Resolving A10)

1. **Fix `COMMENT` vs `MULTILINECOMMENT`:**
   ```antlr
   MULTILINECOMMENT: ';;(' .*? ';;)' -> channel(HIDDEN);
   COMMENT:          ';;' (~[(\r\n] ~[\r\n]*)? -> channel(HIDDEN);
   ```
   Because `COMMENT` cannot match `;;(`, `;;( note ;;) def x = 1` always lexes `;;( note ;;)` as `MULTILINECOMMENT` followed by `def x = 1`.
2. **Numeric literals (aligned with `BIP011` §7.2):**
   Support `[eE] [+-]? [0-9]+` exponents and require a leading digit `[0-9]+ '.' [0-9]+` (eliminating `.5` ambiguity with `.` member selection).

### 4.7 Headroom for Planned Single-Bracket Features

With `()`-only preserved and all predicates removed, planned features slot into the grammar cleanly with **zero predicates**:
* **`BIP011` core types:** `list of int`, `map of (string, int)`, `list of int (1, 2, 3)`, `e!` (postfix), `x is list` — all LL(1).
* **`BIP007-P2` ownership:** `move x` (`MOVE ID`), `frozen list of int` (`FROZEN?` on `simpleType`), `fn (unique mut xs: list of int): unique list of int -> (...)` — all LL(1).
* **`BIP005-D3` destructuring `def`:** `def (Circle, unit) = import("shapes.ba")` (`DEF '(' ID (',' ID)* ')' '=' expression`) — LL(1), disjoint from `DEF ID`.
* **`BIP008` §5.1 indexing (under the `()`-only constraint):** Since `[]` is off-limits, indexing can use either method syntax (`xs.get(i)` / `xs.set(i, v)`) or a keyword operator (e.g., `xs at i` / `xs at i = v`, which works directly with the unified `assignExpression` rule!).

---

## 5. Complete Proposed `Babble.g4` Grammar

The grammar below has **0 lines of `@parser::members`** and **0 semantic predicates** (shown with `BIP011` core keywords and `BIP007-P2` ownership hooks indicated where relevant):

```antlr
grammar Babble;

// Zero @parser::members. Zero semantic predicates. Single bracket type: ().

file
    : sep? item (sep item)* sep? EOF
    ;

sep
    : (';' | NL)+
    ;

item
    : effectDeclaration
    | expression
    ;

// ---- Effect & axiom declarations (LL(1))

effectDeclaration
    : EXPORT? effectClause ON effectTarget (ctorOpen='(' ctorClose=')')?
    ;

effectClause
    : AXIOM effectModifier+
    | IMPURE
    ;

effectPrefix
    : AXIOM effectModifier+
    | IMPURE
    | effectModifier+
    ;

effectModifier
    : PURE | HERMETIC | MUT | BORROW | DETERMINISTIC | NOTHROW
    | IDEMPOTENT | COMMUTATIVE | ASSOCIATIVE | MONOTONE | INJECTIVE
    ;

paramModifier
    : MUT | BORROW // BIP007-P2: | UNIQUE
    ;

effectTarget
    : (ID '(' STRING ')' | memberName) ('.' memberName)*
    ;

// ---- Expressions (stratified, zero predicates)

expression
    : primary                                                                       # primaryExpr
    | expression NL* ('.' | safe='?.') memberName                                   # selector
    | expression '!'                                                                # nonNullAssertion // BIP011
    | expression OF typeArguments                                                   # typeApply
    | expression callParameters                                                     # call
    | '-' operand=expression                                                        # negate
    | left=expression AS NL* right=type                                             # asExpression
    | left=expression op=('*' | '/') NL* right=expression                           # binaryOp
    | left=expression op=('+' | '-') NL* right=expression                           # binaryOp
    | left=expression IS NL* right=type                                             # isExpression
    | left=expression op=('<' | '<=' | '==' | NEQ | '>=' | '>') NL* right=expression # binaryOp
    | NOT operand=expression                                                        # booleanNot
    | left=expression op=AND NL* right=expression                                   # booleanOp
    | left=expression op=OR NL* right=expression                                    # booleanOp
    | <assoc=right> lhs=expression op=('=' | '+=' | '-=' | '*=' | '/=')
                    NL* value=expression                                            # assignExpression
    | defExpr                                                                       # defExpression
    | RETURN operand=expression                                                     # returnExpression
    ;

primary
    : ifExpr                                                                        # ifExpression
    | WHILE test=expression THEN NL* whileBlock=block                               # whileExpression
    | (CLASS | RECORD) classParameters (IMPLEMENTS typeList)? NL* classBlock=block
          (NL* STATIC NL* staticBlock=block)?                                       # classExpression
    | INTERFACE (EXTENDS typeList)? NL* interfaceBlock=block
          (NL* STATIC NL* staticBlock=block)?                                       # interfaceExpression
    | OBJECT (IMPLEMENTS typeList)? NL* createBlock=block                           # objectExpression
    | FN effectPrefix? fnParameters (':' type)? '->' NL* functionBlock=block        # functionLiteral
    | THIS                                                                          # thisExpression
    | SUPER ('(' NL* qualifier=type NL* ')')?                                       # superExpression
    | block                                                                         # blockExpression
    | coreCollectionKeyword                                                         # coreCollection // BIP011 + BIP012-D4
    | coreScalarKeyword                                                             # coreScalarRef  // RM005
    | ID                                                                            # identifier
    | NULL                                                                          # null
    | BOOLEAN                                                                       # boolean
    | RECURSE                                                                       # recurse
    | LONG_LIT                                                                      # longLiteral    // RM005
    | INT                                                                           # integer
    | FLOAT_LIT                                                                     # floatLiteral   // RM005
    | DOUBLE_LIT                                                                    # double         // RM005
    | CHAR_LIT                                                                      # charLiteral    // RM005
    | STRING                                                                        # string
    ;

ifExpr
    : IF test=expression THEN NL* thenBlock=block
      (NL* ELSE NL* (elseIf=ifExpr | elseBlock=block))?
    ;

defExpr
    : (EXPORT | PRIVATE)? DEF name=ID (OF typeParameters)? (':' type)?
      ('=' NL* (effectClause ON)? value=expression)?
    ;

block
    : '(' sep? ')'
    | '(' sep? item (sep item)* sep? ')'
    ;

// ---- Parameters (split by syntactic role)

classParameters
    : '(' NL* classParam (NL* ',' NL* classParam)* NL* ')'
    | '(' NL* ')'
    ;

classParam
    : PRIVATE? DEF? paramModifier* ID (':' type)? ('=' NL* defaultValue=expression)?
    ;

fnParameters
    : '(' NL* fnParam (NL* ',' NL* fnParam)* NL* ')'
    | '(' NL* ')'
    ;

fnParam
    : paramModifier* ID (':' type)? ('=' NL* defaultValue=expression)?
    ;

fnTypeParameters
    : '(' NL* fnTypeParam (NL* ',' NL* fnTypeParam)* NL* ')'
    | '(' NL* ')'
    ;

fnTypeParam
    : paramModifier* (ID ':')? type
    ;

// ---- Generics with `of` (LL(1), BIP012-D2–D4)

typeParameters
    : ID
    | '(' NL* ID (NL* ',' NL* ID)* NL* ')'
    ;

typeArguments
    : '(' NL* type (NL* ',' NL* type)* NL* ')'
    | nonParenNullableType
    ;

callParameters
    : '(' NL* callParameter (NL* ',' NL* callParameter)* NL* ')'
    | '(' NL* ')'
    ;

callParameter
    : (memberName ':')? expression
    ;

// ---- Types (LL(1), zero ambiguity between fnType and parenType; BIP012-D2 `of` vs `?`)

typeList
    : type (NL* ',' NL* type)*
    ;

type
    : nullableType ('|' NL* nullableType)*
    ;

nullableType
    : nonParenNullableType
    | '(' NL* type NL* ')' question='?'?                                            # parenType
    ;

nonParenNullableType
    : typeName ( OF ( '(' NL* type (NL* ',' NL* type)* NL* ')' question='?'?
                    | nonParenNullableType )
               | question='?'? )                                                    # simpleType
    | FN effectPrefix? fnTypeParameters (':' type)?                                 # functionType
    | NULL                                                                          # nullType
    ;

typeName
    : coreTypeKeyword
    | ID ('.' memberName)*
    ;

// ---- Keywords exempted in memberName (BIP011-D11 + BIP012)

coreCollectionKeyword
    : LIST | SET | SORTEDSET | MAP | SORTEDMAP | ENTRY
    ;

coreScalarKeyword
    : BYTE | SHORT | INT_KW | LONG_KW | FLOAT_KW | DOUBLE_KW | BOOL_KW | CHAR_KW | STRING_KW
    ;

coreTypeKeyword
    : coreScalarKeyword | coreCollectionKeyword | ANY | VOID
    ;

memberName
    : ID
    | coreTypeKeyword
    | effectModifier
    | AXIOM | IMPURE | RECORD
    ;

// ---- Lexer (preserving RM005 literals and escapes; fixing leading-dot floats and COMMENT)

LONG_LIT:   [0-9]+ [lL];
FLOAT_LIT:  ([0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)? | [0-9]+ ([eE] [+-]? [0-9]+)) [fF]
          | [0-9]+ [fF];
DOUBLE_LIT: [0-9]+ '.' [0-9]+ ([eE] [+-]? [0-9]+)? [dD]?
          | [0-9]+ [eE] [+-]? [0-9]+ [dD]?
          | [0-9]+ [dD];
INT:        [0-9]+;
CHAR_LIT:   '\'' ( ~['\\\r\n] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) ) '\'';
STRING:     '"' ( ~[\\"] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* '"';

PLUS: '+'; MINUS: '-'; MUL: '*'; DIV: '/';
LT: '<'; LTE: '<='; EQ: '=='; NEQ: '<>' | '!='; GTE: '>='; GT: '>';
COLON: ':'; SEMICOLON: ';';
ASSIGN: '='; PLUS_ASSIGN: '+='; MINUS_ASSIGN: '-='; MUL_ASSIGN: '*='; DIV_ASSIGN: '/=';
DOT: '.'; SAFE_DOT: '?.'; ARROW: '->'; PIPE: '|'; QUESTION: '?'; BANG: '!';
LPAREN: '('; RPAREN: ')'; COMMA: ',';

// Core language keywords
IF: 'if'; THEN: 'then'; ELSE: 'else'; DEF: 'def'; RETURN: 'return'; WHILE: 'while';
AND: 'and'; OR: 'or'; NULL: 'null'; NOT: 'not'; BOOLEAN: 'true' | 'false'; RECURSE: 'recurse';
OBJECT: 'object'; CLASS: 'class'; RECORD: 'record'; INTERFACE: 'interface';
IMPLEMENTS: 'implements'; EXTENDS: 'extends'; IS: 'is'; AS: 'as';
THIS: 'this'; SUPER: 'super'; PRIVATE: 'private'; STATIC: 'static'; EXPORT: 'export';

// New disambiguating keywords (BIP012)
FN: 'fn'; OF: 'of'; ON: 'on';

// Effect & algebraic keywords (BIP007-P1, formerly contextual IDs)
AXIOM: 'axiom'; IMPURE: 'impure'; PURE: 'pure'; HERMETIC: 'hermetic';
MUT: 'mut'; BORROW: 'borrow'; DETERMINISTIC: 'deterministic'; NOTHROW: 'nothrow';
IDEMPOTENT: 'idempotent'; COMMUTATIVE: 'commutative'; ASSOCIATIVE: 'associative';
MONOTONE: 'monotone'; INJECTIVE: 'injective';

// Core type keywords (BIP011-D11 / RM005)
BYTE: 'byte'; SHORT: 'short'; INT_KW: 'int'; LONG_KW: 'long';
FLOAT_KW: 'float'; DOUBLE_KW: 'double'; BOOL_KW: 'bool'; CHAR_KW: 'char'; STRING_KW: 'string';
LIST: 'list'; SET: 'set'; SORTEDSET: 'sortedset'; MAP: 'map'; SORTEDMAP: 'sortedmap'; ENTRY: 'entry';
ANY: 'any'; VOID: 'void';

ID: [_a-zA-Z] [_a-zA-Z0-9]*;

MULTILINECOMMENT: ';;(' .*? ';;)' -> channel(HIDDEN);
COMMENT:          ';;' (~[(\r\n] ~[\r\n]*)? -> channel(HIDDEN);
NL:               '\r'? '\n';
WS:               [ \t]+ -> skip;
```

---

## 6. Side-by-Side Before & After Syntax Showcase

| Construct | Current (post-`RM005` `Babble.g4`) | Proposed (`BIP012`) |
|---|---|---|
| Simple function | `def square = (x: int): int -> (x * x)` | `def square = fn (x: int): int -> (x * x)` |
| Short lambda | `xs.map((n) -> ("v=" + n))` | `xs.map(fn (n) -> ("v=" + n))` |
| Effect-annotated function | `def dot = pure deterministic (o: Vec2): double -> (...)` | `def dot = fn pure deterministic (o: Vec2): double -> (...)` |
| Axiomatic function | `def fib = axiom pure (n: int): int -> (...)` | `def fib = fn axiom pure (n: int): int -> (...)` |
| Function type | `def apply(A, B) = (x: A, f: (a: A): B): B -> (f(x))` | `def apply of (A, B) = fn (x: A, f: fn (A): B): B -> (f(x))` |
| Effect function type | `f: pure (x: int): int` | `f: fn pure (x: int): int` |
| Nullable function type | `f: ((x: int): int)?` | `f: (fn (int): int)?` |
| External `axiom` | `axiom pure deterministic java.lang.Math.sqrt` | `axiom pure deterministic on java.lang.Math.sqrt` |
| Constructor `axiom` | `axiom pure java.util.HashMap()` | `axiom pure on java.util.HashMap()` |
| `def` with `axiom` | `def m = axiom pure import("math.ba")` | `def m = axiom pure on import("math.ba")` |
| Generic class | `def Box(T) = class (def value: T) (...)` | `def Box of T = class (def value: T) (...)` |
| Value class / record | `def Pair(A, B) = value class (a: A, b: B) (...)` | `def Pair of (A, B) = record (a: A, b: B) (...)` |
| Generic type (1 arg) | `xs: list(int)` / `xs: list(int?)` / `xs: list(int)?` | `xs: list of int` / `xs: list of int?` / `xs: (list of int)?` |
| Generic type (2 args) | `m: map(string, int)` / `m: map(string, int)?` | `m: map of (string, int)` / `m: map of (string, int)?` |
| Standalone type application | *(not supported)* | `def IntBox = Box of int` / `def idInt = id of int` |
| Call-site type application | `Box(int?)(null)` / `list(int)(1, 2)` | `Box of int? (null)` / `list of int (1, 2)` |
| Curried value call | `f(a)(b)` *(ambiguous with `Box(T)(x)`!)* | `f(a)(b)` *(100% unambiguous!)* |
| Class with newline before body | `class (x: int)\n(...)` *(syntax error!)* | `class (x: int)\n(...)` *(valid!)* |
| Single-line block comment | `;;( ok ;;) def x = 1` *(eats `def x = 1`!)* | `;;( ok ;;) def x = 1` *(valid!)* |

---

## 7. Impact on Pipeline, `Formatter`, `BabbleSession` & Codebase

1. **Parser & `BabbleScriptEngine`:**
   * Deleting all **172 lines of `@parser::members`** and all **10 semantic predicates** (including `RM005`'s `isTypeApplicationCall()` and `isCoreTypeAfterIs()`) shrinks `Babble.g4` and its ATN dramatically, enabling ANTLR4's two-stage **`PredictionMode.SLL` → `PredictionMode.LL`** fast path in `BabbleScriptEngine.parse`.
2. **`BabbleSession.classify` (REPL multi-line detection):**
   * The `Token.EOF` workaround inside `isFunctionLiteralStart()` (lines 54–57) is deleted. Incomplete inputs like `def f = fn pure (` or `def xs: list of ` fail cleanly at `EOF` (`ParseFailure.atEnd() == true`) without any special-case code.
3. **`Formatter` (`org.babblelang.format.Formatter`):**
   * `callParentheses` simplifies (type parameters and type arguments no longer have tight `(` after an identifier: `Box of (A, B)` has a space before `(` just like `if (cond)`).
   * Add canonical spacing rules: space after `fn`, `of`, `on`, `record`; no space before `!`; space between `typeArguments` and `callParameters` in `list of int (1, 2)` vs tight `list(1, 2)`.
4. **Analysis & Backends (`BlockScopes`, `ScopeAnalysis`, `CallSolver`, `TypeAnnotations`, `TypeChecker`, `EffectChecker`, `Interpreter`, `CodeGenerator`):**
   * **Net simplification over `RM005`:** deletes `ScopeAnalysis.typeApplyCalls` (`markTypeApplyCall` / `isTypeApplyCall`), `BlockScopes.visitTypeApplyCall`, and the curried-call fallback branches in `CallSolver.typeApplyCall`, `Interpreter.visitTypeApplyCall`, and `CodeGenerator.visitTypeApplyCall`, as well as the dual `# isExpression` handling (`coreType` vs `right`).
   * Mechanical updates for renamed/split ANTLR contexts (`DefExprContext`, `IfExprContext`, `FnParamContext`, `ClassParamContext`, `FnTypeParamContext`, `TypeApplyContext`, `CoreCollectionContext`, unified `AssignExpressionContext`), plus compile-time specialization for standalone `expr of T` (`BIP012-D4`). Zero runtime (`org.babblelang.runtime`) changes.
5. **Repository Migration:**
   * Existing `.ba` scripts (`11` in `src/test/babble/`, `26` in `examples/`), `.batest` suites (`67` files, including all `RM005` core-type suites), and JUnit inline snippets will be updated for `fn`, `of`, `on`, `record`, and `as`/`is`/`not` precedence. A small AST-guided migrator using the pre-`BIP012` parser can convert almost all existing `.ba` and `.batest` files automatically and verify byte-identical `examples/raytracer-argon/scene.ppm`.

---

## 8. Open Questions & Decisions (`BIP012-Q1`–`Q9` → `BIP012-D1`–`D9`)

| Question | Decision | Resolution |
|---|---|---|
| **BIP012-Q1** Function keyword & modifier order | **BIP012-D1** | **`fn` first:** `fn pure deterministic (x: int): int -> (...)` and `fn pure (x: int): int`. Strictly LL(1) in both expression and type positions, concise, and aligned with `class`/`record`/`interface`/`object`. |
| **BIP012-Q2** Precedence of `of` vs `?` | **BIP012-D2** | **`?` binds tighter than `of` on bare single args:** `list of int?` = `list of (int?)`, `(list of int)?` = nullable list, `map of (string, int)?` = `(map of (string, int))?`, `list of (int \| string)?` = `(list of (int \| string))?`, and `list of int??` is rejected (use `(list of int?)?`). |
| **BIP012-Q3** Generic `def` declarations | **BIP012-D3** | **Uniform `of` everywhere:** `def Box of T = ...`, `def Pair of (A, B) = ...`, `def id of T = fn (x: T): T -> (x)`. Keeps `def (a, b) = expr` destructuring (`BIP005-D3`) visually and grammatically distinct. |
| **BIP012-Q4** Standalone `expr of T` vs call-only | **BIP012-D4** | **First-class standalone `expr of T` right away:** `def idInt = id of int`, `def IntBox = Box of int`, `def IntList = list of int`, as well as `Box of int? (null)` and `list of int (1, 2, 3)`. Zero-cost compile-time specialization in `TypeChecker`, erased at runtime. |
| **BIP012-Q5** `class` header vs body disambiguation | **BIP012-D5** | **Mandatory `classParameters`:** `class () (...)`, `class (def x: int) implements I (...)`, `record (x: double, y: double) (...)`. Zero extra body keyword, 100% LL(1), allows `NL*` before `classBlock`. |
| **BIP012-Q6** `value class` keyword | **BIP012-D6** | **Replace `value class` with `record`:** `record (x: double, y: double) (...)`. Eliminates contextual `modifier=ID? CLASS`, leaves `value` an ordinary `ID` everywhere, and exempts `record` in `memberName`. |
| **BIP012-Q7** `effectDeclaration` separator keyword | **BIP012-D7** | **Use `on`:** `axiom pure deterministic on java.lang.Math.sqrt`, `axiom pure on java.util.HashMap()`, `impure on java.net.URL.equals`, `def m = axiom pure on import("math.ba")`. Unambiguously terminates effect modifiers even when targets start with core type keywords. |
| **BIP012-Q8** Precedence fixes for `as`, `is`, `not` | **BIP012-D8** | **Fix all three:** `as` above `*`/`+`, `is` taking `type` above `==`/`<` (eliminating `RM005`'s `isCoreTypeAfterIs()`), and `not` below `==`/`is` (above `and`), so `a + b as int`, `x is Foo == true`, and `not a == b` all parse naturally without extra parens. |
| **BIP012-Q9** Sequencing relative to `BIP011` | **BIP012-D9** | **Land `BIP011` (`RM005`) first, then `BIP012`:** `RM005` has now landed with `C(T)(...)`, so `BIP012-P1`–`P4` executes directly on top of `RM005`, migrating core collections and deleting `isTypeApplicationCall()`, `isCoreTypeAfterIs()`, and `ScopeAnalysis.typeApplyCalls` cleanly in one pass. |

---

## 9. Phased Implementation Plan (`BIP012-P1`–`BIP012-P4`)

Building directly on landed `RM005` (`BIP012-D9`), each phase keeps `mvn -B package` (both `interpret` and `compile` with `CheckClassAdapter`), `FormatterTestCase`, and byte-identical `examples/raytracer-argon/scene.ppm` green:

1. **`BIP012-P1` — Zero-syntax-change grammar & lexer cleanups:**
   Factor `ifExpr` (removing `{_input.LA(1) == IF}?`), unify `assignExpression`, split `classParameters`/`fnParameters`/`fnTypeParameters`, and fix `COMMENT` vs `MULTILINECOMMENT` and leading-dot `FLOAT_LIT`/`DOUBLE_LIT` rules.
2. **`BIP012-P2` — `fn`, reserved effect keywords & `on`:**
   Introduce `FN` (`BIP012-D1`) and `ON` (`BIP012-D7`), reserve the 13 `BIP007-P1` effect keywords (exempted in `memberName`), delete all 7 effect/function predicates (P1–P7) and the `BabbleSession.classify` `EOF` workaround, and update `Formatter` and tests.
3. **`BIP012-P3` — `of` generics, standalone `expr of T`, mandatory `class` parameters & `record`:**
   Switch type arguments (`BIP012-D2`), `def` type parameters (`BIP012-D3`), and call-site/standalone type applications (`BIP012-D4`) to `of`—deleting `RM005`'s `isTypeApplicationCall()` predicate (P9), `ScopeAnalysis.typeApplyCalls`, `BlockScopes.visitTypeApplyCall`, and the curried-call fallback in `CallSolver`, `Interpreter`, and `CodeGenerator`; enforce mandatory `classParameters` with `NL*` before `classBlock` (`BIP012-D5`); replace `value class` with `record` (`BIP012-D6`); migrate `.ba`, `.batest`, and `examples/` scripts.
4. **`BIP012-P4` — Precedence alignment, `SLL` fast path & doc sync:**
   Apply `BIP012-D8` (`is` with `type` RHS above `==`—deleting `RM005`'s `isCoreTypeAfterIs()` (P10) and the last remaining `@parser::members` code—`as` above `*`/`+`, `not` below `==`/`is`), enable `PredictionMode.SLL` → `LL` in `BabbleScriptEngine.parse`, and update `BIP002`, `BIP007` §4.1, `BIP011` §5.2/§7, `README.md`, and `CLAUDE.md`.
