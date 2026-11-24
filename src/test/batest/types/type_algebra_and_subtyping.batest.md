# Type algebra, function subtyping, array variance, castability, and normalization

References : `BIP008` (typing), `BIP022` (intersections), `BIP026` (overloads), `analysis.TypeAlgebra`, `analysis.Type`.

```babble
;; babble preamble
def IA = interface (def x : fn () : int)
def IB = interface extends IA ()
def IC = interface (def y : fn () : int)
def Box = class (def v : int) ()
def Impl = record (v : int) implements IB, IC (
    def x = fn () : int -> ( this.v )
    def y = fn () : int -> ( this.v + 1 )
)
def Other = record (v : int) ()
def MyRun = class () implements java.lang.Runnable (
    def run = fn () -> ( null )
)
def ImmRec = record (v : int) implements IA (
    def x = fn () : int -> ( this.v )
)
def passInt = fn (x : int) : int -> ( x )
def passStr = fn (s : string) : string -> ( s )
```

## Polymorphic function literal instantiates to monomorphic target with bound

```babble
def f : fn (Impl) : Impl = fn of (T : IA) (v : T) : T -> ( v )
def g : fn (int) : int = fn of T (v : T) : T -> ( v )
assert(f(Impl(3)).v == 3 and g(9) == 9)
```

## Polymorphic function literal rejected when monomorphic target violates bound

```babble
def f : fn (Other) : Other = fn of (T : IA) (v : T) : T -> ( v )
```

```error
Type error : expected (p0 : Other) : Other, got fn of (T : IA) (v : T) : T
```

## Polymorphic function literal rejected when type parameter is unconstrained

```babble
def f : fn (int) : int = fn of T (x : int) : int -> ( x )
```

```error
Type error : expected (p0 : int) : int, got fn of T (x : int) : int
```

## Polymorphic function literal rejected on primitive array substitution

```babble
def f : fn (int, fn (any) : int) : int =
    fn of T (x : T, g : fn (array of T) : int) : int -> ( 0 )
```

```error
Type error : expected (p0 : int, p1 : (p0 : any) : int) : int, got fn of T (x : T, g : (p0 : array of T) : int) : int
```

## Variadic target rejects source with fewer keyword-only parameters

```babble
def bad : fn (xs : int..., k : int = ...) : int = fn (xs : int...) : int -> ( 0 )
```

```error
Type error : expected (xs : int..., k : int = ...) : int, got (xs : int...) : int
```

## Variadic target rejects required parameter for optional positional parameter

```babble
def bad : fn (a : int = ..., xs : int...) : int = fn (a : int, xs : int...) : int -> ( a )
```

```error
Type error : expected (a : int = ..., xs : int...) : int, got (a : int, xs : int...) : int
```

## Variadic target rejects required keyword-only for optional keyword-only parameter

```babble
def bad : fn (xs : int..., k : int = ...) : int = fn (xs : int..., k : int) : int -> ( k )
```

```error
Type error : expected (xs : int..., k : int = ...) : int, got (xs : int..., k : int) : int
```

## Variadic target rejects extra required keyword-only parameter

```babble
def bad : fn (xs : int...) : int = fn (xs : int..., k : int) : int -> ( k )
```

```error
Type error : expected (xs : int...) : int, got (xs : int..., k : int) : int
```

## Variadic source satisfies non-variadic target when trailing keyword param is optional

```babble
def f : fn (int) : int = fn (xs : int..., k : int = 5) : int -> ( k )
assert(f(1) == 5)
```

## Variadic source cannot satisfy non-variadic target with keyword-only parameter

```babble
def bad : fn (..., k : int) : int = fn (xs : int...) : int -> ( 0 )
```

```error
Type error : expected (..., k : int) : int, got (xs : int...) : int
```

## Variadic source cannot satisfy non-variadic target with required pre-rest parameter

```babble
def bad : fn (int) : int = fn (a : int, b : int, xs : int...) : int -> ( a + b )
```

