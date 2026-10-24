# Design: the type system

| | |
|---|---|
| **Status** | Implemented in `warn` mode (`org.babblelang.analysis.TypeChecker`). `error` mode and runtime checks (T9) are not. |
| **Depends on** | [bytecode-compiler.md](bytecode-compiler.md) §10 (where the checker sits, the T1–T9 agenda), [object-model.md](object-model.md) §8 (classes and interfaces are types) |
| **Unblocks** | `error` mode and boundary checks (T9), then typed code generation (bytecode-compiler.md §10.5) |

## 1. Summary

Babble has optional annotations: `def n : int = 0`, `(a : int, b : string = "") : int -> …`,
`def area : () : double`. Until now nothing read them. The type checker gives them a meaning,
gradually:

* **Gradual.** Whatever is unannotated and can't be inferred has the type `dynamic`, which is
  compatible with every type in both directions. An unannotated program gets no type warnings,
  except for calls and member accesses that are certain to fail.
* **Front end, both modes.** The checker is the fourth pass of the analysis, after `BlockScopes`,
  `TypeChecks` and the `Resolver`, on the parse tree. Both modes get the same diagnostics.
* **Warnings only.** `babble.typecheck=warn` (system property or engine attribute) turns it on.
  Its diagnostics go to the context's error writer before the script runs, as
  `Warning : Type error : expected int, got string in <input> at line number 3, column 15`. The
  default is `off`. The test suite runs with `warn`.
* **Wording.** Every diagnostic that predicts a runtime error uses the runtime's message
  (`Missing parameter : b`, `No such member : x`, `Not a number : "a"`), so the warning and the
  failure read the same.

## 2. Types

| Type | Values | Written |
|---|---|---|
| `dynamic` | anything | `dynamic`, or no annotation |
| `int`, `double`, `bool`, `string` | `Integer`, `Double`, `Boolean`, `String` | the built-in names |
| `null` | `null` | not written: the type of the literal |
| function types | Babble functions | `(a : int, b = …) : int` |
| class and interface types (nominal) | instances | the name of a class or interface literal |
| object literal types | the single instance | not written |
| class values | a class or interface expression's value: constructor, statics | not written |
| packages (structural) | a package | not written |
| Java types | instances of a Java class | `java.util.List`, or through `import("…")` |
| `nothing` | none: the type of `return` | not written |

## 3. Decisions (T1–T8 of bytecode-compiler.md §10.4)

* **T1, type names.** `int`, `double`, `bool`, `string` and `dynamic` are built in. A plain name
  must refer to a `def` initialised with a class or interface literal and never reassigned,
  possibly reached through packages (`shapes.Shape`), as for `implements`. A dotted name
  starting with `java` or `javax`, or with a name bound to `import("…")`, is a Java class;
  `java.lang.String` is `string`, `java.lang.Integer` is `int`, and so on. Anything else is
  reported as `Unknown type : X` and treated as `dynamic`.
* **T2, null.** `int`, `double` and `bool` reject `null`; every other type accepts it. So
  `def x : int` without a value is reported, and `def s : string` isn't. A `T?` syntax can come
  later.
* **T3, operators.** As the runtime does them (`Operators`):
  - `+` with a `string` on the left is `string`;
  - `+ - *` on two `int`s are `int`, on two numbers otherwise `double`, and `/` is always
    `double`;
  - an operand known not to be a number is `Not a number : …`;
  - `< <= > >=` take two numbers, or a `Comparable` and a value of its class, or else report
    `Not comparable : … and …`;
  - comparisons, `==`, `!=`, `and`, `or`, `not` and `is` are `bool`;
  - `if` and `while` accept any test, since every value has a truth value.
* **T4, function types.** A function type lists its parameters, their types, and whether each
  is optional (has a default), then its result. Calls are checked against it with the binding
  rules of `Arguments`: by name, then by position, then the default. The reports are those of
  the runtime: `Missing parameter`, `No such parameter`, `Too many parameters`, then
  `Type error : expected T, got U` per argument.
  - A function is assignable to a function type when it accepts the target's arguments by
    position, at types at least as wide, needs no more of them, and returns a narrower result.
  - Parameter names are not compared: a callback is normally called by position.
  - A default *expression* in a function type, which the grammar allows, only marks the
    parameter optional.
* **T5, classes, objects and packages.** As decided by the object model (§8 there):
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
* **T6, this.** In a class or object body, or one of its methods, `this` has the class's type.
  In an interface default it has the interface's type, and in a static block the class value's.
* **T7, Java.** Overloads are resolved at check time, as `Overloads` does at runtime:
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
* **T8, inference.** An unannotated `def` that is never assigned with `x = …` has its value's
  type, except that `null` (and `return`) infer `dynamic`: `def head = null` says nothing about
  what `head` holds later. So `def f = (a : int) -> …` gives `f` a function type, and
  `def p = Point(1, 2)` gives `p` the type `Point`. Results are not inferred from function
  bodies: an unannotated result is `dynamic`. An unannotated function's parameter list is
  still known, so its calls get arity checks, but not type checks.

## 4. What is checked

* A `def`'s value, a parameter's default, an assignment (`x = …`, `obj.x = …`, `this.x = …`) and
  each call argument, against the declared type.
* A function's result, against its annotation: each `return`'s value, and the value of its last
  statement. An `if` without `else` may be `null`, so `(n : int) : int -> ( if … then ( n ) )`
  is `dynamic` and not reported.
* Calls to non-callable values (`1()`, an instance, a string): `X is not callable`.
* Member selection on values that have no members (numbers, booleans, strings, null,
  functions): `Not a scope : x`.
* Operators (T3), members (T5), Java calls (T7).

The value of an `if` is the join of its branches: the same type, `double` for `int` and
`double`, a nullable type for `null` and that type, and `dynamic` otherwise. A `while` loop's
value is the join of its body's value and `null`.

## 5. Not done yet

* **`error` mode and runtime checks (T9).** Step 10 of the compiler's plan: in `error` mode the
  diagnostics fail `compile()`, and `Types.check` runs where a `dynamic` value meets a declared
  type, in both modes.
* **Narrowing after `is`.** `if x is Point then ( x.norm() )` doesn't narrow `x`.
* **Result inference** from function bodies, and generics.
* **Interface members' declared types** against the implementing methods' signatures.
* **Types for the compiler.** The checker keeps a type per expression, but doesn't publish it:
  typed code generation (bytecode-compiler.md §10.5) only makes sense once types are enforced.

## 6. Testing

`TypeCheckerTestCase` has the golden tests of bytecode-compiler.md §10.6: a snippet, and the
diagnostics expected at each line and column. It also checks that every `.ba` script in
`src/test/babble` checks cleanly, and that the engine switch reports the same diagnostics in
both modes. The whole suite runs with `babble.typecheck=warn`. Every warning the existing tests
trigger matches a runtime error those tests expect.
