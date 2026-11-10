The Babble Language
===================

[![Build & Test](https://github.com/nlehuen/babble/actions/workflows/maven.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/maven.yml)
[![CodeQL](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml)

Babble is a toy language running on top of the Java Virtual Machine. Its objectives are :

* to teach myself about parsing, interpreting and compiling a simple programming language.
* to build a simple programming language that in turn can be used to teach programming to beginners.

Scripts run either in a tree-walking interpreter, or compiled to JVM bytecode. Both modes
give the same results, and the whole test suite runs in both.

What does it look like ?
-------------------------

```
;; comments

;;(
    multi-line
    comments
;;)

;; a package is just a namespace
package test1 (
    def add = (a, b) -> (    ;;
        a + b                ;; function literal
    )                        ;;

    ;; optional types
    def add2 = (a:int, b:int):int -> (
        a + b
    )

    ;; function as first-type objects
    ;; closure support
    def adder = (a:int):(b:int):int -> (
        return (b:int):int -> (
            a + b
        )
    )

    ;; loops
    def fac2 = (n:int):int -> (
        def result = 1
        while n > 1 then (
            ;; use ";" to put several statements on one line
            result = result * n ; n = n - 1
        )
        return result
    )

    ;; recursion
    def fac = (n:int):int -> (
        if n < 2 then (
            1
         ) else (
            n * recurse(n - 1)
         )
    )

    ;; default values, including function literals
    def apply = (a, f=(v) -> (v)) -> ( f(a) )

    ;; closures
    def counter = () -> (
        def c = 0

        () -> (
            c = c + 1
        )
    )
)

def main = () -> (
    def local = "world !"

    ;; println is a native function
    println ("Hello, ", local)

    ;; positional parameters
    println("0+1=", test1.add(0,1))
    ;; named parameters
    println("1+2=", test1.add(a:1,b:2))
    ;; named parameters can have their order changed
    println("2+3=", test1.add2(b:3,a:2))

    def result = test1.adder(2)(2)

    return "ok:" + result + ":" + test1.fac(5) + ":" + test1.fac2(6)
)

;; Assertions
assert (1 < 2, "Something is wrong")

assert (test1.apply(1) == 1, "apply is broken")
assert (test1.apply(1, (v) -> (v+1)) == 2, "apply is broken")

assert("ok:4:120:720" == main(), "Test failed")

;; testing counter
def c = test1.counter()
assert(1 == c())
def c2 = test1.counter()
assert(2 == c())
assert(1 == c2())
assert(3 == c())

;; using Java classes from Babble
def list = java.util.ArrayList()
list.add(3) ; list.add(1) ; list.add(2)
list.sort((a, b) -> ( a - b ))      ;; a Babble function passed as a Comparator
assert(list.get(0) == 1)
```

A short tour
------------

### Everything is an expression

Blocks, `if` and `while` all have a value : a block is worth its last expression, `if`
without `else` is `null` when the test fails, and `while` is worth its last iteration.

```
def sign = (n) -> ( if n > 0 then ( "positive" ) else ( if n < 0 then ( "negative" ) else ( "zero" ) ) )
assert(sign(0 - 3) == "negative")

def i = 0
def last = while i < 3 then ( i = i + 1 ; i * 10 )
assert(last == 30)
```

### Block scope

Blocks, `if` branches and `while` bodies are scopes. A name defined in a scope is visible
in the whole scope, but using it before its `def` has run is an error.

```
def total = 0
def i = 0
while i < 3 then (
    def square = i * i      ;; a new variable on every iteration
    total = total + square
    i = i + 1
)
assert(total == 5)
```

### Classes

A class is an expression too. Its header is the constructor's parameter list, its body runs
once per instance, and every `def` at the top of the body is a member. Members are always
reached through `this` or a receiver : a bare name is never a member.

```
def Account = class (def owner, def balance = 0) (     ;; "def" : the parameter is also a member
    private def history = java.util.ArrayList()        ;; private : only reachable as this.history

    def deposit = (n) -> (
        this.history.add(n)
        this.balance = this.balance + n
    )
    def operations = () -> ( this.history.size() )
)
static (
    def open = (owner) -> ( this(owner) )              ;; in a static block, this is the class
)

def a = Account.open("ann")
a.deposit(5) ; a.deposit(10)
assert(a.balance == 15 and a.operations() == 2)
assert(a is Account and classof(a) == Account)

;; an object literal is the single instance of an anonymous class
def origin = object (
    def x = 0
    def y = 0
)
assert(origin.x == 0)
```

### Value classes

A value class compares by value, prints its fields, and its members are read-only.

```
def Point = value class (x, y) (
    def plus = (o) -> ( Point(this.x + o.x, this.y + o.y) )
)

assert(Point(1, 2).plus(Point(3, 4)) == Point(4, 6))
assert(Point(1, 2) == Point(1.0, 2.0))        ;; numbers compare across types
assert(not same(Point(1, 2), Point(1, 2)))    ;; same() tests identity
assert("" + Point(1, 2) == "Point(x: 1, y: 2)")
```

### Interfaces

There is no class inheritance : classes reuse behaviour by implementing interfaces, which
declare required members and may provide default methods. An override can call the default
it replaces with `super`.

```
def Shape = interface (
    def area : () : double                                   ;; required
    def describe = () -> ( "area " + this.area() )           ;; a default method
)

def Square = class (def side) implements Shape (
    def area = () -> ( this.side * this.side )
)

def Circle = class (def r) implements Shape (
    def area = () -> ( 3 * this.r * this.r )
    def describe = () -> ( "round, " + super.describe() )
)

assert(Square(2).describe() == "area 4")
assert(Circle(1).describe() == "round, area 3")
assert(Square(2) is Shape)
```

A class that forgets a required member, or that inherits two different defaults with the
same name, is rejected before the script runs.

### Java interop

Java packages are reachable from `java` and `javax`, and any other one through
`import("org")`. Babble functions convert to Java functional interfaces, and Babble classes
can implement Java interfaces.

```
def list = java.util.ArrayList()
list.add(3) ; list.add(1) ; list.add(2)
list.removeIf((v) -> ( v > 2 ))                   ;; a Predicate
assert(list.size() == 2)

def Countdown = class (private def n) implements java.util.Iterator (
    def hasNext = () -> ( this.n > 0 )
    def next = () -> ( this.n = this.n - 1 ; this.n + 1 )
)
def seen = java.util.ArrayList()
Countdown(3).forEachRemaining((v) -> ( seen.add(v) ))   ;; a Java default method
assert(seen.toString() == "[3, 2, 1]")

def Money = value class (cents) implements java.lang.Comparable (
    def compareTo = (o) -> ( this.cents - o.cents )
)
def wallet = java.util.ArrayList()
wallet.add(Money(500)) ; wallet.add(Money(20))
java.util.Collections.sort(wallet)                 ;; Java calls Money.compareTo
assert(wallet.get(0) == Money(20))
```

### Optional types

Annotations are optional. With `-Dbabble.typecheck=warn`, a gradual type checker reads them
before the script runs, and reports what contradicts them, or is bound to fail, as warnings :

```
def area = (w : int, h : int) : int -> ( w * h )
area(2, "3")    ;; Warning : Type error : expected int, got string ... at line number 2, column 9
area(2)         ;; Warning : Missing parameter : h ...
```

Unannotated code is `dynamic`, and gets no type warnings. With `-Dbabble.typecheck=error`, the
same diagnostics are errors that stop the script from compiling, and declared types are also
checked at runtime, where an untyped value reaches them :

```
def id = (x) -> ( x )
def half = (n : int) : double -> ( n / 2 )
half(id("4"))   ;; Type error : expected int, got string, at line number 3
```

Ray tracing demo
----------------

[examples/raytracer-fable/raytracer.ba](examples/raytracer-fable/raytracer.ba) is a port of
[_Ray Tracing in One Weekend_](https://raytracing.github.io/books/RayTracingInOneWeekend.html)
to Babble : vectors as a value class, spheres and materials as classes implementing
interfaces, a camera with depth of field, and the final scene of the book. This image was
rendered by it, in compiled mode :

![The final scene of Ray Tracing in One Weekend, rendered by Babble](examples/raytracer-fable/demo.png)

The ray tracer itself is all Babble, using the JDK for maths, random numbers, threads and the
PNG file. The only Java code is [a dozen lines](examples/raytracer-fable/Run.java) that hand the script to
the engine. To render the image again :

```
examples/raytracer-fable/render.sh
```

[examples/raytracer-argon](examples/raytracer-argon/README.md) is another port of the same book,
which uses no Java at all : it has its own square root, trigonometry and random numbers, and a
bounding volume hierarchy.

Building and running
--------------------

Babble needs Java 21 and Maven :

```
mvn package
```

That builds the jar and runs the test suite twice : once interpreted, once compiled to
bytecode. Babble is used through the standard [JSR 223](http://www.jcp.org/en/jsr/detail?id=223)
`javax.script` API :

```java
ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
engine.put("name", "world");
engine.eval("println(\"Hello, \", name)");
```

Output goes to the `ScriptContext`'s writer, so a host can capture it with
`context.setWriter(...)`. Scripts are interpreted by default ; `-Dbabble.mode=compile`, or
`engine.put("babble.mode", "compile")`, compiles them to bytecode instead. For a look at what
the compiler generates, `-Dbabble.dumpClasses=<dir>` writes the class files for `javap -c -p`.
`-Dbabble.typecheck=warn`, or `engine.put("babble.typecheck", "warn")`, turns the type checker
on : its warnings go to the context's error writer. `error` makes them errors, and enforces
declared types at runtime.

Implemented Features
--------------------

Language features :

- [x] no C-style brackets ! Those are nasty to type on most keyboards, especially on Apple keyboards.
- [x] in fact, there is only one type of brackets in Babble : parentheses `( )`. The grammar is simple enough, and the parser clever enough (thanks to [ANTLR4](https://www.antlr.org/)) to get away with it.
- [x] no semicolons at end of line : newlines are significant and end the current statement, except after tokens that call for a continuation (operators, `,`, `=`, `->`, `then`, ...). Semicolons are only needed to separate multiple statements on the same line.
- [x] everything is an expression, including blocks, `if` and `while`.
- [x] block scope : blocks, `if` branches and `while` bodies are scopes, and names are hoisted to the start of their scope.
- [x] functions, supporting recursion (`recurse`) and closures.
- [x] first-class function objects, functions are stored in variables and can be passed around as parameters.
- [x] positional & named parameters in function calls, and default values for parameters.
- [x] basic types : boolean, int, double, string, functions.
- [x] packages as namespaces.
- [x] object model : classes, object literals, interfaces with default methods, value classes, private and static members, `is`, `same` and `classof` (see the [design doc](docs/design/object-model.md)).
- [x] Java interop both ways : Java classes used directly from Babble, Babble functions passed as Java functional interfaces, and Babble classes implementing Java interfaces.
- [x] optional type declarations for variables and parameters, checked by a gradual type checker (`-Dbabble.typecheck=warn` or `error`, see the [design doc](docs/design/type-system.md)) : it reports values of the wrong type, calls that don't match a signature, missing members and the like, before the script runs. In error mode, declared types are also enforced at runtime, where untyped values reach them.
- [x] "native" functions : `print`/`println`, `assert` (or `suppose`), `import`, `same`, `classof`.

Implementation features :

- [x] interpreted mode running on the parse tree, like 1.8.x Ruby, A.K.A. SLOW MODE.
- [x] compiled mode : scripts compiled to JVM bytecode with [ASM](https://asm.ow2.io/), behind the same API. Names are resolved at compile time, so variables are JVM locals and classes are JVM classes, and calls and member accesses are `invokedynamic` sites with inline caches (phases 1 to 3 of the [bytecode compiler design](docs/design/bytecode-compiler.md)) : 25 to 55 times faster than the interpreter on calls, loops, closures and objects in the benchmarks, and Java calls no longer re-resolve overloads each time.
- [x] errors found before running : redefined names, misplaced `return`, `this` or `super`, incomplete or conflicting interface implementations, private members reached from outside.
- [x] about 100 lines of ANTLR4 grammar (see [Babble.g4](src/main/antlr4/org/babblelang/parser/Babble.g4)).
- [x] clean separation between grammar and implementation thanks to ANTLR4's parse tree & Visitor support. This allows the grammar to be reused in other contexts (IDEs ?) or by other languages (C# implementation ?).
- [x] [JSR 223](http://www.jcp.org/en/jsr/detail?id=223) support : `javax.script` compatible API.

Planned features
----------------

Language features :

- [ ] interactive mode.
- [ ] better, more user-friendly error reports.
- [ ] fuller type inference, narrowing after `is`, and error mode by default.
- [ ] collection classes (mapped from Java).
- [ ] first-class iterator support (`for` loop).
- [ ] `yield` keyword : transform functions/method into iterators.
- [ ] functional-style programming : `map`.

Implementation features :

- [ ] type specialisation in compiled mode (unboxed arithmetic), once the type checker can vouch for the types.
- [ ] ahead-of-time compilation to a jar, phase 4 of the [bytecode compiler design](docs/design/bytecode-compiler.md).

TODO : lessons from the ray tracer
----------------------------------

Writing [the ray tracer](examples/raytracer-fable/raytracer.ba) showed where Babble gets in the way. In rough
order of how much each would improve that code :

- [ ] unary minus : `-x` and `-1.0` have to be written `0 - x` and `0 - 1.0`.
- [ ] operator methods : let `a + b * t` call `plus` and `times` members, so vector maths doesn't
  read `a.plus(b.times(t))`.
- [ ] members of Java values returned by Java calls : only objects constructed in Babble accept
  method calls, so `list.get(0).size()` or `Runtime.getRuntime().availableProcessors()` fail with
  `Not a scope`. This is why the ray tracer has its own linked list.
- [ ] static fields of Java classes : `java.lang.Math.PI` or `BufferedImage.TYPE_INT_RGB` are
  not reachable.
- [ ] bare names for a class's own members in its body, or at least for `def` header parameters :
  a plain header parameter can be read as `x` in the body, a `def` one only as `this.x`.
- [ ] `for` loops over ranges and collections (also in the planned features above) : every loop
  is a `while` with a counter and an increment that is easy to forget.
- [ ] `else if`, without nesting a block : `else ( if … then ( … ) else ( … ) )`.
- [ ] `break` and `continue` in loops.
- [ ] compound assignment : `+=`, `-=`, `*=`, `/=`.
- [ ] number conversions : an `int(x)` native or an integer division operator, instead of
  `Math.toIntExact(Math.round(Math.floor(x)))`.
- [ ] line continuation before `.`, to break long method chains across lines.
- [ ] importing other Babble files : `import` only reaches Java packages, so a program is one file.
- [ ] parse errors with a line, a column and a message : a syntax error such as using the keyword
  `object` as a name is reported as a bare `ParseCancellationException`.
- [ ] command-line launcher : a `main` class in the jar to run a `.ba` file, with script arguments,
  instead of [a launcher per project](examples/raytracer-fable/Run.java).
- [ ] thread safety : the ray tracer found a race in the Java member caches, now fixed, but nothing
  tests scripts running on several threads.
- [ ] unboxed arithmetic for typed code (also planned above) : the ray tracer spends its time
  boxing doubles and allocating vectors, about 150 ns per ray-sphere test.
