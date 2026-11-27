# Unified HIR -O2 GVN/CSE and Alias-Aware RLE (BIP039)

Tests `HirAeGraphPass` (dominator-scoped CSE, commutative operand canonicalization,
idempotent call collapsing, `ArrayOp.SIZE` CSE, exception-handler dominance safety,
store-to-load forwarding, and disjoint `unique`/`frozen` alias preservation).

## GVN/CSE eliminates redundant commutative arithmetic and dominator-scoped subexpressions

```babble
def cseComm = fn (a: int, b: int, flag: bool): int -> (
    def x = a + b
    def y = b + a
    if flag then (x + y + (a * b) + (b * a)) else (x + (b + a))
)
assert(cseComm(3, 4, true) == 38 and cseComm(3, 4, false) == 14)
```

```hir
;; hir -O0 fn=cseComm
fn cseComm (a:int, b:int, flag:bool) -> int {nothrow}
  (def x (+:int var(a) var(b)))
  (def y (+:int var(b) var(a)))
  (if var(flag) (block (+:int (+:int (+:int var(x) var(y)) (block (*:int var(a) var(b)))) (block (*:int var(b) var(a))))) (block (+:int var(x) (block (+:int var(b) var(a))))))
```

```hir
;; hir -O1 fn=cseComm
fn cseComm (a:int, b:int, flag:bool) -> int {nothrow}
  (def x (+:int var(a) var(b)))
  (def y (+:int var(b) var(a)))
  (if var(flag) (+:int (+:int (+:int var(x) var(y)) (*:int var(a) var(b))) (*:int var(b) var(a))) (+:int var(x) (+:int var(b) var(a))))
```

```hir
;; hir -O2 fn=cseComm
fn cseComm (a:int, b:int, flag:bool) -> int {nothrow}
  (def x (+:int var(a) var(b)))
  (if var(flag)
    (block
      (def $cse0 (*:int var(a) var(b)))
      (+:int (+:int (+:int var(x) var(x)) var($cse0)) var($cse0)))
    (+:int var(x) var(x)))
```

## GVN collapses idempotent pure calls and ArrayLen

```babble
def absTwice = fn (x: double, xs: array of int): double -> (
    def a = java.lang.Math.abs(x)
    def b = java.lang.Math.abs(a)
    b + xs.size() + xs.size()
)
assert(absTwice(-3.5, (array of int).of(1, 2, 3)) == 9.5)
```

```hir
;; hir -O0 fn=absTwice
fn absTwice (x:double, xs:obj) -> double {nothrow unique}
  (def a (jcall Math.abs var(x)))
  (def b (jcall Math.abs var(a)))
  (+:double (+:double var(b) (convert[double] (array-op SIZE var(xs)))) (convert[double] (array-op SIZE var(xs))))
```

```hir
;; hir -O1 fn=absTwice
fn absTwice (x:double, xs:obj) -> double {nothrow unique}
  (def a (jcall Math.abs var(x)))
  (def b (jcall Math.abs var(a)))
  (+:double (+:double var(b) (convert[double] (array-op SIZE var(xs)))) (convert[double] (array-op SIZE var(xs))))
```

```hir
;; hir -O2 fn=absTwice
fn absTwice (x:double, xs:obj) -> double {nothrow unique}
  (def a (jcall Math.abs var(x)))
  (block
    (def $cse0 (array-op SIZE var(xs)))
    (def $cse1 (convert[double] var($cse0)))
    (+:double (+:double var(a) var($cse1)) var($cse1)))
```

## AliasRLE forwards StoreField to LoadField and preserves frozen reads across writes

```babble
def Box = class (v0: int) (
    var v = v0
    def setAndRead = fn mut (nv: int): int -> (
        this.v = nv
        this.v + this.v
    )
)
def Pt = record (x: int) ()
def frozenAcrossWrite = fn (p: Pt, mut b: Box, k: int): int -> (
    def a = p.x
    b.setAndRead(k)
    a + p.x
)
def bx = Box(1)
assert(bx.setAndRead(7) == 14 and frozenAcrossWrite(Pt(5), bx, 9) == 10)
```

```hir
;; hir -O0 fn=Box.setAndRead
fn Box.setAndRead (nv:int) -> int {nothrow mut unique}
  (set-this-field v var(nv))
  (+:int (get-this-field v) (get-this-field v))
```

