# BIP034: Immutable `def` and Mutable `var` Bindings

| | |
|---|---|
| **BIP** | 034 |
| **Date** | 2026-11-25 |
| **Status** | Implemented (`BIP034-Q1`–`BIP034-Q11` → `BIP034-D1`–`BIP034-D11` settled ; `BIP034-I1`–`BIP034-I5` implemented) |
| **Depends on** | Object model ([bip002_object-model.md](bip002_object-model.md)), interactive sessions ([bip004_repl.md](bip004_repl.md)), modules ([bip005_modules.md](bip005_modules.md)), effects & ownership ([bip007_effects.md](bip007_effects.md), [bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)), predicate-free grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), HIR & SSA MIR optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)) |
| **Scope** | Splitting Babble's single `def` declaration into non-reassignable `def` and explicitly reassignable `var` across locals, module-private top-level bindings, `class`/`object` fields, `private var` static fields, `class` parameters, and optional `fn (var ...)` parameters ; requiring `= expr` on both `def` and `var` outside interfaces ; keeping public module exports, interface bodies, and public `static` members strictly `def` ; retiring whole-file `reassigned` scans and `AnalysisSession.reassigned` ; making reads of top-level `def` function and class/record/interface definitions genuinely pure (`HirPurity.isPureNothrowTerminating`, first-class MIR `name_read Pair {pure}`, epoch-free GVN/LICM, and `Bootstrap.NameCache`/`SelectCache` fast paths) ; upgrading per-field immutability on regular `class`es in `EffectChecker`, `HIR`, and `MIR` ; value-capturing immutable `def` locals without `Cell` allocation ; and migrating existing `.ba` / `.batest.md` / test sources via a deterministic AST-backed migrator |

---

## 1. Summary

Today, Babble has a single declaration keyword, `def`, and **every `def` binding is implicitly reassignable** — including top-level function and class definitions (`def foo = fn ...`, `def Pair = record ...`), local bindings, class fields, static fields, and function parameters. Only `record` members (`BIP002`), `for` loop variables (`BIP013-D7`), `catch` parameters (`BIP028-D5`), methods, and module exports viewed from outside (`BIP005`) are protected from reassignment.

This proposal splits binding mutability into two explicit keywords:

* **`def`** — **non-reassignable binding** (like `val` in Kotlin/Scala, `let` in Swift/Rust, `const` in JS/TS, or `final` in Java). Once initialized, the binding can never be assigned to (`x = ...` or `x += ...` is a compile-time error).
* **`var`** — **reassignable binding** (like `var` in Kotlin/Scala/Swift). Reassignment is explicitly opted into at the declaration site.

Just as in Kotlin, Scala, Swift, and Java, `def` vs. `var` governs **binding rebindability**, not deep object immutability (`BIP007-I2` `frozen` and `BIP002` `record` already handle deep immutability):

```babble
;; Immutable bindings (the common case — ~90%+ of real Babble code)
def Pair = record (a: int, b: int) (
    def swap = fn (): Pair -> ( Pair(this.b, this.a) )
)
def xs = list.of(1, 2)        ;; binding `xs` cannot be rebound; list contents can still mutate under `mut` rules
def stop = 10

;; Explicitly mutable bindings (initializer always required outside interfaces)
var acc = 0
var i = 0
var best: Pair? = null
while i < stop then (
    acc += i
    i += 1
)

;; Regular classes distinguish immutable `def` fields from mutable `var` fields
export def RangeCursor = class (private var cur: int, private def stop: int) (
    def hasNext = fn (): bool -> ( this.cur < this.stop )
    def next = fn mut (): int -> ( def v = this.cur ; this.cur += 1 ; v )
)

;; Function parameters are implicitly `def`; `var` is allowed locally when needed (rarely used)
def countdown = fn (var n: int): int -> (
    var acc = 1
    while n > 1 then ( acc *= n ; n -= 1 )
    acc
)
```

Public module exports (`export def`), `interface` bodies, and public `static` members are **always `def`** — mutable module/static/interface state is encapsulated behind private `var` bindings or instance `var` fields with `fn mut` / `fn volatile` / `fn io` accessors.

### Key side goal: reading a function or class definition becomes pure

Because a top-level `def` (such as `def Pair = record ...`, `def Vec3 = record ...`, or `def helper = fn ...`) can **never** be reassigned — neither inside its module, nor through an `io` callback, nor in a later REPL session input — pairing `def` immutability with a richer non-escaping module-header initialization check makes accessing a function or class definition **genuinely pure** across the entire compiler stack:

```text
;; Before BIP034 (even after tightening NameRead from {impure} to {read:shared})
fn use(v0: int, v1: int) -> any:
  bb0:
    v2: any = opaque.NameRead(Pair) {read:shared}      ;; flushed by writes/io; {read:shared,throws} after import(...)
    v5: any = record_new Pair[v2](v0, v1) {pure}
    ret v5

;; After BIP034
fn use(v0: int, v1: int) -> any:
  bb0:
    v2: any = name_read Pair {pure}                    ;; PURE! Epoch-free GVN, LICM out of io loops, constant-folded in NameCache
    v5: any = record_new Pair[v2](v0, v1) {pure}
    ret v5
```

---

## 2. Motivation

Auditing the current repository exposes six coupled problems caused by mutable-by-default `def`:

1. **Over 90% of bindings are never reassigned, yet none can declare that fact.**
   Scanning the codebase today:
   * `examples/`: **662 `def`s** across 26 files, with only ~79 assignment statements across 15 files (`examples/raytracer-argon`: 391 `def`s, ~25 assignments; `examples/raytracer-fable`: 271 `def`s, ~28 assignments).
   * `src/test/babble/`: **599 `def`s** across 26 files, with only ~67 assignment statements across 12 files.
   * `src/test/batest/`: **9,578 `def`s** across 171 `.batest.md` files, with ~1,541 assignment statements across 133 files.
   * `src/main/java/org/babblelang/axioms/`: 100% immutable declarations.

   Yet nothing prevents `def foo = fn (a: int): int -> ( a + 2 )` or `def Pair = record ...` from being accidentally reassigned later, and a reader scanning a class like `Sphere`, `BVHNode`, or `RangeCursor` cannot tell which fields mutate (`cur`, `seed`) and which are fixed for the lifetime of the instance (`stop`, `center`, `radius`, `albedo`, `bbox`).

2. **Accessing a top-level function or class definition is still not pure in HIR or MIR.**
   In `src/test/batest/compiler/inline_and_alpha_rename.batest.md` (`InlinePass.inlineExpr : identity-order named args on record method skip inlining`), reading `Pair` inside a method lowered to `opaque.NameRead() {impure}`, and even after tightening `MirLowerer.opaqueEffect` to `MirEffect.READ_SHARED` (`{read:shared}`) for the leading pure prefix:
   * `NameRead(Pair)` is still classified as `Kind.READ` on `AliasClass.SHARED`, so every `WRITE_SHARED`, `NameAssign`, `StoreField`, or `IMPURE` (`io`) call flushes its GVN epoch and blocks `LicmPass` from hoisting it out of any loop with side effects.
   * In `examples/raytracer-argon/lib/vec3.ba`, `export def Vec3 = record (...) (...) static (...)` appears *after* `def math = import("math.ba")` and `def Rng = import("rng.ba").Rng`, and defines a `static ( ... )` block (`def zero: Vec3 = this(0.0, 0.0, 0.0)`). Consequently, `NameRead(Vec3)` inside `Vec3.plus` / `Vec3.minus` / `Vec3.times` still degrades all the way to `{read:shared,throws}` — neither hoistable nor dead-code-eliminable!
   * In `HirPurity.isPureNothrowTerminating`, standalone `HirExpr.NameRead` still returns `false` (only special-cased inside `DirectBabbleCall` and `RecordNew`).

