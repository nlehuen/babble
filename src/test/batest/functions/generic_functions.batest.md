# Generic function literals, bounded type parameters (F-bounds & intersections), polymorphic function types,...

References : BIP021 (D17..D21, §2.2), BIP022 (intersection bounds)

```babble
;; babble module=poly_mod.ba
export def HasName = interface (
    def name : fn () : string
)
export def HasWeight = interface (
    def weight : fn () : int
)
export def maxOf of (T : java.lang.Comparable of T) = fn (a : T, b : T) : T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
export def describe of (T : HasName & HasWeight) = fn (x : T) : string -> (
    x.name() + ":" + x.weight()
)
export def applyBoth = fn (f : fn of T (t : T) : T) : string -> (
    "" + f(1) + "|" + f("x")
)
export def PolyIface = interface (
    def id of T : fn (t : T) : T
)
```

```babble
;; babble preamble
def Plain = class () ()
def Shape = interface ( def area : fn () : double )
def Named = interface ( def name : fn () : string )
def biggest of (T : java.lang.Comparable of T) = fn (a : T, b : T) : T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
def both = fn (f : fn of T (t : T) : T) : string -> ( "" + f(1) + f("a") )
```

## Case 1

```babble
def id = fn of T (t : T) : T -> ( t )
def swap = fn of (A, B) io (a : A, b : B) : string -> ( "" + b + ":" + a )
def alias = id
assert(id(42) == 42)
assert(id("hi") == "hi")
assert(alias(true))
assert(swap(1, "two") == "two:1")
```

## Type parameters declared on both def and its function literal is an error (BIP021-D18)

```babble
def id of T = fn of T (t : T) : T -> ( t )
```

```error
Type parameters declared twice : id
```

## Type parameters declared on both def and its function type annotation is an error

```babble
def id of T : fn of T (t : T) : T = fn (t : T) : T -> ( t )
```

```error
Type parameters declared twice : id
```

## Case 4

```babble
def Score = record (n : int) implements java.lang.Comparable of Score (
    def compareTo = fn (o : Score) : int -> ( this.n - o.n )
)
assert(biggest(3, 7) == 7)
assert(biggest("pear", "apple") == "pear")
assert(biggest(1.5, 0.5) == 1.5)
assert(biggest(Score(2), Score(9)).n == 9)
```

## Case 5

```babble
def PairMax of (T : java.lang.Comparable of T) = class (def a : T, def b : T) (
    def hi = fn () : T -> ( if this.a.compareTo(this.b) >= 0 then ( this.a ) else ( this.b ) )
)
assert(PairMax(10, 4).hi() == 10)
assert(PairMax("a", "z").hi() == "z")
```

## Error : Type error : type argument Plain does not satisfy bound java.lang.Com...

```babble
biggest(Plain(), Plain())
```

```error
Type error : type argument Plain does not satisfy bound java.lang.Comparable(Plain)
```

## Error : Type error : type argument Plain does not satisfy bound java.lang.Com... (2)

```babble
def bad = biggest of Plain
```

```error
Type error : type argument Plain does not satisfy bound java.lang.Comparable(Plain)
```

## Error : Type error : type argument Plain does not satisfy bound java.lang.Com... (3)

```babble
def SortedBox of (T : java.lang.Comparable of T) = class (def v : T) ()
def bad : SortedBox of Plain = SortedBox(Plain())
```

```error
Type error : type argument Plain does not satisfy bound java.lang.Comparable(Plain)
```

## Case 9

```babble
def Box = record (n : string, a : double) implements Shape, Named (
    def area = fn () : double -> ( this.a )
    def name = fn () : string -> ( this.n )
)
def tag of (T : Shape & Named) = fn (x : T) : string -> ( x.name() + "=" + x.area() )
assert(tag(Box("sq", 9.0)) == "sq=9.0")
```

## Error : Type error : type argument OnlyShape does not satisfy bound Shape & N...

```babble
def OnlyShape = class () implements Shape ( def area = fn () : double -> ( 1.0 ) )
def tag of (T : Shape & Named) = fn (x : T) : string -> ( x.name() + "=" + x.area() )
tag(OnlyShape())
```

```error
Type error : type argument OnlyShape does not satisfy bound Shape & Named
```

## Polymorphic function type parameter with named generic, explicit generic lambda, and unannotated rigid lambda

```babble
def id of T = fn (t : T) : T -> ( t )
assert(both(id) == "1a")
assert(both(fn of U (u : U) : U -> ( u )) == "1a")
assert(both(fn (t) -> ( t )) == "1a")
```

## Error : Type error : expected fn of T (t : T) : T, got fn (t : int) : int

```babble
both(fn (t : int) : int -> ( t ))
```

