# Record LICM, Pure Interface DCE, and Try Spill Normalization

References : BIP014-I4, BIP007-I2, FbipReusePass, CoreCollections

```babble
;; babble module=vec_mod.ba
export def ExtVec = record (x: double, y: double) (
    def plus = fn (o: ExtVec): ExtVec -> ( ExtVec(this.x + o.x, this.y + o.y) )
    def neg = fn (): ExtVec -> ( ExtVec(-this.x, -this.y) )
    def mag2 = fn (): double -> ( this.x * this.x + this.y * this.y )
)
```

```babble
;; babble preamble
def Vec3 = record (x: double, y: double, z: double) (
    def plus = fn (o: Vec3): Vec3 -> ( Vec3(this.x + o.x, this.y + o.y, this.z + o.z) )
    def dot = fn (o: Vec3): double -> ( this.x * o.x + this.y * o.y + this.z * o.z )
    def swap = fn (): Vec3 -> ( Vec3(this.y, this.x, this.z) )
)
def Holder = record (items: list of int, count: int) ()
def Metric = interface ( def score = fn nothrow (): int -> ( 0 ) )
```


## Pure interface DCE and record/frozen field LICM

```babble
def Box = record (v: int) implements Metric (
    def score = fn nothrow (): int -> ( this.v * 2 )
)
def run = fn (m: frozen Metric, b: Box, n: int): int -> (
    def unused = m.score()
    var i = 0
    var s = 0
    while i < n then ( s = s + m.score() + b.v + i ; i = i + 1 )
    s
)
assert(run(Box(5), Box(3), 4) == 58)
```

```hir
;; hir -O0 fn=run
fn run (m:obj, b:obj, n:int) -> int {nothrow unique}
  (def unused (call-iface var(m).score))
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s (+:int (+:int (+:int var(s) (call-iface var(m).score)) (get-this-field v)) var(i)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run (m:obj, b:obj, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (block
    (def $licm0 (call-iface var(m).score))
    (def $licm1 (get-this-field v))
    (while (<:bool var(i) var(n))
      (block
        (set s (+:int (+:int (+:int var(s) var($licm0)) var($licm1)) var(i)))
        (set i (+:int var(i) 1:int)))))
  var(s)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: int = call_iface v0.score() {pure}
    v6: int = load_field v1.v {read:frozen}
    v7: any = const null
    br bb1(v7, v3, v4)
  bb1(v8: any, v9: int, v10: int):
    v11: bool = lt v9, v2 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: int = add v10, v5 {pure}
    v13: int = add v12, v6 {pure}
    v14: int = add v13, v9 {pure}
    v15: int = const 1
    v16: int = add v9, v15 {pure}
    v17: any = convert.any v16
    br bb1(v17, v16, v14)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = call_iface v0.score() {pure}
    v5: int = load_field v1.v {read:frozen}
    br bb1(v3, v3)
  bb1(v6: int, v7: int):
    v8: bool = lt v6, v2 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: int = add v7, v4 {pure}
    v10: int = add v9, v5 {pure}
    v11: int = add v10, v6 {pure}
    v12: int = const 1
    v13: int = add v6, v12 {pure}
    br bb1(v13, v11)
```

## LICM on non-inlined pure record method call

```babble
def B = record (x: double, y: double) (
    def c = fn (a: double): double -> (
        def t = this.x + this.y + a
        (t + a) * (t + 1.0) + (t + 2.0) * (t + 3.0) + (t + 4.0) * (t + 5.0) + t * 2.0
    )
)
def r = fn (): double -> (
    def b = B(1.0, 2.0) ; var s = 0.0 ; var i = 0
    while i < 3 then ( s = s + b.c(1.0) ; i = i + 1 )
    s
)
assert(r() == 441.0)
```

