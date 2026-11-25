# Record SROA and Projection Beta-Reduction

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


## Loop-carried SROA with swap and method inlining

```babble
def run = fn (n: int): double -> (
    def acc = Vec3(0.0, 0.0, 0.0)
    def step = Vec3(1.0, 2.0, 3.0)
    def i = 0
    while i < n then (
        acc = (acc + step).swap()
        i = i + 1
    )
    acc.dot(step)
)
assert(run(10) == 135.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> double {nothrow unique}
  (def acc (record-new Vec3 0.0:double 0.0:double 0.0:double))
  (def step (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc (call-method (block (call-method var(acc).plus var(step))).swap))
      (set i (+:int var(i) 1:int))))
  (call-method var(acc).dot var(step))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> double {nothrow unique}
  (block
    (def $sr_acc_0 0.0:double)
    (def $sr_acc_1 0.0:double)
    (def $sr_acc_2 0.0:double))
  (block
    (def $sr_step_0 1.0:double)
    (def $sr_step_1 2.0:double)
    (def $sr_step_2 3.0:double))
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (block
        (block
          (def $sr_$this_0 (+:double var($sr_acc_0) var($sr_step_0)))
          (def $sr_$this_1 (+:double var($sr_acc_1) var($sr_step_1)))
          (def $sr_$this_2 (+:double var($sr_acc_2) var($sr_step_2))))
        (block
          (def $sr_t0 var($sr_$this_1))
          (def $sr_t1 var($sr_$this_0))
          (def $sr_t2 var($sr_$this_2))
          (set $sr_acc_0 var($sr_t0))
          (set $sr_acc_1 var($sr_t1))
          (set $sr_acc_2 var($sr_t2))))
      (set i (+:int var(i) 1:int))))
  (+:double (+:double (*:double var($sr_acc_0) var($sr_step_0)) (*:double var($sr_acc_1) var($sr_step_1))) (*:double var($sr_acc_2) var($sr_step_2)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: double = const 0.0
    v3: double = const 0.0
    v4: double = const 1.0
    v5: double = const 2.0
    v6: double = const 3.0
    v7: int = const 0
    v8: any = const null
    br bb1(v8, v7, v1, v2, v3)
  bb1(v9: any, v10: int, v11: double, v12: double, v13: double):
    v14: bool = lt v10, v0 {pure}
    br_if v14, bb3, bb2
  bb2:
    v15: double = mul v11, v4 {pure}
    v16: double = mul v12, v5 {pure}
    v17: double = add v15, v16 {pure}
    v18: double = mul v13, v6 {pure}
    v19: double = add v17, v18 {pure}
    ret v19
  bb3:
    v20: double = add v11, v4 {pure}
    v21: double = add v12, v5 {pure}
    v22: double = add v13, v6 {pure}
    v23: int = const 1
    v24: int = add v10, v23 {pure}
    v25: any = convert.any v24
    br bb1(v25, v24, v21, v20, v22)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: double = const 1.0
    v3: double = const 2.0
    v4: double = const 3.0
    v5: int = const 0
    br bb1(v5, v1, v1, v1)
  bb1(v6: int, v7: double, v8: double, v9: double):
    v10: bool = lt v6, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    v11: double = mul v7, v2 {pure}
    v12: double = mul v8, v3 {pure}
    v13: double = add v11, v12 {pure}
    v14: double = mul v9, v4 {pure}
    v15: double = add v13, v14 {pure}
    ret v15
  bb3:
    v16: double = add v7, v2 {pure}
    v17: double = add v8, v3 {pure}
    v18: double = add v9, v4 {pure}
    v19: int = const 1
    v20: int = add v6, v19 {pure}
    br bb1(v20, v17, v16, v18)
```

## Direct projection beta-reduction with named/reordered args and widening

```babble
def f = fn (a: int, b: int): double -> (
    def v = Vec3(z: 3.0, x: a, y: b)
    v.x + v.y * 10.0 + v.z * 100.0
)
assert(f(2, 4) == 342.0)
assert(Vec3(1.0, 2.0, 3.0).y == 2.0)
```

