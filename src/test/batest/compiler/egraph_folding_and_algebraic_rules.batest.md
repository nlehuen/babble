# E-Graph Constant Folding and Algebraic Rewrite Rules

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble
;; babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (return this.v ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return this.v + n ; this.v)
)
```

```babble
;; babble preamble
var tick = 0
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

```hir
;; hir -O0 fn=fNotN
fn fNotN (b:bool) -> bool {nothrow idempotent unique}
  (def o null)
  (or var(b) (block (not var(o))))
```

```hir
;; hir -O1 fn=fNotN
fn fNotN (b:bool) -> bool {nothrow idempotent unique}
  (or var(b) true:bool)
```

```hir
;; hir -O0 fn=fNegF
fn fNegF (x:float) -> float {nothrow monotone unique}
  (+:float var(x) (block (neg (block 1.5:float))))
```

```hir
;; hir -O1 fn=fNegF
fn fNegF (x:float) -> float {nothrow monotone unique}
  (+:float var(x) -1.5:float)
```

```hir
;; hir -O2 fn=fNegF
fn fNegF (x:float) -> float {nothrow monotone unique}
  (+:float var(x) -1.5:float)
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

```hir
;; hir -O0 fn=fLSub
fn fLSub (x:long) -> long {nothrow injective unique}
  (+:long var(x) (block (-:long 10:long 3:long)))
```

```hir
;; hir -O1 fn=fLSub
fn fLSub (x:long) -> long {nothrow injective unique}
  (+:long var(x) 7:long)
```

```hir
;; hir -O0 fn=fAnd
fn fAnd (b:bool) -> bool {nothrow idempotent unique}
  (and var(b) (block (and true:bool false:bool)))
```

```hir
;; hir -O1 fn=fAnd
fn fAnd (b:bool) -> bool {nothrow idempotent unique}
  (and var(b) false:bool)
```

```hir
;; hir -O2 fn=fAnd
fn fAnd (b:bool) -> bool {nothrow idempotent unique}
  (and var(b) false:bool)
```

## EGraph.foldBinary : float and double arithmetic folding

```babble
def fFlt = fn (x: float): float -> ( x + (1.5f + 2.5f) + (5.5f - 2.5f) + (2.0f * 3.0f) + (6.0f / 2.0f) )
def fDbl = fn (x: double): double -> ( x + (5.5 - 2.5) + (2.0 * 3.0) + (6.0 / 2.0) )
assert(fFlt(0.0f) == 16.0f and fDbl(0.0) == 12.0)
```

```hir
;; hir -O0 fn=fFlt
fn fFlt (x:float) -> float {nothrow monotone unique}
  (+:float (+:float (+:float (+:float var(x) (block (+:float 1.5:float 2.5:float))) (block (-:float 5.5:float 2.5:float))) (block (*:float 2.0:float 3.0:float))) (block (/:float 6.0:float 2.0:float)))
```

```hir
;; hir -O1 fn=fFlt
fn fFlt (x:float) -> float {nothrow monotone unique}
  (+:float (+:float (+:float (+:float var(x) 4.0:float) 3.0:float) 6.0:float) 3.0:float)
```

```hir
;; hir -O2 fn=fFlt
fn fFlt (x:float) -> float {nothrow monotone unique}
  (+:float (+:float (+:float (+:float var(x) 4.0:float) 3.0:float) 6.0:float) 3.0:float)
```

## EGraph.foldComparison : bool, char, and string constant comparisons

```babble
def fBool = fn (b: bool): bool -> ( b and (true == true) and not (true == false) and (true != false) and not (false != false) )
def fChar = fn (b: bool): bool -> ( b and ('a' < 'b') and ('a' <= 'a') and ('b' > 'a') and ('b' >= 'b') and ('a' == 'a') and ('a' != 'b') )
def fStr = fn (b: bool): bool -> ( b and not ("a" == "b") )
assert(fBool(true) and fChar(true) and fStr(true))
```

```hir
;; hir -O0 fn=fChar
fn fChar (b:bool) -> bool {nothrow idempotent unique}
  (and (and (and (and (and (and var(b) (block (<:bool 'a' 'b'))) (block (<=:bool 'a' 'a'))) (block (>:bool 'b' 'a'))) (block (>=:bool 'b' 'b'))) (block (==:bool 'a' 'a'))) (block (<>:bool 'a' 'b')))
