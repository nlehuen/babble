# Design: removing dynamic typing

| | |
|---|---|
| **Status** | Accepted : Q1 to Q6 of §9 are decided, Q7 is open. Nothing implemented yet ; the plan is §8. |
| **Replaces** | the gradual part of [type-system.md](type-system.md) (T1–T9), once implemented. |
| **Scope** | the type checker, the grammar (`as`, `is` on built-in types), the runtime checks, the tests, the examples and the docs. |

## 1. Summary

Babble is gradually typed today : what is unannotated and can't be inferred is `dynamic`, which
is assignable to and from everything, and declared types are enforced at runtime where a
`dynamic` value reaches them (T9). This design removes `dynamic`. Every expression gets a static
type, and a program the checker cannot type is rejected before it runs.

`dynamic` is replaced by a **top type, `any`**, that is sound : anything can be put in an `any`,
but nothing can be done with one (no member, no call, no operator, not assignable to a narrower
type) until it is narrowed, by `is` in a condition or by an explicit, runtime-checked cast `as`.
`any` is what a Java `Object` is, and what an untyped binding is ; it is never what an
unannotated parameter is : those must be annotated, unless the context gives their type.

The change is made in phases (§8) behind a strict mode, so that the suite stays green at every
step and the default flips only once the tests, the examples and the README are migrated.

## 2. Goals and non-goals

Goals :

* No expression of an unknown type : every member, call and operator is checked before running.
* Fewer runtime checks : a boundary the checker proves needs none (T9 checks remain only at
  casts and at the Java boundary). It also simplifies unboxed arithmetic, and opens the way to
  direct calls and primitive fields (README, *Planned features* ; §7.1).
* Diagnostics in the style of today's : worded like the runtime errors they replace.

Non-goals, for this design :

* Generics for Babble functions and classes, and union types. They make some programs typable
  without casts ; §7 describes what this design needs instead, and §8 places them after it.
* Subtyping between classes : there is still no class inheritance.

## 3. Where `dynamic` comes from today

From `TypeChecker` (67 uses of `Type.DYNAMIC`), the sources, grouped by what removes them :

| # | Source | Example | Today | Proposed (§) |
|---|---|---|---|---|
| S1 | unannotated parameter, no contextual type | `(a, b) -> ( a + b )` | `dynamic` | annotation required (4.1) |
| S2 | unannotated class header parameter or member | `class (def x) ( def y = null )` | `dynamic` | inferred from the initialiser, else annotation required (4.2) |
| S3 | `def` initialised with `null`, or without a value | `def m = null`, `def m` | `dynamic` | annotation required (4.1) |
| S4 | a reassigned unannotated `def` | `def i = 0 ; i = "a"` | `dynamic` | typed by its initialiser ; assignments checked (4.3) |
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
  * from P7, the calls to it (4.9).
* An unannotated `def` is typed by inference over its initialiser and every assignment to it
  (4.3), so `def m = null ; m = "a"` is a `string`. It needs an annotation only when that gives
  no type : no value and no assignment, or nothing but `null`.
* A function's result is inferred from its body, as today.

Diagnostic : `Missing type : a` (the parameter or def), at its declaration.

### 4.2 Members

A class header parameter needs an annotation ; a member is typed by its annotation or its
initialiser, as a `def` is (4.1), and `obj.x = v` is checked against that type. Today a member
without annotation is `dynamic` because `obj.x = …` can change it (`memberType`) ; checking
writes against the inferred type removes that reason. Statics alike.

### 4.3 Reassignment

An unannotated `def`'s type is the join (4.4) of its initialiser's type and the types of every
value assigned to it, `x = v` and `x op= v` alike, wherever they are in its scope :

* `def x = 1 ; x = 1.5` : `int` and `double` join to `double`, so `x` is a `double` from its
  definition on (and its initial `1` is `1.0` where a `double` is expected).
* `def m = null ; m = "a"` : `null` and `string` join to `string`.
* `def i = 0 ; i = "a"` : `int` and `string` don't join, `Type error : expected int, got string`
  at the assignment, `expected` being the type of the values before it in source order.

An assigned value may depend on the def itself (`x = x + 0.5`) : the checker iterates the join
until it is stable, which it is after one widening at most (`int` to `double`, `T` to nullable
`T`). An annotated `def` is not inferred : assignments must fit its annotation, as today.

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
when `T` admits `null`. An `if` without `else` used as a value is `T` joined with `null`, so an
error for `int`, `double` and `bool` (`if c then ( 1 )` has no type).

Where an `int` joins to `double` — the branches of an `if`, a loop's value, a def's assignments
(4.3), a parameter's arguments (4.9) — the value is **converted** to a `double`, in both
backends, so that a static `double` is always a runtime `Double` (Q7). Today it isn't :
`def w = if c then ( 1 ) else ( 2.0 )` is typed `double` and holds the int `1`, so `w + w` is `2` ;
only an annotated def converts (`def v : double = …` holds `1.0`, through its runtime check).

### 4.5 `super`