```hir
;; hir -O1 fn=Box.setAndRead
fn Box.setAndRead (nv:int) -> int {nothrow mut unique}
  (set-this-field v var(nv))
  (+:int (get-this-field v) (get-this-field v))
```

```hir
;; hir -O2 fn=Box.setAndRead
fn Box.setAndRead (nv:int) -> int {nothrow mut unique}
  (set-this-field v var(nv))
  (+:int var(nv) var(nv))
```

```hir
;; hir -O2 fn=frozenAcrossWrite
fn frozenAcrossWrite (p:obj, b:obj, k:int) -> int {nothrow unique}
  (def a (get-this-field x))
  (block
    (set-this-field v var(k))
    (+:int var(k) var(k)))
  (+:int var(a) var(a))
```

## AliasRLE preserves reads of one unique parameter across writes to another unique parameter

```babble
def Cell = class (v0: int) (var v = v0)
def disjointUnique = fn (unique a: Cell, unique b: Cell): int -> (
    def x = a.v
    b.v = 99
    x + a.v + b.v
)
assert(disjointUnique(Cell(4), Cell(1)) == 107)
```

```hir
;; hir -O0 fn=disjointUnique
fn disjointUnique (a:obj, b:obj) -> int {nothrow unique}
  (def x (get-this-field v))
  (set-field var(b).v 99:int)
  (+:int (+:int var(x) (get-this-field v)) (get-this-field v))
```

```hir
;; hir -O1 fn=disjointUnique
fn disjointUnique (a:obj, b:obj) -> int {nothrow unique}
  (def x (get-this-field v))
  (set-field var(b).v 99:int)
  (+:int (+:int var(x) (get-this-field v)) (get-this-field v))
```

```hir
;; hir -O2 fn=disjointUnique
fn disjointUnique (a:obj, b:obj) -> int {nothrow unique}
  (def x (get-this-field v))
  (set-field var(b).v 99:int)
  (+:int (+:int var(x) var(x)) (get-this-field v))
```

## GVN collapses commutative Math.max and idempotent non-inlined Babble calls

```babble
def clamp0 = fn (x: int): int -> (
    var r = x
    if r < 0 then (r = 0)
    if r > 10 then (r = 10)
    def a = r + 1 - 1
    def b = a * 1 + 0
    if b < 0 then (0) else (b)
)
def gvnCalls = fn (a: int, b: int): int -> (
    def m = java.lang.Math.max(a, b) + java.lang.Math.max(b, a)
    def c = clamp0(clamp0(a))
    m + c
)
assert(gvnCalls(3, 7) == 17 and gvnCalls(-2, 5) == 10)
```

```hir
;; hir -O0 fn=gvnCalls
fn gvnCalls (a:int, b:int) -> int {nothrow unique}
  (def m (+:int (jcall Math.max var(a) var(b)) (jcall Math.max var(b) var(a))))
  (def c (call-fn clamp0 (call-fn clamp0 var(a))))
  (+:int var(m) var(c))
```

```hir
;; hir -O1 fn=gvnCalls
fn gvnCalls (a:int, b:int) -> int {nothrow unique}
  (def m (+:int (jcall Math.max var(a#1) var(b#1)) (jcall Math.max var(b#1) var(a#1))))
  (def c
    (block
      (def x
        (block
          (def r#2 var(a#1))
          (if (<:bool var(r#2) 0:int) (box (set r#2 0:int)))
          (if (>:bool var(r#2) 10:int) (box (set r#2 10:int)))
          (def a#3 (-:int (+:int var(r#2) 1:int) 1:int))
          (def b#3 var(a#3))
          (if (<:bool var(b#3) 0:int) 0:int var(b#3))))
      (def r#1 var(x))
      (if (<:bool var(r#1) 0:int) (box (set r#1 0:int)))
      (if (>:bool var(r#1) 10:int) (box (set r#1 10:int)))
      (def a#2 (-:int (+:int var(r#1) 1:int) 1:int))
      (def b#2 var(a#2))
      (if (<:bool var(b#2) 0:int) 0:int var(b#2))))
  (+:int var(m) var(c))
```