```hir
;; hir -O0 fn=r
fn r () -> double {nothrow unique}
  (def b (record-new B 1.0:double 2.0:double))
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (set s (+:double var(s) (call-method var(b).c 1.0:double)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```hir
;; hir -O1 fn=r
fn r () -> double {nothrow unique}
  (block
    (def $sr_b_0 1.0:double)
    (def $sr_b_1 2.0:double))
  (def s 0.0:double)
  (def i 0:int)
  (block
    (def $licm0
      (block
        (def t (+:double (+:double var($sr_b_0) var($sr_b_1)) 1.0:double))
        (+:double (+:double (+:double (*:double (+:double var(t) 1.0:double) (+:double var(t) 1.0:double)) (*:double (+:double var(t) 2.0:double) (+:double var(t) 3.0:double))) (*:double (+:double var(t) 4.0:double) (+:double var(t) 5.0:double))) (*:double var(t) 2.0:double))))
    (while (<:bool var(i) 3:int)
      (block
        (set s (+:double var(s) var($licm0)))
        (set i (+:int var(i) 1:int)))))
  var(s)
```

```mir
;; mir -O2 fn=r stage=before
fn r() -> double:
  bb0:
    v0: double = const 1.0
    v1: double = const 2.0
    v2: double = const 0.0
    v3: int = const 0
    v4: double = add v0, v1 {pure}
    v5: double = const 1.0
    v6: double = add v4, v5 {pure}
    v7: double = const 1.0
    v8: double = add v6, v7 {pure}
    v9: double = const 1.0
    v10: double = add v6, v9 {pure}
    v11: double = mul v8, v10 {pure}
    v12: double = const 2.0
    v13: double = add v6, v12 {pure}
    v14: double = const 3.0
    v15: double = add v6, v14 {pure}
    v16: double = mul v13, v15 {pure}
    v17: double = add v11, v16 {pure}
    v18: double = const 4.0
    v19: double = add v6, v18 {pure}
    v20: double = const 5.0
    v21: double = add v6, v20 {pure}
    v22: double = mul v19, v21 {pure}
    v23: double = add v17, v22 {pure}
    v24: double = const 2.0
    v25: double = mul v6, v24 {pure}
    v26: double = add v23, v25 {pure}
    v27: any = const null
    br bb1(v27, v3, v2)
  bb1(v28: any, v29: int, v30: double):
    v31: int = const 3
    v32: bool = lt v29, v31 {pure}
    br_if v32, bb3, bb2
  bb2:
    ret v30
  bb3:
    v33: double = add v30, v26 {pure}
    v34: int = const 1
    v35: int = add v29, v34 {pure}
    v36: any = convert.any v35
    br bb1(v36, v35, v33)
```

```mir
;; mir -O2 fn=r
fn r() -> double:
  bb0:
    v0: double = const 0.0
    v1: int = const 0
    v2: double = const 147.0
    br bb1(v1, v0)
  bb1(v3: int, v4: double):
    v5: int = const 3
    v6: bool = lt v3, v5 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v4
  bb3:
    v7: double = add v4, v2 {pure}
    v8: int = const 1
    v9: int = add v3, v8 {pure}
    br bb1(v9, v7)
