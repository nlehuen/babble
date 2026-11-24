# Scalar replacement of aggregates (O9) and FBIP reuse (O10) : bailouts, widening, and edge shapes

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
  (def lm name(m))
  (def b
    (block
      (export (block
          (jcall StringBuilder.append var(log) "M")
          name(m)).ExtVec)
      5.0:double))
  (call name(assert) (==:bool (jcall StringBuilder.toString var(log)) "M"))
  (+:double 2.0:double var(b))
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

## Unboxed borrow param disqualifiers : captured cell param and reassigned param

```babble
def capP = fn (a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    def g = fn (): double -> ( a.x )
    g() + b.y
)
def reas = fn (a: Vec3, b: Vec3): double -> (
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

## Unboxed body rewriting with uninitialized local, move, anonymous catch, and non-flattened param

```babble
def work = fn (a: Vec3, b: Vec3): double -> (
    if a.x < -999.0 then ( return 0.0 )
    def tmp: double?
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
  (def tmp)
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
  (def tmp)
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

## SROA candidate discovery bailouts : mismatched union assign, uninitialized def, empty block

```babble
def run = fn (fl: bool): int -> (
    def u: any? = ()
    def p: Vec3 | Pair = Vec3(1.0, 2.0, 3.0)
    if fl then ( p = Pair(4, 5) )
    def q: Vec3? ; q = Vec3(2.0, 3.0, 4.0)
    def tmp: int? ; tmp = 1
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
  (def q)
  (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def tmp)
  (set tmp 1:int)
  (+:int (+:int (+:int (block (if (is var(p) Pair) (block (get-this-field a)) (block 0:int))) (call name(int) (get-this-field x))) (nonnull var(tmp))) (block (if (==:bool var(u) null) (block 0:int) (block 1:int))))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> int {pure unique}
  (def u (block))
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set p (record-new Pair 4:int 5:int)))
  (def q)
  (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (def tmp)
  (set tmp 1:int)
  (+:int (+:int (+:int (if (is var(p) Pair) (get-this-field a) 0:int) (call name(int) (get-this-field x))) (nonnull var(tmp))) (if (==:bool var(u) null) 0:int 1:int))
```

## SROA verification bailouts : typed def/assign, value-used assign, and statement if/else

```babble
def valA = fn (): Vec3 -> ( def p = Vec3(1.0, 2.0, 3.0) ; p = Vec3(4.0, 5.0, 6.0) )
def run = fn (fl: bool): double -> (
    def t: Vec3 = Vec3(1.0, 2.0, 3.0) ; t = Vec3(2.0, 3.0, 4.0)
    def s = Vec3(0.0, 0.0, 0.0)
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

## SROA destructured RHS bailouts : mixed if/else with non-inlined call and assign to call

```babble
def run = fn (fl: bool): double -> (
    def a = if fl then ( Vec3(1.0, 2.0, 3.0) ) else ( mkVec(4.0) )
    def b = if fl then ( mkVec(5.0) ) else ( Vec3(2.0, 3.0, 4.0) )
    def c = Vec3(1.0, 2.0, 3.0)
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

## SROA destructured RHS bailouts : as-cast, non-candidate copy, and mismatched candidate copy

```babble
def run = fn (x: any, pv: Vec3, fl: bool): double -> (
    def p = Vec3(1.0, 2.0, 3.0) ; p = (x as Vec3)
    def q = Vec3(1.0, 2.0, 3.0) ; if fl then ( q = pv )
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

## FBIP record reuse with two unique same-type params, non-unique reads/assigns, and plain class

```babble
def Plain = class (def k: int) ()
def merge = fn (unique a: Holder, unique b: Holder, v: Vec3): Holder -> (
    def c = a.count + b.count + int(v.x)
    c = c + 1
    Holder(a.items, c)
)
def res = merge(Holder(list(1), 2), Holder(list(2), 3), Vec3(4.0, 0.0, 0.0))
assert(res.count == 10 and Plain(7).k == 7)
```

```hir
;; hir -O0 fn=merge
fn merge (a:obj, b:obj, v:obj) -> obj {pure}
  (def c (+:int (+:int (get-this-field count) (get-this-field count)) (call name(int) (get-this-field x))))
  (set c (+:int var(c) 1:int))
  (record-new Holder (get-this-field items) var(c))
```

```hir
;; hir -O1 fn=merge
fn merge (a:obj, b:obj, v:obj) -> obj {pure}
  (def c (+:int (+:int (get-this-field count) (get-this-field count)) (call name(int) (get-this-field x))))
  (set c (+:int var(c) 1:int))
  (record-reuse var(a) Holder (get-this-field items) var(c))
```

## FBIP record reuse tail shapes : one-armed if and empty block else

```babble
def oneArm = fn (unique h: Holder, fl: bool): Holder? -> (
    if fl then ( Holder(h.items, h.count + 1) )
)
def emptyElse = fn (unique h: Holder, fl: bool): Holder? -> (
    if fl then ( Holder(h.items, h.count + 1) ) else ()
)
assert(oneArm(Holder(list(1), 1), true)!.count == 2 and emptyElse(Holder(list(1), 1), false) == null)
```

```hir
;; hir -O0 fn=oneArm
fn oneArm (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) (block (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int))))
```

```hir
;; hir -O1 fn=oneArm
fn oneArm (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int)))
```

```hir
;; hir -O0 fn=emptyElse
fn emptyElse (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) (block (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int))) (block))
```

```hir
;; hir -O1 fn=emptyElse
fn emptyElse (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int)) (block))
```

## FBIP record reuse tail shapes : else-only reuse branch

```babble
def onlyElse = fn (unique h: Holder, fl: bool): Holder? -> (
    if fl then ( null ) else ( Holder(h.items, h.count + 2) )
)
assert(onlyElse(Holder(list(1), 1), false)!.count == 3 and onlyElse(Holder(list(1), 1), true) == null)
```

```hir
;; hir -O0 fn=onlyElse
fn onlyElse (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) (block null) (block (record-new Holder (get-this-field items) (+:int (get-this-field count) 2:int))))
```

```hir
;; hir -O1 fn=onlyElse
fn onlyElse (h:obj, fl:bool) -> obj {nothrow}
  (if var(fl) null (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 2:int)))
