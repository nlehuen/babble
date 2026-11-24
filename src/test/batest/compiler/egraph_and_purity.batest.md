# E-graph constant folding, algebraic verifier rules, and HIR purity / determinism

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (return this.v ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return this.v + n ; this.v)
)
```

```babble preamble
def tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def volLen = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return n ; n)
```

## EGraph.foldUnary : boolean NOT on bool/null and float negation

```babble
def fNotT = fn (b: bool): bool -> ( b and (not true) )
def fNotF = fn (b: bool): bool -> ( b or (not false) )
def fNotN = fn (b: bool): bool -> ( def o: int? = null ; b or (not o) )
def fNegF = fn (x: float): float -> ( x + (-(1.5f)) )
def fNegD = fn (x: double): double -> ( x + (-(2.5)) )
assert(not fNotT(true) and fNotF(false) and fNotN(false) and fNegF(2.5f) == 1.0f and fNegD(3.5) == 1.0)
```

## EGraph.foldBinary : boolean logic, non-string STR_ADD, and long subtraction

```babble
def fAnd = fn (b: bool): bool -> (b and (true and false))
def fOr = fn (b: bool): bool -> (b or (false or true))
def fLogN = fn (b: bool): bool -> (def o: int? = null ; b or (true and o))
def fStrN = fn (s: string): string -> (s + ("a" + 1))
def fLSub = fn (x: long): long -> (x + (10L - 3L))
assert(not fAnd(true) and fOr(false) and fLogN(true) and fStrN("x") == "xa1" and fLSub(1L) == 8L)
```

## EGraph.foldBinary : float and double arithmetic folding

```babble
def fFlt = fn (x: float): float -> ( x + (1.5f + 2.5f) + (5.5f - 2.5f) + (2.0f * 3.0f) + (6.0f / 2.0f) )
def fDbl = fn (x: double): double -> ( x + (5.5 - 2.5) + (2.0 * 3.0) + (6.0 / 2.0) )
assert(fFlt(0.0f) == 16.0f and fDbl(0.0) == 12.0)
```

## EGraph.foldComparison : bool, char, and string constant comparisons

```babble
def fBool = fn (b: bool): bool -> ( b and (true == true) and not (true == false) and (true != false) and not (false != false) )
def fChar = fn (b: bool): bool -> ( b and ('a' < 'b') and ('a' <= 'a') and ('b' > 'a') and ('b' >= 'b') and ('a' == 'a') and ('a' != 'b') )
def fStr = fn (b: bool): bool -> ( b and not ("a" == "b") )
assert(fBool(true) and fChar(true) and fStr(true))
```

## EGraph.foldComparison and numericOrder : byte, short, int, long, float, and double comparisons

```babble
def fByteShort = fn (b: bool): bool -> ( def x: byte = 1 ; def y: short = 2 ; b and (x < y) and (y > x) )
def fWide = fn (b: bool): bool -> ( b and (1 < 2L) and (2L > 1) and (1L <= 2L) and (1.5f < 2.5f) and (2.5 > 1.5) and (2.5 >= 1L) )
assert(fByteShort(true) and fWide(true))
```

## ENode : repeated float literal equality, non-integral asIntegralLong, and non-bool asBool

```babble
def fFltDup = fn (x: float): float -> ( (x + 1.5f) + 1.5f )
def fCharMin = fn (c: char): char -> ( if c < 'a' then ('a') else (c) )
def fByteMin = fn (b: byte): byte -> ( if b < byte(0) then (byte(0)) else (b) )
def fIntCond = fn (a: int, b: int): int -> ( if 1 then (a) else (b) )
assert(fFltDup(1.0f) == 4.0f and fCharMin('A') == 'a' and fByteMin(byte(-1)) == byte(0) and fIntCond(3, 4) == 3)
```

## AlgebraicRules.booleanLogicAndEqRules : boolean identities and annihilators

```babble
def bIdL = fn (b: bool): bool -> ( true and b )
def bIdR = fn (b: bool): bool -> ( b and true )
def bAnnL = fn (b: bool): bool -> ( false and b )
def bAnnR = fn (b: bool): bool -> ( b or true )
def bOrId = fn (b: bool): bool -> ( false or (b or false) )
assert(bIdL(true) and bIdR(true) and not bAnnL(true) and bAnnR(false) and bOrId(true))
```

## AlgebraicRules.booleanLogicAndEqRules : De Morgan laws on and/or

```babble
def dmAnd = fn (a: bool, b: bool): bool -> ( not (a and b) )
def dmOr = fn (a: bool, b: bool): bool -> ( not (a or b) )
assert(dmAnd(true, false) and not dmAnd(true, true) and dmOr(false, false) and not dmOr(true, false))
```

## AlgebraicRules.negateExactComparison : ordering ops on exact-ordered integers

```babble
def nLt = fn (a: int, b: int): bool -> ( not (a < b) )
def nLte = fn (a: int, b: int): bool -> ( not (a <= b) )
def nGt = fn (a: int, b: int): bool -> ( not (a > b) )
def nGte = fn (a: int, b: int): bool -> ( not (a >= b) )
assert(nLt(3, 2) and nLte(3, 2) and nGt(1, 2) and nGte(1, 2))
```

## AlgebraicRules.negateExactComparison : equality ops and byte/short/char vs double

```babble
def nEq = fn (a: int, b: int): bool -> ( not (a == b) )
def nNeq = fn (a: int, b: int): bool -> ( not (a != b) )
def nByte = fn (a: byte, b: byte): bool -> ( not (a < b) )
def nShort = fn (a: short, b: short): bool -> ( not (a <= b) )
assert(nEq(1, 2) and nNeq(2, 2) and nByte(byte(2), byte(1)) and nShort(short(2), short(1)))
```

## AlgebraicRules.isExactOrderedClass : char vs double and mixed int-double comparisons

```babble
def nChar = fn (a: char, b: char): bool -> ( not (a > b) )
def nDbl = fn (a: double, b: double): bool -> ( not (a < b) )
def nMix = fn (a: int, b: int): bool -> ( not (a < 1.5) and not (b < 1.5) )
assert(nChar('a', 'b') and nDbl(2.0, 1.0) and nMix(2, 3))
```

## AlgebraicRules.iteAndOrderRules : equal branches, positive-guard abs, and non-neg guard

```babble
def sameBr = fn (a: int, b: int): int -> ( if a < b then (a + b) else (b + a) )
def absPos = fn (x: int): int -> ( if x >= 0 then (x) else (-x) )
def notAbs = fn (x: int): int -> ( if x >= 0 then (x) else (0) )
assert(sameBr(2, 3) == 5 and absPos(-4) == 4 and notAbs(-4) == 0)
```

## AlgebraicRules.iteAndOrderRules : nested same-cond ITE and two-sided lattice clamp

```babble
def nestT = fn (x: int): int -> ( if x < 0 then (if x < 0 then (0) else (1)) else (x) )
def band = fn (x: int): int -> ( def lo = if x < 0 then (0) else (x) ; if lo > 10 then (10) else (lo) )
assert(nestT(-2) == 0 and nestT(3) == 3 and band(15) == 10 and band(-2) == 0)
```

## AlgebraicRules.callAlgebraicRules : same-arg commutative, mismatched associative, and unary idempotent

```babble
def cAdd = fn commutative associative (a: int, b: int): int -> ( a + b )
def cAlt = fn associative (a: int, b: int): int -> ( a + b )
def idC = fn axiom pure nothrow idempotent (x: int): int -> ( if x < 0 then (0) else (x) )
def u1 = fn (x: int): int -> ( idC(cAdd(x, x)) )
def u2 = fn (a: int, b: int): int -> ( cAdd(cAlt(a, b), a) )
assert(u1(3) == 6 and u2(2, 3) == 7)
```

## AlgebraicRules.floatingCommutativityRules : negated double product and double float negation

```babble
def fMul = fn (a: double, b: double): double -> ( (-a) * (-b) )
def fNeg = fn (x: float): float -> ( -(-x) )
def dNeg = fn (x: double): double -> ( -(-x) )
assert(fMul(2.0, 3.0) == 6.0 and fNeg(1.5f) == 1.5f and dNeg(2.5) == 2.5)
```

## HirPurity.isTryPureNothrowTerminating : pure/impure catch and finally before DCE strips catches

```babble
def run = fn io (x: int): int -> (
    def a = (try (x + 1) catch : exception then (x + 2)) + 0
    def b = (try (x + 1) catch : exception then (bump())) + 0
    def c = (try (x + 1) catch : exception then (x + 2) finally (def z = x + 3 ; discard(z))) + 0
    def d = (try (x + 1) finally (discard(bump()))) + 0
    a + b + c + d
)
assert(run(2) == 12 and tick == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
```

## HirPurity : top-level namespace-scoped block, try, catch, and finally

```babble
discard((def tb = 1 ; tb))
def r1 = (try (def ta = 1 ; ta) finally (discard(2))) + 0
def r2 = (try (1) finally (def tf = 2 ; discard(tf))) + 0
def r3 = (try (1) catch : exception then (def tc = 2 ; tc)) + 0
assert(r1 == 1 and r2 == 1 and r3 == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

## HirPurity.isDeterministic : volatile nothrow record and interface methods in while

```babble
def VI = interface (def vt: fn volatile nothrow (): int)
def VR = record (k: int) implements VI (
    def vt = fn volatile nothrow (): int -> (def n = volLen() ; return this.k + n ; this.k)
)
def run = fn volatile (n: int, vr: VR, vi: VI): int -> (
    def a = 0 ; def i = 0
    while i < n then (a += vr.vt() + vi.vt() ; i += 1) ; a
)
assert(run(2, VR(1), VR(1)) >= 8)
```

## HirPurity.isDeterministic : volatile nothrow fn, Java call, and nested volatile child in while

```babble
def run = fn volatile (n: int): int -> (
    def a = 0 ; def i = 0
    while i < n then (
        a += volLen() + java.lang.System.lineSeparator().length() + bigInt(volLen() + volLen())
        i += 1
    ) ; a
)
assert(run(2) >= 8)
```

## HirPurity : volatile and impure For loops inside blocks

```babble
def run = fn io (n: int, xs: frozen array of int): int -> (
    def a = 0 ; def i = 0
    while i < n then (
        a += bigInt((for e in xs then (discard(volLen())) ; 1))
        def u = (for e in xs then (discard(bump())) ; 1)
        a += u ; i += 1
    ) ; a
)
assert(run(2, freeze(array.of(1, 2))) == 4 and tick == 4)
```

## HirPurity.allArgsPrimitiveOrImmutable : String.equals on immutable String vs mutable Point

```babble
def run = fn io (n: int): int -> (
    def pt = java.awt.Point(1, 2) ; def a = 0 ; def i = 0
    def u1 = "ab".equals("cd") ; def u2 = "ab".equals(pt)
    while i < n then (
        a += (if "ab".equals("ab") and not "ab".equals(pt) then (1) else (0)) ; i += 1
    ) ; a
)
assert(run(2) == 2)
```

## HirPurity : CoreExceptionRef, boxed Integer arithmetic, and char-vs-nullable equality

```babble
def run = fn (ji: java.lang.Integer, chOpt: char?): int -> (
    def uE = exception ; def uC = ('a' == chOpt)
    def uU = -ji ; def uB1 = ji + 1 ; def uB2 = 1 + ji
    1
)
assert(run(java.lang.Integer.valueOf(2), 'a') == 1)
```

## HirPurity : OverloadSelect and non-pure-static JavaMethodCall receivers

```babble
def run = fn io (): int -> (
    def ov = overload (fn (x: int): int -> (x), fn (s: string): int -> (s.length()))
    def uOv: fn (int): int = ov
    def m = java.lang.Math ; def uM1 = m.abs(-5)
    def uM2 = (bump() ; java.lang.Math).abs(-5)
    1
)
assert(run() == 1 and tick == 1)
```

## HirPurity : cross-module DirectThisMethodCall pure, throwing, and volatile paths

```babble
def ext = import("ext_rec.ba")
def run = fn volatile (er: ext.ExtRec): int -> (
    def u1 = er.getPure() ; def u2 = er.getThrow() ; def u3 = er.getVol()
    def a = 0 ; def i = 0
    while i < 2 then (a += er.getPure() + er.getVol() ; i += 1)
    a
)
assert(run(ext.ExtRec(4)) >= 18)
```

## HirPurity : InterfaceCall on ThisRef, RecordNew, call result, and impure method

```babble
def I = interface (
    def a = fn nothrow (): int -> (1)
    def b = fn nothrow (): int -> (def u = this.a() ; 2)
    def imp = fn io (): int -> (bump())
)
def R = record (x: int) implements I ()
def getI = fn (): I -> (R(3))
def run = fn io (vi: I): int -> (def u1 = R(1).b() ; def u2 = getI().a() ; def u3 = vi.imp() ; 1)
assert(run(R(2)) == 1 and tick == 1)
```

## HirPurity : body-field ThisFieldGet, call-result record receiver, and local RecordNew

```babble
def R = record (x: int) (def y: int = this.x + 1 ; def m = fn (): int -> (def u = this.y ; this.x))
def getR = fn (): R -> (R(3))
def run = fn (): int -> (
    def LR = record (z: int) () ; def u0 = LR(1)
    def u1 = getR().x ; def u2 = getR().m() ; 1
)
assert(run() == 1)
```

## HirPurity : uninitialized Def, captured cell local, capturing closure, Move, and late RecordNew

```babble
tick = 1
def LateR = record (k: int) ()
def fUninit = fn (k: int): int -> (def u: int? ; u = k + 1 ; u!)
def run = fn io (k: int): int -> (
    def outer = k ; def cl = fn nothrow (): int -> (outer + 1)
    def u0 = LateR(1) ; def u1 = fUninit(k) ; def u2 = cl()
    def u3 = (def cap = 1 ; def c2 = fn (): int -> (cap) ; c2())
    def r = list(2) ; def u4 = move(r) ; u2
)
assert(run(3) == 4)
```