3. **A soundness hole in interactive sessions (`BIP004`) regarding effect determinism.**
   Today, `TypeChecker` computes `reassigned` over the current input plus `session.reassigned`, and `EffectBodyWalker` treats a non-reassigned outer `def` as deterministic:
   ```babble
   ;; REPL input 1
   def x = 1
   def f = fn (): int -> ( x )   ;; checked as pure & deterministic (x not reassigned in input 1)

   ;; REPL input 2
   x = 2                         ;; succeeds! Now f() returns 2 instead of 1, violating f's pure contract!
   ```
   Making `def` permanently non-reassignable closes this hole cleanly: input 2 cannot assign to `x` (`Cannot assign to def : x`), while `var x = 1` in input 1 immediately requires `fn volatile` on `f` before input 1 commits.

4. **Duplicated whole-file `reassigned` pre-passes across 5 compiler subsystems.**
   Because mutability is not syntactic, `TypeChecks`, `TypeChecker`, `AnalysisSession`, `HirPurity`, and `MirLowerer` each scan the entire AST or HIR to compute their own `reassigned` set before answering basic questions: *"is `Shape` a static interface name?"*, *"is `m` a static module?"*, *"can `f` be a self-tail-call target?"*, *"does narrowing survive into this closure?"*, *"is this outer binding deterministic?"*, *"can `NameRead` be CSE'd?"*.

5. **Immutable fields on regular `class`es are treated as mutable everywhere.**
   `EffectBodyWalker.readsMutableBabbleMember` treats **every** field of a non-`record` class as mutable (`inst.node().kind != ClassShape.Kind.VALUE_CLASS`). Similarly, `HirLicmPass`, `MirLowerer`, and `AliasRlePass` only grant `READ_FROZEN` to `record` fields or `frozen` receivers. In `examples/raytracer-argon`, `Sphere`, `BVHNode`, `Lambertian`, `Metal`, `Dielectric`, `Camera`, and `RangeCursor.stop` are all regular classes with immutable `def` fields that currently miss `READ_FROZEN` RLE/LICM, non-volatile effect precision, JVM `ACC_FINAL` (`BIP018-P2`), and WasmGC immutable `(field ...)` (`BIP025-D11`).

6. **Unnecessary `Cell` heap allocations for captured immutable locals and uncached `NameCache` / `SelectCache` reads.**
   * `Resolver` allocates a mutable `Cell` (`IntCell`/`DoubleCell`/`BoolCell`/`Cell`) for *every* captured local — even immutable `def`s, `fn` parameters, and `for` loop variables.
   * `Bootstrap.NameCache` must call `r.slot.get()` (checking `if (set)` and dereferencing `Slot.value`) on every read because `Slot.isFinal` is currently always `false` in `Namespace.declare`.
   * `Bootstrap.SelectCache` has no fast path for immutable static `def` reads like `Vec3.zero` (one of the two follow-ups noted in `BIP014` §9.4).

---

## 3. State of the Art

| Language | Immutable binding | Mutable binding | Function params | Class header fields | Public module/static/interface surface |
|---|---|---|---|---|---|
| **Kotlin** | `val` | `var` | Always immutable | `class C(val x: Int, var y: Int, z: Int)` | `val`/`var` properties on interfaces & objects |
| **Scala 3** | `val` / `def` | `var` | Always immutable | `class C(val x: Int, var y: Int, z: Int)` | `val`/`var` members |
| **Swift** | `let` | `var` | Always immutable (`SE-0003`) | `let` / `var` stored properties | `{ get }` / `{ get set }` protocols |
| **Rust** | `let` | `let mut` | `mut x: T` allowed locally (distinct from `&mut T`) | Mutability inherited from binding | Encapsulated via methods; `static mut` unsafe |
| **TypeScript / JS** | `const` | `let` | Reassignable | `readonly` fields | `export let` live read-only bindings |
| **Java** | `final` (opt-in) | default | Reassignable unless `final` | `final` fields (`JEP 500` / `JEP 539` trusted final) | Interfaces allow `static final` constants only |

Babble's existing vocabulary (`def`, `mut`, `frozen`, `record`, `lazy def`, `scoped def` in `BIP018` §8) pairs **immutable-by-default `def`** with **explicit `var`** and **encapsulated public surfaces**:

* Keeping **`def`** as the immutable default minimizes churn (~90%+ of existing declarations stay untouched) and harmonizes with `BIP018`'s read-only `lazy def` and `scoped def`.
* Keeping public exports, interfaces, and public `static` members strictly `def` guarantees that any cross-module, interface, or static constant reference (`m.x`, `I.x`, `Vec3.zero`) can never observe a rebound slot.

---

## 4. Decisions (`BIP034-D1`–`BIP034-D11`)

All eleven design questions (`BIP034-Q1`–`BIP034-Q11`) are settled:

* **`BIP034-D1` (`BIP034-Q1`) — `var` is a reserved keyword included in `memberName`.**
  `VAR: 'var'` is added to `Babble.g4` and listed in `memberName`, keeping `.var` member accesses, Java interop, and named arguments (`foo(var: 1)`) valid with zero semantic predicates and pure `PredictionMode.SLL`.
* **`BIP034-D2` (`BIP034-Q2`) — All parameters are implicitly `def`; `fn` literals allow optional local `var` parameters.**
  Function parameters (`fn (x: T)`), bare constructor-only `class` parameters (`class (x: T)`), `for` loop variables, and `catch` parameters are all implicitly `def` (immutable). Function literals additionally allow `fn (var x: T)` (rarely used, local-only — **not** part of `Type.Function` / `fnTypeParam`, so `fn (var x: int): int` has type `fn (int): int`; modifier order `VAR? paramModifier* ID`, e.g., `fn (var mut x: Box)`). In `class` headers, `def x` / `var x` declare immutable/mutable member fields, while bare `x` is an immutable constructor-only parameter.
* **`BIP034-D3` (`BIP034-Q3`) — Both `def` and `var` require `= expr` outside `interface` bodies.**
  Uninitialized `defExpression` statements (`def x: T` or `var x: T` without `= expr`) are rejected outside `interface` required member declarations (`Declaration requires an initializer : <name>`). Mutable nullable bindings write explicit `var x: T? = null`. (`classParam`s receive their value from constructor arguments and keep optional `= defaultValue`.)
* **`BIP034-D4` (`BIP034-Q4`) — Forbid `export var`.**
  `export` applies only to `def` (`export var` is rejected with `Cannot export a var : <name>`). Modules expose mutable state through module-private `var` bindings plus exported `fn volatile` / `fn io` functions. Consequently, **every** module export in Babble is guaranteed non-reassignable both inside and outside its module.