```error
Type error : expected (p0 : int) : int, got (a : int, b : int, xs : int...) : int
```

## Variadic source cannot satisfy non-variadic target with required trailing keyword

```babble
def bad : fn (int) : int = fn (xs : int..., k : int) : int -> ( k )
```

```error
Type error : expected (p0 : int) : int, got (xs : int..., k : int) : int
```

## Single wide function satisfies overload type

```babble
def wide : overload (fn (int) : any, fn (string) : any) = fn (x : any) : any -> ( x )
assert(wide(1) == 1 and wide("a") == "a")
```

## Function fails overload type when one required signature mismatches

```babble
def bad : overload (fn (int) : int, fn (string) : string) = fn (x : any) : int -> ( 0 )
```

```error
Type error : expected overload (fn (p0 : int) : int, fn (p0 : string) : string), got (x : any) : int
```

## Non-callable value fails overload type

```babble
def bad : overload (fn (int) : int, fn (string) : string) = 42
```

```error
Type error : expected overload (fn (p0 : int) : int, fn (p0 : string) : string), got int
```

## Overload set fails Java SAM when no alternative matches

```babble
def ov = overload (passInt, passStr)
def bad : java.lang.Runnable = ov
```

```error
Type error : expected java.lang.Runnable, got overload (fn (x : int) : int, fn (s : string) : string)
```

## Overload set fails wider overload type when an alternative is missing

```babble
def ov = overload (passInt, passStr)
def bad : overload (fn (int) : int, fn (bool) : bool) = ov
```

```error
Type error : expected overload (fn (p0 : int) : int, fn (p0 : bool) : bool), got overload (fn (x : int) : int, fn (s : string) : string)
```

## Covariant JVM reference arrays for Babble and Java interfaces

```babble
def a1 : array of IB = (array of Impl).of(Impl(1))
def a2 : array of java.lang.Runnable = (array of MyRun).of(MyRun())
def a5 : array of java.lang.CharSequence = (array of string).of("hi")
def c : java.lang.Cloneable = a1
def s : java.io.Serializable = a1
assert(a1.size() == 1 and a2.size() == 1 and a5.size() == 1 and c != null and s != null)
```

## Covariant nested primitive arrays assignable to Cloneable and Serializable arrays

```babble
def a3 : array of java.lang.Cloneable = (array of (array of int)).of(array.of(1))
def a4 : array of java.io.Serializable = (array of (array of int)).of(array.of(2))
assert(a3.size() == 1 and a4.size() == 1)
```

## Non-null array is not assignable to nullable array

```babble
def a = (array of string).of("x")
def b : array of string? = a
```

```error
Type error : expected array of string?, got array of string
```

## Erased Object array is not assignable to wider array of any

```babble
def a : array of (int | string) = (array of (int | string)).of(1, "a")
def b : array of any = a
```

```error
Type error : expected array of any, got array of (int | string)
```

## Nested array of int is not assignable to array of Comparable

```babble
def a = (array of (array of int)).of(array.of(1))
def b : array of (java.lang.Comparable of int) = a
```

```error
Type error : expected array of (java.lang.Comparable(int)), got array of (array of int)
```

## Array of Babble class not implementing Java interface is rejected

```babble
def a = (array of Other).of(Other(1))
def b : array of java.lang.Runnable = a
```

```error
Type error : expected array of java.lang.Runnable, got array of Other
```

## Valid casts across interfaces, frozen values, and Java interfaces

```babble
def check = fn (x : IB & IC, r : java.lang.Runnable, fa : frozen any, imm : ImmRec, run : bool) : bool -> (
    def c1 = x as IA
    def c3 = fa as int
    def c4 = imm as frozen IA
    if run then ( discard(r as IA) )
    c1.x() == 2 and c3 == 1 and c4.x() == 2
)
assert(check(Impl(2), MyRun(), freeze(1 as any), ImmRec(2), false))
```

