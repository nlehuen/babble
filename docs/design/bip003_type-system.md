# BIP003: the type system

| | |
|---|---|
| **BIP** | 003 |
| **Date** | 2026-10-24 |
| **Status** | Gradual rules, `dynamic`, and internal `BIP003-D9` checks replaced by [bip008_static-typing.md](bip008_static-typing.md) (`BIP008-I1`–`BIP008-I7` implemented : strict checking, call-site parameter inference, union types `A | B`, nullable types `T?`, Java and Babble generics with `(...)` syntax) ; runtime checks remain only at `as` casts, Java boundaries (including Java generic `TypeVariable`/`WildcardType` returns and fields), and member writes from Java (`org.babblelang.runtime.Types`, `JavaTypes`). |
| **Depends on** | [bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) §10 (where the checker sits, `BIP001-Q1`–`BIP001-Q9`), [bip002_object-model.md](bip002_object-model.md) §8 (classes and interfaces are types) |
| **Unblocks** | Typed code generation (`bip001_bytecode-compiler.md` §10.5) |
| **Replaced by** | [bip008_static-typing.md](bip008_static-typing.md) (`BIP008-I1`–`BIP008-I7` implemented : `any` / `any?`, unions, `T?`, generics, `as`, call-site parameter inference, strict checking, internal `BIP003-D9` checks removed). |

## 1. Summary

Babble has optional annotations: `def n : int = 0`, `(a : int, b : string = "") : int -> …`,
`def area : () : double`. Until now nothing read them. The type checker gives them a meaning,
gradually:

* **Gradual.** Whatever is unannotated and can't be inferred has the type `dynamic`, which is
  compatible with every type in both directions. An unannotated program gets no static type errors,
  except for calls and member accesses that are certain to fail.
* **Front end, both execution modes.** The checker is the fourth pass of the analysis, after `BlockScopes`,
  `TypeChecks` and the `Resolver`, on the parse tree. Both interpreter and compiler share the same diagnostics.
* **Always enforced.** Every type diagnostic goes to the context's error writer before the script
  runs, labelled `Error` (such as
  `Error : Type error : expected int, got string in <input> at line number 3, column 15`), and the
  first one fails `compile()` with a `TypeCheckException` (a `ScriptException`) at its line and
  column. Declared types are also checked at runtime (§5).
* **Wording.** Every diagnostic that predicts a runtime error uses the runtime's message
  (`Missing parameter : b`, `No such member : x`, `Not a number : "a"`), so the static error and
  a runtime failure read the same.

## 2. Types

| Type | Values | Written |
|---|---|---|
| `dynamic` | anything | `dynamic`, or no annotation |
| `int`, `double`, `bool`, `string` | `Integer`, `Double`, `Boolean`, `String` | the built-in names |
| `null` | `null` | not written: the type of the literal |
| function types | Babble functions | `(a : int, b = …) : int` |
| class and interface types (nominal) | instances | the name of a class or interface literal |
| parameterized & generic types | instances of generic classes/interfaces, type variables | `Box(int)`, `java.util.List(string)`, `T` |
| object literal types | the single instance | not written |
| class values | a class or interface expression's value: constructor, statics | not written |
| packages (structural) | a package | not written |
| Java types | instances of a Java class | `java.util.List(string)`, or through `import("…")` |
| `nothing` | none: the type of `return` | not written |

## 3. Decisions (`BIP003-D1`–`BIP003-D8`, resolving `BIP001-Q1`–`BIP001-Q8`)

* **`BIP003-D1` (resolves `BIP001-Q1`), type names.** `int`, `double`, `bool`, `string` and `dynamic` are built in. A plain name
  must refer to a `def` initialised with a class or interface literal and never reassigned,
  possibly reached through packages (`shapes.Shape`), as for `implements`. A dotted name
  starting with `java` or `javax`, or with a name bound to `import("…")`, is a Java class;
  `java.lang.String` is `string`, `java.lang.Integer` is `int`, and so on. Anything else is
  reported as `Unknown type : X` and treated as `dynamic`.
* **`BIP003-D2` (resolves `BIP001-Q2`), `null`.** `int`, `double` and `bool` reject `null`; every other type accepts it. So
  `def x : int` without a value is reported, and `def s : string` isn't. A `T?` syntax can come
  later.
