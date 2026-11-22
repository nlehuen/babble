# BIP026: Multiline strings and string templates

| | |
|---|---|
| **BIP** | 026 |
| **Date** | 2026-11-22 |
| **Status** | Draft (Options analysis & proposed design; Open Questions `BIP026-Q1`–`Q10`) |
| **Depends on** | object model & JVM interfaces ([bip002_object-model.md](bip002_object-model.md), `RM003`), interactive mode ([bip004_repl.md](bip004_repl.md)), `java.base`-only runtime boundary ([bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)), static typing, unions, nullables & generics ([bip008_static-typing.md](bip008_static-typing.md)), core types & transparent `string` ([bip011_core-types.md](bip011_core-types.md)), predicate-free single-bracket `()` grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops & iterables ([bip013_iterators.md](bip013_iterators.md)), ANTLR-free `HIR` & Nanopass optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)), parser dependency analysis ([bip015_down-with-the-ants.md](bip015_down-with-the-ants.md)), pure-by-default effects & callback-effect forwarding ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md)), axioms ([bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)), array types ([bip020_array-types.md](bip020_array-types.md)), optional/generic/overloaded calls ([bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md)), intersection types ([bip022_intersection-types.md](bip022_intersection-types.md)), type aliases ([bip023_type-aliases.md](bip023_type-aliases.md)) |
| **Scope** | Multiline and raw string literals with compile-time indentation handling; first-class string templates evaluating to a structured template object of static string fragments and placeholders backed by lazily memoized callbacks; a plug-in evaluation policy model (built-in string concatenation plus user/library policies such as SQL `PreparedStatement`, HTML escaping, and lazy structured logging); integration with Babble's single-bracket `()` grammar, `Formatter`, REPL `BabbleSession`, static types, `BIP016`/`BIP017` effects, `BIP019` axioms, both execution backends, and `BIP014` `-O1` deforestation |

---

## 1. Summary & Motivation

### 1.1 What Babble has today

Babble currently has a single string literal token in [`Babble.g4`](../../src/main/antlr4/org/babblelang/parser/Babble.g4) (line 178):

```antlr
STRING: '"' ( ~[\\"] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* '"';
```

And a single unescaper in [`Interpreter.java`](../../src/main/java/org/babblelang/engine/impl/Interpreter.java) (`unescape`, lines 802–837). This creates four concrete pain points:

1. **No real multiline or raw strings.** Because `~[\\"]` does not forbid `\r` or `\n`, plain `"..."` literals technically span lines today — but with none of the properties multiline text needs: leading code indentation leaks into the runtime string, every `"` and `\` must be escaped (`\"`, `\\`), an unclosed `"` swallows the rest of the file until EOF, and `babblefmt` ([`Formatter.java`](../../src/main/java/org/babblelang/format/Formatter.java)) cannot re-indent the literal safely without breaking its parse-tree equivalence check (`shape()`).
2. **Verbose, error-prone string assembly.** Building formatted text relies on `"Hello, " + name + " (" + count + ")"` (`Operators.add` → `ArraySupport.format`), `java.lang.StringBuilder`, or `java.lang.String.format("%s (%d)", name, count)` (usable since `BIP021-P2` added Java varargs, but positional, detached from the embedded values, and runtime-checked).
3. **No structured template boundary (injection hazard).** Plain concatenation and eager interpolation flatten static text and dynamic user input into an undifferentiated `string`. A SQL, HTML, XML, shell, or JSON consumer cannot tell which characters were written by the programmer and which came from untrusted variables.
4. **No lazy evaluation of embedded expressions.** Eager interpolation (`"debug: " + expensiveStats()`) evaluates its arguments even when a logger is disabled or a policy rejects a malformed template before binding parameters.

### 1.2 What this proposal introduces

This BIP proposes two closely connected features tailored to Babble's idioms (single bracket `()`, pure-by-default effects, `java.base`-only runtime, dual-mode parity):

1. **Multiline and raw string literals** (`"""..."""`, `r"..."`, `r"""..."""`, with Babble-friendly options for dedenting and delimiter escalation) so embedded SQL, JSON, HTML, regular expressions, and test fixtures stay readable and `babblefmt`-clean.
2. **First-class string templates with a plug-in evaluation policy model:** a template literal does **not** eagerly concatenate into a `string`. Instead, it evaluates to a structured **template object** (`template`) holding:
   * an immutable sequence of static literal **fragments** (`N + 1` strings), and
   * a parallel sequence of `N` **placeholders**, each wrapping a **lazily memoized callback** (`fn () -> expr`, evaluated at most once on demand) plus compile-time metadata (such as the source expression text).

Any **evaluation policy** can consume a template object — the built-in policy performs standard string concatenation, while domain-specific policies can inspect the static fragments first, cache compiled forms, skip evaluation altogether, or bind typed parameters safely:

```babble
;; (Illustrative — using the recommended option package from §3 & §8)

;; 1. Multiline string (compile-time dedented to the closing """)
def banner = """
    Welcome to "Babble"
      - single bracket type ()
      - pure by default
    """

;; 2. String template evaluated with the default concatenation policy
def msg: string = t"Hello, $(name)! You have $(items.size()) item(s).".render()

;; 3. The exact same template mechanism driving a SQL PreparedStatement policy
def query: template = t"""
    SELECT id, email
    FROM users
    WHERE role = $(role)
      AND score >= $(minScore)
    """
def stmt: java.sql.PreparedStatement = query.eval(SqlPolicy(conn).evaluate)

;; 4. Lazy logging: expensiveStats() never runs when DEBUG is disabled
log.debug(t"Frame $(frameNo): $(computeExpensiveStats(world))")
```

### 1.3 Goals and non-goals

**Goals:**
1. Clean multiline and raw string literals that preserve Babble's **single-bracket `()` constraint** (`BIP012` §2), stay **predicate-free and pure `PredictionMode.SLL`** (`ParserGrammarTestCase`), and integrate smoothly with `babblefmt` and the interactive REPL (`BabbleSession.classify`).
2. A first-class `template` value whose placeholders map to **lazily memoized callbacks** (at-most-once evaluation, `null`-safe, cycle-safe, thread-safe) and whose static fragments remain strictly separated from dynamic values.
3. A **plug-in evaluation policy** architecture where concatenation is just the default policy, and policies like JDBC `PreparedStatement`, HTML auto-escaping, JSON serialization, or guarded logging require **no compiler plugins and no special call syntax**.
4. Sound integration with Babble's static type system (`BIP008`/`BIP011`), pure-by-default effects and closure provenance (`BIP016`/`BIP017`), `BIP019` axioms, `java.base`-only runtime boundary (`BIP006`), dual-mode (`Interpreter` + `HIR`) parity, and `BIP014` `-O1` zero-overhead deforestation for direct concatenation.

**Non-goals:**
* **Compile-time SQL/HTML/regex syntax validation** (macro-style compile-time DSL execution belongs to future `BIP014-D4` macros; here, static fragments are fixed at compile time so policies can validate and cache them once per literal site at runtime).
* **Implicit `template` → `string` coercion** or `template + string` concatenation — both would silently defeat injection safety.
* **Format-mini-language parser baked into the core runtime** (like Python's full `format_spec` or `printf` specifiers); policies are free to accept format metadata if optional spec syntax is adopted, and Java's `String.format` already works via `BIP021-P2`.
* **Runtime mutable templates** — a `template` is structurally immutable once constructed (its placeholders only transition monotonically from unevaluated to memoized).

---

## 2. State of the Art

This section reviews four reference languages — **C++**, **Python** (including PEP 750 `t""` template literals and PEP 822), **Java** (JEP 378 Text Blocks and the JEP 430/459/465 String Templates evolution), and **Kotlin** (multiline raw strings, library dedenting, and KEEP-380 multi-dollar interpolation) — focusing on how each separates (or conflates) multiline layout, rawness, interpolation syntax, evaluation timing, and custom processing.

### 2.1 C++ : custom-delimited raw literals, `std::format`, and P1819 / P3412

* **Adjacent literal concatenation & standard escapes:** C++ has no dedicated multiline quote delimiter like `"""`, relying instead on implicit concatenation of adjacent literals (`"line 1\n" "line 2\n"`) or raw string literals.
* **Raw string literals (`R"delim(...)delim"`, C++11):** Suppress *all* escape processing (backslashes, quotes, and newlines are taken verbatim) and allow an optional user-chosen delimiter up to 16 characters (`[^ ()\\\t\v\f\n]`) between `"` and `(`:
  ```cpp
  const char* sql = R"sql(
  SELECT * FROM "users" WHERE path LIKE 'C:\temp\%' AND note != '")'
  )sql";
  ```
  Because the author picks the delimiter tag, **no text content can ever collide with the closing delimiter** — solving the nested-`"""` and `)"` problem completely without escaping. However, C++ raw strings perform **no indentation stripping** at all; indenting a raw string inside a function embeds all leading spaces directly into the literal.
* **`std::format` (C++20) and `std::print` (C++23):** Brought Python/Rust-style `{}` formatting with compile-time format-string validation (`std::format_string<Args...>`) and user-extensible `std::formatter<T>`. It is type-safe and zero-overhead, but separates the format string from its trailing argument list (`std::format("User {} has {}", name, n)`), which grows error-prone in large templates and still only targets text formatting (not SQL binding).
* **String interpolation proposals — P1819 vs. P3412:**
  * **P1819 (*Interpolated Literals*, 2019, Vittorio Romeo):** An early incubator proposal where `f"Connecting on port {port}..."` did **not** eagerly construct a `std::string`; instead, it expanded into a compile-time structured object capturing static fragments and **unevaluated/callable expression pieces** completely decoupled from `<string>` or `<format>`. Conceptually, P1819 anticipated lazy structured templates, though it did not advance through WG21 due to standardization complexity and lack of integration with `<format>`.
  * **P3412 (*String Interpolation*, 2024–2026):** The modern C++26/29 direction re-anchors `f"Value is {x}"` directly onto `std::format` / `std::basic_formatted_string` (leveraging compile-time reflection), trading P1819's open-ended lazy template object for tight, eager `<format>` integration.

*Takeaways for Babble:* C++'s `R"delim(...)delim"` is the gold standard for **collision-proof raw delimiters** (and visually matches Babble's `()`-only aesthetic!), while C++'s lack of compile-time dedenting is a real ergonomic gap for indented blocks. P1819 demonstrated the value of decoupling an interpolated literal into fragments + callable expressions before C++ pivoted back to eager `<format>` in P3412.

### 2.2 Python : triple quotes, f-strings, PEP 750 `t""` templates, and PEP 822

Python has the richest multi-decade evolution of string literals across all four languages:

