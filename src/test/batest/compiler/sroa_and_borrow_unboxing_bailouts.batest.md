# SROA Projection and Borrow Unboxing Bailouts

References : `BIP014-I4`, `BIP007-I2`, `ScalarReplacePass`, `FbipReusePass`.

```babble
;; babble module=ext_rec.ba
export def ExtVec = record (x: double, y: double) ()
export def ExtHolder = record (items: list of int, count: int) ()
```

```babble
;; babble preamble
def Vec3 = record (x: double, y: double, z: double) ()
def Pair = record (a: int, b: int) ()
def Holder = record (items: list of int, count: int) ()
def mkVec = fn (x: double): Vec3 -> (
    if x < -999.0 then ( return Vec3(0.0, 0.0, 0.0) )
    Vec3(x, x + 1.0, 0.0)
)
def heavy = fn (a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    a.x * b.x + a.y * b.y + a.z * b.z
)
```


## Projection beta-reduction with numeric widening and primitive-to-any boxing

```babble
def P = record (a: int, b: double) ()
def AnyBox = record (v: any) ()
def run = fn (n: int, k: int): double -> (
    def w1 = P(n, k).b
    def w2 = P(n, k).a + 1.5
    def bx = AnyBox(n).v
    w1 + w2 + double(bx as int)
)
assert(run(3, 4) == 11.5)
```

```hir
;; hir -O0 fn=run
fn run (n:int, k:int) -> double {pure unique}
  (def w1 (get-this-field b))
  (def w2 (+:double (get-this-field a) 1.5:double))
  (def bx (get-this-field v))
  (+:double (+:double var(w1) var(w2)) (call name(double) (as var(bx) int|int|)))
```

```hir
;; hir -O1 fn=run
fn run (n:int, k:int) -> double {pure unique}
  (def w1 var(k))
  (def w2 (+:double var(n) 1.5:double))
  (def bx (block var(n)))
  (+:double (+:double var(w1) var(w2)) (call name(double) (as var(bx) int|int|)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: int) -> double:
  bb0:
    v2: double = convert.double v1
    v3: double = convert.double v0
    v4: double = const 1.5
    v5: double = add v3, v4 {pure}
    v6: any = convert.any v0
    v7: double = add v2, v5 {pure}
    v8: any = name_read double {pure}
    v9: any = checkcast v6 <int|int|>
    v10: double = opaque.DynamicCall(v8, v9) {impure}
    v11: double = add v7, v10 {pure}
    ret v11
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: int) -> double:
  bb0:
    v2: double = convert.double v1
    v3: double = convert.double v0
    v4: double = const 1.5
    v5: double = add v3, v4 {pure}
    v6: any = convert.any v0
    v7: double = add v2, v5 {pure}
    v8: any = name_read double {pure}
    v9: double = opaque.DynamicCall(v8, v6) {impure}
    v10: double = add v7, v9 {pure}
    ret v10
```

## Projection beta-reduction and SROA with impure, local, and anonymous record class refs

```babble
def run = fn io (): double -> (
    def log = java.lang.StringBuilder()
    def a = ( log.append("C") ; Vec3 )(1.0, 2.0, 3.0).y
    def v = ( log.append("S") ; Vec3 )(4.0, 5.0, 6.0)
    def R = record (k: int) ()
    def c = record (x: int, y: int) ()(10, 20).x
    assert(log.toString() == "CS")
    a + v.x + double(R(3).k + c)
)
assert(run() == 19.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (def a (get-this-field y))
  (def v (record-new Vec3 4.0:double 5.0:double 6.0:double))
  (def R (class-ref R))
  (def c (get-this-field x))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "CS"))
  (+:double (+:double var(a) (get-this-field x)) (call name(double) (+:int (get-this-field k) var(c))))
```

