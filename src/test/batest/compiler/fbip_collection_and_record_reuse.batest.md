# FBIP Collection and Record In-Place Reuse

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


## FBIP collection reuse for list and set (exact mutable types)

```babble
def addL = fn (unique xs: list of int): list of int -> ( def r = list(from: move(xs)) ; r.add(9) ; r )
def addS = fn (unique s: set of int): set of int -> ( def r = set(from: move(s)) ; r.add(9) ; r )
def run = fn (): bool -> ( addL(list(1, 2)).size() == 3 and addS(set(1)).size() == 2 )
assert(run())
```

```hir
;; hir -O0 fn=addL
fn addL (xs:obj) -> obj {pure unique}
  (def r (core-new list from=(move var(xs))))
  (jcall List.add var(r) 9:int)
  var(r)
```

```hir
;; hir -O1 fn=addL
fn addL (xs:obj) -> obj {pure unique}
  (def r (core-new list$reuse from=(move var(xs))))
  (jcall List.add var(r) 9:int)
  var(r)
```

```hir
;; hir -O0 fn=addS
fn addS (s:obj) -> obj {pure unique}
  (def r (core-new set from=(move var(s))))
  (jcall Set.add var(r) 9:int)
  var(r)
```

```hir
;; hir -O1 fn=addS
fn addS (s:obj) -> obj {pure unique}
  (def r (core-new set$reuse from=(move var(s))))
  (jcall Set.add var(r) 9:int)
  var(r)
```

```mir
;; mir -O2 fn=addS stage=before,final
fn addS(v0: any [unique]) -> any:
  bb0:
    v1: any = move v0
    v2: any = opaque.CoreCollectionCall(v1) {impure}
    v3: int = const 9
    v4: bool = call_java v2.add(v3) {impure}
    ret v2
```

## FBIP collection reuse for map (exact mutable type) and fallback on TreeMap

```babble
def addM = fn (unique m: map of (string, int)): map of (string, int) -> (
    def r = map(from: move(m))
    r.put("b", 2)
    r
)
def run = fn (): bool -> (
    addM(map(entry("a", 1))).size() == 2 and addM(sortedmap(entry("a", 1))).size() == 2
)
assert(run())
```

```hir
;; hir -O0 fn=addM
fn addM (m:obj) -> obj {pure unique}
  (def r (core-new map from=(move var(m))))
  (jcall Map.put var(r) "b" 2:int)
  var(r)
```

```hir
;; hir -O1 fn=addM
fn addM (m:obj) -> obj {pure unique}
  (def r (core-new map$reuse from=(move var(m))))
  (jcall Map.put var(r) "b" 2:int)
  var(r)
```

```mir
;; mir -O2 fn=addM stage=before,final
fn addM(v0: any [unique]) -> any:
  bb0:
    v1: any = move v0
    v2: any = opaque.CoreCollectionCall(v1) {impure}
    v3: any = const "b"
    v4: int = const 2
    v5: any = call_java v2.put(v3, v4) {impure}
    ret v2
```

## FBIP list/set reuse falls back cleanly on non-ArrayList/LinkedHashSet inputs

```babble
def copyL = fn (unique xs: java.lang.Iterable of int): list of int -> ( def r = list(from: move(xs)) ; r.add(4) ; r )
def copyS = fn (unique s: java.lang.Iterable of int): set of int -> ( def r = set(from: move(s)) ; r.add(4) ; r )
def run = fn (): bool -> ( copyL(sortedset(1, 2)).size() == 3 and copyS(sortedset(1, 2)).size() == 3 )
assert(run())
```

```hir
;; hir -O0 fn=copyL
fn copyL (xs:obj) -> obj {pure unique}
  (def r (core-new list from=(move var(xs))))
  (jcall List.add var(r) 4:int)
  var(r)
```

```hir
;; hir -O1 fn=copyL
fn copyL (xs:obj) -> obj {pure unique}
  (def r (core-new list$reuse from=(move var(xs))))
  (jcall List.add var(r) 4:int)
  var(r)
```