```

```hir
;; hir -O1 fn=fChar
fn fChar (b:bool) -> bool {nothrow idempotent unique}
  (and (and (and (and (and (and var(b) true:bool) true:bool) true:bool) true:bool) true:bool) true:bool)
```

```hir
;; hir -O2 fn=fChar
fn fChar (b:bool) -> bool {nothrow idempotent unique}
  (and (and (and (and (and (and var(b) true:bool) true:bool) true:bool) true:bool) true:bool) true:bool)
```

## EGraph.foldComparison and numericOrder : byte, short, int, long, float, and double comparisons

```babble
def fByteShort = fn (b: bool): bool -> ( def x: byte = 1 ; def y: short = 2 ; b and (x < y) and (y > x) )
def fWide = fn (b: bool): bool -> ( b and (1 < 2L) and (2L > 1) and (1L <= 2L) and (1.5f < 2.5f) and (2.5 > 1.5) and (2.5 >= 1L) )
assert(fByteShort(true) and fWide(true))
```

```hir
;; hir -O0 fn=fByteShort
fn fByteShort (b:bool) -> bool {nothrow idempotent unique}
  (def x 1:byte)
  (def y 2:short)
  (and (and var(b) (block (<:bool (convert[short] var(x)) var(y)))) (block (>:bool var(y) (convert[short] var(x)))))
```

```hir
;; hir -O1 fn=fByteShort
fn fByteShort (b:bool) -> bool {nothrow idempotent unique}
  (and (and var(b) true:bool) true:bool)
```

```hir
;; hir -O2 fn=fByteShort
fn fByteShort (b:bool) -> bool {nothrow idempotent unique}
  (and (and var(b) true:bool) true:bool)
```

## ENode : repeated float literal equality, non-integral asIntegralLong, and non-bool asBool

```babble
def fFltDup = fn (x: float): float -> ( (x + 1.5f) + 1.5f )
def fCharMin = fn (c: char): char -> ( if c < 'a' then ('a') else (c) )
def fByteMin = fn (b: byte): byte -> ( if b < byte(0) then (byte(0)) else (b) )
def fIntCond = fn (a: int, b: int): int -> ( if 1 then (a) else (b) )
assert(fFltDup(1.0f) == 4.0f and fCharMin('A') == 'a' and fByteMin(byte(-1)) == byte(0) and fIntCond(3, 4) == 3)
```

```hir
;; hir -O0 fn=fIntCond
fn fIntCond (a:int, b:int) -> int {nothrow associative monotone}
  (if 1:int (block var(a)) (block var(b)))
```

```hir
;; hir -O1 fn=fIntCond
fn fIntCond (a:int, b:int) -> int {nothrow associative monotone}
  var(a)
```

```hir
;; hir -O2 fn=fIntCond
fn fIntCond (a:int, b:int) -> int {nothrow associative monotone}
  var(a)
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

```hir
;; hir -O0 fn=bIdL
fn bIdL (b:bool) -> bool {nothrow idempotent unique}
  (and true:bool var(b))
```

```hir
;; hir -O1 fn=bIdL
fn bIdL (b:bool) -> bool {nothrow idempotent unique}
  var(b)
```

```hir
;; hir -O0 fn=bAnnL
fn bAnnL (b:bool) -> bool {nothrow idempotent unique}
  (and false:bool var(b))
```

```hir
;; hir -O1 fn=bAnnL
fn bAnnL (b:bool) -> bool {nothrow idempotent unique}
  false:bool
```

```hir
;; hir -O2 fn=bAnnL
fn bAnnL (b:bool) -> bool {nothrow idempotent unique}
  false:bool
```

## AlgebraicRules.booleanLogicAndEqRules : De Morgan laws on and/or