```hir
;; hir -O0 fn=f
fn f (a:int, b:int) -> double {nothrow unique}
  (def v (record-new Vec3 3.0:double var(a) var(b)))
  (+:double (+:double (get-this-field x) (*:double (get-this-field y) 10.0:double)) (*:double (get-this-field z) 100.0:double))
```

```hir
;; hir -O1 fn=f
fn f (a:int, b:int) -> double {nothrow unique}
  (block
    (def $sr_t0 var(a))
    (def $sr_t1 var(b))
    (def $sr_v_0 var($sr_t0))
    (def $sr_v_1 var($sr_t1))
    (def $sr_v_2 3.0:double))
  (+:double (+:double var($sr_v_0) (*:double var($sr_v_1) 10.0:double)) (*:double var($sr_v_2) 100.0:double))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: int, v1: int) -> double:
  bb0:
    v2: double = convert.double v0
    v3: double = convert.double v1
    v4: double = const 3.0
    v5: double = const 10.0
    v6: double = mul v3, v5 {pure}
    v7: double = add v2, v6 {pure}
    v8: double = const 100.0
    v9: double = mul v4, v8 {pure}
    v10: double = add v7, v9 {pure}
    ret v10
```

```mir
;; mir -O2 fn=f
fn f(v0: int, v1: int) -> double:
  bb0:
    v2: double = convert.double v0
    v3: double = convert.double v1
    v4: double = const 10.0
    v5: double = mul v3, v4 {pure}
    v6: double = add v2, v5 {pure}
    v7: double = const 300.0
    v8: double = add v6, v7 {pure}
    ret v8
```

## Projection beta-reduction must preserve left-to-right side effects of dropped siblings

```babble
def log = java.lang.StringBuilder()
def emit = fn io (tag: string, v: double): double -> ( log.append(tag) ; v )
def run = fn io (): double -> (
    Vec3(emit("A", 1.0), emit("B", 2.0), emit("C", 3.0)).y
)
assert(run() == 2.0)
assert(log.toString() == "ABC")
```

```hir
;; hir -O0 fn=run
fn run () -> double {io}
  (get-this-field y)
```

```hir
;; hir -O1 fn=run
fn run () -> double {io}
  (block
    (call-fn emit "A" 1.0:double)
    (def $sr_p0 (call-fn emit "B" 2.0:double))
    (call-fn emit "C" 3.0:double)
    var($sr_p0))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run() -> double:
  bb0:
    v0: any = opaque.NameRead(emit) {read:shared,throws}
    v1: any = const "A"
    v2: double = const 1.0
    v3: double = call emit(v1, v2) {impure}
    v4: any = opaque.NameRead(emit) {read:shared,throws}
    v5: any = const "B"
    v6: double = const 2.0
    v7: double = call emit(v5, v6) {impure}
    v8: any = opaque.NameRead(emit) {read:shared,throws}
    v9: any = const "C"
    v10: double = const 3.0
    v11: double = call emit(v9, v10) {impure}
    ret v7
```

## Conditional if/else record SROA, copy propagation, and self-assignment

```babble
def pick = fn (flag: bool): double -> (
    def p = if flag then ( Vec3(1.0, 2.0, 3.0) ) else ( Vec3(4.0, 5.0, 6.0) )
    def q = p
    q = q
    if flag then ( p = Vec3(p.z, p.y, p.x) )
    p.x + q.y + p.z
)
assert(pick(true) == 6.0)
assert(pick(false) == 15.0)
```

```hir
;; hir -O0 fn=pick
fn pick (flag:bool) -> double {nothrow unique}
  (def p (if var(flag) (block (record-new Vec3 1.0:double 2.0:double 3.0:double)) (block (record-new Vec3 4.0:double 5.0:double 6.0:double))))
  (def q var(p))
  (set q var(q))
  (if var(flag) (block (set p (record-new Vec3 (get-this-field z) (get-this-field y) (get-this-field x)))))
  (+:double (+:double (get-this-field x) (get-this-field y)) (get-this-field z))
```

