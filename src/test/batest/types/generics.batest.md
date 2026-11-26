# Core generics : parameterized functions, classes, records, interfaces, standalone `of`, covariance, and errors

References : BIP008 (generics), BIP012 (`of` type application and class body newlines)
Note : type inference, widening hints, Java bounds, and polymorphic values are in types/generics_and_inference.batest

```babble
;; babble preamble
def id of T = fn (x: T): T -> ( x )

def Box of T = class (def value: T) (
    def mapBox of U = fn (func: fn (x: T): U): Box of U -> ( Box(func(this.value)) )
)

def ValBox of T = record (value: T) ()
```

## Case 1

```babble
def bi: Box of int = Box(5)
def bd: Box of double = bi.mapBox(fn (x) -> ( x * 1.5 ))
def s: string = id("hello")
assert(bi.value == 5)
assert(bd.value == 7.5)
assert(s == "hello")
```

## Records are immutable, so ValBox of T is covariant; raw `is ValBox` narrows a nullable generic

```babble
def bi: ValBox of int = ValBox(9)
def bu: ValBox of (int | double) = bi
def ba: ValBox of any = bi
def mb: (ValBox of int)? = bi
def n = if mb is ValBox then ( mb.value ) else ( 0 )
assert(bu.value == 9)
assert(ba.value == 9)
assert(n == 9)
```

## Case 3

```babble
def Getter of T = interface (
    def get: fn (): T
)
def Holder of T = class (def v: T) implements Getter of T (
    def get = fn (): T -> ( this.v )
)
def g: Getter of string = Holder("babble")
assert(g.get() == "babble")
```

## Mutable generic classes are invariant : Box of int is not Box of double

```babble
def bi: Box of int = Box(1)
def bd: Box of double = bi
```

```error
Type error : expected Box(double), got Box(int)
```

## Erased generics cannot be checked at runtime via `as`

```babble
def anyVal: any = Box(1)
def bad = anyVal as Box of int
```

```error
Cannot cast to parameterized type : Box of int
```

## Error : Cannot cast to type parameter : T

```babble
def bad of T = fn (x: any) -> ( x as T )
```

```error
Cannot cast to type parameter : T
```

## Error : Missing type arguments : Box

```babble
def b: Box = Box(1)
```

```error
Missing type arguments : Box
```

## First-class standalone type application (BIP012-D4)

```babble
def idInt = id of int
def IntBox = ValBox of int
def IntList = list of int
assert(idInt(42) == 42)
assert(IntBox(7).value == 7)
assert(IntList(1, 2, 3).size() == 3)
```

## `?` binds tighter than `of` : `list of int?` is a non-null list of nullable ints

```babble
def xs: list of int? = list of int? (1, null, 3)
def ys: (list of int)? = null
assert(xs.size() == 3)
assert(ys == null)
```

## Newline before class body (BIP012-D5), curried calls, and `.of` as a member name

```babble
def Cell = class (def x: int)
(
    def get = fn () -> ( this.x )
)
def add = fn (a: int) -> ( fn (b: int) -> ( a + b ) )
assert(Cell(5).get() == 5)
assert(add(2)(3) == 5)
assert(java.util.List.of(10).size() == 1)
```

## Error : Cannot apply type arguments to an overload set : ov

```babble
def ov = overload (fn (x: int): int -> (x), fn (s: string): int -> (s.length()))
def bad = ov of int
```

```error
Cannot apply type arguments to an overload set : ov
```

## Error : (listofint) is not generic

```babble
def bad = (list of int) of string
```

```error
(listofint) is not generic
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
def bad = list of (int, string)
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : plain is not generic

```babble
def plain = fn (x: int): int -> (x)
def bad = plain of int
```

```error
plain is not generic
```

## Error : Wrong number of type arguments : expected 1, got 2 (2)

```babble
def bad = id of (int, string)
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Type error : type argument int cannot be a primitive in array of T

```babble
def firstOf of T = fn (xs: array of T): T -> (xs.get(0))
def bad = firstOf of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : (Boxofint) is not generic

```babble
def bad = (Box of int) of string
```

```error
(Boxofint) is not generic
```

## Error : Wrong number of type arguments : expected 1, got 2 (3)

```babble
def bad = Box of (int, string)
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Type error : type argument int cannot be a primitive in array of T (2)

```babble
def ArrBox of T = class (def xs: array of T) ()
def bad = ArrBox of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : (java.util.ArrayListofint) is not generic

```babble
def bad = (java.util.ArrayList of int) of string
```

```error
(java.util.ArrayListofint) is not generic
```

## Generic record swap and expected-type int to double widening

```babble
def Pair of (A, B) = record (a: A, b: B) (
    def swap = fn (): Pair of (B, A) -> ( Pair(this.b, this.a) )
)
def p1 = Pair(1, "two")
assert(p1 == Pair(1, "two"))
assert(p1.swap() == Pair("two", 1))
def pd: Pair of (double, double) = Pair(1, 2)
assert(pd == Pair(1.0, 2.0))
```

## Generic method on a non-generic class

```babble
def Pair of (A, B) = record (a: A, b: B) ()
def Mapper = class () (
    def mapPair of (A, B) = fn (p: Pair of (A, B), f: fn (a: A): B): Pair of (B, B) -> ( Pair(f(p.a), p.b) )
)
assert(Mapper().mapPair(Pair(4, "4"), fn (n) -> ( "" + n )) == Pair("4", "4"))
```

## Static block on a generic class and contextual nullable default

```babble
def Tagged of T = class (def value: T) () static (
    def label = "tagged"
)
assert(Tagged.label == "tagged")
assert(Tagged(true).value == true)

def optOrNull = fn of T (x: T? = null): T? -> ( x )
def emptyOpt: int? = optOrNull()
assert(emptyOpt == null)
assert(optOrNull(3) == 3)
```
