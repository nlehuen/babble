# BIP008: removing dynamic typing

| | |
|---|---|
| **BIP** | 008 |
| **Date** | 2026-11-16 |
| **Status** | `BIP008-P1`–`BIP008-P7` implemented : static typing is the only mode, redundant internal `BIP003-D9` checks are removed (§7, §8), union types (`A | B`) and nullable types (`T?`, `any` vs `any?`) are implemented (§11), Java/Babble generics with `(...)` syntax are implemented (§5.2, §12), and call-site parameter inference (`BIP008-P7`, §4.9) is implemented. |
| **Replaces** | the gradual part of [bip003_type-system.md](bip003_type-system.md) (`BIP003-D1`–`BIP003-D9`) : `BIP003-D9` checks remain only at `as` casts, Java boundaries (including Java generic `TypeVariable`/`WildcardType` returns and fields), and member writes from Java. |
| **Scope** | the type checker, the grammar (`as`, `is` on built-in types, `|`, `?`, `null` in types, generic type parameters/arguments with `(...)`), the runtime checks, the tests, the examples and the docs. |

## 1. Summary

Babble is gradually typed today : what is unannotated and can't be inferred is `dynamic`, which
is assignable to and from everything, and declared types are enforced at runtime where a
`dynamic` value reaches them (`BIP003-D9`). This design removes `dynamic`. Every expression gets a static
type, and a program the checker cannot type is rejected before it runs.

`dynamic` is replaced by a **top type, `any`**, that is sound : anything can be put in an `any`,
but nothing can be done with one (no member, no call, no operator, not assignable to a narrower
type) until it is narrowed, by `is` in a condition or by an explicit, runtime-checked cast `as`.
`any` is what a Java `Object` is, and what an untyped binding is ; it is never what an
unannotated parameter is : those must be annotated, unless their context, their default value,
or (for non-escaping, non-exported, called callables) the calls to them (4.9) gives their type.

The change is made in phases (§8) behind a strict mode, so that the suite stays green at every
step and the default flips only once the tests, the examples and the README are migrated.

## 2. Goals and non-goals

Goals :

* No expression of an unknown type : every member, call and operator is checked before running.
* Fewer runtime checks : a boundary the checker proves needs none (`BIP003-D9` checks remain only at
  casts and at the Java boundary). It also simplifies unboxed arithmetic, and opens the way to
  direct calls and primitive fields (README, *Planned features* ; §7.1).
* Diagnostics in the style of today's : worded like the runtime errors they replace.

Non-goals, for this design :

* Generics for Babble functions and classes, union types, and nullable types (`T?`) : deferred in
  the initial `BIP008-P1`–`BIP008-P5` design, then added as `BIP008-P6a` (§11) and `BIP008-P6b` (§12).
* Subtyping between classes : there is still no class inheritance.

## 3. Where `dynamic` comes from today

From `TypeChecker` (67 uses of `Type.DYNAMIC`), the sources, grouped by what removes them :

| # | Source | Example | Today | Proposed (§) |
|---|---|---|---|---|
| S1 | unannotated parameter, no contextual type | `(a, b) -> ( a + b )` | `dynamic` | annotation required unless a default gives the type (4.1) ; from `BIP008-P7`, inferred from calls (4.9) |
| S2 | unannotated class header parameter or member | `class (def x) ( def y = null )` | `dynamic` | header parameter : as S1, from `BIP008-P7` inferred from constructor calls (4.2, 4.9) ; member : inferred from its initialiser and assignments (4.2, 4.3) |
| S3 | `def` initialised with `null`, or without a value | `def m = null`, `def m` | `dynamic` | inferred from its assignments (4.3) ; annotation required when there are none (4.1) |
| S4 | a reassigned unannotated `def` | `def i = 0 ; i = "a"` | `dynamic` | the join of its initialiser and every assignment ; error where they don't join (4.3) |
| S5 | join of `if` branches / `while` and `null` with unrelated types | `if c then ( 1 ) else ( "a" )` | `dynamic` | error where the value is used (4.4) |
| S6 | Java `Object`, interfaces, arrays, inaccessible classes | `list.get(0)` | `dynamic` | `any`, `JavaInstance(I)`, arrays (5) |
| S7 | Java overloads not resolved statically | `Math.abs(x)` with `x` unknown | `dynamic` | disappears with S1 ; else error (5.3) |
| S8 | implicits and script bindings | `println`, `args`, `engine.put("name", …)` | `dynamic` | signatures ; bindings `any` (6) |
| S9 | `super.m()` | `super.describe()` | `dynamic` | the default method's signature (4.5) |
| S10 | recursion and import cycles inference can't close | mutual recursion, `a.ba` ⇄ `b.ba` | `dynamic` | annotation required (4.6) |
| S11 | non-literal `import(name)` | `import(path)` | `dynamic` | `any` (6) |
| S12 | integer literal out of range | `2147483648` | `dynamic` (fails at runtime) | error (4.7) |
| S13 | the annotation `dynamic` | `def x : dynamic = …` | `dynamic` | removed ; `any` (4.8) |

