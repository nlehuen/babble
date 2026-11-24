# Method, super, this, recurse, interface default/bridge, and operator-method bytecode lowering

References : BIP001, BIP002, BIP021 ; targets CodeGenerator, InterfaceGen, HirBuilder, HirEmitter

```babble preamble
def Vec = class (def n : int) (
    def neg = fn () : Vec -> ( Vec(-this.n) )
    def plus = fn (o : any) : Vec -> ( Vec(this.n + (o as int)) )
    def minus = fn (o : any) : Vec -> ( Vec(this.n - (o as int)) )
    def times = fn (o : any) : Vec -> ( Vec(this.n * (o as int)) )
    def div = fn (o : any) : Vec -> ( Vec(int(this.n / (o as int))) )
)
```

## this.method direct spec, spread with missing optional arg, and named arg

```babble
def C = class () (
    def specM = fn (a : int) : int -> ( a + 1 )
    def optM = fn (a : int, b : int = 10) : int -> ( a + b )
    def run = fn () -> (
        assert(this.specM(2) == 3 and this.optM(2) == 12 and this.optM(a: 3) == 13)
    )
)
C().run()
```

## this.field assign, compound assign, and spread-to-double field assign

```babble
def C = class (def d : double, def label : string, def n : int) (
    def optM = fn (a : int, b : int = 10) : int -> ( a + b )
    def run = fn mut () -> (
        this.label = "abc" ; this.label += "!" ; this.n += 5
        this.d = this.optM(1)
        assert(this.d == 11.0 and this.label == "abc!" and this.n == 15)
    )
)
C(0.0, "init", 10).run()
```

## recurse with omitted optional arg inside class method and top-level function, plus enclosing captured cell

```babble
def enclosing = 10
def C = class () (
    def recOpt = fn (n : int, step : int = 1) : int -> ( if n <= 0 then ( 0 ) else ( step + recurse(n - 1) ) )
    def bump = fn io () : int -> ( enclosing += 1 ; this.recOpt(3) )
)
def sumDown = fn (n : int, step : int = 2) : int -> ( if n <= 0 then ( 0 ) else ( step + recurse(n - 1) ) )
assert(C().bump() == 3 and enclosing == 11 and sumDown(3) == 6)
```

## Captured outer and class-constructor double cells inside enclosing function (DoubleCell.of)

```babble
def make = fn (start : double) -> (
    def outer = start
    def C = class (p : double) (
        def bump = fn io mut () : double -> ( outer += 1.0 ; p += 0.5 ; outer + p )
    )
    C(2.0)
)
def inst = make(10.0)
assert(inst.bump() == 13.5)
assert(inst.bump() == 15.0)
```

## Static block this assign, compound assign, and this reference

```babble
def C = class () (
    def getV = fn () : int -> ( 7 )
) static (
    def x = 1
    def bump = fn io () : int -> ( this.x = 2 ; this.x += 3 ; this.x )
    def me = fn () -> ( this )
)
assert(C().getV() == 7 and C.bump() == 5 and C.me() == C)
```

## Paren-wrapped class expression with method recurse and interface default

```babble
def Box = ( class (def v : int) (
    def fact = fn (n : int) : int -> ( if n <= 1 then ( 1 ) else ( n * recurse(n - 1) ) )
) )
def IWithConst = interface ( def get = fn () : int -> ( 3 ) )
def Impl = class () implements IWithConst ()
assert(Box(5).fact(4) == 24)
assert(Impl().get() == 3)
```

## 2-level interface inheritance super(G2) and ancestor is/as checks

```babble
def G0 = interface ( def deep = fn () : string -> ( "g0" ) )
def G1 = interface extends G0 ()
def G2 = interface extends G1 ()
def GG = class () implements G2 ( def deep = fn () : string -> ( super(G2).deep() + ":gg" ) )
def dyn = fn (v : any) : any -> ( v )
def gVal : G0 = dyn(GG()) as G0
assert(gVal.deep() == "g0:gg" and (GG() is G0) and (GG() is G1))
```

## Sub-interface extending Java IntPredicate with default method and is check

```babble
def SubP = interface extends java.util.function.IntPredicate (
    def test = fn (x : int) : bool -> ( x > 0 )
)
def sp = (class () implements SubP ())()
assert(sp is java.util.function.IntPredicate and sp.test(3))
```

## InterfaceGen default method calling own default with int->double widening

