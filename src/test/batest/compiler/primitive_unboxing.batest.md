# Full 8-primitive unboxed lowering : locals, 2-slot long/double, cells, fields, bridges, and e!

References : BIP011 (§7 primitive unboxing), BIP001 (RM001/RM003 lowering)

## 8-primitive parameter lowering including 2-slot long/double and branch selection

```babble
def mix = fn (b: byte, s: short, i: int, l: long, f: float, d: double, c: char, ok: bool): double -> (
    def sl = l + long(i) + long(b) + long(s)
    def sf = f + float(i)
    if ok and c == 'A' then ( double(sl) + double(sf) + d ) else ( 0.0 )
)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'A', true) == 116.5)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'B', true) == 0.0)
```

## Unboxed unary negation and arithmetic on narrow/wide primitives (parameterized against -O1 folding)

```babble
def negMix = fn (b : byte, s : short, l : long, f : float) : double -> (
    def nb : int = -b
    def ns : int = -s
    def nl : long = -l + 10L
    def nf : float = -f + 1.5f
    double(nb + ns) + double(l + nl) + double(f + nf)
)
assert(negMix(byte(2), short(3), 100L, 2.5f) == 6.5)
```

## Parameterized against -O1 folding to exercise unboxed byte/short truthiness

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short) -> (
    assert((if b0 then ( 1 ) else ( 0 )) == 0 and (if b1 then ( 1 ) else ( 0 )) == 1)
    assert((if s0 then ( 1 ) else ( 0 )) == 0 and (if s1 then ( 1 ) else ( 0 )) == 1)
)
check(byte(0), byte(5), short(0), short(7))
```

## Parameterized against -O1 folding to exercise unboxed long/float truthiness

```babble
def check = fn (l0 : long, l1 : long, f0 : float, f1 : float) -> (
    assert((if l0 then ( 1 ) else ( 0 )) == 0 and (if l1 then ( 1 ) else ( 0 )) == 1)
    assert((if f0 then ( 1 ) else ( 0 )) == 0 and (if f1 then ( 1 ) else ( 0 )) == 1)
)
check(0L, 10000000000L, 0.0f, 1.5f)
```

## Parameterized against -O1 folding to exercise unboxed primitive comparisons

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short, l1 : long, f1 : float, c0 : char, c1 : char) -> (
    assert(b0 < b1 and s1 > s0 and l1 > 5000000000L and f1 >= 1.5f and c0 < c1 and c0 == 'a')
)
check(byte(0), byte(5), short(0), short(7), 10000000000L, 1.5f, 'a', 'z')
```

## Captured mutable ByteCell, ShortCell, and CharCell

```babble
def make = fn () -> (
    def b : byte = 1; def s : short = 10; def c : char = 'a'
    fn io () : string -> (
        b = byte(int(b) + 1); s = short(int(s) + 2); c = char(int(c) + 1)
        string(b) + ":" + string(s) + ":" + string(c)
    )
)
def step = make()
assert(step() == "2:12:b" and step() == "3:14:c")
```

## Captured mutable LongCell and FloatCell

```babble
def make = fn () -> (
    def l : long = 100L; def f : float = 1.5f
    fn io () : string -> (
        l = l + 10L; f = f + 0.5f
        string(l) + ":" + string(f)
    )
)
def step = make()
assert(step() == "110:2.0" and step() == "120:2.5")
```

## Case 8

```babble
def H = class (def b: byte, def s: short, def l: long, def f: float, def c: char) (
    def sum = fn (x: long): long -> ( this.l + x + long(this.b) + long(this.s) )
)
def h = H(byte(3), short(9), 1000L, 2.25f, 'K')
assert(h.sum(50L) == 1062L and h.f == 2.25f and h.c == 'K')
h.b = byte(4); h.s = short(12); h.l = 2000L; h.f = 3.5f; h.c = 'Z'
assert(h.sum(1L) == 2017L and h.f == 3.5f and h.c == 'Z')
```

## Case 9

