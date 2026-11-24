# Call lowering : core collections, cross-module dispatch, overloads, and SAM spreads

References : BIP001, BIP002, BIP006, BIP011 ; targets `CallEmitter`, `HirBuilder`

```babble module=ext_ops.ba
export def Vec = record (x : int) (
    def plus = overload (
        fn (n : int) : Vec -> ( Vec(this.x + n) ),
        fn (o : Vec) : Vec -> ( Vec(this.x + o.x) )
    )
    def inc = fn (n : int, step : int = 5) : int -> ( this.x + n + step )
    def tag = fn (s : string) : string -> ( s + ":" + this.x )
    def sum9 = fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> (
        this.x + a + b + c + d + e + f + g + h + i
    )
)
export def LateBox = class () (
    def peek = fn () : int -> ( 1 )
    def init = this.peek()
    def late : int = 42
)
```

```babble module=hier_base.ba
export def A0 = interface ( def a0 = fn () : int -> ( 1 ) )
```

```babble module=hier_mid.ba
def b = import("hier_base.ba")
def A1 = interface extends b.A0 ( def a1 = fn () : int -> ( 2 ) )
export def A2 = interface extends A1 ( def a2 = fn () : int -> ( this.a0() + this.a1() ) )
```

```babble preamble
def tests = import("org.babblelang.tests")
def revInt = fn (a : int, b : int) : int -> ( b - a )
def revStr = fn (a : string, b : string) : int -> ( b.compareTo(a) )
```

## Core sortedset and sortedmap from-copy factories

```babble
def ss = sortedset(from: list(3, 1, 2))
def sm = sortedmap(from: map(entry("b", 2), entry("a", 1)))
assert(ss.size() == 3 and ss.first() == 1 and ss.last() == 3)
assert(sm.size() == 2 and sm.firstKey() == "a" and sm.get("b") == 2)
```

## Core sortedset and sortedmap from-plus-comparator in both argument orders

```babble
def xs = list(1, 3, 2)
def m = map(entry("a", 1), entry("c", 3), entry("b", 2))
def ss1 = sortedset(from: xs, comparator: revInt)
def ss2 = sortedset(comparator: revInt, from: xs)
def sm1 = sortedmap(from: m, comparator: revStr)
def sm2 = sortedmap(comparator: revStr, from: m)
assert(ss1.first() == 3 and ss2.first() == 3 and sm1.firstKey() == "c" and sm2.firstKey() == "c")
```

## Cross-module direct overloaded operator, optional spec method, and >8-param fallback

```babble
def m = import("ext_ops.ba")
def v = m.Vec(10)
assert((v + 5).x == 15)
assert((v + m.Vec(3)).x == 13)
assert(v.inc(2) == 17 and v.inc(2, 1) == 13)
assert(v.tag("v") == "v:10")
assert(v.sum9(1, 2, 3, 4, 5, 6, 7, 8, 9) == 55)
```

## Cross-module definite and non-definite field reads plus transitive interface hierarchy

```babble
def eo = import("ext_ops.ba")
def hm = import("hier_mid.ba")
def lb = eo.LateBox()
def Impl = class () implements hm.A2 ()
assert(eo.Vec(7).x == 7 and lb.init == 1 and lb.late == 42 and Impl().a2() == 3)
```

## Dynamic function-typed call with >8 arguments (positional and named)

```babble
def sum9 = fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> (
    a + b + c + d + e + f + g + h + i
)
def call9 = fn (g: fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int): int): int -> (
    g(1, 2, 3, 4, 5, 6, 7, 8, 9) + g(1, 2, 3, 4, 5, 6, 7, 8, i: 10)
)
assert(call9(sum9) == 91)
```

## Overload direct >8-param alternative and anonymous inline overload call

```babble
def big = overload (
    fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> ( a + i ),
    fn (s : string) : int -> ( s.length() )
)
assert(big(1, 2, 3, 4, 5, 6, 7, 8, 9) == 10 and big("abc") == 3)
assert((overload ( fn (x : int) : int -> ( x + 1 ), fn (s : string) : int -> ( s.length() ) ))(5) == 6)
```