```hir
;; hir -O1 fn=pick
fn pick (flag:bool) -> double {nothrow unique}
  (if var(flag)
    (block
      (set $sr_p_0 1.0:double)
      (set $sr_p_1 2.0:double)
      (set $sr_p_2 3.0:double))
    (block
      (set $sr_p_0 4.0:double)
      (set $sr_p_1 5.0:double)
      (set $sr_p_2 6.0:double)))
  (block
    (def $sr_q_1 var($sr_p_1))
    (def $sr_q_2 var($sr_p_2)))
  (if var(flag)
    (block
      (def $sr_t0 var($sr_p_2))
      (def $sr_t1 var($sr_p_1))
      (def $sr_t2 var($sr_p_0))
      (set $sr_p_0 var($sr_t0))
      (set $sr_p_1 var($sr_t1))
      (set $sr_p_2 var($sr_t2))))
  (+:double (+:double var($sr_p_0) var($sr_q_1)) var($sr_p_2))
```

```mir
;; mir -O2 fn=pick stage=before
fn pick(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: double = const 4.0
    v2: double = const 5.0
    v3: double = const 6.0
    v4: any = convert.any v3
    br bb3(v4, v2, v3, v1)
  bb2:
    v5: double = const 1.0
    v6: double = const 2.0
    v7: double = const 3.0
    v8: any = convert.any v7
    br bb3(v8, v6, v7, v5)
  bb3(v9: any, v10: double, v11: double, v12: double):
    br_if v0, bb5, bb4
  bb4:
    v13: any = const null
    br bb6(v13, v12, v11)
  bb5:
    v14: any = convert.any v12
    br bb6(v14, v11, v12)
  bb6(v15: any, v16: double, v17: double):
    v18: double = add v16, v10 {pure}
    v19: double = add v18, v17 {pure}
    ret v19
```

```mir
;; mir -O2 fn=pick
fn pick(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: double = const 4.0
    v2: double = const 5.0
    v3: double = const 6.0
    br bb3(v2, v3, v1)
  bb2:
    v4: double = const 1.0
    v5: double = const 2.0
    v6: double = const 3.0
    br bb3(v5, v6, v4)
  bb3(v7: double, v8: double, v9: double):
    br_if v0, bb5, bb4
  bb4:
    br bb6(v9, v8)
  bb5:
    br bb6(v8, v9)
  bb6(v10: double, v11: double):
    v12: double = add v10, v7 {pure}
    v13: double = add v12, v11 {pure}
    ret v13
```

## SROA across all 8 primitive kinds + string

```babble
def Pack = record (b: byte, s: short, i: int, l: long, f: float, d: double, z: bool, c: char, t: string) ()
def run = fn (k: int): string -> (
    def p = Pack(byte(k), short(k + 1), k + 2, long(k + 3), float(k), double(k), k > 0, 'A', "ok")
    "" + p.b + ":" + p.s + ":" + p.i + ":" + p.l + ":" + p.z + ":" + p.c + ":" + p.t
)
assert(run(1) == "1:2:3:4:true:A:ok")
```