```babble
def Scaler = interface (
    def scale : fn (x : long, factor : float) : double
)
def MyScaler = class (def base : long) implements Scaler (
    def scale = fn (x : long, factor : float) : double -> (
        double(this.base + x) * double(factor)
    )
)
def runScaler = fn (sc : Scaler) : double -> ( sc.scale(5L, 2.5f) )
assert(runScaler(MyScaler(3L)) == 20.0)
```

## Case 10

```babble
def op : java.util.function.LongUnaryOperator = fn (x : long) : long -> ( x * 3L + 1L )
assert(op.applyAsLong(10L) == 31L)
def func : java.util.function.ToLongFunction of string = fn (s : string) : long -> ( long(s.length()) * 100L )
assert(func.applyAsLong("hello") == 500L)
```

## Case 11

```babble
def unwrapAll = fn (b : byte?, s : short?, l : long?, f : float?, c : char?) : string -> (
    def ub : byte = b!; def us : short = s!; def ul : long = l!; def uf : float = f!; def uc : char = c!
    string(ub) + ":" + string(us) + ":" + string(ul) + ":" + string(uf) + ":" + string(uc)
)
assert(unwrapAll(byte(1), short(2), 3L, 4.5f, 'Q') == "1:2:3:4.5:Q")
```

## Case 12

```babble
def capParams = fn (b : byte, s : short, l : long, f : float, c : char) -> (
    fn () : string -> ( string(b) + ":" + string(s) + ":" + string(l) + ":" + string(f) + ":" + string(c) )
)
assert(capParams(byte(1), short(2), 3L, 4.5f, 'Z')() == "1:2:3:4.5:Z")
```

## Error : Name used before definition : x