`super.m()` and `super(I).m()` are typed by the default method they call, which `TypeChecks`
already resolves (`superTarget`).

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
  has no members, calls or operators (`Not a scope : x`, `x is not callable`, `Not a number :
  x`, as for other types that lack them).
* `x is T` narrows `x` to `T` in the branch where it holds, as it does for classes today, and is
  extended to the built-in types : `x is int`, `x is string`… (today `Not a type : int`). The
  built-in type names become values only on the right of `is` and `as`, not in general
  expressions, so `int(x)` (the conversion) is unaffected.
* `e as T` is a cast : `e`'s value if it is a `T`, else a runtime error worded as today's T9
  checks, `Type error : expected string, got int`. Its static type is `T`. The cast is an error
  when `e`'s type can never be a `T` (`1 as string`), and a warning when it always is.
* `dynamic` is no longer a type name (`Unknown type : dynamic`).

`any` differs from `dynamic` in one way only, but it is the whole point : the checker never
assumes anything of an `any`, so a program that type-checks has no type error at runtime except
at an `as`, or across the Java boundary (5).

### 4.9 Parameters inferred from calls (P7)

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
* It doesn't apply, and an annotation is required (`Missing type : a`), when the function
  escapes : used as a value other than in a call (passed as an argument, stored, returned,
  assigned), or exported from a module (`export def`, or a member of an exported class) : a
  module's interface is annotated, since its callers are in files analysed after it. A function
  never called needs annotations too, there being nothing to infer from.
* Named and default arguments bind as at runtime (`Arguments`) : the argument that binds a
  parameter is the one inferred from.
* Calls, parameters and results depend on each other (a call's argument may be another call's
  result, or the parameter of an enclosing function) : the checker iterates over the file until
  the types are stable, as for 4.3. A join only widens (`int` to `double`, `T` to nullable `T`),
  so this ends ; a recursive function's own calls take part like any other.
* A conflict is reported at the call that brings it, worded as an assignment's
  (`Type error : expected double, got string`), `expected` being the join of the calls before
  it in source order.

Inference from the uses of a parameter in the function's body (Hindley–Milner style) is out of
scope : `(a, b) -> ( a + b )` is ambiguous between `int`, `double`, `string` and any class with a
`plus`, typing `a.x` would need structural types, and without generics the result would be
monomorphic, so it would rarely type more than calls do, with errors harder to explain.

## 5. Java

### 5.1 Results and fields

* `Object` is `any`. `list.get(0)` is `any` until generics (5.2) : `list.get(0) as string`.
* An interface is `JavaInstance(I)`, no longer `dynamic`. The reason it is `dynamic` today (a
  Babble instance passed to Java comes back as itself where `I` is declared) is kept by making
  member lookup on `JavaInstance(I)` accept both : the members are `I`'s methods, whichever kind
  of object holds them (`Faces` already makes a Babble instance answer `I`'s methods).
* An array is a new `Type.JavaArray(T)` (`String[]` is `JavaArray(string)`), assignable to the
  Java parameters that take that array type, with `length`. Until Babble has an indexing
  syntax, elements are reached through `java.lang.reflect.Array.get(a, i)`, which is `any`.
* A class Babble can't access directly (`List.of()`'s) is typed by the accessible type it is
  reached through (`JavaTypes.accessible`), as calls already are.

### 5.2 Generics

Reflection gives the generic signatures (`List<E>.get` returns `E`). A later step (§8, P6)
types `java.util.List[string]` annotations and propagates type arguments through calls and
constructors (`java.util.ArrayList[string]()`), so that `list.get(0)` is a `string` without a
cast. Until then, elements are `any` and need `as` or `is`.

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
  always binds a `java.util.List` of strings, so it is declared `java.util.List` (and
  `java.util.List[string]` with generics).
* `import(name)` with a name that isn't a string literal is `any` ; static imports keep their
  module types.

## 7. Runtime

* `Types.check` stays for `as` and for values crossing the Java boundary. The T9 checks at
  parameters, results and assignments are no longer needed where the checker proved the type,
  which is everywhere but `as` ; they are removed in P5, after the strict mode is the only mode.
* The Java boundary runs both ways, and its checks stay in both :
  * calls into Java : a Java method declared to return `Integer` (or any boxed type) is typed
    `int` and may return `null`, so its result is checked ;
  * calls back from Java : a Babble function passed as a functional interface (`Faces`) can be
    called with anything through raw types, so its parameters, typed from the interface's
    method, are checked in that adapter ; likewise the methods of a Babble instance
    implementing a Java interface, and member writes from Java
    (`BabbleInstance`/`BabbleType.setMember`).
* The interpreter stays the reference : both modes give the same results, as today. Fewer
  checks change no result of a program that type-checks ; the conversion of an `int` joined to
  `double` (4.4, Q7) does : `w + w` above becomes `2.0`. It is the one change of behaviour.
* Errors a program could reach before and no longer can (`Not a number : x` on a Babble value,
  `x is not callable`, …) stay in the runtime, as the backstop for Java values and casts.

### 7.1 Unboxed arithmetic