```hir
;; hir -O1 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (def a
    (block
      (block
        (jcall StringBuilder.append var(log) "C")
        name(Vec3))
      2.0:double))
  (block
    (block
      (jcall StringBuilder.append var(log) "S")
      name(Vec3))
    (def $sr_v_0 4.0:double)
    (def $sr_v_2 6.0:double))
  (def R (class-ref R))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "CS"))
  (+:double (+:double var(a) var($sr_v_0)) (call name(double) 13:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> double:
  bb0:
    v0: any = new_java StringBuilder() {impure}
    v1: any = const "C"
    v2: any = call_java v0.append(v1) {impure}
    v3: any = checkcast v2 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v4: any = name_read Vec3 {pure}
    v5: double = const 2.0
    v6: any = const "S"
    v7: any = call_java v0.append(v6) {impure}
    v8: any = checkcast v7 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v9: any = name_read Vec3 {pure}
    v10: double = const 4.0
    v11: double = const 6.0
    v12: any = opaque.ClassExpr(R) {impure}
    v13: any = name_read assert {pure}
    v14: any = call_java v0.toString() {impure}
    v15: any = checkcast v14 <string|string|>
    v16: any = const "CS"
    v17: any = eq v15, v16 {pure}
    v18: bool = opaque.DynamicCall(v13, v17) {impure}
    v19: double = add v5, v10 {pure}
    v20: any = name_read double {pure}
    v21: int = const 13
    v22: double = opaque.DynamicCall(v20, v21) {impure}
    v23: double = add v19, v22 {pure}
    ret v23
```

```mir
;; mir -O2 fn=run
fn run() -> double:
  bb0:
    v0: any = new_java StringBuilder() {impure}
    v1: any = const "C"
    v2: any = call_java v0.append(v1) {impure}
    v3: any = checkcast v2 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v4: any = const "S"
    v5: any = call_java v0.append(v4) {impure}
    v6: any = checkcast v5 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v7: double = const 6.0
    v8: any = opaque.ClassExpr(R) {impure}
    v9: any = name_read assert {pure}
    v10: any = call_java v0.toString() {impure}
    v11: any = checkcast v10 <string|string|>
    v12: any = const "CS"
    v13: any = eq v11, v12 {pure}
    v14: bool = opaque.DynamicCall(v9, v13) {impure}
    v15: any = name_read double {pure}
    v16: int = const 13
    v17: double = opaque.DynamicCall(v15, v16) {impure}
    v18: double = add v7, v17 {pure}
    ret v18
```

## Projection beta-reduction on cross-module record via local and impure module receivers

```babble
def m = import("ext_rec.ba")
def run = fn io (): double -> (
    def log = java.lang.StringBuilder()
    def lm = m
    def a = lm.ExtVec(2.0, 3.0).x
    def b = ( log.append("M") ; m ).ExtVec(4.0, 5.0).y
    assert(log.toString() == "M")
    a + b
)
assert(run() == 7.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (def lm name(m))
  (def a (get-this-field x))
  (def b (get-this-field y))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "M"))
  (+:double var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (def b
    (block
      (export (block
          (jcall StringBuilder.append var(log) "M")
          name(m)).ExtVec)
      5.0:double))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "M"))
  (+:double 2.0:double var(b))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> double:
  bb0:
    v0: any = new_java StringBuilder() {impure}
    v1: any = const "M"
    v2: any = call_java v0.append(v1) {impure}
    v3: any = checkcast v2 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v4: any = name_read m {pure}
    v5: any = export_get v4.ExtVec {pure}
    v6: double = const 5.0
    v7: any = name_read assert {pure}
    v8: any = call_java v0.toString() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: any = const "M"
    v11: any = eq v9, v10 {pure}
    v12: bool = opaque.DynamicCall(v7, v11) {impure}
    v13: double = const 2.0
    v14: double = add v13, v6 {pure}
    ret v14
```

```mir
;; mir -O2 fn=run
fn run() -> double:
  bb0:
    v0: any = new_java StringBuilder() {impure}
    v1: any = const "M"
    v2: any = call_java v0.append(v1) {impure}
    v3: any = checkcast v2 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v4: any = name_read assert {pure}
    v5: any = call_java v0.toString() {impure}
    v6: any = checkcast v5 <string|string|>
    v7: any = eq v6, v1 {pure}
    v8: bool = opaque.DynamicCall(v4, v7) {impure}
    v9: double = const 7.0
    ret v9
```