```hir
;; hir -O0,-O1 fn=run
fn run (k:int) -> obj {pure unique}
  (def p (call name(Pack) (call name(byte) var(k)) (call name(short) (+:int var(k) 1:int)) (+:int var(k) 2:int) (call name(long) (+:int var(k) 3:int)) (call name(float) var(k)) (call name(double) var(k)) (>:bool var(k) 0:int) 'A' "ok"))
  (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj "" (get-this-field b)) ":") (get-this-field s)) ":") (get-this-field i)) ":") (get-this-field l)) ":") (get-this-field z)) ":") (get-this-field c)) ":") (get-this-field t))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> any:
  bb0:
    v1: any = opaque.NameRead(Pack) {read:shared}
    v2: any = opaque.NameRead(byte) {read:shared,throws}
    v3: byte = opaque.DynamicCall(v2, v0) {impure}
    v4: any = opaque.NameRead(short) {read:shared,throws}
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: short = opaque.DynamicCall(v4, v6) {impure}
    v8: int = const 2
    v9: int = add v0, v8 {pure}
    v10: any = opaque.NameRead(long) {read:shared,throws}
    v11: int = const 3
    v12: int = add v0, v11 {pure}
    v13: long = opaque.DynamicCall(v10, v12) {impure}
    v14: any = opaque.NameRead(float) {read:shared,throws}
    v15: float = opaque.DynamicCall(v14, v0) {impure}
    v16: any = opaque.NameRead(double) {read:shared,throws}
    v17: double = opaque.DynamicCall(v16, v0) {impure}
    v18: int = const 0
    v19: bool = gt v0, v18 {pure}
    v20: char = const 'A'
    v21: any = const "ok"
    v22: any = opaque.DynamicCall(v1, v3, v7, v9, v13, v15, v17, v19, v20, v21) {impure}
    v23: any = const ""
    v24: byte = load_field v22.b {read:frozen}
    v25: any = convert.any v24
    v26: any = add v23, v25 {impure}
    v27: any = const ":"
    v28: any = strcat v26, v27 {pure}
    v29: short = load_field v22.s {read:frozen}
    v30: any = convert.any v29
    v31: any = add v28, v30 {impure}
    v32: any = const ":"
    v33: any = strcat v31, v32 {pure}
    v34: int = load_field v22.i {read:frozen}
    v35: any = convert.any v34
    v36: any = add v33, v35 {impure}
    v37: any = const ":"
    v38: any = strcat v36, v37 {pure}
    v39: long = load_field v22.l {read:frozen}
    v40: any = convert.any v39
    v41: any = add v38, v40 {impure}
    v42: any = const ":"
    v43: any = strcat v41, v42 {pure}
    v44: bool = load_field v22.z {read:frozen}
    v45: any = convert.any v44
    v46: any = add v43, v45 {impure}
    v47: any = const ":"
    v48: any = strcat v46, v47 {pure}
    v49: char = load_field v22.c {read:frozen}
    v50: any = convert.any v49
    v51: any = add v48, v50 {impure}
    v52: any = const ":"
    v53: any = strcat v51, v52 {pure}
    v54: any = load_field v22.t {read:frozen}
    v55: any = strcat v53, v54 {pure}
    ret v55
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> any:
  bb0:
    v1: any = opaque.NameRead(Pack) {read:shared}
    v2: any = opaque.NameRead(byte) {read:shared,throws}
    v3: byte = opaque.DynamicCall(v2, v0) {impure}
    v4: any = opaque.NameRead(short) {read:shared,throws}
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: short = opaque.DynamicCall(v4, v6) {impure}
    v8: int = const 2
    v9: int = add v0, v8 {pure}
    v10: any = opaque.NameRead(long) {read:shared,throws}
    v11: int = const 3
    v12: int = add v0, v11 {pure}
    v13: long = opaque.DynamicCall(v10, v12) {impure}
    v14: any = opaque.NameRead(float) {read:shared,throws}
    v15: float = opaque.DynamicCall(v14, v0) {impure}
    v16: any = opaque.NameRead(double) {read:shared,throws}
    v17: double = opaque.DynamicCall(v16, v0) {impure}
    v18: int = const 0
    v19: bool = gt v0, v18 {pure}
    v20: char = const 'A'
    v21: any = const "ok"
    v22: any = opaque.DynamicCall(v1, v3, v7, v9, v13, v15, v17, v19, v20, v21) {impure}
    v23: any = const ""
    v24: byte = load_field v22.b {read:frozen}
    v25: any = convert.any v24
    v26: any = add v23, v25 {impure}
    v27: any = const ":"
    v28: any = strcat v26, v27 {pure}
    v29: short = load_field v22.s {read:frozen}
    v30: any = convert.any v29
    v31: any = add v28, v30 {impure}
    v32: any = strcat v31, v27 {pure}
    v33: int = load_field v22.i {read:frozen}
    v34: any = convert.any v33
    v35: any = add v32, v34 {impure}
    v36: any = strcat v35, v27 {pure}
    v37: long = load_field v22.l {read:frozen}
    v38: any = convert.any v37
    v39: any = add v36, v38 {impure}
    v40: any = strcat v39, v27 {pure}
    v41: bool = load_field v22.z {read:frozen}
    v42: any = convert.any v41
    v43: any = add v40, v42 {impure}
    v44: any = strcat v43, v27 {pure}
    v45: char = load_field v22.c {read:frozen}
    v46: any = convert.any v45
    v47: any = add v44, v46 {impure}
    v48: any = strcat v47, v27 {pure}
    v49: any = load_field v22.t {read:frozen}
    v50: any = strcat v48, v49 {pure}
    ret v50
```