* **`BIP034-D5` (`BIP034-Q5`) — `var f = fn (...) -> (...)` in a `class`, `object`, or `static` body is a mutable field, not a method.**
  Only `def m = fn ...` (or `def m = overload (...)`) declares a shared `ClassShape.METHOD`. Writing `var f = fn ...` (or `private var f = fn ...` in a `static` block) creates a mutable field (`ClassShape.MUTABLE`) holding a function value that can be reassigned (`this.f = otherFn`).
* **`BIP034-D6` (`BIP034-Q6`) — Keep sequential TDZ semantics + non-escaping header / clean-REPL / self-method definite-initialization analysis.**
  Top-level definitions retain Babble's existing sequential Temporal Dead Zone (TDZ) semantics (`Name used before definition` when read before the `def` line executes, preserving `BabbleScopesTestCase:78` and `values_and_operators.batest.md:600`). Static non-escaping module-header, clean-REPL-input, and self-class definite-initialization analysis (§6.2) proves `definitelyDefined = true` so reads of `def` functions, classes, records, interfaces, constants, and non-cyclic imports are `{pure}` (`MirEffect.PURE_NOTHROW`) in all normal modules (including `Pair` and `Vec3`).
* **`BIP034-D7` (`BIP034-Q7`) — Promote `NameRead` and `NameAssign` to first-class MIR instructions.**
  In `BIP034-I3`, `MirInst.NameRead` (`name_read Pair {pure}`) and `MirInst.NameAssign` (`name_assign counter, v1 {write:shared}`) replace the `MirInst.OpaqueExpr` wrapper for lexical/global name accesses.
* **`BIP034-D8` (`BIP034-Q8`) — Restrict `interface` bodies to `def` only.**
  `var` is forbidden in `interface` bodies (`Interface members cannot be var : <name>`). Interface data members (`def x: T`) are read-only and covariant (satisfied by `def x: U` or `var x: U` with `U <: T` in implementing classes/records/objects); mutable state on an interface is accessed through `fn mut` methods.
* **`BIP034-D9` (`BIP034-Q9`) — Keep all 5 implementation phases (`BIP034-I1`–`BIP034-I5`) in `BIP034`.**
  Runtime inline-cache fast paths (`BIP034-I4`: `Bootstrap.NameCache` & `SelectCache`) and value-capture for immutable `def` locals without `Cell` allocation + `ACC_FINAL` (`BIP034-I5`) stay tracked as follow-up phases of `BIP034`.
* **`BIP034-D10` (`BIP034-Q10`) — Hard cutover migration in `BIP034-I1` via a deterministic AST-backed migrator.**
  Consistent with `BIP012`, `BIP016`, `BIP026-I1`, and `BIP030`, `BIP034-I1` performs a single hard cutover using a deterministic ANTLR-token migrator across `examples/`, `src/test/babble/`, `src/test/batest/`, and `src/test/java/` (no temporary deprecation period).
* **`BIP034-D11` (`BIP034-Q11`) — Allow only `private var` in `static ( ... )` blocks.**
  Mirroring `BIP034-D4` (no `export var`) and `BIP034-D8` (`def`-only interfaces), public static members on classes, records, and interfaces are **always `def`** (`Public static members cannot be var : <name>`). Mutable static state uses `private var` encapsulated behind static `fn volatile` / `fn io` methods. Consequently, every externally visible static member read (`Vec3.zero`, `Interval.empty`, `Cls.CONST`) is guaranteed to target a `def`.

---

## 5. Language Semantics & Grammar

### 5.1 `def` vs. `var` across every declaration site

| Declaration site | `def` | `var` | Initializer `= expr` |
|---|---|---|---|
| **Local block / `fn` body** | Immutable local binding; cannot be reassigned. | Mutable local binding; assignable via `x = ...` / `x op= ...`. | **Required** on both `def` and `var` (`BIP034-D3`). |
| **File top-level (module-private)** | Immutable module/script binding (also immutable across subsequent REPL inputs). | Mutable module/script binding (reading in a `fn` requires `volatile`; writing requires `io`). | **Required** on both `def` and `var` (`BIP034-D3`). |
| **Module export (`export`)** | Immutable export; non-reassignable inside and outside module. | **Forbidden** (`Cannot export a var : <name>`, `BIP034-D4`). | **Required**. |
| **`class` header parameter** | `def x: T` / `private def x: T` — immutable instance field (`ClassShape` without `MUTABLE`). | `var x: T` / `private var x: T` — mutable instance field (`ClassShape.MUTABLE`). | Optional default parameter value (`= defaultExpr`). |
| **Bare `class` header parameter** (`x: T`) | Constructor-only parameter (not a member). **Always immutable** (`BIP034-D2`). | N/A (`var x` in `classParameters` declares a mutable member field). | Optional default parameter value. |
| **`class` / `object` body member** | `def x = expr` — immutable field; `def m = fn ...` / `overload (...)` — method (`ClassShape.METHOD`). | `var x = expr` — mutable field (`ClassShape.MUTABLE`); `var cb = fn ...` — mutable **field** holding a function value, **not** a method (`BIP034-D5`). | **Required** on both `def` and `var` (`BIP034-D3`). |
| **`record` header & body** | Every header parameter is an immutable field (`def` stays allowed with existing redundancy warning); body `def` fields & methods are immutable. | **Forbidden** at member level (`Value class members cannot be var : <name>`). Allowed for local variables *inside* record methods. | Standard `record` rules (body `def` requires `= expr`). |
| **`static ( ... )` block** (on `class`, `record`, `interface`) | Immutable static field or static method (public or `private`). | **`private var` only** (`Public static members cannot be var : <name>`, `BIP034-D11`); reading requires `volatile`, writing requires `io`; `private var cb = fn ...` is a mutable static field, not a method. | **Required** on both `def` and `private var`. |
| **`interface` body** | `def m: fn ...` / `def x: T` — required read-only member (covariant; satisfied by `def` or `var`); `def m = fn ...` — default method. | **Forbidden** (`Interface members cannot be var : <name>`, `BIP034-D8`). | Forbidden on required members (`def x: T`); required on default methods. |
| **`fn` parameter (`fn (x: T)`)** | Implicitly `def` (immutable: `Cannot assign to parameter : <name>`). | Optional `fn (var x: T)` / `fn (var mut x: T)` (local-only, not part of `Type.Function`, `BIP034-D2`). | Optional default parameter value. |
| **`for` loop variable** | Always immutable (`Cannot assign to loop variable : <name>`, `BIP013-D7`). | N/A. | Supplied by iterable. |
| **`catch` parameter** | Always immutable (`Cannot assign to catch parameter : <name>`, `BIP028-D5`). | N/A. | Supplied by caught exception. |
| **Generic declaration (`of T`)** | Allowed (`def id of T = fn ...`, `def Box of T = class ...`). | **Forbidden** (`Type parameters only apply to def : <name>`). | Required (except interface required members). |
| **Type alias (`= type T`)** | Allowed (`def Num = type int \| double`, `BIP023`). | **Forbidden** (`Type alias must be declared with def : <name>`). | Required. |
| **Axiom member (`axiom ...`)** | `def m: fn ...` / `def *: fn ...` (unchanged). | **Forbidden** in `axiomMember`. | N/A. |

### 5.2 Grammar (`Babble.g4`)

The grammar adds a single token (`VAR: 'var';`), keeps `Babble.g4` 100% LL(1), zero `@parser::members`, zero semantic predicates, and pure `PredictionMode.SLL`:

```antlr
memberName: ID | coreTypeKeyword | effectModifier | AXIOM | RECORD | OF | UNIQUE | FROZEN
          | IN | BREAK | CONTINUE | CALLING | TYPE | OVERLOAD | AND | OR | NOT | FOREVER
          | TRY | CATCH | FINALLY | THROW | VAR;

expression:
          ifExpr                                              # ifExpression
          | (EXPORT | PRIVATE)? (DEF | VAR) name=ID (OF typeParameters)? (':' type)?
                     ('=' NL* value=expression)?              # defExpression
          ...

classParam: PRIVATE? (DEF | VAR)? paramModifier* ID (':' type)? ('=' NL* defaultValue=expression)?;

fnParam: VAR? paramModifier* ID (':' type rest=(ELLIPSIS | Q_ELLIPSIS)?)? ('=' NL* defaultValue=expression)?;

// fnTypeParam stays unchanged: `var` is local to the function body and not part of Type.Function.

// Lexer
DEF: 'def';
VAR: 'var';
```

* **Zero identifier conflicts in the repository:** A scan of every `.ba` file in `examples/`, `src/test/babble/`, `src/main/java/org/babblelang/axioms/`, and every `babble` fenced block in `src/test/batest/` found **0** occurrences of `var` as an identifier.
* **Member/named-arg safety (`BIP034-D1`):** Including `VAR` in `memberName` keeps `obj.var`, `foo(var: 1)`, and Java fields/methods named `var` valid without escaping.
* **Context-sensitive validation in `BlockScopes`:** Keeping `(DEF | VAR)` unified in `defExpression` and `classParam` yields precise semantic diagnostics rather than raw syntax errors.

### 5.3 Formatting (`Formatter.java`)

* `visitDefExpression` emits `var` when `ctx.VAR() != null` and `def` otherwise, preserving modifier order (`private var`, `export def`, `private def`) and spacing (`var x: int = 0`).
* `visitClassParam` emits `var` / `def` / nothing verbatim (`private var cur: int`, `private def stop: int`, `fuzzIn: double = 0.0`).
* `visitFnParam` emits `var ` before `paramModifier*` when `ctx.VAR() != null` (`fn (var n: int)`, `fn (var mut buf: list of int)`).
* No changes to newline, indentation, or `shape()` normalization rules.

### 5.4 Diagnostics

All diagnostics follow Babble's space-before-colon convention and match between compile-time analysis and runtime boundaries (`Slot`, `Members`):

| Situation | Message | Where reported |
|---|---|---|
| Reassigning a lexical `def` | `Cannot assign to def : <name>` | `TypeChecks` (compile time) & `Slot.set` (runtime defense-in-depth) |
| Reassigning a non-`var` `fn` or bare `class` parameter | `Cannot assign to parameter : <name>` | `TypeChecks` |
| Reassigning a `def` instance or static member (including `this.x = ...` inside the constructor body) | `Cannot assign to def member : <name>` | `TypeChecks`, `TypeChecker`, and `Members.cannotAssignDef` (`BabbleType`, `InterpretedInstance`, `BabbleObjects`) |
| Reassigning a `record` member | `Value members are read-only : <name>` | Existing message (preserved) |
| Reassigning a `for` / `catch` / `export` / method | Existing messages (`Cannot assign to loop variable : ...`, `Cannot assign to catch parameter : ...`, `Cannot assign to an export : ...`, `Cannot assign method : ...`) | Preserved unchanged |
| Missing `= expr` on `def` or `var` outside `interface` | `Declaration requires an initializer : <name>` | `BlockScopes` (`BIP034-D3`) |
| `export var` | `Cannot export a var : <name>` | `BlockScopes` (`BIP034-D4`) |
| `var` in a `record` header or body | `Value class members cannot be var : <name>` | `BlockScopes` |
| `var` in an `interface` body | `Interface members cannot be var : <name>` | `BlockScopes` (`BIP034-D8`) |
| Public `var` in a `static ( ... )` block | `Public static members cannot be var : <name>` | `BlockScopes` (`BIP034-D11`) |
| `var` with `of typeParameters` | `Type parameters only apply to def : <name>` | `BlockScopes` |
| `var T = type ...` | `Type alias must be declared with def : <name>` | `BlockScopes` |

---

## 6. Making Function & Class Definition Reads Pure (Side Goal)

A central goal of `BIP034` is that **accessing a function or class/record/interface definition becomes genuinely pure** — not `{impure}`, and not `{read:shared}` / `{read:shared,throws}` — in both HIR and MIR, and constant-folded at runtime.

```mermaid
flowchart TD
    A["Babble Source : def Pair / def Vec3 / def helper / var counter"] --> B["BlockScopes + TypeChecks : isVar flag, Slot.isFinal"]
    B --> C["HirBuilder : Non-Escaping Header + Clean REPL + Self-Class Init Analysis"]
    C --> D{"NameRead target"}
    D -->|"def + definitely initialized"| E["HirExpr.NameRead(final=true, init=true) : HirPurity = pure"]
    D -->|"def + init not proven"| F["HirExpr.NameRead(final=true, init=false)"]
    D -->|"var + init proven"| G["HirExpr.NameRead(final=false, init=true)"]
    D -->|"var + init not proven"| H["HirExpr.NameRead(final=false, init=false)"]
    E --> I["MIR : name_read Pair {pure} (MirEffect.PURE_NOTHROW)"]
    F --> J["MIR : name_read late {pure,throws} (MirEffect.PURE_MAY_THROW)"]
    G --> K["MIR : name_read counter {read:shared} (MirEffect.READ_SHARED)"]
    H --> L["MIR : name_read lateVar {read:shared,throws} (MirEffect.READ_SHARED_THROW)"]
    I --> M["GvnCsePass (epoch-free PureNameReadKey) + LicmPass (even out of io loops) + MirDcePass"]
    J --> N["GvnCsePass (dominated duplicate CSE) + no epoch flush"]
    M --> O["MirEmitter + Bootstrap.NameCache (final initialized Slot -> constant value fast path)"]
```

### 6.1 Why `NameRead` could not be `{pure}` before `BIP034`

Two separate obstacles prevented `MirLowerer` and `HirPurity` from treating `NameRead(Pair)` or `NameRead(Vec3)` as `MirEffect.PURE_NOTHROW` (`{pure}`):

1. **Lack of binding immutability (`AliasClass.SHARED`):** Without `def` vs. `var`, a top-level `def` could be mutated by `NameAssign`, by a callback, or by a later REPL input. Therefore `MirLowerer` had to classify `NameRead` as `Kind.READ` on `AliasClass.SHARED` — causing every `WRITE_SHARED`, `StoreField`, `NameAssign`, or `IMPURE` (`io`) call to flush the GVN epoch and block LICM.
   With `BIP034`, a `def` slot is **write-once forever** (`Slot.isFinal == true`, statically enforced across the entire module and all future REPL inputs). Once initialized, no store, `NameAssign`, or `io` callback can ever change its value.
