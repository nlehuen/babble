# SROA Candidate Verification and Destructured RHS Bailouts

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



## SROA candidate discovery bailouts : mismatched union assign, uninitialized def, empty block

```babble
def run = fn (fl: bool): int -> (
    def u: any? = ()
    var p: Vec3 | Pair = Vec3(1.0, 2.0, 3.0)
    if fl then ( p = Pair(4, 5) )
    var q: Vec3? = null ; q = Vec3(2.0, 3.0, 4.0)
    var tmp: int? = null ; tmp = 1
    (if p is Pair then ( p.a ) else ( 0 )) + int(q!.x) + tmp! + (if u == null then ( 0 ) else ( 1 ))
)
assert(run(true) == 7 and run(false) == 3)
```

```hir
;; hir -O0 fn=run
fn run (fl:bool) -> int {pure unique}
  (def u (block))
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (block (set p (record-new Pair 4:int 5:int))))
  (def q null)
  (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def tmp null)
  (set tmp 1:int)
  (+:int (+:int (+:int (block (if (is var(p) Pair) (block (get-this-field a)) (block 0:int))) (call name(int) (get-this-field x))) (nonnull var(tmp))) (block (if (==:bool var(u) null) (block 0:int) (block 1:int))))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> int {pure unique}
  (def u (block))
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set p (record-new Pair 4:int 5:int)))
  (def q null)
  (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def tmp null)
  (set tmp 1:int)
  (+:int (+:int (+:int (if (is var(p) Pair) (get-this-field a) 0:int) (call name(int) (get-this-field x))) (nonnull var(tmp))) (if (==:bool var(u) null) 0:int 1:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> int:
  bb0:
    v1: any = const null
    v2: any = opaque.NameRead(Vec3) {read:shared}
    v3: double = const 1.0
    v4: double = const 2.0
    v5: double = const 3.0
    v6: any = record_new Vec3[v2](v3, v4, v5) {pure}
    br_if v0, bb2, bb1
  bb1:
    v7: any = const null
    br bb3(v7, v6)
  bb2:
    v8: any = opaque.NameRead(Pair) {read:shared}
    v9: int = const 4
    v10: int = const 5
    v11: any = record_new Pair[v8](v9, v10) {pure}
    br bb3(v11, v11)
  bb3(v12: any, v13: any):
    v14: any = const null
    v15: any = opaque.NameRead(Vec3) {read:shared}
    v16: double = const 2.0
    v17: double = const 3.0
    v18: double = const 4.0
    v19: any = record_new Vec3[v15](v16, v17, v18) {pure}
    v20: any = const null
    v21: int = const 1
    v22: any = convert.any v21
    v23: bool = is v13 <babble|Pair>
    br_if v23, bb5, bb4
  bb4:
    v24: int = const 0
    br bb6(v24)
  bb5:
    v25: any = narrow.is v13 <babble|Pair>
    v26: int = load_field v25.a {read:frozen}
    br bb6(v26)
  bb6(v27: int):
    v28: any = opaque.NameRead(int) {read:shared,throws}
    v29: any = nonnull v19
    v30: double = load_field v29.x {read:frozen,throws}
    v31: int = opaque.DynamicCall(v28, v30) {impure}
    v32: int = add v27, v31 {pure}
    v33: int = nonnull v22
    v34: int = add v32, v33 {pure}
    v35: any = const null
    v36: any = eq v1, v35 {pure,throws}
    br_if v36, bb8, bb7
  bb7:
    v37: any = narrow.nonnull v1 <non-null>
    v38: int = const 1
    br bb9(v38)
  bb8:
    v39: int = const 0
    br bb9(v39)
  bb9(v40: int):
    v41: int = add v34, v40 {pure}
    ret v41
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> int:
  bb0:
    v1: any = const null
    v2: any = opaque.NameRead(Vec3) {read:shared}
    v3: double = const 1.0
    v4: double = const 2.0
    v5: double = const 3.0
    v6: any = record_new Vec3[v2](v3, v4, v5) {pure}
    br_if v0, bb2, bb1
  bb1:
    br bb3(v6)
  bb2:
    v7: any = opaque.NameRead(Pair) {read:shared}
    v8: int = const 4
    v9: int = const 5
    v10: any = record_new Pair[v7](v8, v9) {pure}
    br bb3(v10)
  bb3(v11: any):
    v12: any = opaque.NameRead(Vec3) {read:shared}
    v13: double = const 4.0
    v14: any = record_new Vec3[v12](v4, v5, v13) {pure}
    v15: int = const 1
    v16: any = const 1
    v17: bool = is v11 <babble|Pair>
    br_if v17, bb5, bb4
  bb4:
    v18: int = const 0
    br bb6(v18)
  bb5:
    v19: any = narrow.is v11 <babble|Pair>
    v20: int = load_field v19.a {read:frozen}
    br bb6(v20)
  bb6(v21: int):
    v22: any = opaque.NameRead(int) {read:shared,throws}
    v23: any = nonnull v14
    v24: double = load_field v23.x {read:frozen,throws}
    v25: int = opaque.DynamicCall(v22, v24) {impure}
    v26: int = add v21, v25 {pure}
    v27: int = nonnull v16
    v28: int = add v26, v27 {pure}
    v29: any = eq v1, v1 {pure,throws}
    br_if v29, bb8, bb7
  bb7:
    br bb9(v15)
  bb8:
    v30: int = const 0
    br bb9(v30)
  bb9(v31: int):
    v32: int = add v28, v31 {pure}
    ret v32
```

