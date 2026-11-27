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
  (set tmp (box 1:int))
  (+:int (+:int (+:int (block (if (is var(p) Pair) (block (get-this-field a)) (block 0:int))) (call name(int) (get-this-field x))) (nonnull var(tmp))) (block (if (==:bool var(u) null) (block 0:int) (block 1:int))))
```

```hir
;; hir -O1 fn=run
fn run (fl:bool) -> int {pure unique}
  (def u (block))
  (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set p (record-new Pair 4:int 5:int)))
  (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (set tmp (box 1:int))
  (+:int (+:int (+:int (if (is var(p) Pair) (get-this-field a) 0:int) (call name(int) (get-this-field x))) (nonnull var(tmp))) (if (==:bool var(u) null) 0:int 1:int))
```

```hir
;; hir -O2 fn=run
fn run (fl:bool) -> int {pure unique}
  (block
    (def $cse0 name(Vec3))
    (def p (record-new Vec3 1.0:double 2.0:double 3.0:double))
    (if var(fl) (set p (record-new Pair 4:int 5:int)))
    (set q (record-new Vec3 2.0:double 3.0:double 4.0:double))
    (set tmp (box 1:int))
    (+:int (+:int (+:int (if (is var(p) Pair) (get-this-field a) 0:int) (call name(int) (get-this-field x))) (nonnull var(tmp))) (if (==:bool null null) 0:int 1:int)))
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
  (set t (record-new Vec3 2.0:double 3.0:double 4.0:double))
  (block (def $sr_s_2 0.0:double))
  (if var(fl)
    (block
      (set $sr_s_0 1.0:double)
      (set $sr_s_2 3.0:double))
    (block
      (set $sr_s_0 4.0:double)
      (set $sr_s_2 6.0:double)))
  (+:double (get-this-field x) var($sr_s_0))
```

```hir
;; hir -O2 fn=run
fn run (fl:bool) -> double {nothrow unique}
  (block
    (def $cse0 name(Vec3))
    (set t (record-new Vec3 2.0:double 3.0:double 4.0:double))
    (if var(fl)
      (block
        (set $sr_s_0 1.0:double)
        (set $sr_s_2 3.0:double))
      (block
        (set $sr_s_0 4.0:double)
        (set $sr_s_2 6.0:double)))
    (+:double (get-this-field x) var($sr_s_0)))
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

```hir
;; hir -O2 fn=run
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
  (set p (as var(x) babble|Vec3))
  (def q (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (if var(fl) (set q var(pv)))
  (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
  (def b (record-new Pair 4:int 5:int))
  (def m (if var(fl) var(a) var(b)))
  (+:double (+:double (get-this-field x) (get-this-field x)) (if (is var(m) Pair) (call name(double) (get-this-field a)) (get-this-field x)))
```

```hir
;; hir -O2 fn=run
fn run (x:obj, pv:obj, fl:bool) -> double {pure unique}
  (block
    (def $cse0 name(Vec3))
    (set p (as var(x) babble|Vec3))
    (def q (record-new Vec3 1.0:double 2.0:double 3.0:double))
    (if var(fl) (set q var(pv)))
    (def a (record-new Vec3 1.0:double 2.0:double 3.0:double))
    (def b (record-new Pair 4:int 5:int))
    (def m (if var(fl) var(a) var(b)))
    (+:double (+:double (get-this-field x) (get-this-field x)) (if (is var(m) Pair) (call name(double) (get-this-field a)) (get-this-field x))))
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

```hir
;; hir -O2 fn=run
fn run (f:bool) -> double {nothrow unique}
  (def u (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (record-new Pair 4:int 5:int)))
  (def e (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (block)))
  (def o (if var(f) (record-new Vec3 1.0:double 2.0:double 3.0:double) (if (not var(f)) (record-new Vec3 2.0:double 0.0:double 0.0:double))))
  (+:double (+:double (if (is var(u) Pair) 4.0:double 1.0:double) (if (<>:bool var(e) null) (get-this-field x) 0.0:double)) (if (<>:bool var(o) null) (get-this-field x) 0.0:double))
```