## Zero-parameter fast record skips SROA layout and projection reduction

```babble
def Empty = record () (
    def one = fn (): int -> ( 1 )
    def getFn = fn (): fn (): int -> ( this.one )
)
def run = fn (): int -> (
    def e = Empty()
    def f = Empty().getFn()
    if e == Empty() then ( f() ) else ( 0 )
)
assert(run() == 1)
```

```hir
;; hir -O0 fn=run
fn run () -> int {pure}
  (def e (record-new Empty))
  (def f (call-method (record-new Empty).getFn))
  (if (==:bool var(e) (record-new Empty)) (block (call var(f))) (block 0:int))
```

```hir
;; hir -O1 fn=run
fn run () -> int {pure}
  (def e (record-new Empty))
  (def f
    (block
      (def $this (record-new Empty))
      (get-this-field one)))
  (if (==:bool var(e) (record-new Empty)) (call var(f)) 0:int)
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: any = name_read Empty {pure}
    v1: any = record_new Empty[v0]() {pure}
    v2: any = name_read Empty {pure}
    v3: any = record_new Empty[v2]() {pure}
    v4: any = load_field v3.one {read:frozen,throws}
    v5: any = name_read Empty {pure}
    v6: any = record_new Empty[v5]() {pure}
    v7: any = eq v1, v6 {pure,throws}
    br_if v7, bb2, bb1
  bb1:
    v8: int = const 0
    br bb3(v8)
  bb2:
    v9: int = opaque.DynamicCall(v4) {impure}
    br bb3(v9)
  bb3(v10: int):
    ret v10
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: any = name_read Empty {pure}
    v1: any = record_new Empty[v0]() {pure}
    v2: any = record_new Empty[v0]() {pure}
    v3: any = load_field v2.one {read:frozen,throws}
    v4: any = record_new Empty[v0]() {pure}
    v5: any = eq v1, v4 {pure,throws}
    br_if v5, bb2, bb1
  bb1:
    v6: int = const 0
    br bb3(v6)
  bb2:
    v7: int = opaque.DynamicCall(v3) {impure}
    br bb3(v7)
  bb3(v8: int):
    ret v8
```

## Inlined method-value projection on fast record disqualifies SROA and borrow unboxing

```babble
def Box = record (x: int) (
    def inc = fn (): int -> ( this.x + 1 )
    def getFn = fn (): fn (): int -> ( this.inc )
)
def useB = fn (a: Box, b: Box): int -> (
    if a.x < -999 then ( return 0 )
    a.getFn()() + b.x
)
def run = fn (): int -> (
    def v = Box(5)
    v.getFn()() + Box(3).getFn()() + useB(v, Box(2))
)
assert(run() == 18)
```

```hir
;; hir -O0 fn=run
fn run () -> int {pure unique}
  (def v (record-new Box 5:int))
  (+:int (+:int (call (call-method var(v).getFn)) (call (call-method (record-new Box 3:int).getFn))) (call-fn useB var(v) (record-new Box 2:int)))
```

```hir
;; hir -O1 fn=run
fn run () -> int {pure unique}
  (def v (record-new Box 5:int))
  (+:int (+:int (call (get-this-field inc)) (call (block
          (def $this (record-new Box 3:int))
          (get-this-field inc)))) (call-fn useB$unboxed var(v) 2:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: any = name_read Box {pure}
    v1: int = const 5
    v2: any = record_new Box[v0](v1) {pure}
    v3: any = load_field v2.inc {read:frozen,throws}
    v4: int = opaque.DynamicCall(v3) {impure}
    v5: any = name_read Box {pure}
    v6: int = const 3
    v7: any = record_new Box[v5](v6) {pure}
    v8: any = load_field v7.inc {read:frozen,throws}
    v9: int = opaque.DynamicCall(v8) {impure}
    v10: int = add v4, v9 {pure}
    v11: any = name_read useB {pure}
    v12: int = const 2
    v13: int = call useB$unboxed(v2, v12) {pure,throws}
    v14: int = add v10, v13 {pure}
    ret v14
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: any = name_read Box {pure}
    v1: int = const 5
    v2: any = record_new Box[v0](v1) {pure}
    v3: any = load_field v2.inc {read:frozen,throws}
    v4: int = opaque.DynamicCall(v3) {impure}
    v5: int = const 3
    v6: any = record_new Box[v0](v5) {pure}
    v7: any = load_field v6.inc {read:frozen,throws}
    v8: int = opaque.DynamicCall(v7) {impure}
    v9: int = add v4, v8 {pure}
    v10: any = name_read useB {pure}
    v11: int = const 2
    v12: int = call useB$unboxed(v2, v11) {pure,throws}
    v13: int = add v9, v12 {pure}
    ret v13
```

