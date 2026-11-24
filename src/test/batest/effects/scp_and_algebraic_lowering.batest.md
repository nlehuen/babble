# Algebraic verification, AST lowering, and size-change termination analysis

References : `BIP027` (`AlgebraicVerifier`, `EffectAstExtractors`, `SizeChangeTermination`).

```babble module=alg_helper.ba
export def bump = fn (x: int): int -> ( x + 1 )
```

```babble preamble
def Box = class (def v: int) ()
def Pair = record (a: int, b: int) ()
def Tri = record (a: int, b: int, c: int) ()
def Wrap = record (v: int) ()
def Unit = record () ()
def Tree = record (v: int, left: Tree?, right: Tree?) (
    def getVal = fn (): int -> ( this.v )
)
def Vec = class (def v: int) (
    def div = fn (o: Vec): Vec -> ( Vec(int(this.v / o.v)) )
)
def id of T = fn (x: T): T -> ( x )
def failInt = fn (x: int) -> ( throw java.lang.RuntimeException("e" + x) )
def extInt: int = 3
def extD: double = 2.0
```

## Signature error : io associative rejected

```babble
def f = fn (g: fn io associative (a: int, b: int): int): int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Signature error : associative returning null rejected

```babble
def f = fn (g: fn associative (a: int, b: int): null): int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Signature error : associative with mismatched return type or nothing return rejected

```babble
def f = fn (g: fn associative (a: int, b: int): double): int -> ( 1 )
```

```error
associative requires a pure binary (T, T) : T signature
```

## Signature error : associative inferring nothing return rejected

```babble
def bad = fn associative (a: int, b: int) -> ( throw java.lang.RuntimeException("x") )
```

```error
Function bad declared associative violates associative guarantee
```

## Signature error : io commutative without mut rejected

```babble
def f = fn (g: fn io commutative (a: int, b: int): int): int -> ( 1 )
```

```error
commutative without mut requires a pure binary (T, T) : R signature
```

## Signature error : non-axiomatic io idempotent rejected

```babble
def f = fn (g: fn io idempotent (x: int): int): int -> ( 1 )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## Signature error : idempotent returning null or mismatched type rejected

```babble
def f = fn (g: fn idempotent (x: int): null): int -> ( 1 )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## Signature error : idempotent with mismatched return type rejected

```babble
def f = fn (g: fn idempotent (x: int): double): int -> ( 1 )
```

```error
idempotent without mut requires a pure unary (T) : T signature or an effectful axiom
```

## Signature error : mut monotone rejected

```babble
def f = fn (g: fn mut monotone (x: int): int): int -> ( 1 )
```

```error
monotone requires ordered parameter and return types without mut
```

## Signature error : monotone with non-ordered parameter rejected

```babble
def f = fn (g: fn monotone (b: Box): int): int -> ( 1 )
```

```error
monotone requires ordered parameter and return types without mut
```

## Signature error : mut injective rejected

```babble
def f = fn (g: fn mut injective (x: int): int): int -> ( 1 )
```

```error
injective requires at least one parameter, no mut, and a non-null return type
```

## Signature error : zero-param injective rejected

```babble
def f = fn (g: fn injective (): int): int -> ( 1 )
```

```error
injective requires at least one parameter, no mut, and a non-null return type
```

## Signature error : nothing-returning injective rejected

```babble
def bad = fn injective (x: int) -> ( throw java.lang.RuntimeException("x") )
```

```error
Function bad declared injective violates injective guarantee
```

## Pure axiomatic idempotent passes signature validation

```babble
axiom java.lang.Math.abs: fn idempotent (x: int): int
assert(java.lang.Math.abs(-5) == 5)
```

## Pure property domains : null, nothing return via call, and fn-type sameDomain

```babble
def n2 = fn (a: null, b: null): null -> ( null )
def n1 = fn (a: null): null -> ( null )
def wrapFail = fn (x: int) -> ( failInt(x) )
def pickFn = fn (a: fn (x: int): int, b: fn (y: int): int): fn (z: int): int -> ( a )
def idI = fn (k: int): int -> ( k )
assert(n2(null, null) == null and n1(null) == null and pickFn(idI, idI)(3) == 3)
```

## Sign.add lattice : DEC+DEC preserves monotone after negation, INC+DEC and TOP+TOP yield TOP

