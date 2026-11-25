# Function Inlining, Alpha-Renaming, and Callee Scan Guards

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble
;; babble module=dce_mod.ba
export def val = 10
```

```babble
;; babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```


## Top-level, function, class body/static, and interface default/static inlining + self-recursion

```babble
def topRes = addMul(5, 2)
def ICalc = interface (
    def compute = fn (v: int): int -> (addMul(v, 2) + ICalc.BASE)
) static (def BASE = addMul(4, 1))
def Calc = class (def n: int) implements ICalc (
    def bodyVal = addMul(this.n, 1)
) static (def TAG = addMul(6, 2))
def c = Calc(4)
assert(topRes == 21 and c.bodyVal == 15 and c.compute(3) == 20 and Calc.TAG == 32 and fib(7) == 13)
```

```hir
;; hir -O0 class=Calc
class Calc (n:int)
  (def bodyVal (call-fn addMul (get-this-field n) 1:int))
  static:
    (def TAG (call-fn addMul 6:int 2:int))
```

```hir
;; hir -O1 class=Calc
class Calc (n:int)
  (def bodyVal
    (block
      (def a (get-this-field n))
      (def s (+:int var(a) 1:int))
      (def d (-:int var(a) 1:int))
      (*:int var(s) var(d))))
  static:
    (def TAG 32:int)
```

```mir
;; mir -O2 class=Calc stage=before,final
class Calc:
  (no methods)
```

## InlinePass error line-number preservation (inlined cast failure vs interpreter)

```babble
def toInt = fn (v: any?): int -> ( v as int )
def caller = fn (v: any?): int -> ( toInt(v) )
caller(null)
```

```error
Type error : expected int, got null
```

```hir
;; hir -O0 fn=caller
fn caller (v:obj) -> int {pure unique}
  (call-fn toInt var(v))
```

```hir
;; hir -O1 fn=caller
fn caller (v:obj) -> int {pure unique}
  (as var(v) int|int|)