## Escaping records must stay boxed for return, equality, identity, and collections

```babble
def make = fn (x: double): Vec3 -> ( Vec3(x, x + 1.0, x + 2.0) )
def run = fn (): bool -> (
    def a = make(1.0)
    def b = Vec3(1.0, 2.0, 3.0)
    def xs = list(a)
    a == b and not same(a, b) and xs.get(0) == b
)
assert(run())
```

```hir
;; hir -O0 fn=run
fn run () -> bool {pure unique}
  (def a (call-fn make 1.0:double))
  (def b (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def xs (core-new list var(a)))
  (and (and (==:bool var(a) var(b)) (not (call name(same) var(a) var(b)))) (==:bool (jcall List.get var(xs) 0:int) var(b)))
```

```hir
;; hir -O1 fn=run
fn run () -> bool {pure unique}
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def xs (core-new list var(a)))
  (and (and (==:bool var(a) var(b)) (not (call name(same) var(a) var(b)))) (==:bool (jcall List.get var(xs) 0:int) var(b)))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> bool:
  bb0:
    v0: any = opaque.NameRead(Vec3) {read:shared}
    v1: double = const 1.0
    v2: double = const 2.0
    v3: double = const 3.0
    v4: any = record_new Vec3[v0](v1, v2, v3) {pure}
    v5: any = opaque.NameRead(Vec3) {read:shared}
    v6: double = const 1.0
    v7: double = const 2.0
    v8: double = const 3.0
    v9: any = record_new Vec3[v5](v6, v7, v8) {pure}
    v10: any = opaque.CoreCollectionCall(list, v4) {impure}
    v11: any = eq v4, v9 {pure,throws}
    v12: bool = not v11 {pure}
    v13: bool = not v12 {pure}
    v14: bool = const false
    br_if v13, bb1, bb2(v14)
  bb1:
    v15: any = opaque.NameRead(same) {read:shared,throws}
    v16: bool = opaque.DynamicCall(v15, v4, v9) {impure}
    v17: bool = not v16 {pure}
    br bb2(v17)
  bb2(v18: bool):
    v19: bool = const false
    br_if v18, bb3, bb4(v19)
  bb3:
    v20: int = const 0
    v21: any = call_java v10.get(v20) {impure}
    v22: any = checkcast v21 <babble|Vec3>
    v23: any = eq v22, v9 {pure,throws}
    v24: bool = not v23 {pure}
    v25: bool = not v24 {pure}
    br bb4(v25)
  bb4(v26: bool):
    ret v26
```

```mir
;; mir -O2 fn=run
fn run() -> bool:
  bb0:
    v0: any = opaque.NameRead(Vec3) {read:shared}
    v1: double = const 1.0
    v2: double = const 2.0
    v3: double = const 3.0
    v4: any = record_new Vec3[v0](v1, v2, v3) {pure}
    v5: any = record_new Vec3[v0](v1, v2, v3) {pure}
    v6: any = opaque.CoreCollectionCall(list, v4) {impure}
    v7: any = eq v4, v5 {pure,throws}
    v8: bool = not v7 {pure}
    v9: bool = not v8 {pure}
    v10: bool = const false
    br_if v9, bb1, bb2(v10)
  bb1:
    v11: any = opaque.NameRead(same) {read:shared,throws}
    v12: bool = opaque.DynamicCall(v11, v4, v5) {impure}
    v13: bool = not v12 {pure}
    br bb2(v13)
  bb2(v14: bool):
    br_if v14, bb3, bb4(v10)
  bb3:
    v15: int = const 0
    v16: any = call_java v6.get(v15) {impure}
    v17: any = checkcast v16 <babble|Vec3>
    v18: any = eq v17, v5 {pure,throws}
    v19: bool = not v18 {pure}
    v20: bool = not v19 {pure}
    br bb4(v20)
  bb4(v21: bool):
    ret v21
```