* **Triple-quoted multiline & raw strings:** `"""..."""` and `'''...'''` allow unescaped quotes and embedded newlines, combined with `r`/`R` for raw backslashes (`r"""..."""`, with the notorious quirk that a raw string still cannot end on an odd number of backslashes because `\"` escapes the quote during lexing). Whitespace is **verbatim**; programmers rely on runtime `textwrap.dedent(...)`.
* **f-strings (`PEP 498`, Python 3.6 → `PEP 701`, Python 3.12):** `f"Hello {name!r}, {val:.2f}"` evaluates expressions eagerly in lexical scope and formats them immediately into a `str`. `PEP 701` removed the old lexer hacks so f-strings can now nest arbitrarily, reuse the same quote character inside `{...}`, span multiple lines, and contain `#` comments inside expressions.
* **Template string literals — `t""` (`PEP 750`, accepted and shipped in Python 3.14):** Generalizes f-string syntax (`t"..."`, `t"""..."""`, raw `rt"..."`/`tr"..."`, case-insensitive prefixes) so a template evaluates to an immutable `string.templatelib.Template` instead of `str`:
  ```python
  from string.templatelib import Template, Interpolation

  tmpl: Template = t"SELECT * FROM users WHERE name = {user_input!s:.32}"
  # tmpl.strings        == ("SELECT * FROM users WHERE name = ", "")
  # tmpl.interpolations == (Interpolation(value=..., expression="user_input",
  #                                       conversion="s", format_spec=".32"),)
  # tmpl.values         == (user_input,)
  ```
  Key design decisions in PEP 750:
  1. **Structured `Template` & `Interpolation` API:** `len(strings) == len(interpolations) + 1`; iterating over a `Template` yields alternating non-empty `str` segments and `Interpolation` instances (`value`, `expression`, `conversion`, `format_spec`); values keep their **raw runtime types** (e.g., `int` or `float`, not pre-formatted `str`) so SQL/JSON/HTML handlers see real objects.
  2. **Injection-safe composition:** `t1 + t2` produces a new `Template` (merging the boundary static strings), whereas **`t1 + "raw"` raises `TypeError`** so untrusted strings can never silently enter `tmpl.strings`.
  3. **No special processor syntax:** Processing is just an ordinary function call (`sql(t"...")`, `html(t"...")`).
  4. **Eager `.value` vs. rejected lazy `.getvalue()`:** Early drafts of PEP 750 (and its predecessor PEP 501) proposed **lazy evaluation** where each `Interpolation` exposed a `.getvalue()` thunk. The final accepted PEP 750 **rejected implicit laziness** and adopted **eager evaluation** (`.value`), requiring users who want laziness to wrap expressions manually as `t"{(lambda: expensive())}"`. Python's rationale was twofold: (a) in dynamic Python, mutable loop/local variables captured implicitly in closures cause surprising aliasing bugs compared to f-strings, and (b) implicit thunks complicated static type checkers (Mypy/Pyright).
* **Dedented multiline strings — `d""` (`PEP 822`, Draft Jan 2026, targeting Python 3.16):** Because `textwrap.dedent()` runs at runtime on `str`, it **cannot compose with `t"""..."""`** (which returns a `Template`, not a `str`, and whose interpolated values may contain newlines that must not be stripped!). PEP 822 therefore proposes a `d` prefix (`d"""..."""`, `dt"""..."""`, `dr"""..."""`) performing compile-time closing-quote dedenting.

*Takeaways for Babble:*
1. **PEP 750 validates first-class `Template` values + ordinary function processing + forbidding `Template + str`**, and highlights the value of preserving **raw typed values** and **source `expression` metadata**.
2. **PEP 822 proves that compile-time dedenting must be built into multiline literals**, not left to a runtime `String` method, if multiline strings and templates are to compose cleanly!
3. **PEP 750's rejection of `.getvalue()` is a crucial contrast case:** Babble *does* want lazily memoized callbacks (unlocking zero-cost skipped logging, pre-evaluation SQL validation/caching, and multi-pass policies), and unlike Python, Babble has a **static pure-by-default effect & provenance checker (`BIP016`/`BIP017`)** that can make lazy callbacks type-safe and effect-sound — provided we address mutated local captures, `null`/exception memoization, and effects explicitly (§3.3, §4.2).

### 2.3 Java : Text Blocks (JEP 378) and the String Templates Reset (JEP 430 / 459 / 465)

* **Text Blocks (`JEP 378`, permanent in Java 15):** Java abandoned raw string literals (`JEP 326`, withdrawn 2018) in favor of escaped, compile-time-dedented multiline text blocks:
  ```java
  String query = """
      SELECT id, name \
      FROM users
      WHERE note = "ok"\s
      """;
  ```
  JEP 378 defines a crisp, deterministic **3-step compile-time pipeline**:
  1. **Line-ending normalization:** `\r\n` and `\r` → `\n`.
  2. **Incidental whitespace removal (`stripIndent()`):** The opening `"""` must be followed by a line terminator (optional trailing horizontal spaces only). The minimum common leading whitespace across all non-blank lines **and the closing `"""` line** is stripped from every line, and trailing horizontal whitespace on every line is stripped. Moving the closing `"""` left of the content intentionally preserves base indentation.
  3. **Escape translation:** Standard Java escapes plus two text-block-motivated escapes: `\` immediately before a line terminator (joins wrapped source lines without inserting `\n`) and `\s` (translates to a literal space while acting as a fence preventing trailing-whitespace stripping to its left).
* **String Templates (`JEP 430` Java 21 1st Preview → `JEP 459` Java 22 2nd Preview → `JEP 465` Withdrawn April 2024, removed in Java 23):**
  * JEP 430/459 introduced `StringTemplate` (`List<String> fragments()`, `List<Object> values()`) and mandatory **template processor** prefixes (`STR."Hello \{name}"`, `FMT."%2.2f\{x}"`, `RAW."..."`, `DB."SELECT ... \{id}"`), with a `StringTemplate.Processor<R, E>` interface and an `invokedynamic` `Processor.Linkage` SPI for zero-allocation `StringConcatFactory` lowering.
  * Notice Java's interpolation syntax `\{expr}`: because `\{` was previously an illegal escape sequence, Java avoided introducing a new sigil (`$`) and avoided breaking existing `"..."` strings.
  * **Why Java withdrew String Templates (Brian Goetz, *"Update on String Templates"*, March 8, 2024):** Real-world experience (especially in `jextract`) exposed a fundamental design flaw: **template capture** (building a `StringTemplate`) and **template processing** (calling a processor on it) were *complected*. Making `PROC."..."` a mandatory syntactic form turned processors into *"a new syntax for a method call"*, forced every library author into an awkward API dilemma (method taking `StringTemplate` vs. custom `Processor` field), over-engineered the `Processor`/`Linkage` SPI, and made everyday string formatting feel noisy (`STR."..."`).
  * Java's redesign direction decouples capture from processing: a template literal should evaluate directly to a first-class `StringTemplate` value (passable to ordinary methods/functions, with no mandatory `PROC.` prefix), while still allowing `invokedynamic` optimization when lowered to string concatenation.

*Takeaways for Babble:*
1. **JEP 378's 3-step dedenting algorithm** (anchored on the closing `"""`, with `\s` and `\<newline>`) is the cleanest compile-time multiline model on the JVM and composes directly with `t"""..."""` and `babblefmt` re-indentation.
2. **Java's JEP 459 post-mortem is a direct validation of the architecture requested for BIP026:** evaluate a template literal directly to a **first-class `Template` object**, and let **ordinary functions / evaluation policy objects** process it — never fuse capture and processing into mandatory `PROC."..."` syntax!
3. **JVM `StringConcatFactory` (`invokedynamic`):** Already inside `java.base` on JDK 21+, giving Babble's `-O1` compiler a native, zero-allocation fast path when a template is immediately evaluated by the default concatenation policy.

### 2.4 Kotlin : raw multiline strings, library dedenting, and multi-dollar interpolation

* **Escaped vs. raw multiline strings:** `"..."` is single-line and supports backslash escapes; `"""..."""` is multiline and **strictly raw** (backslashes are literal, no `\n` or `\"` escapes — embedding `"""` requires `${"\"\"\""}`).
* **Library-driven indentation stripping:** Kotlin does **not** strip indentation at compile time; instead, `kotlin.text` provides `.trimIndent()` (strips minimum common indent of non-blank lines, dropping blank first/last lines) and `.trimMargin("|")` (strips leading whitespace up to an explicit margin prefix). Because these are runtime methods on `String`, they are easy to understand, but they pay runtime work and **cannot apply to structured non-`String` templates**.
* **Built-in eager interpolation everywhere:** Both `"..."` and `"""..."""` support `$identifier` and `${expression}` directly, eagerly concatenating to `String`. Kotlin has **no built-in first-class template object or custom processor API**: safe SQL/HTML templates require third-party compiler plugins (such as *Terpal* or *custom-string-template*) that rewrite string templates at IR level into `surroundings` + `holes`, or falling back to builder DSLs (Exposed/Ktorm) and manual `?` placeholders.
* **Multi-dollar string interpolation (`KEEP-380`; preview in Kotlin 2.1, stable in Kotlin 2.2):** Because `"""..."""` disables backslash escapes, writing literal `$` inside raw multiline strings (JSON Schema `$schema`, currency `$100`, regex anchors, shell scripts) historically required ugly `${'$'}` workarounds. KEEP-380 lets the programmer prefix a string with *k* dollar signs (`$$"""..."""`, `$$$"""..."""`): only *k* consecutive `$` signs trigger interpolation (`$${expr}` / `$$name`), while any shorter run (`$`) stays literal:
  ```kotlin
  val schema = $$"""
      {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "$${title}"
      }
  """
  ```

*Takeaways for Babble:*
1. **Kotlin's multi-dollar prefix (`KEEP-380`) is a brilliant solution** for combining raw multiline strings with interpolation without needing backslash escapes or awkward `${'$'}` workarounds.
2. **Kotlin's bare `$identifier` shorthand has well-known edge cases** (`$foo_bar`, `$foo.bar`, `$100`) that vanish completely if interpolation always uses a bracketed form (`$(expr)`).
3. **Eager-only interpolation on plain strings is a trap for SQL/HTML safety:** requiring an explicit template prefix (`t"..."`) that yields a distinct `Template` type keeps plain strings simple and raw-safe while making structured templates first-class without compiler plugins.

### 2.5 Cross-Language Comparison Matrix

| Dimension | C++ (11–26) | Python (3.12–3.14 + PEP 822) | Java (15–23+) | Kotlin (2.2) | **Babble Constraints (`BIP012`/`BIP016`)** |
|---|---|---|---|---|---|
| **Multiline delimiter** | `R"delim(...)delim"` | `"""..."""` / `'''...'''` | `"""\n...\n"""` | `"""..."""` | Single bracket `()` only; pure `SLL` |
| **Raw strings** | `R"delim(...)delim"` (custom tag) | `r"..."`, `r"""..."""` | None (JEP 326 withdrawn) | `"""..."""` is always raw | Needed for regex, paths, SQL, codegen |
| **Indentation stripping** | None | Runtime `textwrap.dedent`; compile-time `d"""` (PEP 822 draft) | Compile-time `stripIndent` anchored on closing `"""` | Runtime `.trimIndent()` / `.trimMargin()` | Must compose with `t"""..."""` & `babblefmt` |
| **Interpolation syntax** | `{expr}` (`P1819`/`P3412`) | `{expr!c:fmt}` (`f""`, `t""`) | `\{expr}` (JEP 430/459, withdrawn) | `$id`, `${expr}`, `$$"""..$${expr}.."""` | **No `{}`!** Prefer `$(expr)` or `\(expr)` |
| **First-class template object** | P1819 (no); P3412 (format-bound) | `Template` (`t""`, PEP 750) | `StringTemplate` (withdrawn; redesigning) | None (compiler plugins only) | **Required:** fragments + memoized callbacks |
| **Placeholder evaluation** | Lazy (P1819) / eager (P3412) | **Eager** (`.value`; lazy `.getvalue()` rejected) | Eager (`values()`) | Eager | **Lazily memoized callbacks** (at-most-once) |
| **Custom evaluation policy** | `std::formatter` (text only) | Ordinary functions `sql(t"...")` | `PROC."..."` (rejected as complected) | None built-in | **Plug-in policy** (`tmpl.eval(policy)` / `policy(tmpl)`) |
| **Static effect / injection safety** | Format-string type check | `Template + str` → runtime `TypeError` | Static `StringTemplate` type | None (`String` everywhere) | **Static `template` type + `BIP016`/`BIP017` effects** |

---

## 3. Babble-Friendly Design Options

Designing multiline strings and string templates for Babble splits cleanly into **four orthogonal axes**:
* **Axis A:** Multiline and raw string literal syntax & dedenting (§3.1)
* **Axis B:** Template literal & placeholder syntax under Babble's `()`-only rule (§3.2)
* **Axis C:** Template object representation & memoized callback semantics (§3.3)
* **Axis D:** Plug-in evaluation policy API & invocation model (§3.4)

Each axis presents concrete Babble-friendly options with trade-offs and a recommended choice; §8 summarizes the cohesive recommended package and the open questions (`BIP026-Q1`–`Q10`) to settle before implementation.

---

### 3.1 Axis A — Multiline and Raw String Literals

#### Option A1 *(Recommended)* — Compile-time dedented `"""..."""` + raw `r"..."` / `r"""..."""`

Adopt triple double-quotes `"""..."""` for multiline strings using **Java JEP 378's closing-delimiter dedenting algorithm**, paired with a `r` prefix for raw strings (`r"..."` and `r"""..."""`):

```babble
;; Escaped multiline block: leading indent stripped to closing """
def html = """
    <div class="card">
        <p>Line 1\s
        continued \
        on one logical line</p>
    </div>
    """

;; Raw single-line string: backslashes are literal (regexes, Windows paths)
def pattern = r"\d{4}-\d{2}-\d{2}"
def winPath = r"C:\Users\ada\babble"

;; Raw multiline block: literal backslashes AND compile-time dedenting
def regexBlock = r"""
    ^\s*([a-zA-Z_]\w*)\s*=\s*"([^"]*)"\s*$
    """
```

* **Escaped `"""..."""` rules:**
  * The opening `"""` is followed by optional horizontal whitespace (`[ \t]*`) and a mandatory line break (`\r?\n`) for multiline blocks. *(Sub-option: also permit single-line `"""He said "hi"."""` with no dedent when content stays on the same line, or strictly require a newline after `"""` as in Java JEP 378 — `BIP026-Q2`.)*
  * Single `"` and `""` inside the block need no escaping; a literal `"""` is written `\"\"\"` (or `""\"`).
  * Supports all existing Babble escapes (`\b \t \n \f \r \" \' \\ \0 \uXXXX`) plus JEP 378's **`\s`** (literal space fencing trailing-whitespace stripping) and **`\` immediately before a newline** (line continuation suppressing `\n`).
  * **Compile-time 3-step normalization:** (1) normalize `\r\n` and `\r` to `\n`, (2) compute minimum common leading whitespace across all non-blank lines *and* the closing `"""` line, strip that prefix from every line and strip trailing horizontal spaces, (3) translate escapes.
