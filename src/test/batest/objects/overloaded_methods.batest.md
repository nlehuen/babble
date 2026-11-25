# Overloaded methods, interface members, Java interface bridges, and operator methods.

References : BIP021-I6 (BIP021-D16..D20), BIP002 §9.5, OverloadSet, CallSolver, HirEmitter

```babble
;; babble preamble
def App = type java.lang.Appendable
def CS = type java.lang.CharSequence?
def Log = class () implements java.lang.Appendable (
    private def b = java.lang.StringBuilder()
    def append = overload (
        fn io mut shared (c: char): App -> ( this.b.append(c) ; this ),
        fn io mut shared (shared s: CS): App -> ( this.b.append("" + s) ; this ),
        fn io mut shared (shared s: CS, i: int, j: int): App -> ( this.b.append("" + s, i, j) ; this )
    )
    def text = fn (): string -> ( this.b.toString() )
)
def Printer = interface (
    def tag: fn (): string
    def emit = overload ( fn (n: int): string -> ( this.tag() + "#" + n ), fn (s: string): string -> ( this.tag() + ":" + s ) )
)
def IA = interface ()
def IB = interface ()
```

## Case 1

```babble
def Calc = class (def base: int) (
    def add = overload (
        fn (n: int): int -> ( this.base + n ),
        fn (a: int, b: int): int -> ( this.add(a) + b ),
        fn (d: double): double -> ( this.base + d ),
        fn (s: string): string -> ( s + ":" + this.base )
    )
)
def c = Calc(10)
assert(Calc(0).add(5) == 5 and c.add(2, 3) == 15 and c.add(2.5) == 12.5 and c.add("v") == "v:10")
```

## Case 2

```babble
def Calc = class (def base: int) () static (
    def create = overload ( fn (): Calc -> ( Calc(0) ), fn (n: int): Calc -> ( Calc(n) ) )
)
assert(Calc.create().base == 0 and Calc.create(10).base == 10)
```

## Case 3

```babble
def Pt = record (x: double, y: double) (
    def scale = overload (
        fn (k: double): Pt -> ( Pt(this.x * k, this.y * k) ),
        fn (kx: double, ky: double): Pt -> ( Pt(this.x * kx, this.y * ky) )
    )
)
def p = Pt(2.0, 3.0)
assert(p == Pt(2.0, 3.0) and "" + p == "Pt(x: 2.0, y: 3.0)" and p.scale(2) == Pt(4.0, 6.0) and p.scale(2.0, 4.0) == Pt(4.0, 12.0))
```

## Error : Cannot assign method : m

```babble
def Box = class () ( def m = overload ( fn (x: int): int -> ( x ), fn (s: string): string -> ( s ) ) )
def b = Box()
b.m = b.m
```

```error
Cannot assign method : m
```

## Case 5

```babble
def fmt = object (
    def pre = "["
    def wrap = overload (
        fn (x: int): string -> ( this.pre + "i:" + x + "]" ),
        fn (s: string): string -> ( this.pre + "s:" + s + "]" )
    )
)
def boundSet = fmt.wrap
def boundInt: fn (x: int): string = fmt.wrap
assert(fmt.wrap(7) == "[i:7]" and boundSet("ok") == "[s:ok]" and boundInt(9) == "[i:9]")
```

## Case 6

```babble
def Fmt = interface ( def f: overload (fn (x: int): string, fn (s: string): string) )
def Exact = class () implements Fmt ( def f = overload ( fn (x: int): string -> ( "ei:" + x ), fn (s: string): string -> ( "es:" + s ) ) )
def x: Fmt = Exact()
assert(x.f(42) == "ei:42" and x.f("hi") == "es:hi")
```

## Case 7

```babble
def Fmt = interface ( def f: overload (fn (x: int): string, fn (s: string): string) )
def Wider = class () implements Fmt ( def f = overload ( fn (x: int): string -> ( "wi:" + x ), fn (s: string): string -> ( "ws:" + s ), fn (d: double): string -> ( "wd:" + d ) ) )
def x: Fmt = Wider()
assert(x.f(42) == "wi:42" and x.f("hi") == "ws:hi")
```

## Case 8

```babble
def Fmt = interface ( def f: overload (fn (x: int): string, fn (s: string): string) )
def Single = class () implements Fmt ( def f = fn (x: int | string): string -> ( "u:" + x ) )
def x: Fmt = Single()
assert(x.f(42) == "u:42" and x.f("hi") == "u:hi")
```