## SROA verification bailouts : typed def/assign, value-used assign, and statement if/else

```babble
def valA = fn (): Vec3 -> ( var p = Vec3(1.0, 2.0, 3.0) ; p = Vec3(4.0, 5.0, 6.0) )
def run = fn (fl: bool): double -> (
    var t: Vec3 = Vec3(1.0, 2.0, 3.0) ; t = Vec3(2.0, 3.0, 4.0)
    var s = Vec3(0.0, 0.0, 0.0)
    if fl then ( s = Vec3(1.0, 2.0, 3.0) ) else ( s = Vec3(4.0, 5.0, 6.0) )
    t.x + s.x
)
assert(valA().x == 4.0 and run(true) == 3.0 and run(false) == 6.0)
```

```hir
;; hir -O0 fn=run
fn run (fl:bool) -> double {nothrow unique}
  (def t (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (set t (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def s (record-new Vec3 0.0:double 0.0:double 0.0:double))
  (if var(fl) (block (set s (record-new Vec3 1.0:double 2.0:double 3.0:double))) (block (set s (record-new Vec3 4.0:double 5.0:double 6.0:double))))
  (+:double (get-this-field x) (get-this-field x))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> double {nothrow unique}
  (def t (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (set t (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (block
    (def $sr_s_0 0.0:double)
    (def $sr_s_1 0.0:double)
    (def $sr_s_2 0.0:double))
  (if var(fl)
    (block
      (set $sr_s_0 1.0:double)
      (set $sr_s_1 2.0:double)
      (set $sr_s_2 3.0:double))
    (block
      (set $sr_s_0 4.0:double)
      (set $sr_s_1 5.0:double)
      (set $sr_s_2 6.0:double)))
  (+:double (get-this-field x) var($sr_s_0))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> double:
  bb0:
    v1: any = opaque.NameRead(Vec3) {read:shared}
    v2: double = const 1.0
    v3: double = const 2.0
    v4: double = const 3.0
    v5: any = record_new Vec3[v1](v2, v3, v4) {pure}
    v6: any = opaque.NameRead(Vec3) {read:shared}
    v7: double = const 2.0
    v8: double = const 3.0
    v9: double = const 4.0
    v10: any = record_new Vec3[v6](v7, v8, v9) {pure}
    v11: double = const 0.0
    v12: double = const 0.0
    v13: double = const 0.0
    br_if v0, bb2, bb1
  bb1:
    v14: double = const 4.0
    v15: double = const 5.0
    v16: double = const 6.0
    v17: any = convert.any v16
    br bb3(v17, v14)
  bb2:
    v18: double = const 1.0
    v19: double = const 2.0
    v20: double = const 3.0
    v21: any = convert.any v20
    br bb3(v21, v18)
  bb3(v22: any, v23: double):
    v24: double = load_field v10.x {read:frozen}
    v25: double = add v24, v23 {pure}
    ret v25
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> double:
  bb0:
    v1: any = opaque.NameRead(Vec3) {read:shared}
    v2: double = const 1.0
    v3: double = const 2.0
    v4: double = const 3.0
    v5: double = const 4.0
    v6: any = record_new Vec3[v1](v3, v4, v5) {pure}
    br_if v0, bb2, bb1
  bb1:
    br bb3(v5)
  bb2:
    br bb3(v2)
  bb3(v7: double):
    v8: double = load_field v6.x {read:frozen}
    v9: double = add v8, v7 {pure}
    ret v9
```