```mir
;; mir -O2 fn=copyL stage=before,final
fn copyL(v0: any [unique]) -> any:
  bb0:
    v1: any = move v0
    v2: any = opaque.CoreCollectionCall(v1) {impure}
    v3: int = const 4
    v4: bool = call_java v2.add(v3) {impure}
    ret v2
```

## FBIP mutable record in-place reuse vs aliased deeply immutable record safety

```babble
def bumpH = fn (unique h: Holder, f: bool): Holder -> (
    if f then ( Holder(h.items, h.count + 1) ) else ( Holder(h.items, h.count + 2) )
)
def bumpV = fn (unique v: Vec3): Vec3 -> ( Vec3(v.x + 1.0, v.y, v.z) )
def run = fn (): bool -> (
    def h = bumpH(Holder(list(1), 5), true)
    def v0 = Vec3(1.0, 2.0, 3.0)
    h.count == 6 and bumpV(v0).x == 2.0 and v0.x == 1.0
)
assert(run())
```

```hir
;; hir -O0 fn=bumpH
fn bumpH (h:obj, f:bool) -> obj {nothrow}
  (if var(f) (block (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int))) (block (record-new Holder (get-this-field items) (+:int (get-this-field count) 2:int))))
```

```hir
;; hir -O1 fn=bumpH
fn bumpH (h:obj, f:bool) -> obj {nothrow}
  (if var(f) (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int)) (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 2:int)))
```

```hir
;; hir -O1 fn=bumpV
fn bumpV (v:obj) -> obj {nothrow unique}
  (record-new Vec3 (+:double (get-this-field x) 1.0:double) (get-this-field y) (get-this-field z))
```

```mir
;; mir -O2 fn=bumpV stage=before,final
fn bumpV(v0: any [unique]) -> any:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: double = load_field v0.x {read:frozen}
    v3: double = const 1.0
    v4: double = add v2, v3 {pure}
    v5: double = load_field v0.y {read:frozen}
    v6: double = load_field v0.z {read:frozen}
    v7: any = record_new Vec3(v4, v5, v6) {pure}
    ret v7
```

## FBIP record reuse via explicit return and block tail

```babble
def bumpRet = fn (unique h: Holder): Holder -> ( return Holder(h.items, h.count + 1) )
def bumpBlk = fn (unique h: Holder): Holder -> ( def n = h.count + 2 ; Holder(h.items, n) )
assert(bumpRet(Holder(list(1), 1)).count == 2 and bumpBlk(Holder(list(1), 1)).count == 3)
```

```hir
;; hir -O0 fn=bumpRet
fn bumpRet (h:obj) -> obj {nothrow}
  (return (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int)))
```

```hir
;; hir -O1 fn=bumpRet
fn bumpRet (h:obj) -> obj {nothrow}
  (return (record-reuse var(h) Holder (get-this-field items) (+:int (get-this-field count) 1:int)))
```

```hir
;; hir -O0 fn=bumpBlk
fn bumpBlk (h:obj) -> obj {nothrow}
  (def n (+:int (get-this-field count) 2:int))
  (record-new Holder (get-this-field items) var(n))
```

```hir
;; hir -O1 fn=bumpBlk
fn bumpBlk (h:obj) -> obj {nothrow}
  (def n (+:int (get-this-field count) 2:int))
  (record-reuse var(h) Holder (get-this-field items) var(n))
```

```mir
;; mir -O2 fn=bumpBlk stage=before,final
fn bumpBlk(v0: any [unique]) -> any:
  bb0:
    v1: int = load_field v0.count {read:frozen}
    v2: int = const 2
    v3: int = add v1, v2 {pure}
    v4: any = load_field v0.items {read:frozen}
    v5: any = record_reuse v0 as Holder(v4, v3) {write:unique#0}
    ret v5
```

## FBIP record reuse disqualifier when unique parameter escapes before tail construction

```babble
def esc = fn (unique h: Holder): Holder -> ( def xs = list(h) ; Holder(xs.get(0).items, h.count + 1) )
assert(esc(Holder(list(1), 9)).count == 10)
```

