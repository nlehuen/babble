# Record SROA, projection beta-reduction, unboxed borrows, and method/lambda inlining

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