## Union-split overload dispatch with optional primitive and reference defaults

```babble
def f = overload (
    fn (x : int, step : int = 10) : int -> ( x + step ),
    fn (s : string, tag : string = "!") : string -> ( s + tag )
)
def run = fn (u : int | string) : int | string -> ( f(u) )
assert(run(5) == 15 and run("hi") == "hi!")
```

## Union-split overload dispatch with rest parameters on reference and primitive branches

```babble
def g = overload (
    fn (x : int, r : int...) : int -> ( def s = x ; for v in r then ( s += v ) ; s ),
    fn (s : string, r : string...) : string -> ( def o = s ; for v in r then ( o += v ) ; o )
)
def runG = fn (u : int | string) : int | string -> ( g(u) )
assert(runG(3) == 3 and runG("a") == "a")
```

## Union-split overloaded class method exercising rest and trailing optional params

```babble
def Box = class () (
    def m = overload (
        fn (x : int, a : int = 1, b : int = 2) : int -> ( x + a + b ),
        fn (s : string, r : string...) : string -> ( def o = s ; for v in r then ( o += v ) ; o )
    )
)
def run = fn (bx : Box, u : int | string) : int | string -> ( bx.m(u) )
assert(run(Box(), 4) == 7 and run(Box(), "hi") == "hi")
```

## Union-split overloaded class method with middle omitted optional param

```babble
def Box = class () (
    def m = overload (
        fn (x : int, a : int = 1, b : int = 2) : int -> ( x + a + b ),
        fn (s : string, a : int = 1, b : int = 2) : string -> ( s + ":" + (a + b) )
    )
)
def run = fn (bx : Box, u : int | string) : int | string -> ( bx.m(u, b: 10) )
assert(run(Box(), 4) == 15 and run(Box(), "hi") == "hi:11")
```

## Overloaded interface method with named arg, omitted optional arg, and safe call

```babble
def IOv = interface (
    def calc = overload (
        fn (x: int, step: int = 10): int -> ( x + step ),
        fn (s: string): int -> ( s.length() )
    )
)
def C = class () implements IOv ()
def iv: IOv = C() ; def opt: C? = C()
assert(iv.calc(x: 5) == 15 and iv.calc(5) == 15 and opt?.calc(x: 2) == 12)
```

## Overloaded interface operator method

```babble
def IOv = interface (
    def plus = overload ( fn (n: int): int -> ( n + 1 ), fn (s: string): int -> ( s.length() ) )
)
def C = class () implements IOv ()
def iv: IOv = C()
assert((iv + 4) == 5 and (iv + "hi") == 2)
```

## Non-accessible Java SAM spread into Iterable (asIterableByName)

```babble
def takeIt = fn io (shared xs: java.lang.Iterable of tests.JavaClassDemo.HiddenIntSam): int -> (
    def n = 0 ; for x in xs then ( n += tests.JavaClassDemo.invokeHiddenSam(x, 4) ) ; n
)
def fns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x * 2 ))
assert(takeIt(...fns) == 13)
```

## Non-accessible Java SAM spread into List (asListByName)

```babble
def takeL = fn io (shared xs: list of tests.JavaClassDemo.HiddenIntSam): int -> (
    tests.JavaClassDemo.invokeHiddenSam(xs.get(0), 5)
)
def fns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x * 2 ))
assert(takeL(...fns) == 6)
```

## Non-accessible Java SAM spread into Object-erased array

```babble
def takeArr = fn io (shared xs: array of (tests.JavaClassDemo.HiddenIntSam & java.io.Serializable)): int -> (
    tests.JavaClassDemo.invokeHiddenSerializable(xs.get(0), 6)
)
def fns = list(fn (x: int): int -> ( x + 3 ))
assert(takeArr(...fns) == 9)
```

## Non-accessible Java SAM spread into hidden-marker list

```babble
def takeMk = fn (xs: list of (tests.JavaClassDemo.SameSigRunnable & tests.JavaClassDemo.HiddenMarkerSam)): int -> (
    xs.size()
)
def runs = list(fn () -> ())
assert(takeMk(...runs) == 1)
```