2. **Temporal Dead Zone (`Name used before definition`):** Top-level statements in a Babble script execute sequentially while all top-level names are hoisted as unset slots at scope entry. Reading a top-level `def` *before* its `def` statement runs throws `Name used before definition : <name>`. In particular, a naive "leading prefix of `fn`/`const`/`class` without `static`" check stopped before `def math = import("math.ba")` and before `export def Vec3 = record (...) static ( def zero = this(0.0, 0.0, 0.0) ... )`, leaving `Vec3` as `throws` in `examples/raytracer-argon/lib/vec3.ba`.

### 6.2 Non-escaping module header & self-method definite-initialization analysis (`BIP034-D6`)

To prove `definitelyDefined = true` (`nothrow = true`) for real-world modules like `examples/raytracer-argon/lib/{math,vec3,interval,aabb}.ba` while preserving Babble's existing sequential TDZ semantics (`BabbleScopesTestCase:78` and `values_and_operators.batest.md:600`), `HirBuilder` (and `MirLowerer`) computes four sound static facts. Only **direct** `HirExpr.Def` statements of `module.topLevelStatements()` are eligible (never `def`s nested inside conditional or short-circuit expressions such as `if cond and (def x = 1) == 1`).

1. **Non-escaping module header (`headerInitializedDefs`):**
   Define a **non-escaping header expression** inductively as an expression that neither invokes nor escapes any callable defined in the current module:
   * `Const`, `FunctionRef`, `Overload` of `FunctionRef`s, `TypeAlias` (`null`), `CoreCollectionRef`, `CoreExceptionRef`,
   * `NameRead` of an earlier header binding or an unshadowed built-in,
   * `import("other.ba")` (provided `other.ba` does **not** transitively import the current module, already tracked by `ModuleInterface` dependencies — `other.ba` cannot hold a reference to any callable of this module) or `import("java.pkg")`,
   * `ModuleExportGet` on a non-cyclic static import (every module export is `def` by `BIP034-D4`),
   * pure primitive/scalar `Unary`, `Binary`, `Logic`, `As`, and `Is` over non-escaping sub-expressions (covering e.g. `export def infinity: double = 1.0 / 0.0` and `negInfinity = -1.0 / 0.0` in `examples/raytracer-argon/lib/math.ba`),
   * reads of a public static `def` on an already-bound non-cyclic imported `BabbleType` (covering e.g. `Interval.empty` in `examples/raytracer-argon/lib/aabb.ba`) or a `final` static Java field (`java.lang.Double.POSITIVE_INFINITY`),
   * `RecordNew` / `this(...)` constructions of a `fastRecord` (empty constructor body, all arguments supplied with no omitted default calls) whose arguments are non-escaping expressions (covering `Vec3.zero = this(0.0, 0.0, 0.0)`, `Interval.empty = this(math.infinity, math.negInfinity)`, and `AABB.empty = this(Interval.empty, Interval.empty, Interval.empty)`),
   * non-`object` `ClassExpr` (`!cls.isObject()`) or `InterfaceExpr` whose `staticBlock` is either `null` or contains only direct `def` / `private var` declarations initialized by non-escaping expressions. *(Note: `object (...)` expressions immediately execute their body on creation and are intentionally excluded.)*

   Walking direct statements of `module.topLevelStatements()` in order: every `def X = expr` (and `var X = expr` with `finalBinding = false`) whose initializer is a non-escaping header expression, plus `axiom ...`, preserves the *"no function or method of this module has been invoked or escaped yet"* invariant. Every `def X` before the first statement that breaks this invariant is **definitely initialized before ANY function or method of this module can ever be entered**.
   * Across `examples/raytracer-argon`, `math`, `Rng`, `Vec3`, `Interval`, `Ray`, and `AABB` are ALL in `headerInitializedDefs`!
   * In `values_and_operators.batest.md:600`, `C`'s `static` block calls `C.getB()`, which is **not** a non-escaping expression — it breaks the header before `C`, keeping `name_read C` as `{pure,throws}` and preserving `Name used before definition : C`!
   * In `BabbleScopesTestCase:78` (`println(1) ; def println = fn ...`), top-level `println(1)` executes at module top-level (not inside a function body) before `def println = ...`, preserving `Name used before definition : println`!

2. **Committed REPL inputs (`AnalysisSession`):**
   Every `def` declared in an earlier REPL input whose evaluation **completed normally** (did not throw mid-input) is already initialized in the parent `Namespace` before the new input compiles. `AnalysisSession` records whether each committed input finished cleanly; `def`s from cleanly completed inputs get `finalBinding = true, definitelyDefined = true` (`{pure}`), while `def`s from a partially-failed input stay `finalBinding = true, definitelyDefined = false` (`{pure,throws}`), preserving `ReplTestCase:131` and `ReplTestCase:425`.

