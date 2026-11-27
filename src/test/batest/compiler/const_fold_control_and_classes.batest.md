# Constant Folding : Control Flow, Try/Catch/Finally, and Classes

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
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
  (def l1 (box (and true:bool var(opt))))
  (def l2 (box (or false:bool var(opt))))
  (def nn (not (block (not var(opt)))))
  (and (and (block (==:bool var(l1) true:bool)) (block (==:bool var(l2) true:bool))) var(nn))
```

```hir
;; hir -O1 fn=checkMisc
fn checkMisc (opt:obj) -> bool {pure unique}
  (def l1 (box (and true:bool var(opt))))
  (def l2 (box (or false:bool var(opt))))
  (def nn (not (not var(opt))))
  (and (and (==:bool var(l1) true:bool) (==:bool var(l2) true:bool)) var(nn))
```

```hir
;; hir -O2 fn=checkMisc
fn checkMisc (opt:obj) -> bool {pure unique}
  (def l1 (box (and true:bool var(opt))))
  (def l2 (box (or false:bool var(opt))))
  (def nn (not (not var(opt))))
  (and (and (==:bool var(l1) true:bool) (==:bool var(l2) true:bool)) var(nn))
```

## While-false pruning, INT/DOUBLE unary negate, mixed-width & false comparisons

```babble
def f = fn (): bool -> (
    var x = 0
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
  (def cmpOk (and (and (and (and (block (<:bool (convert[long] 1:int) 2:long)) (block (<:bool (convert[double] 1:long) 2.5:double))) (not (block (<:bool 3:int 2:int)))) (not (block (==:bool 3:int 4:int)))) (not (block (>=:bool 2:int 3:int)))))
  (and (and (==:bool var(x) 0:int) var(negOk)) var(cmpOk))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```hir
;; hir -O2 fn=f
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
  (def v (+:int (get-this-field x) 9:int))
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

```hir
;; hir -O2 interface=IFold
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

```hir
;; hir -O2 class=Sub
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
  (def d (+:double (convert[double] (block (if true:bool (block 5:int) (block 6:int)))) var(x)))
  (and (and (==:bool (jcall Math.sqrt var(x)) 2.0:double) (==:bool (jcall String.trim var(s)) "a")) (==:bool var(d) 9.0:double))
```

```hir
;; hir -O1 fn=check
fn check (x:double, s:obj) -> bool {nothrow unique}
  (def d (+:double 5.0:double var(x)))
  (and (and (==:bool (jcall Math.sqrt var(x)) 2.0:double) (==:bool (jcall String.trim var(s)) "a")) (==:bool var(d) 9.0:double))
```

```hir
;; hir -O2 fn=check
fn check (x:double, s:obj) -> bool {nothrow unique}
  (def d (+:double 5.0:double var(x)))
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

```hir
;; hir -O2 fn=trimmed
fn trimmed () -> obj {nothrow unique}
  "babble"
```

## Canonical HIR golden : constant if and dead while-false pruning

```babble
def branch = fn (): int -> ( if 2 < 3 then (42) else (99) )
def deadLoop = fn (): int -> ( var x = 7 ; while false then (x = 99) ; x )
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

```hir
;; hir -O2 fn=deadLoop
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

```hir
;; hir -O2 fn=run
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
    var s = 0
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

```hir
;; hir -O2 fn=run
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
  (def a (+:double (convert[double] (block (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))) var(x)))
  (def b (if true:bool (widen[double] (block var(p))) (block 1.5:double)))
  (def c (if true:bool (widen[double] (block (+:int var(p) 1:int))) (block 1.5:double)))
  (+:double (+:double var(a) var(b)) var(c))
```

```hir
;; hir -O1 fn=widenIf
fn widenIf (p:int, x:double) -> double {nothrow unique}
  (def a (+:double (convert[double] (+:int var(p) 1:int)) var(x)))
  (def b (widen[double] var(p)))
  (def c (widen[double] (+:int var(p) 1:int)))
  (+:double (+:double var(a) var(b)) var(c))
```

```hir
;; hir -O2 fn=widenIf
fn widenIf (p:int, x:double) -> double {nothrow unique}
  (block
    (def $cse0 (+:int var(p) 1:int))
    (def a (+:double (convert[double] var($cse0)) var(x)))
    (def b (widen[double] var(p)))
    (def c (widen[double] var($cse0)))
    (+:double (+:double var(a) var(b)) var(c)))
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

```hir
;; hir -O2 fn=bigConcat
fn bigConcat () -> int {nothrow unique}
  (def s9 (+:obj "0123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901" "01234567890123456789012345678901"))
  (jcall String.length var(s9))
```