```

## LICM on frozen mutable class field read

```babble
def X = class (var v: int) ()
def r = fn (): int -> (
    def f = freeze(X(5)) ; var s = 0 ; var i = 0
    while i < 3 then ( s = s + f.v ; i = i + 1 )
    s
)
assert(r() == 15)
```

```hir
;; hir -O0,-O1 fn=r
fn r () -> int {nothrow unique}
  (def f (freeze (call name(X) 5:int)))
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (set s (+:int var(s) (get-this-field v)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```mir
;; mir -O2 fn=r stage=before
fn r() -> int:
  bb0:
    v0: any = opaque.NameRead(X) {read:shared}
    v1: int = const 5
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = freeze v2
    v4: int = const 0
    v5: int = const 0
    v6: any = const null
    br bb1(v6, v5, v4)
  bb1(v7: any, v8: int, v9: int):
    v10: int = const 3
    v11: bool = lt v8, v10 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v9
  bb3:
    v12: int = load_field v3.v {read:frozen,throws}
    v13: int = add v9, v12 {pure}
    v14: int = const 1
    v15: int = add v8, v14 {pure}
    v16: any = convert.any v15
    br bb1(v16, v15, v13)
```

```mir
;; mir -O2 fn=r
fn r() -> int:
  bb0:
    v0: any = opaque.NameRead(X) {read:shared}
    v1: int = const 5
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = freeze v2
    v4: int = const 0
    br bb1(v4, v4)
  bb1(v5: int, v6: int):
    v7: int = const 3
    v8: bool = lt v5, v7 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v6
  bb3:
    v9: int = load_field v3.v {read:frozen,throws}
    v10: int = add v6, v9 {pure}
    v11: int = const 1
    v12: int = add v5, v11 {pure}
    br bb1(v12, v10)
```

## LICM on pure interface call with args : immutable record hoisted, mutable class not hoisted

```babble
def Op = interface ( def f: fn (int): int )
def IO = record (k: int) implements Op ( def f = fn (n: int): int -> ( this.k + n ) )
def MO = class (def k: int) implements Op ( def f = fn (n: int): int -> ( this.k + n ) )
def r = fn (a: Op, b: Op): int -> (
    var s = 0 ; var i = 0
    while i < 3 then ( s = s + a.f(2) + b.f(2) ; i = i + 1 )
    s
)
assert(r(IO(10), MO(20)) == 102)
```

```hir
;; hir -O0,-O1 fn=r
fn r (a:obj, b:obj) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (set s (+:int (+:int var(s) (call-iface var(a).f 2:int)) (call-iface var(b).f 2:int)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```mir
;; mir -O2 fn=r stage=before
fn r(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: int):
    v8: int = const 3
    v9: bool = lt v6, v8 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: int = const 2
    v11: int = call_iface v0.f(v10) {pure,throws}
    v12: int = add v7, v11 {pure}
    v13: int = const 2
    v14: int = call_iface v1.f(v13) {pure,throws}
    v15: int = add v12, v14 {pure}
    v16: int = const 1
    v17: int = add v6, v16 {pure}
    v18: any = convert.any v17
    br bb1(v18, v17, v15)
```

```mir
;; mir -O2 fn=r
fn r(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 0
    br bb1(v2, v2)
  bb1(v3: int, v4: int):
    v5: int = const 3
    v6: bool = lt v3, v5 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v4
  bb3:
    v7: int = const 2
    v8: int = call_iface v0.f(v7) {pure,throws}
    v9: int = add v4, v8 {pure}
    v10: int = call_iface v1.f(v7) {pure,throws}
    v11: int = add v9, v10 {pure}
    v12: int = const 1
    v13: int = add v3, v12 {pure}
    br bb1(v13, v11)
```

## LICM invariant operand checks with top-level NameRead and freeze/as expressions

```babble
def TOP_K = 7
def run = fn (x: int): double -> (
    var s = 0.0 ; var i = 0
    while i < 3 then (
        var v = freeze(Vec3(double(x), 1.0, 0.0))
        s = s + double(TOP_K) + (x as double) + v.x
        i = i + 1
    )
    s
)
assert(run(3) == 39.0)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> double {pure unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (def v (freeze (record-new Vec3 (call name(double) var(x)) 1.0:double 0.0:double)))
      (set s (+:double (+:double (+:double var(s) (call name(double) name(TOP_K))) (block (as var(x) double|double|))) (get-this-field x)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> double {pure unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (block
        (def $sr_v_0 (call name(double) var(x)))
        (def $sr_v_2 0.0:double))
      (set s (+:double (+:double (+:double var(s) (call name(double) name(TOP_K))) (as var(x) double|double|)) var($sr_v_0)))
      (set i (+:int var(i) 1:int))))
  var(s)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v2, v1)
  bb1(v4: any, v5: int, v6: double):
    v7: int = const 3
    v8: bool = lt v5, v7 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v6
  bb3:
    v9: any = opaque.NameRead(double) {read:shared,throws}
    v10: double = opaque.DynamicCall(v9, v0) {impure}
    v11: double = const 0.0
    v12: any = opaque.NameRead(double) {read:shared,throws}
    v13: any = opaque.NameRead(TOP_K) {read:shared}
    v14: double = opaque.DynamicCall(v12, v13) {impure}
    v15: double = add v6, v14 {pure}
    v16: any = convert.any v0
    v17: any = checkcast v16 <double|double|>
    v18: double = convert.double v17
    v19: double = add v15, v18 {pure}
    v20: double = add v19, v10 {pure}
    v21: int = const 1
    v22: int = add v5, v21 {pure}
    v23: any = convert.any v22
    br bb1(v23, v22, v20)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = convert.any v0
    br bb1(v2, v1)
  bb1(v4: int, v5: double):
    v6: int = const 3
    v7: bool = lt v4, v6 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v5
  bb3:
    v8: any = opaque.NameRead(double) {read:shared,throws}
    v9: double = opaque.DynamicCall(v8, v0) {impure}
    v10: any = opaque.NameRead(double) {read:shared,throws}
    v11: any = opaque.NameRead(TOP_K) {read:shared}
    v12: double = opaque.DynamicCall(v10, v11) {impure}
    v13: double = add v5, v12 {pure}
    v14: any = checkcast v3 <double|double|>
    v15: double = convert.double v14
    v16: double = add v13, v15 {pure}
    v17: double = add v16, v9 {pure}
    v18: int = const 1
    v19: int = add v4, v18 {pure}
    br bb1(v19, v17)
```

## Try expression inside RecordNew and RecordReuse arguments (stack-empty normalization)

```babble
def bump = fn (unique h: Holder, fl: bool): Holder -> (
    Holder(h.items, try ( if fl then ( assert(false) ) ; h.count + 1 ) catch e then ( 9 ))
)
def r = fn (fl: bool): double -> (
    var v = Vec3(1.0, try ( if fl then ( assert(false) ) ; 2.0 ) catch e then ( 8.0 ), 3.0)
    v.y + double(bump(Holder(list(1), 4), fl).count)
)
assert(r(false) == 7.0 and r(true) == 17.0)
```

```hir
;; hir -O0 fn=bump
fn bump (h:obj, fl:bool) -> obj {nothrow}
  (block
    (def $try0 name(Holder))
    (def $try1 (get-this-field items))
    (def $try2
      (try
        (block
          (if var(fl) (block (call name(assert) false:bool)))
          (+:int (get-this-field count) 1:int))
        (catch e
          9:int)))
    (record-new Holder var($try1) var($try2)))
```

```hir
;; hir -O1 fn=bump
fn bump (h:obj, fl:bool) -> obj {nothrow}
  (block
    (def $try0 var(h))
    (def $try1 (get-this-field items))
    (def $try2
      (try
        (block
          (if var(fl) (call name(assert) false:bool))
          (+:int (get-this-field count) 1:int))
        (catch e
          9:int)))
    (record-reuse var($try0) Holder var($try1) var($try2)))
```

```mir
;; mir -O2 fn=bump stage=before
fn bump(v0: any [unique], v1: bool) -> any:
  bb0:
    v2: any = load_field v0.items {read:frozen}
    br bb1
  bb1 [unwind: ThrownException -> bb6, Exception -> bb5]:
    br_if v1, bb3, bb2
  bb2 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v3: any = const null
    br bb4(v3)
  bb3 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: bool = const false
    v6: bool = opaque.DynamicCall(v4, v5) {impure}
    v7: any = convert.any v6
    br bb4(v7)
  bb4(v8: any) [unwind: ThrownException -> bb6, Exception -> bb5]:
    v9: int = load_field v0.count {read:frozen}
    v10: int = const 1
    v11: int = add v9, v10 {pure}
    br bb8(v11)
  bb5(v12: any) [handler]:
    br bb7(v12)
  bb6(v13: any) [handler]:
    v14: any = unwrap_thrown v13
    br bb7(v14)
  bb7(v15: any):
    v16: int = const 9
    br bb8(v16)
  bb8(v17: int):
    v18: any = record_reuse v0 as Holder(v2, v17) {write:shared}
    ret v18
```

```mir
;; mir -O2 fn=bump
fn bump(v0: any [unique], v1: bool) -> any:
  bb0:
    v2: any = load_field v0.items {read:frozen}
    br bb1
  bb1 [unwind: ThrownException -> bb5, Exception -> bb4]:
    br_if v1, bb2, bb3
  bb2 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v3: any = opaque.NameRead(assert) {read:shared,throws}
    v4: bool = const false
    v5: bool = opaque.DynamicCall(v3, v4) {impure}
    br bb3
  bb3 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v6: int = load_field v0.count {read:frozen}
    v7: int = const 1
    v8: int = add v6, v7 {pure}
    br bb7(v8)
  bb4(v9: any) [handler]:
    br bb6
  bb5(v10: any) [handler]:
    br bb6
  bb6:
    v11: int = const 9
    br bb7(v11)
  bb7(v12: int):
    v13: any = record_reuse v0 as Holder(v2, v12) {write:shared}
    ret v13
```

## Try expression inside ThisFieldAssign and DirectThisMethodCall

```babble
def Box = class (var v: int) (
    def put = fn mut (fl: bool): int -> ( this.v = try ( if fl then ( assert(false) ) ; 7 ) catch e then ( 8 ) ; this.v )
    def add = fn (n: int): int -> ( this.v + n )
)
def r = fn (fl: bool): int -> ( def b = Box(1) ; b.put(fl) ; b.add(try ( if fl then ( assert(false) ) ; 2 ) catch e then ( 3 )) )
assert(r(false) == 9 and r(true) == 11)
```

```hir
;; hir -O0 fn=r
fn r (fl:bool) -> int {nothrow unique}
  (def b (call name(Box) 1:int))
  (call-method var(b).put var(fl))
  (block
    (def $try0 var(b))
    (def $try1
      (try
        (block
          (if var(fl) (block (call name(assert) false:bool)))
          2:int)
        (catch e
          3:int)))
    (call-method var($try0).add var($try1)))
```

```hir
;; hir -O1 fn=r
fn r (fl:bool) -> int {nothrow unique}
  (def b (call name(Box) 1:int))
  (call-method var(b).put var(fl))
  (block
    (def n
      (try
        (block
          (if var(fl) (call name(assert) false:bool))
          2:int)
        (catch e
          3:int)))
    (+:int (get-this-field v) var(n)))
```

```mir
;; mir -O2 fn=r stage=before
fn r(v0: bool) -> int:
  bb0:
    v1: any = opaque.NameRead(Box) {read:shared}
    v2: int = const 1
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: int = call_method v3.put(v0) {impure}
    br bb1
  bb1 [unwind: ThrownException -> bb6, Exception -> bb5]:
    br_if v0, bb3, bb2
  bb2 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v5: any = const null
    br bb4(v5)
  bb3 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v6: any = opaque.NameRead(assert) {read:shared,throws}
    v7: bool = const false
    v8: bool = opaque.DynamicCall(v6, v7) {impure}
    v9: any = convert.any v8
    br bb4(v9)
  bb4(v10: any) [unwind: ThrownException -> bb6, Exception -> bb5]:
    v11: int = const 2
    br bb8(v11)
  bb5(v12: any) [handler]:
    br bb7(v12)
  bb6(v13: any) [handler]:
    v14: any = unwrap_thrown v13
    br bb7(v14)
  bb7(v15: any):
    v16: int = const 3
    br bb8(v16)
  bb8(v17: int):
    v18: int = load_field v3.v {read:shared,throws}
    v19: int = add v18, v17 {pure}
    ret v19
```

```mir
;; mir -O2 fn=r
fn r(v0: bool) -> int:
  bb0:
    v1: any = opaque.NameRead(Box) {read:shared}
    v2: int = const 1
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: int = call_method v3.put(v0) {impure}
    br bb1
  bb1 [unwind: ThrownException -> bb5, Exception -> bb4]:
    br_if v0, bb2, bb3
  bb2 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v5: any = opaque.NameRead(assert) {read:shared,throws}
    v6: bool = const false
    v7: bool = opaque.DynamicCall(v5, v6) {impure}
    br bb3
  bb3 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v8: int = const 2
    br bb7(v8)
  bb4(v9: any) [handler]:
    br bb6
  bb5(v10: any) [handler]:
    br bb6
  bb6:
    v11: int = const 3
    br bb7(v11)
  bb7(v12: int):
    v13: int = load_field v3.v {read:shared,throws}
    v14: int = add v13, v12 {pure}
    ret v14
```

## Try expression inside top-level NameAssign

```babble
var top = 0
def r = fn io (fl: bool): int -> ( top = try ( if fl then ( assert(false) ) ; 10 ) catch e then ( 20 ) ; top )
assert(r(false) == 10 and r(true) == 20)
```

```hir
;; hir -O0 fn=r
fn r (fl:bool) -> int {io}
  (block
    (def $try0
      (try
        (block
          (if var(fl) (block (call name(assert) false:bool)))
          10:int)
        (catch e
          20:int)))
    (set-name top var($try0)))
  name(top)
```

```hir
;; hir -O1 fn=r
fn r (fl:bool) -> int {io}
  (block
    (def $try0
      (try
        (block
          (if var(fl) (call name(assert) false:bool))
          10:int)
        (catch e
          20:int)))
    (set-name top var($try0)))
  name(top)
```

```mir
;; mir -O2 fn=r stage=before
fn r(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: ThrownException -> bb6, Exception -> bb5]:
    br_if v0, bb3, bb2
  bb2 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v1: any = const null
    br bb4(v1)
  bb3 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v2: any = opaque.NameRead(assert) {read:shared,throws}
    v3: bool = const false
    v4: bool = opaque.DynamicCall(v2, v3) {impure}
    v5: any = convert.any v4
    br bb4(v5)
  bb4(v6: any) [unwind: ThrownException -> bb6, Exception -> bb5]:
    v7: int = const 10
    br bb8(v7)
  bb5(v8: any) [handler]:
    br bb7(v8)
  bb6(v9: any) [handler]:
    v10: any = unwrap_thrown v9
    br bb7(v10)
  bb7(v11: any):
    v12: int = const 20
    br bb8(v12)
  bb8(v13: int):
    v14: any = opaque.NameAssign(top, v13) {impure}
    v15: any = opaque.NameRead(top) {read:shared,throws}
    v16: int = convert.int v15
    ret v16
```

```mir
;; mir -O2 fn=r
fn r(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: ThrownException -> bb5, Exception -> bb4]:
    br_if v0, bb2, bb3
  bb2 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v1: any = opaque.NameRead(assert) {read:shared,throws}
    v2: bool = const false
    v3: bool = opaque.DynamicCall(v1, v2) {impure}
    br bb3
  bb3 [unwind: ThrownException -> bb5, Exception -> bb4]:
    v4: int = const 10
    br bb7(v4)
  bb4(v5: any) [handler]:
    br bb6
  bb5(v6: any) [handler]:
    br bb6
  bb6:
    v7: int = const 20
    br bb7(v7)
  bb7(v8: int):
    v9: any = opaque.NameAssign(top, v8) {impure}
    v10: any = opaque.NameRead(top) {read:shared,throws}
    v11: int = convert.int v10
    ret v11
```
