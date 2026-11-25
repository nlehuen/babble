# Record Borrow Unboxing and Class Method Inlining

References : BIP014-I4, BIP007-I2, ScalarReplacePass, InlinePass

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
```


## Non-inlined (>35 HIR nodes) borrow record triggers $unboxed entry point

```babble
def heavy = fn (a: Vec3, b: Vec3): double -> (
    def d1 = a.x * b.x + a.y * b.y + a.z * b.z
    def d2 = (a.x + 1.0) * b.x + (a.y + 2.0) * b.y + (a.z + 3.0) * b.z
    def d3 = (a.x - a.y) * (b.x - b.y) + (a.y - a.z) * (b.y - b.z)
    d1 + d2 + d3
)
def a = Vec3(1.0, 2.0, 3.0)
assert(heavy(a, Vec3(0.5, 1.5, 2.5)) == 35.0)
```

```hir
;; hir -O0 fn=heavy
fn heavy (a:obj, b:obj) -> double {nothrow unique}
  (def d1 (+:double (+:double (*:double (get-this-field x) (get-this-field x)) (*:double (get-this-field y) (get-this-field y))) (*:double (get-this-field z) (get-this-field z))))
  (def d2 (+:double (+:double (*:double (block (+:double (get-this-field x) 1.0:double)) (get-this-field x)) (*:double (block (+:double (get-this-field y) 2.0:double)) (get-this-field y))) (*:double (block (+:double (get-this-field z) 3.0:double)) (get-this-field z))))
  (def d3 (+:double (*:double (block (-:double (get-this-field x) (get-this-field y))) (block (-:double (get-this-field x) (get-this-field y)))) (*:double (block (-:double (get-this-field y) (get-this-field z))) (block (-:double (get-this-field y) (get-this-field z))))))
  (+:double (+:double var(d1) var(d2)) var(d3))
```

```hir
;; hir -O1 fn=heavy
fn heavy (a:obj, b:obj) -> double {nothrow unique}
  (def d1 (+:double (+:double (*:double (get-this-field x) (get-this-field x)) (*:double (get-this-field y) (get-this-field y))) (*:double (get-this-field z) (get-this-field z))))
  (def d2 (+:double (+:double (*:double (+:double (get-this-field x) 1.0:double) (get-this-field x)) (*:double (+:double (get-this-field y) 2.0:double) (get-this-field y))) (*:double (+:double (get-this-field z) 3.0:double) (get-this-field z))))
  (def d3 (+:double (*:double (-:double (get-this-field x) (get-this-field y)) (-:double (get-this-field x) (get-this-field y))) (*:double (-:double (get-this-field y) (get-this-field z)) (-:double (get-this-field y) (get-this-field z)))))
  (+:double (+:double var(d1) var(d2)) var(d3))
