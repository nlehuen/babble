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

```mir
;; mir -O2 fn=checkMisc stage=before
fn checkMisc(v0: any) -> bool:
  bb0:
    v1: bool = const true
    v2: bool = const false
    br_if v1, bb1, bb2(v2)
  bb1:
    v3: bool = not v0 {pure}
    v4: bool = not v3 {pure}
    br bb2(v4)
  bb2(v5: bool):
    v6: any = convert.any v5
    v7: bool = const false
    v8: bool = const true
    br_if v7, bb4(v8), bb3
  bb3:
    v9: bool = not v0 {pure}
    v10: bool = not v9 {pure}
    br bb4(v10)
  bb4(v11: bool):
    v12: any = convert.any v11
    v13: bool = not v0 {pure}
    v14: bool = not v13 {pure}
    v15: bool = const true
    v16: any = convert.any v15
    v17: any = eq v6, v16 {pure,throws}
    v18: bool = not v17 {pure}
    v19: bool = not v18 {pure}
    v20: bool = const false
    br_if v19, bb5, bb6(v20)
  bb5:
    v21: bool = const true
    v22: any = convert.any v21
    v23: any = eq v12, v22 {pure,throws}
    v24: bool = not v23 {pure}
    v25: bool = not v24 {pure}
    br bb6(v25)
  bb6(v26: bool):
    v27: bool = const false
    br_if v26, bb7, bb8(v27)
  bb7:
    br bb8(v14)
  bb8(v28: bool):
    ret v28
```

```mir
;; mir -O2 fn=checkMisc
fn checkMisc(v0: any) -> bool:
  bb0:
    v1: bool = const false
    br bb1
  bb1:
    v2: bool = not v0 {pure}
    v3: bool = not v2 {pure}
    br bb2(v3)
  bb2(v4: bool):
    v5: any = convert.any v4
    br bb3
  bb3:
    br bb4(v3)
  bb4(v6: bool):
    v7: any = convert.any v6
    v8: any = const true
    v9: any = eq v5, v8 {pure,throws}
    v10: bool = not v9 {pure}
    v11: bool = not v10 {pure}
    br_if v11, bb5, bb6(v1)
  bb5:
    v12: any = eq v7, v8 {pure,throws}
    v13: bool = not v12 {pure}
    v14: bool = not v13 {pure}
    br bb6(v14)
  bb6(v15: bool):
    br_if v15, bb7, bb8(v1)
  bb7:
    br bb8(v3)
  bb8(v16: bool):
    ret v16
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

```mir
;; mir -O2 fn=f stage=before,final
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
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

```mir
;; mir -O2 interface=IFold stage=before,final
interface IFold:
  fn m() -> int:
    bb0:
      v0: int = const 14
      ret v0
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

```mir
;; mir -O2 class=Sub stage=before,final
class Sub:
  fn Sub.callSup() -> int:
    bb0:
      v0: int = const 3
      v1: any = opaque.SuperCall(g, v0) {impure}
      v2: int = convert.int v1
      ret v2
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

```mir
;; mir -O2 fn=check stage=before
fn check(v0: double, v1: any) -> bool:
  bb0:
    v2: double = const 5.0
    v3: double = add v2, v0 {pure}
    v4: double = call_java Math.sqrt(v0) {pure}
    v5: double = const 2.0
    v6: bool = eq v4, v5 {pure}
    v7: bool = const false
    br_if v6, bb1, bb2(v7)
  bb1:
    v8: any = call_java v1.trim() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: any = const "a"
    v11: any = eq v9, v10 {pure}
    v12: bool = not v11 {pure}
    v13: bool = not v12 {pure}
    br bb2(v13)
  bb2(v14: bool):
    v15: bool = const false
    br_if v14, bb3, bb4(v15)
  bb3:
    v16: double = const 9.0
    v17: bool = eq v3, v16 {pure}
    br bb4(v17)
  bb4(v18: bool):
    ret v18
```

