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
- [x] optional type declarations for variables and parameters, not enforced yet (for documentation only).
- [x] "native" functions : `print`/`println`, `assert` (or `suppose`), `import`, `same`, `classof`.

Implementation features :

- [x] interpreted mode running on the parse tree, like 1.8.x Ruby, A.K.A. SLOW MODE.
- [x] compiled mode : scripts compiled to JVM bytecode with [ASM](https://asm.ow2.io/), behind the same API (phase 1 of the [bytecode compiler design](docs/design/bytecode-compiler.md)).
- [x] errors found before running : redefined names, misplaced `return`, `this` or `super`, incomplete or conflicting interface implementations, private members reached from outside.
- [x] about 100 lines of ANTLR4 grammar (see [Babble.g4](src/main/antlr4/org/babblelang/parser/Babble.g4)).
- [x] clean separation between grammar and implementation thanks to ANTLR4's parse tree & Visitor support. This allows the grammar to be reused in other contexts (IDEs ?) or by other languages (C# implementation ?).
- [x] [JSR 223](http://www.jcp.org/en/jsr/detail?id=223) support : `javax.script` compatible API.

Planned features
----------------

Language features :

- [ ] interactive mode.
- [ ] better, more user-friendly error reports.
- [ ] type checking, then type inference.
- [ ] collection classes (mapped from Java).
- [ ] first-class iterator support (`for` loop).
- [ ] `yield` keyword : transform functions/method into iterators.
- [ ] functional-style programming : `map`.

Implementation features :

- [ ] faster compiled mode : static scope resolution (JVM locals and fields instead of namespaces) and `invokedynamic` call sites, phases 2 and 3 of the [bytecode compiler design](docs/design/bytecode-compiler.md).
