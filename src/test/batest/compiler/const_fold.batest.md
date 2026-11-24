# Constant folding, local constant propagation, widening conversions, if-pruning, and foldable Java calls

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
```

## Case 1

```babble
def f = fn (): bool -> (
    def b1 = true ; def b2 = false
    ((b1 == b2) or (b1 != b2)) and (b1 == b1) and not (b1 != b1) and
    ('a' < 'b') and ('a' <= 'a') and ('b' > 'a') and ('b' >= 'b') and ('a' == 'a') and ('a' != 'b') and
    not (false and b1) and (true and b1) and (true or b2) and (false or b1) and (not false)
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (def b1 true:bool)
contains (<:bool 'a' 'b')
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Case 2

```babble
def f = fn (): bool -> (
    (-5 < 2) and (2 <= 2) and not (3 <= 2) and (3 > 2) and (3 >= 3) and (4 == 4) and (4 != 5) and not (4 != 4) and
    (-5L < 2L) and (2L <= 2L) and (3L > 2L) and (3L >= 3L) and (4L == 4L) and (4L != 5L) and
    (byte(1) < byte(2)) and (short(1) < short(2))
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (<:bool -5:int 2:int)
contains (<:bool -5:long 2:long)
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (and (<:bool (call name(byte) 1:int) (call name(byte) 2:int)) (<:bool (call name(short) 1:int) (call name(short) 2:int)))
```

## Case 3

```babble
def f = fn (): bool -> (
    (-1.5f < 2.0f) and (2.0f <= 2.0f) and (3.0f > 2.0f) and (3.0f >= 3.0f) and (4.0f == 4.0f) and (4.0f != 5.0f) and
    (-2.5 < 1.5) and (1.5 <= 1.5) and (3.5 > 1.5) and (3.5 >= 3.5) and (4.5 == 4.5) and (4.5 != 5.5)
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (<:bool -1.5:float 2.0:float)
contains (<:bool -2.5:double 1.5:double)
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Case 4

```babble
def f = fn (): bool -> (
    ((10 + 2 - 4) * 3 == 24) and ((10L + 2L - 4L) * 3L == 24L) and
    (10 / 2 == 5) and (10L / 2L == 5L) and
    ((1.5f + 2.5f - 1.0f) * 2.0f / 3.0f == 2.0f) and
    ((1.5 + 2.5 - 1.0) * 2.0 / 3.0 == 2.0) and
    (-(1.5f + 2.5f) == -4.0f) and (-(1L + 2L) == -3L)
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (+:int 10:int 2:int)
contains (/:double 10:int 2:int)
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Local constant propagation across primitive widening chains

```babble
def f = fn (): bool -> (
    def bConst: byte = byte(3)
    def sConst: short = bConst
    def iConst: int = sConst
    def lConst: long = iConst
    def fConst: float = 2.5f
    def dConst: double = fConst
    lConst == 3L and dConst == 2.5
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst var(bConst))
  (def iConst var(sConst))
  (def lConst var(iConst))
  (def fConst 2.5:float)
  (def dConst var(fConst))
  (and (==:bool var(lConst) 3:long) (==:bool var(dConst) 2.5:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst var(bConst))
  (def iConst var(sConst))
  (def lConst var(iConst))
  (and (==:bool var(lConst) 3:long) true:bool)
```

## Direct literal widening conversions, string, and null constants

```babble
def f = fn (): bool -> (
    def bs: short = byte(3)
    def sl: long = short(4)
    def il: long = 5
    def ifl: float = 6
    def id: double = 7
    def kStr: string = "hi"
    def kNull: string? = null
    bs == short(3) and sl == 4L and il == 5L and ifl == 6.0f and id == 7.0 and kStr == "hi" and kNull == null
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (def il 5:long)
contains (def kStr "hi")
contains (def kNull null)
```

```hir
;; hir -O1 fn=f check
!contains (def il
!contains (def kStr
!contains (def kNull
contains (==:bool "hi" "hi")
```

## Constant if-pruning and convertConstToKind widening

```babble
def f = fn (): bool -> (
  def e = if false then (1)
  def s: short = if true then (byte(7)) else (short(8))
  def i: int = if true then (short(7)) else (8)
  def l: long = if true then (9) else (10L)
  def fl: float = if false then (1.0f) else (float(4))
  def d: double = if true then (5) else (6.5)
  e == null and s == short(7) and i == 7 and l == 9L and fl == 4.0f and d == 5.0
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (def e (if false:bool
contains (def l (if true:bool
contains (def fl (if false:bool
```

```hir
;; hir -O1 fn=f check
!contains (def e
!contains (def l
contains (def fl (call name(float) 4:int))
```

## Constant if-pruning with folded arithmetic branches and multi-statement blocks

```babble
def f = fn (): bool -> (
    def wL: long = if true then (5 + 1) else (2)
    def wF: float = if true then (3 + 1) else (2)
    def wD: double = if true then (4 + 2) else (1)
    def a = if true then (def x = 2 ; x + 3) else (1)
    def b: double = if false then (1.0) else (4)
    wL == 6L and wF == 4.0f and wD == 6.0 and a == 5 and b == 4.0
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (+:int 5:int 1:int)
contains (def x 2:int)
```

```hir
;; hir -O1 fn=f check
contains (def wL (if true:bool (block 6:int) 2:long))
!contains (def a
!contains (def x
```

## Constant if-pruning with literal/folded branches and target widening

```babble
def f = fn (): bool -> (
    def wL: long = if true then (4 + 2) else (1L)
    def wF: float = if true then (3 + 2) else (1.5f)
    def wD: double = if true then (7 + 1) else (2.5)
    def wStr: string = if true then ("ok") else ("no")
    wL == 6L and wF == 5.0f and wD == 8.0 and wStr == "ok"
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (+:int 4:int 2:int)
contains (def wStr (if true:bool
```

```hir
;; hir -O1 fn=f check
contains (def wL (if true:bool (block 6:int) 1:long))
!contains (def wStr
contains (==:bool "ok" "ok")
```

## Constant if-pruning with non-constant widened branches and non-constant if

```babble
def checkIf = fn (p: int): bool -> (
    def wBl: double = if true then (p + 1) else (p + 2)
    def wArg = widenSinkD(if true then (p + 1) else (p + 2))
    def nc = if p > 0 then (1) else (2)
    wBl == 3.0 and wArg == 3.5 and nc == 1
)
assert(checkIf(2))
```

```hir
;; hir -O0 fn=checkIf check
contains (call-fn widenSinkD
contains (def nc (if (>:bool var(p) 0:int) (block 1:int) (block 2:int)))
```

```hir
;; hir -O1 fn=checkIf check
!contains (call-fn widenSinkD
contains (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
```

## Whitelisted foldable String and Math calls, plus non-folding Math.floorDiv

```babble
def f = fn (): bool -> (
    "  babble  ".trim() == "babble" and "abcd".length() == 4 and "".isEmpty() and
    ("hello" + " world" == "hello world") and
    java.lang.Math.sqrt(9.0) == 3.0 and java.lang.Math.round(2.5) == 3L and
    java.lang.Math.toDegrees(0.0) == 0.0 and java.lang.Math.floorDiv(10, 3) == 3
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (jcall String.trim "  babble  ")
contains (jcall Math.sqrt 9.0:double)
contains (jcall Math.floorDiv 10:int 3:int)
```

```hir
;; hir -O1 fn=f check
!contains String.trim
!contains Math.sqrt
contains (jcall Math.floorDiv 10:int 3:int)
```

## Whitelisted foldable Math.abs/min/max across int, long, float, and double

```babble
def f = fn (): bool -> (
    java.lang.Math.abs(-7) == 7 and java.lang.Math.abs(-9L) == 9L and
    java.lang.Math.abs(-2.5f) == 2.5f and java.lang.Math.abs(-3.5) == 3.5 and
    java.lang.Math.min(3, java.lang.Math.max(1, 2)) == 2 and
    java.lang.Math.min(4L, 9L) == 4L and java.lang.Math.max(1.5f, 2.5f) == 2.5f
)
assert(f())
```

```hir
;; hir -O0 fn=f check
count 4 (jcall Math.abs
count 2 (jcall Math.min
count 2 (jcall Math.max
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Non-bool RHS in logic, TypeApply, and SelfRecurseCall under ConstFoldPass

```babble
def checkMisc = fn (opt: int?): bool -> (
    def l1: any? = true and opt
    def l2: any? = false or opt
    def nn = not (not opt)
    (l1 == true) and (l2 == true) and nn
)
assert(checkMisc(9))
assert((idFn of string)("yo") == "yo")
assert(recCount(4) == 4)
```

```hir
;; hir -O0 fn=checkMisc
fn checkMisc (opt:obj) -> bool {pure unique}
  (def l1 (and true:bool var(opt)))
  (def l2 (or false:bool var(opt)))
  (def nn (not (block (not var(opt)))))
  (and (and (block (==:bool var(l1) true:bool)) (block (==:bool var(l2) true:bool))) var(nn))
```

```hir
;; hir -O1 fn=checkMisc
fn checkMisc (opt:obj) -> bool {pure unique}
  (def l1 (and true:bool var(opt)))
  (def l2 (or false:bool var(opt)))
  (def nn (not (not var(opt))))
  (and (and (==:bool var(l1) true:bool) (==:bool var(l2) true:bool)) var(nn))
```

## While-false pruning, INT/DOUBLE unary negate, mixed-width & false comparisons

```babble
def f = fn (): bool -> (
    def x = 0
    while false then (x += 1)
    def negOk = -(1 + 2) == -3 and -(1.5 + 2.5) == -4.0
    def cmpOk = (1 < 2L) and (1L < 2.5) and not (3 < 2) and not (3 == 4) and not (2 >= 3)
    x == 0 and negOk and cmpOk
)
assert(f())
```

```hir
;; hir -O0 fn=f check
contains (while false:bool
contains (+:int 1:int 2:int)
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Constant folding inside class body/static and interface default/static

```babble
def IFold = interface (
    def m = fn (): int -> (2 + 3 * 4)
) static (def K = 10 + 5)
def CFold = class (def x: int) implements IFold (
    def v = (1 + 2) * 3 + this.x
) static (def S = 6 * 7)
assert(CFold(1).v == 10 and CFold(1).m() == 14 and IFold.K == 15 and CFold.S == 42)
```

```hir
;; hir -O0 class=CFold
class CFold (x:int)
  (def v (+:int (*:int (block (+:int 1:int 2:int)) 3:int) (get-this-field x)))
  static:
    (def S (*:int 6:int 7:int))
```

```hir
;; hir -O1 class=CFold
class CFold (x:int)
  (def v (+:int 9:int (get-this-field x)))
  static:
    (def S 42:int)
```

```hir
;; hir -O0 interface=IFold
interface IFold
  (def m (fn-ref m))
  fn m () -> int {pure}
    (+:int 2:int (*:int 3:int 4:int))
```

```hir
;; hir -O1 interface=IFold
interface IFold
  (def m (fn-ref m))
  fn m () -> int {pure}
    14:int
```

## HirRewriter & ConstFoldPass : folded default parameter, break value, super arg, and recurse arg

```babble
def IBase = interface (def g = fn (k: int): int -> (k + 1))
def Sub = class () implements IBase (def callSup = fn (): int -> (super.g(1 + 2)))
def withDef = fn (x: int = 1 + 2): int -> (x)
def brkVal = fn (): int? -> (while true then (break 2 + 3))
assert(withDef() == 3 and brkVal() == 5 and Sub().callSup() == 4)
```

```hir
;; hir -O0 fn=withDef
fn withDef (x:int=(+:int 1:int 2:int)) -> int {nothrow idempotent monotone injective unique}
  var(x)
```

```hir
;; hir -O1 fn=withDef
fn withDef (x:int=3:int) -> int {nothrow idempotent monotone injective unique}
  var(x)
```

```hir
;; hir -O0 fn=brkVal
fn brkVal () -> obj {nothrow}
  (while true:bool
    (block (break (+:int 2:int 3:int))))
```

```hir
;; hir -O1 fn=brkVal
fn brkVal () -> obj {nothrow}
  (while true:bool
    (break 5:int))
```

```hir
;; hir -O0 class=Sub
class Sub
  (def callSup (fn-ref Sub.callSup))
  fn Sub.callSup () -> int {nothrow unique}
    (super.g (+:int 1:int 2:int))
```

```hir
;; hir -O1 class=Sub
class Sub
  (def callSup (fn-ref Sub.callSup))
  fn Sub.callSup () -> int {nothrow unique}
    (super.g 3:int)
```

## ConstFoldPass : non-constant Math/String args do not fold; widened constant if-pruning

```babble
def check = fn (x: double, s: string): bool -> (
  def d = (if true then (5) else (6)) + x
  java.lang.Math.sqrt(x) == 2.0 and s.trim() == "a" and d == 9.0
)
assert(check(4.0, " a "))
```

```hir
;; hir -O0 fn=check
fn check (x:double, s:obj) -> bool {nothrow unique}
  (def d (+:double (block (if true:bool (block 5:int) (block 6:int))) var(x)))
  (and (and (==:bool (jcall Math.sqrt var(x)) 2.0:double) (==:bool (jcall String.trim var(s)) "a")) (==:bool var(d) 9.0:double))
```

```hir
;; hir -O1 fn=check
fn check (x:double, s:obj) -> bool {nothrow unique}
  (def d (+:double 5:int var(x)))
  (and (and (==:bool (jcall Math.sqrt var(x)) 2.0:double) (==:bool (jcall String.trim var(s)) "a")) (==:bool var(d) 9.0:double))
```

## Canonical HIR golden : arithmetic, string, and whitelisted JDK folding (-O0 vs -O1)

```babble
def arith = fn (): int -> ( def k = 10 ; k * k + 2 * 3 )
def trimmed = fn (): string -> ( ("  bab" + "ble  ").trim() )
def root = fn (): double -> ( java.lang.Math.sqrt(9.0) )
assert(arith() == 106 and trimmed() == "babble" and root() == 3.0)
```

```hir
;; hir -O0 fn=arith
fn arith () -> int {nothrow unique}
  (def k 10:int)
  (+:int (*:int var(k) var(k)) (*:int 2:int 3:int))
```

```hir
;; hir -O1 fn=arith
fn arith () -> int {nothrow unique}
  106:int
```

```hir
;; hir -O0 fn=root check
contains (jcall Math.sqrt 9.0:double)
```

```hir
;; hir -O1 fn=root
fn root () -> double {nothrow unique}
  3.0:double
```

```hir
;; hir -O1 fn=trimmed
fn trimmed () -> obj {nothrow unique}
  "babble"
```

## Canonical HIR golden : constant if and dead while-false pruning

```babble
def branch = fn (): int -> ( if 2 < 3 then (42) else (99) )
def deadLoop = fn (): int -> ( def x = 7 ; while false then (x = 99) ; x )
assert(branch() == 42 and deadLoop() == 7)
```

```hir
;; hir -O0 fn=branch check
contains (if
```

```hir
;; hir -O1 fn=branch
fn branch () -> int {nothrow}
  42:int
```

```hir
;; hir -O1 fn=deadLoop check
!contains (while
contains 7:int
```

## Constant folding inside try, multiple catch clauses, and finally

```babble
def run = fn (k: int): int -> (
  try (
    if k == 0 then (throw java.lang.ArithmeticException("a"))
    else if k == 1 then (throw java.lang.IllegalStateException("b"))
    else (10 + 20)
  ) catch e: java.lang.ArithmeticException then (1 + 2)
    catch e: java.lang.IllegalStateException then (3 + 4)
    finally (discard(5 + 6))
)
assert(run(0) == 3 and run(1) == 7 and run(2) == 30)
```

```hir
;; hir -O0 fn=run check
contains (+:int 10:int 20:int)
contains (+:int 1:int 2:int)
contains (+:int 3:int 4:int)
contains (+:int 5:int 6:int)
```

```hir
;; hir -O1 fn=run check
!contains (+:int
contains 30:int
contains 3:int
contains 7:int
```

## Constant folding in catch-only and finally-only try variants plus for iterable

```babble
def run = fn (k: int, xs: list of int, ys: list of int): int -> (
    def a = try (if k == 0 then (throw exception("e")) else (k)) catch : exception then (2 + 3)
    def b = try (k) finally (def z = 4 + 5 ; discard(z))
    def s = 0
    for e in (if true then (xs) else (ys)) then (s += e)
    a + b + s
)
assert(run(0, list(1, 2), list(9)) == 8 and run(4, list(1, 2), list(9)) == 11)
```

```hir
;; hir -O0 fn=run check
contains (+:int 2:int 3:int)
contains (def z (+:int 4:int 5:int))
contains (if true:bool
```

```hir
;; hir -O1 fn=run check
contains 5:int
!contains (def z
contains (for e in var(xs)
```

## Constant if-pruning with widened non-constant Block and mixed-width branches

```babble
def widenIf = fn (p: int, x: double): double -> (
    def a = (if true then (p + 1) else (p + 2)) + x
    def b = if true then (p) else (1.5)
    def c = if true then (p + 1) else (1.5)
    a + b + c
)
assert(widenIf(2, 0.5) == 8.5)
```

```hir
;; hir -O0 fn=widenIf check
contains (def a (+:double (block (if true:bool
count 3 (if true:bool
```

```hir
;; hir -O1 fn=widenIf check
contains (def a (+:double (+:int var(p) 1:int) var(x)))
count 2 (if true:bool
```

## String concatenation exceeding MAX_FOLDED_STRING_LENGTH stays unfolded

```babble
def bigConcat = fn (): int -> (
    def s1 = "01234567890123456789012345678901"
    def s2 = s1 + s1 ; def s3 = s2 + s2 ; def s4 = s3 + s3
    def s5 = s4 + s4 ; def s6 = s5 + s5 ; def s7 = s6 + s6
    def s8 = s7 + s7 ; def s9 = s8 + s1
    s9.length()
)
assert(bigConcat() == 4128)
```

```hir
;; hir -O0 fn=bigConcat check
contains (def s2 (+:obj var(s1) var(s1)))
contains (def s8 (+:obj var(s7) var(s7)))
contains (def s9 (+:obj var(s8) var(s1)))
```

```hir
;; hir -O1 fn=bigConcat check
!contains (def s2
!contains (def s8
contains (def s9 (+:obj "
contains (jcall String.length var(s9))
```