```babble
def decSum = fn monotone (x: double): double -> ( -((-x) + (-x)) )
def cancel = fn (x: double): double -> ( x - x )
def sqSum = fn (x: double): double -> ( (x * x) + (x * x) )
assert(decSum(2.0) == 4.0 and cancel(3.0) == 0.0 and sqSum(2.0) == 8.0)
```

## Multi-param injective Ctor : constant field and non-injective field

```babble
def triConst = fn injective (a: int, b: int): Tri -> ( Tri(1, a, a + b) )
def pairNonInj = fn (a: int, b: int): Pair -> ( Pair(a * 2, b) )
assert(triConst(2, 3).c == 5 and pairNonInj(2, 3).a == 4)
```

## Multi-param Ctor non-injective regular class, narrow record, and unit record

```babble
def Box2 = class (def a: int, def b: int) ()
def mkB = fn (a: int, b: int): Box2 -> ( Box2(a, b) )
def mkW = fn (a: int, b: int): Wrap -> ( Wrap(a + b) )
def mkU = fn (a: int): Unit -> ( Unit() )
assert(mkB(1, 2).b == 2 and mkW(1, 2).v == 3 and mkU(1) == Unit())
```

## Unary and binary integral RelSignDomain : long/float neg, div, even mul, opaque mul, and x+x

```babble
def negL = fn injective (x: long): long -> ( -x )
def negF = fn monotone (x: float): float -> ( -(-x) )
def f1 = fn (x: int): int -> ( x + x )
def f2 = fn (x: int): int -> ( int(10 / x) )
def f3 = fn (x: int): int -> ( 3 * (x * 2) )
def f4 = fn (x: int): int -> ( 2 * x + extInt * x )
assert(negL(2L) == -2L and negF(1.5f) == 1.5f and f1(3) == 6 and f2(2) == 5 and f3(2) == 12 and f4(2) == 10)
```

## Double binary RelSignDomain : opaque scale, div by zero/opaque/var, and non-finite constant

```babble
def d1 = fn (x: double): double -> ( x * x + extD * x + x * extD )
def d2 = fn (x: double): double -> ( x / extD + 2.0 / x + x / x )
def dZero = fn (x: double): double -> ( x / 0.0 )
def dInf = fn (x: double): double -> ( (1.0 / 0.0) * x )
assert(d1(2.0) == 12.0 and d2(2.0) == 3.0)
```

## Lossless toString concatenation for integral scalars (byte, short, long)

```babble
def sB = fn injective (x: byte): string -> ( "b:" + x )
def sS = fn injective (x: short): string -> ( "s:" + x )
def sL = fn injective (x: long): string -> ( "l:" + x )
assert(sB(byte(1)) == "b:1" and sS(short(2)) == "s:2" and sL(3L) == "l:3")
```

## Lossless toString concatenation for char and bool vs non-lossless double

```babble
def sC = fn injective (x: char): string -> ( "c:" + x )
def sZ = fn injective (x: bool): string -> ( "z:" + x )
def sD = fn (x: double): string -> ( "d:" + x )
assert(sC('a') == "c:a" and sZ(true) == "z:true" and sD(1.0) == "d:1.0")
```

## Numeric narrowing casts : char->short, char/int->string, double->int

```babble
def cShort = fn (c: char): short -> ( short(c) )
def cStr = fn (c: char): string -> ( string(c) )
def iStr = fn (x: int): string -> ( string(x) )
def dInt = fn (d: double): int -> ( int(d) )
assert(cShort('A') == short(65) and cStr('A') == "A" and iStr(7) == "7" and dInt(2.9) == 2)
```

## Numeric monotone widening casts : int->float, long->float, long->double

```babble
def iFlt = fn monotone (x: int): float -> ( float(x) )
def lFlt = fn monotone (x: long): float -> ( float(x) )
def lDbl = fn monotone (x: long): double -> ( double(x) )
assert(iFlt(1) == 1.0f and lFlt(2L) == 2.0f and lDbl(3L) == 3.0)
```

## Constant lowering in evalConstNumber and lowerConst : casts, negations, opaques, and div0

```babble
def m1 = fn monotone (x: double): double -> ( double(2) * x + (double(2) + 1.0) * x )
def m2 = fn (x: double): double -> ( (double(extInt) + 1.0) * x + (-extD) * x + (1.0 + extD) * x + (extD + 1.0) * x )
def m3 = fn (x: int): int -> ( int(2.5) * x + (int(2.5) + 1) * x )
def mDiv0 = fn (x: int): int -> ( int(1 / 0) * x )
assert(m1(2.0) == 10.0 and m2(1.0) == 8.0 and m3(2) == 10)
```