```babble
def run = fn () : byte -> (
    def f = fn () : byte -> ( x )
    def r = f()
    def x : byte = 1
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (2)

```babble
def run = fn () : byte -> (
    def f = fn () : byte -> ( x = byte(2) )
    def r = f()
    def x : byte = 1
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (3)

```babble
def run = fn () : short -> (
    def f = fn () : short -> ( x )
    def r = f()
    def x : short = 1
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (4)

```babble
def run = fn () : short -> (
    def f = fn () : short -> ( x = short(2) )
    def r = f()
    def x : short = 1
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (5)

```babble
def run = fn () : long -> (
    def f = fn () : long -> ( x )
    def r = f()
    def x : long = 1L
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (6)

```babble
def run = fn () : long -> (
    def f = fn () : long -> ( x = 2L )
    def r = f()
    def x : long = 1L
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (7)

```babble
def run = fn () : float -> (
    def f = fn () : float -> ( x )
    def r = f()
    def x : float = 1.0f
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (8)

```babble
def run = fn () : float -> (
    def f = fn () : float -> ( x = 2.0f )
    def r = f()
    def x : float = 1.0f
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (9)

```babble
def run = fn () : char -> (
    def f = fn () : char -> ( x )
    def r = f()
    def x : char = 'a'
    r
)
run()
```

```error
Name used before definition : x
```

## Error : Name used before definition : x (10)

```babble
def run = fn () : char -> (
    def f = fn () : char -> ( x = 'b' )
    def r = f()
    def x : char = 'a'
    r
)
run()
```

```error
Name used before definition : x
```

## Generic Babble interface bridge unboxing for byte and short

```babble
def Box of T = interface ( def id: fn (x: T): T )
def BB = class () implements Box of byte ( def id = fn (x: byte): byte -> ( x ) )
def SB = class () implements Box of short ( def id = fn (x: short): short -> ( x ) )
def b: Box of byte = BB() ; def s: Box of short = SB()
assert(b.id(byte(2)) == byte(2) and s.id(short(3)) == short(3))
```

## Generic Babble interface bridge unboxing for long and float

```babble
def Box of T = interface ( def id: fn (x: T): T )
def LB = class () implements Box of long ( def id = fn (x: long): long -> ( x ) )
def FB = class () implements Box of float ( def id = fn (x: float): float -> ( x ) )
def l: Box of long = LB() ; def f: Box of float = FB()
assert(l.id(4L) == 4L and f.id(1.5f) == 1.5f)
```

## Generic Babble interface bridge unboxing for bool and char

```babble
def Box of T = interface ( def id: fn (x: T): T )
def ZB = class () implements Box of bool ( def id = fn (x: bool): bool -> ( x ) )
def CB = class () implements Box of char ( def id = fn (x: char): char -> ( x ) )
def z: Box of bool = ZB() ; def c: Box of char = CB()
assert(z.id(true) and c.id('K') == 'K')
```

## Babble interface bridge primitive adaptation across int, double, and boxed unions

```babble
def W = interface ( def a: fn (x: int): any ; def b: fn (x: int): double? )
def C = class () implements W (
    def a = fn (x: int | double): int -> ( if x is int then ( x ) else ( int(x) ) )
    def b = fn (x: int | double): double -> ( if x is int then ( double(x) ) else ( x ) )
)
def w: W = C()
assert(w.a(2) == 2 and w.b(3) == 3.0)
```

## Optional union bridge null-guard adaptation into primitive double and default float

```babble
def OptU = interface ( def f: fn (x: int | double = ...): double )
def C = class () implements OptU (
    def f = fn (x: double = 10.0, fl: float = 1.5f): double -> ( x + double(fl) )
)
def u: OptU = C()
assert(u.f() == 11.5 and u.f(3) == 4.5)
```

## Optional union bridge null-guard adaptation and non-optional boxed widening into nullable double

```babble
def OptN = interface ( def g: fn (x: int | double = ...): bool ; def h: fn (x: int): bool )
def C = class () implements OptN (
    def g = fn (x: double? = 10.0): bool -> ( x != null and x == 10.0 )
    def h = fn (x: double?): bool -> ( x != null and x == 5.0 )
)
def u: OptN = C()
assert(u.g() and u.g(10) and u.h(5))
```

## Java bridge primitive and reference arguments widened into nullable numeric Babble params

```babble
def PInt = class () implements java.util.function.IntPredicate ( def test = fn (x: long?): bool -> ( x == 7L ) )
def PRef = class () implements java.util.function.Predicate of int ( def test = fn (x: long?): bool -> ( x == 7L ) )
def a: java.util.function.IntPredicate = PInt() ; def b: java.util.function.Predicate of int = PRef()
assert(a.test(7) and not a.test(2) and b.test(7))
```

## Array for-loops over byte, bool, short, long, and float arrays

```babble
def s = 0
for b in (array of byte).of(byte(1), byte(2)) then ( s += int(b) )
for z in (array of bool).of(true, false, true) then ( if z then ( s += 10 ) )
for sh in (array of short).of(short(3), short(4)) then ( s += int(sh) )
for l in (array of long).of(5L, 6L) then ( s += int(l) )
for f in (array of float).of(1.0f, 2.0f) then ( s += int(f) )
assert(s == 44)
```

## Capturing bool, byte, short, and char for-loop variables in closures

```babble
def s = 0
for z in (array of bool).of(true) then ( def g = fn (): int -> ( if z then (1) else (0) ); s += g() )
for b in (array of byte).of(byte(2)) then ( def g = fn (): int -> ( int(b) ); s += g() )
for h in (array of short).of(short(3)) then ( def g = fn (): int -> ( int(h) ); s += g() )
for c in (array of char).of('A') then ( def g = fn (): int -> ( int(c) ); s += g() )
assert(s == 71)
```

## Capturing long, float, and double for-loop variables in closures

```babble
def fs = (list of (fn (): int))()
for l in (array of long).of(4L) then ( fs.add(fn (): int -> ( int(l) )) )
for f in (array of float).of(5.0f) then ( fs.add(fn (): int -> ( int(f) )) )
for d in (array of double).of(6.0) then ( fs.add(fn (): int -> ( int(d) )) )
assert(fs.get(0)() + fs.get(1)() + fs.get(2)() == 15)
```

## Primitive array(from:) for byte, short, float, bool, and char

```babble
def ab = (array of byte)(from: list.of(byte(1), byte(2)))
def as_ = (array of short)(from: list.of(short(3), short(4)))
def af = (array of float)(from: list.of(1.5f, 2.5f))
def az = (array of bool)(from: list.of(true, false))
def ac = (array of char)(from: list.of('x', 'y'))
assert(ab.get(1) == byte(2) and as_.get(0) == short(3) and af.get(1) == 2.5f and az.get(0) and ac.get(1) == 'y')
```
