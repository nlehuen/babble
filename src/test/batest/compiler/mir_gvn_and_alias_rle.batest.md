# SSA MIR GVN/CSE and Alias-Aware RLE (BIP014-I3)

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
  (+:double (+:double var(b) (convert[double] (array-op SIZE var(xs)))) (convert[double] (array-op SIZE var(xs))))
```

```hir
;; hir -O1 fn=absTwice
fn absTwice (x:double, xs:obj) -> double {nothrow unique}
  (def a (jcall Math.abs var(x)))
  (def b (jcall Math.abs var(a)))
  (+:double (+:double var(b) (convert[double] (array-op SIZE var(xs)))) (convert[double] (array-op SIZE var(xs))))
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
    var v = init
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
def Cell = class (init: int) (var v = init)
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
    v4: any = opaque.DynamicFieldAssign(v1.v, v3) {write:unique#1,throws}
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
    v4: any = opaque.DynamicFieldAssign(v1.v, v3) {write:unique#1,throws}
    v5: int = add v2, v2 {pure}
    v6: int = load_field v1.v {read:unique#1,throws}
    v7: int = add v5, v6 {pure}
    ret v7
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
def Box = class (init: int) (var v = init)
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
    v5: any = opaque.DynamicFieldAssign(v1.v, v4) {write:unique#1,throws}
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
    v4: any = opaque.DynamicFieldAssign(v1.v, v3) {write:unique#1,throws}
    br bb2
  bb2:
    v5: int = load_field v1.v {read:shared,throws}
    v6: int = add v2, v5 {pure}
    ret v6
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

```mir
;; mir -O2 fn=roundTrip stage=before
fn roundTrip(v0: int, v1: double) -> double:
  bb0:
    v2: any = convert.any v0
    v3: any = convert.any v0
    v4: any = convert.any v1
    v5: any = checkcast v2 <int|int|>
    v6: int = convert.int v5
    v7: any = const null
    v8: any = neq v3, v7 {pure,throws}
    br_if v8, bb2, bb1
  bb1:
    v9: int = const 0
    br bb3(v9)
  bb2:
    v10: any = narrow.nonnull v3 <non-null>
    v11: int = convert.int v10
    v12: int = const 1
    v13: int = add v11, v12 {pure}
    br bb3(v13)
  bb3(v14: int):
    v15: any = is v4 <double>
    br_if v15, bb5, bb4
  bb4:
    v16: double = const 0.0
    br bb6(v16)
  bb5:
    v17: any = narrow.is v4 <double>
    v18: double = convert.double v17
    br bb6(v18)
  bb6(v19: double):
    v20: int = add v6, v14 {pure}
    v21: double = convert.double v20
    v22: double = add v19, v21 {pure}
    ret v22
```

```mir
;; mir -O2 fn=roundTrip
fn roundTrip(v0: int, v1: double) -> double:
  bb0:
    v2: any = convert.any v0
    v3: any = convert.any v1
    v4: any = const null
    v5: any = neq v2, v4 {pure,throws}
    br_if v5, bb2, bb1
  bb1:
    v6: int = const 0
    br bb3(v6)
  bb2:
    v7: int = const 1
    v8: int = add v0, v7 {pure}
    br bb3(v8)
  bb3(v9: int):
    v10: any = is v3 <double>
    br_if v10, bb5, bb4
  bb4:
    v11: double = const 0.0
    br bb6(v11)
  bb5:
    br bb6(v1)
  bb6(v12: double):
    v13: int = add v0, v9 {pure}
    v14: double = convert.double v13
    v15: double = add v12, v14 {pure}
    ret v15
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

```mir
;; mir -O2 fn=rbCheck stage=before
fn rbCheck(v0: any) -> any:
  bb0:
    v1: int = const 0
    v2: any = call_java v0.get(v1) {impure}
    v3: any = checkcast v2 <int|int|>
    v4: int = convert.int v3
    v5: any = convert.any v4
    ret v5
```

```mir
;; mir -O2 fn=rbCheck
fn rbCheck(v0: any) -> any:
  bb0:
    v1: int = const 0
    v2: any = call_java v0.get(v1) {impure}
    v3: any = checkcast v2 <int|int|>
    ret v3
```

```mir
;; mir -O2 fn=rbNarrow stage=before
fn rbNarrow(v0: any, v1: any) -> any:
  bb0:
    v2: any = const null
    v3: any = neq v0, v2 {pure,throws}
    br_if v3, bb5, bb1
  bb1:
    v4: any = is v1 <double>
    br_if v4, bb3, bb2
  bb2:
    v5: any = const null
    br bb4(v5)
  bb3:
    v6: any = narrow.is v1 <double>
    v7: double = convert.double v6
    v8: any = convert.any v7
    br bb4(v8)
  bb4(v9: any):
    br bb6(v9)
  bb5:
    v10: any = narrow.nonnull v0 <non-null>
    v11: int = convert.int v10
    v12: any = convert.any v11
    br bb6(v12)
  bb6(v13: any):
    ret v13
```

```mir
;; mir -O2 fn=rbNarrow
fn rbNarrow(v0: any, v1: any) -> any:
  bb0:
    v2: any = const null
    v3: any = neq v0, v2 {pure,throws}
    br_if v3, bb5, bb1
  bb1:
    v4: any = is v1 <double>
    br_if v4, bb3, bb2
  bb2:
    br bb4(v2)
  bb3:
    v5: any = narrow.is v1 <double>
    br bb4(v5)
  bb4(v6: any):
    br bb6(v6)
  bb5:
    v7: any = narrow.nonnull v0 <non-null>
    br bb6(v7)
  bb6(v8: any):
    ret v8
```

```mir
;; mir -O2 fn=rbNN stage=before
fn rbNN(v0: any) -> any:
  bb0:
    v1: int = nonnull v0
    v2: any = convert.any v1
    ret v2
```

```mir
;; mir -O2 fn=rbNN
fn rbNN(v0: any) -> any:
  bb0:
    v1: int = nonnull v0
    ret v0
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

```mir
;; mir -O2 fn=Holder.setAndRead stage=before
fn Holder.setAndRead(v0: int) -> int:
  bb0:
    v1: any = slot_read this {pure}
    v2: any = convert.any v0
    store_field v1.v, v2 {write:shared,throws}
    v3: any = slot_read this {pure}
    v4: any = load_field v3.v {read:shared,throws}
    v5: any = is v4 <int>
    br_if v5, bb2, bb1
  bb1:
    v6: int = const 0
    br bb3(v6)
  bb2:
    v7: any = narrow.is v4 <int>
    v8: int = convert.int v7
    v9: int = const 10
    v10: int = add v8, v9 {pure}
    br bb3(v10)
  bb3(v11: int):
    ret v11
```

```mir
;; mir -O2 fn=Holder.setAndRead
fn Holder.setAndRead(v0: int) -> int:
  bb0:
    v1: any = slot_read this {pure}
    v2: any = convert.any v0
    store_field v1.v, v2 {write:shared,throws}
    v3: any = is v2 <int>
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 0
    br bb3(v4)
  bb2:
    v5: int = const 10
    v6: int = add v0, v5 {pure}
    br bb3(v6)
  bb3(v7: int):
    ret v7
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

```mir
;; mir -O2 fn=lossy stage=before,final
fn lossy(v0: int) -> double:
  bb0:
    v1: float = convert.float v0
    v2: double = convert.double v1
    ret v2
```

```mir
;; mir -O2 fn=widenAs stage=before,final
fn widenAs(v0: int) -> any:
  bb0:
    v1: any = convert.any v0
    v2: any = checkcast v1 <double|double|>
    ret v2
```

```mir
;; mir -O2 fn=nc stage=before,final
fn nc(v0: any) -> int:
  bb0:
    v1: any = const null
    v2: any = neq v0, v1 {pure,throws}
    br_if v2, bb2, bb1
  bb1:
    v3: int = const -1
    br bb3(v3)
  bb2:
    v4: any = narrow.nonnull v0 <non-null>
    v5: any = checkcast v4 <int|int|>
    v6: int = convert.int v5
    br bb3(v6)
  bb3(v7: int):
    ret v7
```

