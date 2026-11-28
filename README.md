The Babble Language
===================

[![Build & Test](https://github.com/nlehuen/babble/actions/workflows/maven.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/maven.yml)
[![CodeQL](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/nlehuen/babble/actions/workflows/codeql-analysis.yml)

> **AI use disclosure**
>
> This project started on 2013-12-06 and was manually authored by a real, meat-made human up to 2022-05-25. Starting on 2026-07-16 and commit `c8afb703272cf5375675cb5838927a5762667ae3`, [nlehuen](https://github.com/nlehuen) began using a mix of open-source and commercial AI models from different providers.
>
> Initially [nlehuen](https://github.com/nlehuen) mainly used [Claude Code](https://claude.ai/code) but lately largely transitioned to using [Antigravity](https://antigravity.google/) and the Google Gemini family of models. [nlehuen](https://github.com/nlehuen) works at Google, but the entirety of this project has been done on personal time, using personal resources (i.e. personal funding of Claude + Antigravity access).

Babble is a toy language running on top of the Java Virtual Machine. Its objectives are :

* to teach myself ([nlehuen](https://github.com/nlehuen)) about parsing, interpreting and compiling a simple programming language.
* to build a simple programming language that in turn can be used to teach programming to beginners.

Scripts run either in a tree-walking interpreter, or compiled to JVM bytecode. Both modes
give the same results, and the whole test suite runs in both.

Get started
-----------

Babble needs Java 21 or later, and Maven. Build the jar (`-DskipTests` skips the test suite),
then start the interactive mode to try things out :

```
$ mvn package -DskipTests
$ java -jar target/babble-1.0-SNAPSHOT.jar
Babble. :help for help, :quit to leave.
>> "Hello, " + "world"
"Hello, world"
```

`:help` lists the commands : `:type expr` shows the type of an expression, `:load file.ba` runs a
file as if it was typed, `:quit` (or Ctrl-D) leaves. The same jar runs scripts too :

```
java -jar target/babble-1.0-SNAPSHOT.jar -O2                          # the interactive mode at -O0, -O1 (the default) or -O2
java -Dbabble.mode=interpret -jar target/babble-1.0-SNAPSHOT.jar      # in the interpreter instead of the compiler
java -jar target/babble-1.0-SNAPSHOT.jar script.ba [arguments...]     # runs a script
java -jar target/babble-1.0-SNAPSHOT.jar -keep-alive script.ba        # runs it, then the interactive mode
```

*Interactive mode* and *Building and running* below have the details.

What does it look like ?
-------------------------

```
;; comments

;;(
    multi-line
    comments
;;)

def add = fn (a: int, b: int) -> ( ;;
    a + b                          ;; function literal
)                                  ;;

;; explicit return type
def add2 = fn (a: int, b: int): int -> (
    a + b
)

;; function as first-type objects
;; closure support
def adder = fn (a: int): fn (b: int): int -> (
    return fn (b: int): int -> (
        a + b
    )
)

;; loops (`def` bindings are immutable; use `var` when a binding is reassigned)
def fac2 = fn (var n: int): int -> (
    var result = 1
    while n > 1 then (
        ;; use ";" to put several statements on one line
        result = result * n ; n = n - 1
    )
    return result
)

;; recursion
def fac = fn (n: int): int -> (
    if n < 2 then (
        1
     ) else (
        n * recurse(n - 1)
     )
)

;; a call of a function to itself in tail position is a loop : no stack overflow
def sumTo = fn (n: int, acc: int): int -> (
    if n == 0 then (acc) else (recurse(n - 1, acc + n))
)
sumTo(1000000, 0)

;; code that can never end and cannot throw is an error, unless forever says it is intended
def spin = fn (n: int): int -> (forever recurse(n + 1))

;; default values, including function literals
def apply = fn (a: int, f: fn (v: int): int = fn (v) -> (v)) -> ( f(a) )

;; closures
def counter = fn () -> (
    var c = 0

    fn io () -> (
        c = c + 1
    )
)

def main = fn () -> (
    def local = "world !"

    ;; println is a native function
    println ("Hello, ", local)

    ;; positional parameters
    println("0+1=", add(0,1))
    ;; named parameters
    println("1+2=", add(a:1,b:2))
    ;; named parameters can have their order changed
    println("2+3=", add2(b:3,a:2))

    def result = adder(2)(2)

    return "ok:" + result + ":" + fac(5) + ":" + fac2(6)
)

;; Assertions
assert (1 < 2, "Something is wrong")

assert (test1.apply(1) == 1, "apply is broken")
assert (test1.apply(1, fn (v) -> (v+1)) == 2, "apply is broken")

assert("ok:4:120:720" == main(), "Test failed")

;; testing counter
def c = test1.counter()
assert(1 == c())
def c2 = test1.counter()
assert(2 == c())
assert(1 == c2())
assert(3 == c())

;; using Java classes from Babble
def list : java.util.List of int = java.util.ArrayList()
list.add(3) ; list.add(1) ; list.add(2)
list.sort(fn (a, b) -> ( a - b ))  ;; a Babble function passed as a Comparator
assert(list.get(0) == 1)
```

A short tour
------------

### Everything is an expression

Blocks, `if` and `while` all have a value : a block is worth its last expression, `if`
without `else` is `null` when the test fails, and `while` is worth its last iteration. An
`else` is followed by a block, or by another `if`.

```
def sign = fn (n: int) -> ( if n > 0 then ( "positive" ) else if n < 0 then ( "negative" ) else ( "zero" ) )
assert(sign(-3) == "negative")

def i = 0
def last = while i < 3 then ( i = i + 1 ; i * 10 )
assert(last == 30)
```

### Strings

Plain `"..."` and raw `r"..."` strings are strictly single-line (`r"..."` treats backslashes
literally, so trailing backslashes like `r"C:\dir\"` work without escaping). Multiline `"""..."""`
and raw multiline `r"""..."""` blocks require a line break after the opening delimiter and strip
incidental leading indentation at compile time anchored on the closing `"""` (Java JEP 378 rules,
rejecting mixed leading tabs and spaces and stripping trailing horizontal whitespace; `"""..."""`
also supports `\s` and `\<newline>`). See [BIP026](docs/design/bip026_multiline-strings-and-string-templates.md).

```
def path = r"C:\Users\ada\"
def query = """
    SELECT id, name
        FROM users \
    WHERE active = 1  \s
    """
def pattern = r"""
    ^\s*([a-zA-Z_]\w*)\s*=\s*"([^"]*)"\s*$
    """
```

### Numbers

Numbers are `int`s (32 bits) or `double`s. `+`, `-` and `*` on two `int`s give an `int`, wrapping
on overflow; any other mix gives a `double`. `/` always gives a `double`, even between two `int`s,
and dividing by zero gives an infinity or `NaN` rather than an error. Comparisons and `==`
compare an `int` and a `double` by value. (On instances, these operators call methods : see
*Records* below.)

```
assert(7 / 2 == 3.5)
assert(4 / 2 == 2)              ;; 2.0, equal to 2
assert(1 / 0 == 1.0 / 0)        ;; Infinity
assert(2147483647 + 1 == -2147483648)
```

`int(x)` and `double(x)` convert : `int` truncates a `double` towards zero, and fails on one
out of the `int` range, an infinity or `NaN` ; `double` widens an `int`. Both parse a string.
`//` and `mod` perform floored integer division and modulo (`Math.floorDiv` and `Math.floorMod`
semantics, so `a == b * (a // b) + a mod b` and `a mod b` takes the divisor's sign) on integral
scalars (`byte`/`short`/`int` → `int`, `long` → `long`), and `//=` is the compound assignment.

```
assert(int(2.7) == 2 and int(-2.7) == -2)
assert(7 // 2 == 3 and -7 // 2 == -4)
assert(10 mod 3 == 1 and -1 mod 5 == 4)
assert(int("42") + double("0.5") == 42.5)
```

Compound assignment applies an operator to a variable or a member : `x += v` is `x = x + v`,
and so are `-=`, `*=`, `/=` and `//=`. The target is read before `v` is evaluated, and the receiver of
`obj.x += v` is evaluated once. Its value is the value assigned, checked against the target's
declared type as any assignment's is : `var n : int = 1 ; n /= 2` is a type error.

```
var total = 0
var i = 0
while i < 4 then (
    i += 1
    total += i
)
assert(total == 10)
```

### Ranges

`lo..hi` (inclusive) and `lo..<hi` (half-open) create first-class immutable covariant `range of T`
values for any ordered type (`.contains(x)`, `.start()`, `.end()`, `.inclusive()`, `.isEmpty()`).
Discrete ranges (`byte`, `short`, `int`, `long`, `char`) are `Iterable` progressions supporting
`(lo..hi).step(k)` and `.reversed()`, and `for i in lo..hi` / `lo..<hi` / `(lo..<hi).step(k).reversed()`
compiles to an allocation-free counted primitive loop on both the JVM and WebAssembly 3.0
(see [BIP031](docs/design/bip031_ranges.md)).

```
var sum = 0
for i in 0..<10 then (
    sum += i
)
assert(sum == 45)
assert((3..<10).step(5).contains(8) and not (3..<10).step(5).contains(5))
assert((0.0..<1.0).contains(0.5) and ("alpha".."omega").contains("beta"))
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

### Exception handling

`try`, `catch`, `finally`, and `throw` are expressions. `catch` uses Babble's union types `A | B` for multi-catch, omits the variable name when unused (`catch : T then (...)`), and provides an untyped non-`Error` catch-all (`catch e then (...)` or `catch then (...)`). Any Java `Throwable`, Babble's core `exception` type (`BabbleException`), or any custom Babble class/record/interface value may be thrown and caught; `finally` runs on every exit and attaches secondary exceptions with `addSuppressed`.

```
def ParseError = record (line: int, message: string) ()

def parsePort = fn io nothrow (text: string): int -> (
    try (
        def n = java.lang.Integer.parseInt(text)
        if n < 1 or n > 65535 then (
            throw exception("port out of range : " + text)
        )
        n
    ) catch : java.lang.NumberFormatException | exception then (
        8080
    ) catch then (
        8080
    )
)
assert(parsePort("80") == 80 and parsePort("bad") == 8080)

def r = try (
    throw ParseError(4, "expected int")
) catch e: ParseError then (
    "line " + e.line + " : " + e.message
) finally (
    ;; cleanup runs on normal exit, exception, return, break, or continue
)
assert(r == "line 4 : expected int")
```

### Classes

A class is an expression too. Its body is declarative (containing fields, methods, and optional
`init` constructor clauses), and members are always reached through `this` or a receiver : a bare
name is never a member. Header parameters define the primary constructor, and `init (...)` runs
constructor-local logic (initializing uninitialized `def`/`var` fields without storing temporaries
as fields). An empty header `class ()` can also define explicit or overloaded `[private] init
(params) -> (...)` constructors, and secondary constructors delegate via `this(...)` (see
[BIP040](docs/design/bip040_constructors.md)).

```
def Account = class (def owner: string, var balance = 0) (     ;; "def"/"var" : the parameter is also a member
    private def history: java.util.List of int = java.util.ArrayList()        ;; private : only reachable as this.history

    init (
        assert(this.balance >= 0)
    )

    def deposit = fn mut (n: int) -> (
        this.history.add(n)
        this.balance += n
    )
    def operations = fn () -> ( this.history.size() )
)
static (
    def open = fn (owner: string) -> ( this(owner) )           ;; in a static block, this is the class
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

### Records

A record compares by value, prints its fields, and its members are read-only. On any
instance, the arithmetic operators call methods : `a + b` is `a.plus(b)`, and `-`, `*`, `/` and
unary `-` call `minus`, `times`, `div` and `neg`, and so do `+=`, `-=`, `*=` and `/=` : `p += q`
is `p = p.plus(q)`. The left operand decides : `2 * p` is an error.

```
def Point = record (x: double, y: double) (
    def plus = fn (o: Point): Point -> ( Point(this.x + o.x, this.y + o.y) )
    def times = overload (
        fn (k: double): Point -> ( Point(this.x * k, this.y * k) ),
        fn (o: Point): Point -> ( Point(this.x * o.x, this.y * o.y) )
    )
)

assert(Point(1, 2).plus(Point(3, 4)) == Point(4, 6))
assert(Point(1, 2) + Point(3, 4) * 2 == Point(7, 10))
assert(Point(2, 3) * Point(4, 5) == Point(8, 15))
assert(Point(1, 2) == Point(1.0, 2.0))        ;; ints widen to double
assert(not same(Point(1, 2), Point(1, 2)))    ;; same() tests identity
assert("" + Point(1, 2) == "Point(x: 1.0, y: 2.0)")
```

### Variadic parameters, spread and overloads

A rest parameter `xs: T...` accepts any number of `T` arguments (or a spread `...seq` from any
`Iterable`, `list` or `array`) and is an `Iterable of T` inside the body ; parameters after a
rest or a bare `...` are keyword-only. Closed overload sets `overload (f, g, ...)` group
several typed signatures under one name — rejecting any possible ambiguity where the set is
built, resolving statically to the most specific alternative, and splitting union arguments by
runtime class (see [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md)) :

```
def join = fn (parts: string..., sep: string = ", "): string -> (
    def sb = java.lang.StringBuilder()
    for p in parts then ( if sb.length() > 0 then ( sb.append(sep) ) ; sb.append(p) )
    sb.toString()
)
assert(join("a", "b", "c") == "a, b, c")
assert(join(...list("x", "y"), sep: " / ") == "x / y")

def show = overload (
    fn (n: int): string -> ( "int:" + n ),
    fn (s: string): string -> ( "string:" + s )
)
def showAny = fn (x: int | string): string -> ( show(x) )
assert(show(42) == "int:42" and showAny("hi") == "string:hi")
```

### Interfaces

There is no class inheritance : classes reuse behaviour by implementing interfaces, which
declare required members and may provide default methods. An override can call the default
it replaces with `super`.

```
def Shape = interface (
    def area : fn () : double                                ;; required
    def describe = fn () -> ( "area " + this.area() )        ;; a default method
)

def Square = class (def side: int) implements Shape (
    def area = fn (): double -> ( this.side * this.side )
)

def Circle = class (def r: int) implements Shape (
    def area = fn (): double -> ( 3 * this.r * this.r )
    def describe = fn () -> ( "round, " + super.describe() )
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
them or a Java call returned them, and Java classes their public static fields. Babble functions
convert to Java functional interfaces, and Babble classes can implement Java interfaces.

```
def cores = java.lang.Runtime.getRuntime().availableProcessors()
def circle = 2 * java.lang.Math.PI
def point = java.awt.Point(1, 2)
point.x = point.x + 10

def list: java.util.List of int = java.util.ArrayList()
list.add(3) ; list.add(1) ; list.add(2)
list.removeIf(fn (v) -> ( v > 2 ))          ;; a Predicate
assert(list.size() == 2)

def Countdown = class (private var n: int) implements java.util.Iterator of int (
    def hasNext = fn () -> ( this.n > 0 )
    def next = fn mut () -> ( this.n = this.n - 1 ; this.n + 1 )
)
def seen: java.util.List of int = java.util.ArrayList()
Countdown(3).forEachRemaining(fn (v) -> ( seen.add(v) ))   ;; a Java default method
assert(seen.toString() == "[3, 2, 1]")

def Money = record (cents: int) implements java.lang.Comparable of Money (
    def compareTo = fn (o: Money): int -> ( this.cents - o.cents )
)
def wallet: java.util.List of Money = java.util.ArrayList()
wallet.add(Money(500)) ; wallet.add(Money(20))
java.util.Collections.sort(wallet)                 ;; Java calls Money.compareTo
assert(wallet.get(0) == Money(20))

;; Java varargs methods are resolved directly (BIP021-I2)
assert(java.lang.String.format("%s=%d", "n", 7) == "n=7")
assert(java.util.Arrays.asList("a", "b").size() == 2)
```

### Modules

A file is a module : `export` marks the definitions it shares, and `import("file.ba")` runs
the file and gives its exports. The path is relative to the importing file.

```
;; shapes.ba
def pi = 3.14159                                   ;; not exported : private to this file
export def Shape = interface (
    def area : fn () : double
)
export def Circle = class (def r: double) implements Shape (
    def area = fn () -> ( pi * this.r * this.r )
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
read-only. Files may import each other, as long as none reads an export of another before its
definition has run.

When the path is a string literal, the import is static : the file is read when the importing
script is compiled, so a missing or broken file, or a missing export, is reported before anything
runs, and classes can implement the interfaces it exports :

```
;; square.ba
def shapes = import("shapes.ba")
export def Square = class (def side: double) implements shapes.Shape (
    def area = fn () -> ( this.side * this.side )
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
def area = fn (w, h) -> ( w * h )
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
def id = fn (x: any?): any? -> ( x )
def half = fn (n: int): double -> ( n / 2 )
half(id("4") as int)   ;; Type error : expected int, got string, at line number 3
```

Since `T?` is `T | null`, the value `null` belongs to every nullable type : `null is T?` is
`true` and `null as T?` succeeds whatever `T` is, even an interface the declared class never
implements, while `null is T` is `false` and `null as T` fails. A cast is rejected statically
only when the two types share no value, and `null` is one they share once both are nullable :

```
def d: Disk? = null
d is Named?            ;; true
d as Named?            ;; null
d is Named             ;; false
def i: int? = null
i as string?           ;; null : int? and string? share null
i as string            ;; Type error : expected string, got int?, before running
```

Functions, classes and interfaces can also take type parameters with `of` — Babble never uses
square or angle brackets — with optional upper bounds `of (T: B)` (including F-bounds and
intersection bounds `of (T: A & B)`), generic function literals `fn of T`, and first-class
polymorphic function types (see [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md)) ;
type arguments are inferred at call sites :

```
def Box of T = class (def value: T) (
    def map of U = fn (f: fn (x: T): U): Box of U -> ( Box(f(this.value)) )
)
def label: Box of string = Box(21).map(fn (n) -> ( "v=" + n ))
def names: java.util.List of string = java.util.ArrayList()

def biggest of (T: java.lang.Comparable of T) = fn (a: T, b: T): T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
def id = fn of T (t: T): T -> ( t )                  ;; fn of T (t : T) : T
def both = fn (f: fn of T (t: T): T): string -> ( "" + f(1) + f("a") )
assert(biggest(3, 7) == 7 and both(id) == "1a")
```

Function types can also mark optional parameters with `= ...` and keyword-only parameters after
`...`, and named arguments are bound statically against the callee's parameter list :

```
def Shape = interface (
    def scale: fn (k: double, ..., around: Point = ...): Shape
)
```

An intersection `A & B` of interface types holds the values that are both, and has the members
of both ; `&` binds tighter than `|`, and `is` narrows to an intersection instead of forgetting
what it knew (see [BIP022](docs/design/bip022_intersection-types.md)) :

```
def describe = fn (x: Shape & Named): string -> ( x.name() + " " + x.area() )
def s: Shape = pick()
if s is Named then ( describe(s) )      ;; s : Shape & Named
def maybe: (Shape & Named)? = null
```

A type alias names a type, so that a long one is written once ; it is transparent, can take type
parameters, and can be exported (see [BIP023](docs/design/bip023_type-aliases.md)) :

```
def ShapeWithText = type Shape & (Named | Label)
def Pair of (A, B) = type entry of (A, B)
def p: Pair of (string, int) = entry("a", 1)
```

### Arrays

`array of T` is Babble's type for plain JVM arrays (`int[]`, `String[]`, `Point[]`, nested arrays — zero wrapper object), created by length (when `T` has a default `0`/`false`/`'\0'`/`null`), from elements with `.of(...)`, or copied from any iterable with `from:` (see [BIP020](docs/design/bip020_array-types.md)) :

```
def scores: array of int = (array of int)(3)
scores.set(0, 10) ; scores.set(1, 20)
assert(scores.size() == 3 and scores.get(1) == 20)

def words = array.of("alpha", "beta")               ;; array of string
def copied = (array of double)(from: scores)         ;; array of double : [10.0, 20.0, 0.0]
assert(scores == array.of(10, 20, 0))                ;; == and printing inspect contents
assert("" + words == "[alpha, beta]")

def parts: array of string = "a,b,c".split(",")      ;; Java arrays pass directly both ways
java.util.Arrays.sort(parts)
```

### Function effects and contracts

Unannotated functions automatically infer their effects (`pure`, `hermetic`, `deterministic`,
`nothrow`, `borrow`) and algebraic properties (`idempotent`, `commutative`, `associative`,
`monotone`, `injective`), while explicit modifiers turn them into statically verified contracts.
Typed functions that mutate `this` or a parameter declare `mut` explicitly, and call sites check
that a `mut` argument does not alias another argument :

```
axiom java.lang.Math.sqrt: fn nothrow foldable (a: double): double

def hypot = fn nothrow (x: double, y: double): double -> (
    java.lang.Math.sqrt(x * x + y * y)
)

def Counter = class (def value: int = 0) (
    def addFrom = fn mut (other: Counter) -> (
        this.value += other.value
    )
)
def a = Counter(10) ; def b = Counter(5)
a.addFrom(b)
;; a.addFrom(a)   ;; Error : Exclusivity error : mut target a aliases argument a
```

`axiom Target: fn ...` and `axiom Target: class notnull ( ... )` declare trusted contracts on Java
classes, methods, constructors, or Babble host bindings (`fn ... : T!` and `fn ... (…): T!` keep
bytecode-inferred effects while stating explicit return/parameter nullability with `!` or `?`);
built-in assumptions about JDK classes, third-party nullability annotations, optimizer folding, and
ordinary built-ins live in `src/main/java/org/babblelang/axioms/` while everything else is inferred
from JVM bytecode on the fly (`java -jar target/babble-1.0-SNAPSHOT.jar -axioms java.util.ArrayList`
dumps what the compiler assumes or infers). Java hosts can also annotate APIs with `@Pure`, `@Mut`,
`@Shared`, `@Volatile`, `@Nothrow`, and `@IO` from `org.babblelang.effects` (or configure an engine
with `assumeEffects` / `allowAxioms(false)`, which become axioms of the engine). BIP007's former
`axiom pure on Target` / `io on Target` spelling is gone (`BIP027-D6`). See
[BIP007](docs/design/bip007_effects.md), [BIP019](docs/design/bip019_refactoring-axioms.md), and
[BIP030](docs/design/bip030_axiom-nullability-and-inferred-effects.md).

### Interactive mode

`java -jar target/babble-1.0-SNAPSHOT.jar` reads an input, evaluates it, prints its value, and
starts again. An input continues on the next line while it is incomplete : inside
parentheses, inside an unfinished `"""..."""` or `r"""..."""` multiline string, or after an
operator, a comma, `=`, `->`, `then` or `else` (while unclosed single-line `"..."` and `r"..."`
literals fail immediately on line 1).

```
>> def twice = fn (f: fn (n: int): int, v: int): int -> (
..   f(f(v))
.. )
function twice
>> twice(fn (n) -> ( n + 1 ), 5)
7
>> def name = "world"
"world"
>> println("Hello, ", name)
Hello, world
>> def Point = record (x: int, y: int) ()
class Point
>> Point(1, 2)
Point(x: 1, y: 2)
>> name + nosuch
Error : No such name : nosuch
>> :type twice
fn (f : fn (n : int) : int, v : int) : int
>> :type Point(1, 2)
Point
```

Each input sees what the previous ones defined, classes and interfaces included, and goes
through the same checks as a script. Defining a name again replaces it for what you type
next ; functions defined before keep the definition they were written against, so functions
that call each other go in one input. To change a variable they see, assign it : `name = …`.

`:help` lists the few commands : `:type expr` shows the type of an expression without running
it, `:load file.ba` runs a file as if it was typed, `:cancel` (or Ctrl-C) drops an unfinished
input, `:quit` (or Ctrl-D) leaves. On a terminal, lines are edited : Up and Down recall earlier
inputs, multi-line ones whole, kept in `~/.babble_history` ; Tab completes names, keywords,
commands, file names after `:load`, and members after a dot (`p.`, `java.lang.Math.`), without
running anything. `-Dbabble.lineEditing=false` reads plain lines instead. The switches below
work here too : `java -Dbabble.mode=interpret -jar …`.
`java -jar … -keep-alive script.ba` runs a script first, and starts with what it defined. The
[design note](docs/design/bip004_repl.md) has the details.

Ray tracing demo
----------------

[examples/raytracer](examples/raytracer/README.md) is a unified ray tracer written maximally in
**pure Babble** — math (`core/math.ba`), deterministic per-scanline RNG (`core/rng.ba`), vectors,
rays, flat AABBs, BVH, five immutable `record` materials (`Lambertian`, `Metal`, `Dielectric`,
`Emissive`, `Toon`), analytic/implicit 3D geometries (`Sphere`, `Quad` & `Box` with `Translate` /
`RotateY` transforms, oriented `Ellipsoid`, tapered `RoundCone` + Bezier tubes, Fourier-contoured
`SculptedLobe`), optional direct area-light sampling (`AreaLight`), and four pure scenes
(*Weekend Cover*, *Three Spheres*, *Baby Triceratops*, and *Cornell Box*) — with only a thin
target-specific IO layer bridging to the JVM (`io/jvm/png.ba`) or WebAssembly (`io/wasm/canvas.ba`).

![The final scene of Ray Tracing in One Weekend, rendered by Babble](examples/raytracer/assets/scene.png)

All scenes render **byte-for-byte identically** across `interpret`, compiled JVM (single- or
multi-threaded), ahead-of-time jars, WebAssembly 3.0 on Node.js, and WebAssembly 3.0 in the browser.
To render a multi-threaded PNG on the JVM, build the jar and run `raytracer_jvm.ba` :

```
mvn package
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_jvm.ba cover scene.png 800 20 16
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_jvm.ba triceratops triceratops.png
java -jar target/babble-1.0-SNAPSHOT.jar examples/raytracer/raytracer_jvm.ba cornell-box cornell_box.png 400 256 16
```

For portable PPM output (`raytracer_ppm.ba`), the exact same script runs on the JVM, compiles
ahead of time into a standalone jar (`babblec`), or compiles directly to **WebAssembly 3.0**
(`WasmGC`) for Node.js — producing the exact same `examples/raytracer/assets/scene.ppm` byte for
byte (and [examples/raytracer/wasm_canvas.html](examples/raytracer/wasm_canvas.html) renders any of
the four scenes live to an HTML `<canvas>`) :

```
java -jar target/babble-1.0-SNAPSHOT.jar -compile examples/raytracer/raytracer_ppm.ba -o raytracer.jar
java -jar raytracer.jar > scene.ppm

java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js examples/raytracer/raytracer_ppm.ba -o raytracer.wasm
node raytracer.mjs > scene.ppm
```

Building and running
--------------------

Babble needs Java 21, Maven, and Node.js 22+ (for the WebAssembly 3.0 test suite) :

```
mvn package
```

That builds the jar, with its dependencies next to it in `target/lib`, and runs the test suite
in all modes : interpreted, compiled to JVM bytecode (`-O1` and `-O0`), and compiled to WebAssembly 3.0
(Node.js V8). The jar is Babble's command line :

```
java -jar target/babble-1.0-SNAPSHOT.jar                              # the interactive mode
java -jar target/babble-1.0-SNAPSHOT.jar script.ba [arguments...]     # runs a script
java -jar target/babble-1.0-SNAPSHOT.jar -keep-alive script.ba ...    # runs it, then the interactive mode
```

A script finds its arguments in `args`, a `java.util.List of string` :

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
on a plain JRE, without Babble, or into a WebAssembly 3.0 (`.wasm`) module :

```
$ java -jar target/babble-1.0-SNAPSHOT.jar -compile hello.ba                           # or -o app.jar, -root dir, -dbg
Wrote /home/ada/hello.jar : 1 module
$ java -jar hello.jar Ada
Hello, Ada

$ java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js hello.ba           # writes hello.wasm + hello.mjs
Wrote /home/ada/hello.wasm : 1 module
$ node hello.mjs Ada
Hello, Ada

$ java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-bare pure.ba          # zero-import standalone .wasm
$ java -jar target/babble-1.0-SNAPSHOT.jar -compile -target wasm-js -wasm-opt hello.ba # optional Binaryen wasm-opt -O3
$ java -jar target/babble-1.0-SNAPSHOT.jar -dev-server -d examples/raytracer -p 8080   # serve browser Wasm demos with proper MIME types
```

The jar holds the program's classes and a tree-shaken copy of Babble's runtime (with runtime
`LocalVariableTable` attributes stripped by default; pass `-dbg` to preserve them, while
`LineNumberTable` and `SourceFile` are always kept), and nothing but `java.base` : no parser, no compiler, no ANTLR or ASM. It starts in about half the
time (a hello world in 100 to 140 ms, against 200 to 270 ms through the engine), compiling the
same program twice gives the same bytes, and errors read as they do through the engine. A
dynamic import only finds the modules the program imports statically. With Babble on the class path,
`java -cp hello.jar:target/babble-1.0-SNAPSHOT.jar org.babblelang.runtime.Launcher Ada`, the
others are compiled from their files when they are imported. Imports may not leave the root,
the script's directory by default (`-Dbabble.root=dir` tells a jar moved away from it). See [BIP006](docs/design/bip006_module-sources-and-aot.md) (§5) and [BIP025](docs/design/bip025_compile-to-wasm.md) for WebAssembly 3.0 compilation, `axiom wasm.<mod>.<name>` host imports, and browser/Node.js embedding.

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
def fac=fn(n:int):int->(          def fac = fn (n: int): int -> (
  if n<2 then                         if n < 2 then (
  (                                       1  ;; done
    1 ;; done                         ) else (
  )                                       n * recurse(n - 1)
  else (                              )
      n*recurse( n-1 );           )
  )

)
```

- four spaces for each level of parentheses, and one more level for a line continuing an
  expression (after an operator, `=`, ...) ;
- multiline `"""..."""` and `r"""..."""` blocks re-indent uniformly to one indent level past their
  opening line (`(line.indent + 1) * 4` spaces), preserving intentional extra indentation and their
  dedented string value ;
- one space around binary operators (`+`, `-`, `*`, `/`, `//`, `mod`, ...), `=` (and `+=`, `-=`, `*=`, `/=`, `//=`) and `->`, after `,` and `:`, none around `..`/`..<` or inside parentheses
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
session.eval("def double = fn (n: int): int -> ( n * 2 )");
session.eval("double(21)");    // 42
```

### System properties

Babble reads these system properties, given to the JVM as `-Dname=value` (`java -Dbabble.mode=interpret -jar
target/babble-1.0-SNAPSHOT.jar script.ba`). Those marked *engine* can also be set on a script engine,
`engine.put(name, value)`, which wins over the property.

| Property | Values | What it does |
|---|---|---|
| `babble.mode` (*engine*) | `compile` (default), `interpret` | Compiles scripts to JVM bytecode, or runs them in the interpreter. |
| `babble.opt` (*engine*) | `0`, `1` (default), `2` | The optimization level in compiled mode, as `-O0`/`-O1`/`-O2` on the command line ([BIP014](docs/design/bip014_optimization-passes.md)). |
| `babble.inlineNodes` | a size in HIR nodes, `35` by default | The largest function `-O1` and `-O2` inline, `0` to inline nothing ; cross-module inlining in `babblec` gets the same proportion (`25` by default). Read once per JVM. |
| `babble.verify` (*engine*) | `true` | Checks what the compiler builds : `HirVerifier`, ASM's `CheckClassAdapter` on every generated class, and the WebAssembly validator. The test suite runs with it. |
| `babble.dumpIr` (*engine*) | `true` (or `all`, `stderr`), `hir`, then optionally `:before` or `:after:<Pass>`, then optionally `:canonical` ; or a directory | Prints the IR before and after optimization to the error stream, or of one stage, in the canonical form of the IR goldens with `:canonical` ([BIP029](docs/design/bip029_batest-v2-and-ir-goldens.md)) ; a directory gets one file per module and stage instead. The passes are `HirEGraphSimplify`, `Inline`, `ScalarReplace`, `FbipReuse`, `HirDce`, `HirLicm`, `HirPredicateReorder` at `-O1`, and `HirSccp`, `HirAeGraph`, `PipelineRewrite`, `Inline`, `ScalarReplace`, `FbipReuse`, `HirDce`, `HirLicm`, `HirPredicateReorder` at `-O2` ([BIP039](docs/design/bip039_mir-deorbitation.md), [BIP042](docs/design/bip042_egraph-pipeline-rewrites.md)). |
| `babble.dumpClasses` | a directory | Writes the generated `.class` files there, for `javap -c -p`. |
| `babble.trace` | `true` | Prints an ASM listing of every generated class to the error stream. |
| `babble.dumpWasm` | `wat` or `true` | Prints the WebAssembly text of a `-target wasm-js` / `wasm-bare` compilation to the error stream. |
| `babble.wasmDebugNames` | `true` | Writes the full debug name section into `.wasm` modules. |
| `babble.dumpAxioms` | Java classes or packages, comma-separated | Prints what the compiler assumes about them (curated or inferred from bytecode) to the error stream, like `-axioms`. |
| `babble.axioms.report` | `true` | Reports curated axioms that bytecode analysis already infers as strong or stronger. |
| `babble.checkAxioms` (*engine*) | `true` | Fuzzes the script's `axiom`-declared functions against their declared properties when it runs ([BIP007](docs/design/bip007_effects.md)). |
| `babble.root` | a directory | The root a `babblec` jar compiles the modules it lacks from, when Babble is on its class path (default : the one its index gives, relative to the jar). |
| `babble.history` | a file | Where the interactive mode keeps its inputs (default : `~/.babble_history`). |
| `babble.lineEditing` | `false` | Reads plain lines in the interactive mode instead of editing them with JLine. |

Hosts embedding the engine also have attributes that are not system properties :
`babble.moduleSource` (where imports come from, a `ModuleSource`), `babble.irObserver` (an
`IrObserver` receiving IR snapshots), and `babble.aotCrossModule` / `babble.aotInlineCache`, which
`babblec` sets for cross-module inlining.

Implemented Features
--------------------

Language features :

- [x] no C-style brackets ! Those are nasty to type on most keyboards, especially on Apple keyboards.
- [x] in fact, there is only one type of brackets in Babble : parentheses `( )`. The grammar is predicate-free LL(1)/LL(2) (`BIP012`, `RM006`) and parses in ANTLR4's fast `PredictionMode.SLL`.
- [x] no semicolons at end of line : newlines are significant and end the current statement, except after tokens that call for a continuation (operators, `,`, `=`, `->`, `then`, ...). Semicolons are only needed to separate multiple statements on the same line.
- [x] everything is an expression, including blocks, `if`, `match`, `while`, `for`, `try`/`catch`/`finally`, and `throw`.
- [x] block scope : blocks, `if` branches, `match` `case`/`else` arms, `while` bodies, `for` bodies, and `try`/`catch`/`finally` blocks are scopes, and names are hoisted to the start of their scope.
- [x] pattern matching (`RM024`, see [BIP038](docs/design/bip038_pattern-matching.md)) : expression-oriented `match x case <pattern> [if <guard>] then (...) else [def rest then] (...)` with explicit `def` bindings (`def x`, `def x: T`), value/constant, binding-free OR (`p1 | p2`), relational (`<`/`<=`/`>`/`>=`/`==`/`!=`), `BIP031` range (`lo..hi`/`lo..<hi`), and `in container` membership patterns, positional/named/partial Babble & Java `record` destructuring, `is list(...)` / `is array(...)` sequence destructuring with fresh `...def tail` slices, mandatory compile-time Maranget exhaustiveness & reachability (`bool`, `T?`, unions, Java `enum`/`sealed`, nested records, sequence lengths) with counterexample witnesses & redundant-`else` warnings, pure (side-effect-free) `case` pattern expressions and `if` guards, `@BORROWED`/`@UNIQUE` ownership & `SizeChangeTermination` structural descent, JVM `TABLESWITCH`/`LOOKUPSWITCH`/string hash-switch/AOT enum switch-map lowering, `-O1` constant folding & `ScalarReplacePass` SROA on `match RecordNew(...)`, `-O2` `HirSccpPass` constant-branch folding, and WebAssembly 3.0 `br_table`/`ref.test`/`struct.get` lowering.
- [x] immutable `def` and mutable `var` bindings ([BIP034](docs/design/bip034_immutable-def-and-mutable-var.md)) : `def` bindings (including `export`s, `interface` members, public `static` members, `record` fields, and function parameters by default) cannot be reassigned, while `var` explicitly opts into reassignment on locals, module-private top-level bindings, `class`/`object` fields, `private var` static fields, and optional `fn (var ...)` parameters ; enables pure function/class `NameRead`s (`name_read Pair {pure}`), per-field `def` immutability (`READ_FROZEN` and `ACC_FINAL` typed `<init>` constructors), value-capture without `Cell` allocation, and constant-folding inline caches in `Bootstrap.NameCache`/`SelectCache`.
- [x] exception handling : expression-oriented `try (...) catch [e] [: A | B] then (...) finally (...)`, `throw` (`Type.NOTHING`), lowercase core `exception` type (`BabbleException`), direct JVM `Throwable` propagation, open nominal Babble exceptions via `ThrownException` isolated from Java `Exception`/`RuntimeException` catches, untyped non-`Error` catch-all, forbidden escaping `return`/`break`/`continue` in `finally` with automatic `addSuppressed`, `nothrow` restoration & dead-catch warnings, intra-procedural local-catch `@BORROWED` provenance, JVM `exception_table` + inline `finally` duplication with `try`-range splitting, and `-O1` dead-catch elimination (see [BIP028](docs/design/bip028_exception-handling.md)).
- [x] first-class iterators, `for ... in ... then (...)` loops, and uniform `break [expr]` / `continue` across `while` and `for` loops, plus parameterized static factories `(list of int).of(...)` / `list.of(...)` (`RM009`, see [BIP013](docs/design/bip013_iterators.md)).
- [x] first-class ranges, modulo and integer division (`RM019`, see [BIP031](docs/design/bip031_ranges.md)) : immutable covariant `range of T` (`lo..hi`, `lo..<hi`), discrete progressions with `(lo..hi).step(k)` and `.reversed()`, floored `mod`, `//`, and `//=` (`-1 mod 5 == 4`, `-7 // 2 == -4`, `def mod`/`def floorDiv` operator methods), `@TERMINATING` certification, allocation-free counted `for` loops and `.contains()` deforestation across JVM (`-O0`/`-O1`/`-O2`) and WebAssembly 3.0, power-of-two bitmask/shift and non-negative-dividend `IREM`/`IDIV` lowering, and E-graph algebraic laws.
- [x] functions (`fn (...) -> (...)`), supporting recursion (`recurse`) and closures.
- [x] self tail calls (`recurse(...)`, a function's own `def`, `this.m(...)`) run in constant stack space in every mode, and code that never ends must say so with `forever` (`docs/design/bip024_tail-calls.md`).
- [x] first-class function objects, functions are stored in variables and can be passed around as parameters.
- [x] positional, statically bound named, optional (`= ...` in function types & interfaces, with `$spec` missing-argument masks), keyword-only (`...`), and variadic rest (`xs: T...`, a generic `Iterable of T` backed by `ArgumentsView` and primitive variants) parameters, plus `...e` spread arguments into any sequence parameter (`BIP021-I1`, `BIP021-I4`, see [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md)).
- [x] closed overload sets (`overload (f, g, ...)`) and set types (`overload (fn ..., fn ...)`): static ambiguity checking with witnesses where sets are built, most-specific resolution with numeric widening ranks, union/nullable runtime splits, overloaded class/record/object/static/interface methods, Java interface overload bridges, and overloaded operator methods (`BIP021-I5`–`I6`, see [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md)).
- [x] basic types : `byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`, `null`, `array of T` over plain JVM arrays (`(array of int)(100)`, `.of(...)`, `from:`, `size()`/`get`/`set`, content equality and printing, [BIP020](docs/design/bip020_array-types.md)), covariant `range of T` ([BIP031](docs/design/bip031_ranges.md)), core collections (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry` mapped to `java.util`), functions (`fn (...) : R`) and polymorphic function types (`fn of T (...) : R`), overload set types (`overload (fn ..., fn ...)`), union types (`A | B`), intersection types of interfaces (`A & B`), type aliases (`def T = type A | B`), nullable types (`T?`, postfix `e!`), `any` / `any?`, and generic/parameterized types with upper bounds (`Box of (T: B)`, `list of int`, `java.util.List of string`).
- [x] multiline & raw string literals : strictly single-line `"..."`, strict JEP 378 `"""..."""` with compile-time closing-delimiter dedenting (`\s`, `\<newline>`, no mixed tabs/spaces, uniform trailing-space stripping), and minimal raw `r"..."` / `r"""..."""` (`BIP026-I1`, see [BIP026](docs/design/bip026_multiline-strings-and-string-templates.md)).
- [x] modules : a file exports some of its definitions with `export`, and another gets them with `import("file.ba")`.
- [x] object model : classes, object literals, interfaces with default methods, overloaded members, and generic interface methods, records (`record`), private and static members, dedicated `init` constructors (`[private] init [effects] [(params) ->] (...)`, declarative bodies, uninitialized `def`/`var` fields with static definite/single-assignment `FieldInitAnalysis`, closed `BIP021` constructor overloading, `this(...)` delegation with pre-delegation locals, pure-by-default constructor effects, direct JVM `<init>` with `ACC_FINAL` on all `def` fields, and WasmGC local-staged immutable `struct.new`), `is`, `same` and `classof` (see [BIP002](docs/design/bip002_object-model.md), [BIP012](docs/design/bip012_grammar-improvements.md), [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md), and [BIP040](docs/design/bip040_constructors.md)).
- [x] Java interop both ways : Java classes, generic types, multi-bound Java `TypeVariable`s (`BIP022-I2`), and Java varargs calls (`BIP021-I2`, Phase 3 resolution and direct `NEWARRAY`/`ANEWARRAY` lowering) used directly from Babble, Babble functions eagerly converted to Java functional interfaces and SAM+marker intersections (`Runnable & Serializable`) via `LambdaMetafactory.altMetafactory` (`FLAG_MARKERS`), Babble classes implementing Java (including generic and overloaded) interfaces, and Java nullability alignment (`@NotNull`/`@Nullable`/JSpecify, mapped `java.base` parametric nullness, and defensive boundary checks, see [BIP011](docs/design/bip011_core-types.md), [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md), and [BIP022](docs/design/bip022_intersection-types.md)).
- [x] static type checking : call-site parameter inference for non-exported/non-escaping callables, required annotations on exports, escapes, interfaces and uncalled functions, bidirectional local inference, generic function literals (`fn of T`), bounded type parameters (`of (T: B)` including F-bounds and `A & B` intersection bounds), polymorphic function types (`BIP021-I3`), generic call/constructor inference (`C of T (...)` and inferred `C(...)`), strict non-nullability with `T?`, `T!` (`T! == T` everywhere, projecting `null` off nullable types/aliases, enforced on concrete reference types in Java axioms per [BIP030](docs/design/bip030_axiom-nullability-and-inferred-effects.md)), and `e!`, union types (`A | B`), safe navigation (`?.`), invariant mutable generics with covariant `record`/`entry`/`borrow` parameters, flow-sensitive narrowing (`is`, intersecting interfaces, `else` subtraction, `== null`/`!= null`/truthiness), checked `as` casts and sound top type `any` / `any?`, and runtime checks at casts and Java boundaries (see [BIP003](docs/design/bip003_type-system.md), [BIP008](docs/design/bip008_static-typing.md), [BIP011](docs/design/bip011_core-types.md), [BIP021](docs/design/bip021_optional-variadic-overloaded-arguments.md), and [BIP030](docs/design/bip030_axiom-nullability-and-inferred-effects.md)).
- [x] static function effects, ownership, algebraic contracts, termination, and HOF forwarding (`RM002`, `RM007`, `RM008`, `RM009`, `RM012`) : pure-by-default opt-out surface syntax (`io`, `volatile`, `mut`, `shared`, `unique`, opt-in `nothrow`, `axiom pure`, `fn calling f`), return-through-borrow provenance, Bourdoncle WTO fixed-point inference and verification of internal orthogonal traits, E-graph & partial-derivative sign-domain algebraic property verification (`idempotent`, `commutative`, `associative`, `monotone`, `injective`), prefix-overlap static `mut` exclusivity, `unique`/`frozen` ownership with `move`/`freeze`/`discard` and `ModalKindTable` deep immutability, polynomial Size-Change Termination (`SCP`) + curated finite-collection `for`-loop `@TERMINATING` certification, unused pure expression checks (`BIP007-D8`, `BIP027-D8`), single-pass cross-module/interface/first-class callback-effect forwarding (`fn calling f`), `axiom` Java contracts (`axiom Target: fn pure`, `class notnull`, `fn ... : T!` / `fn ... (…): T!` keeping bytecode-inferred effects while stating nullability per `BIP030`), `org.babblelang.effects` host annotations (`@Pure`, `@Volatile`, `@Shared`, `@IO`, `@Mut`, `@Unique`, `@Nothrow`), default-on ASM bytecode effect analysis, property-based `babble.checkAxioms` fuzzing, and built-in core type effect profiles (see [BIP007](docs/design/bip007_effects.md), [BIP011](docs/design/bip011_core-types.md), [BIP016](docs/design/bip016_consolidating-effect-annotations.md), [BIP017](docs/design/bip017_higher-order-function-effects.md), [BIP019](docs/design/bip019_refactoring-axioms.md), [BIP027](docs/design/bip027_tackling-complexity-in-the-compiler.md), and [BIP030](docs/design/bip030_axiom-nullability-and-inferred-effects.md)).
- [x] "native" functions : `print`/`println`, `assert` (or `suppose`), `import` (Java packages and Babble modules), `same`, `classof`, `move`, `freeze`, `discard`, all 9 scalar conversions (`byte`..`string`), and core collection/array factories (`array`, `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`).

Implementation features :

- [x] interpreted mode running on the parse tree, like 1.8.x Ruby, A.K.A. SLOW MODE : no longer the default, it is the reference implementation the compiler is tested against, and runs scripts on one thread only.
- [x] compiled mode, the default : scripts lower from the parse tree into a unified enriched typed `HIR` (`org.babblelang.ir.hir`, with `IrEffect`/`AliasClass`, `Narrow`, `Convert`, and `Widen`) decoupled from the parsed AST, pass through a `BIP007`/`BIP016`-gated Nanopass optimizer (`-O1` default, `-O0` to disable : `HirEGraphSimplifyPass` over `org.babblelang.ir.egraph` combining constant folding, strict IEEE-754 algebraic, `idempotent`, and `injective`/`monotone` comparison simplification, intra-module and `babblec` AOT cross-module function/`record`-method & `borrow`-lambda inlining, `ScalarReplacePass` projection beta-reduction / `$unboxed` `borrow` entry points / non-escaping local `record` SROA, `FbipReusePass` in-place reuse for `unique` + `move(...)` collections and mutable `record`s, backward-liveness `@TERMINATING`-aware `HirDcePass`, alias-aware header+body `HirLicmPass`, and `HirPredicateReorderPass`; at `-O2`, `HirSccpPass`, dominator-scoped `HirAeGraphPass` unifying algebraic rules, cross-statement `injective`/`monotone` cancellation, GVN/CSE, `idempotent` collapse, `Convert`/`Narrow` folding, and alias-aware store-to-load forwarding, and `PipelineRewritePass` lifting `for`/`list`/`set`/counted-range pipelines into `org.babblelang.ir.egraph` `PIPE_*` E-nodes for joint stream fusion, filter fusion/reordering, map–filter push-down, `injective` `distinct` push-down, `monotone` range clamping/early-exit/whole-range pruning, and `associative` pairwise loop reduction directly on structured `HIR`, shared by both JVM and WebAssembly 3.0; `RM010`/`RM012`/`RM013`/`RM015`, [BIP014](docs/design/bip014_optimization-passes.md), [BIP027](docs/design/bip027_tackling-complexity-in-the-compiler.md), [BIP039](docs/design/bip039_mir-deorbitation.md), [BIP042](docs/design/bip042_egraph-pipeline-rewrites.md)) before emitting compact JVM bytecode via `HirEmitter` + `HirSlotAllocator` with [ASM](https://asm.ow2.io/) (with name reads through caching `invokedynamic` sites from `-O1`, statement-position `valueNeeded` dead-result suppression, `IFNULL`/`IFNONNULL` null-comparison lowering, and `record` methods kept as per-class constants rather than instance fields), behind the same API. Names are resolved at compile time, so variables are JVM locals, compiled classes, records and objects are plain JVM classes extending `java.lang.Object` directly without a runtime base class (`BIP020-D18`) with direct `$new` factories and direct field/method dispatch (`BIP014-I4`), and interfaces are real JVM interfaces with direct `INVOKEINTERFACE` dispatch and exact-signature Java `implements` bridges (`RM003`, §20), other calls and member accesses are `invokedynamic` sites with inline caches (phases 1 to 3 of the [bytecode compiler design](docs/design/bip001_bytecode-compiler.md)), Java functional-interface callbacks use cached `LambdaMetafactory` adapters (`RM003`), and static types from `BIP008`/`BIP011` lower all 8 non-nullable JVM primitives (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`) across locals, closure cells, class fields, arithmetic, comparisons, `$spec` calls, module exports, arrays (`NEWARRAY`/`ANEWARRAY`/`ARRAYLENGTH`/`<X>ALOAD`/`<X>ASTORE`), and resolved Java calls/fields directly to unboxed JVM instructions (`RM001`/`RM005`, §19) : up to 6,600x faster than the interpreter in the benchmarks.
- [x] errors found before running : redefined names, misplaced `return`, `this` or `super`, incomplete or conflicting interface implementations, private members reached from outside.
- [x] clean, predicate-free ANTLR4 grammar with zero embedded target-language code and `PredictionMode.SLL` fast-path parsing (see [Babble.g4](src/main/antlr4/org/babblelang/parser/Babble.g4) and [BIP012](docs/design/bip012_grammar-improvements.md)).
- [x] clean separation between grammar and implementation thanks to ANTLR4's parse tree & Visitor support. This allows the grammar to be reused in other contexts (IDEs ?) or by other languages (C# implementation ?).
- [x] [JSR 223](http://www.jcp.org/en/jsr/detail?id=223) support : `javax.script` compatible API.
- [x] interactive mode : a read-eval-print loop over sessions, in both modes and with the type checker, with line editing, history and completion on a terminal, `:type` and `:load` (see [BIP004](docs/design/bip004_repl.md)).
- [x] command line : `java -jar babble.jar [-O0|-O1|-O2] script.ba arguments...` runs a script, and `-keep-alive` continues in the interactive mode.
- [x] ahead-of-time compilation : `-compile [-O0|-O1|-O2] script.ba` writes a jar of the program and the modules it imports, which runs on a plain JRE (see [BIP006](docs/design/bip006_module-sources-and-aot.md)), or with `-target wasm-js` / `-target wasm-bare` (plus optional `-wasm-opt`) compiles directly from target-neutral `HIR` to WebAssembly 3.0 (`WasmGC`, typed function references, `try_table`/`exnref` exceptions, `return_call` tail calls, closed-world interface bitsets & itables, strictly immutable `record` structs and `BIP034` header `def` fields, `Cell`-free value captures, portable strings/arrays/collections, and `axiom wasm.<mod>.<name>` host imports) with zero new Maven dependencies, `-O1`/`-O2` `.batest.md` parity + `wat` goldens, and byte-identical `examples/raytracer/assets/scene.ppm` rendering on Node.js V8 and browser `<canvas>` (see [BIP025](docs/design/bip025_compile-to-wasm.md) and [BIP039](docs/design/bip039_mir-deorbitation.md)).
- [x] syntax errors that say what was unexpected, and where.
- [x] a code formatter : `java -jar babble.jar -format`.
- [x] a fast, isolated HTTP `.batest.md` runner : `java -jar babble.jar -crib` serves `POST /crib?<options>`, `GET /health`, and `POST /recycle` over `jdk.httpserver` backed by a warm, poison-audited, killable worker JVM pool (~10–50 ms per 4-mode verified trial with automatic cross-mode parity diffing and optional isolated `wasm-O1`/`wasm-O2` execution; see [BIP041](docs/design/bip041_crib-server.md)).
- [x] a static HTTP dev server : `java -jar babble.jar -dev-server [-b addr] [-p port] [-d dir] [-o none|info|verbose] [dir]` wraps JDK 21's `SimpleFileServer` (`jdk.httpserver`), accepting relative directories and serving `.html`, `.js`, `.mjs`, `.wasm`, and other web assets with proper MIME types for browser WebAssembly demos.

Planned features
----------------

Language features :

- [ ] better, more user-friendly error reports.
- [ ] structural object types.
- [ ] destructuring definitions : `def (Circle, unit) = import("shapes.ba")` binds the named members of
  a module or an object, instead of one `def` per name.
- [ ] `yield` keyword : transform functions/method into iterators.
- [ ] functional-style programming : `map` (standard library).

Implementation features :

- [ ] a Maven goal for ahead-of-time compilation.

TODO : lessons from the ray tracer
----------------------------------

Writing [the ray tracer](examples/raytracer/README.md) showed where Babble gets in the way. In rough
order of how much each would improve that code :

- [x] unary minus : `-x` and `-1.0` had to be written `0 - x` and `0 - 1.0`.
- [x] operator methods (now also overloaded via `BIP021-I6`) : `a + b * t` and `u * v` both call `Vec3.times` (overloaded for scalar scaling and component-wise multiplication), so vector maths doesn't read `a.plus(b.times(t))` or `u.mul(v)`.
- [x] members of Java values returned by Java calls : only objects constructed in Babble accepted
  method calls, so `list.get(0).size()` or `Runtime.getRuntime().availableProcessors()` failed with
  `Not a scope`. This is why the ray tracer had its own linked list. Any Java object now has its
  methods, however it was obtained, and the type checker follows chained calls : the ray tracer
  uses pure-Babble `list of Hittable` (and `java.util.ArrayList` works directly too).
- [x] static fields of Java classes : `java.lang.Math.PI` or `BufferedImage.TYPE_INT_RGB` were
  not reachable, so the ray tracer computed π as `Math.acos(0 - 1)` and passed a bare `1`. They
  are now, with their types, as are the public fields of Java objects.
- [ ] bare names for a class's own members in its body, or at least for `def` header parameters :
  a plain header parameter can be read as `x` in the body, a `def` one only as `this.x`.
- [x] `for` loops over collections, iterators, arrays, and custom Babble iterables (`RM009`, [BIP013](docs/design/bip013_iterators.md)).
- [x] `else if`, without nesting a block : `else ( if … then ( … ) else ( … ) )`.
- [x] `break` (`break expr`) and `continue` in `while` and `for` loops (`RM009`, [BIP013](docs/design/bip013_iterators.md)).
- [x] compound assignment : `+=`, `-=`, `*=`, `/=`, on variables and members, calling operator
  methods on instances. The ray tracer's counters and accumulators use them.
- [x] number conversions : `int(x)` and `double(x)`, instead of
  `Math.toIntExact(Math.round(Math.floor(x)))` or a binary decomposition in Babble.
- [x] line continuation before `.` and `?.`, to break long method chains across lines : a line may start
  with `.` or `?.`, continuing the expression above it.
- [x] importing other Babble files : `export` and `import("file.ba")`.
- [x] parse errors with a line, a column and a message : a syntax error such as using the keyword
  `object` as a name was reported as a bare `ParseCancellationException`. It is now
  `Syntax error : unexpected 'object'`, at its line and column.
- [x] command-line launcher : a `main` class in the jar to run a `.ba` file, with script arguments,
  instead of a launcher per project : `java -jar target/babble-1.0-SNAPSHOT.jar script.ba arguments...`,
  see *Building and running*. The ray tracer runs that way, and its launchers are gone.
- [ ] thread safety : the ray tracer found a race in the Java member caches, now fixed, but nothing
  tests scripts running on several threads. And the interpreter cannot run a script's functions
  on several threads at all, since it keeps the current scope in a field : multi-threaded
  `raytracer_jvm.ba` renders (`threads > 1`) only run in compiled mode.
- [x] unboxed arithmetic for typed code : `RM001` lowers non-nullable `int`, `double`, and `bool` locals,
  closure cells, class fields, arithmetic, comparisons, `$spec` calls, and statically resolved Java calls
  directly to primitive/direct bytecode (`argon` drops from 59.3 s to 46.1 s; cross-object `obj.x` unboxing
  and scalarized `value class` allocation remain for later optimizations).

License
-------

Babble is licensed under the [Apache License v2.0 with LLVM Exceptions](LICENSE)
(SPDX : `Apache-2.0 WITH LLVM-exception`). The exceptions mean that the copy of the Babble runtime
that `babblec` embeds into a compiled program's jar can be redistributed with that program without
the attribution and notice requirements of sections 4(a), 4(b) and 4(d) of the Apache License.
