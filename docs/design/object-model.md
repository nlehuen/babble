# Design: the Babble object model

| | |
|---|---|
| **Status** | Draft. The four foundational choices are decided (§2). The open questions are in §11. |
| **Date** | 2026-10-11 |
| **Depends on** | [bytecode-compiler.md](bytecode-compiler.md): block scope (§8.1), the shared runtime (§5), type checking (§10) |
| **Unblocks** | Compiler phase 2 (§7), object and package types T5 (§10.4) |

## 1. Summary

Babble gets **classes**. `class (params) ( body )` is an expression, like `object ( … )` and
function literals. Its header is a constructor parameter list, and its body runs once per
instance. Every `def` at the top level of the body declares a **member**.

* **Members are reached through `this` or a receiver only.** `obj.x` sees the object's members
  and nothing else. Inside a method, members are written `this.x`. An unqualified name never
  means a member, and it resolves lexically.
* **No class inheritance.** Classes reuse behaviour by implementing **interfaces**.
  `interface ( … )` declares required members and may provide default methods, so interfaces
  double as traits.
* **Babble functions can be passed where Java expects a functional interface**
  (`Runnable`, `Comparator`, `java.util.function.*`). They are converted automatically.
* `object ( … )` stays, as a single instance of an anonymous class.

The model is closed: an object's members are fixed by its class. That is what lets compiler
phase 2 turn a class into a JVM class with fields, an interface into a JVM interface, and a
method call into an ordinary virtual or interface call. It also gives the type checker
nominal types to work with.

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| O1 | Unit of definition | **Class expressions** with a constructor parameter list in the header. Object literals become one-off anonymous classes. |
| O2 | Member lookup | **Members only, explicit `this`.** `obj.x` sees only members, and inside a class members must be written `this.x`. This closes D5 in the compiler doc. |
| O3 | Reuse | **Interfaces with default methods (traits).** There is no class inheritance and no `super`. |
| O4 | Java interop | **Functions convert to Java functional interfaces.** Babble classes do not implement or extend Java types in this design. |

The sections below work out what these imply. Wherever a further choice was needed, the
section states it as a recommendation and lists the alternative in §11.

## 3. Today, and what changes

Today (compiler doc §2.5), an object is a `BabbleObject` namespace whose parent is the scope it
was created in. Selecting `obj.x` walks the whole parent chain, and a `Function` found outside
the object's own locals gets bound to the object on the fly. `Stack.ba` relies on this: its
"methods" are locals of the factory function. `this` exists only because `BoundMethod` defines
it in the call namespace, and it falls back lexically when a function isn't called through an
object.

| | Today | This design |
|---|---|---|
| How objects are made | factory function + `object ( … )` | `class` expression (or `object ( … )` for one-offs) |
| `obj.x` | object's locals, then the creating scope, then globals | the object's members only |
| Unqualified `x` inside a method | the object's locals, through the closure | never a member: lexical scope only |
| What counts as a method | any `Function` found by selection | a member whose initialiser is a function literal |
| `this` | defined at call time, otherwise lexical fallback | valid only inside a method (and in closures nested in it), otherwise a compile-time error |
| Adding members | impossible (`obj.w = 1` fails) | impossible: members are fixed by the class |
| Reuse | copying functions around | interfaces with default methods |
| Java callbacks | impossible | functions convert to functional interfaces |

## 4. Classes

### 4.1 Syntax

```
def Point = class (x:double, y:double) (
    def x = x
    def y = y
    def norm = () -> ( java.lang.Math.sqrt(this.x * this.x + this.y * this.y) )
    def plus = (other) -> ( Point(this.x + other.x, this.y + other.y) )
)

def p = Point(3.0, 4.0)
p.norm()                     ;; 5.0
def q = Point(y: 1.0, x: 2.0)   ;; constructor arguments follow the normal call rules
p.plus(q).x                  ;; 5.0
```

Proposed grammar additions, in the style of `Babble.g4`:

```antlr
| CLASS parametersDeclaration? (IMPLEMENTS typeList)? NL* classBlock=block   # classExpression
| INTERFACE (EXTENDS typeList)? NL* interfaceBlock=block                     # interfaceExpression
| OBJECT (IMPLEMENTS typeList)? NL* createBlock=block                        # objectExpression
| THIS                                                                       # thisExpression
| left=expression IS right=expression                                        # isExpression

typeList: type (NL* ',' NL* type)*;
```

The new keywords are `class`, `interface`, `implements`, `extends` (only between interfaces,
§6.4), `is` and `this`. `this` stops being an ordinary name (§5.3). None of them is used as an
identifier anywhere in the repo, and they only appear in comments.

A class with no parameters can omit the list: `class ( … )` is `class () ( … )`.

### 4.2 The class body

The body is an ordinary block, run **once per instance** by the constructor, top to bottom:

* **Top-level `def`s declare members.** `def size = 0` declares the member `size` and stores its
  initial value. These are the only members the class has.
* **Member `def`s bind no unqualified name** (O2). In the body and in methods alike, a member is
  `this.size`, never `size`. So `def x = x` is unambiguous: it declares the member `x`, and
  initialises it from the constructor parameter `x`. This is the one exception to block-scope
  hoisting (compiler doc §8.1, rule 3): a class or object body is not a lexical scope for its
  own members.
* **Constructor parameters are ordinary lexical variables,** visible in the whole body,
  including inside methods. A parameter that no method uses is just a constructor local. One
  that a method uses becomes a hidden per-instance field (§7.2). This is how Scala handles
  class parameters.
* **Nested blocks are block scopes.** A `def` inside `( … )`, an `if` branch or a `while` body in
  the class body is a block-local variable, not a member, exactly as in §8.1 of the compiler doc.
* **Other expressions are allowed.** The body can validate its arguments, print, or call
  methods. It is the constructor.
* **Reading a member before its `def` has run** is an error, `Member used before definition :
  size`, the member counterpart of block scope's rule 3. Every member starts out undefined. This
  is what happens when an initialiser calls a method that reads a later member.
* **`return` in a class body** (outside its methods) is a compile-time error,
  `return in a class body`, just as `return` in a parameter default is (D3). The body runs when
  the class is *called*, so a `return` there would have nothing sensible to return from.
* The value of a construction expression `Point(1, 2)` is the new instance, not the value of
  the body's last expression.

### 4.3 Members: fields and methods

* A **method** is a member whose initialiser is a function literal *written directly*:
  `def norm = () -> ( … )`. Methods are read-only: `p.norm = …` is an error,
  `Cannot assign method : norm`. Every instance of a class shares the same methods.
* A **field** is any other member: `def size = 0`, `def cb = someFunction`. Fields are mutable,
  like `def` variables. A field holding a function is just a value: calling it does *not* bind
  `this`.
* Selection follows from this. `obj.m`, where `m` is a method, gives a **bound method** that
  remembers its receiver, so `def f = obj.m ; f()` still runs with `this = obj`.
  `obj.cb`, where `cb` is a field, gives the field's value.
* The member set is fixed. Assigning `obj.w = 1` to an undeclared member fails with
  `No such member : w`, as it effectively does today.
* There is no visibility control in this version: every member is public. See §11, Q4.

### 4.4 The class value

A `class` expression evaluates to a **class value** (`BabbleClass` at runtime):

* It is callable. Calling it constructs an instance, using the full argument binding rules
  (`Arguments`, compiler doc §5.2).
* It has a display name, taken from the `def` it is bound to (`Point`), which shows up in
  messages and stack traces.
* It has no static members. Shared helpers live in a `package` next to the class, as they do
  today.
* A class expression can be evaluated many times: a class defined inside a function is created
  on each call. All those class values share one compiled JVM class (§7.1), but each carries
  the outer variables it captured. Instances of two such values are distinct classes for `is`
  (§8.1).

### 4.5 `toString`, equality and printing