## SROA destructured RHS bailouts : mixed if/else with non-inlined call and assign to call

```babble
def run = fn (fl: bool): double -> (
    def a = if fl then ( Vec3(1.0, 2.0, 3.0) ) else ( mkVec(4.0) )
    def b = if fl then ( mkVec(5.0) ) else ( Vec3(2.0, 3.0, 4.0) )
    var c = Vec3(1.0, 2.0, 3.0)
    if fl then ( c = mkVec(6.0) )
    a.x + b.x + c.x
)
assert(run(true) == 12.0 and run(false) == 7.0)
```

```hir
;; hir -O0 fn=run
fn run (fl:bool) -> double {nothrow unique}
  (def a (if var(fl) (block (record-new Vec3 1.0:double 2.0:double 3.0:double)) (block (call-fn mkVec 4.0:double))))
  (def b (if var(fl) (block (call-fn mkVec 5.0:double)) (block (record-new Vec3 2.0:double 3.0:double 4.0:double))))
  (def c (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (block (set c (call-fn mkVec 6.0:double))))
  (+:double (+:double (get-this-field x) (get-this-field x)) (get-this-field x))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> double {nothrow unique}
  (def a (if var(fl) (record-new Vec3 1.0:double 2.0:double 3.0:double) (call-fn mkVec 4.0:double)))
  (def b (if var(fl) (call-fn mkVec 5.0:double) (record-new Vec3 2.0:double 3.0:double 4.0:double)))
  (def c (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set c (call-fn mkVec 6.0:double)))
  (+:double (+:double (get-this-field x) (get-this-field x)) (get-this-field x))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(mkVec) {read:shared}
    v2: double = const 4.0
    v3: any = call mkVec(v2) {pure}
    br bb3(v3)
  bb2:
    v4: any = opaque.NameRead(Vec3) {read:shared}
    v5: double = const 1.0
    v6: double = const 2.0
    v7: double = const 3.0
    v8: any = record_new Vec3[v4](v5, v6, v7) {pure}
    br bb3(v8)
  bb3(v9: any):
    br_if v0, bb5, bb4
  bb4:
    v10: any = opaque.NameRead(Vec3) {read:shared}
    v11: double = const 2.0
    v12: double = const 3.0
    v13: double = const 4.0
    v14: any = record_new Vec3[v10](v11, v12, v13) {pure}
    br bb6(v14)
  bb5:
    v15: any = opaque.NameRead(mkVec) {read:shared}
    v16: double = const 5.0
    v17: any = call mkVec(v16) {pure}
    br bb6(v17)
  bb6(v18: any):
    v19: any = opaque.NameRead(Vec3) {read:shared}
    v20: double = const 1.0
    v21: double = const 2.0
    v22: double = const 3.0
    v23: any = record_new Vec3[v19](v20, v21, v22) {pure}
    br_if v0, bb8, bb7
  bb7:
    v24: any = const null
    br bb9(v24, v23)
  bb8:
    v25: any = opaque.NameRead(mkVec) {read:shared}
    v26: double = const 6.0
    v27: any = call mkVec(v26) {pure}
    br bb9(v27, v27)
  bb9(v28: any, v29: any):
    v30: double = load_field v9.x {read:frozen}
    v31: double = load_field v18.x {read:frozen}
    v32: double = add v30, v31 {pure}
    v33: double = load_field v29.x {read:frozen}
    v34: double = add v32, v33 {pure}
    ret v34
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(mkVec) {read:shared}
    v2: double = const 4.0
    v3: any = call mkVec(v2) {pure}
    br bb3(v3)
  bb2:
    v4: any = opaque.NameRead(Vec3) {read:shared}
    v5: double = const 1.0
    v6: double = const 2.0
    v7: double = const 3.0
    v8: any = record_new Vec3[v4](v5, v6, v7) {pure}
    br bb3(v8)
  bb3(v9: any):
    br_if v0, bb5, bb4
  bb4:
    v10: any = opaque.NameRead(Vec3) {read:shared}
    v11: double = const 2.0
    v12: double = const 3.0
    v13: double = const 4.0
    v14: any = record_new Vec3[v10](v11, v12, v13) {pure}
    br bb6(v14)
  bb5:
    v15: any = opaque.NameRead(mkVec) {read:shared}
    v16: double = const 5.0
    v17: any = call mkVec(v16) {pure}
    br bb6(v17)
  bb6(v18: any):
    v19: any = opaque.NameRead(Vec3) {read:shared}
    v20: double = const 1.0
    v21: double = const 2.0
    v22: double = const 3.0
    v23: any = record_new Vec3[v19](v20, v21, v22) {pure}
    br_if v0, bb8, bb7
  bb7:
    br bb9(v23)
  bb8:
    v24: any = opaque.NameRead(mkVec) {read:shared}
    v25: double = const 6.0
    v26: any = call mkVec(v25) {pure}
    br bb9(v26)
  bb9(v27: any):
    v28: double = load_field v9.x {read:frozen}
    v29: double = load_field v18.x {read:frozen}
    v30: double = add v28, v29 {pure}
    v31: double = load_field v27.x {read:frozen}
    v32: double = add v30, v31 {pure}
    ret v32
```