```stderr
Warning : Redundant cast : IA
Warning : Redundant cast : frozen IA
Warning : Redundant cast : any
```

## Valid casts across erased union arrays and non-final Java classes

```babble
def check = fn (u : array of (int | string), j : java.util.AbstractList of any?) : bool -> (
    def c5 = u as array of (string | bool)
    def c6 = j as java.io.Serializable
    c5.size() == 1 and c6 != null
)
def al = java.util.ArrayList of any?()
def arr : array of (int | string) = (array of (int | string)).of("k")
assert(check(arr, al))
```

```stderr
Warning : Unchecked cast : array of (string | bool)
```

## Final Java class cannot cast to unrelated interface

```babble
def f = fn (s : string) -> ( s as java.lang.Runnable )
```

```error
Type error : expected java.lang.Runnable, got string
```

## Primitive array cannot cast to different primitive array

```babble
def f = fn (a : array of int) -> ( a as array of double )
```

```error
Type error : expected array of double, got array of int
```

## Babble interface cannot cast to unrelated final Java class

```babble
def f = fn (x : IA) -> ( x as java.lang.StringBuilder )
```

```error
Type error : expected java.lang.StringBuilder, got IA
```

## Unfrozen any cannot cast to frozen mutable class

```babble
def f = fn (x : any) -> ( x as frozen Box )
```

```error
Type error : expected frozen Box, got any
```

## Function cannot cast to intersection of two Java interfaces

```babble
def f = fn () -> ( (fn (x : int) : int -> ( x )) as (java.lang.Runnable & java.io.Serializable) )
```

```error
Type error : expected java.lang.Runnable & java.io.Serializable, got (x : int) : int
```

## Reassigning pure function to io and shared merges signatures

```babble
def f = fn (x : Box) : int -> ( x.v )
f = fn io (x : Box) : int -> ( x.v + 1 )
def g = fn (x : Box) : int -> ( x.v )
g = fn (shared x : Box) : int -> ( x.v + 2 )
assert(f(Box(1)) == 2 and g(Box(1)) == 3)
```

## Joining calling-forwarded functions across if branches merges signatures

```babble
def h = fn (b : bool) -> (
    if b then ( fn calling cb (cb : fn () : int) : int -> ( cb() ) )
    else ( fn calling cb (cb : fn () : int) : int -> ( cb() + 1 ) )
)
assert(h(false)(fn () : int -> ( 4 )) == 5)
```

## Reassignment widening to nullable and joining byte, short, and int

```babble
def w = fn (b : bool) : int -> (
    def x = 1
    x = if b then ( 2 ) else ( null )
    def s = if b then ( byte(1) ) else ( short(2) )
    def i = if b then ( short(2) ) else ( 3 )
    def v = if x != null then ( x ) else ( 0 )
    v + int(s) + i
)
assert(w(true) == 5 and w(false) == 5)
```

## Union normalization with frozen and any types

```babble
def f = fn (b: bool, x: frozen Box, y: any, z: frozen any) -> (
    def u1: any | frozen Box = if b then ( y ) else ( x )
    def u2: any | frozen any = if b then ( y ) else ( z )
    def u3: frozen any | frozen Box = if b then ( z ) else ( x )
    def ff: frozen (frozen Box) = x
    assert(u1 != null and u2 != null and u3 != null and ff.v == 1)
)
f(true, freeze(Box(1)), 1, freeze(Box(2)))
```

## Intersection normalization with duplicates, any, and Java interfaces

```babble
def i1 : IA & IA = Impl(1)
def i2 : any & any = 1
def i3 : java.util.List of int & java.util.Collection of int = list of int(1)
assert(i1.x() == 1 and i2 == 1 and i3.size() == 1)
```

## Function mismatch diagnostics format volatile, keyword-only, and unique return

```babble
def bad : fn (..., k : int) : unique Box = fn volatile (..., k : int) : unique Box -> ( Box(k) )
```

```error
Type error : expected (..., k : int) : unique Box, got volatile (..., k : int) : unique Box
```