```hir
;; hir -O2 fn=gvnCalls
fn gvnCalls (a:int, b:int) -> int {nothrow unique}
  (def m (+:int (jcall Math.max var(a#1) var(b)) (jcall Math.max var(b) var(a#1))))
  (def c
    (block
      (def x
        (block
          (def r#2 var(a#1))
          (if (<:bool var(a#1) 0:int) (box (set r#2 0:int)))
          (if (>:bool var(r#2) 10:int) (box (set r#2 10:int)))
          (def a#3 (-:int (+:int var(r#2) 1:int) 1:int))
          (if (<:bool var(a#3) 0:int) 0:int var(a#3))))
      (def r#1 var(x))
      (if (<:bool var(x) 0:int) (box (set r#1 0:int)))
      (if (>:bool var(r#1) 10:int) (box (set r#1 10:int)))
      (def a#2 (-:int (+:int var(r#1) 1:int) 1:int))
      (if (<:bool var(a#2) 0:int) 0:int var(a#2))))
  (+:int var(m) var(c))
```

## GVN invalidates unique epochs across control-flow merge after conditional write

```babble
def Box = class (v0: int) (var v = v0)
def condWrite = fn (flag: bool, unique b: Box): int -> (
    def before = b.v
    if flag then (b.v = 10)
    before + b.v
)
assert(condWrite(true, Box(3)) == 13 and condWrite(false, Box(3)) == 6)
```

```hir
;; hir -O0 fn=condWrite
fn condWrite (flag:bool, b:obj) -> int {nothrow unique}
  (def before (get-this-field v))
  (if var(flag) (box (block (set-field var(b).v 10:int))))
  (+:int var(before) (get-this-field v))
```

```hir
;; hir -O1 fn=condWrite
fn condWrite (flag:bool, b:obj) -> int {nothrow unique}
  (def before (get-this-field v))
  (if var(flag) (box (set-field var(b).v 10:int)))
  (+:int var(before) (get-this-field v))
```

```hir
;; hir -O2 fn=condWrite
fn condWrite (flag:bool, b:obj) -> int {nothrow unique}
  (def before (get-this-field v))
  (if var(flag) (box (set-field var(b).v 10:int)))
  (+:int var(before) (get-this-field v))
```

## Convert simplification : unbox(box(a)) -> a through CheckCast and Narrow (with IEEE-754 -0.0)

```babble
def roundTrip = fn (x: int, d: double): double -> (
    def a: any = x ; def b: int? = x ; def c: any = d
    def r1: int = a as int
    def r2: int = if b != null then (b + 1) else (0)
    def r3: double = if c is double then (c) else (0.0)
    r3 + (r1 + r2)
)
assert(roundTrip(5, 2.5) == 13.5 and 1.0 / ((fn (d: double): double -> (def a: any = d ; a as double))(-0.0)) < 0.0)
```

```hir
;; hir -O0 fn=roundTrip
fn roundTrip (x:int, d:double) -> double {pure unique}
  (def a (box var(x)))
  (def b (box var(x)))
  (def c (box var(d)))
  (def r1 (as var(a) int|int|))
  (def r2 (if (<>:bool var(b) null) (block (+:int (narrow.nonnull var(b)) 1:int)) (block 0:int)))
  (def r3 (if (is var(c) double) (block (narrow.is[double] var(c))) (block 0.0:double)))
  (+:double var(r3) (convert[double] (block (+:int var(r1) var(r2)))))
```

```hir
;; hir -O1 fn=roundTrip
fn roundTrip (x:int, d:double) -> double {pure unique}
  (def a (box var(x)))
  (def b (box var(x)))
  (def c (box var(d)))
  (def r1 (as var(a) int|int|))
  (def r2 (if (<>:bool var(b) null) (+:int (narrow.nonnull var(b)) 1:int) 0:int))
  (def r3 (if (is var(c) double) (narrow.is[double] var(c)) 0.0:double))
  (+:double var(r3) (convert[double] (+:int var(r1) var(r2))))
```

```hir
;; hir -O2 fn=roundTrip
fn roundTrip (x:int, d:double) -> double {pure unique}
  (def a (box var(x)))
  (def c (box var(d)))
  (def r2 (if (<>:bool var(a) null) (+:int var(x) 1:int) 0:int))
  (def r3 (if (is var(c) double) var(d) 0.0:double))
  (+:double var(r3) (convert[double] (+:int var(x) var(r2))))
```

## Convert simplification : sound box(unbox(a)) -> a on CheckCast, Narrow, and NonNull

