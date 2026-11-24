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

## GVN collapses commutative Math.max and idempotent non-inlined Babble calls

```babble
def clamp0 = fn (x: int): int -> (
    def r = x
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
          (if (<:bool var(r#2) 0:int) (set r#2 0:int))
          (if (>:bool var(r#2) 10:int) (set r#2 10:int))
          (def a#3 (-:int (+:int var(r#2) 1:int) 1:int))
          (def b#3 var(a#3))
          (if (<:bool var(b#3) 0:int) 0:int var(b#3))))
      (def r#1 var(x))
      (if (<:bool var(r#1) 0:int) (set r#1 0:int))
      (if (>:bool var(r#1) 10:int) (set r#1 10:int))
      (def a#2 (-:int (+:int var(r#1) 1:int) 1:int))
      (def b#2 var(a#2))
      (if (<:bool var(b#2) 0:int) 0:int var(b#2))))
  (+:int var(m) var(c))
```

```mir
;; mir -O2 fn=gvnCalls stage=before
fn gvnCalls(v0: int, v1: int) -> int:
  bb0:
    v2: int = call_java Math.max(v0, v1) {pure}
    v3: int = call_java Math.max(v1, v0) {pure}
    v4: int = add v2, v3 {pure}
    v5: int = const 0
    v6: bool = lt v0, v5 {pure}
    br_if v6, bb2, bb1
  bb1:
    v7: any = const null
    br bb3(v7, v0)
  bb2:
    v8: int = const 0
    v9: any = convert.any v8
    br bb3(v9, v8)
  bb3(v10: any, v11: int):
    v12: int = const 10
    v13: bool = gt v11, v12 {pure}
    br_if v13, bb5, bb4
  bb4:
    v14: any = const null
    br bb6(v14, v11)
  bb5:
    v15: int = const 10
    v16: any = convert.any v15
    br bb6(v16, v15)
  bb6(v17: any, v18: int):
    v19: int = const 1
    v20: int = add v18, v19 {pure}
    v21: int = const 1
    v22: int = sub v20, v21 {pure}
    v23: int = const 0
    v24: bool = lt v22, v23 {pure}
    br_if v24, bb8, bb7
  bb7:
    br bb9(v22)
  bb8:
    v25: int = const 0
    br bb9(v25)
  bb9(v26: int):
    v27: int = const 0
    v28: bool = lt v26, v27 {pure}
    br_if v28, bb11, bb10
  bb10:
    v29: any = const null
    br bb12(v29, v26)
  bb11:
    v30: int = const 0
    v31: any = convert.any v30
    br bb12(v31, v30)
  bb12(v32: any, v33: int):
    v34: int = const 10
    v35: bool = gt v33, v34 {pure}
    br_if v35, bb14, bb13
  bb13:
    v36: any = const null
    br bb15(v36, v33)
  bb14:
    v37: int = const 10
    v38: any = convert.any v37
    br bb15(v38, v37)
  bb15(v39: any, v40: int):
    v41: int = const 1
    v42: int = add v40, v41 {pure}
    v43: int = const 1
    v44: int = sub v42, v43 {pure}
    v45: int = const 0
    v46: bool = lt v44, v45 {pure}
    br_if v46, bb17, bb16
  bb16:
    br bb18(v44)
  bb17:
    v47: int = const 0
    br bb18(v47)
  bb18(v48: int):
    v49: int = add v4, v48 {pure}
    ret v49
```

```mir
;; mir -O2 fn=gvnCalls
fn gvnCalls(v0: int, v1: int) -> int:
  bb0:
    v2: int = call_java Math.max(v0, v1) {pure}
    v3: int = call_java Math.max(v1, v0) {pure}
    v4: int = add v2, v3 {pure}
    v5: int = const 0
    v6: bool = lt v0, v5 {pure}
    br_if v6, bb2, bb1
  bb1:
    br bb3(v0)
  bb2:
    br bb3(v5)
  bb3(v7: int):
    v8: int = const 10
    v9: bool = gt v7, v8 {pure}
    br_if v9, bb5, bb4
  bb4:
    br bb6(v7)
  bb5:
    br bb6(v8)
  bb6(v10: int):
    v11: int = const 1
    v12: int = add v10, v11 {pure}
    v13: int = sub v12, v11 {pure}
    v14: bool = lt v13, v5 {pure}
    br_if v14, bb8, bb7
  bb7:
    br bb9(v13)
  bb8:
    br bb9(v5)
  bb9(v15: int):
    v16: bool = lt v15, v5 {pure}
    br_if v16, bb11, bb10
  bb10:
    br bb12(v15)
  bb11:
    br bb12(v5)
  bb12(v17: int):
    v18: bool = gt v17, v8 {pure}
    br_if v18, bb14, bb13
  bb13:
    br bb15(v17)
  bb14:
    br bb15(v8)
  bb15(v19: int):
    v20: int = add v19, v11 {pure}
    v21: int = sub v20, v11 {pure}
    v22: bool = lt v21, v5 {pure}
    br_if v22, bb17, bb16
  bb16:
    br bb18(v21)
  bb17:
    br bb18(v5)
  bb18(v23: int):
    v24: int = add v4, v23 {pure}
    ret v24
```

## GVN invalidates unique epochs across control-flow merge after conditional write

```babble
def Box = class (init: int) (def v = init)
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
  (if var(flag) (block (set-field var(b).v 10:int)))
  (+:int var(before) (get-this-field v))
```

```hir
;; hir -O1 fn=condWrite
fn condWrite (flag:bool, b:obj) -> int {nothrow unique}
  (def before (get-this-field v))
  (if var(flag) (set-field var(b).v 10:int))
  (+:int var(before) (get-this-field v))
```

```mir
;; mir -O2 fn=condWrite stage=before
fn condWrite(v0: bool, v1: any [unique]) -> int:
  bb0:
    v2: int = load_field v1.v {read:unique#1,throws}
    br_if v0, bb2, bb1
  bb1:
    v3: any = const null
    br bb3(v3)
  bb2:
    v4: int = const 10
    v5: any = opaque.DynamicFieldAssign(v1, v4) {write:unique#1,throws}
    br bb3(v5)
  bb3(v6: any):
    v7: int = load_field v1.v {read:shared,throws}
    v8: int = add v2, v7 {pure}
    ret v8
```

```mir
;; mir -O2 fn=condWrite
fn condWrite(v0: bool, v1: any [unique]) -> int:
  bb0:
    v2: int = load_field v1.v {read:unique#1,throws}
    br_if v0, bb1, bb2
  bb1:
    v3: int = const 10
    v4: any = opaque.DynamicFieldAssign(v1, v3) {write:unique#1,throws}
    br bb2
  bb2:
    v5: int = load_field v1.v {read:shared,throws}
    v6: int = add v2, v5 {pure}
    ret v6
```