* If a class has a zero-parameter method `toString`, it is used whenever the instance is turned
  into a string: by `print`, by `"…" + obj`, and by Java code calling `toString()`. Otherwise
  the default is `Point@1b6d3586`, which uses the class's display name.
* `==` on instances stays identity, as it is today for non-comparable values. A protocol for
  value equality (`equals`/`hashCode` methods) is deliberately left out (§11, Q3).

## 5. Lookup, `this` and `recurse`

### 5.1 What a name can reach

| Expression | Resolves to |
|---|---|
| `obj.x` | the member `x` of `obj`'s class. Otherwise `No such member : x`. For packages and Java scopes, the same as today. |
| `this.x` | the same, with `this` as the receiver |
| `x` (unqualified) | a lexical variable: block, function, constructor parameter, outer scopes, script root, globals. **Never a member.** |

Packages are not objects, and they keep their current rule: a package body's `def`s are
reachable unqualified inside it and as `pkg.x` from outside.

### 5.2 `this`

* `this` is a keyword. It is valid in a class or object body, in a method, and in any function
  literal nested inside a method (where it is captured lexically, like JavaScript's arrow
  functions). Anywhere else it is a compile-time error, `this outside of a class`.
* So whether `this` is valid is now a **static** property of where it is written. The
  compiler doc's §7.4 machinery (an `UNBOUND` sentinel and a lexical fallback for every
  function) is no longer needed: a method receives its receiver as an ordinary argument.
* A method selected from an instance is always bound to it (§4.3), so there is no "method
  called without a receiver" case left to handle at runtime.

### 5.3 `recurse`

Inside a method, `recurse` is the method bound to the current receiver, which is what commit
`5dcc5a9` established, so `recurse(n - 1)` keeps `this`. Inside any other function literal,
including one nested in a method, `recurse` is that function literal, as today.

## 6. Interfaces

### 6.1 Declaring an interface

```
def Shape = interface (
    def area : () : double                                     ;; required, no value
    def name : string                                          ;; required field or method
    def describe = () -> ( this.name + " of area " + this.area() )   ;; default method
)
```

* `def m` **without a value** declares a required member. An optional type says what kind it
  is. `def x … : type` with no value is already valid syntax.
* `def m = (…) -> ( … )` declares a **default method**. Its body can use `this`, but only the
  interface's own members are reachable through it: required ones and defaults. Interfaces have
  no state, so a default must be a function literal. `def size = 0` in an interface is
  rejected.
* An interface can extend other interfaces: `interface extends Shape, Named ( … )`. It inherits
  their required members and defaults.

### 6.2 Implementing interfaces

```
def Square = class (side:double) implements Shape (
    def side = side
    def name = "square"
    def area = () -> ( this.side * this.side )
)

Square(2.0).describe()    ;; "square of area 4.0", the default method from Shape
```

* **Conformance is declared, i.e. nominal.** A class satisfies only the interfaces it lists in
  `implements`, including what those interfaces extend. Merely having the right members is not
  enough. Declaring is what lets `is` (§8.1), the type checker and the JVM mapping (§7.3) all
  give the same answer cheaply.
* **Completeness is checked before the program runs.** A class that lacks a required member and
  doesn't inherit a default for it is rejected: `Square does not implement Shape.area`.
* **Defaults are inherited** unless the class defines a member with the same name, which
  overrides it.
* **Conflicts must be resolved explicitly.** If two implemented interfaces provide defaults with
  the same name, the class must define that member itself, or it is rejected:
  `Conflicting defaults for describe : Shape, Named`. There is no `super`, so an override cannot
  yet call the default it replaces (§11, Q2).
* `object implements Shape ( … )` works the same way for one-off objects.

### 6.3 `implements` must resolve statically

The names in `implements` and `extends` must refer, at compile time, to an `interface`
expression. That means a `def` (possibly reached through a package, e.g. `shapes.Shape`) whose
initialiser is an `interface` literal and which is never reassigned. Anything else is a
compile-time error, `implements needs a statically known interface`.

