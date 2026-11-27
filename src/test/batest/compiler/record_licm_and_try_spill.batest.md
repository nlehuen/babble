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

```hir
;; hir -O2 fn=run
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

```hir
;; hir -O2 fn=r
fn r () -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (set s (+:double var(s) 147.0:double))
      (set i (+:int var(i) 1:int))))
  var(s)
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
;; hir -O0 fn=r
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

```hir
;; hir -O1 fn=r
fn r () -> int {nothrow unique}
  (def f (freeze (call name(X) 5:int)))
  (def s 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field v))
    (while (<:bool var(i) 3:int)
      (block
        (set s (+:int var(s) var($licm0)))
        (set i (+:int var(i) 1:int)))))
  var(s)
```

```hir
;; hir -O2 fn=r
fn r () -> int {nothrow unique}
  (def f (freeze (call name(X) 5:int)))
  (def s 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field v))
    (while (<:bool var(i) 3:int)
      (block
        (set s (+:int var(s) var($licm0)))
        (set i (+:int var(i) 1:int)))))
  var(s)
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

```hir
;; hir -O2 fn=r
fn r (a:obj, b:obj) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (set s (+:int (+:int var(s) (call-iface var(a).f 2:int)) (call-iface var(b).f 2:int)))
      (set i (+:int var(i) 1:int))))
  var(s)
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

```hir
;; hir -O2 fn=run
fn run (x:int) -> double {pure unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) 3:int)
    (block
      (def $cse0 name(double))
      (block
        (def $sr_v_0 (call var($cse0) var(x)))
        (def $sr_v_2 0.0:double))
      (set s (+:double (+:double (+:double var(s) (call var($cse0) name(TOP_K))) (as var(x) double|double|)) var($sr_v_0)))
      (set i (+:int var(i) 1:int))))
  var(s)
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
          (if var(fl) (box (block (call name(assert) false:bool))))
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
          (if var(fl) (box (call name(assert) false:bool)))
          (+:int (get-this-field count) 1:int))
        (catch e
          9:int)))
    (record-reuse var($try0) Holder var($try1) var($try2)))
```

```hir
;; hir -O2 fn=bump
fn bump (h:obj, fl:bool) -> obj {nothrow}
  (block
    (def $try0 var(h))
    (def $try1 (get-this-field items))
    (def $try2
      (try
        (block
          (if var(fl) (box (call name(assert) false:bool)))
          (+:int (get-this-field count) 1:int))
        (catch e
          9:int)))
    (record-reuse var($try0) Holder var($try1) var($try2)))
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
          (if var(fl) (box (block (call name(assert) false:bool))))
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
          (if var(fl) (box (call name(assert) false:bool)))
          2:int)
        (catch e
          3:int)))
    (+:int (get-this-field v) var(n)))
```

```hir
;; hir -O2 fn=r
fn r (fl:bool) -> int {nothrow unique}
  (def b (call name(Box) 1:int))
  (call-method var(b).put var(fl))
  (block
    (def n
      (try
        (block
          (if var(fl) (box (call name(assert) false:bool)))
          2:int)
        (catch e
          3:int)))
    (+:int (get-this-field v) var(n)))
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
          (if var(fl) (box (block (call name(assert) false:bool))))
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
          (if var(fl) (box (call name(assert) false:bool)))
          10:int)
        (catch e
          20:int)))
    (set-name top var($try0)))
  name(top)
```

```hir
;; hir -O2 fn=r
fn r (fl:bool) -> int {io}
  (block
    (def $try0
      (try
        (block
          (if var(fl) (box (call name(assert) false:bool)))
          10:int)
        (catch e
          20:int)))
    (set-name top var($try0)))
  name(top)
```
