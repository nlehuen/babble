# Algebraic effect properties, inference, signature validation, record/static-block purity, and closure scoping

References : BIP007, BIP016, BIP017, EffectChecker

```babble
;; babble preamble
def Box = class (var v : int) ()
def Val = record (x : int) (
    def get = fn () : int -> ( this.x )
)
def addInt = fn (a : int, b : int) : int -> ( a + b )
```

## Error : static block inside pure function violates pure guarantee

```babble
def makeBadStatic = fn () -> (
    def C = class () () static ( println("bad") )
    ()
)
```

```error
static block inside pure function violates pure guarantee
```

## Error : static block inside hermetic function violates hermetic guarantee

```babble
def C = class (var v : int) (
    def makeBadStatic = fn volatile mut () -> (
        def I = interface () static ( println("bad") )
        this.v += 1
        ()
    )
)
```

```error
static block inside hermetic function violates hermetic guarantee
```

## Error : static block inside deterministic function violates deterministic gua...

```babble
axiom java.lang.Math.random: fn volatile
def makeBadStatic = fn () -> (
    def C = class () () static ( def y = java.lang.Math.random() )
    ()
)
```

```error
static block inside deterministic function violates deterministic guarantee
```

## Error : associative requires a pure binary (T, T) : T signature

```babble
def f = fn (g : fn associative (x : int) : int) : int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Error : associative requires a pure binary (T, T) : T signature (2)

```babble
def f = fn (g : fn associative (mut a : Box, b : Box) : Box) : int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Error : commutative without mut requires a pure binary (T, T) : R signature

```babble
def f = fn (g : fn commutative (a : int) : int) : int -> ( 1 )
```

```error
commutative without mut requires a pure binary (T, T) : R signature
```

## Error : mut commutative requires a value parameter on the mutated target

```babble
def f = fn (g : fn mut commutative () : int) : int -> ( 1 )
```

```error
mut commutative requires a value parameter on the mutated target
```

## Error : idempotent without mut requires a pure unary (T) : T signature or an ...