* **Raw `r"..."` and `r"""..."""` rules:**
  * Backslashes have **no escape meaning** (`r"\n"` is the two characters `\` and `n`). Unlike Python — where `\"` still escapes quotes during lexing, making `r"C:\dir\"` a syntax error — Babble can lex `r"..."` strictly as `~["\r\n]*` so trailing backslashes like `r"C:\dir\"` work cleanly (while strings containing embedded `"` use `r"""..."""`).
  * For `r"""..."""`, dedenting runs Steps 1–2 (normalize line endings + strip incidental indent anchored on the closing `"""`) and skips Step 3 (no escape translation).
  * **Embedding `"""` or trailing `\` inside raw blocks (sub-options `BIP026-Q3`):**
    * **A1a (C# 11 / Swift variable quote count):** Allow $k \ge 3$ quotes (`""""...""""`) — any sequence of fewer than $k$ quotes is literal, including `"""`!
    * **A1b (C++ custom delimiter `r"tag(...)tag"`):** Allow `r"tag(...)tag"` (matching Babble's `()` aesthetic!) alongside `r"..."` / `r"""..."""` for completely collision-proof raw blocks.
    * **A1c (Keep it minimal):** Only `r"..."` and `r"""..."""`; use `"""..."""` with `""\"` or string concatenation in the rare case a raw literal contains `"""`.
* **What about existing `"..."` literals (`BIP026-Q1`)?**
  * Today `STRING` in `Babble.g4` permits raw newlines (`~[\\"]`). Once `"""..."""` exists, tightening `"..."` to **single-line only** (`~[\\"\r\n]`) catches unclosed strings on the offending line immediately and makes REPL `BabbleSession.classify` much cleaner — at the cost of breaking any script that relied on unescaped newlines inside `"..."` (worth auditing `src/test/` and `examples/` before deciding).
* **Pros:** Zero runtime cost; composes natively with `t"""..."""` templates (avoiding Python's PEP 822 problem); lets `babblefmt` re-indent blocks safely when surrounding code shifts; immediately familiar to Java/Python/Kotlin developers.
* **Cons:** Requires specifying the dedent algorithm carefully (especially mixed tabs/spaces and blank lines, §5.2).

#### Option A2 — Kotlin-style Verbatim `"""..."""` + Library `.stripIndent()` / `.trimMargin()`

Keep `"""..."""` verbatim (zero compile-time whitespace stripping) and rely on `java.lang.String.stripIndent()` (which already exists on Babble's transparent `string`, `BIP011-D2`) or a helper `trimMargin("|")`.

* **Pros:** Trivial lexer/compiler implementation; WYSIWYG whitespace (never surprises users writing ASCII art or exact golden fixtures).
* **Cons:**
  * **Breaks multiline templates (`t"""..."""`)**: a template returns `template`, not `string`, so `.stripIndent()` cannot be called on it — and calling `.stripIndent()` *after* `.render()` is actively wrong whenever an interpolated value contains newlines (e.g., a multi-line user bio or nested SQL fragment)!
  * Breaks `babblefmt`: re-indenting a function body would either leave `"""..."""` awkwardly unaligned or silently change the runtime value of the string.

#### Option A3 — C++-style Custom Delimiter `r"tag(...)tag"` as the Primary Multiline Raw Form

Use `"""..."""` for escaped dedented blocks and `r"tag(...)tag"` (e.g., `r"(...)"`, `r"sql(...)sql"`) for raw multiline blocks:

```babble
def grammarSnippet = r"babble(
    def s = """nested triple quotes and \backslashes\ work verbatim"""
)babble"
```

* **Pros:** Visually echoes Babble's `()`-only design; 100% immune to any embedded quotes, backslashes, or parentheses; works cleanly on single lines (`r"(C:\temp\)"` — even trailing backslash works!).
* **Cons:** Less immediately recognizable than `r"""..."""` for Python/Kotlin users; custom tag matching requires a tiny state check in the lexer (matching the opening `tag` at `)tag"`, which ANTLR4 needs a lexer member/predicate for — fine in `@lexer::members`, though slightly trickier than fixed `"""`).

---

### 3.2 Axis B — Template Literal & Placeholder Syntax

Babble's hard syntactic invariant (`README.md`, `BIP012` §2, `Babble.g4` line 3) is **a single bracket type: `()` only** (no `{}`, `[]`, or `<>`). This eliminates `{expr}` as the idiomatic default and gives Babble two clean `()`-based designs, plus a comparison against `{expr}`.

#### Option B1 *(Recommended)* — `t"..."` / `t"""..."""` (and `rt"..."` / `rt"""..."""`) with `$(expr)` + Optional Multi-Dollar `t$$"""..."""`

```babble
;; Single-line template
def greeting = t"Hello, $(user.name)! Score: $(score * 100)%"

;; Multiline template (dedented to closing """)
def sql = t"""
    SELECT id, title
    FROM articles
    WHERE author_id = $(author.id)
      AND status = $(if published then ("live") else ("draft"))
    """

;; Nested templates work naturally because parentheses balance
def nested = t"Outer: $(t"Inner($(x + 1))")"

;; Raw template: backslashes are literal, $(...) still interpolates
def regexTpl = rt"^\s*$(prefix)_\d+\s*$"

;; Multi-dollar escalation (Kotlin 2.2 KEEP-380 style): single $ stays literal!
def jsonSchema = rt$$"""
    {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "title": "$$(schemaTitle)",
        "priceExample": "$100"
    }
    """
```

* **Why `$(expr)` only (no bare `$name` shorthand)?**
  * **100% unambiguous:** `$100`, `$schema`, `$PATH`, `$(x)px`, `foo$bar` never accidentally trigger interpolation or require awkward boundary rules (contrast Kotlin's `"$name_suffix"` vs `"${name}_suffix"` and `"$a.b"` stopping at `.`).
  * **100% single-bracket `()`:** Uses Babble's one and only bracket pair, matching `Formatter`'s single parenthesis stack.
  * **Easy escaping:** In escaped templates (`t"..."`, `t"""..."""`), `\$(` writes a literal `$(`, and bare `$` not followed by `(` is already a plain literal character! So `t"Price: $100 or $(amount) EUR"` needs **zero escaping** for `$100`!
  * **Seamless raw-template escalation:** Adding Kotlin 2.2-style multi-dollar prefixes (`t$$"""..."""`, `rt$$"""..."""`) means even text containing literal `$(...)` (like Makefiles, Bash scripts, or jQuery) requires zero escaping — only `$$(` triggers interpolation!
  * *(If desired, bare `$id` could still be reconsidered under `BIP026-Q4`, though `$(expr)`-only is noticeably cleaner and avoids accidental `$name` captures in SQL/regex/shell text.)*
* **Optional placeholder metadata / format hint (`BIP026-Q9`):**
  * Some policies benefit from per-placeholder hints (such as Python's `{x:.2f}` / `{x!r}` or a SQL/format specifier).
  * In Babble, `;` separates statements inside blocks, while `:` only appears inside `callParameters` (named arguments `f(a: 1)`) and declaration headers (`def x: T`), never directly after a top-level expression (`Babble.g4` lines 52–107). Therefore, an optional top-level `$(expr : spec)` remains strictly LL(1) and predicate-free if ever wanted.
  * For v1, however, omitting format-spec syntax keeps the grammar minimal: policies inspect each placeholder's real runtime type directly, or users pass a small wrapper record such as `$(fmt(x, ".2f"))` or `$(raw(sqlFragment))` (`BIP026-Q9`).

#### Option B2 — `t"..."` / `t"""..."""` with Swift-style `\(expr)`

```babble
def greeting = t"Hello, \(user.name)! Price: $\(price)"
def sql = t"""
    SELECT * FROM users WHERE id = \(userId)
    """
```

* **Pros:**
  * Preserves Babble's `()`-only rule with **zero new sigil characters** (`\` is already the escape trigger, just like Java's `\{expr}` adapted to Babble's `()`).
  * Every `$` is always literal (`$100`, `$schema`, `$(MAKE)` need zero escaping).
  * `\\(` naturally escapes a literal `\(`.
* **Cons:**
  * **Clashes with raw templates (`rt"..."`):** In a raw string, `\` is supposed to be a literal character (e.g., regex `\d`, `\s`, `\(` for a literal regex parenthesis!). Having `\(` trigger interpolation inside `rt"..."` breaks regexes like `rt"\((foo)\)"`; avoiding that forces a second delimiter mechanism like Swift's `rt#"..."#` + `\#(expr)`.
  * Backslash is harder to reach on several non-US keyboards (especially Apple AZERTY/QWERTZ keyboards — the exact ergonomics concern noted in Babble's `README.md`!).

#### Option B3 — `t"..."` / `t"""..."""` with Python/C++-style `{expr}` *(Evaluated & Not Recommended)*

```babble
def greeting = t"Hello, {user.name}!"
```

* **Pros:** Instantly familiar to Python PEP 750 and C++ `std::format` users; `$` and `\` stay literal.
* **Cons:**
  * **Directly violates Babble's defining single-bracket `()` rule** (`README.md`: *"no C-style brackets! Those are nasty to type on most keyboards, especially on Apple keyboards"*; `BIP012` §2).
  * Heavy escaping friction (`{{` / `}}`) in JSON, CSS, LaTeX, GraphQL, and C-family code generation — some of the primary use cases for multiline templates!

#### Option B4 *(Orthogonal)* — Template-Only (`t"..."`) vs. Dual Prefix (`t"..."` + Eager String `s"..."`)

Should Babble also offer a shorthand prefix `s"..."` / `s"""..."""` (or `f"..."`) that evaluates directly to `string`, defined semantically as sugar for `t"...".render()` (and compiled with zero `Template` allocation via `StringConcatFactory`)?

| Sub-option | Everyday concatenation | Safe SQL / HTML / Logging | Trade-off |
|---|---|---|---|
| **B4a: `t"..."` only** | `t"Hello $(name)".render()` or `string(t"Hello $(name)")` | `sql(conn, t"SELECT ... $(id)")` | Minimal surface (2 prefixes: `r`, `t`, plus `rt`); zero temptation to write unsafe `s"SELECT ..."` by habit; slightly more verbose for plain logging/UI strings. |
| **B4b: `t"..."` + `s"..."` (or `f"..."`)** | `s"Hello $(name)"` (type `string`, deforested) | `sql(conn, t"SELECT ... $(id)")` | Maximum ergonomics for everyday string building (like Python `f""` + `t""`), while keeping `t""` distinct and safe; adds a second prefix family (`s`, `rs`) and relies on API types (`fn (q: template)`) to prevent passing `s"..."` to SQL APIs. |

Note that under **both** B4a and B4b, a SQL API written as `def query = fn (conn: Connection, q: template): PreparedStatement` **statically rejects** a plain `string` (including `s"..."`) at compile time! Under B4b, `s"..."` is strictly a syntactic shorthand for `t"...".render()` with identical placeholder syntax and dedent rules. This is tracked as **`BIP026-Q5`**.

---

### 3.3 Axis C — Template Object & Memoized Callback Model

This is the core architectural contract requested for BIP026: **a template literal evaluates to a first-class template object composed of static string fragments and placeholders mapping to lazily memoized callbacks.**

#### 3.3.1 Core Data Model (`template` and `placeholder`)

Represented in `org.babblelang.runtime` (`java.base` only, zero front-end or third-party dependencies, `RuntimeBoundaryTestCase` clean) and typed in Babble as built-in core types (`template` and `placeholder`, or `Template` and `Placeholder` — see `BIP026-Q6`; shown lowercase below to match `string`, `list`, `entry`, `array` per `BIP011`/`BIP020`):

```babble
;; Conceptual public contract (declared via BIP019 axioms in org/babblelang/axioms/)

;; A single placeholder inside a template, backed by a lazily memoized callback.
;; Conceptually:
;;   p.value()       : any?     ;; forces the memoized callback on first call, returns cached result thereafter
;;   p.isEvaluated() : bool     ;; true once value() has been computed (does NOT force evaluation)
;;   p.expression()  : string   ;; compile-time source text of the placeholder expression, e.g. "user.id"
;;   p.format()      : string   ;; optional format hint ("" when omitted; only if BIP026-Q9 adopts specs)

;; A structured string template: N + 1 static literal fragments interleaved with N placeholders.
;; Conceptually:
;;   tmpl.strings()      : frozen list of string       ;; immutable list of length N + 1
;;   tmpl.placeholders() : frozen list of placeholder  ;; immutable list of length N
;;   tmpl.size()         : int                         ;; number of placeholders (N)
;;   tmpl.parts()        : java.lang.Iterable of (string | placeholder)  ;; alternating segments for `for` loops
;;   tmpl.render()       : string                      ;; shortcut for default concatenation policy
;;   tmpl.eval(policy)   : R                           ;; applies an evaluation policy (TemplatePolicy of R or fn)
;;   tmpl.materialize()  : template                    ;; forces all placeholders now and returns this template
;;   tmpl.plus(other)    : template                    ;; operator `+`: safe template + template composition
```

**Structural invariants:**
1. **`strings().size() == placeholders().size() + 1` always.**
   * `t""` → `strings = [""]`, `placeholders = []`.
   * `t"hello"` → `strings = ["hello"]`, `placeholders = []`.
   * `t"$(a)$(b)"` → `strings = ["", "", ""]`, `placeholders = [p_a, p_b]`.
   * `t"a=$(a)"` → `strings = ["a=", ""]`, `placeholders = [p_a]`.
2. **Static fragment sharing:** The `String[]` backing `strings()` is allocated **once per literal site** (as a `private static final String[]` in compiled bytecode, and cached in `ParseTreeProperty` in `Interpreter`), never re-allocated when a template literal executes in a loop.
   * *Bonus for SQL/HTML policies:* Two executions of the same template literal share the **exact same `strings()` array reference**, enabling $O(1)$ identity-keyed (`IdentityHashMap` / `ClassValue`-style) or equality-keyed caching of compiled SQL strings or parsed HTML skeletons!
3. **Iteration (`BIP013`):** Iterating `for part in tmpl.parts() then (...)` (or directly `for part in tmpl then (...)` if `template` implements `Iterable<Object>` typed as `Iterable of (string | placeholder)`) yields alternating non-empty `string` fragments and `placeholder` objects, letting any custom policy be written concisely with `is` narrowing:
   ```babble
   for part in tmpl.parts() then (
       if part is string then (
           out.append(part)
       ) else (
           out.append(escapeHtml(string(part.value())))
       )
   )
   ```
4. **Safe composition (`+`):**
   * `t1 + t2` (`Template.plus(Template)`) creates a new `template` merging `t1.strings()[last] + t2.strings()[0]` and concatenating `t1.placeholders()` + `t2.placeholders()` **without forcing any placeholder callbacks**.
   * **`template + string` and `string + template` are compile-time type errors** (mirroring Python PEP 750's `TypeError`). A dynamic string can never accidentally become a static fragment; to embed a dynamic string safely, write `t1 + t"$(dynamicStr)"` or `t"$(t1)$(dynamicStr)"` (if a policy supports nested templates!).
   * For genuinely trusted programmatic static fragments (e.g., table/column names verified against an allowlist), an explicit factory `template.literal(trustedText)` makes the trust boundary auditable in code review.

#### 3.3.2 Memoized Callback Semantics (Edge Cases & Guarantees)

Inside `org.babblelang.runtime.Placeholder`, each placeholder stores a zero-argument thunk (a `Supplier<Object>` / Babble `Callable` / specialized JVM lambda) and a state machine:

```
UNSET  ──(first value() call)──▶  EVALUATING  ──(normal return v)──▶  v (may be null)
                                      │
                                      └──(re-entrant value() call)──▶ throws IllegalStateException
                                      └──(throws Throwable ex)──▶ [Q7: cache failure or reset to UNSET]
```

* **`null` is memoized properly:** A private static sentinel `UNSET` (distinct from `null`) ensures that a placeholder evaluating to `null` executes its callback **once**, caches `null`, and returns `null` on all subsequent reads.
* **Cycle / re-entrancy detection:** Because Babble hoists `def` bindings to the start of a block, a pathological template like `def q = t"$(q.render())"` can reference itself. A private `EVALUATING` sentinel catches re-entrant evaluation immediately and throws a clean error (`Circular template evaluation`) instead of overflowing the stack.
* **Exception behavior (`BIP026-Q7`):**
  * *Choice 1 — Strict once-only (memoize exceptions):* If the callback throws an exception, store a failure box and rethrow it on subsequent `value()` calls. Guarantees that an impure/failing expression is never accidentally retried with duplicate side effects.
  * *Choice 2 — Retry on failure (reset to `UNSET`):* Matches standard lazy-init / `StableValue` semantics (and JDK 25+ `LazyConstant`, `BIP018`), allowing transient errors (e.g., retryable lookups) to recover without keeping a stale stack trace alive.
* **Concurrency & future JDK alignment (`BIP018`):**
  * On JDK 21, `Placeholder.value()` uses double-checked locking over a `volatile Object state` + `synchronized (this)` (zero contention once initialized, and virtual-thread-friendly on JDK 24+ via `JEP 491`).
  * On `Babble 25+` (`BIP018`), `Placeholder` can transparently back its memoization with JDK `StableValue` / `LazyConstant`, allowing the JIT to constant-fold memoized values!
* **Lazy vs. eager capture trade-off (explicitly acknowledged):**
  Because placeholders are lazy closures, mutating a captured local variable *between* template creation and first evaluation is observed by the placeholder:
  ```babble
  def x = 1
  def tmpl = t"x = $(x)"
  x = 2
  println(tmpl.render())    ;; prints "x = 2", NOT "x = 1"
  ```
  This is identical to `def f = fn () -> (x); x = 2; f()` — and it is the exact reason Python PEP 750 chose eager evaluation (§2.2). In Babble, this trade-off is much cleaner because:
  1. Unreassigned `def`s and `record` fields (the vast majority of Babble code under `BIP016` pure-by-default) produce the exact same value lazily or eagerly.
  2. Lazy memoized callbacks unlock major capabilities Python PEP 750 gave up: zero-cost skipped logging, pre-evaluation SQL structural validation & statement-template caching, and at-most-once evaluation across multi-pass policies.
  3. Callers who explicitly want snapshot-at-creation semantics before mutating a loop variable can call `tmpl.materialize()` (which evaluates and caches every placeholder immediately and returns `tmpl`).

---

### 3.4 Axis D — The Plug-in Evaluation Policy Model

How are evaluation policies defined, composed, and invoked on a `template`?

#### Option D1 *(Recommended)* — Effect-Polymorphic Function Policies + Optional `TemplatePolicy of R` Interface (`tmpl.eval(policy)` & Plain Calls)

Following Brian Goetz's 2024 insight (§2.3) and Python PEP 750 (§2.2), **template capture (`t"..."`) and template evaluation (`policy`) are completely decoupled**, with **no special processor call syntax**:

```babble
;; On `template` — effect-polymorphic via BIP017 `calling`!
;; Pure policy -> pure eval(); io policy -> io eval(); bound mut method -> forwarded mut!
def eval of R = fn calling policy (policy: fn (tmpl: template): R): R -> (policy(this))

;; Optional named interface for OO/Java-interop policies (pure or io):
def TemplatePolicy of R = interface (
    def evaluate: fn io (tmpl: template): R
)
```

Notice how cleanly **`BIP017` (`fn calling policy`)** solves the pure-vs-`io` policy tension in Babble:
1. **A function policy** `fn (tmpl: template): R` (or a bound method `sqlPolicy.evaluate`, or a factory `sql(conn)` returning `fn io (tmpl: template): PreparedStatement`) passed to `tmpl.eval(policy)` forwards its exact effects to the caller via `BIP017`! Thus `tmpl.eval(html)` is **pure**, while `tmpl.eval(sqlPolicy.evaluate)` is **`io`** — through one single `eval` method, with zero interface duplication!
2. **A configured class or object** (such as `SqlPolicy(conn)` or `HtmlPolicy(strict: true)`) can either expose a bound `evaluate` method (`q.eval(SqlPolicy(conn).evaluate)`), expose a factory function (`q.eval(sql(conn))`), or implement `TemplatePolicy of R` (for Java interop via `RM003`, where `evaluate` carries `fn io` so both pure and `io` implementations conform). Using `BIP021` overload sets, `tmpl.eval` can also accept `TemplatePolicy of R` directly alongside `fn (template): R`.
3. **Plain function calls** (`html(t"...")`, `sql(conn, t"...")`) work directly with zero wrapper boilerplate.

**Built-in default policy — Concatenation:**
* `tmpl.render(): string` (and `template.concat(tmpl)` / `tmpl.eval()` with default policy) evaluates each placeholder left-to-right via `p.value()`, formats each value using Babble's existing `ArraySupport.format(v)` (identical to `"..." + v` and `string(v)`: arrays render as `[1, 2, 3]`, `null` as `"null"`), and joins them with `StringBuilder`.

**Concrete end-to-end examples under Option D1:**

*Example 1 — SQL `PreparedStatement` Policy (with fragment validation, typed parameter binding, and audit logging):*

```babble
def SqlPolicy = class (private def conn: java.sql.Connection) implements TemplatePolicy of java.sql.PreparedStatement (
    def evaluate = fn io (tmpl: template): java.sql.PreparedStatement -> (
        ;; Pass 1: inspect & assemble static SQL BEFORE evaluating any placeholder!
        def sql = java.lang.StringBuilder()
        def frags = tmpl.strings()
        def i = 0
        while i < frags.size() then (
            if i > 0 then ( sql.append("?") )
            sql.append(frags.get(i))
            i += 1
        )
        ;; Prepare statement (could also cache sql.toString() keyed on frags!)
        def stmt = this.conn.prepareStatement(sql.toString())!

        ;; Pass 2: lazily force & bind each placeholder with its real static/runtime type
        def idx = 1
        for p in tmpl.placeholders() then (
            def v = p.value()
            if v == null then (
                stmt.setObject(idx, null)
            ) else if v is int then (
                stmt.setInt(idx, v)
            ) else if v is long then (
                stmt.setLong(idx, v)
            ) else if v is double then (
                stmt.setDouble(idx, v)
            ) else if v is bool then (
                stmt.setBoolean(idx, v)
            ) else if v is string then (
                stmt.setString(idx, v)
            ) else (
                stmt.setObject(idx, v)
            )
            idx += 1
        )
        stmt
    )
)

;; Usage — completely immune to SQL injection!
def userInput = "admin' OR '1'='1"
def q = t"""
    SELECT id, role
    FROM accounts
    WHERE username = $(userInput)
      AND active = $(true)
    """
def stmt = q.eval(SqlPolicy(conn).evaluate)    ;; or SqlPolicy(conn).evaluate(q)
```

Notice what memoization adds here: if a debugging wrapper logs the bound query after `SqlPolicy` runs:
```babble
def logQuery = fn io (q: template) -> (
    for p in q.placeholders() then (
        println("  param ", p.expression(), " = ", p.value())  ;; reads cached value — ZERO re-execution!
    )
)
```
Each placeholder expression runs **at most once**, even across multiple passes or policies!

*Example 2 — Guarded Lazy Logger Policy:*

```babble
def Logger = class (private def debugEnabled: bool) (
    def debug = fn io (msg: template) -> (
        if this.debugEnabled then (
            println("[DEBUG] ", msg.render())
        )
        ;; When debugEnabled is false, no placeholder callback is ever invoked!
    )
)
```

*Example 3 — HTML Auto-Escaping Policy (with `SafeHtml` opt-out):*

```babble
def SafeHtml = record (def html: string) ()

def escapeHtml = fn (s: string): string -> (
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
)

def html = fn (tmpl: template): SafeHtml -> (
    def out = java.lang.StringBuilder()
    for part in tmpl.parts() then (
        if part is string then (
            out.append(part)
        ) else (
            def v = part.value()
            if v is SafeHtml then (
                out.append(v.html)
            ) else (
                out.append(escapeHtml(string(v)))
            )
        )
    )
    SafeHtml(out.toString())
)

def page = html(t"""
    <h1>$(userTitle)</h1>
    <section>$(trustedBody)</section>
    """)
```

#### Option D2 — Streaming / Visitor Policy Interface (`onText` / `onPlaceholder` / `finish`)

Instead of passing the whole `template` to `evaluate(tmpl)`, a policy implements callbacks driven by `template.eval(policy)`:

```babble
def TemplatePolicy of R = interface (
    def onText: fn mut (text: string)
    def onPlaceholder: fn mut (p: placeholder)
    def finish: fn (): R
)
```

* **Pros:** Concise for single-pass streaming builders (concatenation, HTML escaping, JSON writers); eliminates manual iteration boilerplate.
* **Cons:**
  * Forces **mutable per-call policy instances** (`mut` methods), preventing stateless/reusable policy singletons like `HtmlPolicy` or `ConcatPolicy` unless wrapped in an extra factory layer (`TemplatePolicyFactory`).
  * Poor fit for **two-pass or caching policies** (like SQL `PreparedStatement` caching or guarded logging that decides *before* scanning whether to run at all).
* *Hybrid note:* Under **Option D1**, a small utility function `template.fold` (or `for part in tmpl.parts()`) gives single-pass policies the exact same conciseness without giving up immutable policies or two-pass inspection!

#### Option D3 — Tagged / Prefixed Policy Application (`policy t"..."` or `sql"..."`)

Add syntactic sugar allowing a policy expression directly before a template literal (with no intervening `NL`), desugaring `policy t"..."` to `policy(t"...")` (or `t"...".eval(policy)`):

```babble
def page = html t"<h1>$(title)</h1>"
def stmt = SqlPolicy(conn) t"SELECT * FROM users WHERE id = $(id)"
```

* **Grammar feasibility:** Because Babble requires `;` or `NL` between two expressions on the same line, `expression templateLiteral` (with no `NL*` allowed before `templateLiteral`, matching `# call`'s no-`NL` rule before `(`) is actually **LL(1)** and predicate-free!
* **Pros:** Concise DSL feel reminiscent of JavaScript tagged templates (`html\`...\``) and Scala interpolators (`sql"..."`).
* **Cons:**
  * Repeats half of Java JEP 459's pitfall: introduces a second syntax for calling a function (`html t"..."` vs. `html(t"...")`), saving only **two parentheses** in a language whose entire aesthetic is built around parentheses `()`!
  * Ambiguous for readers when mixed with operators (`a + html t"..."` — what is the precedence of juxtaposition?) and doesn't help multi-argument APIs (`query(conn, t"...")`).
* **Recommendation:** Keep ordinary calls `html(t"...")` and `q.eval(policy)` (Option D1); do **not** add juxtaposition policy calls unless real-world usage shows strong demand.

---

## 4. Deep Integration with Babble's Type, Effect, and Ownership Systems

### 4.1 Type System (`BIP008`, `BIP011`, `BIP020`, `BIP022`, `BIP023`)

* **Placeholder typing:** Every embedded expression `$(expr)` is type-checked in the exact enclosing lexical environment (`TypeEnv`, `Narrowing`), with expected type `any?` (accepting any value type — primitives, `null`, references, unions, intersections, records, Java objects — and rejecting `void` with `Type error : template placeholder has type void`).
* **Narrowing inside templates:** Flow-sensitive narrowing (`if user != null then (t"hi $(user.name)")`) works automatically because `$(...)` is an ordinary sub-expression in the same block!
  * *Subtlety with lazy callbacks:* If a nullable variable `x: Box?` is narrowed to `Box` inside `if x != null then (...)` and later reassigned `x = null` inside the same branch *after* creating `def tmpl = t"$(x.label)"`, reading `x` lazily would observe `null` — the **exact same soundness question that already applies to ordinary closures** (`fn () -> (x.label)`), which `Narrowing` handles uniformly (reassignment invalidates narrowing for captured mutable locals, or `TypeChecker` checks captured locals against their declared slot type). Template placeholders inherit the exact same closure-narrowing rule with zero special cases!
* **Core types & aliases:** `template` and `placeholder` are non-null core types (`template?` for nullable), usable in `is`, `as`, generics (`list of template`), and `BIP023` type aliases (`def SqlQuery = type template`).
* **No implicit coercion to `string`:** `template` is **not** a subtype of `string`, and `template + string` / `string + template` is rejected statically (`Type error : cannot concatenate template with string`). Only `template + template` is valid via `Template.plus`.

### 4.2 Effects, Purity, and Closure Provenance (`BIP007`, `BIP016`, `BIP017`)

Because placeholders are **lazily evaluated callbacks**, templates interact deeply with Babble's pure-by-default effect system (`BIP016`) and callback-effect forwarding (`BIP017`). Ignoring this interaction would either break purity soundness (allowing an `io` callback to hide inside a `template` and execute inside a pure function!) or make every `tmpl.render()` call require `fn io` (unacceptable in pure-by-default Babble).

There are **three concrete design options** for template effects (`BIP026-Q8`):

#### Effect Model E1 *(Recommended)* — Pure-by-Default `template` + `io` / `volatile` Effect-Qualified Templates (Mirroring `fn` in `BIP016` & `BIP017`)

Treat a `template` like a zero-argument closure bundle:

1. **Unqualified `template` means pure placeholders (`{@HERMETIC, @DETERMINISTIC}`):**
   * Creating `t"Hello $(name)"` when all placeholder expressions are pure produces a pure `template`.
   * Calling `tmpl.render()` or `p.value()` on a pure `template` is **pure** (hermetic & deterministic) — so pure functions can build, pass, and render templates with zero effect annotations!
   * Even better: for a pure `template`, memoization is **observationally transparent** (evaluating lazily vs. eagerly produces the same value, modulo termination/exceptions).
2. **Templates with `volatile` or `io` placeholders carry that effect (`volatile template` / `io template`, or `template` effect trait):**
   * `t"now = $(java.lang.System.currentTimeMillis())"` has inferred effect `volatile` (type `volatile template` or `io template` — subtyping `template <: volatile template <: io template`, matching `fn () <: fn volatile () <: fn io ()`!).
   * Calling `p.value()` or `.render()` on an `io template` requires an `io` caller (and on a `volatile template` requires `volatile` or `io`).
   * **Policy polymorphism via `BIP017` (`calling`):** A policy function can forward the template's effects using Babble's existing `calling` concept (or `fn calling tmpl (tmpl: template): R`), so **one** `html` or `render` policy works seamlessly on pure templates inside pure functions *and* on `io` templates inside `io` functions!
3. **Closure Provenance (`BIP017-D6`) & Borrowed Parameters:**
   * A template literal aliases the parameters and `this` its placeholders capture — **exactly like a closure under `BIP017-D6`**.
   * Therefore `def greet = fn (user: User): string -> (t"Hello $(user.name)".render())` requires **zero `shared` annotations** on `user`!
   * Only if `tmpl` escapes into a field or collection does escape analysis report `Borrowed parameter user escapes via ... : mark user as shared`.
4. **Ownership (`unique` / `move`) & Frame-Local Mutation:**
   * Moving a `unique` binding inside a lazy placeholder (`t"$(consume(move(x)))"`) is rejected (`Cannot move unique variable inside lazy template placeholder : x`), because a lazy placeholder may be evaluated zero times (leaking or breaking definite-unassignment assumptions) — the user evaluates or moves before the template if needed.
   * Capturing a frame-local mutable `def` (`t"$(n += 1)"`) follows standard closure rules (`frameWriteOwners` / `IntCell` capture), requiring the enclosing context to allow the mutation.

#### Effect Model E2 — Pure-Only Templates (All Placeholders Must Be Pure)

Require every `$(expr)` inside a template literal to be **pure** (`{@HERMETIC, @DETERMINISTIC}`). Impure or volatile values must be bound to a local variable *before* the template:

```babble
def now = java.lang.System.currentTimeMillis()    ;; volatile call happens eagerly here
def tmpl = t"Timestamp: $(now)"                   ;; template itself is always 100% pure!
```

* **Pros:**
  * Dramatically simpler type system — **only one `template` type**, no `io template` qualifier, no effect subtyping on templates, no `calling tmpl` extension!
  * Eliminates the worst lazy-side-effect footguns (e.g., surprising evaluation order of `io` side effects across placeholders, or side effects silently skipped by a policy).
  * `p.value()` and `tmpl.render()` are **always pure**.
* **Cons:**
  * Prevents lazy `io` / `volatile` expressions in guarded logging (`log.debug(t"disk: $(readDiskStats())")` would have to be written `if log.isDebug() then (...)` or `readDiskStats()` eagerly). *(Note: pure expensive computations like `log.debug(t"ray stats: $(summarize(scene))")` STILL evaluate lazily!)*

#### Effect Model E3 — Unchecked / Always-`io` `Placeholder.value()` *(Not Recommended)*

Treat `Placeholder.value()` as `fn io (): any?` unconditionally so templates can hold arbitrary `io` expressions without tracking effects in the type.

* **Why not:** Forces every pure function that merely formats a string (`t"Point($(this.x), $(this.y))".render()`) to be marked `fn io`, directly contradicting `BIP016`'s pure-by-default design!

*Summary:* **E1** is the most expressive and aligns naturally with `BIP017`; **E2** is remarkably clean and minimal if pure-only templates cover 99% of use cases (and can even serve as the Phase 3 baseline that E1 extends cleanly without breaking existing code!). Tracked in **`BIP026-Q8`**.

### 4.3 Control Flow Inside Placeholders

Just as default parameter expressions (`BIP001-D3`) and closures forbid non-local control transfers, `return`, `break`, and `continue` inside `$(...)` cannot target an enclosing function or loop outside the placeholder (only a `fn` / `while` / `for` *nested inside* `$(...)` may use them). `BlockScopes` and `Resolver` reject escaping `return`/`break`/`continue` statically with the existing diagnostics (`Return outside of a function`, `Break outside of a loop`, `Continue outside of a loop`).

---

## 5. Grammar, Lexing, Formatting & Interactive Mode

### 5.1 Grammar & ANTLR4 Split-Grammar Requirement (`BIP012` / `BIP015`)

[`ParserGrammarTestCase`](../../src/test/java/org/babblelang/tests/ParserGrammarTestCase.java) enforces three strict parser invariants from `BIP012`:
1. **Zero `@parser::members`** in the parser grammar.
2. **Zero semantic predicates (`}?`)** in the parser grammar.
3. **100% pure `PredictionMode.SLL`** parsing across all `.ba` and `.batest` files.

Supporting arbitrary Babble expressions (including nested string literals, parenthesized calls, blocks, and nested templates) inside `$(...)` requires **lexer modes** (`pushMode` / `popMode`) so the lexer switches from template-text scanning into normal Babble tokenizing on `$(` and switches back on the matching `)`.

**Important ANTLR4 architectural constraint:** ANTLR4 only allows `mode` declarations inside a **split grammar** (`lexer grammar BabbleLexer;` in `BabbleLexer.g4` + `parser grammar BabbleParser;` in `BabbleParser.g4`), **not** inside a combined `grammar Babble;`.

Splitting `Babble.g4` into `BabbleLexer.g4` + `BabbleParser.g4` is clean and mechanical:
* **Parser (`BabbleParser.g4`)** stays **100% predicate-free, zero `@parser::members`, strictly LL(1) / pure `SLL`**:
  ```antlr
  expression: ...
            | STRING                  # string
            | MULTILINE_STRING        # multilineString
            | RAW_STRING              # rawString
            | RAW_MULTILINE_STRING    # rawMultilineString
            | templateLiteral         # templateExpression
            ;

  templateLiteral: TEMPLATE_OPEN templatePart* TEMPLATE_CLOSE;
  templatePart: TEMPLATE_TEXT
              | TEMPLATE_EXPR_OPEN NL* expression NL* TEMPLATE_EXPR_CLOSE
              ;
  ```
* **Lexer (`BabbleLexer.g4`)** maintains a small integer stack (`ArrayDeque<Integer> parenDepths` in `@lexer::members`) to track nested `(` / `)` inside each active `$(...)` interpolation, plus mode transitions:
  * In `DEFAULT_MODE`:
    * `LPAREN`: `'('` — if `!parenDepths.isEmpty()`, increments top depth.
    * `RPAREN`: `')'` — if `!parenDepths.isEmpty()`, decrements top depth; when it reaches `0`, emits `TEMPLATE_EXPR_CLOSE` and executes `popMode()` (returning to the enclosing template mode!).
    * `TEMPLATE_OPEN`: `'t"'` / `'t"""'` / `'rt"'` / `'rt"""'` (and optional `t$$"""` / `rt$$"""` with dollar-count recorded on a parallel stack) — pushes `0` and `pushMode(TEMPLATE_SINGLE | TEMPLATE_MULTI | RAW_TEMPLATE_SINGLE | RAW_TEMPLATE_MULTI)`.
  * In each `TEMPLATE_*` mode:
    * Matching closing delimiter (`"` or `"""`) emits `TEMPLATE_CLOSE` and `popMode()`.
    * Matching trigger (`$(` or `$`×*k*`(`) pushes `1` onto `parenDepths`, emits `TEMPLATE_EXPR_OPEN`, and `pushMode(DEFAULT_MODE)`.
    * Literal text (and escapes in non-raw modes) emits `TEMPLATE_TEXT`.
* **No reserved keywords needed for prefixes (`r`, `t`, `rt`):** Under ANTLR4 longest-match lexing, `t"` matches `TEMPLATE_OPEN` (2 chars) whereas `t`, `t(1)`, `t.x`, `def t = 1` match `ID` (1 char). Existing variables named `r`, `t`, or `rt` remain 100% valid!
* **New type keywords (`template`, `placeholder`):** If exposed as lowercase core type keywords (`BIP026-Q6`), they join `coreTypeKeyword` and `memberName` (just like `array` in `BIP020` and `type` in `BIP023`), keeping `.template` and `.placeholder` valid after `.` / `?.`.
* **Synergy with `BIP015` (*Down with the ants*):** If Babble later adopts a hand-written lexer/parser (`BIP015` Option 6), this exact 5-token protocol (`TEMPLATE_OPEN`, `TEMPLATE_TEXT`, `TEMPLATE_EXPR_OPEN`, `TEMPLATE_EXPR_CLOSE`, `TEMPLATE_CLOSE`) translates directly to ~40 lines of pure Java with zero parser changes.
* *(Note on `ParserGrammarTestCase`: update the file path assertion to verify zero `@parser::members` and zero `}?` in `BabbleParser.g4`.)*

### 5.2 Exact Compile-Time Dedenting Rules

Applied identically to `"""..."""`, `r"""..."""`, `t"""..."""`, and `rt"""..."""` (multiline forms):

1. **Line-ending normalization:** Replace every `\r\n` and standalone `\r` in literal fragments with `\n`.
2. **Opening line:** After the opening delimiter (`"""`, `r"""`, `t"""`, `rt"""`), optional horizontal whitespace (`[ \t]*`) followed by `\n` is consumed and not part of the content.
3. **Common indentation calculation (across logical lines of the block):**
   * Split the template's raw text + placeholder markers into logical lines at `\n` inside static fragments (newlines *inside* `$(...)` expression code do not count as template lines).
   * A line is **blank** iff it contains only horizontal whitespace (`[ \t]*`) and **no** placeholder markers (`$(...)`).
   * The closing `"""` line counts as a non-blank anchor line (its leading horizontal whitespace participates in the minimum, exactly as in Java JEP 378!).
   * Compute `minIndent` = minimum leading whitespace prefix across all non-blank lines and the closing `"""` line.
   * **Tabs vs. spaces:** Count each space and tab as one code unit (matching `String.stripIndent()`), or reject mixed leading tabs/spaces inside a multiline literal with a clear lexical error (`Mixed tabs and spaces in multiline string indentation`) so layout is never ambiguous across editor tab widths (`BIP026-Q2`).
4. **Stripping:**
   * Strip `minIndent` leading whitespace characters from every non-blank line; replace blank lines with `""`.
   * Strip trailing horizontal whitespace (`[ \t]+`) from every line (stopping at `\s` in escaped blocks; **not** stripped in raw blocks if raw preserves trailing spaces, or stripped uniformly — settled in `BIP026-Q2`).
   * Drop the closing delimiter line's own trailing `\n` (the newline immediately before the closing `"""` is kept if the closing `"""` is on its own line, matching Java JEP 378; placing `"""` at the end of the last content line omits the trailing newline).
5. **Escape translation (escaped forms `"""..."""` and `t"""..."""` only):**
   * Translate `\b \t \n \f \r \" \' \\ \0 \uXXXX`, `\s` → `' '`, `\<newline>` → `""`, and `\$(` → `$(` (in templates).
   * Raw forms (`r"""..."""`, `rt"""..."""`) skip escape translation completely.
6. **Runtime interpolated newlines:** Newlines produced at runtime by a placeholder `$(multilineVal)` are **never** touched by compile-time dedenting — keeping static layout predictable and injection-safe.

### 5.3 Formatter (`babblefmt` / `Formatter.java`)

[`Formatter.java`](../../src/main/java/org/babblelang/format/Formatter.java) formats Babble code token-by-token, tracks indentation with a single parenthesis stack, shifts `MULTILINECOMMENT` tokens via `Formatter.moved()`, and verifies `shape(parse(formatted)).equals(shape(parse(source)))`.

With compile-time closing-delimiter dedenting (Option A1) and split lexer modes (§5.1):
1. **Multiline string literals (`"""..."""`, `r"""..."""`):** When the enclosing block's indentation shifts by $\Delta$ spaces, `Formatter` shifts every subsequent line of the multiline literal by $\Delta$ (clamped at 0, or aligning the closing `"""` to the line's indent + 1 level, just like `Formatter.moved()`). Because compile-time dedenting is relative to the closing `"""`, **the runtime string value is 100% preserved!**
2. **Template literals (`t"..."`, `t"""..."""`):**
   * `TEMPLATE_OPEN`, `TEMPLATE_TEXT`, `TEMPLATE_EXPR_OPEN`, `TEMPLATE_EXPR_CLOSE`, and `TEMPLATE_CLOSE` emit **zero injected spaces** at template boundaries (`space()` returns `false` adjacent to template delimiters/text).
   * Inside `TEMPLATE_EXPR_OPEN`..`TEMPLATE_EXPR_CLOSE`, the embedded expression consists of ordinary Babble tokens, so `babblefmt` automatically formats `t"$(a+b)"` into `t"$(a + b)"`!
   * For multiline `t"""..."""`, `TEMPLATE_TEXT` lines shift identically to `"""..."""`.
3. **Equivalence check (`Formatter.check()`):** `shape()` normalizes multiline string / `TEMPLATE_TEXT` tokens using their **dedented semantic text** (rather than raw indented source characters), so re-indenting a multiline block passes `babblefmt`'s invariant check while still catching any accidental change to actual string content!

### 5.4 Interactive Mode (`BabbleSession.classify`)

[`BabbleSession.classify`](../../src/main/java/org/babblelang/engine/BabbleSession.java) detects incomplete multiline REPL inputs via `ParseFailure.atEnd()` and `hasTokens()`.
* An unfinished multiline string (`def s = """\n    hello`) or unfinished template (`def q = t"hello $(x +`) hits EOF in the lexer/parser (`atEnd() == true`, `hasTokens() == true`), returning `Input.INCOMPLETE` automatically — so the JLine interactive prompt (`BIP004` §9) continues reading lines until the closing delimiter arrives.
* Tightening plain `"..."` to single-line (`BIP026-Q1`) also ensures an accidentally unclosed `"hello` at the REPL reports a crisp lexical error on line 1 instead of hanging in `INCOMPLETE` forever!

---

## 6. Execution, Runtime Boundary & Compiler Optimizations

### 6.1 Runtime Classes (`org.babblelang.runtime`, `java.base` only)

All runtime support lives in `org.babblelang.runtime`, keeping `RuntimeBoundaryTestCase` (`BIP006` §3) 100% green (`java.base` only, zero ANTLR/ASM/third-party classes):

| Runtime Class | Role |
|---|---|
| `org.babblelang.runtime.Template` | Immutable template object holding `private final String[] fragments` (`N + 1`) and `private final Placeholder[] placeholders` (`N`). Provides `strings()`, `placeholders()`, `size()`, `parts()`, `render()`, `eval(Object policy)`, `materialize()`, `plus(Template)`, and static `Template.literal(String)`. |
| `org.babblelang.runtime.Placeholder` | Wraps a lazy thunk (`Supplier<Object>` or `org.babblelang.runtime.Callable`), `private final String expression`, optional `private final String format`, and thread-safe memoization (`UNSET` / `EVALUATING` / cached value). Provides `value()`, `isEvaluated()`, `expression()`. |
| `org.babblelang.runtime.TemplateSupport` | Static helpers for default concatenation (`concat(Template)` using `ArraySupport.format` for `BIP020` array parity), `Template.plus` boundary merging, and `eval` dispatch (accepting either a `TemplatePolicy` implementation or a Babble `fn`). |

* **Axioms (`BIP019`):** All signatures, nullability, immutability, and effect contracts for `Template`, `Placeholder`, and `TemplatePolicy` live in `src/main/java/org/babblelang/axioms/` — never hardcoded in `TypeChecker`, `EffectChecker`, or `HirPurity`.

### 6.2 Dual-Mode Lowering (`Interpreter` & `HIR` Compiler)

Both execution modes produce identical `Template` / `Placeholder` runtime behavior:

* **Interpreter (`Interpreter.java`):**
  * Static fragments `String[]` and source expression strings `String[]` are precomputed once per `TemplateLiteralContext` and cached in `ParseTreeProperty`.
  * Visiting a `TemplateLiteralContext` creates a `Placeholder` per slot wrapping a zero-arg closure over the current `Namespace` (same lexical capture semantics as `Function`), and constructs `new Template(fragments, placeholders)`.
* **Compiler (`BIP014` ANTLR-free `HIR`):**
  * Add `HirExpr.TemplateLiteral(List<String> fragments, List<Slot> slots, ExprMeta meta)` to [`HirExpr.java`](../../src/main/java/org/babblelang/ir/hir/HirExpr.java), where each `Slot(HirFunction thunk, String expressionText, String formatSpec)` reuses `HirFunction` for closure capture and bytecode emission!
  * `HirEmitter` stores the literal's `String[]` fragments once in a `private static final String[]` field on the enclosing class (`<clinit>`), instantiates each slot's thunk (reusing Babble's existing `$spec` / primitive closure cells `IntCell`/`DoubleCell`/`BoolCell` from `RM001`/`RM005`), wraps them in `Placeholder`s, and invokes `new Template(STATIC_FRAGS, placeholders)`.
  * `HirVerifier` and `HirPrinter` handle `HirExpr.TemplateLiteral` exhaustively via Java 21 sealed-interface pattern matching.

### 6.3 `-O1` Compiler Optimizations (`BIP014` Nanopass Pipeline)

Because `HirExpr.TemplateLiteral` is first-class in `HIR`, the `-O1` optimizer unlocks two high-value passes without breaking dual-mode semantics:

1. **Constant Template Folding (`ConstFoldPass`):**
   * A zero-placeholder template (`t"SELECT 1"`) compiles to a single `private static final Template` constant (zero allocation per evaluation).
   * `t"hello".render()` folds directly to `HirExpr.Const("hello")`.
2. **Direct Concatenation Deforestation (`TemplateDeforestPass` in `-O1`):**
   * When a template literal is immediately consumed by `.render()` (or the `s"..."` shorthand if `BIP026-Q5` adopts it) without escaping — e.g., `t"Hello $(name), age $(age)".render()` — the optimizer eliminates the `Template`, `Placeholder[]`, and closure allocations completely!
   * Instead, `HirEmitter` lowers the expression directly to `invokedynamic` **`java.lang.invoke.StringConcatFactory.makeConcatWithConstants`** (already in `java.base` since JDK 9!) passing unboxed `int`/`long`/`double`/`bool`/`char` primitives directly (`RM001`/`RM005`), falling back to `ArraySupport.format` only for `array`/`any?` operands whose static type may hold an array.
   * Result: **zero intermediate `Template` allocation, zero closure allocation, zero primitive boxing**, matching Java's `StringConcatFactory` performance while preserving 100% observable parity with the interpreter (left-to-right single evaluation of every placeholder)!

---

## 7. Diagnostics

All messages follow Babble's `Message : detail` convention (space before colon) and match across interpreted and compiled modes:

| Situation | Stage | Message |
|---|---|---|
| Unclosed `"..."` hitting newline (if `BIP026-Q1` tightens `"..."`) | Lexer | `Lexical error : unterminated string literal` |
| Unclosed `"""...` or `t"...` at EOF | Lexer | `Lexical error : unterminated multiline string` / `unterminated template literal` |
| Missing newline after opening `"""` (if required) | Lexer | `Lexical error : multiline string opening """ must be followed by a newline` |
| Invalid escape sequence in `"..."` / `"""..."""` / `t"..."` | Lexer | `Lexical error : unknown escape sequence : \q` |
| Mixed tabs and spaces in multiline indentation (if disallowed) | Lexer | `Lexical error : mixed tabs and spaces in multiline string indentation` |
| `template + string` or `string + template` | `TypeChecker` | `Type error : cannot concatenate template with string` |
| `void`-typed placeholder expression `t"$(println(1))"` | `TypeChecker` | `Type error : template placeholder has type void` |
| Escaping `return`/`break`/`continue` inside `$(...)` | `BlockScopes`/`Resolver` | `Return outside of a function` / `Break outside of a loop` / `Continue outside of a loop` |
| `move(x)` inside a lazy template placeholder | `EffectChecker` | `Cannot move unique variable inside lazy template placeholder : x` |
| Evaluating an `io`/`volatile` template in a pure function (Model E1) | `EffectChecker` | `Pure function cannot evaluate io template` / `Pure function cannot evaluate volatile template` |
| Re-entrant self-evaluating template `def q = t"$(q.render())"` | Runtime | `Circular template evaluation` |

---

## 8. Open Questions & Recommended Babble Package

### 8.1 Open Questions (`BIP026-Q1`–`Q10`)

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **`BIP026-Q1`** | Should plain `"..."` become strictly single-line once `"""..."""` exists? | (a) Single-line only (`~[\\"\r\n]`); (b) Keep allowing raw newlines in `"..."` | **(a) Single-line only** (after auditing existing `.ba`/`.batest` files for any multiline `"..."` literals). Dramatically improves syntax error locality and REPL `classify`. |
| **`BIP026-Q2`** | Multiline `"""..."""` opening line & dedenting rules | (a) Mandatory newline after `"""`, JEP 378 closing-`"""` dedent, `\s` + `\<newline>`, reject mixed tabs/spaces; (b) Allow single-line `"""a "b" c"""` verbatim + multiline dedent; (c) Kotlin-style verbatim + `.stripIndent()` | **(a)** (or **(b)** if one-line quote-heavy literals are desired; both keep compile-time closing-`"""` dedenting so `t"""..."""` and `babblefmt` compose cleanly). |
| **`BIP026-Q3`** | Raw string & delimiter-collision escape hatch | (a) `r"..."` + `r"""..."""` only; (b) Add variable quote count `r""""...""""` (C# 11); (c) Add C++-style `r"tag(...)tag"` | **(a)** in `P1`, with **(b)** or **(c)** as an optional clean extension (`r"tag(...)tag"` fits Babble's `()`-only identity especially well). |
| **`BIP026-Q4`** | Template placeholder delimiter | (a) `$(expr)` only + multi-dollar `t$$"""..."""` (KEEP-380); (b) `$(expr)` + bare `$id`; (c) Swift-style `\(expr)`; (d) `{expr}` | **(a) `$(expr)` only + multi-dollar `t$$"""..."""`**. Preserves Babble's `()`-only rule, avoids `$100`/`$foo.bar` ambiguity, and composes cleanly with raw `rt"..."` templates. |
| **`BIP026-Q5`** | Template prefix only (`t"..."`) vs. also eager string shorthand (`s"..."` / `f"..."`)? | (a) `t"..."` only (`.render()` for string); (b) `t"..."` (returns `template`) + `s"..."` (sugar for `t"...".render()`, deforested to `string`) | **(a)** initially for a minimal, safe-by-default surface — or **(b)** if everyday string interpolation ergonomics are a top priority (since SQL/HTML APIs taking `template` still reject `s"..."` statically!). |
| **`BIP026-Q6`** | Type naming & `Template.toString()` behavior | Naming: (a) lowercase core keywords `template` / `placeholder` (exempted in `memberName`, like `array`/`entry`) vs. (b) PascalCase `Template` / `Placeholder`. `toString()`: (x) non-evaluating debug summary `Template(...)` vs. (y) forces `.render()` | **(a) `template` / `placeholder`** for consistency with `string`/`list`/`entry`/`array`, and **(x) non-evaluating `toString()`** so debugger/logging/Java `String.valueOf` never silently triggers side effects or flattens a safe template! |
| **`BIP026-Q7`** | Exception memoization in `Placeholder.value()` | (a) Strict once-only (memoize thrown exception and rethrow); (b) Reset to `UNSET` on throw (retryable, matching `LazyConstant`) | **(a) Strict once-only** for `Placeholder` so policies never accidentally re-run a failing callback twice — worth confirming if retry semantics are preferred. |
| **`BIP026-Q8`** | Effect tracking for lazy template placeholders (`BIP016`/`BIP017`) | (a) **Model E1:** pure-by-default `template`, with `volatile template` / `io template` and `calling` forwarding; (b) **Model E2:** pure-only placeholders (impure values bound to a `def` beforehand) | **Start with E2 in `P3` or implement E1 directly in `P4`** — **E1** is the most complete long-term fit because it enables lazy `io` logging without sacrificing pure-by-default templates. |
| **`BIP026-Q9`** | Optional per-placeholder format spec metadata | (a) None in v1 (policies inspect typed values or helper records); (b) Optional top-level `$(expr : spec)` captured as `p.format()` | **(a) None in v1** — keeps grammar and `Placeholder` minimal; `$(expr : spec)` can be added backward-compatibly later since top-level `:` cannot follow an expression. |
| **`BIP026-Q10`** | Policy invocation API | (a) `TemplatePolicy of R` interface + `fn (template): R` + `tmpl.eval(policy)` + plain calls `policy(tmpl)` (Option D1); (b) Streaming `onText`/`onPlaceholder`/`finish` (Option D2); (c) Also add juxtaposition `policy t"..."` (Option D3) | **(a) Option D1** — avoids Java JEP 459's complected processor syntax, supports both stateless functions and configured classes (like `SqlPolicy(conn)`), and enables two-pass fragment caching. |

### 8.2 Summary of the Recommended Babble Package

Putting the recommended defaults together yields a cohesive, unmistakably Babble design:

* **Multiline & raw strings:** `"""..."""` (JEP 378 compile-time closing-delimiter dedenting, `\s`, `\<newline>`), `r"..."`, `r"""..."""` (raw backslashes + multiline dedenting), and strictly single-line `"..."`.
* **String templates:** `t"..."`, `t"""..."""`, `rt"..."`, `rt"""..."""` (plus multi-dollar `t$$"""..."""` / `rt$$"""..."""` for literal `$(` in raw text), using **`$(expr)`** — preserving Babble's single-bracket `()` rule and pure `SLL` split grammar.
* **Runtime model:** First-class immutable `template` (`strings()`, `placeholders()`, `parts()`, `render()`, `eval(policy)`, `materialize()`, `+`) and `placeholder` (`value()` with `null`-safe, cycle-safe, thread-safe lazy memoization; `isEvaluated()`, `expression()`), 100% inside `org.babblelang.runtime` (`java.base` only) and declared via `BIP019` axioms.
* **Plug-in policies:** Ordinary functions `fn (tmpl: template): R` and `TemplatePolicy of R` implementations (`tmpl.eval(policy)` or `policy(tmpl)`), with `tmpl.render()` as the built-in concatenation policy and `-O1` `StringConcatFactory` deforestation eliminating all allocations for direct `.render()`.
* **Safety:** Static `template` type distinct from `string` (no implicit coercion, no `template + string`), `BIP017-D6` closure provenance (no viral `shared`), and `BIP016`/`BIP017` effect soundness.

---

## 9. Phased Implementation Plan (`BIP026-P1`–`P5`)

Once `BIP026-Q1`–`Q10` are decided, implementation proceeds in five incremental phases, each keeping `mvn -B package` (all three Surefire modes: `interpret`, `compile -O1`, `compile -O0`), `ParserGrammarTestCase`, `RuntimeBoundaryTestCase`, `AxiomsTestCase`, `FormatterTestCase`, `-Pbatest` (`>= 75%` branch gate), and `examples/raytracer-argon/scene.ppm` green:

| Phase | Scope & Deliverables | Key Verification |
|---|---|---|
| **`BIP026-P1`: Multiline & Raw String Literals** | Audit existing `.ba`/`.batest` for multiline `"..."`; tighten `"..."` (`Q1`), add `"""..."""`, `r"..."`, `r"""..."""` (`Q2`/`Q3`) and shared compile-time dedenter/unescaper (used by both `Interpreter` and `HirBuilder`); update `Formatter` (`moved`-style block shifting + semantic `shape()` check) and `BabbleSession.classify`. | New `src/test/babble/MultilineStrings.ba`, `.batest` dedent/escape/raw edge-case specs, `FormatterTestCase` round-trips, `ReplTestCase` multiline input tests. |
| **`BIP026-P2`: Split ANTLR4 Grammar & Template Syntax** | Split `Babble.g4` into `BabbleLexer.g4` + `BabbleParser.g4` with lexer modes and paren-depth stack (zero `@parser::members`, zero parser predicates, pure `SLL`); add `t"..."`, `t"""..."""`, `rt"..."`, `rt"""..."""`, multi-dollar escalation, and `$(expr)` (including nested templates); update `Formatter` for template token spacing and embedded expression formatting. | `ParserGrammarTestCase` (pure `SLL` across all files + nested template grammar tests), `FormatterTestCase` template round-trips, `ReplTestCase` unfinished template `INCOMPLETE` classification. |
| **`BIP026-P3`: Runtime `Template`/`Placeholder`, Core Types & Dual-Mode Lowering** | Implement `org.babblelang.runtime.Template`, `Placeholder` (lazy `UNSET`/`EVALUATING` memoization, thread safety), and `TemplateSupport` (`java.base`-only); register `template`/`placeholder` types and `BIP019` axioms; wire `BlockScopes`, `Resolver`, `TypeChecker` (forbidding `void` placeholders and `template + string`), `Interpreter`, and `HirExpr.TemplateLiteral` (`HirBuilder`/`HirVerifier`/`HirPrinter`/`HirEmitter`). | `RuntimeBoundaryTestCase`, `AxiomsTestCase`, `DifferentialTestCase` + `.batest` specs for lazy memoization (`null`, side-effect count = 1, cycles, exceptions, nested templates, `materialize()`, `t1 + t2`, `.render()`). |
| **`BIP026-P4`: Plug-in Evaluation Policies & Effect Integration** | Add `TemplatePolicy of R` + `tmpl.eval(policy)` (supporting both interface implementations and functions); integrate `BIP016`/`BIP017` effect tracking (`Q8`), `BIP017-D6` closure provenance (no viral `shared` for borrowed captures), and `unique`/`move` rejection inside lazy placeholders; add end-to-end policy tests (SQL `PreparedStatement` with an in-memory/mock JDBC `Connection`, HTML auto-escaping, guarded lazy logger). | `EffectsTestCase`, `src/test/babble/StringTemplates.ba`, `src/test/batest/strings/templates_and_policies.batest` (3-mode parity + JaCoCo coverage gate). |
| **`BIP026-P5`: `-O1` Deforestation & Documentation** | Add constant template folding (`ConstFoldPass`) and direct `.render()` deforestation (`TemplateDeforestPass` → `StringConcatFactory.makeConcatWithConstants` with unboxed primitives); add `Benchmarks` entries comparing `t"...".render()` at `-O0` vs `-O1` vs `"..." + ...`; update `README.md`, `AGENTS.md`, and `docs/design/README.md`. | `OptimizerTestCase` (bytecode inspection verifying zero `Template`/`Placeholder` allocation on direct `.render()`), full 3-mode `mvn -B package`, `mvn -q verify -Pbatest`, `-format -check`, and byte-identical `examples/raytracer-argon/scene.ppm`. |

---

## 10. References

* **C++:** ISO/IEC 14882 (C++11 §2.14.5 Raw string literals; C++20 `<format>` `std::format`; C++23 `<print>` `std::print`); Vittorio Romeo, [P1819R0: *Interpolated Literals*](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2019/p1819r0.html) (2019); Bengt Gustafsson & Victor Zverovich, [P3412: *String Interpolation*](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/) (2024–2026).
* **Python:** [PEP 498: *Literal String Interpolation*](https://peps.python.org/pep-0498/) (Python 3.6); [PEP 701: *Syntactic formalization of f-strings*](https://peps.python.org/pep-0701/) (Python 3.12); Jim Baker, Guido van Rossum, Paul Everitt, Koudai Aono, Lysandros Nikolaou & Dave Peck, [PEP 750: *Template Strings*](https://peps.python.org/pep-0750/) (Python 3.14); Inada Naoki, [PEP 822: *Dedented Multiline String Literals*](https://peps.python.org/pep-0822/) (Draft, 2026).
* **Java:** Jim Laskey & Alex Buckley, [JEP 378: *Text Blocks*](https://openjdk.org/jeps/378) (Java 15); Jim Laskey, [JEP 430](https://openjdk.org/jeps/430) (Java 21) & [JEP 459](https://openjdk.org/jeps/459) (Java 22): *String Templates*; Brian Goetz, [*Update on String Templates (JEP 459)*](https://mail.openjdk.org/pipermail/amber-spec-experts/2024-March/) (`amber-spec-experts`, March 8, 2024); `java.lang.invoke.StringConcatFactory` (JDK 9+ `java.base`).
* **Kotlin:** Kotlin Language Reference — [*Strings & String templates*](https://kotlinlang.org/docs/strings.html) (`"""..."""`, `trimIndent`, `trimMargin`); [KEEP-380 / Issue #375: *Multi-dollar String Interpolation*](https://github.com/Kotlin/KEEP/issues/375) (Preview in Kotlin 2.1, Stable in Kotlin 2.2).
