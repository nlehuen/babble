# FBIP Record Reuse Tail Shapes and Purity Bailouts

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



## FBIP record reuse with two unique same-type params, non-unique reads/assigns, and plain class

```babble
def Plain = class (def k: int) ()
def merge = fn (unique a: Holder, unique b: Holder, v: Vec3): Holder -> (
    var c = a.count + b.count + int(v.x)
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

```hir
;; hir -O2 fn=merge
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

```hir
;; hir -O2 fn=emptyElse
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

```hir
;; hir -O2 fn=onlyElse
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

```hir
;; hir -O2 fn=toExt
fn toExt (h:obj) -> obj {nothrow}
  (record-new ext_rec$ba$C1$ExtHolder (get-this-field items) (+:int (get-this-field count) 1:int))
```

## FBIP record reuse classRef purity : local alias reuses while mutable alias bails out

```babble
var HRef = Holder
def pureLocal = fn (unique h: Holder): Holder -> ( def H = Holder ; H(h.items, h.count + 1) )
def nonInitRef = fn volatile (unique h: Holder): Holder -> ( HRef(h.items, h.count + 2) )
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
  (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int))
```

```hir
;; hir -O0,-O1 fn=nonInitRef
fn nonInitRef (h:obj) -> obj {volatile nothrow}
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 2:int))
```

```hir
;; hir -O2 fn=nonInitRef
fn nonInitRef (h:obj) -> obj {volatile nothrow}
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

```hir
;; hir -O2 fn=impureRef
fn impureRef (h:obj) -> obj {io}
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 3:int))
```