```babble
def f = fn (g : fn idempotent (a : int, b : int) : int) : int -> ( 1 )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## Error : monotone requires ordered parameter and return types without mut

```babble
def f = fn (g : fn monotone () : int) : int -> ( 1 )
```

```error
monotone requires ordered parameter and return types without mut
```

## Error : monotone requires ordered parameter and return types without mut (2)

```babble
def f = fn (g : fn monotone (b : Box) : Box) : int -> ( 1 )
```

```error
monotone requires ordered parameter and return types without mut
```

## Error : injective requires at least one parameter, no mut, and a non-null ret...

```babble
def f = fn (g : fn injective (x : int) : null) : int -> ( 1 )
```

```error
injective requires at least one parameter, no mut, and a non-null return type
```

## Error : Function badAssoc declared associative violates associative guarantee

```babble
def badAssoc = fn associative (x : int, y : double) : int -> ( x )
```

```error
Function badAssoc declared associative violates associative guarantee
```

## Error : Function badComm declared commutative violates commutative guarantee

```babble
def badComm = fn commutative (x : int, y : double) : int -> ( x )
```

```error
Function badComm declared commutative violates commutative guarantee
```

## Error : Function bad declared commutative violates commutative guarantee

```babble
def C = class (var v : int) (
    def bad = fn mut commutative () : int -> ( this.v )
)
```

```error
Function bad declared commutative violates commutative guarantee
```

## Error : Function badIdemp declared idempotent violates idempotent guarantee

```babble
def badIdemp = fn idempotent (x : int, y : int) : int -> ( x )
```

```error
Function badIdemp declared idempotent violates idempotent guarantee
```

## Error : Function badMono declared monotone violates monotone guarantee

```babble
def badMono = fn monotone (x : Box) : Box -> ( x )
```

```error
Function badMono declared monotone violates monotone guarantee
```

## Error : Function badInj declared injective violates injective guarantee

```babble
def badInj = fn injective (x : int) : null -> ( null )
```

```error
Function badInj declared injective violates injective guarantee
```

## Case 18

```babble
def addRev = fn (a : int, b : int) : int -> ( b + a )
def mulD = fn (a : double, b : double) : double -> ( a * b )
def c1 : fn commutative associative (a : int, b : int) : int = addRev
def c2 : fn commutative (a : double, b : double) : double = mulD
assert(c1(2, 3) == 5)
assert(c2(2.0, 3.0) == 6.0)
```

## Case 19

```babble
def wrapAddRev = fn (a : int, b : int) : int -> ( addInt(b, a) )
def c3 : fn commutative (a : int, b : int) : int = wrapAddRev
assert(c3(2, 3) == 5)
```

## Case 20

```babble
def catS = fn (a : string, b : string) : string -> ( a + b )
def catRev = fn (a : string, b : string) : string -> ( b + a )
def c1 : fn associative (a : string, b : string) : string = catS
assert(c1("a", "b") == "ab")
assert(catRev("a", "b") == "ba")
```

## Case 21

```babble
def eqD = fn (a : double, b : double) : bool -> ( a == b )
def neqI = fn (a : int, b : int) : bool -> ( a <> b )
def c2 : fn commutative (a : double, b : double) : bool = eqD
def c3 : fn commutative (a : int, b : int) : bool = neqI
assert(c2(1.0, 1.0))
assert(c3(3, 4))
```

## Case 22

```babble
def dAdd = fn commutative (a : double, b : double) : double -> ( a + b )
def eqI = fn commutative (a : int, b : int) : bool -> ( a == b )
def sJoin = fn associative (a : string, b : string) : string -> ( a + b )
assert(dAdd(1.0, 2.0) == 3.0)
assert(eqI(3, 3))
assert(sJoin("a", "b") == "ab")
```

## Case 23

```babble
def d1 = fn (x : double) : double -> ( return -(-x) + 1.0 - (-2.0 * x) )
def d2 = fn (x : double, y : double) : double -> ( x + (y * 0.0 + 1.0) )
def m1 : fn monotone (x : double) : double = d1
def m2 : fn monotone (x : double, y : double) : double = d2
assert(m1(2.0) == 7.0)
assert(m2(1.0, 99.0) == 2.0)
```

## Case 24

```babble
def d3 = fn (x : int) : double -> ( (x as double) * 2.0 )
def d4 = fn (x : double) : double -> ( 3.0 * x )
def m3 : fn monotone (x : int) : double = d3
def m4 : fn monotone (x : double) : double = d4
assert(m3(4) == 8.0)
assert(m4(2.0) == 6.0)
```

## Case 25

```babble
def dMono1 = fn monotone (x : double) : double -> ( -(-x) + 1.0 - 2.0 )
def dMono2 = fn monotone (x : double) : double -> ( -(10.0 - x) )
def addSame = fn monotone (x : double) : double -> ( x + x )
assert(dMono1(3.0) == 2.0)
assert(dMono2(4.0) == -6.0)
assert(addSame(5.0) == 10.0)
```

## Case 26

```babble
def dMulPos = fn monotone (x : double) : double -> ( 2.0 * x )
def dMulNeg = fn (x : double) : double -> ( -2.0 * x )
def dMulNegR = fn (x : double) : double -> ( x * -3.0 )
assert(dMulPos(2.0) == 4.0)
assert(dMulNeg(2.0) == -4.0)
assert(dMulNegR(2.0) == -6.0)
```

## Case 27

```babble
def i1 = fn (x : int) : int -> ( -x * -3 + 5 )
def i2 = fn (x : int) : int -> ( 7 - (3 * x) )
def j1 : fn injective (x : int) : int = i1
def j2 : fn injective (x : int) : int = i2
assert(j1(2) == 11)
assert(j2(2) == 1)
```

## Case 28

```babble
def iNeg = fn injective (x : int) : int -> ( -x )
def iAdd = fn injective (x : int) : int -> ( 1 + (x - 2) )
def iSub = fn injective (x : int) : int -> ( 10 - x )
def iMulOdd = fn injective (x : int) : int -> ( 3 * (x * 5) )
assert(iNeg(5) == -5)
assert(iAdd(3) == 2)
assert(iSub(4) == 6)
assert(iMulOdd(2) == 30)
```

## Case 29

```babble
def i3 = fn (x : int) : string -> ( "k:" + x )
def i4 = fn (x : int) : string -> ( "" + (x + 1) + ":k" )
def vc = fn (x : int) : Val -> ( Val(x) )
def j3 : fn injective (x : int) : string = i3
def j4 : fn injective (x : int) : string = i4
def j5 : fn injective (x : int) : Val = vc
assert(j3(7) == "k:7")
assert(j4(2) == "3:k")
assert(j5(9).get() == 9)
```

## Case 30

```babble
def sLeft = fn injective (x : int) : string -> ( "k=" + x )
def sRight = fn injective (s : string) : string -> ( s + "!" )
def widen = fn monotone injective (x : int) : double -> ( x as double )
assert(sLeft(3) == "k=3")
assert(sRight("ok") == "ok!")
assert(widen(4) == 4.0)
```

## Case 31

```babble
def cInt = fn (x : int) : int -> ( 42 )
def cNeg = fn (x : int) : double -> ( -(42.0) )
def sSelf = fn (s : string) : string -> ( s + s )
def idemSelf = fn idempotent (x : int) : int -> ( x )
def wrapIdem = fn (x : int) : int -> ( idemSelf(x) )
def idCheck : fn idempotent (x : int) : int = wrapIdem
assert(cInt(1) == 42)
assert(cNeg(1) == -42.0)
assert(sSelf("a") == "aa")
assert(idCheck(7) == 7)
```

## Case 32

```babble
def M = class (var v : int) (
    def setV = fn mut idempotent (x : int) -> ( this.v = x )
    def fwdSet = fn mut idempotent (x : int) -> ( this.setV(x) )
)
def m = M(0)
def ms1 : fn mut idempotent (x : int) = m.setV
def ms2 : fn mut idempotent (x : int) = m.fwdSet
ms1(5)
assert(m.v == 5)
ms2(9)
assert(m.v == 9)
```

## Case 33

```babble
def setParam = fn idempotent (mut b : Box, x : int) -> ( b.v = x )
def ms : fn idempotent (mut b : Box, x : int) = setParam
def b = Box(0)
ms(b, 4)
assert(b.v == 4)
```

## Case 34

```babble
def M = class (var v : int) (
    def addAssign = fn mut commutative (d : int) -> ( this.v += d )
    def fwdAdd = fn mut commutative (d : int) -> ( this.addAssign(d) )
)
def m = M(0)
def ms1 : fn mut commutative (d : int) = m.addAssign
def ms2 : fn mut commutative (d : int) = m.fwdAdd
ms1(1)
ms2(4)
assert(m.v == 5)
```

## Case 35

```babble
def M = class (var v : int) (
    def addLeft = fn mut commutative (d : int) -> ( this.v = this.v + d )
    def addRight = fn mut commutative (d : int) -> ( this.v = d + this.v )
)
def m = M(0)
def ms1 : fn mut commutative (d : int) = m.addLeft
def ms2 : fn mut commutative (d : int) = m.addRight
ms1(2)
ms2(3)
assert(m.v == 5)
```

## Error : value class constructor parameter cannot be mut : x

```babble
def BadV1 = record (mut x : int) ()
```

```error
value class constructor parameter cannot be mut : x
```

## Error : value class BadV2 constructor must be pure

```babble
def BadV2 = record (x : int) (
    init ( println(x) )
)
```

```error
value class BadV2 constructor must be pure
```

## Error : value class BadV3 constructor must be deterministic

```babble
var g = 1
g = 2
def BadV3 = record (x : int) (
    def y = g
)
```

```error
value class BadV3 constructor must be deterministic
```

## Case 39

```babble
def Custom = class (def x : int) (
    def toString = fn () : string -> ( "C" )
)
def pStr = fn (x : int) : string -> (
    var s = "v=" + Val(x) + ":" + Custom(x) + ":" + (if x > 0 then ( x ) else ( "z" ))
    s += Val(x)
    s
)
def arr = java.lang.String("ab").toCharArray()
assert(pStr(1) != "")
assert(("arr=" + arr) != "")
```

## Case 40

```babble
def pCmp = fn (x : int, ia : java.lang.Integer, ib : java.lang.Integer) : bool -> (
    (Val(x) == Val(x)) and (Box(x) != Box(x)) and ("abc" < "def") and (ia < ib)
)
assert(pCmp(1, java.lang.Integer.valueOf(1), java.lang.Integer.valueOf(2)))
```

## Case 41

```babble
def f = fn (x : int) : int -> ( x as int )
assert(f(5) == 5)
```

```stderr
Warning : Redundant cast : int
```

## Error : Function badObj violates pure guarantee (performs I/O or external mut...

```babble
var outer = 0
def badObj = fn () -> (
    def o = object (
        init io ( outer = 1 )
    )
    ()
)
```

```error
Function badObj violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Case 43

