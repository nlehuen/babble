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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def b1 true:bool)
  (def b2 false:bool)
  (and (and (and (and (and (and (and (and (and (and (and (and (and (block (or (block (==:bool var(b1) var(b2))) (block (<>:bool var(b1) var(b2))))) (block (==:bool var(b1) var(b1)))) (not (block (<>:bool var(b1) var(b1))))) (block (<:bool 'a' 'b'))) (block (<=:bool 'a' 'a'))) (block (>:bool 'b' 'a'))) (block (>=:bool 'b' 'b'))) (block (==:bool 'a' 'a'))) (block (<>:bool 'a' 'b'))) (not (block (and false:bool var(b1))))) (block (and true:bool var(b1)))) (block (or true:bool var(b2)))) (block (or false:bool var(b1)))) (block (not false:bool)))
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
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -5:int 2:int)) (block (<=:bool 2:int 2:int))) (not (block (<=:bool 3:int 2:int)))) (block (>:bool 3:int 2:int))) (block (>=:bool 3:int 3:int))) (block (==:bool 4:int 4:int))) (block (<>:bool 4:int 5:int))) (not (block (<>:bool 4:int 4:int)))) (block (<:bool -5:long 2:long))) (block (<=:bool 2:long 2:long))) (block (>:bool 3:long 2:long))) (block (>=:bool 3:long 3:long))) (block (==:bool 4:long 4:long))) (block (<>:bool 4:long 5:long))) (block (<:bool (call name(byte) 1:int) (call name(byte) 2:int)))) (block (<:bool (call name(short) 1:int) (call name(short) 2:int))))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -1.5:float 2.0:float)) (block (<=:bool 2.0:float 2.0:float))) (block (>:bool 3.0:float 2.0:float))) (block (>=:bool 3.0:float 3.0:float))) (block (==:bool 4.0:float 4.0:float))) (block (<>:bool 4.0:float 5.0:float))) (block (<:bool -2.5:double 1.5:double))) (block (<=:bool 1.5:double 1.5:double))) (block (>:bool 3.5:double 1.5:double))) (block (>=:bool 3.5:double 3.5:double))) (block (==:bool 4.5:double 4.5:double))) (block (<>:bool 4.5:double 5.5:double)))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (block (==:bool (*:int (block (-:int (+:int 10:int 2:int) 4:int)) 3:int) 24:int)) (block (==:bool (*:long (block (-:long (+:long 10:long 2:long) 4:long)) 3:long) 24:long))) (block (==:bool (/:double 10:int 2:int) 5:int))) (block (==:bool (/:double 10:long 2:long) 5:long))) (block (==:bool (/:float (*:float (block (-:float (+:float 1.5:float 2.5:float) 1.0:float)) 2.0:float) 3.0:float) 2.0:float))) (block (==:bool (/:double (*:double (block (-:double (+:double 1.5:double 2.5:double) 1.0:double)) 2.0:double) 3.0:double) 2.0:double))) (block (==:bool (neg (block (+:float 1.5:float 2.5:float))) -4.0:float))) (block (==:bool (neg (block (+:long 1:long 2:long))) -3:long)))
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
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bs (call name(byte) 3:int))
  (def sl (call name(short) 4:int))
  (def il 5:long)
  (def ifl 6:int)
  (def id 7:int)
  (def kStr "hi")
  (def kNull null)
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) (==:bool var(sl) 4:long)) (==:bool var(il) 5:long)) (==:bool var(ifl) 6.0:float)) (==:bool var(id) 7.0:double)) (==:bool var(kStr) "hi")) (==:bool var(kNull) null))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bs (call name(byte) 3:int))
  (def sl (call name(short) 4:int))
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) (==:bool var(sl) 4:long)) true:bool) true:bool) true:bool) (==:bool "hi" "hi")) (==:bool null null))
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
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def e (if false:bool (block 1:int)))
  (def s (if true:bool (block (call name(byte) 7:int)) (block (call name(short) 8:int))))
  (def i (if true:bool (block (call name(short) 7:int)) (block 8:int)))
  (def l (if true:bool (block 9:long) (block 10:long)))
  (def fl (if false:bool (block 1.0:float) (block (call name(float) 4:int))))
  (def d (if true:bool (block 5:int) (block 6.5:double)))
  (and (and (and (and (and (==:bool var(e) null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) (==:bool var(l) 9:long)) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def s (if true:bool (block (call name(byte) 7:int)) (call name(short) 8:int)))
  (def i (if true:bool (block (call name(short) 7:int)) 8:int))
  (def fl (call name(float) 4:int))
  (def d (if true:bool (block 5:int) 6.5:double))
  (and (and (and (and (and (==:bool null null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) true:bool) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block (+:int 5:int 1:int)) (block 2:long)))
  (def wF (if true:bool (block (+:int 3:int 1:int)) (block 2:int)))
  (def wD (if true:bool (block (+:int 4:int 2:int)) (block 1:int)))
  (def a
    (if true:bool
      (block
        (def x 2:int)
        (+:int var(x) 3:int))
      (block 1:int)))
  (def b (if false:bool (block 1.0:double) (block 4:int)))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) (==:bool var(a) 5:int)) (==:bool var(b) 4.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block 6:int) 2:long))
  (def wF (if true:bool (block 4:int) (block 2:int)))
  (def wD (if true:bool (block 6:int) (block 1:int)))
  (def b (if false:bool 1.0:double (block 4:int)))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) true:bool) (==:bool var(b) 4.0:double))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block (+:int 4:int 2:int)) (block 1:long)))
  (def wF (if true:bool (block (+:int 3:int 2:int)) (block 1.5:float)))
  (def wD (if true:bool (block (+:int 7:int 1:int)) (block 2.5:double)))
  (def wStr (if true:bool (block "ok") (block "no")))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) (==:bool var(wStr) "ok"))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block 6:int) 1:long))
  (def wF (if true:bool (block 5:int) 1.5:float))
  (def wD (if true:bool (block 8:int) 2.5:double))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) (==:bool "ok" "ok"))
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
;; hir -O0 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
  (def wArg (call-fn widenSinkD (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int)))))
  (def nc (if (>:bool var(p) 0:int) (block 1:int) (block 2:int)))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```hir
;; hir -O1 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
  (def wArg
    (block
      (def v (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
      (+:double var(v) 0.5:double)))
  (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
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
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (jcall String.trim "  babble  ") "babble") (==:bool (jcall String.length "abcd") 4:int)) (jcall String.isEmpty "")) (block (==:bool (+:obj "hello" " world") "hello world"))) (==:bool (jcall Math.sqrt 9.0:double) 3.0:double)) (==:bool (jcall Math.round 2.5:double) 3:long)) (==:bool (jcall Math.toDegrees 0.0:double) 0.0:double)) (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool "babble" "babble") true:bool) true:bool) (==:bool "hello world" "hello world")) true:bool) true:bool) true:bool) (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (==:bool (jcall Math.abs -7:int) 7:int) (==:bool (jcall Math.abs -9:long) 9:long)) (==:bool (jcall Math.abs -2.5:float) 2.5:float)) (==:bool (jcall Math.abs -3.5:double) 3.5:double)) (==:bool (jcall Math.min 3:int (jcall Math.max 1:int 2:int)) 2:int)) (==:bool (jcall Math.min 4:long 9:long) 4:long)) (==:bool (jcall Math.max 1.5:float 2.5:float) 2.5:float))
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
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def x 0:int)
  (while false:bool
    (block (set x += 1:int)))
  (def negOk (and (==:bool (neg (block (+:int 1:int 2:int))) -3:int) (==:bool (neg (block (+:double 1.5:double 2.5:double))) -4.0:double)))
  (def cmpOk (and (and (and (and (block (<:bool 1:int 2:long)) (block (<:bool 1:long 2.5:double))) (not (block (<:bool 3:int 2:int)))) (not (block (==:bool 3:int 4:int)))) (not (block (>=:bool 2:int 3:int)))))
  (and (and (==:bool var(x) 0:int) var(negOk)) var(cmpOk))
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
;; hir -O0 fn=root
fn root () -> double {nothrow unique}
  (jcall Math.sqrt 9.0:double)
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
;; hir -O0 fn=branch
fn branch () -> int {nothrow}
  (if (<:bool 2:int 3:int) (block 42:int) (block 99:int))
```

