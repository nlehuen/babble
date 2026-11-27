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

```hir
;; hir -O2 fn=heavy
fn heavy (a:obj, b:obj) -> double {nothrow unique}
  (block
    (def $cse0 (get-this-field x))
    (def $cse1 (get-this-field x))
    (def $cse2 (get-this-field y))
    (def $cse3 (get-this-field y))
    (def $cse4 (get-this-field z))
    (def $cse5 (get-this-field z))
    (def d1 (+:double (+:double (*:double var($cse0) var($cse1)) (*:double var($cse2) var($cse3))) (*:double var($cse4) var($cse5))))
    (def d2 (+:double (+:double (*:double (+:double var($cse0) 1.0:double) var($cse1)) (*:double (+:double var($cse2) 2.0:double) var($cse3))) (*:double (+:double var($cse4) 3.0:double) var($cse5))))
    (def d3 (+:double (*:double (-:double var($cse0) var($cse2)) (-:double var($cse1) var($cse3))) (*:double (-:double var($cse2) var($cse4)) (-:double var($cse3) var($cse5)))))
    (+:double (+:double var(d1) var(d2)) var(d3)))
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

```hir
;; hir -O2 fn=run
fn run () -> double {pure unique}
  (block
    (def $cse0 name(Vec3))
    (def a (record-new Vec3 2.0:double 3.0:double 4.0:double))
    (block
      (def xs (core-new list var(a)))
      (+:double (+:double (get-this-field x) 6.0:double) 7.0:double)))
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

```hir
;; hir -O2 fn=run
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
;; hir -O0 fn=run
fn run (n:int) -> double {nothrow unique}
  (def a (record-new vec_mod$ba$C0$ExtVec 3.0:double 4.0:double))
  (def b (call-method (record-new vec_mod$ba$C0$ExtVec 1.0:double 2.0:double).neg))
  (def c (call-method var(a).plus var(b)))
  (+:double (+:double (+:double (get-this-field x) (get-this-field y)) (call-method var(c).mag2)) (call name(double) var(n)))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> double {nothrow unique}
  (block
    (def $sr_a_0 3.0:double)
    (def $sr_a_1 4.0:double))
  (block
    (block
      (def $sr_$this_0 1.0:double)
      (def $sr_$this_1 2.0:double))
    (block
      (def $sr_b_0 (neg var($sr_$this_0)))
      (def $sr_b_1 (neg var($sr_$this_1)))))
  (block
    (def $sr_c_0 (+:double var($sr_a_0) var($sr_b_0)))
    (def $sr_c_1 (+:double var($sr_a_1) var($sr_b_1))))
  (+:double (+:double (+:double var($sr_c_0) var($sr_c_1)) (+:double (*:double var($sr_c_0) var($sr_c_0)) (*:double var($sr_c_1) var($sr_c_1)))) (call name(double) var(n)))
```

```hir
;; hir -O2 fn=run
fn run (n:int) -> double {nothrow unique}
  (block
    (block (def $sr_b_1 -2.0:double))
    (+:double 12.0:double (call name(double) var(n))))
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

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {nothrow unique}
  (block
    (block
      (def $sr_$this_0 (+:int var(x) 3:int))
      (def $sr_b_0 (+:int var($sr_$this_0) 4:int)))
    var($sr_b_0))
```

## Unboxed borrow entry point with local vars, for-loop, try, and non-record scalar param

```babble
def heavyLoop = fn (a: Vec3, s: int): double -> (
    var acc = 0.0
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
      (if (<:bool var(s) 0:int) (box (block (call name(assert) false:bool))))
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
      (if (<:bool var(s) 0:int) (box (call name(assert) false:bool)))
      (+:double var(acc) (*:double (*:double (get-this-field x) (get-this-field y)) (get-this-field z))))
    (catch e
      0.0:double))
```