## Call RelSignDomain : monotone DEC/TOP and injective multi-arg partial application

```babble
def idD = fn monotone (x: double): double -> ( x + 1.0 )
def decC = fn monotone (x: double): double -> ( -idD(-x) )
def topC = fn (x: double): double -> ( idD(x * x) )
def tp = fn injective (a: int, b: int): Pair -> ( Pair(a, a + b) )
def c1 = fn (x: int): Pair -> ( tp(1, x) )
def c2 = fn (x: int): Pair -> ( tp(x, x) )
assert(decC(2.0) == 1.0 and topC(2.0) == 5.0 and c1(3).b == 4)
```

## Call extractCall : non-injective arg, TypeApply, static Java, and instance method calls

```babble
def lib = import("alg_helper.ba")
def inj1 = fn injective (x: int): int -> ( x + 1 )
def c3 = fn (x: int): int -> ( inj1(x * 2) )
def c4 = fn (x: int): int -> ( id of int (x) + java.lang.Math.abs(x) + lib.bump(x) )
def c5 = fn (s: string): int -> ( s.length() )
assert(c3(3) == 7 and c4(2) == 7 and c5("abc") == 3)
```

## Statement extraction : last-stmt assign, param reassign, and sub-expr return

```babble
def lastAssign = fn commutative (a: int, b: int): int -> ( def s = a ; s += b )
def pRe = fn (a: int, b: int): int -> ( a += b ; a )
def subRet = fn (x: int): int -> ( 1 + (return x) )
assert(lastAssign(2, 3) == 5 and pRe(2, 3) == 5 and subRet(4) == 4)
```

## Expression extraction : contextual literal widening and byte-to-short if join

```babble
def litW = fn (x: double): double -> (
    def d: double = 1 ; def f: float = 1 ; def a: any = 1
    def b: byte = 1 ; def s: short = 2
    x + d + double(f) + double(int(b) + int(s))
)
def ifShort = fn (flag: bool, s: short): short -> ( def b: byte = 1 ; if flag then ( b ) else ( s ) )
assert(litW(1.0) == 6.0 and ifShort(true, short(9)) == short(1))
```

## Extraction bailouts : Vec custom div/compound and early-return else-if

```babble
def vDiv = fn (a: Vec, b: Vec): Vec -> ( def s = a ; s /= b ; s )
def eIf = fn (x: int, b: Box): int -> ( if x < 0 then ( return 0 ) else if x > 9 then ( return 9 ) ; x )
def eUn = fn (x: int, b: Box): int -> ( if x < 0 then ( return b.v ) ; x )
assert(vDiv(Vec(6), Vec(2)).v == 3 and eIf(5, Box(1)) == 5 and eUn(5, Box(1)) == 5)
```

## Extraction bailouts : unsupported cast and field-dependent if condition

```babble
def castUn = fn (b: Box): any -> ( b.v as any )
def ifUn = fn (b: Box, x: int): int -> ( if b.v > 0 then ( x ) else ( x ) )
assert(castUn(Box(3)) == 3 and ifUn(Box(1), 4) == 4)
```

```stderr
Warning : Redundant cast : any
```

## Extraction node budget exhaustion over deep addition chain

```babble
def deep = fn (x: int): int -> ( x+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1+1 )
assert(deep(5) == 40)
```

## Mut body extraction : empty body, local assign, fresh local mutation, and Vec compound

```babble
def M = class (def v: int, def vec: Vec) (
    def noop = fn mut () -> ()
    def locA = fn mut (x: int) -> ( x = x + 1 )
    def mkB = fn mut () -> ( def b = Box(0) ; b.v = 1 ; Box(1) )
    def divV = fn mut (shared d: Vec) -> ( this.vec /= d )
)
def m = M(0, Vec(8)) ; m.noop() ; m.locA(1) ; m.mkB() ; m.divV(Vec(2))
assert(m.vec.v == 4)
```

## Mut body extraction : calling mut and volatile helpers on this

```babble
def incB = fn (mut b: Box): int -> ( b.v += 1 ; b.v )
def volR = fn volatile (): int -> ( extInt )
def M = class (def v: int) (
    def callM = fn mut (mut b: Box) -> ( this.v = incB(b) )
    def callV = fn volatile mut () -> ( this.v = volR() )
)
def m = M(0) ; m.callM(Box(3)) ; m.callV()
assert(m.v == 3)
```