## Case 9

```babble
def DefP = class () implements Printer ( def tag = fn (): string -> ( "D" ) )
def p: Printer = DefP()
assert(p.emit(1) == "D#1" and p.emit("a") == "D:a")
```

## Case 10

```babble
def CustP = class () implements Printer (
    def tag = fn (): string -> ( "C" )
    def emit = overload ( fn (n: int): string -> ( "[" + super.emit(n) + "]" ), fn (s: string): string -> ( "{" + super.emit(s) + "}" ) )
)
def p: Printer = CustP()
assert(p.emit(1) == "[C#1]" and p.emit("a") == "{C:a}")
```

## Case 11

```babble
def log = Log()
def app: App = log
app.append('A')
app.append("bcde", 1, 3)
app.append("!")
java.util.Formatter(app).format("(%d)", 7)
assert(log.text() == "Acd!(7)")
```

## Error : BadLog.append has no alternative for java.lang.Appendable.append(java...

```babble
def BadLog = class () implements java.lang.Appendable (
    def append = overload ( fn (c: char): App -> ( this ), fn (s: CS): App -> ( this ) )
)
BadLog()
```

```error
BadLog.append has no alternative for java.lang.Appendable.append(java.lang.CharSequence, int, int)
```

## Case 13

```babble
def Vec2 = record (x: double, y: double) (
    def times = overload ( fn (t: double): Vec2 -> ( Vec2(this.x * t, this.y * t) ), fn (v: Vec2): Vec2 -> ( Vec2(this.x * v.x, this.y * v.y) ) )
)
def a = Vec2(2.0, 3.0) ; def b = Vec2(4.0, 5.0) ; var m = Vec2(1.0, 2.0) ; m *= 3 ; m *= Vec2(2.0, 5.0)
assert(a * 2 == Vec2(4.0, 6.0) and a * b == Vec2(8.0, 15.0) and m == Vec2(6.0, 30.0))
```

## Case 14

```babble
def Vec2 = record (x: double, y: double) (
    def times = overload ( fn (t: double): Vec2 -> ( Vec2(this.x * t, this.y * t) ), fn (v: Vec2): Vec2 -> ( Vec2(this.x * v.x, this.y * v.y) ) )
)
def go = fn (v: Vec2, r: double | Vec2): Vec2 -> ( v * r )
def a = Vec2(2.0, 3.0)
assert(go(a, 3.0) == Vec2(6.0, 9.0) and go(a, Vec2(4.0, 5.0)) == Vec2(8.0, 15.0))
```

## Case 15

```babble
def Vec2 = record (x: double, y: double) (
    def neg = overload ( fn (): Vec2 -> ( Vec2(-this.x, -this.y) ) )
    def plus = overload ( fn (v: Vec2): Vec2 -> ( Vec2(this.x + v.x, this.y + v.y) ), fn (d: double): Vec2 -> ( Vec2(this.x + d, this.y + d) ) )
)
def a = Vec2(2.0, 3.0)
assert(a + 1.5 == Vec2(3.5, 4.5) and a + Vec2(1.0, 2.0) == Vec2(3.0, 5.0) and -a == Vec2(-2.0, -3.0))
```

## Error : No applicable overload of Vec2(1.0,2.0).times for (string) : candidat...

```babble
def Vec2 = record (x: double, y: double) (
    def times = overload ( fn (t: double): Vec2 -> ( Vec2(this.x * t, this.y * t) ), fn (v: Vec2): Vec2 -> ( Vec2(this.x * v.x, this.y * v.y) ) )
)
Vec2(1.0, 2.0) * "nope"
```

```error
No applicable overload of Vec2(1.0,2.0).times for (string) : candidates are (t : double), (v : Vec2)
```

## Case 17

```babble
def Num = record (v: double) (
    def minus = overload ( fn (d: double): Num -> ( Num(this.v - d) ), fn (o: Num): Num -> ( Num(this.v - o.v) ) )
)
def a = Num(10.0) ; def u: double | Num = Num(2.0) ; var m = Num(20.0) ; m -= 5 ; m -= Num(3.0)
assert(a - 3 == Num(7.0) and a - Num(2.0) == Num(8.0) and a - u == Num(8.0) and m == Num(12.0))
```

## Case 18