```error
Type error : expected fn of T (t : T) : T, got fn (t : int) : int
```

## Unannotated lambda checked with rigid type parameter rejects non-polymorphic body

```babble
both(fn (t) -> ( t + 1 ))
```

```error
Not a number : t
```

## Unbounded polymorphic function satisfies bounded polymorphic function type

```babble
def unb = fn of T (t : T) : T -> ( t )
def bnd : fn of (U : Shape) (u : U) : U = unb
def Sq = class () implements Shape ( def area = fn () : double -> ( 4.0 ) )
assert(bnd(Sq()).area() == 4.0)
```

## Bounded polymorphic function does not satisfy unbounded polymorphic function type

```babble
def bnd = fn of (T : Shape) (t : T) : T -> ( t )
def unb : fn of U (u : U) : U = bnd
```

```error
Type error : expected fn of U (u : U) : U, got fn of (T : Shape) (t : T) : T
```

## Case 16

```babble
def Mapper = interface (
    def id of T : fn (t : T) : T
    def wrap : fn of U (u : U) : U
)
def Impl = class () implements Mapper (
    def id of A = fn (a : A) : A -> ( a )
    def wrap = fn of B (b : B) : B -> ( b )
)
def m : Mapper = Impl()
assert(m.id(7) == 7)
assert(m.id("ok") == "ok")
assert(not m.wrap(false))
```

## Case 17

```babble
def withDef = fn of T (a : T, b : T = a) : T -> ( b )
def withNull = fn of T (a : T, b : T? = null) : T? -> ( b )
assert(withDef(3) == 3)
assert(withDef(3, 9) == 9)
assert(withDef(b: 8, a: 1) == 8)
assert(withNull("x") == null)
assert(withNull("x", "y") == "y")
```

## Case 18

```babble
def pairStr = fn of (A, B) io (first : A, second : B) : string -> ( "" + first + "-" + second )
assert(pairStr(second: 2, first: "one") == "one-2")
```

## Error : Type error : expected T, got null

```babble
def bad = fn of T (a : T, b : T = null) : T -> ( b )
```

```error
Type error : expected T, got null
```

## Type variable determined only by omitted optional argument needs expected type (never uses bound)

```babble
def f = fn of (T : java.lang.Comparable of T) (x : T? = null) : T? -> ( x )
f()
```

```error
Missing type : cannot infer type arguments for f
```

## Case 21

```babble
def countAll of (S : java.lang.Iterable of int) = fn (xs : S) : int -> (
    def total = 0
    for x in xs then (
        total += x
    )
    total
)
assert(countAll(list.of(10, 20, 30)) == 60)
```

## Case 22

```babble
def m = import("poly_mod.ba")
def MyPoly = class () implements m.PolyIface (
    def id of X = fn (x : X) : X -> ( x )
)
def p : m.PolyIface = MyPoly()
assert(m.maxOf(5, 9) == 9)
assert(m.maxOf("b", "a") == "b")
assert(m.applyBoth(fn of T (t : T) : T -> ( t )) == "1|x")
assert(p.id(123) == 123)
assert(p.id("abc") == "abc")
```

## Case 23

```babble
def m = import("poly_mod.ba")
def Item = record (n : string, w : int) implements m.HasName, m.HasWeight (
    def name = fn () : string -> ( this.n )
    def weight = fn () : int -> ( this.w )
)
assert(m.describe(Item("stone", 42)) == "stone:42")
```

## Error : Type error : type argument Plain does not satisfy bound java.lang.Com... (4)

```babble
def m = import("poly_mod.ba")
m.maxOf(Plain(), Plain())
```

```error
Type error : type argument Plain does not satisfy bound java.lang.Comparable(Plain)
```

## REPL session persistence of bounded generics and polymorphic values

```babble
;; babble session
def maxOf = fn of (T : java.lang.Comparable of T) (a : T, b : T) : T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
def id = fn of T (t : T) : T -> ( t )
```

```babble
;; babble session
assert(maxOf(4, 9) == 9)
assert(maxOf("x", "a") == "x")
assert(id(1) == 1)
assert(id("ok") == "ok")
```

## Exercises boxed primitive interface dispatch across all 9 scalar types

```babble
assert(biggest(byte(2), byte(5)) == byte(5))
assert(biggest(short(3), short(1)) == short(3))
assert(biggest(4, 9) == 9)
assert(biggest(10L, 2L) == 10L)
assert(biggest(1.5f, 2.5f) == 2.5f)
assert(biggest(3.5, 1.5) == 3.5)
assert(biggest(false, true))
assert(biggest('a', 'z') == 'z')
assert(biggest("cat", "ant") == "cat")
```