## Mut body extraction : sibling field write and nested receiver mutation

```babble
def H2 = class (def a: Box) ()
def sib = fn (mut a: Box, b: Box) -> ( a.v = b.v + 1 )
def nest = fn (mut h: H2) -> ( h.a.v = 5 )
def b1 = Box(0) ; sib(b1, Box(4))
def h = H2(Box(0)) ; nest(h)
assert(b1.v == 5 and h.a.v == 5)
```

## Mut body extraction error : mut method writing unannotated param hits mutParams bailout

```babble
def C = class (def v: int) ( def bad = fn mut (b: Box) -> ( b.v = 1 ) )
```

```error
Cannot mutate parameter b (field assignment .v) : mark parameter as mut b
```

## Reassigned local function and outer mutable or reassigned vars in extractExpr and callSymbol

```babble
def r = 1 ; r = 2
def ob = Box(1)
def f = fn (x: int, g: fn (int): int): int -> (
    def h = fn (k: int): int -> ( k )
    h = g
    h(x) + g(x)
)
def readOb = fn volatile (): int -> ( r + ob.v )
assert(f(3, fn (n: int): int -> ( n + 1 )) == 8 and readOb() == 3)
```

## SCP asymmetric mutual recursion eliminates one edge leaving acyclic active graph

```babble
def evenA = fn (n: int): bool -> ( if n <= 0 then ( true ) else ( oddA(n - 1) ) )
def oddA = fn (n: int): bool -> ( if n <= 0 then ( false ) else ( evenA(n) ) )
assert(evenA(0) and not evenA(4))
```

## SCP nested self-loop and mutual link exercises multi-SCC edge filtering (decrease on outer edge)

```babble
def a1 = fn (m: int, n: int): int -> ( if m <= 0 or n <= 0 then ( 0 ) else ( b1(m - 1, n) + a1(m, n - 1) ) )
def b1 = fn (m: int, n: int): int -> ( if m <= 0 or n <= 0 then ( 1 ) else ( a1(m, n) ) )
assert(a1(2, 2) >= 0)
```

## SCP nested self-loop and mutual link exercises multi-SCC edge filtering (decrease on inner edge)

```babble
def a2 = fn (m: int, n: int): int -> ( if m <= 0 or n <= 0 then ( 0 ) else ( b2(m, n) + a2(m, n - 1) ) )
def b2 = fn (m: int, n: int): int -> ( if m <= 0 or n <= 0 then ( 1 ) else ( a2(m - 1, n) ) )
assert(a2(2, 2) >= 0)
```

## SCP mutual fan-out triggers hasInjection m>n and m==1,n>1 before Criterion 2 max-ranking

```babble
def f = fn (a: int, b: int): int -> ( if a <= 0 or b <= 0 then ( 1 ) else ( g(a - 1) + g(b - 1) ) )
def g = fn (x: int): int -> ( if x <= 0 then ( 1 ) else ( f(x, x) ) )
assert(f(2, 2) > 0)
```

## SCP 2-param swap and 3-param cyclic rotation exercise Kuhn bipartite matching

```babble
def swap1 = fn (a: int, b: int): int -> ( if a <= 0 or b <= 0 then ( 0 ) else ( 1 + swap1(b - 1, a) ) )
def rot3 = fn (a: int, b: int, c: int): int -> ( if a <= 0 or b <= 0 or c <= 0 then ( 0 ) else ( 1 + rot3(b, c, a - 1) ) )
assert(swap1(2, 2) == 3 and rot3(2, 2, 2) == 4)
```

## SCP 3-param non-decreasing rotation branch with duplicate arg in Kuhn matching

```babble
def dup3 = fn (a: int, b: int, c: int): int -> (
    if a <= 0 or b <= 0 or c <= 0 then ( 0 )
    else if a > b then ( dup3(b, c, a) )
    else ( dup3(a - 1, a, b) )
)
assert(dup3(1, 2, 2) == 0)
```

## SCP local alias descent and Tree record nested fields with record method access

```babble
def down = fn (n: int): int -> ( if n <= 0 then ( 0 ) else ( def m = n - 1 ; 1 + down(m) ) )
def tSum = fn (t: Tree?, c: Box): int -> (
  if t == null then ( c.v ) else (
    def l = t.left ; def s = if l != null then ( tSum(l.right, c) ) else ( 0 )
    t.getVal() + tSum(l, c) + tSum(t.right, c) + s
  )
)
assert(down(3) == 3 and tSum(Tree(1, Tree(2, null, null), null), Box(0)) == 3)
```