## Unboxed borrow call rewrite inside a function alongside non-unboxed and non-qualifying calls

```babble
def id = fn (x: double): double -> (if x < -999.0 then (return 0.0) ; x)
def run = fn io (a: Vec3, b: Vec3): double -> (
  def lg = java.lang.StringBuilder()
  def r = id(heavy(a, b)) + heavy(b: b, a: a) + heavy(Vec3(z: 3.0, x: 1.0, y: 2.0), b) +
    heavy((lg.append("U") ; Vec3)(1.0, 2.0, 3.0), b)
  assert(lg.toString() == "U") ; r
)
assert(run(Vec3(1.0, 2.0, 3.0), Vec3(1.0, 0.0, 0.0)) == 4.0)
```

```hir
;; hir -O0 fn=run
fn run (a:obj, b:obj) -> double {io}
  (def lg (jnew StringBuilder))
  (def r (+:double (+:double (+:double (call-fn id (call-fn heavy var(a) var(b))) (call-fn heavy var(b) var(a))) (call-fn heavy (record-new Vec3 3.0:double 1.0:double 2.0:double) var(b))) (call-fn heavy (record-new Vec3 1.0:double 2.0:double 3.0:double) var(b))))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(lg)) "U"))
  var(r)
```

```hir
;; hir -O1 fn=run
fn run (a:obj, b:obj) -> double {io}
  (def lg (jnew StringBuilder))
  (def r (+:double (+:double (+:double (call-fn id (call-fn heavy$unboxed (get-this-field x) (get-this-field y) (get-this-field z) (get-this-field x) (get-this-field y) (get-this-field z))) (call-fn heavy var(b) var(a))) (call-fn heavy (record-new Vec3 3.0:double 1.0:double 2.0:double) var(b))) (call-fn heavy (record-new Vec3 1.0:double 2.0:double 3.0:double) var(b))))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(lg)) "U"))
  var(r)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any) -> double:
  bb0:
    v2: any = new_java StringBuilder() {impure}
    v3: any = name_read id {pure}
    v4: any = name_read heavy {pure}
    v5: double = load_field v0.x {read:frozen}
    v6: double = load_field v0.y {read:frozen}
    v7: double = load_field v0.z {read:frozen}
    v8: double = load_field v1.x {read:frozen}
    v9: double = load_field v1.y {read:frozen}
    v10: double = load_field v1.z {read:frozen}
    v11: double = call heavy$unboxed(v5, v6, v7, v8, v9, v10) {pure}
    v12: double = call id(v11) {pure}
    v13: any = name_read heavy {pure}
    v14: double = call heavy(v1, v0) {pure}
    v15: double = add v12, v14 {pure}
    v16: any = name_read heavy {pure}
    v17: any = name_read Vec3 {pure}
    v18: double = const 3.0
    v19: double = const 1.0
    v20: double = const 2.0
    v21: any = record_new Vec3[v17](v18, v19, v20) {pure}
    v22: double = call heavy(v21, v1) {pure}
    v23: double = add v15, v22 {pure}
    v24: any = name_read heavy {pure}
    v25: any = const "U"
    v26: any = call_java v2.append(v25) {impure}
    v27: any = checkcast v26 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v28: any = name_read Vec3 {pure}
    v29: double = const 1.0
    v30: double = const 2.0
    v31: double = const 3.0
    v32: any = record_new Vec3[v28](v29, v30, v31) {pure}
    v33: double = call heavy(v32, v1) {impure}
    v34: double = add v23, v33 {pure}
    v35: any = name_read assert {pure}
    v36: any = call_java v2.toString() {impure}
    v37: any = checkcast v36 <string|string|>
    v38: any = const "U"
    v39: any = eq v37, v38 {pure}
    v40: bool = opaque.DynamicCall(v35, v39) {impure}
    ret v34
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any) -> double:
  bb0:
    v2: any = new_java StringBuilder() {impure}
    v3: any = name_read id {pure}
    v4: any = name_read heavy {pure}
    v5: double = load_field v0.x {read:frozen}
    v6: double = load_field v0.y {read:frozen}
    v7: double = load_field v0.z {read:frozen}
    v8: double = load_field v1.x {read:frozen}
    v9: double = load_field v1.y {read:frozen}
    v10: double = load_field v1.z {read:frozen}
    v11: double = call heavy$unboxed(v5, v6, v7, v8, v9, v10) {pure}
    v12: double = call id(v11) {pure}
    v13: double = call heavy(v1, v0) {pure}
    v14: double = add v12, v13 {pure}
    v15: any = name_read Vec3 {pure}
    v16: double = const 3.0
    v17: double = const 1.0
    v18: double = const 2.0
    v19: any = record_new Vec3[v15](v16, v17, v18) {pure}
    v20: double = call heavy(v19, v1) {pure}
    v21: double = add v14, v20 {pure}
    v22: any = const "U"
    v23: any = call_java v2.append(v22) {impure}
    v24: any = checkcast v23 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v25: any = record_new Vec3[v15](v17, v18, v16) {pure}
    v26: double = call heavy(v25, v1) {impure}
    v27: double = add v21, v26 {pure}
    v28: any = name_read assert {pure}
    v29: any = call_java v2.toString() {impure}
    v30: any = checkcast v29 <string|string|>
    v31: any = eq v30, v22 {pure}
    v32: bool = opaque.DynamicCall(v28, v31) {impure}
    ret v27
```

