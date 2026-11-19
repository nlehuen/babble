The Babble Language
===================

[![Build & Test](https://github.com/nlehuen/babble/actions/workflows/maven.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/maven.yml)
[![CodeQL](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml)

Babble is a toy language running on top of the Java Virtual Machine. Its objectives are :

* to teach myself about parsing, interpreting and compiling a simple programming language.
* to build a simple programming language that in turn can be used to teach programming to beginners.

Scripts run either in a tree-walking interpreter, or compiled to JVM bytecode. Both modes
give the same results, and the whole test suite runs in both. There is an interactive mode
to try things out :

```
$ mvn package
$ java -jar target/babble-1.0-SNAPSHOT.jar
Babble. :help for help, :quit to leave.
>> "Hello, " + "world"
"Hello, world"
```

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
    def add = (a: int, b: int) -> (    ;;
        a + b                          ;; function literal
    )                                  ;;

    ;; explicit return type
    def add2 = (a: int, b: int): int -> (
        a + b
    )

    ;; function as first-type objects
    ;; closure support
    def adder = (a: int): (b: int): int -> (
        return (b: int): int -> (
            a + b
        )
    )

    ;; loops
    def fac2 = (n: int): int -> (
        def result = 1
        while n > 1 then (
            ;; use ";" to put several statements on one line
            result = result * n ; n = n - 1
        )
        return result
    )

    ;; recursion
    def fac = (n: int): int -> (
        if n < 2 then (
            1
         ) else (
            n * recurse(n - 1)
         )
    )

    ;; default values, including function literals
    def apply = (a: int, f: (v: int): int = (v) -> (v)) -> ( f(a) )

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
def ints : java.util.List(int) = java.util.ArrayList()
ints.add(3) ; ints.add(1) ; ints.add(2)
ints.sort((a, b) -> ( a - b ))  ;; a Babble function passed as a Comparator
assert(ints.get(0) == 1)
```

A short tour
------------

### Everything is an expression

Blocks, `if` and `while` all have a value : a block is worth its last expression, `if`
without `else` is `null` when the test fails, and `while` is worth its last iteration. An
`else` is followed by a block, or by another `if`.

```
def sign = (n: int) -> ( if n > 0 then ( "positive" ) else if n < 0 then ( "negative" ) else ( "zero" ) )
assert(sign(-3) == "negative")

def i = 0
def last = while i < 3 then ( i = i + 1 ; i * 10 )
assert(last == 30)
```

### Numbers

Numbers are the JVM's : `byte`, `short`, `int` (32 bits, the default), `long` (`42L`), `float`
(`1.5f`) and `double` (`1.5`, `1e3`). An integer or decimal literal takes the number its context
expects when it fits (`def b: byte = 100`, `def f: float = 0.1`), and a number widens where a
wider one is expected, `byte` to `short` to `int` to `long` to `float` to `double`, but never
narrows. `+`, `-` and `*` promote as Java does : bytes, shorts and `int`s give an `int`, wrapping
on overflow, a `long` a `long`, a `float` a `float`, a `double` a `double`. `/` is never an integer
division : it gives a `float` when one operand is a `float` and neither a `long` or a `double`,
else a `double`, and dividing by zero gives an infinity or `NaN` rather than an error.
Comparisons and `==` compare numbers by value. (On instances, these operators call methods : see
*Value classes* below.)

`char` (`'a'`, `'\n'`, `'\u03bb'`) is a character, not a number : it compares with `<`, but
`'a' + 1` is an error, and `int(c)` / `char(n)` convert. Strings take the same escapes.

```
assert(7 / 2 == 3.5)
assert(4 / 2 == 2)              ;; 2.0, equal to 2
assert(1 / 0 == 1.0 / 0)        ;; Infinity
assert(2147483647 + 1 == -2147483648)
```

Each scalar's name converts : `byte(x)`, `short(x)`, `int(x)` and `long(x)` truncate a
floating-point number towards zero, take a character's code, and fail on a value out of their
range, an infinity or `NaN` ; `float(x)` and `double(x)` convert any number ; `bool(x)` and
`char(x)` take what is one already or a string ; `string(x)` formats anything. All parse a string.
There is no integer division operator : `int(a / b)` truncates the quotient, and
`java.lang.Math.floorDiv(a, b)` and `floorMod` round it down.

```
assert(int(2.7) == 2 and int(-2.7) == -2)
assert(int(7 / 2) == 3)
assert(int("42") + double("0.5") == 42.5)
assert(long("9000000000") * 2 == 18000000000L and char(int('a') + 1) == 'b')
```

Compound assignment applies an operator to a variable or a member : `x += v` is `x = x + v`,
and so are `-=`, `*=` and `/=`. The target is read before `v` is evaluated, and the receiver of
`obj.x += v` is evaluated once. Its value is the value assigned, checked against the target's
declared type as any assignment's is : `def n : int = 1 ; n /= 2` is a type error.

```
def total = 0
def i = 0
while i < 4 then (
    i += 1
    total += i
)
assert(total == 10)
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
def Account = class (def owner: string, def balance = 0) (     ;; "def" : the parameter is also a member
    private def history: java.util.List(int) = java.util.ArrayList()        ;; private : only reachable as this.history

    def deposit = mut (n: int) -> (
        this.history.add(n)
        this.balance += n
    )
    def operations = () -> ( this.history.size() )
)
static (
    def open = (owner: string) -> ( this(owner) )              ;; in a static block, this is the class
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

A value class compares by value, prints its fields, and its members are read-only. On any
instance, the arithmetic operators call methods : `a + b` is `a.plus(b)`, and `-`, `*`, `/` and
unary `-` call `minus`, `times`, `div` and `neg`, and so do `+=`, `-=`, `*=` and `/=` : `p += q`
is `p = p.plus(q)`. The left operand decides : `2 * p` is an error.

```
def Point = value class (x: double, y: double) (
    def plus = (o: Point): Point -> ( Point(this.x + o.x, this.y + o.y) )
    def times = (k: double): Point -> ( Point(this.x * k, this.y * k) )
)

assert(Point(1, 2).plus(Point(3, 4)) == Point(4, 6))
assert(Point(1, 2) + Point(3, 4) * 2 == Point(7, 10))
assert(Point(1, 2) == Point(1.0, 2.0))        ;; ints widen to double
assert(not same(Point(1, 2), Point(1, 2)))    ;; same() tests identity
assert("" + Point(1, 2) == "Point(x: 1.0, y: 2.0)")
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

def Square = class (def side: int) implements Shape (
    def area = (): double -> ( this.side * this.side )
)

def Circle = class (def r: int) implements Shape (
    def area = (): double -> ( 3 * this.r * this.r )
    def describe = () -> ( "round, " + super.describe() )
)

assert(Square(2).describe() == "area 4.0")
assert(Circle(1).describe() == "round, area 3.0")
assert(Square(2) is Shape)
```

A class that forgets a required member, or that inherits two different defaults with the
same name, is rejected before the script runs.

### Java interop

Java packages are reachable from `java` and `javax`, and any other one through
`import("org")`. Java objects have their public methods and fields, whether Babble constructed
them or a Java call returned them, and Java classes their public static fields. A `string` is a
`java.lang.String`, with its methods (`"abc".toUpperCase()`, `s.trim().length()`), while the
other scalars have no members. Babble functions convert to Java functional interfaces, and Babble
classes can implement Java interfaces.

The core collections are Java's : `list(T)` is a `java.util.List<T>`, `set(T)` a `Set<T>`,
`sortedset(T)` a `SortedSet<T>`, `map(K, V)` a `Map<K, V>`, `sortedmap(K, V)` a `SortedMap<K, V>`
and `entry(K, V)` a `Map.Entry<K, V>`, so any implementation does where one is expected. Their
names make them, with deterministic classes (`ArrayList`, `LinkedHashSet`, `TreeSet`,
`LinkedHashMap`, `TreeMap`), from elements or entries, or from `capacity:`, `from:` or, sorted,
`comparator:` ; their type arguments come from explicit ones, the expected type or the elements :

```
def xs = list(1, 2, 3)                           ;; list(int)
def ys: list(string) = list()
def counts = map(entry("a", 1), entry("b", 2))   ;; map(string, int)
def byLength = sortedset(comparator: (a: string, b: string): int -> (a.length() - b.length()))
def maybes = list(int?)(1, null)
assert(xs.get(0) + counts.getOrDefault("c", 0) == 1 and xs is list)
```

What Java returns is nullable unless Java says otherwise : `System.getenv(name)` is a `string?`,
`Integer.getInteger(name)` an `int?`, `map.get(k)` a `V?`, to narrow, navigate with `?.` or
assert with `!`. Primitives are never null, nor is what `@NotNull`/`@NonNull` annotations
(JetBrains, JSpecify, Jakarta... matched by name) or a JSpecify `@NullMarked` scope promise, nor
what the JDK's everyday classes return (`String`, `StringBuilder`, `Math`, the wrappers, streams,
collections : `list.get(i)` is as null as the list's elements, `map.getOrDefault(k, d)` a `V`).
Such a promise is checked where the value enters Babble, as Java doesn't enforce it.

```
def cores = java.lang.Runtime.getRuntime().availableProcessors()
def circle = 2 * java.lang.Math.PI
def point = java.awt.Point(1, 2)
point.x = point.x + 10

def ints: java.util.List(int) = java.util.ArrayList()
ints.add(3) ; ints.add(1) ; ints.add(2)
ints.removeIf((v) -> ( v > 2 ))          ;; a Predicate
assert(ints.size() == 2)

def Countdown = class (private def n: int) implements java.util.Iterator(int) (
    def hasNext = () -> ( this.n > 0 )
    def next = () -> ( this.n = this.n - 1 ; this.n + 1 )
)
def seen: java.util.List(int) = java.util.ArrayList()
Countdown(3).forEachRemaining((v) -> ( seen.add(v) ))   ;; a Java default method
assert(seen.toString() == "[3, 2, 1]")

def Money = value class (cents: int) implements java.lang.Comparable(Money) (
    def compareTo = (o: Money): int -> ( this.cents - o.cents )
)
def wallet: java.util.List(Money) = java.util.ArrayList()
wallet.add(Money(500)) ; wallet.add(Money(20))
java.util.Collections.sort(wallet)                 ;; Java calls Money.compareTo
assert(wallet.get(0) == Money(20))
```

### Modules

A file is a module : `export` marks the definitions it shares, and `import("file.ba")` runs
the file and gives its exports. The path is relative to the importing file.

```
;; shapes.ba
def pi = 3.14159                                   ;; not exported : private to this file
export def Shape = interface (
    def area : () : double
)
export def Circle = class (def r: double) implements Shape (
    def area = () -> ( pi * this.r * this.r )
)
export def unit = Circle(1)
```

```
;; main.ba
def shapes = import("shapes.ba")
def Circle = shapes.Circle                         ;; a local name for an export

assert(Circle(2).area() > shapes.unit.area())
assert(same(import("shapes.ba"), shapes))          ;; a file runs once, however often it is imported
```

A module has its own top-level names : it sees neither the names of the scripts importing it
nor those of other modules, and only its exports can be read from outside, where they are
read-only. Packages can be exported too (`export package units ( … )`), and files may import
each other, as long as none reads an export of another before its definition has run.

When the path is a string literal, the import is static : the file is read when the importing
script is compiled, so a missing or broken file, or a missing export, is reported before anything
runs, and classes can implement the interfaces it exports :

```
;; square.ba
def shapes = import("shapes.ba")
export def Square = class (def side: double) implements shapes.Shape (
    def area = () -> ( this.side * this.side )
)
```

An engine compiles each file once, until it or a file it imports changes. The type checker
sees the types of what modules export : `def c : shapes.Circle = shapes.Circle(2)` checks the
constructor's arguments and the annotation, and the annotation is also checked at
runtime.

### Types

Function and class parameters are inferred from their calls when every call is visible
(non-exported, non-escaping functions, classes, and non-interface methods), or from a default
value or expected function type ; exported, escaping, interface, and uncalled definitions need
parameter type annotations. Bindings, members and function results are inferred from their values
and assignments (including widening `int` to `double`), and every expression is checked before
the script runs :

```
def area = (w, h) -> ( w * h )
area(2, 3)      ;; infers w: int, h: int
area(2, "3")    ;; Error : Type error : expected int, got string ... at line number 3, column 9
area(2)         ;; Error : Missing parameter : h ...
```

`any` is the sound non-null top type for values whose static type is unknown (such as Java `Object`
returns like `list.get(0)` or script bindings), with `any?` as its nullable counterpart (plain
types are non-nullable; `T?` is shorthand for `T | null`, and `A | B` forms a union type).
Nothing can be called, selected or computed on an `any`, `T?` or mixed union until it is
narrowed (with `is`, `!= null`, or truthiness) or checked at runtime with `as` — or navigated
with `?.`, which short-circuits to `null` when the receiver is `null` (and is rejected when the
receiver is provably non-null) :

```
def id = (x: any?): any? -> ( x )
def half = (n: int): double -> ( n / 2 )
half(id("4") as int)   ;; Type error : expected int, got string, at line number 3
```

Functions, classes and interfaces can also take type parameters in parentheses `(T)` — Babble
never uses square brackets — and type arguments are inferred at call sites :

```
def Box(T) = class (def value: T) (
    def transform(U) = (fn: (x: T): U): Box(U) -> ( Box(fn(this.value)) )
)
def label: Box(string) = Box(21).transform((n) -> ( "v=" + n ))
def names: java.util.List(string) = java.util.ArrayList()
```

A mutable generic type is invariant : a `list(int)` is not a `list(int?)`, or `null` could be
added to it through the other name. Value classes, `entry` and `Optional` are covariant, and so
is what a `borrow` parameter that isn't `mut` takes, since it only reads it :

```
def ints = list(1, 2)
def maybes: list(int?) = ints                    ;; Type error : expected list(int?), got list(int)
def copy: list(int?) = list(from: ints)          ;; a copy is fine
def count = (borrow xs: java.util.Collection(any?)): int -> ( xs.size() )
```

Type arguments can also be given explicitly, before the call's : `Box(int?)(null)`,
`java.util.ArrayDeque(string)()`, `id(double)(1)`. A call whose callee is generic and whose
arguments are all types is a type application ; a curried call such as `add(a)(b)` stays a call.

The core type names — `byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`,
`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`, `any` and `void` (the type of `null`, what
a function returning nothing gives) — are keywords : no definition, parameter or package can take
one of them, so `int` always means the same thing. They remain member names, as Java's are
(`xs.set(0, v)`, `stream.map(f)`, `dir.list()`). Called as a function, a scalar type name converts
(`int("42")`, `double(1)`), and `x is int` tests a value's type.

A postfix `!` asserts that a value is not `null` : `e!` has the type of `e` without `null`, and
fails with `Type error : expected int, got null` when it is (a non-null `e` gets a
`Redundant non-null assertion` warning) :

```
def counts: java.util.Map(string, int) = java.util.HashMap()
counts.put("a", 1)
def a: int = counts.get("a")!     ;; int? -> int
```

### Function effects and contracts

Unannotated functions automatically infer their effects (`pure`, `hermetic`, `deterministic`,
`nothrow`, `borrow`) and algebraic properties (`idempotent`, `commutative`, `associative`,
`monotone`, `injective`), while explicit modifiers turn them into statically verified contracts.
Typed functions that mutate `this` or a parameter declare `mut` explicitly, and call sites check
that a `mut` argument does not alias another argument :

```
axiom pure deterministic nothrow java.lang.Math

def hypot = pure deterministic nothrow (x: double, y: double): double -> (
    java.lang.Math.sqrt(x * x + y * y)
)

def Counter = class (def value: int = 0) (
    def addFrom = hermetic mut (borrow other: Counter) -> (
        this.value += other.value
    )
)
def a = Counter(10) ; def b = Counter(5)
a.addFrom(b)
;; a.addFrom(a)   ;; Error : Exclusivity error : mut target a aliases argument a
```

`axiom` and `impure` declare trusted or conservative contracts on Java classes, constructors
(`Class()`), and methods, or Java hosts can annotate APIs with `@Pure`, `@Mut`, `@Borrow`,
`@Deterministic`, `@Nothrow`, and `@Impure` from `org.babblelang.effects` (or configure an engine
with `assumeEffects` / `allowAxioms(false)`). See [BIP007](docs/design/bip007_effects.md).

### Interactive mode

`java -jar target/babble-1.0-SNAPSHOT.jar` reads an input, evaluates it, prints its value, and
starts again. An input continues on the next line while it is incomplete : inside
parentheses, or after an operator, a comma, `=`, `->`, `then` or `else`.

```
>> def twice = (f: (n: int): int, v: int): int -> (
..   f(f(v))
.. )
function twice
>> twice((n) -> ( n + 1 ), 5)
7
>> def name = "world"
"world"
>> println("Hello, ", name)
Hello, world
>> def Point = value class (x: int, y: int) ()
class Point
>> Point(1, 2)
Point(x: 1, y: 2)
>> name + nosuch
Error : No such name : nosuch
```

Each input sees what the previous ones defined, classes and interfaces included, and goes
through the same checks as a script. Defining a name again replaces it for what you type
next ; functions defined before keep the definition they were written against, so functions
that call each other go in one input. To change a variable they see, assign it : `name = …`.

`:help` lists the few commands (`:cancel` drops an unfinished input, `:quit` leaves). The
switches below work here too : `java -Dbabble.mode=interpret -jar …`.
`java -jar … -keep-alive script.ba` runs a script first, and starts with what it defined.
There is no line editing or history ; `rlwrap java -jar …` adds both. The
[design note](docs/design/bip004_repl.md) has the details.

Ray tracing demo
----------------

[examples/raytracer-fable](examples/raytracer-fable/raytracer.ba) is a port of
[_Ray Tracing in One Weekend_](https://raytracing.github.io/books/RayTracingInOneWeekend.html)
to Babble : vectors as a value class, spheres and materials as classes implementing
interfaces, a bounding volume hierarchy, a camera with depth of field, and the final scene
of the book. It is split into modules along the files of the book (`rtweekend.ba`,
`vec3.ba`, `ray.ba`, `aabb.ba`, `hittable.ba`, `sphere.ba`, `hittable_list.ba`, `bvh.ba`,
`material.ba`, `camera.ba`), and `raytracer.ba` is its main. This image was rendered by it, in
compiled mode :

![The final scene of Ray Tracing in One Weekend, rendered by Babble](examples/raytracer-fable/demo.png)

It is all Babble, using the JDK for maths, random numbers, threads and the PNG file : there is
no Java code of its own, and no launcher. To render the image again, build the jar and hand it
the script :

```
mvn package
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer-fable/raytracer.ba
```

[examples/raytracer-argon](examples/raytracer-argon/README.md) is another port of the same book,
which uses no Java at all : it has its own square root, trigonometry and random numbers, and a
bounding volume hierarchy. It is split into modules too, in its `lib/` directory. Either ray
tracer can also be compiled ahead of time into a jar of its own (see *Building and running*) :

```
java -jar target/babble-1.0-SNAPSHOT.jar -compile examples/raytracer-argon/raytracer.ba -o argon.jar
java -jar argon.jar > scene.ppm
```

`argon.jar` is 273 KB for its 14 modules and the runtime, and renders the same image, byte for
byte, in about the same minute as through the engine : a jar saves the start-up, not the work.

Building and running
--------------------

Babble needs Java 21 and Maven :

```
mvn package
```

That builds the jar, with its dependencies next to it in `target/lib`, and runs the test suite
twice : once interpreted, once compiled to bytecode. The jar is Babble's command line :

```
java -jar target/babble-1.0-SNAPSHOT.jar                              # the interactive mode
java -jar target/babble-1.0-SNAPSHOT.jar script.ba [arguments...]     # runs a script
java -jar target/babble-1.0-SNAPSHOT.jar -keep-alive script.ba ...    # runs it, then the interactive mode
```

A script finds its arguments in `args`, a `java.util.List(string)` :

```
;; hello.ba
def i = 0
while i < args.size() then (
    println("Hello, ", args.get(i))
    i = i + 1
)
```

```
$ java -jar target/babble-1.0-SNAPSHOT.jar hello.ba Ada Grace
Hello, Ada
Hello, Grace
```

Its output goes to the standard output, and its errors and warnings to the error stream. The
exit status is 1 when the script fails, and 2 when it can't be read. With `-keep-alive`, the
interactive mode starts once the script is done, or has failed, with everything it defined :
handy to try out the functions of a file. Options go before the script ; what follows it is the
script's.

A program can also be compiled ahead of time, with the modules it imports, into a jar that runs
on a plain JRE, without Babble :

```
$ java -jar target/babble-1.0-SNAPSHOT.jar -compile hello.ba        # or -o app.jar, -root dir
Wrote /home/ada/hello.jar : 1 module
$ java -jar hello.jar Ada
Hello, Ada
```

The jar holds the program's classes and a copy of Babble's runtime (about 120 KB), and
nothing but `java.base` : no parser, no compiler, no ANTLR or ASM. It starts in about half the
time (a hello world in 100 to 140 ms, against 200 to 270 ms through the engine), compiling the
same program twice gives the same bytes, and errors read as they do through the engine. A
dynamic import only finds the modules the program imports statically. With Babble on the class path,
`java -cp hello.jar:target/babble-1.0-SNAPSHOT.jar org.babblelang.runtime.Launcher Ada`, the
others are compiled from their files when they are imported. Imports may not leave the root,
the script's directory by default (`-Dbabble.root=dir` tells a jar moved away from it). See [BIP006](docs/design/bip006_module-sources-and-aot.md) (§5).

### Formatting code

`-format` formats Babble source files :

```
java -jar target/babble-1.0-SNAPSHOT.jar -format < script.ba            # formats the standard input to the standard output
java -jar target/babble-1.0-SNAPSHOT.jar -format script.ba              # prints the file, formatted
java -jar target/babble-1.0-SNAPSHOT.jar -format -w script.ba examples  # rewrites the files that change ; a directory stands for its .ba files
java -jar target/babble-1.0-SNAPSHOT.jar -format -l examples            # lists the files that would change
java -jar target/babble-1.0-SNAPSHOT.jar -format -check examples        # lists them, and exits with status 1 if there are any
```

From Java, it is `org.babblelang.format.Formatter.format(source)`.

It keeps your line breaks, and settles the rest :

```
def fac=(n:int):int->(          def fac = (n: int): int -> (
  if n<2 then                       if n < 2 then (
  (                                     1  ;; done
    1 ;; done                       ) else (
  )                                     n * recurse(n - 1)
  else (                            )
      n*recurse( n-1 );         )
  )

)
```

- four spaces for each level of parentheses, and one more level for a line continuing an
  expression (after an operator, `=`, ...) ;
- one space around binary operators, `=` (and `+=`, `-=`, `*=`, `/=`) and `->`, after `,` and `:`, none inside parentheses
  nor before a call's ;
- a block's `(` comes back on the line of its `if`, `while`, function, class..., as do `else` and
  `static` ;
- one blank line at most, and none at the start or at the end of a block ;
- semicolons only where they separate two expressions of one line ;
- no trailing spaces, comments ending consecutive lines aligned, and one newline at the end of
  the file.

A file with a syntax error is reported and left as it is. The formatter parses its result, and
refuses to produce a text whose parse tree differs from the source's.

From Java, Babble is used through the standard
[JSR 223](http://www.jcp.org/en/jsr/detail?id=223) `javax.script` API :

```java
ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
engine.put("name", "world");
engine.eval("println(\"Hello, \", name)");
```

A script that imports modules by relative paths needs its own file name, which the host gives
with `engine.put(ScriptEngine.FILENAME, path)` ; without it, paths are relative to the working
directory.

Output goes to the `ScriptContext`'s writer, so a host can capture it with
`context.setWriter(...)`. Scripts are compiled to bytecode by default ; `-Dbabble.mode=interpret`,
or `engine.put("babble.mode", "interpret")`, runs them in the interpreter instead, which is much
slower but kept as the reference the compiler is tested against. For a look at what
the compiler generates, `-Dbabble.dumpClasses=<dir>` writes the class files for `javap -c -p`.
Type checking is always on, and declared types are enforced at runtime. Imported modules are files by default ; a host can serve them from
elsewhere, memory or a database, with `engine.put("babble.moduleSource", source)` and its own
`org.babblelang.engine.ModuleSource`.

A host can also keep a session, whose inputs see each other's definitions, as the
interactive mode does :

```java
BabbleSession session = ((BabbleScriptEngine) engine).newSession();
session.eval("def twice = (n: int): int -> ( n * 2 )");
session.eval("twice(21)");    // 42
```

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
- [x] basic types : the nine scalars (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`, with Java's promotion and widening, and `42L`, `1.5f`, `'a'` literals), `null`, functions, union types (`A | B`), nullable types (`T?`), `any` / `any?`, and generic/parameterized types (`Box(T)`, `java.util.List(string)`).
- [x] packages as namespaces, which change only through their own code : `p.x = 1` from outside is an error.
- [x] modules : a file exports some of its definitions with `export`, and another gets them with `import("file.ba")`.
- [x] object model : classes, object literals, interfaces with default methods, value classes, private and static members, `is`, `same` and `classof` (see [BIP002](docs/design/bip002_object-model.md)).
- [x] Java interop both ways : Java classes and generic types used directly from Babble, Babble functions passed as Java functional interfaces, and Babble classes implementing Java (including generic) interfaces.
- [x] static type checking : call-site parameter inference for non-exported/non-escaping callables, required annotations on exports, escapes, interfaces and uncalled functions, bidirectional local inference and generic call/constructor inference, strict non-nullability with `T?`, union types (`A | B`), safe navigation (`?.`), parameterized types (`Box(T)`, `list(T)`), invariant when mutable and covariant when immutable (value classes, `entry`) or read through a `borrow` parameter, flow-sensitive narrowing (`is`, `else` subtraction, `== null`/`!= null`/truthiness), checked `as` casts and sound top type `any` / `any?`, and runtime checks at casts and Java boundaries (see [BIP003](docs/design/bip003_type-system.md) and [BIP008](docs/design/bip008_static-typing.md)).
- [x] static function effects and algebraic contracts (`RM002`) : fixed-point inference and verification of `pure`, `hermetic`, `mut`, `borrow`, `deterministic`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone` and `injective`, static `mut` alias/exclusivity checking, higher-order call-site specialization, `axiom`/`impure` Java contracts, and `org.babblelang.effects` host annotations (see [BIP007](docs/design/bip007_effects.md)).
- [x] "native" functions : `print`/`println`, `assert` (or `suppose`), `import` (Java packages and Babble modules), `same`, `classof`, and the conversions `byte(x)`..`string(x)`.
- [x] core type names (`byte`..`string`, `list`..`entry`, `any`, `void`) are keywords that can't be redefined, but remain member names (`xs.set`, `stream.map`) ; the non-null assertion `e!` ; `string` methods are `java.lang.String`'s ; Java results nullable unless annotated or known non-null ; explicit type arguments `Box(int?)(null)` ; core collections `list`..`sortedmap` and `entry`, Java's interfaces with deterministic factories (see [BIP011](docs/design/bip011_core-types.md)).

Implementation features :

- [x] interpreted mode running on the parse tree, like 1.8.x Ruby, A.K.A. SLOW MODE : no longer the default, it is the reference implementation the compiler is tested against, and runs scripts on one thread only.
- [x] compiled mode, the default : scripts compiled to JVM bytecode with [ASM](https://asm.ow2.io/), behind the same API. Names are resolved at compile time, so variables are JVM locals, classes are JVM classes, and interfaces are real JVM interfaces with direct `INVOKEINTERFACE` dispatch and exact-signature Java `implements` bridges (`RM003`, §20), calls and member accesses are `invokedynamic` sites with inline caches (phases 1 to 3 of the [bytecode compiler design](docs/design/bip001_bytecode-compiler.md)), Java functional-interface callbacks use cached `LambdaMetafactory` adapters (`RM003`), and static types from `BIP008` lower non-nullable `int`, `double`, and `bool` locals, closure cells, class fields, arithmetic, comparisons, `$spec` calls, module exports, and resolved Java calls/fields directly to unboxed JVM instructions (`RM001`, §19) : 13x to 769x faster than the interpreter in the benchmarks, and about 22% faster end-to-end on `raytracer-argon`.
- [x] errors found before running : redefined names, misplaced `return`, `this` or `super`, incomplete or conflicting interface implementations, private members reached from outside.
- [x] about 100 lines of ANTLR4 grammar (see [Babble.g4](src/main/antlr4/org/babblelang/parser/Babble.g4)).
- [x] clean separation between grammar and implementation thanks to ANTLR4's parse tree & Visitor support. This allows the grammar to be reused in other contexts (IDEs ?) or by other languages (C# implementation ?).
- [x] [JSR 223](http://www.jcp.org/en/jsr/detail?id=223) support : `javax.script` compatible API.
- [x] interactive mode : a read-eval-print loop over sessions, in both modes and with the type checker (see [BIP004](docs/design/bip004_repl.md)).
- [x] command line : `java -jar babble.jar script.ba arguments...` runs a script, and `-keep-alive` continues in the interactive mode.
- [x] ahead-of-time compilation : `-compile script.ba` writes a jar of the program and the modules it imports, which runs on a plain JRE (see [BIP006](docs/design/bip006_module-sources-and-aot.md)).
- [x] syntax errors that say what was unexpected, and where.
- [x] a code formatter : `java -jar babble.jar -format`.

Planned features
----------------

Language features :

- [ ] line editing, history and completion in the interactive mode.
- [ ] better, more user-friendly error reports.
- [ ] structural object types.
- [ ] destructuring definitions : `def (Circle, unit) = import("shapes.ba")` binds the named members of
  a module, an object or a package, instead of one `def` per name.
- [ ] first-class iterator support (`for` loop).
- [ ] `yield` keyword : transform functions/method into iterators.
- [ ] functional-style programming : `map`.

Implementation features :

- [ ] deep ownership (`unique`, `move`, `freeze`), effect-driven bytecode optimizations, and `axiom` property fuzzing (see [BIP007](docs/design/bip007_effects.md)).
- [ ] a Maven goal for ahead-of-time compilation.

TODO : lessons from the ray tracer
----------------------------------

Writing [the ray tracer](examples/raytracer-fable/raytracer.ba) showed where Babble gets in the way. In rough
order of how much each would improve that code :

- [x] unary minus : `-x` and `-1.0` had to be written `0 - x` and `0 - 1.0`.
- [x] operator methods : let `a + b * t` call `plus` and `times` members, so vector maths doesn't
  read `a.plus(b.times(t))`.
- [x] members of Java values returned by Java calls : only objects constructed in Babble accepted
  method calls, so `list.get(0).size()` or `Runtime.getRuntime().availableProcessors()` failed with
  `Not a scope`. This is why the ray tracer had its own linked list. Any Java object now has its
  methods, however it was obtained, and the type checker follows chained calls : the ray tracer
  uses a `java.util.ArrayList`.
- [x] static fields of Java classes : `java.lang.Math.PI` or `BufferedImage.TYPE_INT_RGB` were
  not reachable, so the ray tracer computed π as `Math.acos(0 - 1)` and passed a bare `1`. They
  are now, with their types, as are the public fields of Java objects.
- [ ] bare names for a class's own members in its body, or at least for `def` header parameters :
  a plain header parameter can be read as `x` in the body, a `def` one only as `this.x`.
- [ ] `for` loops over ranges and collections (also in the planned features above) : every loop
  is a `while` with a counter and an increment that is easy to forget.
- [x] `else if`, without nesting a block : `else ( if … then ( … ) else ( … ) )`.
- [ ] `break` and `continue` in loops.
- [x] compound assignment : `+=`, `-=`, `*=`, `/=`, on variables and members, calling operator
  methods on instances. The ray tracers' counters and accumulators use them.
- [x] number conversions : `int(x)` and `double(x)` (and since, `byte(x)`..`string(x)`), instead of
  `Math.toIntExact(Math.round(Math.floor(x)))` or a binary decomposition in Babble.
- [x] line continuation before `.` and `?.`, to break long method chains across lines : a line may start
  with `.` or `?.`, continuing the expression above it.
- [x] importing other Babble files : `export` and `import("file.ba")`.
- [x] parse errors with a line, a column and a message : a syntax error such as using the keyword
  `object` as a name was reported as a bare `ParseCancellationException`. It is now
  `Syntax error : unexpected 'object'`, at its line and column.
- [x] command-line launcher : a `main` class in the jar to run a `.ba` file, with script arguments,
  instead of a launcher per project : `java -jar target/babble-1.0-SNAPSHOT.jar script.ba arguments...`,
  see *Building and running*. The ray tracers run that way, and their launchers are gone.
- [ ] thread safety : the ray tracer found a race in the Java member caches, now fixed, but nothing
  tests scripts running on several threads. And the interpreter cannot run a script's functions
  on several threads at all, since it keeps the current scope in a field : the fable ray tracer
  only renders in compiled mode.
- [x] unboxed arithmetic for typed code : `RM001` lowers non-nullable `int`, `double`, and `bool` locals,
  closure cells, class fields, arithmetic, comparisons, `$spec` calls, and statically resolved Java calls
  directly to primitive/direct bytecode (`argon` drops from 59.3 s to 46.1 s; cross-object `obj.x` unboxing
  and scalarized `value class` allocation remain for later optimizations).