```babble
def dmAnd = fn (a: bool, b: bool): bool -> ( not (a and b) )
def dmOr = fn (a: bool, b: bool): bool -> ( not (a or b) )
assert(dmAnd(true, false) and not dmAnd(true, true) and dmOr(false, false) and not dmOr(true, false))
```

```hir
;; hir -O0 fn=dmAnd
fn dmAnd (a:bool, b:bool) -> bool {nothrow commutative unique}
  (not (block (and var(a) var(b))))
```

```hir
;; hir -O1 fn=dmAnd
fn dmAnd (a:bool, b:bool) -> bool {nothrow commutative unique}
  (not (and var(a) var(b)))
```

```hir
;; hir -O2 fn=dmAnd
fn dmAnd (a:bool, b:bool) -> bool {nothrow commutative unique}
  (not (and var(a) var(b)))
```

## AlgebraicRules.negateExactComparison : ordering ops on exact-ordered integers

```babble
def nLt = fn (a: int, b: int): bool -> ( not (a < b) )
def nLte = fn (a: int, b: int): bool -> ( not (a <= b) )
def nGt = fn (a: int, b: int): bool -> ( not (a > b) )
def nGte = fn (a: int, b: int): bool -> ( not (a >= b) )
assert(nLt(3, 2) and nLte(3, 2) and nGt(1, 2) and nGte(1, 2))
```

```hir
;; hir -O0 fn=nLt
fn nLt (a:int, b:int) -> bool {nothrow unique}
  (not (block (<:bool var(a) var(b))))
```

```hir
;; hir -O1 fn=nLt
fn nLt (a:int, b:int) -> bool {nothrow unique}
  (>=:bool var(a) var(b))
```

```hir
;; hir -O2 fn=nLt
fn nLt (a:int, b:int) -> bool {nothrow unique}
  (>=:bool var(a) var(b))
```

## AlgebraicRules.negateExactComparison : equality ops and byte/short/char vs double

```babble
def nEq = fn (a: int, b: int): bool -> ( not (a == b) )
def nNeq = fn (a: int, b: int): bool -> ( not (a != b) )
def nByte = fn (a: byte, b: byte): bool -> ( not (a < b) )
def nShort = fn (a: short, b: short): bool -> ( not (a <= b) )
assert(nEq(1, 2) and nNeq(2, 2) and nByte(byte(2), byte(1)) and nShort(short(2), short(1)))
```

```hir
;; hir -O0 fn=nEq
fn nEq (a:int, b:int) -> bool {nothrow commutative unique}
  (not (block (==:bool var(a) var(b))))
```

```hir
;; hir -O1 fn=nEq
fn nEq (a:int, b:int) -> bool {nothrow commutative unique}
  (<>:bool var(a) var(b))
```

```hir
;; hir -O2 fn=nEq
fn nEq (a:int, b:int) -> bool {nothrow commutative unique}
  (<>:bool var(a) var(b))
```

## AlgebraicRules.isExactOrderedClass : char vs double and mixed int-double comparisons

```babble
def nChar = fn (a: char, b: char): bool -> ( not (a > b) )
def nDbl = fn (a: double, b: double): bool -> ( not (a < b) )
def nMix = fn (a: int, b: int): bool -> ( not (a < 1.5) and not (b < 1.5) )
assert(nChar('a', 'b') and nDbl(2.0, 1.0) and nMix(2, 3))
```

```hir
;; hir -O0 fn=nMix
fn nMix (a:int, b:int) -> bool {nothrow commutative unique}
  (and (not (block (<:bool (convert[double] var(a)) 1.5:double))) (not (block (<:bool (convert[double] var(b)) 1.5:double))))
```

```hir
;; hir -O1 fn=nMix
fn nMix (a:int, b:int) -> bool {nothrow commutative unique}
  (and (>=:bool (convert[double] var(a)) 1.5:double) (>=:bool (convert[double] var(b)) 1.5:double))
```

```hir
;; hir -O2 fn=nMix
fn nMix (a:int, b:int) -> bool {nothrow commutative unique}
  (block
    (def $cse0 (convert[double] var(a)))
    (and (>=:bool var($cse0) 1.5:double) (>=:bool (convert[double] var(b)) 1.5:double)))
```