This costs a little flexibility, since no interface can be computed at runtime. In return
conformance and default conflicts are checked before anything runs, and each Babble interface
can become a real JVM interface (§7.3). Classes, by contrast, can still be created and passed
around dynamically.

### 6.4 Why not class inheritance

Declined for this version, by decision O3. Interfaces with defaults cover the main reuse
cases. They avoid the fragile-base-class problem and `super` chains, and they fit the JVM
mapping directly (default methods). Class inheritance could still be added later, because
nothing here excludes it.

## 7. Runtime representation and compilation

### 7.1 One JVM class per class expression

Before compiler phase 2, in the interpreter and in phase 1, an instance is a `BabbleInstance`.
It holds its `BabbleClass` and an `Object[]` of member values, indexed through the class's
member table, which replaces the `HashMap` of today's `BabbleObject`. That already makes
member access an array load. It also makes "fixed member set" structural rather than a
convention.

In phase 2, each `class` (and each `object`) expression compiles to its own JVM class,
`Script$7$Point`. As a Java-equivalent sketch:

```java
final class Script$7$Point extends BabbleInstanceBase implements Script$7$Shape {
    final BabbleClass $class;      // the class value: display name, captured outer variables
    Object x, y;                   // members, as fields (typed fields later, with T9)
    // + hidden fields for constructor parameters that methods use (§7.2)

    Script$7$Point(BabbleClass c, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        $class = c;
        // bind parameters (Arguments), then run the class body: field initialisers and statements
    }

    Object norm(ScriptRuntime rt, Object[] args, String[] names, CallSite site) { ... }  // a method
}
```

* **Methods are JVM instance methods,** with the receiver in local 0. There is no per-instance
  closure.
* **A member access with a known receiver class** compiles to `GETFIELD`/`PUTFIELD`, and a call
  to `INVOKEVIRTUAL`. That happens when the receiver is `this`, or when the type checker knows
  its class. An unknown receiver goes through `Members.select`, which uses the class's member
  table (an array of `VarHandle`/`MethodHandle`). In compiler phase 3 that path becomes an
  `invokedynamic` site cached per receiver class.
* **Construction** is `new` plus the constructor, through the class value's `call`.
* **Outer variables** captured by the class body or its methods are stored once per class value
  (`$class.captured(k)`), not per instance. Two evaluations of a class expression inside a
  function share the JVM class but not their captures.

### 7.2 Constructor parameters used by methods

The resolver knows which constructor parameters each method uses. Those become hidden fields
(`$p$name`), set by the constructor. A method assigning to one updates the field, so a
parameter is shared mutable state of the instance, consistent with a closure capturing a cell.
Parameters no method uses stay JVM locals of the constructor.

### 7.3 Interfaces as JVM interfaces

Each `interface` expression compiles to a JVM interface. Required methods become abstract
methods, default methods become JVM `default` methods, and required fields become abstract
accessors. A class's `implements` list becomes its JVM `implements` list, which §6.3 guarantees
is known statically. So `x is Shape` is an `INSTANCEOF`, and a call to an interface method on a
receiver typed as that interface is an `INVOKEINTERFACE`.

### 7.4 Bound methods

`obj.m` without a call allocates a small `BoundMethod(receiver, method handle)`. The common
case, `obj.m(args)`, never allocates one: the compiler recognises select-then-call and emits a
direct invocation, through `Members.invoke` in phase 2 and an `invokedynamic` site in phase 3.

## 8. Types

### 8.1 Classes and interfaces are types

This settles T5 in the compiler doc.

* **A class value used in a type position is a nominal type,** for example `def p : Point`.
  The same goes for interfaces: `(s : Shape)`.
* **An object literal has an anonymous class type.** It is assignable to the interfaces it
  declares, and otherwise only to `dynamic`.
* **Member types** come from annotations on member `def`s (`def size : int = 0`) and from
  method literals' signatures. Unannotated members are `dynamic`.
* **Type positions must resolve statically,** like `implements` (§6.3). A type name that
  doesn't resolve to a class or interface literal is an error in `warn`/`error` mode.
