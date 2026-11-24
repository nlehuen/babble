# Overload overlap, static meet, target selection, and overload call resolution

References : `BIP026` (overload sets), `analysis.OverloadSolver`, `analysis.OverloadCallResolver`.

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def Demo = tests.JavaClassDemo
def I1 = interface ( def a: fn (): int )
def I2 = interface ( def b: fn (): int )
def C1 = record (x: int) implements I1 ( def a = fn (): int -> ( this.x ) )
def C2 = record (y: int) implements I2 ( def b = fn (): int -> ( this.y ) )
def Box of T = class (def v: T) ()
def Val of T = record (v: T) ()
def GenI of T = interface ( def get: fn (): T )
def GenC = record (v: string) implements GenI of string ( def get = fn (): string -> ( this.v ) )
```

## Overloaded method implements Java varargs interface via rest alternative

```babble
def MySink = class () implements tests.JavaClassDemo.VarArgsSink (
    def sum = overload (
        fn (s: string): int -> ( s.length() ),
        fn (xs: int...): int -> ( def t = 0 ; for x in xs then ( t += x ) ; t )
    )
)
assert(Demo.invokeVarArgsSink(MySink(), 1, 2, 3) == 6)
```

## Target selection for Java SAM and Babble function picks strictly more specific later alternative

```babble
def f = overload (
    fn (x: any): string -> ( "any" ),
    fn (x: string): string -> ( "str:" + x )
)
def jf: java.util.function.Function of (string, string) = f
def bf: fn (string): string = f
assert(jf.apply("hi") == "str:hi" and bf("ok") == "str:ok")
```

## Function and array parameter overloads are statically disjoint

```babble
def d = overload (
    fn (f: fn (int): int): int -> ( f(1) ),
    fn (f: fn (int, int): int): int -> ( f(1, 2) ),
    fn (a: array of int): int -> ( a.size() ),
    fn (a: array of double): int -> ( a.size() + 10 ),
    fn (s: string): int -> ( s.length() )
)
assert(d(fn (x: int): int -> ( x + 1 )) == 2 and d(array.of(1, 2)) == 2 and d(array.of(1.0)) == 11 and d("abc") == 3)
```

## Generic record, class, and interface-first overloads check type-arg overlap

```babble
def g = overload (
    fn (x: Val of int): int -> ( x.v ),
    fn (x: Val of string): int -> ( x.v.length() ),
    fn (x: GenI of int): int -> ( x.get() ),
    fn (x: GenC): int -> ( x.get().length() )
)
assert(g(Val(5)) == 5 and g(Val("hi")) == 2 and g(GenC("abc")) == 3)
```

## Java-first vs Babble interface and record overloads

```babble
def j1 = overload (
    fn (x: java.lang.StringBuilder): int -> ( x.length() ),
    fn (x: I1): int -> ( x.a() ),
    fn (x: C2): int -> ( x.b() )
)
assert(j1(java.lang.StringBuilder("ab")) == 2 and j1(C1(7)) == 7 and j1(C2(9)) == 9)
```

## Covariant and invariant Java collection hierarchy overloads

```babble
def j2 = overload (
    fn (x: java.lang.Iterable of int): int -> ( 10 ),
    fn (x: java.util.List of string): int -> ( 20 )
)
assert(j2(list(1)) == 10 and j2(list("x")) == 20)
```

## Two nullable disjoint types overlap at null

```babble
def bad = overload ( fn (x: int?): int -> ( 1 ), fn (x: string?): int -> ( 2 ) )
```

```error
Ambiguous overloads of bad : bad(null) fits fn (x : int?) : int and fn (x : string?) : int
```

## Cross-generic alternatives hit typeVarMeet

```babble
def bad = overload ( fn of T (x: T, y: int): int -> ( 1 ), fn of U (x: int, y: U): int -> ( 2 ) )
```

```error
Ambiguous overloads of bad : bad(int, int) fits fn of T (x : T, y : int) : int and fn of U (x : int, y : U) : int
```

## Cross-specificity pair hits paramAccepts in staticMeet

```babble
def bad = overload ( fn (x: C1, y: I1): int -> ( 1 ), fn (x: I1, y: C1): int -> ( 2 ) )
```

```error
Ambiguous overloads of bad : bad(C1, C1) fits fn (x : C1, y : I1) : int and fn (x : I1, y : C1) : int
```

## Frozen interface and mutable interface meet at frozen intersection

```babble
def bad = overload ( fn (x: frozen I1): int -> ( 1 ), fn (x: I2): int -> ( 2 ) )
```

```error
Ambiguous overloads of bad : bad(frozen (I1 & I2)) fits fn (x : frozen I1) : int and fn (x : I2) : int
```

## Rest-first alternative with fixed alternative and keyword-only options

```babble
def f = overload (
    fn (xs: int...): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s ),
    fn (a: int, ..., tag: string): int -> ( a + tag.length() )
)
assert(f(1, 2, 3) == 6 and f(5, tag: "hi") == 7)
```

## Overloads sharing multiple optional named parameters

```babble
def g = overload (
    fn (x: int, a: int = 1, b: int = 2): int -> ( x + a + b ),
    fn (x: string, a: int = 1, b: int = 2): int -> ( x.length() + a + b )
)
assert(g(10) == 13 and g("hi", a: 3, b: 4) == 9)
```

## Union of two Function types is rejected as runtime-indistinguishable

```babble
def f = overload ( fn (g: fn (int): int): int -> ( g(1) ), fn (g: fn (int, int): int): int -> ( g(1, 2) ) )
def go = fn (u: (fn (int): int) | (fn (int, int): int)): int -> ( f(u) )
```

```error
Cannot dispatch f on (fn (p0 : int) : int) | (fn (p0 : int, p1 : int) : int) : its alternatives are not told apart at runtime
```

## Generic overload alternative infers untyped lambda parameter

```babble
def applyOv = overload (
    fn of T (x: T, f: fn (T): int): int -> ( f(x) ),
    fn (x: int): int -> ( x )
)
assert(applyOv(5) == 5 and applyOv("hello", fn (s) -> ( s.length() )) == 5)
```

## Generic overload alternative skips wrong-arity untyped lambda

```babble
def applyOv = overload (
    fn of T (x: T, f: fn (T): int): int -> ( f(x) ),
    fn (x: int): int -> ( x )
)
applyOv("hi", fn (a, b) -> ( 1 ))
```

```error
No applicable overload of applyOv for (string, fn (...)) : candidates are (x : T, f : fn (p0 : T) : int), (x : int)
```

## Generic overload alternative accepts matching bound and skips non-matching

```babble
def f = overload (
    fn of (T: I1) (x: T): int -> ( x.a() ),
    fn (x: string): int -> ( x.length() )
)
assert(f(C1(4)) == 4 and f("abc") == 3)
```

## Generic overload alternative rejects failed bound with no fallback

```babble
def f = overload (
    fn of (T: I1) (x: T): int -> ( x.a() ),
    fn (x: string): int -> ( x.length() )
)
f(42)
```

```error
No applicable overload of f for (int) : candidates are (T : I1) (x : T), (x : string)
```

## Generic overload alternative rejects primitive array substitution

```babble
def f = overload (
    fn of T (xs: array of T): int -> ( xs.size() ),
    fn (x: string): int -> ( x.length() )
)
f(array.of(1, 2))
```

```error
No applicable overload of f for (array of int) : candidates are (xs : array of T), (x : string)
```

## Generic rest and sequence overload alternatives accept list and array spreads

```babble
def g = overload (
    fn of T (xs: T...): int -> ( def n = 0 ; for x in xs then ( n += 1 ) ; n ),
    fn (tag: string, xs: list of int): int -> ( tag.length() + xs.size() )
)
assert(g(...list(1, 2)) == 2 and g(...array.of("a", "b", "c")) == 3 and g("k", ...array.of(1, 2)) == 3)
```

## Overload rest alternative skips non-iterable spread

```babble
def g = overload ( fn (xs: int...): int -> ( 1 ), fn (s: string): int -> ( 2 ) )
g(...42)
```

```error
No applicable overload of g for (...int) : candidates are (xs : int...), (s : string)
```

## Overload call with named argument formats name in miss diagnostic

```babble
def g = overload ( fn (a: int): int -> ( a ), fn (s: string): int -> ( s.length() ) )
g(bad: 1)
```

```error
No applicable overload of g for (bad: int) : candidates are (a : int), (s : string)
```

## Union split product cap falls through to No applicable overload

```babble
def g = overload (
    fn (a: int, b: int, c: int, d: int, e: int, f: int, h: int): int -> ( 1 ),
    fn (a: string, b: string, c: string, d: string, e: string, f: string, h: string): int -> ( 2 )
)
def go = fn (u: int | string): int -> ( g(u, u, u, u, u, u, u) )
```

```error
No applicable overload of g for (int | string, int | string, int | string, int | string, int | string, int | string, int | string) : candidates are (a : int, b : int, c : int, d : int, e : int, f : int, h : int), (a : string, b : string, c : string, d : string, e : string, f : string, h : string)
```

## Overloaded operator splits nullable union with bounded generic alternative

```babble
def Acc = class () (
    def plus = overload (
        fn (n: null): int -> ( 0 ),
        fn (n: int): int -> ( n ),
        fn of (T: I1) (x: T): int -> ( x.a() )
    )
)
def go = fn (a: Acc, v: C1 | int | null): int -> ( a + v )
assert(go(Acc(), null) == 0 and go(Acc(), 7) == 7 and go(Acc(), C1(9)) == 9)
```

## Overloaded operator fails cleanly when union member has no matching alternative

```babble
def Acc = class () (
    def plus = overload ( fn (n: int): int -> ( n ), fn (s: string): int -> ( s.length() ) )
)
def go = fn (a: Acc, v: int | bool): int -> ( a + v )
```

```error
No applicable overload of a.plus for (int | bool) : candidates are (n : int), (s : string)
```

## Overloaded unary operator neg with no zero-arg alternative reports empty arg list

```babble
def Acc = class () (
    def neg = overload ( fn (n: int): int -> ( -n ), fn (s: string): int -> ( s.length() ) )
)
def a = Acc()
-a
```

```error
No applicable overload of a.neg for () : candidates are (n : int), (s : string)
```