In the repository today, 65 of the 181 function parameters of `src/test/babble` and `examples`
are unannotated (S1), and 7 test files route values through an identity function
(`def id = (v) -> ( v )`, 15 of them) to reach a runtime type error past the checker.

## 4. The language

### 4.1 Annotations required where nothing else gives the type

* A function literal's parameter needs an annotation, unless something else gives its type :
  * the function type the literal is checked against (contextual typing, as today :
    `list.removeIf((v) -> ( v > 2 ))` types `v` from `Predicate`,
    `def f : (n : int) : int = (n) -> ( n + 1 )` from the annotation) ;
  * its default value : `(sep = "")` is a `string`, `(threads = 1)` an `int` (a default of
    `null` gives no type) ;
  * from `BIP008-P7`, the calls to it (4.9).
* An unannotated `def` is typed by inference over its initialiser and every assignment to it
  (4.3), so `def m = null ; m = "a"` is a `string`. It needs an annotation only when that gives
  no type : no value and no assignment, or nothing but `null`.
* A function's result is inferred from its body, as today.

Diagnostic : `Missing type : a` (the parameter or def), at its declaration.

### 4.2 Members

A class header parameter is typed as a function's parameter is (4.1) : by its annotation, its
default value, or from `BIP008-P7` the constructor calls `C(…)` (4.9). A member is typed by its
annotation, or by inference over its initialiser and assignments, as a `def` is (4.3), and
`obj.x = v` is checked against that type. Today a member without annotation is `dynamic`
because `obj.x = …` can change it (`memberType`) ; checking writes against the inferred type
removes that reason. Statics alike.

### 4.3 Reassignment

An unannotated `def`'s type is the join (4.4) of its initialiser's type and the types of every
value assigned to it, `x = v` and `x op= v` alike, wherever they are in its scope :

* `def x = 1 ; x = 1.5` : `int` and `double` join to `double`, so `x` is a `double` from its
  definition on, and its initial `1` is converted to `1.0` (4.4).
* `def m = null ; m = "a"` : `null` and `string` join to `string`.
* `def i = 0 ; i = "a"` : `int` and `string` don't join, `Type error : expected int, got string`
  at the assignment, `expected` being the type of the values before it in source order.

An assigned value may depend on the def itself (`x = x + 0.5`) : the checker iterates the join
until it is stable, which it is after one widening at most, the only joins that change a type
being `int` to `double` and `null` to a type that admits it (4.4). Because an assignment later
in scope (such as `i = i / 2`, since `/` yields `double`) widens `i` from its definition on, it
can change earlier uses (`println(i)`, `i is int`, `list.get(i)`) ; diagnostics caused by a
widened binding name the widening site (`i is double because of the assignment at line N`). An
annotated `def` is not inferred : assignments must fit its annotation, as today.

Inference no longer depends on "never reassigned" ; flow-sensitive narrowing still does (a
narrowed binding that can be reassigned loses its narrowing, as today). A member is inferred the
same way over its initialiser and the assignments the checker sees (`this.x = …` in its class,
`obj.x = …` where `obj`'s class is known) ; one assigned through a receiver whose class isn't
known statically can't be, which with `dynamic` gone means never : every receiver has a type.

### 4.4 The value of `if` and `while`

A value is only required to have a type where it is used : assigned, passed, returned, an
operand, or the last expression of a block whose value is used. `if c then ( 1 ) else ( "a" )`
as a statement is fine ; as a value it is `Type error : the branches of if have different types :
int and string`. Today's joins stay : `int` and `double` give `double`, `T` and `null` give `T`
when `T` admits `null`. A join never introduces `any` implicitly ; however, when an `if` has an
expected type `T` (such as `def v : any = if c then ( 1 ) else ( "a" )`), each branch is checked
against `T` instead of being joined independently. An `if` without `else` used as a value is `T`
joined with `null`, so an error for `int`, `double` and `bool` (`if c then ( 1 )` has no type).

Where an `int` joins to `double` — the branches of an `if`, a loop's value, a def's assignments
(4.3), a parameter's arguments (4.9) — the value is **converted** to a `double`, in both
backends, so that a static `double` is always a runtime `Double` (`BIP008-D7`). Today it isn't :
`def w = if c then ( 1 ) else ( 2.0 )` is typed `double` and holds the int `1`, so `w + w` is `2` ;
only an annotated def converts (`def v : double = …` holds `1.0`, through its runtime check).

### 4.5 `super`

`super.m()` and `super(I).m()` are typed by the default method they call, which the analysis
already resolves (`ScopeAnalysis.superTarget`).

### 4.6 Recursion and cycles

Where inference can't close (mutually recursive functions, which today fall back to `dynamic`
in the second pass, and files in an import cycle, `dynamic` to those analysed before them), the
types needed must be annotated : `Missing type : f (recursive)`, `Missing type : x (import
cycle with b.ba)`. Self-recursion through `recurse` keeps its two-pass inference.