* **`BIP003-D3` (resolves `BIP001-Q3`), operators.** As the runtime does them (`Operators`):
  - `+` with a `string` on the left is `string`;
  - `+ - *` on two `int`s are `int`, on two numbers otherwise `double`, and `/` is always
    `double`;
  - an operand known not to be a number is `Not a number : …`;
  - `< <= > >=` take two numbers, or a `Comparable` and a value of its class, or else report
    `Not comparable : … and …`;
  - comparisons, `==`, `!=`, `and`, `or`, `not` and `is` are `bool`;
  - `if` and `while` accept any test, since every value has a truth value.
* **`BIP003-D4` (resolves `BIP001-Q4`), function types.** A function type lists its parameters, their types, and whether each
  is optional (has a default), then its result. Calls are checked against it with the binding
  rules of `Arguments`: by name, then by position, then the default. The reports are those of
  the runtime: `Missing parameter`, `No such parameter`, `Too many parameters`, then
  `Type error : expected T, got U` per argument.
  - A function is assignable to a function type when it accepts the target's arguments by
    position, at types at least as wide, needs no more of them, and returns a narrower result.
  - Parameter names are not compared: a callback is normally called by position.
  - A default *expression* in a function type, which the grammar allows, only marks the
    parameter optional.
* **`BIP003-D5` (resolves `BIP001-Q5`), classes, objects and packages.** As decided by the object model ([bip002_object-model.md](bip002_object-model.md) §8):
  - Classes and interfaces are nominal. An instance is assignable to its class and to every
    interface it implements, directly or through other interfaces. An object literal has an
    anonymous class type, assignable to its interfaces.
  - A member's type is its annotation (`def size : int = 0`, `def balance : int` in a class
    header) or, for a method, its literal's signature. Other members are `dynamic`, since
    `obj.x = …` can change them.
  - Reading or assigning a member the class doesn't have, including inherited defaults and
    Java interface methods, is `No such member : x`. On an interface type, only the
    interface's members are known.
  - A class value's call is checked against the constructor's header parameters and gives an
    instance. Its statics are its members.
  - A package's members are its `def`s and nested packages, with their types; it never reports
    a missing member.
* **`BIP003-D6` (resolves `BIP001-Q6`), `this`.** In a class or object body, or one of its methods, `this` has the class's type.
  In an interface default it has the interface's type, and in a static block the class value's.
* **`BIP003-D7` (resolves `BIP001-Q7`), Java.** Overloads are resolved at check time, as `Overloads` does at runtime:
  - a `dynamic` argument fits any parameter;
  - a call no overload can accept is `No applicable overload of C.m for (types)`;
  - unknown names are `No such name in C : x`.
  - The result type is that of the applicable overloads when they agree, or of the most
    specific one when every argument's type is known. A constructor gives an instance of its
    class.
  - Java results come back unwrapped, so beyond numbers, booleans and strings they are
    `dynamic`, and `Object` is `dynamic` everywhere (generics are erased).
  - Babble functions and class values are assignable to functional interfaces, and Babble
    instances to the Java interfaces their class implements.
* **`BIP003-D8` (resolves `BIP001-Q8`), bidirectional local inference.**
  - An unannotated `def` that is never assigned with `x = …` has its value's type, except that
    `null` (and `return`) infer `dynamic`: `def head = null` says nothing about what `head`
    holds later. So `def f = (a : int) -> …` gives `f` a function type, and `def p = Point(1, 2)`
    gives `p` the type `Point`.
  - Unannotated function literals infer their result type by joining the types of their `return`
    expressions and the last statement of their body (falling back to `dynamic` if that yields
    `null` or `nothing`). Direct self-recursion (`recurse`, or calling the function's own
    unreassigned `def` name or `this.method`) is inferred in two passes when the body fixes a
    concrete result type. An unannotated parameter still defaults to `dynamic` when no contextual
    type applies, so calls get arity checks without constraining caller types.
  - Contextual typing flows downward from declared `def` types, assignments, parameter defaults,
    annotated function results (`return` and last expression), Babble call/constructor parameters,
    and unambiguous Java functional interface targets (`IntUnaryOperator`, `Callable`, single
    matching functional overloads), propagating through blocks and `if` branches into unannotated
    function literal parameters and return expressions.
  - Flow-sensitive narrowing after `is`: when a non-reassigned lexical binding or parameter is
    tested with `x is T` (a Babble class/interface or a Java class/interface), `x` is narrowed
    to `T` in the `then` branch (or `else` under `not`), inside `while x is T`, on the right-hand
    side of short-circuit `and` / `or` (`x is T and …`, `not (x is T) or …`), and for subsequent
    statements in a block after an early-exit guard (`if not (x is T) then ( return … )`).

## 4. What is checked