## Scalarized record locals inside for and try/catch/finally scopes

```babble
def run = fn (): double -> (
    def s = 0.0
    for i in list(1, 2, 3) then ( def v = Vec3(double(i), 1.0, 0.0) ; s = s + v.x + v.y )
    try (
        if s < 0.0 then ( assert(false) )
        def w = Vec3(s, 2.0, 0.0)
        w.x + w.y
    ) catch e then ( 0.0 ) finally ( def z = Vec3(1.0, 0.0, 0.0) ; discard(z.x) )
)
assert(run() == 11.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {nothrow unique}
  (def s 0.0:double)
  (for i in (core-new list 1:int 2:int 3:int)
    (block
      (def v (record-new Vec3 (call name(double) var(i)) 1.0:double 0.0:double))
      (set s (+:double (+:double var(s) (get-this-field x)) (get-this-field y)))))
  (try
    (block
      (if (<:bool var(s) 0.0:double) (block (call name(assert) false:bool)))
      (def w (record-new Vec3 var(s) 2.0:double 0.0:double))
      (+:double (get-this-field x) (get-this-field y)))
    (catch e
      0.0:double)
    (finally
      (def z (record-new Vec3 1.0:double 0.0:double 0.0:double))
      (call name(discard) (get-this-field x))))
```

```hir
;; hir -O1 fn=run
fn run () -> double {nothrow unique}
  (def s 0.0:double)
  (for i in (core-new list 1:int 2:int 3:int)
    (block
      (block
        (def $sr_v_0 (call name(double) var(i)))
        (def $sr_v_1 1.0:double)
        (def $sr_v_2 0.0:double))
      (set s (+:double (+:double var(s) var($sr_v_0)) var($sr_v_1)))))
  (try
    (block
      (if (<:bool var(s) 0.0:double) (call name(assert) false:bool))
      (block
        (def $sr_w_0 var(s))
        (def $sr_w_1 2.0:double)
        (def $sr_w_2 0.0:double))
      (+:double var($sr_w_0) var($sr_w_1)))
    (catch e
      0.0:double)
    (finally
      (block (def $sr_z_2 0.0:double))
      null))
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> double:
  bb0:
    v0: double = const 0.0
    v1: int = const 1
    v2: int = const 2
    v3: int = const 3
    v4: any = opaque.CoreCollectionCall(list, v1, v2, v3) {impure}
    v5: any = for_init.java_iterable v4
    v6: any = const null
    br bb1(v6, v0)
  bb1(v7: any, v8: double):
    v9: bool = for_has_next v5
    br_if v9, bb15, bb2
  bb2:
    br bb3
  bb3 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb10]:
    v10: double = const 0.0
    v11: bool = lt v8, v10 {pure}
    br_if v11, bb5, bb4
  bb4 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb10]:
    v12: any = const null
    br bb6(v12)
  bb5 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb10]:
    v13: any = opaque.NameRead(assert) {read:shared,throws}
    v14: bool = const false
    v15: bool = opaque.DynamicCall(v13, v14) {impure}
    v16: any = convert.any v15
    br bb6(v16)
  bb6(v17: any) [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb10]:
    v18: double = const 2.0
    v19: double = const 0.0
    v20: double = add v8, v18 {pure}
    br bb13
  bb7(v21: any) [handler]:
    br bb9(v21)
  bb8(v22: any) [handler]:
    v23: any = unwrap_thrown v22
    br bb9(v23)
  bb9(v24: any) [unwind: * -> bb10]:
    v25: double = const 0.0
    br bb12
  bb10(v26: any) [handler]:
    br bb11
  bb11:
    v27: double = const 0.0
    v28: any = const null
    rethrow v26
  bb12:
    v29: double = const 0.0
    v30: any = const null
    br bb14(v25)
  bb13:
    v31: double = const 0.0
    v32: any = const null
    br bb14(v20)
  bb14(v33: double):
    ret v33
  bb15:
    v34: any = for_next v5
    v35: any = checkcast v34 <int|int|>
    v36: int = convert.int v35
    v37: any = opaque.NameRead(double) {read:shared,throws}
    v38: double = opaque.DynamicCall(v37, v36) {impure}
    v39: double = const 1.0
    v40: double = const 0.0
    v41: double = add v8, v38 {pure}
    v42: double = add v41, v39 {pure}
    v43: any = convert.any v42
    br bb1(v43, v42)
```

