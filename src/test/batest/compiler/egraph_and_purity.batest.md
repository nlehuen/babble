# E-graph constant folding, algebraic verifier rules, and HIR purity / determinism

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
  (and (and var(b) (block (<:bool var(x) var(y)))) (block (>:bool var(y) var(x))))
```

```hir
;; hir -O1 fn=fByteShort
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
  (not (<:bool var(a) var(b)))
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
  (not (==:bool var(a) var(b)))
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
  (and (not (block (<:bool var(a) 1.5:double))) (not (block (<:bool var(b) 1.5:double))))
```

```hir
;; hir -O1 fn=nMix
fn nMix (a:int, b:int) -> bool {nothrow commutative unique}
  (and (not (<:bool var(a) 1.5:double)) (not (<:bool var(b) 1.5:double)))
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

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {io}
  (def a (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int)))) 0:int))
  (def b (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call-fn bump)))) 0:int))
  (def c (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int))
          (finally
            (def z (+:int var(x) 3:int))
            (call name(discard) var(z))))) 0:int))
  (def d (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (finally
            (call name(discard) (call-fn bump))))) 0:int))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {io}
  (def a (+:int var(x) 1:int))
  (def b (+:int var(x) 1:int))
  (def c
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        null)))
  (def d
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        (call name(discard) (call-fn bump)))))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
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

```hir
;; hir -O0 top-level check
contains (def tb 1:int)
count 3 (try
contains (catch
```

```hir
;; hir -O1 top-level
top-level:
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bigInt (fn-ref bigInt))
  (def volLen (fn-ref volLen))
  (block
    (def $try0
      (try
        (block
          1:int)
        (finally
          null)))
    (def r1 var($try0)))
  (block
    (def $try1
      (try
        (block
          1:int)
        (finally
          null)))
    (def r2 var($try1)))
  (def r3 1:int)
  (call name(assert) (and (and (==:bool name(r1) 1:int) (==:bool name(r2) 1:int)) (==:bool name(r3) 1:int)))
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

```hir
;; hir -O0 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (call-method var(vr).vt) (call-iface var(vi).vt)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field k))
    (while (<:bool var(i) var(n#1))
      (block
        (set a += (+:int (block
              (def n#2
                (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)))
              (+:int var($licm0) var(n#2))) (call-iface var(vi).vt)))
        (set i += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-fn volLen) (jcall String.length (jcall System.lineSeparator))) (call-fn bigInt (+:int (call-fn volLen) (call-fn volLen)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n#1))
    (block
      (set a += (+:int (+:int (block
              (def n#2 (jcall String.length (jcall System.lineSeparator)))
              var(n#2)) (jcall String.length (jcall System.lineSeparator))) (block
            (def x (+:int (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)) (block
                  (def n#4 (jcall String.length (jcall System.lineSeparator)))
                  var(n#4))))
            var(x))))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigInt (block
            (for e#1 in var(xs)
              (block
                (call name(discard) (call-fn volLen))))
            1:int)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a +=
        (block
          (def x
            (block
              (for e#1 in var(xs)
                (block
                  null))
              1:int))
          var(x)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (def u1 (jcall String.equals "ab" "cd"))
  (def u2 (jcall String.equals "ab" var(pt)))
  (while (<:bool var(i) var(n))
    (block
      (set a += (block (if (and (jcall String.equals "ab" "ab") (not (jcall String.equals "ab" var(pt)))) (block 1:int) (block 0:int))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (def u2 (jcall String.equals "ab" var(pt)))
  (block
    (def $licm0 (jcall String.equals "ab" "ab"))
    (while (<:bool var(i) var(n))
      (block
        (set a += (if (and var($licm0) (not (jcall String.equals "ab" var(pt)))) 1:int 0:int))
        (set i += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (def uE core-exception)
  (def uC (block (==:bool 'a' var(chOpt))))
  (def uU (neg var(ji)))
  (def uB1 (+:int var(ji) 1:int))
  (def uB2 (+:int 1:int var(ji)))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (def uC (==:bool 'a' var(chOpt)))
  1:int
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

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def ov (overload (fn-ref ov#1) (fn-ref ov#2)))
  (def uOv (overload-select #0 var(ov)))
  (def m (select (select name(java).lang).Math))
  (def uM1 (jcall Math.abs -5:int))
  (def uM2 (jcall Math.abs -5:int))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def ov (overload (fn-ref ov#1) (fn-ref ov#2)))
  (def m (select (select name(java).lang).Math))
  (def uM2 (jcall Math.abs -5:int))
  1:int
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

```hir
;; hir -O0 fn=run
fn run (er:obj) -> int {volatile unique}
  (def u1 (call-method var(er).getPure))
  (def u2 (call-method var(er).getThrow))
  (def u3 (call-method var(er).getVol))
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (call-method var(er).getPure) (call-method var(er).getVol)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (er:obj) -> int {volatile unique}
  (def u2 (call-method var(er).getThrow))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-method var(er).getPure))
    (while (<:bool var(i) 2:int)
      (block
        (set a += (+:int var($licm0) (call-method var(er).getVol)))
        (set i += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (vi:obj) -> int {io}
  (def u1 (call (select (record-new R 1:int).b)))
  (def u2 (call-iface (call-fn getI).a))
  (def u3 (call-iface var(vi).imp))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (vi:obj) -> int {io}
  (def u1 (call (select (record-new R 1:int).b)))
  (def u3 (call-iface var(vi).imp))
  1:int
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

```hir
;; hir -O0 fn=run
fn run () -> int {nothrow unique}
  (def LR (class-ref LR))
  (def u0 (record-new LR 1:int))
  (def u1 (get-this-field x))
  (def u2 (call-method (call-fn getR).m))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {nothrow unique}
  (def LR (class-ref LR))
  (block (def $sr_u0_0 1:int))
  (def u1 (get-this-field x))
  (def u2
    (block
      (def $this (call name(R) 3:int))
      (def u (get-this-field y))
      (get-this-field x)))
  1:int
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

```hir
;; hir -O0 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (def u0 (record-new LateR 1:int))
  (def u1 (call-fn fUninit var(k)))
  (def u2 (call-fn cl))
  (def u3
    (block
      (def cap 1:int)
      (def c2 (fn-ref c2))
      (call-fn c2)))
  (def r (core-new list 2:int))
  (def u4 (move var(r)))
  var(u2)
```

```hir
;; hir -O1 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (block
    name(LateR)
    (def $sr_u0_0 1:int))
  (def u1
    (block
      name(fUninit)
      (def u)
      (set u (+:int var(k) 1:int))
      (nonnull var(u))))
  (def u2 (call-fn cl))
  (def u3
    (block
      (def cap 1:int)
      (def c2 (fn-ref c2))
      (call-fn c2)))
  (def r (core-new list 2:int))
  var(u2)
```