### 4.7 Literals

An integer literal out of the `int` range is an error, `Integer out of range : 2147483648`,
instead of a runtime `NumberFormatException` (the negated `-2147483648` stays an `int`).

### 4.8 `any`, `is` and `as`

* `any` is a type name, assignable from every type ; an `any` is assignable to nothing else, and
  has no members, calls, arithmetic, or ordering comparisons (`Not a scope : x`, `x is not
  callable`, `Not a number : x`, `Not comparable : x and y`, as for other types that lack them).
  It remains allowed in equality (`==`, `!=`, `same(a, b)`), as the right operand of string
  concatenation (`"x = " + v`), when passed to `any` or Java `Object` parameters (`print`,
  `println`, `assert`'s message, `classof`), and in `is` and `as`.
* `x is T` narrows `x` to `T` in the branch where it holds, as it does for classes today, and is
  extended to the built-in types : `x is int`, `x is string`… (today `Not a type : int`). It
  keeps its existing precedence (looser than comparisons, tighter than `and`/`or`) and stays
  an expression on the right (`def T = Point ; x is T` keeps working), with built-in type names
  resolved only when directly on the right of `is` so `int(x)` (the conversion) is unaffected.
* `e as T` takes a `type` on the right and binds tighter than comparisons and looser than
  `+`/`-` (`a + b as int == 1` is `((a + b) as int) == 1`). It evaluates to `e`'s value if it is
  a `T`, else a runtime error worded as today's `BIP003-D9` checks, `Type error : expected string, got
  int`. Its static type is `T`. The cast is an error when `e`'s type can never be a `T`
  (`1 as string`), and a warning when it always is. Only types `Types.check` can enforce at
  runtime are valid targets (built-in types, Babble classes, Java classes/interfaces/arrays,
  and `any`) ; function types are erased at runtime and rejected statically (`Cannot cast to a
  function type`). `null` follows `BIP003-D2`/`BIP003-D9` rules : `null as string` and `null as C` succeed, while
  `null as int`, `null as double` and `null as bool` fail at runtime.
* `dynamic` is no longer a type name (`Unknown type : dynamic`).

`any` differs from `dynamic` in one way only, but it is the whole point : the checker never
assumes anything of an `any`, so a program that type-checks has no type error at runtime except
at an `as`, or across the Java boundary (5).

### 4.9 Parameters inferred from calls (`BIP008-P7`)

An unannotated parameter with no default and no contextual type is typed by inference over the
calls to its function, as a `def` is over its assignments (4.3) : its type is the join of the
arguments the checker sees passed to it.

```
def area = (w, h) -> ( w * h )
area(2, 3)
area(1.5, 2)          ;; w : double, h : int ; the result is a double
area("x", 1)          ;; Type error : expected double, got string, at this call
```

* It applies where every call is visible : a function bound by a `def` (or a package's def) and
  only ever called by that name ; a method of a class or object called as `obj.m(…)` or
  `this.m(…)` with a receiver of known class (every receiver has one, `dynamic` being gone) ; a
  class header parameter, through the constructor calls `C(…)`.
* It doesn't apply, and an annotation is required (`Missing type : a`), when calls can be
  hidden : when the function escapes as a value (passed as an argument, stored, returned,
  assigned), when it is exported from a module (`export def`, `export package`, or a public or
  static member of an exported class, since callers are in files analysed after it), when a
  method implements or overrides one on an interface (Babble or Java) or is an interface default
  method, or for a class's header parameters when the class itself is used as a value
  (`def K = Point ; K(1, 2)`). A function never called (or called only with `null` when it has
  no default) needs annotations too, there being nothing to infer from.
* Named and default arguments bind as at runtime (`Arguments`) : the argument that binds a
  parameter is the one inferred from.
* Calls, parameters and results depend on each other (a call's argument may be another call's
  result, or the parameter of an enclosing function) : the checker iterates over the file until
  the types are stable, as for 4.3. A join only widens (`int` to `double`, `null` to a type
  that admits it), so this ends ; a recursive function's own calls take part like any other.
* A conflict is reported at the call that brings it, worded as an assignment's
  (`Type error : expected double, got string`), `expected` being the join of the calls before
  it in source order. Diagnostics caused earlier in the file by a widening call name the
  widening site (`w is double because of the call at line N`).

Inference from the uses of a parameter in the function's body (Hindley–Milner style) is out of
scope : `(a, b) -> ( a + b )` is ambiguous between `int`, `double`, `string` and any class with a
`plus`, typing `a.x` would need structural types, and without generics the result would be
monomorphic, so it would rarely type more than calls do, with errors harder to explain.

## 5. Java

### 5.1 Results and fields

* `Object` is `any`. `list.get(0)` is `string?` on `java.util.List(string)` (5.2).
* An interface is `JavaInstance(I)`, no longer `dynamic`. The reason it is `dynamic` today (a
  Babble instance passed to Java comes back as itself where `I` is declared) is kept by making
  member lookup on `JavaInstance(I)` accept both : the members are `I`'s methods, whichever kind
  of object holds them (`Faces` already makes a Babble instance answer `I`'s methods).
* An array is `Type.Array(T)` (`array of T`, promoted from `Type.JavaArray(T)` in [BIP020](bip020_array-types.md) : `String[]` is `array of string`), assignable to the
  Java parameters that take that array type (§2.5 of `BIP020`), with `size()`, `get(i)` and
  `set(i, v)` (`BIP020` replaced `length` and `java.lang.reflect.Array.get(a, i)`).
* A class Babble can't access directly (`List.of()`'s) is typed by the accessible type it is
  reached through (`JavaTypes.accessible`), as calls already are.

### 5.2 Generics

Reflection gives the generic signatures (`List<E>.get` returns `E`). From `BIP008-P6b` (§8, §12), Babble
types `java.util.List(string)` and `java.util.Map(string, int)` annotations and infers type
arguments from call arguments and expected types (`java.util.ArrayList()`,
`java.util.Collections.emptyList()`), enforcing Java `TypeVariable` bounds. Generic Java methods
and fields whose reflection return/field type is a `TypeVariable` or `WildcardType` — such as
`Map(string, int).get(k)` returning `null` on a missing key or `List(string).get(0)` — widen to
nullable `T?` and record a runtime `Types.check` boundary check when non-`any?`. Uninferred or
raw generic annotations report `Missing type arguments` or `Missing type : cannot infer type
arguments for ...`.

### 5.3 Overloads

With S1 gone, every argument has a type, and the checker picks the overload the runtime picks
(`mostSpecific`), or reports `No applicable overload` / an ambiguity, as it does today when the
arguments are known.

## 6. Implicits, bindings and dynamic imports

* `print`/`println` take any number of `any` ; `assert(c : bool, message : any = null)` ; `same(a :
  any, b : any) : bool` ; `classof(v : any) : any` (a class value of unknown class) ; `int(v :
  any) : int` and `double(v : any) : double` stay as they are (they already check at runtime).
* Script bindings (`engine.put(…)`, `args`) are `any` : a `CompiledScript` is compiled before its
  bindings are known, and runs with whichever it is given. `args` is the exception : `Main`
  always binds a `java.util.List` of strings, so it is declared `java.util.List(string)`.
* `import(name)` with a name that isn't a string literal is `any` ; static imports keep their
  module types.
* Interactive sessions (REPL, `BIP008-D5`) : each input in an `AnalysisSession` is analysed and compiled
  before later inputs are known (`bip004_repl.md` §2). A `def`'s or member's inference (4.2, 4.3)
  therefore closes at the end of the input that defines it, and assignments in later inputs are
  checked against that type rather than widening it (`AnalysisSession.reassigned` still drops
  flow-sensitive narrowing across inputs, not the inferred type). Likewise, in `BIP008-P7`, call-site
  inference (4.9) closes at the end of the input that defines the function ; a function defined
  at the prompt with no calls in that same input needs annotations (`Missing type : f`).

## 7. Runtime

* `Types.check` stays for `as` and for external boundaries. In `BIP008-P5`, the `BIP003-D9` checks at Babble-to-Babble
  parameters, results, `def`s, unqualified assignments, and `this.x` direct field writes are removed,
  keeping `Values.toDouble` where `widensToDouble` marks an `int`→`double` conversion.
* External boundaries retain runtime checks :
  * calls and field reads exiting Java : `JavaTypes.checkBoxed` (in `JavaMethod`, `JavaLinker`,
    `JavaClass`, and `JavaDefaultCall`) checks boxed primitive returns and fields (`Integer`,
    `Double`, `Boolean`) that map to non-nullable Babble `int`, `double`, `bool`, failing with
    `Type error : expected int/double/bool, got null` when Java returns `null` ;
  * calls entering Babble from Java : `JavaTypes.checkParameters` (in `Faces.FaceHandler` and
    `Faces.FunctionHandler`) checks arguments typed from the Java interface method before invoking
    Babble code ;
  * member and static writes through `HasMembers` (`BabbleInstance.setMember`, `BabbleType.setMember`) :
    `ClassShape` retains declared member and static types so external Java callers and `any` writes
    remain checked, while `CodeGenerator`'s direct `this.x = …` `PUTFIELD` writes omit checks.
* The interpreter stays the reference : both modes give the same results, as today. Fewer
  checks change no result of a program that type-checks ; the conversion of an `int` joined to
  `double` (4.4, `BIP008-D7`) does : `w + w` above becomes `2.0`. It is the one change of behaviour of a
  program that type-checks (others may now be rejected before running, e.g. 4.7).
* Errors a program could reach before and no longer can (`Not a number : x` on a Babble value,
  `x is not callable`, …) stay in the runtime, as the backstop for Java values and casts.

### 7.1 Unboxed arithmetic *(Implemented in `RM001`)*

`RM001` ([bip001_bytecode-compiler.md §19](bip001_bytecode-compiler.md#19-design--implementation-notes-rm001--typed-primitive-lowering-and-static-dispatch))
implements unboxed `int`, `double`, and `bool` arithmetic, locals, closure cells (`IntCell`/`DoubleCell`/`BoolCell`),
class fields (`I`/`D`/`Z`), `$spec` specialized entry points, typed `invokedynamic` calls, and direct static Java
dispatch by reading converged `TypeChecker` results directly from `ScopeAnalysis` (without a standalone `Unboxing` pass) :

* `ScopeAnalysis.primitiveKind(node)` and `declarationKind(node)` expose exact non-nullable `int`, `double`, and `bool`
  types (while nullable `int?`/`double?`/`bool?` and unions stay boxed at storage boundaries and unbox after narrowing);
  the left operand's static type skips `instanceof BabbleInstance` checks on arithmetic operators.
* A definite-initialization bitmask (`$defined` / `BabbleInstance.isMemberDefined`) preserves exact
  `Member used before definition` checks for unboxed body fields when `this` escapes during construction;
  floating-point comparisons use `Double.compare` to match `Operators` on `NaN` and signed zero.
* Measured in `BIP008-P5` : removing redundant internal `BIP003-D9` checks cut `examples/raytracer-argon/raytracer.ba`
  from 78.7 s to 58.3 s (byte-identical to `scene.ppm`) and sped up `Stack.ba` in `Benchmarks` from 1.7 ms to 1.3 ms.
* Measured in `RM001` (uncontended, JDK 21) : unboxed lowering + static Java dispatch further cut `argon` from 59.3 s to
  **46.1 s**, `fable` from 72.9 s to **67.9 s**, `fib(25)` to **1.7 ms**, `while loop` to **0.1 ms**, and `ArrayList`
  interop to **2.0 ms** (`bip001_bytecode-compiler.md` §19).

## 8. Plan

Each phase keeps `mvn package` green and is a pull request of its own. To keep default mode
backward-compatible while scripts are migrated, `BIP008-P1`–`BIP008-P3` gate the new strict rules and inference
that can reject existing code behind `babble.typing=strict` :

* **`BIP008-P1`, `any`, `is` on built-in types, `as`, and `if`/`while` numeric conversion.** *(Implemented)* Grammar (`as`
  binds tighter than comparisons, looser than arithmetic : `a + b as int` is `(a + b) as int`),
  `Type.ANY`, `Type.JavaArray(T)` (5.1), both backends (`as` compiles to `Types.check`),
  formatter, tests. `dynamic` still exists. Also the conversion of an `int` joined to `double`
  in `if` and `while` (4.4, `BIP008-D7`), which needs no strict mode : the checker records where those
  joins widen, and both backends convert there. Verify that `argon` still renders `scene.ppm`
  byte for byte.
* **`BIP008-P2`, strict mode.** *(Implemented)* `babble.typing=strict` (engine attribute and system property) enables the
  strict typing and inference of §4–§6 : parameter defaults (4.1), member inference (4.2),
  inference over all assignments with its `int`→`double` conversion (4.3), `super` typing (4.5),
  `JavaInstance(I)` member lookup (5.1), implicit signatures and `any` bindings (§6), and errors
  for every unresolved source of §3 (`Missing type` for unannotated parameters, defs, cycles and
  imports, out-of-range literals 4.7, unknown `dynamic` 4.8). Off by default ; a test runs every
  `.ba` script and example in strict mode and lists which fail, as the migration's to-do list.
* **`BIP008-P3`, migration.** *(Implemented)* Annotate the tests and the examples ; rewrite the tests that reach runtime
  type errors through `def id = (v) -> ( v )` with `as` (the error still exists, at the cast) or
  turn them into static diagnostics ; the README's *Optional types* section and the tour ; argon
  must still render `scene.ppm` byte for byte.
* **`BIP008-P4`, flip the default.** *(Implemented)* Strict is the only mode ; `dynamic` and the flag are removed ;
  `TypeChecker`'s gradual paths (`isUnknown`, the `dynamic` cases of `assignable`) go.
* **`BIP008-P5`, runtime checks.** *(Implemented)* Remove the redundant internal `BIP003-D9` boundary checks while keeping checks at
  `as` casts, Java boundaries (`JavaTypes.checkBoxed`, `Faces`), and `BabbleInstance`/`BabbleType.setMember` ;
  verify compiled bytecode omits internal `Types.check` / `TYPE*` fields (`BabbleCompilerTestCase`) and measure
  with `Benchmarks` and `argon` (§7.1).
* **`BIP008-P6a`, union types (`A | B`) and nullable types (`T?`).** *(Implemented)* Separates nullability in
  the type system (`T` is non-nullable, including `any`; `T?` is `T | null`; `any?` is the nullable
  top type; `null` is allowed in type annotations; `( ... )` parenthesises complex types such as
  `((a : int) : int)?`), joins `if`/`while`/`return` branches to unions (and `int?` + `double` to
  `double?` with conversion), narrows via `else` subtraction, `== null`/`!= null`/`<> null`, truthiness,
  and `while` loop invalidation, and updates `Types` descriptors for unions and non-nullable checks (§11).
* **`BIP008-P6b`, Java generics (5.2) and Babble generics (§12).** *(Implemented)* Adds type parameters
  and arguments using `(...)` syntax (`def Box(T) = class ...`, `def id(T) = ...`,
  `java.util.List(string)`), call-site and contextual type inference (including `int` → `double`
  widening when safe and uncalled generic function values), Java `TypeVariable` bound checking,
  covariant parameterized subtyping, raw `is`/`as` narrowing with runtime erasure, and runtime
  `Types.check` boundary checks on Java generic `TypeVariable`/`WildcardType` returns and fields.
* **`BIP008-P7`, parameters inferred from calls** (4.9). *(Implemented)* Infers unannotated parameters of
  non-exported, non-escaping, called functions, class headers, and non-interface methods from
  their call sites (supporting named/default args, mutual/self recursion, source-order conflict
  reporting, widening line notes, and end-of-input closure in REPL sessions, §6), and removes
  redundant parameter annotations across the tests and examples while keeping required annotations
  on exported, escaping, interface, and uncalled definitions.

## 9. Decisions

The questions of the draft, as decided :

* **`BIP008-Q1` → `BIP008-D1`. Unannotated parameters with no contextual type** : an annotation is required (4.1),
  unless the parameter has a default value. Then, in `BIP008-P7`, a parameter of a function whose calls
  are all visible is inferred from them (4.9) ; inference from uses in the body is out of scope.
* **`BIP008-Q2` → `BIP008-D2`. Numeric widening on reassignment** : inference over all assignments (4.3) :
  `def x = 1 ; x = 1.5` makes `x` a `double`.
* **`BIP008-Q3` → `BIP008-D3`. `if` without `else` as a value** : an error where the value is used and its type admits
  no `null` (`int`, `double`, `bool`), as in 4.4.
* **`BIP008-Q4` → `BIP008-D4`. Arrays** : a `JavaArray(T)` type now (5.1).
* **`BIP008-Q5` → `BIP008-D5`. The REPL** : the same typing rules as scripts, with inference (4.3, 4.9) closed at the
  end of the input that defines the binding or function (§6).
* **`BIP008-Q6` → `BIP008-D6`. Cast syntax** : `e as T` (4.8).
* **`BIP008-Q7` → `BIP008-D7`. Converting an `int` joined to `double`** : yes (4.4). A static `double` is always a
  runtime `Double`, as the unboxed-arithmetic work needs (7.1). It changes the result of programs
  like `w + w` in 4.4 (`2` becomes `2.0`), the one change of behaviour this design makes to a
  program that type-checks.

## 10. Testing

* `BIP008-P1` : golden tests of `as` and `is` diagnostics in `TypeCheckerTestCase`, `DifferentialTestCase`
  snippets for failing casts, a `.ba` script, and `argon` rendering `scene.ppm` byte for byte.
* `BIP008-P2` : one golden test per source of §3 in strict mode, and the strict-mode survey of every
  script (§8).
* `BIP008-P3`–`BIP008-P4` : the whole suite in strict mode ; `ExamplesTestCase` compiles the examples in it.
* `BIP008-P5` : `BabbleCompilerTestCase` checks the boundary checks are gone from the generated code ;
  benchmarks before and after.
* `BIP008-P6a` : `FormatterTestCase`, `TypeCheckerTestCase#testP6aUnionAndNullableTypes`, `TypeCheckerTestCase#testSafeCallOperator`, `BabbleCompilerTestCase`,
  `DifferentialTestCase`, `src/test/babble/UnionsAndNullables.ba`, and `argon` rendering `scene.ppm` byte for byte.
* `BIP008-P6b` : `FormatterTestCase`, `TypeCheckerTestCase#testP6bGenerics`, `BabbleCompilerTestCase`,
  `BabbleModulesTestCase`, `DifferentialTestCase`, `src/test/babble/Generics.ba`, and `argon`
  rendering `scene.ppm` byte for byte.
* `BIP008-P7` : golden tests of call-site inference in `TypeCheckerTestCase` (widening across calls,
  named and default arguments, methods and constructors, recursion, a conflicting call, and
  `Missing type` for escaping, exported and never-called functions) ; removing the annotations
  inference makes redundant from the tests and examples must leave the suite green.

## 11. Union types (`A | B`) and nullable types (`T?`) *(Implemented in `BIP008-P6a`)*

Originally Babble followed `BIP003-D2` : `int`, `double` and `bool` rejected `null`, while `string`, Babble
classes, function types, Java types and `any` admitted it. `BIP008-P6a` separates nullability in the type
system itself and introduces union types (`A | B`) :

* **`T` vs. `T?`.** Every plain type `T` (`int`, `double`, `bool`, `string`, Babble classes,
  Java classes, function types, `any`) denotes a **non-nullable** value ; `T?` is shorthand for
  `T | null` (`string?`, `Point?`, `int?`, `any?`, `((x : int) : int)?`). `null` is also a type
  assignable to any `T?` and to no `T` ; `T` is assignable to `T?`, and `T?` is not assignable to
  `T` without narrowing.
* **`null` and `T?`.** Because `T?` is the union `T | null`, the value `null` is a member of every
  nullable type : `null is T?` is `true` and `null as T?` succeeds for any `T`, including an
  interface the declared class never implements (`def d: Disk? = null` then `d as Named?`), while
  `null is T` is `false` and `null as T` fails (`expected T, got null`). Statically, a cast is rejected only
  when the two types share no value, and `null` is a shared value once both are nullable : `i as string?` is accepted for `i: int?` (and yields `null`), while
  `i as string` is `Type error : expected string, got int?` before running.
* **Union types (`A | B`).** Unions flatten nested unions, deduplicate members, absorb `any`
  (`any` or `any?`), and canonicalize `null` last (`T | null` formats as `T?`). `if`, `while`
  and `return` join branches into unions (while joining `int`/`int?` with `double`/`double?`
  into `double`/`double?` and widening `int` to `double` in both backends ; `int | double`
  preserves `Integer`). Reassignment inference (4.3) continues to widen only `T <-> null` and
  `int <-> double`.
* **Using unions and `T?`.**
  * A value of type `T?` or mixed union has `==`, `!=`, `<>`, `same`, string concatenation (`+`),
    `is`, `as`, and `any?` parameters; members, operator methods, numeric arithmetic (`int | double`),
    comparisons and calls require every member of the union to support them safely.
  * Safe navigation (`a?.x`, `a?.f(...)`) selects or calls on a receiver whose static type can be
    `null` : if the receiver evaluates to `null` at runtime, the expression short-circuits to
    `null` (evaluating the receiver once and skipping member lookup and call arguments) ; otherwise
    it performs the selection or call and yields `T?` (or `B?` for a method returning `B`). Using
    `?.` on a provably non-null receiver is a compile-time error (`Not nullable : …`), as is
    `super?.m()` or assignment `a?.x = v` ; parenthesized `(a?.f)(...)` evaluates `a?.f` to a
    nullable function value (`not callable`).
  * Flow-sensitive narrowing refines local bindings and parameters through `x is T` (and subtracts
    `T` in the `else` branch), `x == null` / `x != null` / `x <> null`, truthiness (`if x`, `and`,
    `or`, `not`), and `while` conditions (invalidated if the variable is reassigned or captured across
    function boundaries).
* **`int?`, `double?`, `bool?` and Java `Integer`.** Nullable primitive types reject primitive Java
  overloads (`Math.abs(n)` when `n : int?`), keeping boxed wrappers distinct from unboxed primitives
  until narrowed, while non-nullable `int`, `double` and `bool` stay guaranteed non-null.
* **Runtime `Types` descriptors.** `Types.parse` handles `union`, `null`, `any` (`value != null`),
  and non-nullable `Builtin`, `FunctionType`, `JavaClassType`, and `NominalType` descriptors, with
  `UnionType.convert` preferring a non-converting matching member before widening.

## 12. Generics *(Implemented in `BIP008-P6b`)*

`BIP008-P6b` adds parametric polymorphism for Babble functions, classes, interfaces, and methods alongside
Java generic classes, interfaces, and methods, using parentheses `(...)` exclusively (no square
brackets `[]` anywhere in Babble syntax) :

* **Type parameters on `def`.** A `def` whose value is a function literal, class/value-class literal,
  interface literal, or an interface member declaration can declare type parameters before `=`:
  `def id(T) = (x : T) : T -> ( x )`, `def Box(T) = class (def value : T) ( ... )`,
  `def Container(T) = interface ( def get : () : T )`, `def map(U) = (fn : (x : T) : U) : Box(U) -> ...`.
  Duplicate type parameter names report `Name already defined : T`, and `static (...)` blocks of a
  generic class do not see the class's type parameters. Function type annotations always require
  named parameters: `(x : T) : U`.
* **Type arguments in annotations.** Parameterized types are written with parentheses :
  `Box(int)`, `Pair(string, Node(T)?)`, `java.util.List(string)`, `java.util.List(int | string)?`.
  Omitting type arguments on a generic class/interface reports `Missing type arguments : C`, and
  mismatching counts report `Wrong number of type arguments : expected N, got M`.
* **Call-site and contextual inference.** Type arguments are not written at call or construction sites ;
  they are inferred from arguments and expected types (including uncalled generic function values
  such as `def idInt : (x : int) : int = id`, Java SAM lambdas, and contextual `int` → `double`
  widening when safe). Calls or constructions whose type parameters cannot be inferred fail with
  `Missing type : cannot infer type arguments for ...`.
* **Type variables, nullability, and covariance.**
  * Unconstrained Babble type variables `T` can hold nullable types when instantiated, so `T` is
    assignable to `T`, `T?`, `any?`, or unions containing them, and is **not** assignable to
    non-nullable `any`.
  * Parameterized Babble and Java types are covariant in their type arguments : `Box(int)` is
    assignable to `Box(int | double)` and `Box(any)`, and `java.util.List(int)` to
    `java.util.List(any)` (without implicit `int` → `double` conversion inside containers).
* **Type erasure, `is`, and `as`.** Type arguments are erased at runtime. `is` and `as` take raw
  class/interface names (`x is Box`, `x is java.util.List`, `x as Box`), narrowing/typing them as
  `Box(any?)` / `java.util.List(any?)` while `is` preserves narrower covariant type arguments when
  already known statically. Parameterized `as` targets (`x as Box(int)`) and `x as T` casts are
  rejected statically (`Cannot cast to parameterized type`, `Cannot cast to type parameter`).
* **Java generics.** Reflection `TypeVariable` bounds are checked on Java calls/constructions
  (`type argument X does not satisfy bound ...`). Java generic methods and fields whose reflection
  type is a `TypeVariable` or `WildcardType` widen to `T?` and record a runtime `Types.check`
  boundary check when non-`any?`.