```babble
def CalcIface = interface (
    def base = fn () : int -> ( 10 )
    def plusBase = fn (k : int) : double -> ( def mul = 2 ; this.base() * mul + k )
)
def ci : CalcIface = (class () implements CalcIface ())()
assert(ci.plusBase(5) == 25.0)
```

## InterfaceGen signature adaptation bridge inherited through intermediate interface

```babble
def IBase = interface ( def calc = fn (x : int) : any -> ( x * 0 ) )
def IMid = interface extends IBase ( def calc = fn (x : any) : int -> ( 5 ) )
def ILeaf = interface extends IMid ()
def C1 = class () implements ILeaf ()
def c1 : IBase = C1()
assert(c1.calc(4) == 5)
assert(C1().calc(4) == 5)
```

## super call inside Babble interface default method, inside nested lambda in class, and with positional assig...

```babble
def I1 = interface ( def calc = fn (x : int) : int -> ( x + 10 ) )
def I2 = interface extends I1 ( def calc = fn (x : int) : int -> ( super.calc(x) + 2 ) )
def C2 = class () implements I2 (
    def run = fn (x : int) : int -> (
        def inner = fn () : int -> ( super.calc(x) + 1 )
        inner() + super.calc(x = x + 1)
    )
)
assert(C2().run(5) == 36)
```

## Safe-navigation call on nullable Babble receiver with super default call through intermediate interface

```babble
def I1 = interface ( def greet = fn (x : int) : int -> ( x + 10 ) )
def I2 = interface extends I1 ()
def C = class (def v : int) implements I2 ( def greet = fn (x : int) : int -> ( super.greet(x) + this.v ) )
def opt : C? = C(5) ; def nil : C? = null
assert(opt?.greet(2) == 17 and nil?.greet(2) == null)
```

## Safe-navigation call on nullable Java receiver

```babble
def s1 : java.lang.StringBuilder? = java.lang.StringBuilder("abc")
def s2 : java.lang.StringBuilder? = null
assert(s1?.length() == 3 and s2?.length() == null)
```

## emitReturnToJava double->void POP2 on Runnable and Object->double on DoubleSupplier

```babble
def dyn = fn (v : any) : any -> ( v )
def DS = class () implements java.util.function.DoubleSupplier (
    def getAsDouble = fn () : any -> ( dyn(3.5) )
)
def f2 : java.util.function.DoubleSupplier = DS()
def r : java.lang.Runnable = fn () -> ( 1.5 + 2.5 )
r.run()
assert(f2.getAsDouble() == 3.5)
```

## Java primitive bridge args : DoublePredicate and IntPredicate

```babble
def DP = class () implements java.util.function.DoublePredicate ( def test = fn (d : double) : bool -> ( d > 0.0 ) )
def IP = class () implements java.util.function.IntPredicate ( def test = fn (i : int) : bool -> ( i > 0 ) )
def dp : java.util.function.DoublePredicate = DP()
def ip : java.util.function.IntPredicate = IP()
assert(dp.test(1.5) and ip.test(3))
```

## Java primitive bridge args : IntToDoubleFunction

```babble
def I2D = class () implements java.util.function.IntToDoubleFunction (
    def applyAsDouble = fn (i : int) : double -> ( i + 0.5 )
)
def i2d : java.util.function.IntToDoubleFunction = I2D()
assert(i2d.applyAsDouble(2) == 2.5)
```

## BabbleInstance operator methods (neg, plus, minus, times, div, compound +=) and folded unary minus

```babble
def v = Vec(10) ; def c = Vec(5) ; c += 3
assert((-v).n == -10 and -(1 + 1) == -2 and (v + 2).n == 12 and (v - 3).n == 7)
assert((v * 4).n == 40 and (v / 2).n == 5 and c.n == 8)
```

## super call to interface default with optional primitive param (class method, sub-interface, nested lambda)

```babble
def IOpt = interface ( def add = fn (a: int, b: int = 10): int -> ( a + b ) )
def ISub = interface extends IOpt ( def add = fn (a: int, b: int = 20): int -> ( super.add(a) + b ) )
def C = class () implements ISub (
    def dir = fn (x: int): int -> ( super.add(x) )
    def lam = fn (x: int): int -> ( (fn (): int -> ( super.add(x) ))() )
)
assert(C().dir(5) == 35 and C().lam(2) == 32)
```

## super call to overloaded interface default with union-typed positional and named arguments

```babble
def IOv = interface (
    def calc = overload ( fn (x: int): int -> ( x + 10 ), fn (x: string): int -> ( x.length() ) )
)
def C = class () implements IOv (
    def pos = fn (u: int | string): int -> ( super.calc(u) )
    def nam = fn (u: int | string): int -> ( super.calc(x: u) )
)
assert(C().pos(5) == 15 and C().pos("abcd") == 4 and C().nam(3) == 13 and C().nam("xy") == 2)
```

