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
  (def w2 (+:double (convert[double] (get-this-field a)) 1.5:double))
  (def bx (get-this-field v))
  (+:double (+:double var(w1) var(w2)) (call name(double) (as var(bx) int|int|)))
```

```hir
;; hir -O1 fn=run
fn run (n:int, k:int) -> double {pure unique}
  (def w1 (widen[double] var(k)))
  (def w2 (+:double (convert[double] var(n)) 1.5:double))
  (def bx (box var(n)))
  (+:double (+:double var(w1) var(w2)) (call name(double) (as var(bx) int|int|)))
```

```hir
;; hir -O2 fn=run
fn run (n:int, k:int) -> double {pure unique}
  (block
    (def $cse0 (widen[double] var(k)))
    (def w2 (+:double (convert[double] var(n)) 1.5:double))
    (+:double (+:double var($cse0) var(w2)) (call name(double) var(n))))
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
  (class-ref R)
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "CS"))
  (+:double (+:double var(a) var($sr_v_0)) (call name(double) 13:int))
```

```hir
;; hir -O2 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (block
    (block
      (jcall StringBuilder.append var(log) "C")
      name(Vec3))
    2.0:double)
  (block
    (block
      (jcall StringBuilder.append var(log) "S")
      name(Vec3))
    (def $sr_v_2 6.0:double))
  (class-ref R)
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "CS"))
  (+:double 6.0:double (call name(double) 13:int))
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

```hir
;; hir -O2 fn=run
fn run () -> double {io}
  (def log (jnew StringBuilder))
  (block
    (export (block
        (jcall StringBuilder.append var(log) "M")
        name(m)).ExtVec)
    5.0:double)
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "M"))
  7.0:double
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

```hir
;; hir -O2 fn=run
fn run () -> int {pure}
  (def e (record-new Empty))
  (def f
    (block
      (def $this (record-new Empty))
      (get-this-field one)))
  (if (==:bool var(e) (record-new Empty)) (call var(f)) 0:int)
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

```hir
;; hir -O2 fn=run
fn run () -> int {pure unique}
  (def v (record-new Box 5:int))
  (+:int (+:int (call (get-this-field inc)) (call (block
          (def $this (record-new Box 3:int))
          (get-this-field inc)))) (call-fn useB$unboxed var(v) 2:int))
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

```hir
;; hir -O2 fn=run
fn run (a:obj, b:obj) -> double {io}
  (def lg (jnew StringBuilder))
  (def r (+:double (+:double (+:double (call-fn id (call-fn heavy$unboxed (get-this-field x) (get-this-field y) (get-this-field z) (get-this-field x) (get-this-field y) (get-this-field z))) (call-fn heavy var(b) var(a))) (call-fn heavy (record-new Vec3 3.0:double 1.0:double 2.0:double) var(b))) (call-fn heavy (record-new Vec3 1.0:double 2.0:double 3.0:double) var(b))))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(lg)) "U"))
  var(r)
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

```hir
;; hir -O2 fn=run
fn run (fl:bool) -> double {nothrow}
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Vec3 1.0:double 0.0:double 0.0:double))
  (def cap (fn-ref cap))
  (def r (+:double (+:double (call-fn heavy var(a) var(b)) (call-fn cap)) 0.0:double))
  (def c (record-new Vec3 2.0:double 0.0:double 0.0:double))
  (if var(fl) (+:double var(r) (call-fn heavy var(c) var(b))) var(r))
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

```hir
;; hir -O2 fn=reas
fn reas (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (return 0.0:double))
  (set a (record-new Vec3 0.0:double 0.0:double 0.0:double))
  (+:double (get-this-field x) (get-this-field y))
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

```hir
;; hir -O2 top-level
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
      (def $cse0 (get-this-field x))
      (def $cse1 (get-this-field y))
      (def $cse2 (get-this-field z))
      (call-fn <anon#0> var($cse0) var($cse1) var($cse2) var($cse0) var($cse1) var($cse2))))
  (call name(assert) (and (==:bool name(r) 3.0:double) (==:bool (call-fn wide name(v) name(v) name(v)) 6.0:double)))
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
  (set tmp (box (+:double (+:double (get-this-field x) (get-this-field y)) (call name(double) (+:int (get-this-field count) (jcall List.size var(xs)))))))
  (try
    (block
      (if (<:bool (nonnull var(tmp)) 0.0:double) (box (block (call name(assert) false:bool))))
      (nonnull var(tmp)))
    (catch _
      0.0:double))
```

```hir
;; hir -O1 fn=work
fn work (a:obj, b:obj) -> double {nothrow unique}
  (if (<:bool (get-this-field x) -999.0:double) (return 0.0:double))
  (def xs (core-new list var(a)))
  (block
    (core-new list 1:int)
    (def $sr_h_1 2:int))
  (block (def $sr_h2_1 var($sr_h_1)))
  (set tmp (box (+:double (+:double (get-this-field x) (get-this-field y)) (call name(double) (+:int var($sr_h2_1) (jcall List.size var(xs)))))))
  (try
    (block
      (if (<:bool (nonnull var(tmp)) 0.0:double) (box (call name(assert) false:bool)))
      (nonnull var(tmp)))
    (catch _
      0.0:double))
```

```hir
;; hir -O2 fn=work
fn work (a:obj, b:obj) -> double {nothrow unique}
  (block
    (def $cse0 (get-this-field x))
    (if (<:bool var($cse0) -999.0:double) (return 0.0:double))
    (def xs (core-new list var(a)))
    (block
      (core-new list 1:int)
      (def $sr_h_1 2:int))
    (set tmp (box (+:double (+:double var($cse0) (get-this-field y)) (call name(double) (+:int 2:int (jcall List.size var(xs)))))))
    (try
      (block
        (if (<:bool (nonnull var(tmp)) 0.0:double) (box (call name(assert) false:bool)))
        (nonnull var(tmp)))
      (catch _
        0.0:double)))
```