## SROA destructured RHS bailouts : as-cast, non-candidate copy, and mismatched candidate copy

```babble
def run = fn (x: any, pv: Vec3, fl: bool): double -> (
    var p = Vec3(1.0, 2.0, 3.0) ; p = (x as Vec3)
    var q = Vec3(1.0, 2.0, 3.0) ; if fl then ( q = pv )
    def a = Vec3(1.0, 2.0, 3.0) ; def b = Pair(4, 5)
    def m = if fl then ( a ) else ( b )
    p.x + q.x + (if m is Pair then ( double(m.a) ) else ( a.x ))
)
assert(run(Vec3(3.0, 0.0, 0.0), Vec3(5.0, 0.0, 0.0), false) == 8.0)
```

```hir
;; hir -O0 fn=run
fn run (x:obj, pv:obj, fl:bool) -> double {pure unique}
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (set p (block (as var(x) babble|Vec3)))
  (def q (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (block (set q var(pv))))
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Pair 4:int 5:int))
  (def m (if var(fl) (block var(a)) (block var(b))))
  (+:double (+:double (get-this-field x) (get-this-field x)) (block (if (is var(m) Pair) (block (call name(double) (get-this-field a))) (block (get-this-field x)))))
```

```hir
;; hir -O1 fn=run
fn run (x:obj, pv:obj, fl:bool) -> double {pure unique}
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (set p (as var(x) babble|Vec3))
  (def q (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set q var(pv)))
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Pair 4:int 5:int))
  (def m (if var(fl) var(a) var(b)))
  (+:double (+:double (get-this-field x) (get-this-field x)) (if (is var(m) Pair) (call name(double) (get-this-field a)) (get-this-field x)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any, v2: bool) -> double:
  bb0:
    v3: any = opaque.NameRead(Vec3) {read:shared}
    v4: double = const 1.0
    v5: double = const 2.0
    v6: double = const 3.0
    v7: any = record_new Vec3[v3](v4, v5, v6) {pure}
    v8: any = checkcast v0 <babble|Vec3>
    v9: any = opaque.NameRead(Vec3) {read:shared}
    v10: double = const 1.0
    v11: double = const 2.0
    v12: double = const 3.0
    v13: any = record_new Vec3[v9](v10, v11, v12) {pure}
    br_if v2, bb2, bb1
  bb1:
    v14: any = const null
    br bb3(v14, v13)
  bb2:
    br bb3(v1, v1)
  bb3(v15: any, v16: any):
    v17: any = opaque.NameRead(Vec3) {read:shared}
    v18: double = const 1.0
    v19: double = const 2.0
    v20: double = const 3.0
    v21: any = record_new Vec3[v17](v18, v19, v20) {pure}
    v22: any = opaque.NameRead(Pair) {read:shared}
    v23: int = const 4
    v24: int = const 5
    v25: any = record_new Pair[v22](v23, v24) {pure}
    br_if v2, bb5, bb4
  bb4:
    br bb6(v25)
  bb5:
    br bb6(v21)
  bb6(v26: any):
    v27: double = load_field v8.x {read:frozen}
    v28: double = load_field v16.x {read:frozen}
    v29: double = add v27, v28 {pure}
    v30: bool = is v26 <babble|Pair>
    br_if v30, bb8, bb7
  bb7:
    v31: double = load_field v21.x {read:frozen}
    br bb9(v31)
  bb8:
    v32: any = narrow.is v26 <babble|Pair>
    v33: any = opaque.NameRead(double) {read:shared,throws}
    v34: int = load_field v32.a {read:frozen}
    v35: double = opaque.DynamicCall(v33, v34) {impure}
    br bb9(v35)
  bb9(v36: double):
    v37: double = add v29, v36 {pure}
    ret v37
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any, v2: bool) -> double:
  bb0:
    v3: any = opaque.NameRead(Vec3) {read:shared}
    v4: double = const 1.0
    v5: double = const 2.0
    v6: double = const 3.0
    v7: any = checkcast v0 <babble|Vec3>
    v8: any = record_new Vec3[v3](v4, v5, v6) {pure}
    br_if v2, bb2, bb1
  bb1:
    br bb3(v8)
  bb2:
    br bb3(v1)
  bb3(v9: any):
    v10: any = opaque.NameRead(Vec3) {read:shared}
    v11: any = record_new Vec3[v10](v4, v5, v6) {pure}
    v12: any = opaque.NameRead(Pair) {read:shared}
    v13: int = const 4
    v14: int = const 5
    v15: any = record_new Pair[v12](v13, v14) {pure}
    br_if v2, bb5, bb4
  bb4:
    br bb6(v15)
  bb5:
    br bb6(v11)
  bb6(v16: any):
    v17: double = load_field v7.x {read:frozen}
    v18: double = load_field v9.x {read:frozen}
    v19: double = add v17, v18 {pure}
    v20: bool = is v16 <babble|Pair>
    br_if v20, bb8, bb7
  bb7:
    v21: double = load_field v11.x {read:frozen}
    br bb9(v21)
  bb8:
    v22: any = narrow.is v16 <babble|Pair>
    v23: any = opaque.NameRead(double) {read:shared,throws}
    v24: int = load_field v22.a {read:frozen}
    v25: double = opaque.DynamicCall(v23, v24) {impure}
    br bb9(v25)
  bb9(v26: double):
    v27: double = add v19, v26 {pure}
    ret v27
```