## Non-tail recurse in reference-only function and class method

```babble
def rep = fn (s: string): string -> ( if s.length() >= 4 then ( s ) else ( "a" + recurse(s + "b") ) )
def Joiner = class (def p: string) (
    def build = fn (s: string): string -> ( if s.length() >= 3 then ( s ) else ( this.p + recurse(s + "x") ) )
)
assert(rep("x") == "aaaxbbb" and Joiner("!").build("a") == "!!axx")
```

## Non-tail recurse with >8 primitive parameters

```babble
def r9 = fn (n:int, a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int): int -> (
    if n <= 0 then ( 0 ) else ( 1 + recurse(n - 1, a, b, c, d, e, f, g, h) )
)
assert(r9(3, 0, 0, 0, 0, 0, 0, 0, 0) == 3)
```

## Direct this.method call with >8 default params (0 args, positional, and reordered named args)

```babble
def Big = class () (
    def s9 = fn (a:int=1, b:int=1, c:int=1, d:int=1, e:int=1, f:int=1, g:int=1, h:int=1, i:int=1) : int -> (
        a + b + c + d + e + f + g + h + i
    )
    def run = fn () : int -> ( this.s9() + this.s9(2, 2, 2, 2, 2, 2, 2, 2, 2) + this.s9(i: 10) )
)
assert(Big().run() == 45)
```

## this.field callable, inherited interface default via this, and overloaded this operator

```babble
def IG = interface ( def greet = fn (): int -> ( 4 ) )
def C = class (def n: int, def cb: fn (int): int) implements IG (
    def plus = overload ( fn (k: int): int -> ( this.n + k ), fn (s: string): int -> ( s.length() ) )
    def run = fn (): int -> ( this.cb(3) + this.greet() + (this + 2) )
)
assert(C(5, fn (x: int): int -> ( x * 2 )).run() == 17)
```

## Object literal this.method dispatch

```babble
def obj = object ( def m = fn (): int -> ( 7 ) ; def go = fn (): int -> ( this.m() ) )
assert(obj.go() == 7)
```

## Record fast-class disqualifiers : >8 fields, captured outer cell, and Java SAM param

```babble
def R9 = record (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) ()
def k = 3 ; def CellRec = record (x: int) ( def get = fn (): int -> ( this.x + k ) )
def SamRec = record (r: java.lang.Runnable) ()
def hits = 0 ; def sr = SamRec(fn io () -> ( hits += 1 )) ; sr.r.run()
assert(R9(1, 2, 3, 4, 5, 6, 7, 8, 9).i == 9 and CellRec(4).get() == 7 and hits == 1)
```

## Record fast-class disqualifiers : body assert, body field, and nested overload

```babble
def ChkR = record (x: int) ( assert(this.x >= 0) )
def FldR = record (x: int) ( def dbl: int = this.x * 2 )
def OvR = record (x: int) (
    def f = overload ( fn (n: int): int -> ( this.x + n ), overload ( fn (b: bool): int -> ( 1 ), fn (s: string): int -> ( s.length() ) ) )
)
assert(ChkR(5).x == 5 and FldR(3).dbl == 6 and OvR(4).f(2) == 6 and OvR(4).f("hi") == 2)
```

## Class constructor non-method and non-FunctionRef overload sets (isAllMethodRefsOf)

```babble
def extI = fn (x: int): int -> ( x + 1 )
def extS = fn (s: string): int -> ( s.length() )
def C = class () (
    def r1 : int = ( def loc = overload ( extI, extS ) ; loc(3) + loc("abc") )
    def r2 : int = ( def loc = overload ( fn (x : int) : int -> ( x * 2 ), fn (s : string) : int -> ( s.length() ) ) ; loc(5) + loc("hi") )
)
def c = C()
assert(c.r1 == 7 and c.r2 == 12)
```

## Diamond Java interfaces (Closeable + AutoCloseable) and non-top-level block class expressions

```babble
def Res = class () implements java.io.Closeable, java.lang.AutoCloseable ( def close = fn () -> () )
def r = Res() ; r.close()
def C1 = ( def tag = 1 ; class (def v : int) ( def get = fn () : int -> ( this.v + tag ) ) )
def r2 = ( def C2 = class (def v : int) () ; C2(9).v )
assert(C1(4).get() == 5 and r2 == 9)
```