```

## InlinePass alpha-renaming across if/else inner defs, for loops, and small while loops

```babble
def inlBlocks = fn (n: int, xs: list of int): int -> (
    def res = 0
    if n > 0 then (def inner = n * 2 ; res += inner) else (res = 1)
    for e in xs then (res += e)
    res
)
def smallWhile = fn (x: int): int -> (def i = x ; while i < 3 then (i += 1) ; i)
assert(inlBlocks(3, list(1, 2)) == 9 and inlBlocks(0, list(4)) == 5 and smallWhile(2) == 3)
```

```hir
;; hir -O0 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def inlBlocks (fn-ref inlBlocks))
  (def smallWhile (fn-ref smallWhile))
  (call name(assert) (and (and (==:bool (call-fn inlBlocks 3:int (core-new list 1:int 2:int)) 9:int) (==:bool (call-fn inlBlocks 0:int (core-new list 4:int)) 5:int)) (==:bool (call-fn smallWhile 2:int) 3:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def inlBlocks (fn-ref inlBlocks))
  (def smallWhile (fn-ref smallWhile))
  (call name(assert) (and (and (==:bool (block
            (def xs#1 (core-new list 1:int 2:int))
            (def res#1 0:int)
            (set res#1 += 6:int)
            (for e#1 in var(xs#1)
              (block
                (set res#1 += var(e#1))))
            var(res#1)) 9:int) (==:bool (block
            (def xs#2 (core-new list 4:int))
            (def res#2 0:int)
            (set res#2 1:int)
            (for e#2 in var(xs#2)
              (block
                (set res#2 += var(e#2))))
            var(res#2)) 5:int)) (==:bool (block
          (def i 2:int)
          (while (<:bool var(i) 3:int)
            (set i += 1:int))
          var(i)) 3:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## HirDcePass top-level user discard shadowing + InlinePass parameter widening and RecurseRef scan

```babble
def discard = fn io (x: int): int -> (x * 2)
def callShadow = fn io (n: int): int -> (discard(n) ; n + 1)
def widenInside = fn (x: int): double -> (x + 0.5)
def passRec = fn (n: int): int -> (if n <= 0 then (0) else (def r = recurse ; r(n - 1) + 1))
def p = 3
assert(discard(7) == 14 and callShadow(4) == 5 and widenInside(5) == 5.5 and widenInside(p) == 3.5 and passRec(3) == 3)
```

```hir
;; hir -O0,-O1 fn=callShadow
fn callShadow (n:int) -> int {io}
  (call-fn discard var(n))
  (+:int var(n) 1:int)
```

```hir
;; hir -O0 fn=passRec
fn passRec (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```hir
;; hir -O1 fn=passRec
fn passRec (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```mir
;; mir -O2 fn=passRec stage=before
fn passRec(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: any = slot_read $recurse {pure}
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = opaque.DynamicCall(v3, v5) {impure}
    v7: int = const 1
    v8: int = add v6, v7 {pure}
    br bb3(v8)
  bb2:
    v9: int = const 0
    br bb3(v9)
  bb3(v10: int):
    ret v10
```

```mir
;; mir -O2 fn=passRec
fn passRec(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: any = slot_read $recurse {pure}
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = opaque.DynamicCall(v3, v5) {impure}
    v7: int = add v6, v4 {pure}
    br bb3(v7)
  bb2:
    br bb3(v1)
  bb3(v8: int):
    ret v8
```

## InlinePass.scanCalleeBody guards : local interface and block cell capture

```babble
def smallIface = fn (x: any): int -> (def LI = interface () ; if x is LI then (0) else (1))
def smallBlkCell = fn (x: int): int -> ((def c = x + 1 ; def f = fn (): int -> (c) ; f()))
assert(smallIface(1) == 1 and smallBlkCell(4) == 5)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallIface (fn-ref smallIface))
  (def smallBlkCell (fn-ref smallBlkCell))
  (call name(assert) (and (==:bool (call-fn smallIface 1:int) 1:int) (==:bool (call-fn smallBlkCell 4:int) 5:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## InlinePass.scanCalleeBody guard : for-loop cell capture

```babble
def smallForCell = fn (xs: list of int): int -> (
    def fs = (list of (fn (): int))()
    for e in xs then (fs.add(fn (): int -> (e)))
    fs.get(0)()
)
assert(smallForCell(list(9, 10)) == 9)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallForCell (fn-ref smallForCell))
  (call name(assert) (==:bool (call-fn smallForCell (core-new list 9:int 10:int)) 9:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## InlinePass : reassigned parameter in callee (freshParam allocation) and self-recursion by name

```babble
def incMut = fn (mut x: int): int -> (x = x + 1 ; x * 2)
def selfByName = fn (n: int): int -> (if n <= 0 then (0) else (selfByName(n - 1) + 1))
def run = fn io (v: int): int -> (incMut(v) + incMut(4) + selfByName(v))
assert(run(3) == 21)
```

```hir
;; hir -O0 fn=run
fn run (v:int) -> int {io}
  (+:int (+:int (call-fn incMut var(v)) (call-fn incMut 4:int)) (call-fn selfByName var(v)))
```

```hir
;; hir -O1 fn=run
fn run (v:int) -> int {io}
  (+:int (+:int (block
        (def x#1 var(v))
        (set x#1 (+:int var(x#1) 1:int))
        (*:int var(x#1) 2:int)) (block
        (def x#2 4:int)
        (set x#2 (+:int var(x#2) 1:int))
        (*:int var(x#2) 2:int))) (call-fn selfByName var(v)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: int = const 2
    v4: int = mul v2, v3 {pure}
    v5: int = const 4
    v6: int = const 1
    v7: int = add v5, v6 {pure}
    v8: int = const 2
    v9: int = mul v7, v8 {pure}
    v10: int = add v4, v9 {pure}
    v11: any = opaque.NameRead(selfByName) {read:shared}
    v12: int = call selfByName(v0) {pure}
    v13: int = add v10, v12 {pure}
    ret v13
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: int = const 2
    v4: int = mul v2, v3 {pure}
    v5: int = const 10
    v6: int = add v4, v5 {pure}
    v7: any = opaque.NameRead(selfByName) {read:shared}
    v8: int = call selfByName(v0) {pure}
    v9: int = add v6, v8 {pure}
    ret v9
```

## InlinePass : isNoOpWidening across ranks 1/3/4/5 and alphaRename widened Const substitution

```babble
def wS = fn (x: byte): short -> (x)
def wL = fn (x: int): long -> (x + 1L)
def wF = fn (x: int): float -> (x + 1.5f)
def wD = fn (x: float): double -> (x + 0.5)
def run = fn (b: byte, i: int, f: float): bool -> (
  wS(b) == short(2) and wS(byte(3)) == short(3) and wL(i) == 5L and wL(5) == 6L and
  wF(i) == 5.5f and wF(2) == 3.5f and wD(f) == 2.0 and wD(2.5f) == 3.0
)
assert(run(byte(2), 4, 1.5f))
```

```hir
;; hir -O0 fn=run
fn run (b:byte, i:int, f:float) -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (call-fn wS var(b)) (call name(short) 2:int)) (==:bool (call-fn wS (call name(byte) 3:int)) (call name(short) 3:int))) (==:bool (call-fn wL var(i)) 5:long)) (==:bool (call-fn wL 5:int) 6:long)) (==:bool (call-fn wF var(i)) 5.5:float)) (==:bool (call-fn wF 2:int) 3.5:float)) (==:bool (call-fn wD var(f)) 2.0:double)) (==:bool (call-fn wD 2.5:float) 3.0:double))
```

```hir
;; hir -O1 fn=run
fn run (b:byte, i:int, f:float) -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (block var(b)) (call name(short) 2:int)) (==:bool (block
                    (def x (call name(byte) 3:int))
                    var(x)) (call name(short) 3:int))) (==:bool (+:long var(i) 1:long) 5:long)) true:bool) (==:bool (+:float var(i) 1.5:float) 5.5:float)) true:bool) (==:bool (+:double var(f) 0.5:double) 2.0:double)) true:bool)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: byte, v1: int, v2: float) -> bool:
  bb0:
    v3: short = convert.short v0
    v4: any = opaque.NameRead(short) {read:shared,throws}
    v5: int = const 2
    v6: short = opaque.DynamicCall(v4, v5) {impure}
    v7: bool = eq v3, v6 {pure}
    v8: bool = const false
    br_if v7, bb1, bb2(v8)
  bb1:
    v9: any = opaque.NameRead(byte) {read:shared,throws}
    v10: int = const 3
    v11: byte = opaque.DynamicCall(v9, v10) {impure}
    v12: short = convert.short v11
    v13: any = opaque.NameRead(short) {read:shared,throws}
    v14: int = const 3
    v15: short = opaque.DynamicCall(v13, v14) {impure}
    v16: bool = eq v12, v15 {pure}
    br bb2(v16)
  bb2(v17: bool):
    v18: bool = const false
    br_if v17, bb3, bb4(v18)
  bb3:
    v19: long = convert.long v1
    v20: long = const 1L
    v21: long = add v19, v20 {pure}
    v22: long = const 5L
    v23: bool = eq v21, v22 {pure}
    br bb4(v23)
  bb4(v24: bool):
    v25: bool = const false
    br_if v24, bb5, bb6(v25)
  bb5:
    v26: bool = const true
    br bb6(v26)
  bb6(v27: bool):
    v28: bool = const false
    br_if v27, bb7, bb8(v28)
  bb7:
    v29: float = convert.float v1
    v30: float = const 1.5f
    v31: float = add v29, v30 {pure}
    v32: float = const 5.5f
    v33: bool = eq v31, v32 {pure}
    br bb8(v33)
  bb8(v34: bool):
    v35: bool = const false
    br_if v34, bb9, bb10(v35)
  bb9:
    v36: bool = const true
    br bb10(v36)
  bb10(v37: bool):
    v38: bool = const false
    br_if v37, bb11, bb12(v38)
  bb11:
    v39: double = convert.double v2
    v40: double = const 0.5
    v41: double = add v39, v40 {pure}
    v42: double = const 2.0
    v43: bool = eq v41, v42 {pure}
    br bb12(v43)
  bb12(v44: bool):
    v45: bool = const false
    br_if v44, bb13, bb14(v45)
  bb13:
    v46: bool = const true
    br bb14(v46)
  bb14(v47: bool):
    ret v47
```

```mir
;; mir -O2 fn=run
fn run(v0: byte, v1: int, v2: float) -> bool:
  bb0:
    v3: short = convert.short v0
    v4: any = opaque.NameRead(short) {read:shared,throws}
    v5: int = const 2
    v6: short = opaque.DynamicCall(v4, v5) {impure}
    v7: bool = eq v3, v6 {pure}
    v8: bool = const false
    br_if v7, bb1, bb2(v8)
  bb1:
    v9: any = opaque.NameRead(byte) {read:shared,throws}
    v10: int = const 3
    v11: byte = opaque.DynamicCall(v9, v10) {impure}
    v12: short = convert.short v11
    v13: any = opaque.NameRead(short) {read:shared,throws}
    v14: short = opaque.DynamicCall(v13, v10) {impure}
    v15: bool = eq v12, v14 {pure}
    br bb2(v15)
  bb2(v16: bool):
    br_if v16, bb3, bb4(v8)
  bb3:
    v17: long = convert.long v1
    v18: long = const 1L
    v19: long = add v17, v18 {pure}
    v20: long = const 5L
    v21: bool = eq v19, v20 {pure}
    br bb4(v21)
  bb4(v22: bool):
    br_if v22, bb5, bb6(v8)
  bb5:
    v23: bool = const true
    br bb6(v23)
  bb6(v24: bool):
    br_if v24, bb7, bb8(v8)
  bb7:
    v25: float = convert.float v1
    v26: float = const 1.5f
    v27: float = add v25, v26 {pure}
    v28: float = const 5.5f
    v29: bool = eq v27, v28 {pure}
    br bb8(v29)
  bb8(v30: bool):
    br_if v30, bb9, bb10(v8)
  bb9:
    v31: bool = const true
    br bb10(v31)
  bb10(v32: bool):
    br_if v32, bb11, bb12(v8)
  bb11:
    v33: double = convert.double v2
    v34: double = const 0.5
    v35: double = add v33, v34 {pure}
    v36: double = const 2.0
    v37: bool = eq v35, v36 {pure}
    br bb12(v37)
  bb12(v38: bool):
    br_if v38, bb13, bb14(v8)
  bb13:
    v39: bool = const true
    br bb14(v39)
  bb14(v40: bool):
    ret v40
```

## InlinePass : widened single-stmt inline call and trailing-return callee

```babble
def idI = fn (x: int): int -> (x)
def idR = fn (x: int): int -> (return x)
def run = fn (p: int): double -> (
  def d: double = idI(p)
  d + double(idR(p))
)
assert(run(3) == 6.0)
```

```hir
;; hir -O0 fn=run
fn run (p:int) -> double {nothrow monotone unique}
  (def d (call-fn idI var(p)))
  (+:double var(d) (call name(double) (call-fn idR var(p))))
```

```hir
;; hir -O1 fn=run
fn run (p:int) -> double {nothrow monotone unique}
  (def d var(p))
  (+:double var(d) (call name(double) var(p)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> double:
  bb0:
    v1: double = convert.double v0
    v2: any = opaque.NameRead(double) {read:shared,throws}
    v3: double = opaque.DynamicCall(v2, v0) {impure}
    v4: double = add v1, v3 {pure}
    ret v4
```

## Canonical HIR golden : small-function inlining and dead-code elimination (-O0 vs -O1)

```babble
def caller = fn (x: int): int -> (
    def dead = addMul(x, 1)
    discard(dead)
    return addMul(x, 2)
    addMul(x, 99)
)
assert(caller(5) == 21)
```

```hir
;; hir -O0 fn=caller
fn caller (x:int) -> int {nothrow unique}
  (def dead (call-fn addMul var(x) 1:int))
  (call name(discard) var(dead))
  (return (call-fn addMul var(x) 2:int))
  (call-fn addMul var(x) 99:int)
```

```hir
;; hir -O1 fn=caller
fn caller (x:int) -> int {nothrow unique}
  (return (block
      (def s (+:int var(x) 2:int))
      (def d (-:int var(x) 2:int))
      (*:int var(s) var(d))))
```

```mir
;; mir -O2 fn=caller stage=before
fn caller(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: int = add v0, v1 {pure}
    v3: int = const 2
    v4: int = sub v0, v3 {pure}
    v5: int = mul v2, v4 {pure}
    ret v5
```

```mir
;; mir -O2 fn=caller
fn caller(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: int = add v0, v1 {pure}
    v3: int = sub v0, v1 {pure}
    v4: int = mul v2, v3 {pure}
    ret v4
```
