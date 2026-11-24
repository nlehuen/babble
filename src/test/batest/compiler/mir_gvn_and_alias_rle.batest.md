# SSA MIR GVN/CSE and Alias-Aware RLE (BIP014-P3)

Tests `GvnCsePass` (dominator-scoped CSE, commutative operand canonicalization,
idempotent call collapsing, `ArrayLen` CSE, and exception-handler dominance safety) and
`AliasRlePass` (store-to-load forwarding and disjoint `unique`/`frozen` alias preservation).

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

```mir
;; mir -O2 fn=cseComm stage=before
fn cseComm(v0: int, v1: int, v2: bool) -> int:
  bb0:
    v3: int = add v0, v1 {pure}
    v4: int = add v1, v0 {pure}
    br_if v2, bb2, bb1
  bb1:
    v5: int = add v1, v0 {pure}
    v6: int = add v3, v5 {pure}
    br bb3(v6)
  bb2:
    v7: int = add v3, v4 {pure}
    v8: int = mul v0, v1 {pure}
    v9: int = add v7, v8 {pure}
    v10: int = mul v1, v0 {pure}
    v11: int = add v9, v10 {pure}
    br bb3(v11)
  bb3(v12: int):
    ret v12
```

```mir
;; mir -O2 fn=cseComm stage=after:GvnCsePass
fn cseComm(v0: int, v1: int, v2: bool) -> int:
  bb0:
    v3: int = add v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v4: int = add v3, v3 {pure}
    br bb3(v4)
  bb2:
    v5: int = add v3, v3 {pure}
    v6: int = mul v0, v1 {pure}
    v7: int = add v5, v6 {pure}
    v8: int = add v7, v6 {pure}
    br bb3(v8)
  bb3(v9: int):
    ret v9
```

```mir
;; mir -O2 fn=cseComm
fn cseComm(v0: int, v1: int, v2: bool) -> int:
  bb0:
    v3: int = add v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v4: int = add v3, v3 {pure}
    br bb3(v4)
  bb2:
    v5: int = add v3, v3 {pure}
    v6: int = mul v0, v1 {pure}
    v7: int = add v5, v6 {pure}
    v8: int = add v7, v6 {pure}
    br bb3(v8)
  bb3(v9: int):
    ret v9
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
  (+:double (+:double var(b) (array-op SIZE var(xs))) (array-op SIZE var(xs)))
```

```hir
;; hir -O1 fn=absTwice
fn absTwice (x:double, xs:obj) -> double {nothrow unique}
  (def a (jcall Math.abs var(x)))
  (def b (jcall Math.abs var(a)))
  (+:double (+:double var(b) (array-op SIZE var(xs))) (array-op SIZE var(xs)))
```

```mir
;; mir -O2 fn=absTwice stage=before
fn absTwice(v0: double, v1: any) -> double:
  bb0:
    v2: double = call_java Math.abs(v0) {pure}
    v3: double = call_java Math.abs(v2) {pure}
    v4: int = array_len v1 {read:frozen,throws}
    v5: double = convert.double v4
    v6: double = add v3, v5 {pure}
    v7: int = array_len v1 {read:frozen,throws}
    v8: double = convert.double v7
    v9: double = add v6, v8 {pure}
    ret v9
```

```mir
;; mir -O2 fn=absTwice
fn absTwice(v0: double, v1: any) -> double:
  bb0:
    v2: double = call_java Math.abs(v0) {pure}
    v3: int = array_len v1 {read:frozen,throws}
    v4: double = convert.double v3
    v5: double = add v2, v4 {pure}
    v6: double = add v5, v4 {pure}
    ret v6
```

## AliasRLE forwards StoreField to LoadField and preserves frozen reads across writes

```babble
def Box = class (init: int) (
    def v = init
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

```mir
;; mir -O2 fn=Box.setAndRead stage=before
fn Box.setAndRead(v0: int) -> int:
  bb0:
    v1: any = slot_read this {pure}
    store_field v1.v, v0 {write:shared,throws}
    v2: any = slot_read this {pure}
    v3: int = load_field v2.v {read:shared,throws}
    v4: any = slot_read this {pure}
    v5: int = load_field v4.v {read:shared,throws}
    v6: int = add v3, v5 {pure}
    ret v6
```

```mir
;; mir -O2 fn=Box.setAndRead
fn Box.setAndRead(v0: int) -> int:
  bb0:
    v1: any = slot_read this {pure}
    store_field v1.v, v0 {write:shared,throws}
    v2: int = add v0, v0 {pure}
    ret v2
```

```mir
;; mir -O2 fn=frozenAcrossWrite stage=before
fn frozenAcrossWrite(v0: any, v1: any, v2: int) -> int:
  bb0:
    v3: int = load_field v0.x {read:frozen}
    store_field v1.v, v2 {write:shared,throws}
    v4: int = load_field v1.v {read:shared,throws}
    v5: int = load_field v1.v {read:shared,throws}
    v6: int = add v4, v5 {pure}
    v7: int = load_field v0.x {read:frozen}
    v8: int = add v3, v7 {pure}
    ret v8
```

```mir
;; mir -O2 fn=frozenAcrossWrite
fn frozenAcrossWrite(v0: any, v1: any, v2: int) -> int:
  bb0:
    v3: int = load_field v0.x {read:frozen}
    store_field v1.v, v2 {write:shared,throws}
    v4: int = add v3, v3 {pure}
    ret v4
```

## AliasRLE preserves reads of one unique parameter across writes to another unique parameter

```babble
def Cell = class (init: int) (def v = init)
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

```mir
;; mir -O2 fn=disjointUnique stage=before
fn disjointUnique(v0: any [unique], v1: any [unique]) -> int:
  bb0:
    v2: int = load_field v0.v {read:unique#0,throws}
    v3: int = const 99
    v4: any = opaque.DynamicFieldAssign(v1, v3) {write:unique#1,throws}
    v5: int = load_field v0.v {read:unique#0,throws}
    v6: int = add v2, v5 {pure}
    v7: int = load_field v1.v {read:unique#1,throws}
    v8: int = add v6, v7 {pure}
    ret v8
```

```mir
;; mir -O2 fn=disjointUnique
fn disjointUnique(v0: any [unique], v1: any [unique]) -> int:
  bb0:
    v2: int = load_field v0.v {read:unique#0,throws}
    v3: int = const 99
    v4: any = opaque.DynamicFieldAssign(v1, v3) {write:unique#1,throws}
    v5: int = add v2, v2 {pure}
    v6: int = load_field v1.v {read:unique#1,throws}
    v7: int = add v5, v6 {pure}
    ret v7
```