## Unboxed borrow call bailouts for captured cell and conditionally initialized record locals

```babble
def run = fn (fl: bool): double -> (
    def a = Vec3(1.0, 2.0, 3.0)
    def b = Vec3(1.0, 0.0, 0.0)
    def cap = fn (): double -> ( a.x )
    def r = heavy(a, b) + cap() + (if false then ( heavy(c, b) ) else ( 0.0 ))
    def c = Vec3(2.0, 0.0, 0.0)
    if fl then ( r + heavy(c, b) ) else ( r )
)
assert(run(true) == 4.0 and run(false) == 2.0)
```

```hir
;; hir -O0 fn=run
fn run (fl:bool) -> double {nothrow}
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Vec3 1.0:double 0.0:double 0.0:double))
  (def cap (fn-ref cap))
  (def r (+:double (+:double (call-fn heavy var(a) var(b)) (call-fn cap)) (block (if false:bool (block (call-fn heavy var(c) var(b))) (block 0.0:double)))))
  (def c (record-new Vec3 2.0:double 0.0:double 0.0:double))
  (if var(fl) (block (+:double var(r) (call-fn heavy var(c) var(b)))) (block var(r)))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> double {nothrow}
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Vec3 1.0:double 0.0:double 0.0:double))
  (def cap (fn-ref cap))
  (def r (+:double (+:double (call-fn heavy var(a) var(b)) (call-fn cap)) 0.0:double))
  (def c (record-new Vec3 2.0:double 0.0:double 0.0:double))
  (if var(fl) (+:double var(r) (call-fn heavy var(c) var(b))) var(r))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> double:
  bb0:
    v1: any = name_read Vec3 {pure}
    v2: double = const 1.0
    v3: double = const 2.0
    v4: double = const 3.0
    v5: any = record_new Vec3[v1](v2, v3, v4) {pure}
    slot_init a, v5 {write:shared}
    v6: any = name_read Vec3 {pure}
    v7: double = const 1.0
    v8: double = const 0.0
    v9: double = const 0.0
    v10: any = record_new Vec3[v6](v7, v8, v9) {pure}
    v11: any = opaque.FunctionRef(cap) {read:shared}
    v12: any = name_read heavy {pure}
    v13: any = slot_read a {read:shared}
    v14: double = call heavy(v13, v10) {pure}
    v15: double = call cap() {impure}
    v16: double = add v14, v15 {pure}
    v17: double = const 0.0
    v18: double = add v16, v17 {pure}
    v19: any = name_read Vec3 {pure}
    v20: double = const 2.0
    v21: double = const 0.0
    v22: double = const 0.0
    v23: any = record_new Vec3[v19](v20, v21, v22) {pure}
    slot_init c, v23 {write:shared,throws}
    br_if v0, bb2, bb1
  bb1:
    br bb3(v18)
  bb2:
    v24: any = name_read heavy {pure}
    v25: any = slot_read c {read:shared,throws}
    v26: double = call heavy(v25, v10) {pure}
    v27: double = add v18, v26 {pure}
    br bb3(v27)
  bb3(v28: double):
    ret v28
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> double:
  bb0:
    v1: any = name_read Vec3 {pure}
    v2: double = const 1.0
    v3: double = const 2.0
    v4: double = const 3.0
    v5: any = record_new Vec3[v1](v2, v3, v4) {pure}
    slot_init a, v5 {write:shared}
    v6: double = const 0.0
    v7: any = record_new Vec3[v1](v2, v6, v6) {pure}
    v8: any = opaque.FunctionRef(cap) {read:shared}
    v9: any = name_read heavy {pure}
    v10: any = slot_read a {read:shared}
    v11: double = call heavy(v10, v7) {pure}
    v12: double = call cap() {impure}
    v13: double = add v11, v12 {pure}
    v14: double = add v13, v6 {pure}
    v15: any = record_new Vec3[v1](v3, v6, v6) {pure}
    slot_init c, v15 {write:shared,throws}
    br_if v0, bb2, bb1
  bb1:
    br bb3(v14)
  bb2:
    v16: any = slot_read c {read:shared,throws}
    v17: double = call heavy(v16, v7) {pure}
    v18: double = add v14, v17 {pure}
    br bb3(v18)
  bb3(v19: double):
    ret v19
```