```hir
;; hir -O1 fn=branch
fn branch () -> int {nothrow}
  42:int
```

```hir
;; hir -O1 fn=deadLoop
fn deadLoop () -> int {nothrow unique}
  7:int
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
;; hir -O0 fn=run
fn run (k:int) -> int {pure}
  (try
    (block
      (if (==:bool var(k) 0:int) (block (throw (jnew ArithmeticException "a"))) (if (==:bool var(k) 1:int) (block (throw (jnew IllegalStateException "b"))) (block (+:int 10:int 20:int)))))
    (catch e#1:java|java.lang.ArithmeticException|java.lang.ArithmeticException
      (+:int 1:int 2:int))
    (catch e#2:java|java.lang.IllegalStateException|java.lang.IllegalStateException
      (+:int 3:int 4:int))
    (finally
      (call name(discard) (+:int 5:int 6:int))))
```

```hir
;; hir -O1 fn=run
fn run (k:int) -> int {pure}
  (try
    (block
      (if (==:bool var(k) 0:int) (throw (jnew ArithmeticException "a")) (if (==:bool var(k) 1:int) (throw (jnew IllegalStateException "b")) 30:int)))
    (catch e#1:java|java.lang.ArithmeticException|java.lang.ArithmeticException
      3:int)
    (catch e#2:java|java.lang.IllegalStateException|java.lang.IllegalStateException
      7:int)
    (finally
      null))
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
;; hir -O0 fn=run
fn run (k:int, xs:obj, ys:obj) -> int {pure unique}
  (def a
    (try
      (block
        (if (==:bool var(k) 0:int) (block (throw (jnew BabbleException "e"))) (block var(k))))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        (+:int 2:int 3:int))))
  (def b
    (try
      (block
        var(k))
      (finally
        (def z (+:int 4:int 5:int))
        (call name(discard) var(z)))))
  (def s 0:int)
  (for e in (block (if true:bool (block var(xs)) (block var(ys))))
    (block
      (set s += var(e))))
  (+:int (+:int var(a) var(b)) var(s))
```