## AlgebraicRules.iteAndOrderRules : equal branches, positive-guard abs, and non-neg guard

```babble
def sameBr = fn (a: int, b: int): int -> ( if a < b then (a + b) else (b + a) )
def absPos = fn (x: int): int -> ( if x >= 0 then (x) else (-x) )
def notAbs = fn (x: int): int -> ( if x >= 0 then (x) else (0) )
assert(sameBr(2, 3) == 5 and absPos(-4) == 4 and notAbs(-4) == 0)
```

```hir
;; hir -O0 fn=sameBr
fn sameBr (a:int, b:int) -> int {nothrow commutative associative}
  (if (<:bool var(a) var(b)) (block (+:int var(a) var(b))) (block (+:int var(b) var(a))))
```

```hir
;; hir -O1 fn=sameBr
fn sameBr (a:int, b:int) -> int {nothrow commutative associative}
  (if (<:bool var(a) var(b)) (+:int var(a) var(b)) (+:int var(b) var(a)))
```

```hir
;; hir -O2 fn=sameBr
fn sameBr (a:int, b:int) -> int {nothrow commutative associative}
  (if (<:bool var(a) var(b)) (+:int var(a) var(b)) (+:int var(b) var(a)))
```

## AlgebraicRules.iteAndOrderRules : nested same-cond ITE and two-sided lattice clamp

```babble
def nestT = fn (x: int): int -> ( if x < 0 then (if x < 0 then (0) else (1)) else (x) )
def band = fn (x: int): int -> ( def lo = if x < 0 then (0) else (x) ; if lo > 10 then (10) else (lo) )
assert(nestT(-2) == 0 and nestT(3) == 3 and band(15) == 10 and band(-2) == 0)
```

```hir
;; hir -O0 fn=band
fn band (x:int) -> int {nothrow idempotent monotone}
  (def lo (if (<:bool var(x) 0:int) (block 0:int) (block var(x))))
  (if (>:bool var(lo) 10:int) (block 10:int) (block var(lo)))
```

```hir
;; hir -O1 fn=band
fn band (x:int) -> int {nothrow idempotent monotone}
  (def lo (if (<:bool var(x) 0:int) 0:int var(x)))
  (if (>:bool var(lo) 10:int) 10:int var(lo))
```

```hir
;; hir -O2 fn=band
fn band (x:int) -> int {nothrow idempotent monotone}
  (def lo (if (<:bool var(x) 0:int) 0:int var(x)))
  (if (>:bool var(lo) 10:int) 10:int var(lo))
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

```hir
;; hir -O0 fn=u1
fn u1 (x:int) -> int {nothrow unique}
  (call-fn idC (call-fn cAdd var(x) var(x)))
```

```hir
;; hir -O1 fn=u1
fn u1 (x:int) -> int {nothrow unique}
  (block
    (def x#2 (+:int var(x#1) var(x#1)))
    (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
```

```hir
;; hir -O2 fn=u1
fn u1 (x:int) -> int {nothrow unique}
  (block
    (def x#2 (+:int var(x#1) var(x#1)))
    (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
```

## AlgebraicRules.floatingCommutativityRules : negated double product and double float negation

```babble
def fMul = fn (a: double, b: double): double -> ( (-a) * (-b) )
def fNeg = fn (x: float): float -> ( -(-x) )
def dNeg = fn (x: double): double -> ( -(-x) )
assert(fMul(2.0, 3.0) == 6.0 and fNeg(1.5f) == 1.5f and dNeg(2.5) == 2.5)
```

```hir
;; hir -O0 fn=fMul
fn fMul (a:double, b:double) -> double {nothrow commutative unique}
  (*:double (block (neg var(a))) (block (neg var(b))))
```

```hir
;; hir -O1 fn=fMul
fn fMul (a:double, b:double) -> double {nothrow commutative unique}
  (*:double (neg var(a)) (neg var(b)))
```

```hir
;; hir -O2 fn=fMul
fn fMul (a:double, b:double) -> double {nothrow commutative unique}
  (*:double (neg var(a)) (neg var(b)))
```