## Unboxed borrow param disqualifiers : captured cell param and reassigned param

```babble
def capP = fn (a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    def g = fn (): double -> ( a.x )
    g() + b.y
)
def reas = fn (var a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    a = Vec3(0.0, 0.0, 0.0) ; a.x + b.y
)
def v = Vec3(1.0, 2.0, 3.0)
assert(capP(v, v) == 3.0 and reas(v, v) == 2.0)
```

```hir
;; hir -O0 fn=capP
fn capP (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (block (return 0.0:double)))
  (def g (fn-ref g))
  (+:double (call-fn g) (get-this-field y))
```

```hir
;; hir -O1 fn=capP
fn capP (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (return 0.0:double))
  (def g (fn-ref g))
  (+:double (call-fn g) (get-this-field y))
```

```hir
;; hir -O0 fn=reas
fn reas (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (block (return 0.0:double)))
  (set a (record-new Vec3 0.0:double 0.0:double 0.0:double))
  (+:double (get-this-field x) (get-this-field y))
```

```hir
;; hir -O1 fn=reas
fn reas (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (return 0.0:double))
  (set a (record-new Vec3 0.0:double 0.0:double 0.0:double))
  (+:double (get-this-field x) (get-this-field y))
```

```mir
;; mir -O2 fn=reas stage=before
fn reas(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = const -999.0
    v4: bool = lt v2, v3 {pure}
    br_if v4, bb3, bb1
  bb1:
    v5: any = const null
    br bb2
  bb2:
    v6: any = name_read Vec3 {pure}
    v7: double = const 0.0
    v8: double = const 0.0
    v9: double = const 0.0
    v10: any = record_new Vec3[v6](v7, v8, v9) {pure}
    v11: double = load_field v10.x {read:frozen}
    v12: double = load_field v1.y {read:frozen}
    v13: double = add v11, v12 {pure}
    ret v13
  bb3:
    v14: double = const 0.0
    ret v14
```

