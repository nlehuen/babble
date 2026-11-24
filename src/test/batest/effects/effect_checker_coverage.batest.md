# EffectChecker and EffectSet coverage : purity, determinism, nothrow, toString profiles,

SAM callback propagation, mut exclusivity, algebraic property inference/validation,
EffectSet.intersect/satisfies, static blocks in constrained functions, and legacy axiom syntax.
References : BIP007, BIP016, BIP019, EffectChecker, EffectSet

```babble module=ce_mod_a.ba
export def ModCustom = class (def x: int) (
    def toString = fn (): string -> ( "M:" + this.x )
)
```

```babble preamble
def Reg = class (def v: int) ()
def Box = class (def v: int) ()
```

## Error : Function badHerm violates pure guarantee (performs I/O or external mu...

```babble
def g = 1
g = 2
def badHerm = fn () -> ( g = 3 )
```

```error
Function badHerm violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Function badDet violates deterministic guarantee : mark function as v...

```babble
axiom java.lang.Math.random: fn volatile
def badDet = fn () -> ( java.lang.Math.random() )
```

```error
Function badDet violates deterministic guarantee : mark function as volatile (or io)
```

## Error : Function badNothrow2 declared nothrow violates nothrow guarantee

```babble
def badNothrow = fn io () -> ( java.lang.Integer.parseInt("1") )
def badNothrow2 = fn nothrow (s: string): int -> ( int(s) )
```

```error
Function badNothrow2 declared nothrow violates nothrow guarantee
```

## Case 4

```babble
def ma = import("ce_mod_a.ba")
def useRegOk = fn volatile (r: Reg): string -> ( "r=" + r )
def useModOk = fn (m: ma.ModCustom): string -> ( "m=" + m )
def uStr = fn (v: int | string): string -> ( "" + v )
assert(useModOk(ma.ModCustom(7)) == "m=M:7")
assert(uStr(5) == "5")
assert(uStr("ok") == "ok")
```

## Case 5

```babble
def useJavaOk = fn (p: java.awt.Point): string -> ( "p=" + p.x )
def useJavaStr = fn volatile (p: java.awt.Point): string -> ( "p=" + p )
def pt = java.awt.Point(1, 2)
assert(useJavaOk(pt) == "p=1")
assert(useJavaStr(pt) == "p=java.awt.Point[x=1,y=2]")
```

## Error : Function badRegDet violates deterministic guarantee : mark function a...

```babble
def badRegDet = fn (r: Reg): string -> ( "r=" + r )
```

```error
Function badRegDet violates deterministic guarantee : mark function as volatile (or io)
```

## Case 7

```babble
axiom java.util.Collections.singletonList: fn pure
axiom java.lang.Iterable.forEach: fn pure
axiom java.util.List.size: fn pure
def okSam = fn (): int -> (
    def item: java.lang.Object = 1
    def xs: java.util.List of java.lang.Object = java.util.Collections.singletonList(item)
    xs.forEach(fn (x) -> ( def y = x ))
    xs.size()
)
assert(okSam() == 1)
```

## Case 8

```babble
axiom java.util.Collections.singletonList: fn pure
axiom java.lang.Iterable.forEach: fn pure
def GoodCons = class () implements java.util.function.Consumer of any (
    def accept = fn (x: any) -> ( () )
)
def okSam = fn (): int -> (
    def item: any = 1
    java.util.Collections.singletonList(item).forEach(GoodCons())
    1
)
assert(okSam() == 1)
```

## Error : Function badSamLambda violates pure guarantee (performs I/O or extern...

```babble
axiom java.util.ArrayList.new: fn pure
axiom java.util.ArrayList.add: fn mut
axiom java.util.ArrayList.forEach: fn pure
def badSamLambda = fn () -> (
    def xs: java.util.ArrayList of any = java.util.ArrayList()
    xs.add(1)
    xs.forEach(fn (x) -> ( println(x) ))
)
```

```error
Function badSamLambda violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Function badSamClass violates pure guarantee (performs I/O or externa...

```babble
axiom java.util.ArrayList.new: fn pure
axiom java.util.ArrayList.add: fn mut
axiom java.util.ArrayList.forEach: fn pure
def BadCons = class () implements java.util.function.Consumer of any (
    def accept = fn io (x: any) -> ( println(x) )
)
def badSamClass = fn () -> (
    def xs: java.util.ArrayList of any = java.util.ArrayList()
    xs.add(1)
    xs.forEach(BadCons())
)
```

```error
Function badSamClass violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Exclusivity error : mut target b aliases argument b

```babble
def SwapBox = class (def v: int) (
    def swapWith = fn mut (mut other: SwapBox) -> (
        def t = this.v
        this.v = other.v
        other.v = t
    )
)
def b = SwapBox(1)
b.swapWith(b)
```

```error
Exclusivity error : mut target b aliases argument b
```

## StringBuilder implements Comparable in Java 11+, exercising the Java ordered-type branch

```babble
def monoJ = fn (f: fn monotone (s: java.lang.StringBuilder): int): int -> ( 1 )
def w1 = fn (x: double): double -> ( 1.0 + x )
def w2 = fn (x: int): string -> ( "" + x + "!" )
def w3 = fn (x: double): string -> ( "d=" + x )
def m1: fn monotone (x: double): double = w1
def j1: fn injective (x: int): string = w2
assert(m1(2.0) == 3.0)
assert(j1(4) == "4!")
assert(w3(1.5) == "d=1.5")
```

## Case 13

```babble
def addI = fn (a: int, b: int): int -> ( a + b )
def impBin = fn io (a: int, b: int): int -> ( println(a); a + b )
def w4 = fn (x: int, y: int): int -> ( addI(a: x, b: y) )
def w5 = fn io (x: int, y: int): int -> ( impBin(x, y) )
assert(w4(2, 3) == 5)
```

## Case 14

```babble
def sCat: fn associative (a: string, b: string): string = fn (a: string, b: string): string -> ( a + b )
def dAdd: fn commutative (a: double, b: double): double = fn (a: double, b: double): double -> ( a + b )
assert(sCat("a", "b") == "ab")
assert(dAdd(1.5, 2.5) == 4.0)
```

## Error : Type error : expected commutative (a : int, b : int) : int, got (a : ...

```babble
def badSub: fn commutative (a: int, b: int): int = fn (a: int, b: int): int -> ( a - b )
```

```error
Type error : expected commutative (a : int, b : int) : int, got (a : int, b : int) : int
```

## Case 16

```babble
axiom java.nio.file.Path.normalize: fn pure idempotent
def normWrap = fn (p: java.nio.file.Path): java.nio.file.Path -> ( p.normalize() )
def pIdem: fn idempotent (p: java.nio.file.Path): java.nio.file.Path = normWrap
assert(pIdem(java.nio.file.Path.of("a")).toString() == "a")
```

## Case 17

```babble
def cleanOp = fn idempotent (s: string): string -> ( s )
def cleanWrap = fn (s: string): string -> ( cleanOp(s) )
def sIdem: fn idempotent (s: string): string = cleanWrap
assert(sIdem("ok") == "ok")
```

## Case 18

```babble
def MBox = class (def v: int) (
    def addL = fn mut commutative (d: int): int -> ( this.v = this.v + d )
    def addR = fn mut commutative (d: int): int -> ( this.v = d + this.v )
)
def mb = MBox(0)
def mc1: fn commutative mut (d: int): int = mb.addL
def mc2: fn commutative mut (d: int): int = mb.addR
assert(mc1(1) == 1)
assert(mc2(2) == 3)
```

## Case 19

```babble
def MBox = class (def v: int) (
    def addPlus = fn mut commutative (d: int): int -> ( this.v += d )
)
def mb = MBox(0)
def mc3: fn commutative mut (d: int): int = mb.addPlus
assert(mc3(4) == 4)
```

## Error : Function badStaticRead violates deterministic guarantee : mark functi...

```babble
def nDet = class () () static (
    def s = Box(1)
)
def badStaticRead = fn (): int -> ( nDet.s.v )
```

```error
Function badStaticRead violates deterministic guarantee : mark function as volatile (or io)
```

## Case 21

```babble
def negF: fn monotone (x: double): double = fn (x: double): double -> ( -(-x) )
def dblSum: fn monotone (x: double): double = fn (x: double): double -> ( x + x )
def oddMul: fn injective (x: int): int = fn (x: int): int -> ( x * 3 )
def loc = 1
loc += 2
assert(negF(2.5) == 2.5)
assert(dblSum(1.5) == 3.0)
assert(oddMul(4) == 12)
assert(loc == 3)
```

## Case 22

```babble
def sLeft: fn injective (x: string): string = fn (x: string): string -> ( x + "!" )
def sRight: fn injective (x: int): string = fn (x: int): string -> ( "!" + x )
assert(sLeft("ok") == "ok!")
assert(sRight(4) == "!4")
```

## Error : associative requires a pure binary (T, T) : T signature

```babble
def badAssocDom = fn (f: fn associative (a: int, b: double): double): int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Error : associative requires a pure binary (T, T) : T signature (2)

```babble
def badAssocRet: fn associative (a: int, b: int): double = fn (a: int, b: int): double -> ( a + b )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Error : commutative without mut requires a pure binary (T, T) : R signature

```babble
def badCommDom = fn (f: fn commutative (a: int, b: double): double): int -> ( 1 )
```

```error
commutative without mut requires a pure binary (T, T) : R signature
```

## Error : commutative without mut requires a pure binary (T, T) : R signature (2)

```babble
def badCommParam: fn commutative (a: int, b: string): int = fn (a: int, b: string): int -> ( a )
```

```error
commutative without mut requires a pure binary (T, T) : R signature
```

## Error : mut commutative requires a value parameter on the mutated target

```babble
def badCommMutOne = fn (f: fn commutative (mut a: Box): null): int -> ( 1 )
```

```error
mut commutative requires a value parameter on the mutated target
```

## Error : idempotent without mut requires a pure unary (T) : T signature or an ...

```babble
def badIdemRet = fn (f: fn idempotent (a: int): string): int -> ( 1 )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## Error : idempotent without mut requires a pure unary (T) : T signature or an ... (2)

```babble
def badIdemArity: fn idempotent (a: int, b: int): int = fn (a: int, b: int): int -> ( a + b )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## bool is non-null so accepted by signature check; null return triggers the error

```babble
def injBool = fn (f: fn injective (x: int): bool): int -> ( 1 )
def badInjNull = fn (f: fn injective (x: int): null): int -> ( 1 )
```

```error
injective requires at least one parameter, no mut, and a non-null return type
```

## Error : monotone requires ordered parameter and return types without mut

```babble
def badMonoAny: fn monotone (x: any): any = fn (x: any): any -> ( x )
```

```error
monotone requires ordered parameter and return types without mut
```

## Case 32

```babble
def C = class (def v: int) (
    def mMut = fn mut (): int -> ( this.v = 1; this.v )
    def mPure = fn (): int -> ( this.v )
    def pick = fn shared (flag: bool) -> (
        if flag then ( this.mMut ) else ( this.mPure )
    )
)
def obj = C(5)
assert(obj.pick(false)() == 5)
assert(obj.pick(true)() == 1)
```

## Error : Type error : expected () : () : int, got shared () : () : int

```babble
def C = class (def v: int) (
    def make = fn shared (): fn (): int -> ( fn () -> ( this.v ) )
)
def obj = C(4)
def func: fn (): fn (): int = obj.make
```

```error
Type error : expected () : () : int, got shared () : () : int
```

## Error : static block inside hermetic function violates hermetic guarantee

```babble
def SBox = class (def v: int) (
    def fHerm = fn volatile mut () -> (
        def C = class () () static ( println("side") )
        this.v += 1
        ()
    )
)
```

```error
static block inside hermetic function violates hermetic guarantee
```

## Error : static block inside pure function violates pure guarantee

```babble
def fPure = fn () -> (
    def C = class () () static ( println("side") )
    ()
)
```

```error
static block inside pure function violates pure guarantee
```

## Error : static block inside deterministic function violates deterministic gua...

```babble
axiom java.lang.System.nanoTime: fn volatile
def fDet = fn () -> (
    def C = class () () static ( java.lang.System.nanoTime() )
    ()
)
```

```error
static block inside deterministic function violates deterministic guarantee
```

## Error : axiom declaration is not allowed inside a verified constrained function

```babble
def f = fn (): int -> (
    axiom java.lang.Math.abs: fn pure
    1
)
```

```error
axiom declaration is not allowed inside a verified constrained function
```

## Case 38

```babble
axiom java.lang.System.nanoTime: fn io
def f = fn io (): int -> ( 1 )
def t = java.lang.System.nanoTime()
assert(f() == 1)
assert(t == t)
```
