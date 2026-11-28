# Soundness regressions : `types` (`T1`, `T2`, `T3`, `T5`, `T6`) generic & array variance, flow narrowing, erased aliases & field init

Promoted from `BIP032` quarantine (`types_t1_record_covariance_mutable_field.batest.md`, `types_t1_borrow_covariance_contravariant_method.batest.md`, `types_t3_flow_narrowing_shortcircuit_reassign.batest.md`, `types_t2_array_covariance_erased_generic_element.batest.md`, `types_t5_variadic_join_drops_rest.batest.md`, `types_t1_erased_generic_is_type_alias.batest.md`, `types_t3_join_branches_stale_narrowing.batest.md`, `types_t6_field_init_shortcircuit_and.batest.md`) and sister vectors covering `TypeVariance`, `TypeAlgebra`, `Narrowing`, `TypeEnv`, `TypeCheckDiagnostics`, `MatchTyper`, `FieldInitAnalysis`, and `ExpressionTyper`.

```babble
;; babble preamble
def Box of T = record (xs: list of T) ()
def Cell of T = class (var v: T) ()
def CellRec of T = record (c: Cell of T) ()
def CbRec of T = record (f: fn (x: T): int) ()
def Sink of T = class (var seen: int = 0) (
    def accept = fn (x: T): int -> (this.seen)
)
def RoBox of T = class (var v: T) (
    def get = fn (): T -> (this.v)
    def put = fn mut (shared x: T) -> (this.v = x)
)
def Consumer of T = interface (
    def take: fn (x: T): int
)
```

## Generic record holding mutable list must not be covariant

```babble
def widen = fn (b: Box of any): Box of any -> (b)
def b = Box of string (list of string ("hello"))
def w = widen(b)
w.xs.add(42)
```

```error
Type error : expected Box(any), got Box(string)
```

## Generic record holding mutable generic class field must not be covariant

```babble
def widen = fn (r: CellRec of any): CellRec of any -> (r)
def r = CellRec of string (Cell of string ("ok"))
widen(r)
```

```error
Type error : expected CellRec(any), got CellRec(string)
```

## Generic record holding contravariant callback field must not be covariant

```babble
def widen = fn (r: CbRec of any): CbRec of any -> (r)
def r = CbRec of string (fn (s: string): int -> (s.length()))
widen(r)
```

```error
Type error : expected CbRec(any), got CbRec(string)
```

## Borrowed generic class with contravariant method parameter must not be covariant

```babble
def feed = fn (s: Sink of any): int -> (s.accept(42))
def strSink = Sink of string ()
feed(strSink)
```

```error
Type error : expected Sink(any), got Sink(string)
```

## Frozen generic class with contravariant method parameter must not be covariant

```babble
def feed = fn (s: frozen (Sink of any)): int -> (s.accept(42))
def f = freeze(Sink of string ())
feed(f)
```

```error
Type error : expected frozen Sink(any), got frozen Sink(string)
```

## Borrowed generic interface with contravariant method parameter must not be covariant

```babble
def feed = fn (c: Consumer of any): int -> (c.take(42))
def impl = object implements Consumer of string (
    def take = fn (s: string): int -> (s.length())
)
feed(impl)
```

```error
Type error : expected Consumer(any), got object
```

## Borrowed Java Predicate must not be covariant in contravariant parameter

```babble
def runPred = fn (p: java.util.function.Predicate of any): bool -> (true)
def sp: java.util.function.Predicate of string = fn (s: string): bool -> (s.length() > 0)
runPred(sp)
```

```error
Type error : expected java.util.function.Predicate(any), got java.util.function.Predicate(string)
```

## Borrowed Java Function must not be covariant in its input parameter

```babble
def applyFn = fn (f: java.util.function.Function of (any, int)): int -> (0)
def sf: java.util.function.Function of (string, int) = fn (s: string): int -> (s.length())
applyFn(sf)
```

```error
Type error : expected java.util.function.Function(any, int), got java.util.function.Function(string, int)
```

## Borrowed Java Comparator must not be covariant in its parameter

```babble
def cmpAny = fn (c: java.util.Comparator of any): int -> (c.compare(1, 2))
def sc: java.util.Comparator of string = fn (a: string, b: string): int -> (a.compareTo(b))
cmpAny(sc)
```

```error
Type error : expected java.util.Comparator(any), got java.util.Comparator(string)
```

## Immutable record, read-only borrowed class, and borrowed list remain covariant

```babble
def Wrap of T = record (v: T) ()
def readWrap = fn (w: Wrap of any): any -> (w.v)
def checkBox = fn (b: RoBox of any): bool -> (b.get() == "b")
def checkList = fn (xs: list of any): bool -> (xs.get(0) == "c")
assert(readWrap(Wrap of string ("a")) == "a")
assert(checkBox(RoBox of string ("b")))
assert(checkList(list of string ("c")))
```