```

```mir
;; mir -O2 fn=heavy stage=before
fn heavy(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = load_field v1.x {read:frozen}
    v4: double = mul v2, v3 {pure}
    v5: double = load_field v0.y {read:frozen}
    v6: double = load_field v1.y {read:frozen}
    v7: double = mul v5, v6 {pure}
    v8: double = add v4, v7 {pure}
    v9: double = load_field v0.z {read:frozen}
    v10: double = load_field v1.z {read:frozen}
    v11: double = mul v9, v10 {pure}
    v12: double = add v8, v11 {pure}
    v13: double = load_field v0.x {read:frozen}
    v14: double = const 1.0
    v15: double = add v13, v14 {pure}
    v16: double = load_field v1.x {read:frozen}
    v17: double = mul v15, v16 {pure}
    v18: double = load_field v0.y {read:frozen}
    v19: double = const 2.0
    v20: double = add v18, v19 {pure}
    v21: double = load_field v1.y {read:frozen}
    v22: double = mul v20, v21 {pure}
    v23: double = add v17, v22 {pure}
    v24: double = load_field v0.z {read:frozen}
    v25: double = const 3.0
    v26: double = add v24, v25 {pure}
    v27: double = load_field v1.z {read:frozen}
    v28: double = mul v26, v27 {pure}
    v29: double = add v23, v28 {pure}
    v30: double = load_field v0.x {read:frozen}
    v31: double = load_field v0.y {read:frozen}
    v32: double = sub v30, v31 {pure}
    v33: double = load_field v1.x {read:frozen}
    v34: double = load_field v1.y {read:frozen}
    v35: double = sub v33, v34 {pure}
    v36: double = mul v32, v35 {pure}
    v37: double = load_field v0.y {read:frozen}
    v38: double = load_field v0.z {read:frozen}
    v39: double = sub v37, v38 {pure}
    v40: double = load_field v1.y {read:frozen}
    v41: double = load_field v1.z {read:frozen}
    v42: double = sub v40, v41 {pure}
    v43: double = mul v39, v42 {pure}
    v44: double = add v36, v43 {pure}
    v45: double = add v12, v29 {pure}
    v46: double = add v45, v44 {pure}
    ret v46
```

```mir
;; mir -O2 fn=heavy
fn heavy(v0: any, v1: any) -> double:
  bb0:
    v2: double = load_field v0.x {read:frozen}
    v3: double = load_field v1.x {read:frozen}
    v4: double = mul v2, v3 {pure}
    v5: double = load_field v0.y {read:frozen}
    v6: double = load_field v1.y {read:frozen}
    v7: double = mul v5, v6 {pure}
    v8: double = add v4, v7 {pure}
    v9: double = load_field v0.z {read:frozen}
    v10: double = load_field v1.z {read:frozen}
    v11: double = mul v9, v10 {pure}
    v12: double = add v8, v11 {pure}
    v13: double = const 1.0
    v14: double = add v2, v13 {pure}
    v15: double = mul v14, v3 {pure}
    v16: double = const 2.0
    v17: double = add v5, v16 {pure}
    v18: double = mul v17, v6 {pure}
    v19: double = add v15, v18 {pure}
    v20: double = const 3.0
    v21: double = add v9, v20 {pure}
    v22: double = mul v21, v10 {pure}
    v23: double = add v19, v22 {pure}
    v24: double = sub v2, v5 {pure}
    v25: double = sub v3, v6 {pure}
    v26: double = mul v24, v25 {pure}
    v27: double = sub v5, v9 {pure}
    v28: double = sub v6, v10 {pure}
    v29: double = mul v27, v28 {pure}
    v30: double = add v26, v29 {pure}
    v31: double = add v12, v23 {pure}
    v32: double = add v31, v30 {pure}
    ret v32
```

## Borrow param that escapes inside callee must not break $unboxed analysis

```babble
def keepFirst = fn (a: Vec3, b: Vec3): double -> (
    def xs = list(a)
    xs.get(0).x + b.y + b.z
)
def run = fn (): double -> (
    def a = Vec3(2.0, 3.0, 4.0)
    def b = Vec3(5.0, 6.0, 7.0)
    keepFirst(a, b)
)
assert(run() == 15.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {pure unique}
  (def a (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def b (record-new Vec3 5.0:double 6.0:double 7.0:double))
  (call-fn keepFirst var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run () -> double {pure unique}
  (def a (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (block
    (def $sr_b_1 6.0:double)
    (def $sr_b_2 7.0:double))
  (block
    (def xs (core-new list var(a)))
    (+:double (+:double (get-this-field x) var($sr_b_1)) var($sr_b_2)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run() -> double:
  bb0:
    v0: any = opaque.NameRead(Vec3) {read:shared}
    v1: double = const 2.0
    v2: double = const 3.0
    v3: double = const 4.0
    v4: any = record_new Vec3[v0](v1, v2, v3) {pure}
    v5: double = const 6.0
    v6: double = const 7.0
    v7: any = opaque.CoreCollectionCall(list, v4) {impure}
    v8: int = const 0
    v9: any = call_java v7.get(v8) {impure}
    v10: any = checkcast v9 <babble|Vec3>
    v11: double = load_field v10.x {read:frozen,throws}
    v12: double = add v11, v5 {pure}
    v13: double = add v12, v6 {pure}
    ret v13
```

## Borrow-lambda higher-order devirtualization and inlining

```babble
def applyTwice = fn (x: int, f: fn (int): int): int -> ( f(f(x)) )
def run = fn (x: int): int -> ( applyTwice(x, fn (v: int): int -> ( v * 2 + 1 )) )
assert(run(3) == 15)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {nothrow unique}
  (call-fn applyTwice var(x) (fn-ref <anon#0>))
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {nothrow unique}
  (block
    (def v (+:int (*:int var(x) 2:int) 1:int))
    (+:int (*:int var(v) 2:int) 1:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: int = mul v0, v1 {pure}
    v3: int = const 1
    v4: int = add v2, v3 {pure}
    v5: int = const 2
    v6: int = mul v4, v5 {pure}
    v7: int = const 1
    v8: int = add v6, v7 {pure}
    ret v8
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: int = mul v0, v1 {pure}
    v3: int = const 1
    v4: int = add v2, v3 {pure}
    v5: int = mul v4, v1 {pure}
    v6: int = add v5, v3 {pure}
    ret v6
```

## Cross-module record construction, field access, operator & method direct dispatch

```babble
def m = import("vec_mod.ba")
def run = fn (n: int): double -> (
    def a = m.ExtVec(3.0, 4.0)
    def b = -m.ExtVec(1.0, 2.0)
    def c = a + b
    c.x + c.y + c.mag2() + double(n)
)
assert(run(1) == 13.0)
```

```hir
;; hir -O0,-O1 fn=run
fn run (n:int) -> double {nothrow unique}
  (def a (record-new vec_mod$ba$C0$ExtVec 3.0:double 4.0:double))
  (def b (call-method (record-new vec_mod$ba$C0$ExtVec 1.0:double 2.0:double).neg))
  (def c (call-method var(a).plus var(b)))
  (+:double (+:double (+:double (get-this-field x) (get-this-field y)) (call-method var(c).mag2)) (call name(double) var(n)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> double:
  bb0:
    v1: any = opaque.NameRead(m) {read:shared,throws}
    v2: any = opaque.ModuleExportGet(v1.ExtVec) {impure}
    v3: double = const 3.0
    v4: double = const 4.0
    v5: any = record_new vec_mod$ba$C0$ExtVec[v2](v3, v4) {pure}
    v6: any = opaque.NameRead(m) {read:shared,throws}
    v7: any = opaque.ModuleExportGet(v6.ExtVec) {impure}
    v8: double = const 1.0
    v9: double = const 2.0
    v10: any = record_new vec_mod$ba$C0$ExtVec[v7](v8, v9) {pure}
    v11: any = call_method v10.neg() {impure}
    v12: any = call_method v5.plus(v11) {impure}
    v13: double = load_field v12.x {read:frozen}
    v14: double = load_field v12.y {read:frozen}
    v15: double = add v13, v14 {pure}
    v16: double = call_method v12.mag2() {pure}
    v17: double = add v15, v16 {pure}
    v18: any = opaque.NameRead(double) {read:shared,throws}
    v19: double = opaque.DynamicCall(v18, v0) {impure}
    v20: double = add v17, v19 {pure}
    ret v20
```

## Overloaded operator methods on a compiled record

```babble
def Num = record (v: int) (
    def plus = overload (
        fn (o: Num): Num -> ( Num(this.v + o.v) ),
        fn (k: int): Num -> ( Num(this.v + k) )
    )
)
def run = fn (x: int): int -> (
    def a = Num(x)
    def b = (a + Num(3)) + 4
    b.v
)
assert(run(10) == 17)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {nothrow unique}
  (def a (record-new Num var(x)))
  (def b (call-method (block (call-method var(a).plus (record-new Num 3:int))).plus 4:int))
  (get-this-field v)
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {nothrow unique}
  (block (def $sr_a_0 var(x)))
  (block
    (block
      (block (def $sr_o_0 3:int))
      (block (def $sr_$this_0 (+:int var($sr_a_0) var($sr_o_0)))))
    (block (def $sr_b_0 (+:int var($sr_$this_0) 4:int))))
  var($sr_b_0)
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    v1: int = const 3
    v2: int = add v0, v1 {pure}
    v3: int = const 4
    v4: int = add v2, v3 {pure}
    ret v4
```

## Unboxed borrow entry point with local vars, for-loop, try, and non-record scalar param

```babble
def heavyLoop = fn (a: Vec3, s: int): double -> (
    def acc = 0.0
    for k in list(1, 2) then (
        def t = (a.x + double(k)) * double(s) + a.y * a.z
        acc = acc + t + a.x - a.y + a.z
    )
    try ( if s < 0 then ( assert(false) ) ; acc + a.x * a.y * a.z ) catch e then ( 0.0 )
)
assert(heavyLoop(Vec3(1.0, 2.0, 3.0), 2) == 32.0)
```

```hir
;; hir -O0 fn=heavyLoop
fn heavyLoop (a:obj, s:int) -> double {nothrow unique}
  (def acc 0.0:double)
  (for k in (core-new list 1:int 2:int)
    (block
      (def t (+:double (*:double (block (+:double (get-this-field x) (call name(double) var(k)))) (call name(double) var(s))) (*:double (get-this-field y) (get-this-field z))))
      (set acc (+:double (-:double (+:double (+:double var(acc) var(t)) (get-this-field x)) (get-this-field y)) (get-this-field z)))))
  (try
    (block
      (if (<:bool var(s) 0:int) (block (call name(assert) false:bool)))
      (+:double var(acc) (*:double (*:double (get-this-field x) (get-this-field y)) (get-this-field z))))
    (catch e
      0.0:double))
```

```hir
;; hir -O1 fn=heavyLoop
fn heavyLoop (a:obj, s:int) -> double {nothrow unique}
  (def acc 0.0:double)
  (block
    (def $licm0 (get-this-field x))
    (def $licm1 (*:double (get-this-field y) (get-this-field z)))
    (def $licm2 (get-this-field x))
    (def $licm3 (get-this-field y))
    (def $licm4 (get-this-field z))
    (for k in (core-new list 1:int 2:int)
      (block
        (def t (+:double (*:double (+:double var($licm0) (call name(double) var(k))) (call name(double) var(s))) var($licm1)))
        (set acc (+:double (-:double (+:double (+:double var(acc) var(t)) var($licm2)) var($licm3)) var($licm4))))))
  (try
    (block
      (if (<:bool var(s) 0:int) (call name(assert) false:bool))
      (+:double var(acc) (*:double (*:double (get-this-field x) (get-this-field y)) (get-this-field z))))
    (catch e
      0.0:double))
```

```mir
;; mir -O2 fn=heavyLoop stage=before
fn heavyLoop(v0: any, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: double = load_field v0.x {read:frozen}
    v4: double = load_field v0.y {read:frozen}
    v5: double = load_field v0.z {read:frozen}
    v6: double = mul v4, v5 {pure}
    v7: double = load_field v0.x {read:frozen}
    v8: double = load_field v0.y {read:frozen}
    v9: double = load_field v0.z {read:frozen}
    v10: int = const 1
    v11: int = const 2
    v12: any = opaque.CoreCollectionCall(list, v10, v11) {impure}
    v13: any = for_init.java_iterable v12
    v14: any = const null
    br bb1(v14, v2)
  bb1(v15: any, v16: double):
    v17: bool = for_has_next v13
    br_if v17, bb11, bb2
  bb2:
    br bb3
  bb3 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v18: int = const 0
    v19: bool = lt v1, v18 {pure}
    br_if v19, bb5, bb4
  bb4 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v20: any = const null
    br bb6(v20)
  bb5 [unwind: ThrownException -> bb8, Exception -> bb7]:
    v21: any = opaque.NameRead(assert) {read:shared,throws}
    v22: bool = const false
    v23: bool = opaque.DynamicCall(v21, v22) {impure}
    v24: any = convert.any v23
    br bb6(v24)
  bb6(v25: any) [unwind: ThrownException -> bb8, Exception -> bb7]:
    v26: double = load_field v0.x {read:frozen}
    v27: double = load_field v0.y {read:frozen}
    v28: double = mul v26, v27 {pure}
    v29: double = load_field v0.z {read:frozen}
    v30: double = mul v28, v29 {pure}
    v31: double = add v16, v30 {pure}
    br bb10(v31)
  bb7(v32: any) [handler]:
    br bb9(v32)
  bb8(v33: any) [handler]:
    v34: any = unwrap_thrown v33
    br bb9(v34)
  bb9(v35: any):
    v36: double = const 0.0
    br bb10(v36)
  bb10(v37: double):
    ret v37
  bb11:
    v38: any = for_next v13
    v39: any = checkcast v38 <int|int|>
    v40: int = convert.int v39
    v41: any = opaque.NameRead(double) {read:shared,throws}
    v42: double = opaque.DynamicCall(v41, v40) {impure}
    v43: double = add v3, v42 {pure}
    v44: any = opaque.NameRead(double) {read:shared,throws}
    v45: double = opaque.DynamicCall(v44, v1) {impure}
    v46: double = mul v43, v45 {pure}
    v47: double = add v46, v6 {pure}
    v48: double = add v16, v47 {pure}
    v49: double = add v48, v7 {pure}
    v50: double = sub v49, v8 {pure}
    v51: double = add v50, v9 {pure}
    v52: any = convert.any v51
    br bb1(v52, v51)
```

```mir
;; mir -O2 fn=heavyLoop
fn heavyLoop(v0: any, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: double = load_field v0.x {read:frozen}
    v4: double = load_field v0.y {read:frozen}
    v5: double = load_field v0.z {read:frozen}
    v6: double = mul v4, v5 {pure}
    v7: int = const 1
    v8: int = const 2
    v9: any = opaque.CoreCollectionCall(list, v7, v8) {impure}
    v10: any = for_init.java_iterable v9
    br bb1(v2)
  bb1(v11: double):
    v12: bool = for_has_next v10
    br_if v12, bb9, bb2
  bb2 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v13: int = const 0
    v14: bool = lt v1, v13 {pure}
    br_if v14, bb3, bb4
  bb3 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v15: any = opaque.NameRead(assert) {read:shared,throws}
    v16: bool = const false
    v17: bool = opaque.DynamicCall(v15, v16) {impure}
    br bb4
  bb4 [unwind: ThrownException -> bb6, Exception -> bb5]:
    v18: double = mul v3, v4 {pure}
    v19: double = mul v18, v5 {pure}
    v20: double = add v11, v19 {pure}
    br bb8(v20)
  bb5(v21: any) [handler]:
    br bb7
  bb6(v22: any) [handler]:
    br bb7
  bb7:
    br bb8(v2)
  bb8(v23: double):
    ret v23
  bb9:
    v24: any = for_next v10
    v25: any = checkcast v24 <int|int|>
    v26: int = convert.int v25
    v27: any = opaque.NameRead(double) {read:shared,throws}
    v28: double = opaque.DynamicCall(v27, v26) {impure}
    v29: double = add v3, v28 {pure}
    v30: any = opaque.NameRead(double) {read:shared,throws}
    v31: double = opaque.DynamicCall(v30, v1) {impure}
    v32: double = mul v29, v31 {pure}
    v33: double = add v32, v6 {pure}
    v34: double = add v11, v33 {pure}
    v35: double = add v34, v3 {pure}
    v36: double = sub v35, v4 {pure}
    v37: double = add v36, v5 {pure}
    br bb1(v37)
```

## Non-record compiled class method inlining vs named-arg call & self-recursive borrow fn

```babble
def C = class (def x: int) (
    def add = fn (a: int, b: int): int -> ( this.x + a + b )
)
def rec = fn (v: Vec3, n: int): double -> ( if n == 0 then ( v.x ) else ( recurse(v, n - 1) + v.y ) )
def run = fn (): double -> (
    def c = C(10)
    double(c.add(b: 3, a: 2) + c.add(1, 2)) + rec(Vec3(1.0, 2.0, 0.0), 3)
)
assert(run() == 35.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {nothrow unique}
  (def c (call name(C) 10:int))
  (+:double (call name(double) (+:int (call-method var(c).add b=3:int a=2:int) (call-method var(c).add 1:int 2:int))) (call-fn rec (record-new Vec3 1.0:double 2.0:double 0.0:double) 3:int))
```

```hir
;; hir -O1 fn=run
fn run () -> double {nothrow unique}
  (def c (call name(C) 10:int))
  (+:double (call name(double) (+:int (call-method var(c).add b=3:int a=2:int) (+:int (+:int (get-this-field x) 1:int) 2:int))) (call-fn rec (record-new Vec3 1.0:double 2.0:double 0.0:double) 3:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> double:
  bb0:
    v0: any = opaque.NameRead(C) {read:shared}
    v1: int = const 10
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = opaque.NameRead(double) {read:shared,throws}
    v4: int = const 3
    v5: int = const 2
    v6: int = call_method v2.add(v4, v5) {pure!}
    v7: int = load_field v2.x {read:shared,throws}
    v8: int = const 1
    v9: int = add v7, v8 {pure}
    v10: int = const 2
    v11: int = add v9, v10 {pure}
    v12: int = add v6, v11 {pure}
    v13: double = opaque.DynamicCall(v3, v12) {impure}
    v14: any = opaque.NameRead(rec) {read:shared}
    v15: any = opaque.NameRead(Vec3) {read:shared}
    v16: double = const 1.0
    v17: double = const 2.0
    v18: double = const 0.0
    v19: any = record_new Vec3[v15](v16, v17, v18) {pure}
    v20: int = const 3
    v21: double = call rec(v19, v20) {pure!}
    v22: double = add v13, v21 {pure}
    ret v22
```

```mir
;; mir -O2 fn=run
fn run() -> double:
  bb0:
    v0: any = opaque.NameRead(C) {read:shared}
    v1: int = const 10
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = opaque.NameRead(double) {read:shared,throws}
    v4: int = const 3
    v5: int = const 2
    v6: int = call_method v2.add(v4, v5) {pure!}
    v7: int = load_field v2.x {read:shared,throws}
    v8: int = const 1
    v9: int = add v7, v8 {pure}
    v10: int = add v9, v5 {pure}
    v11: int = add v6, v10 {pure}
    v12: double = opaque.DynamicCall(v3, v11) {impure}
    v13: any = opaque.NameRead(rec) {read:shared}
    v14: any = opaque.NameRead(Vec3) {read:shared}
    v15: double = const 1.0
    v16: double = const 2.0
    v17: double = const 0.0
    v18: any = record_new Vec3[v14](v15, v16, v17) {pure}
    v19: double = call rec(v18, v4) {pure!}
    v20: double = add v12, v19 {pure}
    ret v20
```

## Class method inlining with constructor statements : safe this.field init vs leaking this

```babble
def Safe = class (def x: int) (
    def y = this.x + 1
    def get = fn (): int -> ( this.y )
)
def Leak = class (def x: int) (
    def box = list(this)
    def get = fn (): int -> ( this.x )
)
def r = fn (): int -> ( Safe(4).get() + Leak(5).get() )
assert(r() == 10)
```

```hir
;; hir -O0 fn=r
fn r () -> int {nothrow unique}
  (+:int (call-method (call name(Safe) 4:int).get) (call-method (call name(Leak) 5:int).get))
```

```hir
;; hir -O1 fn=r
fn r () -> int {nothrow unique}
  (+:int (block
      (def $this (call name(Safe) 4:int))
      (get-this-field y)) (call-method (call name(Leak) 5:int).get))
```

```mir
;; mir -O2 fn=r stage=before,final
fn r() -> int:
  bb0:
    v0: any = opaque.NameRead(Safe) {read:shared}
    v1: int = const 4
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: int = load_field v2.y {read:shared,throws}
    v4: any = opaque.NameRead(Leak) {read:shared}
    v5: int = const 5
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: int = call_method v6.get() {pure}
    v8: int = add v3, v7 {pure}
    ret v8
```

## Inlining with parameter reassignment, widening read, for loop, and move inside callee

```babble
def f = fn (x: int): double -> (
    def s = 0
    for k in list(1, 2) then ( s = s + k )
    x = x + s
    x + 0.5
)
def r = fn (): double -> ( def a = 3 ; f(a) + f(1) )
assert(r() == 11.0)
```

```hir
;; hir -O0 fn=r
fn r () -> double {nothrow unique}
  (def a 3:int)
  (+:double (call-fn f var(a)) (call-fn f 1:int))
```

```hir
;; hir -O1 fn=r
fn r () -> double {nothrow unique}
  (+:double (block
      (def x#1 3:int)
      (def s#1 0:int)
      (for k#1 in (core-new list 1:int 2:int)
        (block
          (set s#1 (+:int var(s#1) var(k#1)))))
      (set x#1 (+:int var(x#1) var(s#1)))
      (+:double var(x#1) 0.5:double)) (block
      (def x#2 1:int)
      (def s#2 0:int)
      (for k#2 in (core-new list 1:int 2:int)
        (block
          (set s#2 (+:int var(s#2) var(k#2)))))
      (set x#2 (+:int var(x#2) var(s#2)))
      (+:double var(x#2) 0.5:double)))
```

```mir
;; mir -O2 fn=r stage=before
fn r() -> double:
  bb0:
    v0: int = const 3
    v1: int = const 0
    v2: int = const 1
    v3: int = const 2
    v4: any = opaque.CoreCollectionCall(list, v2, v3) {impure}
    v5: any = for_init.java_iterable v4
    v6: any = const null
    br bb1(v6, v1)
  bb1(v7: any, v8: int):
    v9: bool = for_has_next v5
    br_if v9, bb6, bb2
  bb2:
    v10: int = add v0, v8 {pure}
    v11: double = convert.double v10
    v12: double = const 0.5
    v13: double = add v11, v12 {pure}
    v14: int = const 1
    v15: int = const 0
    v16: int = const 1
    v17: int = const 2
    v18: any = opaque.CoreCollectionCall(list, v16, v17) {impure}
    v19: any = for_init.java_iterable v18
    v20: any = const null
    br bb3(v20, v15)
  bb3(v21: any, v22: int):
    v23: bool = for_has_next v19
    br_if v23, bb5, bb4
  bb4:
    v24: int = add v14, v22 {pure}
    v25: double = convert.double v24
    v26: double = const 0.5
    v27: double = add v25, v26 {pure}
    v28: double = add v13, v27 {pure}
    ret v28
  bb5:
    v29: any = for_next v19
    v30: any = checkcast v29 <int|int|>
    v31: int = convert.int v30
    v32: int = add v22, v31 {pure}
    v33: any = convert.any v32
    br bb3(v33, v32)
  bb6:
    v34: any = for_next v5
    v35: any = checkcast v34 <int|int|>
    v36: int = convert.int v35
    v37: int = add v8, v36 {pure}
    v38: any = convert.any v37
    br bb1(v38, v37)
```

```mir
;; mir -O2 fn=r
fn r() -> double:
  bb0:
    v0: int = const 3
    v1: int = const 0
    v2: int = const 1
    v3: int = const 2
    v4: any = opaque.CoreCollectionCall(list, v2, v3) {impure}
    v5: any = for_init.java_iterable v4
    br bb1(v1)
  bb1(v6: int):
    v7: bool = for_has_next v5
    br_if v7, bb6, bb2
  bb2:
    v8: int = add v0, v6 {pure}
    v9: double = convert.double v8
    v10: double = const 0.5
    v11: double = add v9, v10 {pure}
    v12: any = opaque.CoreCollectionCall(list, v2, v3) {impure}
    v13: any = for_init.java_iterable v12
    br bb3(v1)
  bb3(v14: int):
    v15: bool = for_has_next v13
    br_if v15, bb5, bb4
  bb4:
    v16: int = add v2, v14 {pure}
    v17: double = convert.double v16
    v18: double = add v17, v10 {pure}
    v19: double = add v11, v18 {pure}
    ret v19
  bb5:
    v20: any = for_next v13
    v21: any = checkcast v20 <int|int|>
    v22: int = convert.int v21
    v23: int = add v14, v22 {pure}
    br bb3(v23)
  bb6:
    v24: any = for_next v5
    v25: any = checkcast v24 <int|int|>
    v26: int = convert.int v25
    v27: int = add v6, v26 {pure}
    br bb1(v27)
```

## Inlining with capturing borrow lambda and receiver var reassigned in call arg

```babble
def Box = class (def v: int) (
    def add = fn (n: int): int -> ( this.v + n )
)
def ap = fn (f: fn (int, int): int): int -> ( f(3, 2) )
def r = fn (): int -> (
    def b = Box(10) ; def k = 5
    b.add(( b = Box(20) ; 1 )) + b.v + ap(fn (a: int, c: int): int -> ( a - c + k ))
)
assert(r() == 37)
```

```hir
;; hir -O0 fn=r
fn r () -> int {nothrow unique}
  (def b (call name(Box) 10:int))
  (def k 5:int)
  (+:int (+:int (call-method var(b).add (block
          (set b (call name(Box) 20:int))
          1:int)) (get-this-field v)) (call-fn ap (fn-ref <anon#0>)))
```

```hir
;; hir -O1 fn=r
fn r () -> int {nothrow unique}
  (def b (call name(Box) 10:int))
  (def k 5:int)
  (+:int (+:int (block
        (def $this var(b))
        (def n
          (block
            (set b (call name(Box) 20:int))
            1:int))
        (+:int (get-this-field v) var(n))) (get-this-field v)) (block
      (def f (fn-ref <anon#0>))
      (call var(f) 3:int 2:int)))
```

```mir
;; mir -O2 fn=r stage=before,final
fn r() -> int:
  bb0:
    v0: any = opaque.NameRead(Box) {read:shared}
    v1: int = const 10
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: int = const 5
    slot_init k, v3 {write:shared}
    v4: any = opaque.NameRead(Box) {read:shared}
    v5: int = const 20
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: int = const 1
    v8: int = load_field v2.v {read:shared,throws}
    v9: int = add v8, v7 {pure}
    v10: int = load_field v6.v {read:shared,throws}
    v11: int = add v9, v10 {pure}
    v12: any = opaque.FunctionRef(<anon>) {read:shared}
    v13: int = const 3
    v14: int = const 2
    v15: int = opaque.DynamicCall(v12, v13, v14) {impure}
    v16: int = add v11, v15 {pure}
    ret v16
```
