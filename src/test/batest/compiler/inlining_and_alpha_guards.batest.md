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
    v6: any = opaque.DynamicCall(v3, v5) {impure}
    v7: int = convert.int v6
    v8: int = const 1
    v9: int = add v7, v8 {pure}
    br bb3(v9)
  bb2:
    v10: int = const 0
    br bb3(v10)
  bb3(v11: int):
    ret v11
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
    v6: any = opaque.DynamicCall(v3, v5) {impure}
    v7: int = convert.int v6
    v8: int = add v7, v4 {pure}
    br bb3(v8)
  bb2:
    br bb3(v1)
  bb3(v9: int):
    ret v9
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
    v11: any = opaque.NameRead() {impure}
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
    v7: any = opaque.NameRead() {impure}
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
    v4: any = opaque.NameRead() {impure}
    v5: int = const 2
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: short = convert.short v6
    v8: bool = eq v3, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: any = opaque.NameRead() {impure}
    v11: int = const 3
    v12: any = opaque.DynamicCall(v10, v11) {impure}
    v13: byte = convert.byte v12
    v14: short = convert.short v13
    v15: any = opaque.NameRead() {impure}
    v16: int = const 3
    v17: any = opaque.DynamicCall(v15, v16) {impure}
    v18: short = convert.short v17
    v19: bool = eq v14, v18 {pure}
    br bb2(v19)
  bb2(v20: bool):
    v21: bool = const false
    br_if v20, bb3, bb4(v21)
  bb3:
    v22: long = convert.long v1
    v23: long = const 1L
    v24: long = add v22, v23 {pure}
    v25: long = const 5L
    v26: bool = eq v24, v25 {pure}
    br bb4(v26)
  bb4(v27: bool):
    v28: bool = const false
    br_if v27, bb5, bb6(v28)
  bb5:
    v29: bool = const true
    br bb6(v29)
  bb6(v30: bool):
    v31: bool = const false
    br_if v30, bb7, bb8(v31)
  bb7:
    v32: float = convert.float v1
    v33: float = const 1.5f
    v34: float = add v32, v33 {pure}
    v35: float = const 5.5f
    v36: bool = eq v34, v35 {pure}
    br bb8(v36)
  bb8(v37: bool):
    v38: bool = const false
    br_if v37, bb9, bb10(v38)
  bb9:
    v39: bool = const true
    br bb10(v39)
  bb10(v40: bool):
    v41: bool = const false
    br_if v40, bb11, bb12(v41)
  bb11:
    v42: double = convert.double v2
    v43: double = const 0.5
    v44: double = add v42, v43 {pure}
    v45: double = const 2.0
    v46: bool = eq v44, v45 {pure}
    br bb12(v46)
  bb12(v47: bool):
    v48: bool = const false
    br_if v47, bb13, bb14(v48)
  bb13:
    v49: bool = const true
    br bb14(v49)
  bb14(v50: bool):
    ret v50
```

```mir
;; mir -O2 fn=run
fn run(v0: byte, v1: int, v2: float) -> bool:
  bb0:
    v3: short = convert.short v0
    v4: any = opaque.NameRead() {impure}
    v5: int = const 2
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: short = convert.short v6
    v8: bool = eq v3, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: any = opaque.NameRead() {impure}
    v11: int = const 3
    v12: any = opaque.DynamicCall(v10, v11) {impure}
    v13: byte = convert.byte v12
    v14: short = convert.short v13
    v15: any = opaque.NameRead() {impure}
    v16: any = opaque.DynamicCall(v15, v11) {impure}
    v17: short = convert.short v16
    v18: bool = eq v14, v17 {pure}
    br bb2(v18)
  bb2(v19: bool):
    br_if v19, bb3, bb4(v9)
  bb3:
    v20: long = convert.long v1
    v21: long = const 1L
    v22: long = add v20, v21 {pure}
    v23: long = const 5L
    v24: bool = eq v22, v23 {pure}
    br bb4(v24)
  bb4(v25: bool):
    br_if v25, bb5, bb6(v9)
  bb5:
    v26: bool = const true
    br bb6(v26)
  bb6(v27: bool):
    br_if v27, bb7, bb8(v9)
  bb7:
    v28: float = convert.float v1
    v29: float = const 1.5f
    v30: float = add v28, v29 {pure}
    v31: float = const 5.5f
    v32: bool = eq v30, v31 {pure}
    br bb8(v32)
  bb8(v33: bool):
    br_if v33, bb9, bb10(v9)
  bb9:
    v34: bool = const true
    br bb10(v34)
  bb10(v35: bool):
    br_if v35, bb11, bb12(v9)
  bb11:
    v36: double = convert.double v2
    v37: double = const 0.5
    v38: double = add v36, v37 {pure}
    v39: double = const 2.0
    v40: bool = eq v38, v39 {pure}
    br bb12(v40)
  bb12(v41: bool):
    br_if v41, bb13, bb14(v9)
  bb13:
    v42: bool = const true
    br bb14(v42)
  bb14(v43: bool):
    ret v43
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
    v2: any = opaque.NameRead() {impure}
    v3: any = opaque.DynamicCall(v2, v0) {impure}
    v4: double = convert.double v3
    v5: double = add v1, v4 {pure}
    ret v5
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