```mir
;; mir -O2 fn=check
fn check(v0: double, v1: any) -> bool:
  bb0:
    v2: double = const 5.0
    v3: double = add v2, v0 {pure}
    v4: double = call_java Math.sqrt(v0) {pure}
    v5: double = const 2.0
    v6: bool = eq v4, v5 {pure}
    v7: bool = const false
    br_if v6, bb1, bb2(v7)
  bb1:
    v8: any = call_java v1.trim() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: any = const "a"
    v11: any = eq v9, v10 {pure}
    v12: bool = not v11 {pure}
    v13: bool = not v12 {pure}
    br bb2(v13)
  bb2(v14: bool):
    br_if v14, bb3, bb4(v7)
  bb3:
    v15: double = const 9.0
    v16: bool = eq v3, v15 {pure}
    br bb4(v16)
  bb4(v17: bool):
    ret v17
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

```mir
;; mir -O2 fn=trimmed stage=before,final
fn trimmed() -> any:
  bb0:
    v0: any = const "babble"
    ret v0
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

```mir
;; mir -O2 fn=deadLoop stage=before,final
fn deadLoop() -> int:
  bb0:
    v0: int = const 7
    ret v0
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

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    br bb1
  bb1 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    v1: int = const 0
    v2: bool = eq v0, v1 {pure}
    br_if v2, bb8, bb2
  bb2 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    v3: int = const 1
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb7, bb3
  bb3 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    v5: int = const 30
    br bb4
  bb4 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    br bb5
  bb5 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    br bb6
  bb6:
    v6: any = const null
    br bb17(v5)
  bb7 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    v7: any = const "b"
    v8: any = new_java IllegalStateException(v7) {pure!}
    rethrow v8
  bb8 [unwind: ArithmeticException -> bb12, IllegalStateException -> bb9, * -> bb14]:
    v9: any = const "a"
    v10: any = new_java ArithmeticException(v9) {pure!}
    rethrow v10
  bb9(v11: any) [handler]:
    br bb10
  bb10 [unwind: * -> bb14]:
    v12: int = const 7
    br bb11
  bb11:
    v13: any = const null
    br bb17(v12)
  bb12(v14: any) [handler]:
    br bb13
  bb13 [unwind: * -> bb14]:
    v15: int = const 3
    br bb16
  bb14(v16: any) [handler]:
    br bb15
  bb15:
    v17: any = const null
    rethrow v16
  bb16:
    v18: any = const null
    br bb17(v15)
  bb17(v19: int):
    ret v19
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    br bb1
  bb1 [unwind: ArithmeticException -> bb10, IllegalStateException -> bb7, * -> bb12]:
    v1: int = const 0
    v2: bool = eq v0, v1 {pure}
    br_if v2, bb6, bb2
  bb2 [unwind: ArithmeticException -> bb10, IllegalStateException -> bb7, * -> bb12]:
    v3: int = const 1
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb5, bb3
  bb3 [unwind: ArithmeticException -> bb10, IllegalStateException -> bb7, * -> bb12]:
    v5: int = const 30
    br bb4
  bb4:
    br bb15(v5)
  bb5 [unwind: ArithmeticException -> bb10, IllegalStateException -> bb7, * -> bb12]:
    v6: any = const "b"
    v7: any = new_java IllegalStateException(v6) {pure!}
    rethrow v7
  bb6 [unwind: ArithmeticException -> bb10, IllegalStateException -> bb7, * -> bb12]:
    v8: any = const "a"
    v9: any = new_java ArithmeticException(v8) {pure!}
    rethrow v9
  bb7(v10: any) [handler]:
    br bb8
  bb8 [unwind: * -> bb12]:
    v11: int = const 7
    br bb9
  bb9:
    br bb15(v11)
  bb10(v12: any) [handler]:
    br bb11
  bb11 [unwind: * -> bb12]:
    v13: int = const 3
    br bb14
  bb12(v14: any) [handler]:
    br bb13
  bb13:
    rethrow v14
  bb14:
    br bb15(v13)
  bb15(v15: int):
    ret v15
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

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any, v2: any) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb5]:
    v3: int = const 0
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb4, bb2
  bb2 [unwind: BabbleException -> bb5]:
    br bb3
  bb3 [unwind: BabbleException -> bb5]:
    br bb7(v0)
  bb4 [unwind: BabbleException -> bb5]:
    v5: any = const "e"
    v6: any = new_java BabbleException(v5) {pure!}
    rethrow v6
  bb5(v7: any) [handler]:
    br bb6
  bb6:
    v8: int = const 5
    br bb7(v8)
  bb7(v9: int):
    br bb8
  bb8 [unwind: * -> bb9]:
    br bb11
  bb9(v10: any) [handler]:
    br bb10
  bb10:
    v11: any = const null
    rethrow v10
  bb11:
    v12: any = const null
    br bb12
  bb12:
    v13: int = const 0
    v14: any = for_init.java_iterable v1
    v15: any = const null
    br bb13(v15, v13)
  bb13(v16: any, v17: int):
    v18: bool = for_has_next v14
    br_if v18, bb15, bb14
  bb14:
    v19: int = add v9, v0 {pure}
    v20: int = add v19, v17 {pure}
    ret v20
  bb15:
    v21: any = for_next v14
    v22: any = checkcast v21 <int|int|>
    v23: int = convert.int v22
    v24: int = add v17, v23 {pure}
    v25: any = convert.any v24
    br bb13(v25, v24)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any, v2: any) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb4]:
    v3: int = const 0
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb3, bb2
  bb2 [unwind: BabbleException -> bb4]:
    br bb6(v0)
  bb3 [unwind: BabbleException -> bb4]:
    v5: any = const "e"
    v6: any = new_java BabbleException(v5) {pure!}
    rethrow v6
  bb4(v7: any) [handler]:
    br bb5
  bb5:
    v8: int = const 5
    br bb6(v8)
  bb6(v9: int):
    br bb7
  bb7:
    v10: int = const 0
    v11: any = for_init.java_iterable v1
    br bb8(v10)
  bb8(v12: int):
    v13: bool = for_has_next v11
    br_if v13, bb10, bb9
  bb9:
    v14: int = add v9, v0 {pure}
    v15: int = add v14, v12 {pure}
    ret v15
  bb10:
    v16: any = for_next v11
    v17: any = checkcast v16 <int|int|>
    v18: int = convert.int v17
    v19: int = add v12, v18 {pure}
    br bb8(v19)
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

```mir
;; mir -O2 fn=widenIf stage=before
fn widenIf(v0: int, v1: double) -> double:
  bb0:
    v2: int = const 1
    v3: int = add v0, v2 {pure}
    v4: double = convert.double v3
    v5: double = add v4, v1 {pure}
    v6: double = convert.double v0
    v7: int = const 1
    v8: int = add v0, v7 {pure}
    v9: double = convert.double v8
    v10: double = add v5, v6 {pure}
    v11: double = add v10, v9 {pure}
    ret v11
```

```mir
;; mir -O2 fn=widenIf
fn widenIf(v0: int, v1: double) -> double:
  bb0:
    v2: int = const 1
    v3: int = add v0, v2 {pure}
    v4: double = convert.double v3
    v5: double = add v4, v1 {pure}
    v6: double = convert.double v0
    v7: double = add v5, v6 {pure}
    v8: double = add v7, v4 {pure}
    ret v8
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

```mir
;; mir -O2 fn=bigConcat stage=before
fn bigConcat() -> int:
  bb0:
    v0: any = const "0123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901"
    v1: any = const "01234567890123456789012345678901"
    v2: any = strcat v0, v1 {pure}
    v3: int = call_java v2.length() {pure,throws}
    ret v3
```

```mir
;; mir -O2 fn=bigConcat
fn bigConcat() -> int:
  bb0:
    v0: any = const "012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901012345678901234567890123456789010123456789012345678901234567890101234567890123456789012345678901"
    v1: int = call_java v0.length() {pure,throws}
    ret v1
```