3. **Self-class initialization for instance methods:**
   Even if a non-`object` `class` / `record` `C` appears as a direct top-level statement *after* an arbitrary top-level statement in a script, an instance method `C.m` can only be called after at least one instance of `C` exists. Provided `C`'s constructor body has no calls or `this`-escapes **and** `C`'s `static` block is `null` or non-escaping per item 1 (so `C`'s `static` block cannot construct and leak an instance of `C` before `def C` finishes binding), `def C` itself and every direct top-level `def` preceding `def C` in `module.topLevelStatements()` are guaranteed to have completed before `C.m` runs!

4. **Built-in & implicit names:**
   Built-in prelude functions (`print`, `println`, `assert`, `classof`, `int`, `double`, `string`, `bool`, etc.) that are **not** shadowed by a file-level declaration are always bound in the root `Namespace` before any script executes (`finalBinding = true, definitelyDefined = true` → `{pure}`).

### 6.3 HIR & MIR Effect Matrix, First-Class `MirInst.NameRead` (`BIP034-D7`), and Optimizer Payoff

| Target of `NameRead(name)` | `finalBinding` | `definitelyDefined` | `HirPurity.isPureNothrowTerminating` | `MirEffect` | Canonical MIR syntax | GVN / LICM / DCE behavior |
|---|---|---|---|---|---|---|
| Top-level `def` (fn, class, record, interface, const, non-cyclic import, or clean REPL `def`) | `true` | `true` | **`true`** | **`PURE_NOTHROW`** | **`name_read Pair {pure}`** | **Epoch-free GVN** across `StoreField`/`NameAssign`/`io` calls; **LICM out of any loop** (including `io` loops); **DCE if unused**. |
| Top-level `def` (late / after arbitrary top-level call / active `static` block / failed REPL input) | `true` | `false` | `false` | **`PURE_MAY_THROW`** | **`name_read late {pure,throws}`** | Never flushes alias epochs; **dominated duplicate CSE** (along non-unwind edges once an earlier dominating `name_read` succeeds, the slot is initialized and final forever). |
| Top-level `var` (definitely initialized) | `false` | `true` | `false` | `READ_SHARED` | `name_read counter {read:shared}` | Epoch-scoped GVN (`NameReadKey(name, kind, epoch)`); LICM out of pure/read-only loops; DCE if unused. |
| Top-level `var` (init not proven) | `false` | `false` | `false` | `READ_SHARED_THROW` | `name_read lateVar {read:shared,throws}` | Preserves TDZ exception; does not act as a write barrier for unrelated loads. |

In `BIP034-I3` (`BIP034-D7`):
* `MirInst.NameRead(MirVal result, String name, boolean finalBinding, MirEffect effect, int line)` and `MirInst.NameAssign(MirVal result, String name, MirVal value, MirEffect effect, int line)` become first-class MIR instructions (`name_read Pair {pure}`, `name_assign counter, v1 {write:shared}`), eliminating `OpaqueExpr` special-casing in `GvnCsePass`, `MirLowerer`, and `MirCanonicalPrinter`.
* `GvnCsePass` uses epoch-free `PureNameReadKey(String name, PrimitiveKind kind)` for `PURE_NOTHROW` `NameRead`s and records `PURE_MAY_THROW` `NameRead`s after execution so strictly dominated duplicate reads reuse the first SSA value — guarded by `GvnCsePass`'s existing `!child.handlerEntry() && normalSinglePred` exception-edge discipline so a `catch` handler entered precisely when the first `name_read` threw `Name used before definition` never reuses its uninitialized result.
* Because `BIP034-D4` forbids `export var`, `ModuleExportGet` on a non-cyclic static import of an immutable `def` export (function, class, record, interface, constant) is likewise guaranteed immutable!

### 6.4 Runtime & JIT Fast Paths (`Slot`, `Bootstrap.NameCache`, `Bootstrap.SelectCache`)

`Slot<T>` (`src/main/java/org/babblelang/runtime/Slot.java`) **already** has a `private final boolean isFinal` field — it is simply hardcoded to `false` in `Namespace.declare` today.

1. **`Namespace.declare` sets `isFinal = true` for `def`s and `false` for `var`s** (and `Namespace.defineParameter(name, value, isFinal)` sets `isFinal = true` for implicitly-`def` parameters and `false` for `fn (var ...)` parameters).
2. **`Bootstrap.NameCache` constant fast path (`BIP034-I4`):**
   When `Bootstrap.NameCache.lookup(Namespace)` resolves a `Slot` with `slot.isFinal() && slot.isSet()`, the `Resolution` record caches the resolved `Object value` directly (skipping `r.slot.get()` and its `if (set)` branch on every access — or specializing a monomorphic `MutableCallSite` guard `(ns == cachedNs && Namespace.epoch() == cachedEpoch)` directly to `MethodHandles.constant(Object.class, value)` so HotSpot C2 constant-folds `Vec3` / `Pair` / `math`!).
3. **`Bootstrap.SelectCache` constant fast paths for `Module` exports & `BabbleType` static `def`s (`BIP034-I4`):**
   * **Static `def` members (`Vec3.zero`, `Interval.empty`, `Vec3.randomUnitVector`):** Because `BIP034-D11` restricts `static` `var`s to `private var`, **every** externally visible static member (`!shape.isStaticPrivate(idx) && !shape.isStaticMutable(idx)`) — both static `def` values and static `def` methods (already bound as `BoundMethod` to `bt`) — is constant forever for that `BabbleType` instance once initialized. `SelectCache.relink` can guard on `IS_SAME.bindTo(bt)` and return `MethodHandles.dropArguments(MethodHandles.constant(Object.class, result), 0, Object.class)`, eliminating both the `HashMap` lookup in `ClassShape.staticIndex` noted in `BIP014` §9.4 and per-call `BoundMethod` allocation across all optimization levels!
   * **Module exports (`math.pi`, `math.min`):** Because `BIP034-D4` forbids `export var`, every initialized `Module` export is also constant forever for that `Module` instance, so `SelectCache`'s existing `target instanceof Module mod` branch can likewise return `MethodHandles.constant(Object.class, result)` directly instead of calling `EXPORT_AT` (`Slot.get()`) on every read!

---

## 7. Type Checking, Effects, and Object Model Integration

### 7.1 Retiring whole-file `reassigned` scans and `AnalysisSession.reassigned`

Once binding mutability is a local syntactic fact (`isVar(decl)`):

* **`TypeChecks`**: drops its `Set<ParserRuleContext> reassigned` completely. `resolveDefinition`, `moduleOf`, `exportedModule`, and `staticClassOf` simply test `!isVar(def)`. Any `PendingAssignment` targeting a non-`var` binding is rejected with `Cannot assign to def : <name>` or `Cannot assign to parameter : <name>`.
* **`AnalysisSession`**: drops `final Set<ParserRuleContext> reassigned`. Earlier REPL inputs' `def`s can never be reassigned in later inputs, permanently fixing the §2 item 3 REPL determinism soundness hole!
* **`TypeChecker`**: `TypeEnv.enterCallable` only evicts `var` bindings — flow-sensitive narrowing on `def` bindings (`if s != null then ( fn () -> ( s.length() ) )`) is locally known to survive closure capture and REPL input boundaries without a whole-file pre-scan. *(For `SlotInference` widening — e.g., `var x = 1 ; x = 1.5` widening `x` to `double`, while an un-reassigned `var y = 1` stays `int` — `TypeChecker` still tracks assigned `var`s during inference, while never needing to scan `def`s.)*
* **`StaticRefs`, `TypeAnnotations`, `LoweringAnalyzer`, `TailCalls`, `ScalarReplacePass`**: all `!checker.isReassigned(def)` / `!isReassigned(param)` checks become direct `!isVar(decl)` checks (`fn (var x: T)` behaves as a mutable local for borrow-unboxing and algebraic bailouts).
* **`TypedModuleSummary`**: thanks to `BIP034-D4` (no `export var`), `BIP034-D8` (`def`-only interfaces), and `BIP034-D11` (`private var`-only statics), `TypedModuleSummary` only needs to carry `boolean mutable` on **`class`/`object` instance fields** — every module export, interface member, and public static member is immutable by construction!

### 7.2 `EffectChecker` precision improvements

1. **Per-field immutability on regular `class`es and `object`s:**
   `EffectBodyWalker.readsMutableBabbleMember(Type recvType, String fieldName)` checks whether `fieldName` is declared `var` (`member.isMutable()`) on any concrete `Type.Instance` in `recvType`, rather than assuming every non-`record` class is completely mutable.
   * Reading `this.stop` (`private def stop: int`) on `RangeCursor` or `s.radius` (`def radius: double`) on `Sphere` no longer taints `readsThis` or `readParams`!
   * Reading `this.cur` (`private var cur: int`) on `RangeCursor` still accurately taints `readsThis`.
2. **Static `def` determinism:**
   In `EffectBodyWalker` (line 846), `if (sm != null && (sm.isMutable() || !isImmutableConstantValue(resType)))` immediately recognizes static `def` fields of immutable types (`Vec3.zero`, `Interval.empty`) without whole-file reassignment scanning (and every public static member is guaranteed `def` by `BIP034-D11`).
3. **Captured local `def`s:**
   In `EffectBodyWalker` (line 781), an `escapedCapturedLocal` only degrades `deterministic = false` if it is a `var`; a captured `def` of an immutable value is always deterministic even when captured by an escaping closure.
4. **`AlgebraicVerifier` & `SizeChangeTermination` (`EffectAstExtractors`):**
   Non-`var` parameters and `def`s are statically immutable, replacing whole-file `isReassigned` scans with direct `!isVar(decl)` checks.

### 7.3 `ClassShape.MUTABLE` & Runtime Enforcement

* `ClassShape` adds `public static final int MUTABLE = 4;` alongside `METHOD = 1` and `PRIVATE = 2`, with `isMutable(int index)` and `isStaticMutable(int index)`. Because `ClassShape.encode`/`decode` already serializes `flags[]` and `staticFlags[]` as integers, the wire format for compiled classes and `babblec` AOT jars stays completely compatible.
* `BabbleType.setMember`, `InterpretedInstance.setMember`, and `BabbleObjects.setMember` enforce `!shape.isMutable(index)` → `Members.cannotAssignDef(name)` (`Cannot assign to def member : <name>`) for dynamically typed (`any`) receivers, maintaining 100% behavioral parity between compile-time diagnostics and runtime checks in both backends.

### 7.4 Synergy with HIR/MIR & Downstream Backends

* **`def` fields on regular `class`es become `READ_FROZEN` in MIR and hoistable in `HirLicmPass`:**
  Generalizing `HirExpr.ThisFieldGet.recordField()` → `immutableField()` (true for all `record` fields **and** all `def` fields on regular `class`/`object`s) unlocks `MirEffect.READ_FROZEN`, `AliasRlePass` redundant-load elimination, and `HirLicmPass`/`LicmPass` loop hoisting on regular classes (`Sphere`, `BVHNode`, `Lambertian`, `RangeCursor.stop`).
  *(Soundness note: inside the class's own constructor `cls$N$Name`, `this.defField` is only marked `definitelyDefined = true` for header `def` parameters, which are initialized before the constructor body starts — preserving `Member used before definition` for body `def`s read before their definition line!)*
* **Value-capture for immutable `def` locals (`BIP034-I5`):**
  In `Resolver` (`use()`), a captured variable that is a definitely-initialized `def` (including non-`var` `fn` parameters, `for` loop vars, `catch` params, and local `def`s whose declaration dominates the closure creation — excluding self-recursive or mutually-recursive local `def` closures initialized later) does **not** need a heap-allocated `Cell` / `IntCell` / `DoubleCell` / `BoolCell` wrapper:
  * Reference captures (`string`, `Vec3`, `list`, `fn`, `this`, objects) and compiled class hidden fields (`$v$x` in `ClassGen`, including all 8 primitive kinds) store the raw value directly with **zero** wrapper/box allocation and zero indirection.
  * Primitive captures stored in `CompiledFunction`'s `Object[] captured` array box once at closure creation (`Integer.valueOf`, etc.) rather than allocating an `IntCell`..`CharCell` at `def` entry, eliminating `Cell` allocation on paths that never construct the closure and removing a pointer dereference on every read.
* **Alignment with `BIP018` (Java improvements) and JVMS `ACC_FINAL`:**
  * Under JVMS §4.5 / §6.5, an `ACC_FINAL` instance field may only be written by `PUTFIELD` inside `<init>` of its declaring class (not from `cls$N$Name` or `$new`). In `BIP034-I5`, `ClassGen` emits a typed `<init>(BabbleClass, ScriptRuntime, <headerDefParams...>)` constructor that initializes header `def` fields directly (starting with `fastRecord` and classes whose header `def` fields are passed directly to `<init>`), making `ACC_FINAL` verifiable on JDK 21 and laying the exact groundwork for `BIP018-P2` (`JEP 500` trusted-final constant folding) and `BIP018-P4` (`JEP 539` `ACC_STRICT_INIT`).
  * `BIP018` §8.1 (`lazy def`) and §8.2 (`scoped def`) already assume `def` is strictly read-only — `BIP034` makes that universal.
* **Alignment with `BIP025` (WebAssembly 3.0 / `WasmGC`):**
  `BIP025-D11` emits immutable `(field ...)` for `record` fields and `(field (mut ...))` for mutable fields; with `BIP034`, header `def` fields on regular `class`es also become immutable WasmGC `(field ...)`s initialized by `struct.new`, body `def` fields set unconditionally by a constructor that never lets `this` escape skip their read-before-definition checks, and `BIP034-I5` value-captures are stored unboxed in closure/class structs without `$Cell` allocation (`BIP025` §5.6).

---

## 8. Implementation Plan (`BIP034-I1`–`BIP034-I5`)

Each phase keeps `mvn -B package -PO2` green across all four execution modes (`interpret`, `compile -O0`, `compile -O1`, `compile -O2`), passes `ArchitectureBoundaryTestCase` / `RuntimeBoundaryTestCase` / `ParserGrammarTestCase`, and maintains `-format -check` cleanliness.

### `BIP034-I1` — Grammar, Front-End Enforcement, Runtime Metadata & Repository Migration
* Add `VAR: 'var'` (and `VAR` in `memberName`) to `Babble.g4`; update `defExpression`, `classParam`, and `fnParam` (`BIP034-D1`, `BIP034-D2`).
* Update `Formatter` to emit `def` / `var` verbatim in definitions, `class` parameters, and `fn` parameters.
* Add `ClassShape.MUTABLE = 4` (with `isMutable` / `isStaticMutable` and `encode`/`decode` support); update `Slot.set` (`Cannot assign to def : ...`), `Namespace.declare` / `Namespace.defineParameter(name, value, isFinal)` (wired through `Interpreter`, `Arguments`/`Binder`, and `CompiledFunction` parameter metadata), and runtime `BabbleType`/`InterpretedInstance`/`BabbleObjects`/`Members` (`Cannot assign to def member : ...`).
* Update `BlockScopes`, `TypeChecks`, `TypeChecker`, `TypedModuleSummary`, `AnalysisSession`, `StaticRefs`, `TypeAnnotations`, `LoweringAnalyzer`, and `TailCalls`:
  * Enforce all §5.1 & §5.4 rules (`def` vs. `var`, mandatory `= expr` outside interfaces per `BIP034-D3`, no `export var` per `BIP034-D4`, `var f = fn ...` as mutable field per `BIP034-D5`, `def`-only interfaces per `BIP034-D8`, `private var`-only statics per `BIP034-D11`, and immutable parameters with optional `fn (var ...)` per `BIP034-D2`).
  * Retire whole-file `reassigned` scans in `TypeChecks` and `AnalysisSession.reassigned`.
  * Update `TypeEnv.enterCallable` to evict only `var` bindings.
* Run a deterministic ANTLR-token migration tool (`BIP034-D10`) over `examples/`, `src/test/babble/`, `src/test/batest/`, and `src/test/java/` to rewrite reassigned `def`s to `var`, uninitialized non-interface `def x: T?` to `var x: T? = null`, `export var` / public static `var` fixtures (e.g., `call_and_body_effects.batest.md`, `unique_and_borrow_ownership.batest.md`) to encapsulated private `var` + accessors, and reassigned `fn` parameters (e.g., `Test1.ba`) to `var` params or local `var`s; format with `-format -w`; add dedicated `def`/`var` positive & error tests across all declaration sites.

### `BIP034-I2` — `EffectChecker` Precision & REPL Soundness
* Close the §2 item 3 REPL determinism hole (verified in `ReplTestCase` and `EffectsTestCase`).
* Upgrade `EffectBodyWalker.readsMutableBabbleMember` to check per-field `member.isMutable()` on regular `class`es and `object`s.
* Simplify static `def` checks, `escapedCapturedLocals` (`var`-only), and `EffectAstExtractors` (`AlgebraicVerifier` / `SizeChangeTermination`).
* Add `.batest.md` tests under `src/test/batest/effects/` covering `def` vs. `var` fields on regular `class`es, `private var` statics, module-private `var` with exported `fn volatile`/`fn io` accessors, and REPL determinism.

### `BIP034-I3` — Pure Function & Class `NameRead` + First-Class MIR `NameRead` + Immutable `class` Field Loads
* Implement §6.2's inductive non-escaping module header, clean-REPL-input, and self-class definite-initialization analysis (`BIP034-D6`) in `HirBuilder` (and `MirLowerer`).
* Add `boolean mutable` to `HirVar`, `(boolean finalBinding, boolean definitelyDefined)` to `HirExpr.NameRead`, and generalize `HirExpr.ThisFieldGet.recordField()` → `immutableField()` (covering `record` fields and `def` fields on regular `class`es).
* Update `HirPurity.isPureNothrowTerminating` (`NameRead` returns `finalBinding && definitelyDefined`), `HirLicmPass`, and `HirDcePass`.
* Promote `NameRead` and `NameAssign` to first-class `MirInst.NameRead` / `MirInst.NameAssign` (`BIP034-D7`), assigning `PURE_NOTHROW` (`{pure}`), `PURE_MAY_THROW` (`{pure,throws}`), `READ_SHARED`, or `READ_SHARED_THROW` per §6.3, and `READ_FROZEN` for definitely-defined `def` fields on regular `class`es.
* Update `GvnCsePass` with epoch-free `PureNameReadKey` and exception-edge-guarded dominated `PURE_MAY_THROW` duplicate CSE; verify `LicmPass` hoists `{pure}` `name_read`s out of `io`/mutating loops.
* Regenerate canonical HIR/MIR goldens (`-Dbatest.updateIr=true`) and verify `inline_and_alpha_rename.batest.md` (`name_read Pair {pure}`) and `vec3.ba` (`name_read Vec3 {pure}`).

### `BIP034-I4` — Runtime Inline-Cache Fast Paths (`NameCache` & `SelectCache`)
* Upgrade `Bootstrap.NameCache` to cache the value of initialized final `Slot`s directly (§6.4).
* Upgrade `Bootstrap.SelectCache` to constant-fold both public static `def` members (`Vec3.zero`, `Interval.empty`, `AABB.empty`, static `def` methods) on `BabbleType` and initialized `def` exports on `Module` (§6.4).
* Benchmark `examples/raytracer-argon` and `Benchmarks` across `-O0`, `-O1`, and `-O2` (separate JVMs with `-Xms1g -Xmx1g -XX:+UseParallelGC`) and record before/after measurements.

### `BIP034-I5` — Value-Capture for Immutable `def` Locals & `ACC_FINAL` Constructors
* Update `Resolver`, `HirBuilder`, and `HirEmitter` so captured definitely-initialized `def` locals (including non-`var` `fn` parameters, `for` vars, and `catch` params) are captured by value rather than allocating a mutable `Cell` / `IntCell` / `DoubleCell` / `BoolCell` (§7.4).
* Generate typed `<init>(BabbleClass, ScriptRuntime, <headerDefParams...>)` constructors in `ClassGen` so immutable header `def` fields are initialized inside `<init>` and marked `ACC_FINAL` cleanly under JVMS verification (aligning with `BIP018-P2` / `JEP 500` and `BIP025-D11`).

---

## 9. Testing & Verification Criteria

1. **Grammar & formatting:**
   * `ParserGrammarTestCase` passes with zero semantic predicates and 100% pure `SLL` across all `.ba` and `.batest.md` sources.
   * `FormatterTestCase` verifies fixed-point formatting for `def` and `var` across all declaration sites (including `fn (var ...)` and `private var` statics); `-format -check src/test/babble examples src/main/java/org/babblelang/axioms` passes clean.
2. **Differential & IR golden parity:**
   * `mvn -B package -PO2` green across all 4 modes (`interpret`, `compile -O0`, `compile -O1`, `compile -O2` with `HirVerifier`, `MirVerifier`, and `CheckClassAdapter`).
   * `MirCanonicalPrinterTestCase` and `src/test/batest/compiler/*.batest.md` confirm `name_read Pair {pure}`, `name_read Vec3 {pure}` (after `import(...)` and pure `static` block), `name_read late {pure,throws}` (after arbitrary top-level call / active `static` block), `name_read counter {read:shared}`, epoch-free GVN across `io` calls, LICM out of `io` loops, and `load_field` `{read:frozen}` on regular `class` `def` fields.
   * `ReplTestCase` verifies that reassigning an earlier input's `def` fails with `Cannot assign to def : <name>`, while reading an earlier input's `var` requires `volatile` and reading an earlier clean input's `def` is `{pure}`.
   * `BabbleModulesTestCase` and `BabblecTestCase` verify `export var` rejection, encapsulated module-private `var` state, and AOT jar serialization of `ClassShape.MUTABLE`.
   * `ArchitectureBoundaryTestCase` and `RuntimeBoundaryTestCase` stay green (zero `ir` → `opt` or `runtime` → front-end violations, LOC ceilings intact).
3. **Coverage & end-to-end verification:**
   * Full 4-JVM 6-scope `mvn -q verify -Pbatest` passes `>= 80%` line and `>= 80%` branch coverage on all 6 scopes with 0 divergences.
   * `examples/raytracer-argon/raytracer.ba` renders byte-identical `examples/raytracer-argon/scene.ppm` (`1,021,340` bytes) across `-O0`, `-O1`, and `-O2`, with `Benchmarks` confirming improved or neutral runtime and compile-time performance.

---

## 10. Implementation & Benchmark Notes

All five phases (`BIP034-I1`–`BIP034-I5`) are implemented and verified across all four execution modes (`interpret`, `-O0`, `-O1`, `-O2` with `HirVerifier`, `MirVerifier`, and ASM `CheckClassAdapter`):

* **`examples/raytracer-argon/raytracer.ba` byte-identity:** renders `examples/raytracer-argon/scene.ppm` (`1,021,340` bytes) byte-for-byte identically at `-O0` (16.25 s), `-O1` (16.74 s), and `-O2` (14.34 s).
* **Isolated-JVM `Benchmarks` (`-Xms1g -Xmx1g -XX:+UseParallelGC`, one JVM per optimization level):**

| Benchmark | `-O0` (ms) | `-O1` (ms) | `-O2` (ms) | `-O2` vs `-O0` | `-O2` vs `-O1` |
|---|---:|---:|---:|---:|---:|
| `fib(25) : calls and arithmetic` | 3.04 | 2.52 | 0.87 | 3.49× | 2.90× |
| `opt : small helper inlining` | 3.04 | 0.59 | 0.60 | 5.07× | 0.98× |
| `opt : loop-invariant pure call (LICM)` | 10.14 | 3.93 | 3.94 | 2.57× | 1.00× |
| `mir : header LICM + GVN/CSE + predicate reorder` | 1.92 | 1.81 | 0.11 | 17.45× | 16.45× |
| `mir : branch-heavy math & AABB slab loop` | 0.68 | 2.47 | 0.38 | 1.79× | 6.50× |
| `mir : BVH-style interface dispatch` | 4.00 | 2.14 | 1.90 | 2.11× | 1.13× |
| `argon ray tracer : 64x36, 4 samples per pixel` | 73.77 | 72.48 | 61.74 | 1.19× | 1.17× |
| `argon ray tracer : compile its 15 modules` | 244.02 | 390.10 | 432.99 | 0.56× | 0.90× |