```babble
def applyZero = fn calling f (f : fn () : int) : int -> ( f() )
def pureWithFrame = fn (n : int) : int -> (
    var acc = n
    def step = fn (k : int) : int -> ( acc += k ; acc )
    def r1 = step(2)
    applyZero(fn () : int -> ( acc += 3 ; acc ))
)
assert(pureWithFrame(10) == 15)
```

## Case 44

```babble
def specCaller = fn (n : int) : int -> (
    def invoke = fn calling cb (cb : fn (x : int) : int, v : int) : int -> ( cb(v) )
    invoke(fn (x : int) : int -> ( x + 1 ), n)
)
assert(specCaller(5) == 6)
```

## Returned closure capturing this aliases receiver (BIP017-D6)

```babble
def C = class (var v : int) (
    def okMut = fn mut shared () : fn mut () : int -> (
        def inner = fn mut () -> ( this.v += 1 )
        inner
    )
    def reader = fn () : fn () : int -> ( fn () -> ( this.v ) )
)
def c = C(10)
def bump = c.okMut()
assert(bump() == 11)
assert(c.reader()() == 11)
```

## Storing a returned closure that aliases borrowed parameter b escapes b (BIP017-D6)

```babble
def C = class (var v : int) (
    def reader = fn () : fn () : int -> ( fn () -> ( this.v ) )
)
def Holder = class () ( def ref : any? = null )
def stash = fn (mut h : Holder, b : C) -> ( h.ref = b.reader() )
```

```error
Borrowed parameter b escapes via field .ref : mark parameter as shared b
```

## Error : Cannot mutate this (field assignment .v) : function is not declared mut

```babble
def C = class (var v : int) (
    def badNoMut = fn () : null -> (
        this.v = 1
        null
    )
)
```

```error
Cannot mutate this (field assignment .v) : function is not declared mut
```

## Case 48

```babble
def Node = class (def next : Node?) ()
def walk = fn (start : Node?) : int -> (
    var cur : Node? = start
    var c = 0
    while cur != null then (
        c += 1
        cur = cur.next
    )
    c
)
assert(walk(Node(Node(null))) == 2)
```