## SROA destructured RHS bailouts : mismatched RecordNew, empty block, and one-armed if

```babble
def run = fn (f: bool): double -> (
  def u = if f then (Vec3(1.0, 2.0, 3.0)) else (Pair(4, 5))
  def e = if f then (Vec3(1.0, 2.0, 3.0)) else ()
  def o = if f then (Vec3(1.0, 2.0, 3.0)) else (if not f then (Vec3(2.0, 0.0, 0.0)))
  (if u is Pair then (4.0) else (1.0)) + (if e != null then (e.x) else (0.0)) + (if o != null then (o.x) else (0.0))
)
assert(run(false) == 6.0 and run(true) == 3.0)
```

```hir
;; hir -O0 fn=run
fn run (f:bool) -> double {nothrow unique}
  (def u (if var(f) (block (record-new Vec3 1.0:double 2.0:double 3.0:double)) (block (record-new Pair 4:int 5:int))))
  (def e (if var(f) (block (record-new Vec3 1.0:double 2.0:double 3.0:double)) (block)))
  (def o (if var(f) (block (record-new Vec3 1.0:double 2.0:double 3.0:double)) (block (if (not var(f)) (block (record-new Vec3 2.0:double 0.0:double 0.0:double))))))
  (+:double (+:double (block (if (is var(u) Pair) (block 4.0:double) (block 1.0:double))) (block (if (<>:bool var(e) null) (block (get-this-field x)) (block 0.0:double)))) (block (if (<>:bool var(o) null) (block (get-this-field x)) (block 0.0:double))))
```