* **`x is T` tests membership at runtime,** with `T` a class or interface value. It is an
  `INSTANCEOF` in compiled code. For a class defined in a function, the test also compares the
  `BabbleClass` value. `x is java.lang.String` works for Java classes too.
* **Runtime boundary checks (T9)** use the same test, so a failure reads
  `Type error : expected Shape, got Point`.

### 8.2 Packages

Packages remain namespaces. For the type checker they get a structural type: their `def`s,
with their annotated or inferred types. They are never used in type positions.

## 9. Java interop: functions as functional interfaces

### 9.1 Automatic conversion

When overload resolution (`Overloads`) considers a Babble callable argument (a function, a
bound method or a class value) for a parameter whose type is a **functional interface**, the
argument counts as applicable. A functional interface is any interface with exactly one
abstract method, not counting the public methods of `Object`; `@FunctionalInterface` is not
required. The argument is then converted:

```
def list = java.util.ArrayList()
list.add(3) ; list.add(1) ; list.add(2)
list.sort((a, b) -> ( a - b ))                        ;; Comparator
java.lang.Thread(() -> ( println("hi") )).start()     ;; Runnable
list.removeIf((v) -> ( v > 2 ))                       ;; Predicate
```

* **Arguments** are passed to the Babble function as they are (raw Java objects, boxed
  primitives).
* **The result** is converted to the interface method's return type. For `void` it is
  discarded. A primitive return type unboxes the result, with Java's widening, so an `Integer`
  can satisfy `long`. A reference return type must hold it. Otherwise the call fails with
  `Type error : Comparator.compare must return int, got Double`.
* **Arity is checked at conversion time,** when the Babble function has a known parameter table:
  `Comparator.compare takes 2 arguments, function takes 1`. That reports a mistake where it was
  made, not deep inside a Java library.
* **A converted function remembers the `ScriptRuntime`** of the eval that converted it. A
  callback that Java invokes later still prints to the right writer.

### 9.2 Overloads and explicit conversion

A function fits *every* functional interface with the right arity. So
`executor.submit(() -> ( 1 ))` is ambiguous between `submit(Runnable)` and `submit(Callable)`,
and `Overloads` already reports ambiguity as an error rather than guessing. The way out is an
**explicit conversion**: calling a Java functional interface as if it were a class, with one
Babble callable as the argument, yields the converted object.

```
executor.submit(java.util.concurrent.Callable(() -> ( 1 )))
```

### 9.3 Implementation

* In the interpreter and in compiler phases 1 and 2, `java.lang.reflect.Proxy` with an
  `InvocationHandler`. The handler routes the single abstract method to `Callable.call`,
  default methods to `InvocationHandler.invokeDefault`, and `toString`/`equals`/`hashCode` to
  sensible identity-based implementations. This is simple and supports every interface.
* In compiler phase 3, `LambdaMetafactory`-generated adapters are cached per (interface, Babble
  callable kind). That avoids the reflection cost of proxies on hot callbacks such as
  comparators inside a sort.

### 9.4 Not in this design

Babble classes do not implement Java interfaces or extend Java classes (`implements
java.util.Iterator` is rejected by §6.3). Multi-method listeners can't be written in Babble
yet. A Java functional object received from Java (say, a `java.util.function.Function`) is
called through its method, `f.apply(x)`, rather than as `f(x)`.

The Babble runtime is not thread-safe. A function handed to `Thread` or an executor runs on
another thread, and it is the script author's job to avoid sharing mutable Babble state across
threads.

## 10. Migration

Three things in the repo depend on today's object behaviour. Each is migrated in the same
change that implements the model, in the interpreter.

**`Stack.ba`**: methods move into a class, and members are written through `this`:

```
package stack (
    def Node = class (value, next) (
        def value = value
        def next = next
    )

    def Stack = class () (
        def size = 0
        def head = null

        def push = (a) -> (
            this.size = this.size + 1
            this.head = Node(a, this.head)
        )
        def first = () -> (
            if this.head == null then ( null ) else ( this.head.value )
        )
        def pop = () -> (
            if this.head == null then ( null ) else (
                def result = this.head.value
                this.head = this.head.next
                this.size = this.size - 1
                result
            )
        )
        def clear = () -> (
            if this.head == null then ( this.size ) else ( this.pop() ; recurse() )
        )
        def join = (sep = " ") -> ( … unchanged apart from this.head … )
    )

    def create = () -> ( Stack() )      ;; keeps stack.create() working for the tests
)
```

**`Test1.ba` and the README's `counter2`**: `v = v + 1` inside the object's method becomes
`this.v = this.v + 1`. The comment "object literals (class-less for the moment)" goes, and the
README gains a short class/interface example.

**`BabbleFunctionsTestCase.testRecursionInAMethodKeepsTheReceiver`**: the free function `down`
selected through the object becomes a method of the object. What the test checks, that
`recurse` keeps `this`, is unchanged (§5.3):

```
def o = object (
    def total = 0
    def down = (n) -> (
        if n < 1 then ( this.total ) else ( this.total = this.total + n ; recurse(n - 1) )
    )
)
o.down(3)    ;; 6
```

New tests cover the following:
- constructor parameters and defaults, and parameters used from methods;
- reading a member before its `def`, and `return` in a class body;
- fixed member sets and read-only methods;
- bound methods and fields holding functions;
- `this` outside a class;
- interface completeness, default inheritance and conflicts, and statically resolved
  `implements`;
- `is`;
- `toString` in `print` and in concatenation;
- the Java conversions in §9: `sort`, `Thread`, `removeIf`, the ambiguous `submit`, a
  wrong return type, and wrong arity.

## 11. Open questions

| # | Question | Leaning |
|---|---|---|
| Q1 | A shorthand for "parameter that is also a member", to avoid `def x = x` | Wait and see how noisy real code is. A later option is `class (def x, def y) ( … )`. |
| Q2 | Calling an overridden default from the override (no `super`) | Add qualified calls, `Shape.describe(this)`, if the need shows up. |
| Q3 | Value equality: `equals`/`hashCode` methods honoured by `==` and Java collections | Worth doing soon, since objects in a `HashMap` are a natural interop case. It needs the semantics of `==` across types revisited. |
| Q4 | Private members | Out of scope. A naming convention (`_x`) until there is demand. |
| Q5 | Static members on classes | No. Use a package. |
| Q6 | Babble classes implementing Java interfaces (multi-method listeners) | A later design. It needs Java signatures and overloads expressed in Babble. |
| Q7 | Structural typing for object literals passed where an interface is expected | No for now (nominal, §6.2). Revisit if declaring `implements` on literals proves tedious. |

## 12. Implementation order

1. Grammar: `class`, `interface`, `implements`, `extends`, `is`, `this`. Regenerate the parser.
2. Runtime: `BabbleClass`, `BabbleInstance` (an array of member values), `BabbleInterface`,
   bound methods over the new `Callable`. Retire `BabbleObject`'s parent fall-through and the
   on-the-fly binding of free functions.
3. Analysis pass, in the `BlockScopes` pass that grows into the `Resolver`:
   - member tables;
   - the `this`/`return` placement checks;
   - static resolution of `implements`/`extends`;
   - completeness and default-conflict checks.
4. Interpreter: class and object expressions, construction, member access, `is`, `toString`.
   Migrate `Stack.ba`, `Test1.ba`, the README and the test in §10.
5. Java: functional-interface applicability in `Overloads`, `Proxy`-based conversion, and
   explicit conversion through a Java interface's class value.
6. Compiler phase 1 compiles all of this, backed by `BabbleInstance`. Phase 2 moves to
   JVM classes and interfaces (§7), and phase 3 adds `LambdaMetafactory` adapters.

Steps 1–5 need nothing from the compiler beyond the shared runtime (compiler phase 0) and
block scope. Ideally they land before compiler phase 1 reaches `object` and member access
(compiler doc §15, step 7), so phase 1 never has to compile the old object semantics.