```mir
;; mir -O2 fn=run
fn run() -> double:
  bb0:
    v0: double = const 0.0
    v1: int = const 1
    v2: int = const 2
    v3: int = const 3
    v4: any = opaque.CoreCollectionCall(list, v1, v2, v3) {impure}
    v5: any = for_init.java_iterable v4
    br bb1(v0)
  bb1(v6: double):
    v7: bool = for_has_next v5
    br_if v7, bb12, bb2
  bb2 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb5]:
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb4
  bb3 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb5]:
    v9: any = opaque.NameRead(assert) {read:shared,throws}
    v10: bool = const false
    v11: bool = opaque.DynamicCall(v9, v10) {impure}
    br bb4
  bb4 [unwind: ThrownException -> bb8, Exception -> bb7, * -> bb5]:
    v12: double = const 2.0
    v13: double = add v6, v12 {pure}
    br bb10
  bb5(v14: any) [handler]:
    br bb6
  bb6:
    rethrow v14
  bb7(v15: any) [handler]:
    br bb9
  bb8(v16: any) [handler]:
    br bb9
  bb9:
    br bb11(v0)
  bb10:
    br bb11(v13)
  bb11(v17: double):
    ret v17
  bb12:
    v18: any = for_next v5
    v19: any = checkcast v18 <int|int|>
    v20: int = convert.int v19
    v21: any = opaque.NameRead(double) {read:shared,throws}
    v22: double = opaque.DynamicCall(v21, v20) {impure}
    v23: double = const 1.0
    v24: double = add v6, v22 {pure}
    v25: double = add v24, v23 {pure}
    br bb1(v25)
```

## Projection beta-reduction through move, freeze, and cross-module record construction

```babble
def m = import("vec_mod.ba")
def run = fn (): double -> (
    def a = m.ExtVec(2.0, 5.0).x
    def b = freeze(Vec3(3.0, 4.0, 5.0)).y
    def h = Holder(list(1), 7)
    a + b + double(move(h).count)
)
assert(run() == 13.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {nothrow unique}
  (def a (get-this-field x))
  (def b (get-this-field y))
  (def h (record-new Holder (core-new list 1:int) 7:int))
  (+:double (+:double var(a) var(b)) (call name(double) (get-this-field count)))
```

```hir
;; hir -O1 fn=run
fn run () -> double {nothrow unique}
  (def h (record-new Holder (core-new list 1:int) 7:int))
  (+:double 6.0:double (call name(double) (get-this-field count)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run() -> double:
  bb0:
    v0: any = opaque.NameRead(Holder) {read:shared}
    v1: int = const 1
    v2: any = opaque.CoreCollectionCall(list, v1) {impure}
    v3: int = const 7
    v4: any = record_new Holder[v0](v2, v3) {pure}
    v5: double = const 6.0
    v6: any = opaque.NameRead(double) {read:shared,throws}
    v7: any = move v4
    v8: int = load_field v7.count {read:frozen,throws}
    v9: double = opaque.DynamicCall(v6, v8) {impure}
    v10: double = add v5, v9 {pure}
    ret v10
```

## SROA with freeze/move RHS, non-candidate param copy, non-record call, and one-armed if

```babble
def mk = fn (x: double): Vec3 -> ( def s = x + 1.0 ; def t = s * 2.0 + s * 3.0 + s * 4.0 + s * 5.0 ; Vec3(t, x, 0.0) )
def r = fn (p: Vec3, fl: bool): double -> (
    def a = freeze(Vec3(1.0, 2.0, 3.0)) ; def h = Holder(list(1), 4) ; def b = move(h)
    def c = p ; def d = mk(1.0)
    if fl then ( discard(a.x) )
    a.y + double(b.count) + c.x + d.x
)
assert(r(Vec3(5.0, 0.0, 0.0), true) == 39.0)
```