```mir
;; mir -O2 fn=reas
fn reas(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = const -999.0
    v4: bool = lt v2, v3 {pure}
    br_if v4, bb2, bb1
  bb1:
    v5: any = name_read Vec3 {pure}
    v6: double = const 0.0
    v7: any = record_new Vec3[v5](v6, v6, v6) {pure}
    v8: double = load_field v7.x {read:frozen}
    v9: double = load_field v1.y {read:frozen}
    v10: double = add v8, v9 {pure}
    ret v10
  bb2:
    v11: double = const 0.0
    ret v11
```

## Anonymous borrow function unboxing and >8 flattened-field limit bailout

```babble
def apply = fn (f: fn (Vec3, Vec3): double, a: Vec3, b: Vec3): double -> ( f(a, b) )
def wide = fn (a: Vec3, b: Vec3, c: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 ) ; a.x + b.y + c.z
)
def v = Vec3(1.0, 2.0, 3.0)
def r = apply(fn (x: Vec3, y: Vec3): double -> (
    if x.x < -999.0 then ( return 0.0 ) ; x.x + y.y
), v, v)
assert(r == 3.0 and wide(v, v, v) == 6.0)
```

```hir
;; hir -O0 top-level
top-level:
  (def Vec3 (class-ref Vec3))
  (def Pair (class-ref Pair))
  (def Holder (class-ref Holder))
  (def mkVec (fn-ref mkVec))
  (def heavy (fn-ref heavy))
  (def apply (fn-ref apply))
  (def wide (fn-ref wide))
  (def v (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def r (call-fn apply (fn-ref <anon#0>) name(v) name(v)))
  (call name(assert) (and (==:bool name(r) 3.0:double) (==:bool (call-fn wide name(v) name(v) name(v)) 6.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def Vec3 (class-ref Vec3))
  (def Pair (class-ref Pair))
  (def Holder (class-ref Holder))
  (def mkVec (fn-ref mkVec))
  (def heavy (fn-ref heavy))
  (def apply (fn-ref apply))
  (def wide (fn-ref wide))
  (def v (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def r
    (block
      (def a name(v))
      (def b name(v))
      (call-fn <anon#0> (get-this-field x) (get-this-field y) (get-this-field z) (get-this-field x) (get-this-field y) (get-this-field z))))
  (call name(assert) (and (==:bool name(r) 3.0:double) (==:bool (call-fn wide name(v) name(v) name(v)) 6.0:double)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Unboxed body rewriting with uninitialized local, move, anonymous catch, and non-flattened param

```babble
def work = fn (a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    var tmp: double? = null
    def xs = list(a)
    def h = Holder(list(1), 2)
    def h2 = move(h)
    tmp = a.x + b.y + double(h2.count + xs.size())
    try ( if tmp! < 0.0 then ( assert(false) ) ; tmp! ) catch then ( 0.0 )
)
assert(work(Vec3(1.0, 2.0, 3.0), Vec3(4.0, 5.0, 6.0)) == 9.0)
```

```hir
;; hir -O0 fn=work
fn work (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (block (return 0.0:double)))
  (def tmp null)
  (def xs (core-new list var(a)))
  (def h (record-new Holder (core-new list 1:int) 2:int))
  (def h2 (move var(h)))
  (set tmp (+:double (+:double (get-this-field x) (get-this-field y)) (call name(double) (+:int (get-this-field count) (jcall List.size var(xs))))))
  (try
    (block
      (if (<:bool (nonnull var(tmp)) 0.0:double) (block (call name(assert) false:bool)))
      (nonnull var(tmp)))
    (catch _
      0.0:double))