## Short-circuit and reassigning type-narrowed var must invalidate narrowing

```babble
def f = fn (var x: string | int): int -> (
    if x is string and (x = 42; true) then (x.length()) else (0)
)
f("hi")
```

```error
Not a scope : x
```

## Short-circuit or reassigning null-narrowed var must invalidate else narrowing

```babble
def f = fn (var x: string?): int -> (
    if x == null or (x = null; false) then (0) else (x.length())
)
f("hi")
```

```error
Not a scope : x
```

## While condition short-circuit and reassigning narrowed var must invalidate body narrowing

```babble
def f = fn (var x: string?): int -> (
    var r = 0
    while x != null and (x = null; true) then (
        r = x.length()
    )
    r
)
f("hi")
```

```error
Not a scope : x
```

## Early-exit if reassigning var in surviving else branch must invalidate post-if narrowing

```babble
def f = fn (var x: string?): int -> (
    if x == null then (return 0) else (x = null)
    x.length()
)
f("hi")
```

```error
Not a scope : x
```

## Early-exit if reassigning var in surviving then branch must invalidate post-if narrowing

```babble
def f = fn (var x: string?): int -> (
    if x != null then (x = null) else (return 0)
    x.length()
)
f("hi")
```

```error
Not a scope : x
```

## Short-circuit narrowing without reassignment and post-reassignment narrowing remain valid

```babble
def f = fn (var x: string | int): int -> (
    if x is string and x.length() > 0 then (x.length()) else (0)
)
def g = fn (var x: string | int): int -> (
    if (x = "ok"; true) and x is string then (x.length()) else (0)
)
assert(f("hello") == 5 and f(1) == 0 and g(99) == 2)
```

## Array of a parameterized element type must not be covariant

```babble
def a = array.of(list of string ("x"))
def b: array of any = a
b.set(0, list of int (42))
def s: string = a.get(0).get(0)
println(s.length())
```

```error
Type error : expected array of any, got array of (list(string))
```

## Array of a generic Babble class must not be covariant to array of any

```babble
def a = array.of(Cell of string ("x"))
def b: array of any = a
```

```error
Type error : expected array of any, got array of (Cell(string))
```

## Arrays of non-generic classes and of arrays of them stay covariant

```babble
def Shape = interface (def area: fn (): int)
def Sq = class (def s: int) implements Shape (def area = fn (): int -> (this.s))
def shapes: array of Shape = array.of(Sq(2))
def rows: array of any = array.of(array.of(Sq(3)))
assert(shapes.get(0).area() == 2 and rows.size() == 1)
```

## Joining two variadic functions keeps the rest parameter

```babble
def a = fn (xs: int...): int -> (var s = 0 ; for x in xs then (s += x) ; s)
def b = fn (xs: int...): int -> (7)
var k = 0
def c = if k == 0 then (a) else (b)
assert(c(1, 2, 3) == 6 and c() == 0)
```

## Joining a variadic with a single-parameter function takes one argument

```babble
def a = fn (xs: int...): int -> (var s = 0 ; for x in xs then (s += x) ; s)
def b = fn (xs: int): int -> (xs)
var k = 0
def c = if k == 0 then (a) else (b)
assert(c(5) == 5)
c(1, 2)
```

```error
Too many parameters : 1 declared, 2 given
```

## Type alias to parameterized class must not bypass erased generic is check

```babble
def StrCell = type (Cell of string)
def x: any = Cell of int (42)
if x is StrCell then (println(x.v.length())) else (println(0))
```

```error
Cannot test erased type arguments with 'is' : use 'is Cell'
```

## Type alias to parameterized class must not bypass erased generic match check

```babble
def StrCell = type (Cell of string)
def x: any = Cell of int (42)
match x
case is StrCell then (println(x.v.length()))
else (println(0))
```

```error
Cannot test erased type arguments with 'is' : use 'is Cell'
```

## Block reassigning and re-narrowing var to null must not restore outer non-null narrowing

```babble
def boom = fn (var x: string?): int -> (
    if x != null then (
        def dummy = (
            x = null
            if x != null then (return 0)
            0
        )
        x.length() + dummy
    ) else (0)
)
println(boom("hi"))
```

```error
Not a scope : x
```

## Short-circuit and RHS assignment must not count as definite field initialization

```babble
def InitBox = class () (
    def x: string
    init (cond: bool) -> (
        def _ = cond and (this.x = "ok"; true)
    )
)
println(InitBox(false).x.length())
```

```error
Field may not be initialized by init : x
```