```

## FBIP record reuse disqualifiers : captured unique param and cross-module mutable record

```babble
def m = import("ext_rec.ba")
def capH = fn (unique h: Holder): Holder -> (
    def g = fn (): int -> ( h.count )
    Holder(h.items, g() + 1)
)
def toExt = fn (unique h: Holder): m.ExtHolder -> (
    m.ExtHolder(h.items, h.count + 1)
)
assert(capH(Holder(list(1), 3)).count == 4)
assert(toExt(Holder(list(1), 4)).count == 5)
```

```hir
;; hir -O0,-O1 fn=capH
fn capH (h:obj) -> obj {nothrow}
  (def g (fn-ref g))
  (record-new Holder (get-this-field items) (+:int (call-fn g) 1:int))
```

```hir
;; hir -O0,-O1 fn=toExt
fn toExt (h:obj) -> obj {nothrow}
  (record-new ext_rec$ba$C1$ExtHolder (get-this-field items) (+:int (get-this-field count) 1:int))
```

## FBIP record reuse classRef purity : local alias reuses while non-init alias bails out

```babble
def HRef = Holder
def pureLocal = fn (unique h: Holder): Holder -> ( def H = Holder ; H(h.items, h.count + 1) )
def nonInitRef = fn (unique h: Holder): Holder -> ( HRef(h.items, h.count + 2) )
assert(pureLocal(Holder(list(1), 1)).count == 2 and nonInitRef(Holder(list(1), 1)).count == 3)
```

```hir
;; hir -O0 fn=pureLocal
fn pureLocal (h:obj) -> obj {nothrow}
  (def H name(Holder))
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int))
```

```hir
;; hir -O1 fn=pureLocal
fn pureLocal (h:obj) -> obj {nothrow}
  (def H name(Holder))
  (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int))
```

```hir
;; hir -O0,-O1 fn=nonInitRef
fn nonInitRef (h:obj) -> obj {nothrow}
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 2:int))
```

## FBIP record reuse classRef purity : impure block classRef bails out

```babble
def log = java.lang.StringBuilder()
def impureRef = fn io (unique h: Holder): Holder -> (
    ( log.append("H") ; Holder )(h.items, h.count + 3)
)
assert(impureRef(Holder(list(1), 1)).count == 4 and log.toString() == "H")
```

```hir
;; hir -O0,-O1 fn=impureRef
fn impureRef (h:obj) -> obj {io}
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 3:int))
```
