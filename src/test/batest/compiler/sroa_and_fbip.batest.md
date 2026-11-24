# Scalar replacement of aggregates (O9) and FBIP reuse (O10) : bailouts, widening, and edge shapes

References : `BIP014-I4`, `BIP007-I2`, `ScalarReplacePass`, `FbipReusePass`.

```babble module=ext_rec.ba
export def ExtVec = record (x: double, y: double) ()
export def ExtHolder = record (items: list of int, count: int) ()
```

```babble preamble
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

```hir -O1 fn=run check
!contains record-new
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

```hir -O1 fn=run check
!contains record-new
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

```hir -O1 fn=run check
contains $unboxed
contains (call-fn heavy
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

```hir -O1 fn=merge check
contains (record-reuse var(a) Holder
!contains record-new
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

## FBIP record reuse tail shapes : else-only reuse branch

```babble
def onlyElse = fn (unique h: Holder, fl: bool): Holder? -> (
    if fl then ( null ) else ( Holder(h.items, h.count + 2) )
)
assert(onlyElse(Holder(list(1), 1), false)!.count == 3 and onlyElse(Holder(list(1), 1), true) == null)
```

```hir -O1 fn=onlyElse check
contains (record-reuse var(h) Holder
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

```hir -O1 fn=capH check
!contains record-reuse
```

```hir -O1 fn=toExt check
!contains record-reuse
```

## FBIP record reuse classRef purity : local alias reuses while non-init alias bails out

```babble
def HRef = Holder
def pureLocal = fn (unique h: Holder): Holder -> ( def H = Holder ; H(h.items, h.count + 1) )
def nonInitRef = fn (unique h: Holder): Holder -> ( HRef(h.items, h.count + 2) )
assert(pureLocal(Holder(list(1), 1)).count == 2 and nonInitRef(Holder(list(1), 1)).count == 3)
```

```hir -O1 fn=pureLocal check
contains (record-reuse var(h) Holder
```

```hir -O1 fn=nonInitRef check
!contains record-reuse
```

## FBIP record reuse classRef purity : impure block classRef bails out

```babble
def log = java.lang.StringBuilder()
def impureRef = fn io (unique h: Holder): Holder -> (
    ( log.append("H") ; Holder )(h.items, h.count + 3)
)
assert(impureRef(Holder(list(1), 1)).count == 4 and log.toString() == "H")
```

```hir -O1 fn=impureRef check
!contains record-reuse
```