```hir
;; hir -O0,-O1 fn=esc
fn esc (h:obj) -> obj {pure}
  (def xs (core-new list var(h)))
  (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int))
```

```mir
;; mir -O2 fn=esc stage=before,final
fn esc(v0: any [unique]) -> any:
  bb0:
    v1: any = opaque.CoreCollectionCall(v0) {impure}
    v2: any = opaque.NameRead() {impure}
    v3: int = const 0
    v4: any = call_java v1.get(v3) {impure}
    v5: any = checkcast v4 <babble|Holder>
    v6: any = load_field v5.items {read:frozen,throws}
    v7: int = load_field v0.count {read:frozen}
    v8: int = const 1
    v9: int = add v7, v8 {pure}
    v10: any = record_new Holder(v6, v9) {pure}
    ret v10
```

## FBIP reuse disqualifiers : reassigned unique param and reordered named args

```babble
def f1 = fn (unique h: Holder): Holder -> ( h = Holder(h.items, h.count + 1) ; Holder(h.items, h.count) )
def f2 = fn (unique h: Holder): Holder -> ( return Holder(count: h.count + 1, items: h.items) )
assert(f1(Holder(list(1), 1)).count == 2 and f2(Holder(list(1), 2)).count == 3)
```

```hir
;; hir -O0,-O1 fn=f1
fn f1 (h:obj) -> obj {nothrow}
  (set h (record-new Holder (get-this-field items) (+:int (get-this-field count) 1:int)))
  (record-new Holder (get-this-field items) (get-this-field count))
```

```hir
;; hir -O0,-O1 fn=f2
fn f2 (h:obj) -> obj {nothrow}
  (return (record-new Holder (+:int (get-this-field count) 1:int) (get-this-field items)))
```

```mir
;; mir -O2 fn=f2 stage=before,final
fn f2(v0: any [unique]) -> any:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: int = load_field v0.count {read:frozen}
    v3: int = const 1
    v4: int = add v2, v3 {pure}
    v5: any = load_field v0.items {read:frozen}
    v6: any = record_new Holder(v4, v5) {pure}
    ret v6
```

## FBIP reuse disqualifier : non-reuse branches and mismatched record type

```babble
def O = record (xs: list of int, n: int) ()
def f1 = fn (unique h: Holder, fl: bool): int -> ( if fl then ( h.count ) else ( 0 ) )
def f2 = fn (unique h: Holder): O -> ( O(h.items, h.count + 1) )
assert(f1(Holder(list(1), 5), true) == 5 and f2(Holder(list(1), 4)).n == 5)
```

```hir
;; hir -O0,-O1 fn=f2
fn f2 (h:obj) -> obj {nothrow}
  (record-new O (get-this-field items) (+:int (get-this-field count) 1:int))
```

```mir
;; mir -O2 fn=f2 stage=before,final
fn f2(v0: any [unique]) -> any:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: any = load_field v0.items {read:frozen}
    v3: int = load_field v0.count {read:frozen}
    v4: int = const 1
    v5: int = add v3, v4 {pure}
    v6: any = record_new O(v2, v5) {pure}
    ret v6
```

## FBIP reuse disqualifier : cross-module unique record parameter

```babble
def m = import("vec_mod.ba")
def f = fn (unique v: m.ExtVec): m.ExtVec -> ( m.ExtVec(v.x + 1.0, v.y) )
assert(f(m.ExtVec(2.0, 3.0)).x == 3.0)
```

```hir
;; hir -O0,-O1 fn=f
fn f (v:obj) -> obj {nothrow unique}
  (record-new vec_mod$ba$C0$ExtVec (+:double (get-this-field x) 1.0:double) (get-this-field y))
```

```mir
;; mir -O2 fn=f stage=before,final
fn f(v0: any [unique]) -> any:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: any = opaque.ModuleExportGet(v1) {impure}
    v3: double = load_field v0.x {read:frozen}
    v4: double = const 1.0
    v5: double = add v3, v4 {pure}
    v6: double = load_field v0.y {read:frozen}
    v7: any = record_new vec_mod$ba$C0$ExtVec(v5, v6) {pure}
    ret v7
```