* A `def`'s value, a parameter's default, an assignment (`x = …`, `obj.x = …`, `this.x = …`) and
  each call argument, against the declared type (with contextual parameter typing for function
  literal arguments, assignments and definitions).
* A function's result, against its annotation: each `return`'s value, and the value of its last
  statement. An `if` without `else` may be `null`, so `(n : int) : int -> ( if … then ( n ) )`
  is `dynamic` and not reported.
* Calls to non-callable values (`1()`, an instance, a string): `X is not callable`.
* Member selection on values that have no members (numbers, booleans, strings, null,
  functions): `Not a scope : x`. Narrowed lexical bindings use their narrowed type in branches,
  short-circuit operators and post-guard block statements.
* Operators (`BIP003-D3`), members (`BIP003-D5`), Java calls (`BIP003-D7`).
* Assigning a package's member from outside (`p.x = …`, `p.x += …`), when the package is known
  statically: `Cannot assign to a package member : x`. A package changes only through its own
  code; the runtime refuses it whatever the receiver.
* Compound assignment (`x += v`, `obj.x -= v`, `this.x *= v`, `/=`) is typed as `x = x op v`:
  the operator on the target's type (its declaration, or `dynamic`) and `v`'s, operator methods
  included, and its result against the target's declared type, reported at the assignment.
* The number conversions `int(x)` and `double(x)`, when no `def` shadows them, are functions of
  one `dynamic` parameter returning `int` and `double`.

The value of an `if` is the join of its branches: the same type, `double` for `int` and
`double`, a nullable type for `null` and that type, and `dynamic` otherwise. A `while` loop's
value is the join of its body's value and `null`.

## 5. Runtime checks (`BIP003-D9`, resolving `BIP001-Q9`, updated in `BIP008-I5`–`BIP008-I6b`)

With strict static typing ([bip008_static-typing.md](bip008_static-typing.md) `BIP008-I1`–`BIP008-I6b`), `Types.check` is no longer emitted at
Babble-to-Babble parameter, return, `def`, or assignment boundaries : the static checker proves
those types, and `int`→`double`/`double?` widening uses `Values.toDouble` where `widensToDouble`
marks a widening point.

* **Where runtime checks remain.**
  - Explicit `as` casts (`Types.check` with the target descriptor from `ScopeAnalysis.checkedType`, including union, `null`, `any`, and non-nullable checks).
  - Values exiting Java that map a boxed primitive (`Integer`, `Double`, `Boolean`) to a
    non-nullable Babble primitive (`JavaTypes.checkBoxed` in `JavaMethod`, `JavaLinker`,
    `JavaClass`, and `JavaDefaultCall`), which reject `null` with
    `Type error : expected int/double/bool, got null`.
  - Java generic method calls and field reads whose reflection return/field type is a `TypeVariable`
    or `WildcardType` resolving to a non-`any?` type (`Types.check` with `ScopeAnalysis.checkedType`
    emitted/applied at the boundary in `JavaMethod`, `BoundJavaMethod`, `Interpreter`, and `CodeGenerator`).
  - Arguments entering Babble from Java through `Faces` proxies (`JavaTypes.checkParameters`
    in `FaceHandler` and `FunctionHandler`) against the Java interface method's parameter types.
  - Member and static writes going through `BabbleInstance.setMember` and `BabbleType.setMember`
    (used by external Java callers and dynamic `any` member writes ; `ClassShape` retains the
    declared field types for that, while Babble-side `this.x = …` direct field writes in
    `CodeGenerator` omit checks).

The in-repo scripts pass type checking and run with these checks enforced (`DifferentialTestCase`,
`BabbleCompilerTestCase`).

## 6. Not done yet

* Structural object types.
* *(Done in `RM001`, [bip001_bytecode-compiler.md §19](bip001_bytecode-compiler.md#19-design--implementation-notes-rm001--typed-primitive-lowering-and-static-dispatch)):* `ScopeAnalysis` now publishes converged `PrimitiveKind`, definite-initialization flags, and static Babble/Java/module targets to `CodeGenerator`.

## 7. Testing

`TypeCheckerTestCase` has the golden tests of bip001_bytecode-compiler.md §10.6: a snippet, and the
diagnostics expected at each line and column. It also checks that every `.ba` script in
`src/test/babble` checks cleanly, that type errors fail the compilation with all diagnostics on
the error writer, and that declared types are checked at runtime.
* `DifferentialTestCase` runs a list of typed snippets in both interpreter and compiler and compares
  results, output and errors. They cover dynamic values reaching every kind of boundary and type,
  the widening, null, and nominal checks. It also runs every `.ba` script cleanly.