```hir
;; hir -O1 fn=run
fn run (f:bool) -> double {nothrow unique}
  (def u (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (record-new Pair 4:int 5:int)))
  (def e (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (block)))
  (def o (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (if (not var(f)) (record-new Vec3 2.0:double 0.0:double 0.0:double))))
  (+:double (+:double (if (is var(u) Pair) 4.0:double 1.0:double) (if (<>:bool var(e) null) (get-this-field x) 0.0:double)) (if (<>:bool var(o) null) (get-this-field x) 0.0:double))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(Pair) {read:shared}
    v2: int = const 4
    v3: int = const 5
    v4: any = record_new Pair[v1](v2, v3) {pure}
    br bb3(v4)
  bb2:
    v5: any = opaque.NameRead(Vec3) {read:shared}
    v6: double = const 1.0
    v7: double = const 2.0
    v8: double = const 3.0
    v9: any = record_new Vec3[v5](v6, v7, v8) {pure}
    br bb3(v9)
  bb3(v10: any):
    br_if v0, bb5, bb4
  bb4:
    v11: any = const null
    br bb6(v11)
  bb5:
    v12: any = opaque.NameRead(Vec3) {read:shared}
    v13: double = const 1.0
    v14: double = const 2.0
    v15: double = const 3.0
    v16: any = record_new Vec3[v12](v13, v14, v15) {pure}
    br bb6(v16)
  bb6(v17: any):
    br_if v0, bb11, bb7
  bb7:
    v18: bool = not v0 {pure}
    br_if v18, bb9, bb8
  bb8:
    v19: any = const null
    br bb10(v19)
  bb9:
    v20: any = opaque.NameRead(Vec3) {read:shared}
    v21: double = const 2.0
    v22: double = const 0.0
    v23: double = const 0.0
    v24: any = record_new Vec3[v20](v21, v22, v23) {pure}
    br bb10(v24)
  bb10(v25: any):
    br bb12(v25)
  bb11:
    v26: any = opaque.NameRead(Vec3) {read:shared}
    v27: double = const 1.0
    v28: double = const 2.0
    v29: double = const 3.0
    v30: any = record_new Vec3[v26](v27, v28, v29) {pure}
    br bb12(v30)
  bb12(v31: any):
    v32: bool = is v10 <babble|Pair>
    br_if v32, bb14, bb13
  bb13:
    v33: double = const 1.0
    br bb15(v33)
  bb14:
    v34: any = narrow.is v10 <babble|Pair>
    v35: double = const 4.0
    br bb15(v35)
  bb15(v36: double):
    v37: any = const null
    v38: any = neq v17, v37 {pure,throws}
    br_if v38, bb17, bb16
  bb16:
    v39: double = const 0.0
    br bb18(v39)
  bb17:
    v40: any = narrow.nonnull v17 <non-null>
    v41: double = load_field v40.x {read:frozen}
    br bb18(v41)
  bb18(v42: double):
    v43: double = add v36, v42 {pure}
    v44: any = const null
    v45: any = neq v31, v44 {pure,throws}
    br_if v45, bb20, bb19
  bb19:
    v46: double = const 0.0
    br bb21(v46)
  bb20:
    v47: any = narrow.nonnull v31 <non-null>
    v48: double = load_field v47.x {read:frozen}
    br bb21(v48)
  bb21(v49: double):
    v50: double = add v43, v49 {pure}
    ret v50
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(Pair) {read:shared}
    v2: int = const 4
    v3: int = const 5
    v4: any = record_new Pair[v1](v2, v3) {pure}
    br bb3(v4)
  bb2:
    v5: any = opaque.NameRead(Vec3) {read:shared}
    v6: double = const 1.0
    v7: double = const 2.0
    v8: double = const 3.0
    v9: any = record_new Vec3[v5](v6, v7, v8) {pure}
    br bb3(v9)
  bb3(v10: any):
    br_if v0, bb5, bb4
  bb4:
    v11: any = const null
    br bb6(v11)
  bb5:
    v12: any = opaque.NameRead(Vec3) {read:shared}
    v13: double = const 1.0
    v14: double = const 2.0
    v15: double = const 3.0
    v16: any = record_new Vec3[v12](v13, v14, v15) {pure}
    br bb6(v16)
  bb6(v17: any):
    br_if v0, bb11, bb7
  bb7:
    v18: bool = not v0 {pure}
    br_if v18, bb9, bb8
  bb8:
    v19: any = const null
    br bb10(v19)
  bb9:
    v20: any = opaque.NameRead(Vec3) {read:shared}
    v21: double = const 2.0
    v22: double = const 0.0
    v23: any = record_new Vec3[v20](v21, v22, v22) {pure}
    br bb10(v23)
  bb10(v24: any):
    br bb12(v24)
  bb11:
    v25: any = opaque.NameRead(Vec3) {read:shared}
    v26: double = const 1.0
    v27: double = const 2.0
    v28: double = const 3.0
    v29: any = record_new Vec3[v25](v26, v27, v28) {pure}
    br bb12(v29)
  bb12(v30: any):
    v31: bool = is v10 <babble|Pair>
    br_if v31, bb14, bb13
  bb13:
    v32: double = const 1.0
    br bb15(v32)
  bb14:
    v33: double = const 4.0
    br bb15(v33)
  bb15(v34: double):
    v35: any = const null
    v36: any = neq v17, v35 {pure,throws}
    br_if v36, bb17, bb16
  bb16:
    v37: double = const 0.0
    br bb18(v37)
  bb17:
    v38: any = narrow.nonnull v17 <non-null>
    v39: double = load_field v38.x {read:frozen}
    br bb18(v39)
  bb18(v40: double):
    v41: double = add v34, v40 {pure}
    v42: any = neq v30, v35 {pure,throws}
    br_if v42, bb20, bb19
  bb19:
    v43: double = const 0.0
    br bb21(v43)
  bb20:
    v44: any = narrow.nonnull v30 <non-null>
    v45: double = load_field v44.x {read:frozen}
    br bb21(v45)
  bb21(v46: double):
    v47: double = add v41, v46 {pure}
    ret v47
```