```hir
;; hir -O2 fn=heavyLoop
fn heavyLoop (a:obj, s:int) -> double {nothrow unique}
  (def acc 0.0:double)
  (block
    (def $licm0 (get-this-field x))
    (def $licm1 (get-this-field y))
    (def $licm2 (get-this-field z))
    (for k in (core-new list 1:int 2:int)
      (block
        (block
          (def $cse0 name(double))
          (def t (+:double (*:double (+:double var($licm0) (call var($cse0) var(k))) (call var($cse0) var(s))) (*:double var($licm1) var($licm2))))
          (set acc (+:double (-:double (+:double (+:double var(acc) var(t)) var($licm0)) var($licm1)) var($licm2)))))))
  (try
    (block
      (if (<:bool var(s) 0:int) (box (call name(assert) false:bool)))
      (+:double var(acc) (*:double (*:double (get-this-field x) (get-this-field y)) (get-this-field z))))
    (catch e
      0.0:double))
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
  (+:double (call name(double) (+:int (call-method var(c).add b=3:int a=2:int) (+:int (get-this-field x) 3:int))) (call-fn rec (record-new Vec3 1.0:double 2.0:double 0.0:double) 3:int))
```

```hir
;; hir -O2 fn=run
fn run () -> double {nothrow unique}
  (def c (call name(C) 10:int))
  (block
    (def $cse0 (get-this-field x))
    (+:double (call name(double) (+:int (call-method var(c).add b=3:int a=2:int) (+:int var($cse0) 3:int))) (call-fn rec (record-new Vec3 1.0:double 2.0:double 0.0:double) 3:int)))
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

```hir
;; hir -O2 fn=r
fn r () -> int {nothrow unique}
  (+:int (block
      (def $this (call name(Safe) 4:int))
      (get-this-field y)) (call-method (call name(Leak) 5:int).get))
```

## Inlining with parameter reassignment, widening read, for loop, and move inside callee

```babble
def f = fn (var x: int): double -> (
    var s = 0
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
      (+:double (convert[double] var(x#1)) 0.5:double)) (block
      (def x#2 1:int)
      (def s#2 0:int)
      (for k#2 in (core-new list 1:int 2:int)
        (block
          (set s#2 (+:int var(s#2) var(k#2)))))
      (set x#2 (+:int var(x#2) var(s#2)))
      (+:double (convert[double] var(x#2)) 0.5:double)))
```

```hir
;; hir -O2 fn=r
fn r () -> double {nothrow unique}
  (+:double (block
      (def s#1 0:int)
      (for k#1 in (core-new list 1:int 2:int)
        (block
          (set s#1 (+:int var(s#1) var(k#1)))))
      (set x#1 (+:int var(s#1) 3:int))
      (+:double (convert[double] var(x#1)) 0.5:double)) (block
      (def s#2 0:int)
      (for k#2 in (core-new list 1:int 2:int)
        (block
          (set s#2 (+:int var(s#2) var(k#2)))))
      (set x#2 (+:int var(s#2) 1:int))
      (+:double (convert[double] var(x#2)) 0.5:double)))
```

## Inlining with capturing borrow lambda and receiver var reassigned in call arg

```babble
def Box = class (def v: int) (
    def add = fn (n: int): int -> ( this.v + n )
)
def ap = fn (f: fn (int, int): int): int -> ( f(3, 2) )
def r = fn (): int -> (
    var b = Box(10) ; def k = 5
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

```hir
;; hir -O2 fn=r
fn r () -> int {nothrow unique}
  (block
    (def $cse0 name(Box))
    (def b (call var($cse0) 10:int))
    (def k 5:int)
    (+:int (+:int (block
          (def $this var(b))
          (block
            (set b (call var($cse0) 20:int))
            1:int)
          (+:int (get-this-field v) 1:int)) (get-this-field v)) (block
        (def f (fn-ref <anon#0>))
        (call var(f) 3:int 2:int))))
```