```babble
def Num = record (v: double) (
    def div = overload ( fn (d: double): Num -> ( Num(this.v / d) ), fn (o: Num): Num -> ( Num(this.v / o.v) ) )
)
def a = Num(10.0) ; var m = Num(12.0) ; m /= 2 ; m /= Num(3.0)
assert(a / 4 == Num(2.5) and a / Num(2.0) == Num(5.0) and m == Num(2.0))
```

## Case 19

```babble
def Num = record (v: double) (
    def plus = overload ( fn (n: int): Num -> ( Num(this.v + n) ), fn of T io (x: T): string -> ( "" + this.v + "+" + x ) )
)
def a = Num(10.0)
assert(a + 1 == Num(11.0) and a + "ok" == "10.0+ok")
```

## Case 20

```babble
def I = interface (
    def v: fn (): int
    def plus = overload ( fn (n: int): int -> ( this.v() + n ), fn (s: string): string -> ( "" + this.v() + s ) )
)
def S = interface extends I ()
def C = class (def n: int) implements S ( def v = fn (): int -> ( this.n ) )
def go = fn (x: I, r: int | string): int | string -> ( x + r )
def o: S = C(7) ; assert(o + 3 == 10 and go(o, "?") == "7?")
```

## Dynamic super.f dispatch across widening alternatives (OverloadSet.call)

```babble
def S = interface ( def f = overload ( fn (n: int): string -> ( "i:" + n ), fn (d: double): string -> ( "d:" + d ) ) )
def C = class () implements S (
    def f = overload ( fn (n: int): string -> ( "[" + super.f(n) + "]" ), fn (d: double): string -> ( "[" + super.f(d) + "]" ) )
    def run = fn (): string -> ( super.f(byte(2)) + "|" + super.f(1.5f) )
)
assert(C().run() == "i:2|d:1.5")
```

## Dynamic super.f dispatch across any? and null alternatives (OverloadSet.call)

```babble
def S = interface ( def f = overload ( fn (n: int): string -> ( "i:" + n ), fn io (x: any?): string -> ( "a:" + x ) ) )
def C = class () implements S (
    def f = overload ( fn (n: int): string -> ( "[" + super.f(n) + "]" ), fn io (x: any?): string -> ( "[" + super.f(x) + "]" ) )
    def run = fn io (): string -> ( super.f(null) + "|" + super.f(true) )
)
assert(C().run() == "a:null|a:true")
```

## Dynamic super.f dispatch forwarding a packed rest parameter via ...xs

```babble
def S = interface ( def f = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string, xs: int...): string -> ( var t = 0 ; for x in xs then ( t += x ) ; s + ":" + t ) ) )
def C = class () implements S (
    def f = overload ( fn (n: int): string -> ( "[" + super.f(n) + "]" ), fn (s: string, xs: int...): string -> ( "[" + super.f(s, ...xs) + "]" ) )
)
assert(C().f("k", 5, 6) == "[k:11]")
```

## Case 24

```babble
def CA = class () implements IA ()
def CB = class () implements IB ()
def CAB = class () implements IA, IB ()
def M = class () ( def plus = overload ( fn (x: IA): string -> ( "A" ), fn (x: IB): string -> ( "B" ), fn (x: IA & IB): string -> ( "AB" ) ) )
def go = fn (m: M, r: IA | IB): string -> ( m + r )
assert(go(M(), CA()) == "A" and go(M(), CB()) == "B" and go(M(), CAB()) == "AB")
```

## Error : Cannot dispatch b.plus on list of int | list of string : its alternat...

```babble
def Box = class () ( def plus = overload ( fn (xs: list of int): string -> ( "i" ), fn (xs: list of string): string -> ( "s" ) ) )
def run = fn (b: Box, u: list of int | list of string): string -> ( b + u )
run(Box(), list(1))
```

```error
Cannot dispatch b.plus on list of int | list of string : its alternatives are not told apart at runtime
```

## Overloaded operator splitting on a nullable operand exercises non-null check fallthrough on null

```babble
def Tag = class () (
    def plus = overload (
        fn (n: int): string -> ( "i:" + n ),
        fn (s: string?): string -> ( if s == null then ( "none" ) else ( "s:" + s ) )
    )
)
def addOpt = fn (t: Tag, v: int?): string -> ( t + v )
assert(addOpt(Tag(), 7) == "i:7" and addOpt(Tag(), null) == "none")
```