```hir
;; hir -O0 fn=r
fn r (p:obj, fl:bool) -> double {nothrow unique}
  (def a (freeze (record-new Vec3 1.0:double 2.0:double 3.0:double)))
  (def h (record-new Holder (core-new list 1:int) 4:int))
  (def b (move var(h)))
  (def c var(p))
  (def d (call-fn mk 1.0:double))
  (if var(fl) (block (call name(discard) (get-this-field x))))
  (+:double (+:double (+:double (get-this-field y) (call name(double) (get-this-field count))) (get-this-field x)) (get-this-field x))
```

```hir
;; hir -O1 fn=r
fn r (p:obj, fl:bool) -> double {nothrow unique}
  (block
    (def $sr_a_1 2.0:double)
    (def $sr_a_2 3.0:double))
  (block
    (def $sr_h_0 (core-new list 1:int))
    (def $sr_h_1 4:int))
  (block (def $sr_b_1 var($sr_h_1)))
  (def c var(p))
  (block
    (def $sr_d_0 28.0:double)
    (def $sr_d_2 0.0:double))
  (+:double (+:double (+:double var($sr_a_1) (call name(double) var($sr_b_1))) (get-this-field x)) var($sr_d_0))
```

```mir
;; mir -O2 fn=r stage=before
fn r(v0: any, v1: bool) -> double:
  bb0:
    v2: double = const 2.0
    v3: double = const 3.0
    v4: int = const 1
    v5: any = opaque.CoreCollectionCall(list, v4) {impure}
    v6: int = const 4
    v7: double = const 28.0
    v8: double = const 0.0
    v9: any = opaque.NameRead(double) {read:shared,throws}
    v10: double = opaque.DynamicCall(v9, v6) {impure}
    v11: double = add v2, v10 {pure}
    v12: double = load_field v0.x {read:frozen}
    v13: double = add v11, v12 {pure}
    v14: double = add v13, v7 {pure}
    ret v14
```

```mir
;; mir -O2 fn=r
fn r(v0: any, v1: bool) -> double:
  bb0:
    v2: double = const 2.0
    v3: int = const 1
    v4: any = opaque.CoreCollectionCall(list, v3) {impure}
    v5: int = const 4
    v6: double = const 28.0
    v7: any = opaque.NameRead(double) {read:shared,throws}
    v8: double = opaque.DynamicCall(v7, v5) {impure}
    v9: double = add v2, v8 {pure}
    v10: double = load_field v0.x {read:frozen}
    v11: double = add v9, v10 {pure}
    v12: double = add v11, v6 {pure}
    ret v12
```

## Projection beta-reduction with primitive widening (int -> double) and value-used local record def

```babble
def P = record (a: int, b: double) ()
def mk = fn (): P -> ( def p = P(3, 4) )
def r = fn (): double -> (
    def x: double = P(3, 4).a
    def y = P(3, 4).b
    x + y + double(mk().a)
)
assert(r() == 10.0)
```

```hir
;; hir -O0 fn=r
fn r () -> double {nothrow unique}
  (def x (get-this-field a))
  (def y (get-this-field b))
  (+:double (+:double var(x) var(y)) (call name(double) (get-this-field a)))
```

```hir
;; hir -O1 fn=r
fn r () -> double {nothrow unique}
  (+:double 7.0:double (call name(double) (get-this-field a)))
```

```mir
;; mir -O2 fn=r stage=before,final
fn r() -> double:
  bb0:
    v0: double = const 7.0
    v1: any = opaque.NameRead(double) {read:shared,throws}
    v2: any = opaque.NameRead(P) {read:shared}
    v3: int = const 3
    v4: double = const 4.0
    v5: any = record_new P[v2](v3, v4) {pure}
    v6: int = load_field v5.a {read:frozen}
    v7: double = opaque.DynamicCall(v1, v6) {impure}
    v8: double = add v0, v7 {pure}
    ret v8
```