## SCP binary subtraction and addition edge cases in describeArg (single param)

```babble
def f1 = fn (n: int): int -> (
    if n <= 0 then ( 0 )
    else if n == 99 then ( f1(n * 2) + f1(10 - n) + f1(n + 1) )
    else if n >= -5 then ( def m = n - 1 ; f1(m - 1) )
    else ( 0 )
)
assert(f1(2) == 0)
```

## SCP binary subtraction and addition edge cases in describeArg (multi-param and long)

```babble
def f2 = fn (a: int, b: int, l: long): int -> (
    if a <= 0 or l <= 0L then ( 0 )
    else if a == 99 then ( def m = b - 1 ; f2(a + b, b, l - 3000000000L) + f2(a - m, b, l) + f2(a - b, b, l) )
    else if b >= 0 then ( f2(a - b, b, l) )
    else ( 0 )
)
assert(f2(0, 1, 1L) == 0)
```

## SCP scalar division and describeDivDecrease edge cases (int)

```babble
def d1 = fn (n: int, m: int, a: int, b: int): int -> (
    if n <= 0 then ( 0 )
    else if n == 99 then ( def k = n - 1 ; d1(int(10 / 2), int(k / 2), int(a / b), int(n / 2)) )
    else if n >= -1 then ( d1(int(n / 2), int(n), int(n + 1), b) )
    else ( 0 )
)
assert(d1(0, 0, 0, 1) == 0)
```

## SCP scalar division and describeDivDecrease edge cases (long)

```babble
def d2 = fn (n: long): long -> (
    if n <= 0L then ( 0L )
    else if n >= 0L then ( d2(long(n / 2L)) + long(double(n / 2L)) )
    else ( 0L )
)
assert(d2(0L) == 0L)
```

## SCP Math.floorDiv, Math.floorMod, Math.abs, and Math.max in describeArg

```babble
def mf = fn (a: int, b: int): int -> (
    if a <= 0 then ( 0 )
    else if a == 99 then (
        def m = b - 1
        mf(java.lang.Math.floorDiv(a, -2), java.lang.Math.abs(b)) +
        mf(java.lang.Math.max(a, b), java.lang.Math.floorMod(a, 2)) + mf(java.lang.Math.floorMod(a, m), b)
    ) else if b >= 0 then ( mf(java.lang.Math.floorMod(a, b + 1), b) ) else ( 0 )
)
assert(mf(0, 1) == 0)
```

## SCP assume, refineBound, negateComparisonOp, and flipComparisonOp edge cases

```babble
def g1 = fn (flag: bool, n: int, a: int, b: int): int -> (
    def m = b - 1
    if flag then ( 0 )
    else if not (n != 0) then ( 0 )
    else if 0 > n then ( 0 )
    else if a + 1 > 0 and 0 < a + 1 and a >= 1 and a <= b and a == b and a > m and -n < 0 then ( g1(true, n - 1, a - 1, b) )
    else ( 0 )
)
assert(g1(true, 1, 1, 1) == 0)
```

## SCP Long.MAX_VALUE guards in refineBound

```babble
def mg = fn (a: long, b: long): long -> (
    def MX = 9223372036854775807L
    if a <= 0L then ( 0L )
    else if a > MX then ( mg(a - 1L, b) )
    else if a >= MX and a != MX then ( mg(a - 1L, b) )
    else if b >= MX and a > b then ( mg(a - 1L, b) )
    else if a >= MX and a < b then ( mg(a - 1L, b) )
    else ( 0L )
)
assert(mg(1L, 1L) == 0L)
```

## SCP custom bool plus operator exercises negateComparisonOp null fallback

```babble
def BP = class () ( def plus = fn (o: BP): bool -> ( true ) )
def f = fn (n: int, a: BP, b: BP): int -> (
    if n <= 0 then ( 0 )
    else if not (a + b) then ( f(n - 1, a, b) )
    else ( 0 )
)
assert(f(1, BP(), BP()) == 0)
```

## SCP walkExpr control forms : block expr, expr-position if, local interface, and nullable def