```babble
def rbCheck = fn (xs: list of int): any -> (def y: int = xs.get(0) ; y)
def rbNarrow = fn (x: int?, z: any?): any? -> (
    if x != null then (def y: int = x ; y) else if z is double then (def d: double = z ; d) else (null)
)
def rbNN = fn (x: int?): any -> (def y: int = x! ; y)
assert(rbCheck(list(42)) == 42 and rbNarrow(7, null) == 7 and rbNarrow(null, 3.5) == 3.5 and rbNN(9) == 9)
```

```hir
;; hir -O0 fn=rbCheck
fn rbCheck (xs:obj) -> obj {pure unique}
  (def y (jcall List.get var(xs) 0:int))
  (box var(y))
```

```hir
;; hir -O1 fn=rbCheck
fn rbCheck (xs:obj) -> obj {pure unique}
  (def y (jcall List.get var(xs) 0:int))
  (box var(y))
```

```hir
;; hir -O2 fn=rbCheck
fn rbCheck (xs:obj) -> obj {pure unique}
  (def y (jcall List.get var(xs) 0:int))
  (box var(y))
```

```hir
;; hir -O2 fn=rbNarrow
fn rbNarrow (x:obj, z:obj) -> obj {nothrow}
  (if (<>:bool var(x) null) (narrow.nonnull var(x)) (if (is var(z) double) (narrow.is[double] var(z)) null))
```

```hir
;; hir -O2 fn=rbNN
fn rbNN (x:obj) -> obj {pure unique}
  (nonnull var(x))
  var(x)
```

## Convert simplification : box(nonnull(null)) still throws expected int error

```babble
def rbNN = fn (x: int?): any -> (def y: int = x! ; y)
rbNN(null)
```

```error
Type error : expected int, got null
```

## Convert simplification : cross-pass AliasRLE + GVN unbox(narrow(box(x))) -> x

```babble
def Holder = class (var v: any) (
    def setAndRead = fn mut (x: int): int -> (
        this.v = x
        def cur = this.v
        if cur is int then (cur + 10) else (0)
    )
)
assert(Holder("init").setAndRead(5) == 15)
```

```hir
;; hir -O0 fn=Holder.setAndRead
fn Holder.setAndRead (x:int) -> int {nothrow mut}
  (set-this-field v var(x))
  (def cur (get-this-field v))
  (if (is var(cur) int) (block (+:int (narrow.is[int] var(cur)) 10:int)) (block 0:int))
```

```hir
;; hir -O1 fn=Holder.setAndRead
fn Holder.setAndRead (x:int) -> int {nothrow mut}
  (set-this-field v var(x))
  (def cur (get-this-field v))
  (if (is var(cur) int) (+:int (narrow.is[int] var(cur)) 10:int) 0:int)
```

```hir
;; hir -O2 fn=Holder.setAndRead
fn Holder.setAndRead (x:int) -> int {nothrow mut}
  (set-this-field v var(x))
  (def cur (box var(x)))
  (if (is var(cur) int) (+:int var(x) 10:int) 0:int)
```

## Convert simplification : soundness guards (lossy int->float->double, NonNull Narrow + CheckCast, int as double)

```babble
def lossy = fn (x: int): double -> (def f: float = x ; def d: double = f ; d)
def widenAs = fn (x: int): any -> (x as double)
def nc = fn (x: any?): int -> (if x != null then (x as int) else (-1))
assert(lossy(16777217) == 16777216.0 and widenAs(3) is double and nc(4) == 4 and nc(null) == -1)
var bad = false ; try (nc("oops")) catch e: exception then (bad = true) ; assert(bad)
```

```hir
;; hir -O0 fn=lossy
fn lossy (x:int) -> double {nothrow monotone injective unique}
  (def f (widen[float] var(x)))
  (def d (widen[double] var(f)))
  var(d)
```

```hir
;; hir -O1 fn=lossy
fn lossy (x:int) -> double {nothrow monotone injective unique}
  (def f (widen[float] var(x)))
  (def d (widen[double] var(f)))
  var(d)
```

```hir
;; hir -O2 fn=lossy
fn lossy (x:int) -> double {nothrow monotone injective unique}
  (def f (widen[float] var(x)))
  (def d (widen[double] var(f)))
  var(d)
```

```hir
;; hir -O2 fn=widenAs
fn widenAs (x:int) -> obj {injective unique}
  (box (as var(x) double|double|))
```

```hir
;; hir -O2 fn=nc
fn nc (x:obj) -> int {pure}
  (if (<>:bool var(x) null) (as (narrow.nonnull var(x)) int|int|) -1:int)
```

