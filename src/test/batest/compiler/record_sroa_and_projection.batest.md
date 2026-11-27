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
    var acc = Vec3(0.0, 0.0, 0.0)
    def step = Vec3(1.0, 2.0, 3.0)
    var i = 0
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

```hir
;; hir -O2 fn=run
fn run (n:int) -> double {nothrow unique}
  (block
    (block
      (def $sr_acc_0 0.0:double)
      (def $sr_acc_1 0.0:double)
      (def $sr_acc_2 0.0:double))
    (def i 0:int)
    (while (<:bool var(i) var(n))
      (block
        (block
          (block
            (def $sr_$this_0 (+:double var($sr_acc_0) 1.0:double))
            (def $sr_$this_1 (+:double var($sr_acc_1) 2.0:double))
            (def $sr_$this_2 (+:double var($sr_acc_2) 3.0:double)))
          (block
            (set $sr_acc_0 var($sr_$this_1))
            (set $sr_acc_1 var($sr_$this_0))
            (set $sr_acc_2 var($sr_$this_2))))
        (set i (+:int var(i) 1:int))))
    (+:double (+:double (*:double var($sr_acc_0) 1.0:double) (*:double var($sr_acc_1) 2.0:double)) (*:double var($sr_acc_2) 3.0:double)))
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
  (def v (record-new Vec3 3.0:double (widen[double] var(a)) (widen[double] var(b))))
  (+:double (+:double (get-this-field x) (*:double (get-this-field y) 10.0:double)) (*:double (get-this-field z) 100.0:double))
```

```hir
;; hir -O1 fn=f
fn f (a:int, b:int) -> double {nothrow unique}
  (block
    (def $sr_t0 (widen[double] var(a)))
    (def $sr_t1 (widen[double] var(b)))
    (def $sr_v_0 var($sr_t0))
    (def $sr_v_1 var($sr_t1))
    (def $sr_v_2 3.0:double))
  (+:double (+:double var($sr_v_0) (*:double var($sr_v_1) 10.0:double)) (*:double var($sr_v_2) 100.0:double))
```

```hir
;; hir -O2 fn=f
fn f (a:int, b:int) -> double {nothrow unique}
  (block
    (def $sr_t0 (widen[double] var(a)))
    (def $sr_t1 (widen[double] var(b)))
    (def $sr_v_0 var($sr_t0))
    (def $sr_v_1 var($sr_t1))
    (def $sr_v_2 3.0:double))
  (+:double (+:double var($sr_v_0) (*:double var($sr_v_1) 10.0:double)) 300.0:double)
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

```hir
;; hir -O2 fn=run
fn run () -> double {io}
  (block
    (def $cse0 name(emit))
    (block
      (call-fn emit "A" 1.0:double)
      (def $sr_p0 (call-fn emit "B" 2.0:double))
      (call-fn emit "C" 3.0:double)
      var($sr_p0)))
```

## Conditional if/else record SROA, copy propagation, and self-assignment

```babble
def pick = fn (flag: bool): double -> (
    var p = if flag then ( Vec3(1.0, 2.0, 3.0) ) else ( Vec3(4.0, 5.0, 6.0) )
    var q = p
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
      (def $sr_t1 var($sr_p_0))
      (set $sr_p_0 var($sr_t0))
      (set $sr_p_2 var($sr_t1))))
  (+:double (+:double var($sr_p_0) var($sr_q_1)) var($sr_p_2))
```

```hir
;; hir -O2 fn=pick
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
    (set $sr_q_1 var($sr_p_1))
    (set $sr_q_2 var($sr_p_2)))
  (if var(flag)
    (block
      (def $sr_t0 var($sr_p_0))
      (set $sr_p_0 var($sr_p_2))
      (set $sr_p_2 var($sr_t0))))
  (+:double (+:double var($sr_p_0) var($sr_q_1)) var($sr_p_2))
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

```hir
;; hir -O2 fn=run
fn run (k:int) -> obj {pure unique}
  (def p (call name(Pack) (call name(byte) var(k)) (call name(short) (+:int var(k) 1:int)) (+:int var(k) 2:int) (call name(long) (+:int var(k) 3:int)) (call name(float) var(k)) (call name(double) var(k)) (>:bool var(k) 0:int) 'A' "ok"))
  (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj "" (get-this-field b)) ":") (get-this-field s)) ":") (get-this-field i)) ":") (get-this-field l)) ":") (get-this-field z)) ":") (get-this-field c)) ":") (get-this-field t))
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

```hir
;; hir -O2 fn=run
fn run () -> bool {pure unique}
  (block
    (def $cse0 name(Vec3))
    (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
    (def b (record-new Vec3 1.0:double 2.0:double 3.0:double))
    (def xs (core-new list var(a)))
    (and (and (==:bool var(a) var(b)) (not (call name(same) var(a) var(b)))) (==:bool (jcall List.get var(xs) 0:int) var(b))))
```

## Scalarized record locals inside for and try/catch/finally scopes

```babble
def run = fn (): double -> (
    var s = 0.0
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
      (if (<:bool var(s) 0.0:double) (box (block (call name(assert) false:bool))))
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
      (if (<:bool var(s) 0.0:double) (box (call name(assert) false:bool)))
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

```hir
;; hir -O2 fn=run
fn run () -> double {nothrow unique}
  (def s 0.0:double)
  (for i in (core-new list 1:int 2:int 3:int)
    (block
      (block
        (def $sr_v_0 (call name(double) var(i)))
        (def $sr_v_2 0.0:double))
      (set s (+:double (+:double var(s) var($sr_v_0)) 1.0:double))))
  (try
    (block
      (if (<:bool var(s) 0.0:double) (box (call name(assert) false:bool)))
      (block
        (def $sr_w_0 var(s))
        (def $sr_w_2 0.0:double))
      (+:double var($sr_w_0) 2.0:double))
    (catch e
      0.0:double)
    (finally
      null))
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

```hir
;; hir -O2 fn=run
fn run () -> double {nothrow unique}
  (def h (record-new Holder (core-new list 1:int) 7:int))
  (+:double 6.0:double (call name(double) (get-this-field count)))
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
    (core-new list 1:int)
    (def $sr_h_1 4:int))
  (block (def $sr_b_1 var($sr_h_1)))
  (def c var(p))
  (block
    (def $sr_d_0 28.0:double)
    (def $sr_d_2 0.0:double))
  (+:double (+:double (+:double var($sr_a_1) (call name(double) var($sr_b_1))) (get-this-field x)) var($sr_d_0))
```

```hir
;; hir -O2 fn=r
fn r (p:obj, fl:bool) -> double {nothrow unique}
  (block
    (core-new list 1:int)
    (def $sr_h_1 4:int))
  (+:double (+:double (+:double 2.0:double (call name(double) 4:int)) (get-this-field x)) 28.0:double)
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
  (def x (widen[double] (get-this-field a)))
  (def y (get-this-field b))
  (+:double (+:double var(x) var(y)) (call name(double) (get-this-field a)))
```

```hir
;; hir -O1 fn=r
fn r () -> double {nothrow unique}
  (+:double 7.0:double (call name(double) (get-this-field a)))
```

```hir
;; hir -O2 fn=r
fn r () -> double {nothrow unique}
  (+:double 7.0:double (call name(double) (get-this-field a)))
```