```babble
def fCtrl = fn (n: int): int -> (
    def I = interface ()
    def u: int? = null
    u = 1
    if n <= 0 then ( 0 ) else ( (u!) + ( def t = n - 1 ; fCtrl(t) ) + (if n == 1 then ( 0 ) else ( fCtrl(n - 1) )) )
)
assert(fCtrl(2) == 3)
```

## SCP walkExpr with byte and short integral parameters

```babble
def fB = fn (n: byte): int -> ( if n <= byte(0) then ( 0 ) else ( 1 + fB(byte(int(n) - 1)) ) )
def fS = fn (n: short): int -> ( if n <= short(0) then ( 0 ) else ( 1 + fS(short(int(n) - 1)) ) )
assert(fB(byte(2)) == 2 and fS(short(2)) == 2)
```

## SCP non-terminating bailouts : if-test and and-left refs

```babble
def f2 = fn (n: int): int -> ( if f2 == null then ( 0 ) else if n <= 0 then ( 0 ) else ( f2(n - 1) ) )
def f3 = fn (n: int): int -> ( if (f3 == null) and (n > 0) then ( 0 ) else if n <= 0 then ( 0 ) else ( f3(n - 1) ) )
assert(f2(1) == 0 and f3(1) == 0)
```

## SCP non-terminating bailouts : stringified self-ref and generic arg refs

```babble
def f4 = fn io (n: int): int -> ( def s = "" + f4 ; if n <= 0 then ( s.length() ) else ( f4(n - 1) ) )
def f5 = fn (n: int): int -> ( def x = id of any (f5) ; if n <= 0 then ( 0 ) else ( f5(n - 1) ) )
assert(f4(1) > 0 and f5(1) == 0)
```

## SCP generic TypeApply recursive call and spread into non-rest list param

```babble
def fGen of T = fn (n: int, x: T): int -> ( if n <= 0 then ( 0 ) else ( 1 + fGen of T (n - 1, x) ) )
def fSp = fn (n: int, xs: list of int): int -> ( if n <= 0 then ( 0 ) else ( fSp(n - 1, ...xs) ) )
assert(fGen of int (2, 9) == 2 and fSp(1, list(1)) == 0)
```

## SCP inner closure recurse inside recursive outer function

```babble
def fIn = fn (n: int): int -> (
    def h = fn (k: int): int -> ( if k <= 0 then ( 0 ) else ( recurse(k - 1) ) )
    if n <= 0 then ( 0 ) else ( h(1) + fIn(n - 1) )
)
assert(fIn(2) == 0)
```

## SCP non-this, overloaded, and sibling method reachability

```babble
def C = class (def cb: fn (int): int) (
    def h = fn (n: int): int -> ( n )
    def m = fn (n: int, o: C): int -> ( if n <= 0 then ( this.h(0) + this.cb(0) ) else ( o.m(n - 1, o) ) )
    def ov = overload(fn (n: int): int -> ( if n <= 0 then ( 0 ) else ( this.ov(n - 1) ) ), fn (s: string): int -> ( s.length() ))
)
def c = C(fn (k: int): int -> ( k ))
assert(c.m(1, c) == 0 and c.ov(1) == 0)
```

## SCP indirect getter call and reassigned recursive local reachability

```babble
def getF = fn (): fn (int): int -> ( fInd )
def fInd = fn (n: int): int -> ( if n <= 0 then ( 0 ) else ( getF()(n - 1) ) )
def r = fn volatile (n: int): int -> ( if n <= 0 then ( 0 ) else ( r(n - 1) ) )
r = fn volatile (n: int): int -> ( n )
assert(fInd(1) == 0 and r(2) == 2)
```

## SCP recursive toString in string concatenation

```babble
def RStr = class (def n: int) (
    def toString = fn volatile (): string -> ( if this.n <= 0 then ( "0" ) else ( "" + RStr(this.n - 1) + Box(this.n) ) )
)
assert(RStr(1).toString().startsWith("0"))
```

## SCP alwaysExitsBlock empty and partial-exit nested if

```babble
def exits = fn (n: int): int -> (
    def _e: any? = if n <= 0 then () ; discard(_e)
    if n <= 0 then ( if n == 0 then ( return 0 ) )
    if n <= 0 then ( if n == 0 then ( return 0 ) else ( discard(0) ) )
    if n <= 0 then ( if n == 0 then ( discard(0) ) else ( return 0 ) )
    if n <= 0 then ( 1 ) else ( exits(n - 1) )
)
assert(exits(1) == 0)
```