Branch `ccr-2f175974-zutbpj` (`bytecode-compiler.md` §19 there) computes on unboxed `int`,
`double` and `bool` values in compiled code. It can't trust the checker's types today (a
`double` may be an int at runtime, 4.4 ; a parameter typed from its context is checked by
nothing), so it adds a fifth analysis pass, `Unboxing`, that trusts only the T9 checks, literals
and operators on such values. With this design, a static `int`, `double` or `bool` is the fact
it needs :

* At P4, `ScopeAnalysis.kind` (the pass's interface to `CodeGenerator`, which doesn't change) is
  filled from the checker's types instead of from `Unboxing`. What goes : the reasoning about
  which class an instance or which function a callee is certain to be, the fixpoint over
  reassigned variables (4.3 is the same inference), and the `instanceof BabbleInstance` branch on
  every arithmetic operator, since the left operand's type says statically whether it is
  numbers or an operator method.
* What stays : a primitive local (and later field) has no undefined state, so "used only after
  its definition" still decides where a value can live, and captured variables keep their
  cells ; comparisons keep `Double.compare`, to agree with `Operators` on NaN and the zeros.
* Measured there : the fable ray tracer with annotations ran a third slower than without (137 s
  against 105 s), from runtime checks alone ; nominal checks were a quarter of argon's time
  before that branch stopped comparing class ids as strings, and checks are still about a tenth
  of its profile. That is what P5 removes.

## 8. Plan

Each phase keeps `mvn package` green and is a pull request of its own.

* **P1, `any`, `is` on built-in types, `as`.** Grammar (`as` binds tighter than comparisons,
  looser than arithmetic : `a + b as int` is `(a + b) as int`), `Type.ANY`, both backends
  (`as` compiles to `Types.check`), formatter, tests. `dynamic` still exists.
* **P2, strict mode.** `babble.typing=strict` (engine attribute and system property) reports
  every source of §3 as an error, with the wording of §4. Off by default ; a test runs every
  `.ba` script and example in strict mode and lists which fail, as the migration's to-do list.
* **P3, migration.** Annotate the tests and the examples ; rewrite the tests that reach runtime
  type errors through `def id = (v) -> ( v )` with `as` (the error still exists, at the cast) or
  turn them into static diagnostics ; the README's *Optional types* section and the tour ; argon
  must still render `scene.ppm` byte for byte.
* **P4, flip the default.** Strict is the only mode ; `dynamic` and the flag are removed ;
  `TypeChecker`'s gradual paths (`isUnknown`, the `dynamic` cases of `assignable`) go.
* **P5, runtime checks.** Remove the T9 boundary checks the checker makes redundant ; measure
  with `Benchmarks` and the ray tracers (7.1). Only after P4, and after `Unboxing` takes its kinds
  from the checker : until then that pass relies on the T9 checks for its soundness.
* **P6, Java generics** (5.2), then **Babble generics** and **union types** (README, *Planned
  features*), each reducing the casts P3 had to write.
* **P7, parameters inferred from calls** (4.9). It needs only P4, and can come before P6. It
  makes most of the annotations P3 adds optional again : P7 removes those inference gives the
  same type, keeping the ones on exported and escaping functions.

## 9. Decisions

The questions of the draft, as decided :

* **Q1. Unannotated parameters with no contextual type** : an annotation is required (4.1),
  unless the parameter has a default value. Then, in P7, a parameter of a function whose calls
  are all visible is inferred from them (4.9) ; inference from uses in the body is out of scope.
* **Q2. Numeric widening on reassignment** : inference over all assignments (4.3) :
  `def x = 1 ; x = 1.5` makes `x` a `double`.
* **Q3. `if` without `else` as a value** : an error where the value is used and its type admits
  no `null` (`int`, `double`, `bool`), as in 4.4.
* **Q4. Arrays** : a `JavaArray(T)` type now (5.1).
* **Q5. The REPL** : the same rules as scripts.
* **Q6. Cast syntax** : `e as T` (4.8).

Open :

* **Q7. Converting an `int` joined to `double`** (4.4) : proposed from the unboxed-arithmetic
  work (7.1), so that a static `double` is always a runtime `Double`. Recommended : yes ; it
  changes the result of programs like `w + w` in 4.4 (`2` becomes `2.0`), the one change of
  behaviour this design makes.

## 10. Testing

* P1 : golden tests of `as` and `is` diagnostics in `TypeCheckerTestCase`, `DifferentialTestCase`
  snippets for failing casts, a `.ba` script.
* P2 : one golden test per source of §3 in strict mode, and the strict-mode survey of every
  script (§8).
* P3–P4 : the whole suite in strict mode ; `ExamplesTestCase` compiles the examples in it.
* P5 : `BabbleCompilerTestCase` checks the boundary checks are gone from the generated code ;
  benchmarks before and after.
* P7 : golden tests of call-site inference in `TypeCheckerTestCase` (widening across calls,
  named and default arguments, methods and constructors, recursion, a conflicting call, and
  `Missing type` for escaping, exported and never-called functions) ; removing the annotations
  inference makes redundant from the tests and examples must leave the suite green.