```

```hir
;; hir -O1 fn=work
fn work (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (return 0.0:double))
  (def tmp null)
  (def xs (core-new list var(a)))
  (block
    (def $sr_h_0 (core-new list 1:int))
    (def $sr_h_1 2:int))
  (block (def $sr_h2_1 var($sr_h_1)))
  (set tmp (+:double (+:double (get-this-field x) (get-this-field y)) (call name(double) (+:int var($sr_h2_1) (jcall List.size var(xs))))))
  (try
    (block
      (if (<:bool (nonnull var(tmp)) 0.0:double) (call name(assert) false:bool))
      (nonnull var(tmp)))
    (catch _
      0.0:double))
```

```mir
;; mir -O2 fn=work stage=before
fn work(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = const -999.0
    v4: bool = lt v2, v3 {pure}
    br_if v4, bb11, bb1
  bb1:
    v5: any = const null
    br bb2
  bb2:
    v6: any = const null
    v7: any = opaque.CoreCollectionCall(list, v0) {impure}
    v8: int = const 1
    v9: any = opaque.CoreCollectionCall(list, v8) {impure}
    v10: int = const 2
    v11: double = load_field v0.x {read:frozen}
    v12: double = load_field v1.y {read:frozen}
    v13: double = add v11, v12 {pure}
    v14: any = name_read double {pure}
    v15: int = call_java v7.size() {pure,throws}
    v16: int = add v10, v15 {pure}
    v17: double = opaque.DynamicCall(v14, v16) {impure}
    v18: double = add v13, v17 {pure}
    v19: any = convert.any v18
    br bb3
  bb3 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v20: double = nonnull v19
    v21: double = const 0.0
    v22: bool = lt v20, v21 {pure}
    br_if v22, bb5, bb4
  bb4 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v23: any = const null
    br bb6(v23)
  bb5 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v24: any = name_read assert {pure}
    v25: bool = const false
    v26: bool = opaque.DynamicCall(v24, v25) {impure}
    v27: any = convert.any v26
    br bb6(v27)
  bb6(v28: any) [unwind: ThrownException -> bb8, Exception -> bb7]:
    v29: double = nonnull v19
    br bb10(v29)
  bb7(v30: any) [handler]:
    br bb9(v30)
  bb8(v31: any) [handler]:
    v32: any = unwrap_thrown v31
    br bb9(v32)
  bb9(v33: any):
    v34: double = const 0.0
    br bb10(v34)
  bb10(v35: double):
    ret v35
  bb11:
    v36: double = const 0.0
    ret v36
```

```mir
;; mir -O2 fn=work
fn work(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = const -999.0
    v4: bool = lt v2, v3 {pure}
    br_if v4, bb9, bb1
  bb1:
    v5: any = opaque.CoreCollectionCall(list, v0) {impure}
    v6: int = const 1
    v7: any = opaque.CoreCollectionCall(list, v6) {impure}
    v8: int = const 2
    v9: double = load_field v1.y {read:frozen}
    v10: double = add v2, v9 {pure}
    v11: any = name_read double {pure}
    v12: int = call_java v5.size() {pure,throws}
    v13: int = add v8, v12 {pure}
    v14: double = opaque.DynamicCall(v11, v13) {impure}
    v15: double = add v10, v14 {pure}
    v16: any = convert.any v15
    br bb2
  bb2 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v17: double = nonnull v16
    v18: double = const 0.0
    v19: bool = lt v17, v18 {pure}
    br_if v19, bb3, bb4
  bb3 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v20: any = name_read assert {pure}
    v21: bool = const false
    v22: bool = opaque.DynamicCall(v20, v21) {impure}
    br bb4
  bb4 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v23: double = nonnull v16
    br bb8(v23)
  bb5(v24: any) [handler]:
    br bb7
  bb6(v25: any) [handler]:
    br bb7
  bb7:
    v26: double = const 0.0
    br bb8(v26)
  bb8(v27: double):
    ret v27
  bb9:
    v28: double = const 0.0
    ret v28
```