```hir
;; hir -O1 fn=run
fn run (k:int, xs:obj, ys:obj) -> int {pure unique}
  (def a
    (try
      (block
        (if (==:bool var(k) 0:int) (throw (jnew BabbleException "e")) var(k)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        5:int)))
  (def b
    (try
      (block
        var(k))
      (finally
        null)))
  (def s 0:int)
  (for e in var(xs)
    (block
      (set s += var(e))))
  (+:int (+:int var(a) var(b)) var(s))
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
;; hir -O0 fn=widenIf
fn widenIf (p:int, x:double) -> double {nothrow unique}
  (def a (+:double (block (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int)))) var(x)))
  (def b (if true:bool (block var(p)) (block 1.5:double)))
  (def c (if true:bool (block (+:int var(p) 1:int)) (block 1.5:double)))
  (+:double (+:double var(a) var(b)) var(c))
```

```hir
;; hir -O1 fn=widenIf
fn widenIf (p:int, x:double) -> double {nothrow unique}
  (def a (+:double (+:int var(p) 1:int) var(x)))
  (def b (if true:bool (block var(p)) 1.5:double))
  (def c (if true:bool (block (+:int var(p) 1:int)) 1.5:double))
  (+:double (+:double var(a) var(b)) var(c))
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
;; hir -O0 fn=bigConcat
fn bigConcat () -> int {nothrow unique}
  (def s1 "01234567890123456789012345678901")
  (def s2 (+:obj var(s1) var(s1)))
  (def s3 (+:obj var(s2) var(s2)))
  (def s4 (+:obj var(s3) var(s3)))
  (def s5 (+:obj var(s4) var(s4)))
  (def s6 (+:obj var(s5) var(s5)))
  (def s7 (+:obj var(s6) var(s6)))
  (def s8 (+:obj var(s7) var(s7)))
  (def s9 (+:obj var(s8) var(s1)))
  (jcall String.length var(s9))
```

```hir
;; hir -O1 fn=bigConcat
fn bigConcat () -> int {nothrow unique}
  (def s9 (+:obj "0123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901" "01234567890123456789012345678901"))
  (jcall String.length var(s9))
```
