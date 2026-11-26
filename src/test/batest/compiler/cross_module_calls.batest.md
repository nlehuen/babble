# Cross-module calls : in-module vs imported functions and static methods

References : BIP005, BIP006, BIP014 ; targets `HirBuilder`, `MirLowerer`, `CallEmitter`

Each pair calls the same function twice over : defined in the case's own module, and imported
from `geo.ba`. An in-module call resolves statically (`DirectBabbleCall`, inlined at `-O1`, pure in
`MIR`) ; the cross-module one stays a dynamic call through the module's export.

```babble
;; babble module=geo.ba
export def sq = fn (x : double) : double -> ( x * x )
export def P = record (x : double, y : double) () static (
    def make = fn (k : double) : P -> ( this(k, k) )
)
```

## In-module function call

```babble
def sq = fn (x : double) : double -> ( x * x )
def f = fn (a : double) : double -> ( sq(a) + 1.0 )
println(f(3.0))
```

```stdout
10.0
```

```hir
;; hir -O0 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (call-fn sq var(a)) 1.0:double)
```

```hir
;; hir -O1 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (*:double var(a) var(a)) 1.0:double)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: double = mul v0, v0 {pure}
    v2: double = const 1.0
    v3: double = add v1, v2 {pure}
    ret v3
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: double = mul v0, v0 {pure}
    v2: double = const 1.0
    v3: double = add v1, v2 {pure}
    ret v3
```

## Cross-module function call

```babble
;; QUESTIONABLE : geo.sq is a pure function of a static import, yet the call stays an
;; impure opaque.DynamicCall through an impure opaque.ModuleExportGet, never inlined
def geo = import("geo.ba")
def f = fn (a : double) : double -> ( geo.sq(a) + 1.0 )
println(f(3.0))
```

```stdout
10.0
```

```hir
;; hir -O0 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (call (export name(geo).sq) var(a)) 1.0:double)
```

```hir
;; hir -O1 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (call (export name(geo).sq) var(a)) 1.0:double)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = opaque.ModuleExportGet(v1.sq) {impure}
    v3: double = opaque.DynamicCall(v2, v0) {impure}
    v4: double = const 1.0
    v5: double = add v3, v4 {pure}
    ret v5
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = opaque.ModuleExportGet(v1.sq) {impure}
    v3: double = opaque.DynamicCall(v2, v0) {impure}
    v4: double = const 1.0
    v5: double = add v3, v4 {pure}
    ret v5
```

## In-module call in a loop

```babble
def sq = fn (x : double) : double -> ( x * x )
def f = fn (a : double, n : int) : double -> (
    var s = 0.0
    var i = 0
    while i < n then (
        s += sq(a)
        i += 1
    )
    s
)
println(f(3.0, 4))
```

```stdout
36.0
```

```hir
;; hir -O0 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (call-fn sq var(a)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (*:double var(a) var(a)))
      (set i += 1:int)))
  var(s)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: double):
    v8: bool = lt v6, v1 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: double = mul v0, v0 {pure}
    v10: double = add v7, v9 {pure}
    v11: int = const 1
    v12: int = add v6, v11 {pure}
    v13: any = convert.any v12
    br bb1(v13, v12, v10)
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: double = mul v0, v0 {pure}
    br bb1(v3, v2)
  bb1(v5: int, v6: double):
    v7: bool = lt v5, v1 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: double = add v6, v4 {pure}
    v9: int = const 1
    v10: int = add v5, v9 {pure}
    br bb1(v10, v8)
```

## Cross-module call in a loop

```babble
;; QUESTIONABLE : only name_read geo leaves the loop ; the in-module twin hoists sq(a)
def geo = import("geo.ba")
def f = fn (a : double, n : int) : double -> (
    var s = 0.0
    var i = 0
    while i < n then (
        s += geo.sq(a)
        i += 1
    )
    s
)
println(f(3.0, 4))
```

```stdout
36.0
```

```hir
;; hir -O0 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (call (export name(geo).sq) var(a)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (call (export name(geo).sq) var(a)))
      (set i += 1:int)))
  var(s)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: double):
    v8: bool = lt v6, v1 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: any = name_read geo {pure}
    v10: any = opaque.ModuleExportGet(v9.sq) {impure}
    v11: double = opaque.DynamicCall(v10, v0) {impure}
    v12: double = add v7, v11 {pure}
    v13: int = const 1
    v14: int = add v6, v13 {pure}
    v15: any = convert.any v14
    br bb1(v15, v14, v12)
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = name_read geo {pure}
    br bb1(v3, v2)
  bb1(v5: int, v6: double):
    v7: bool = lt v5, v1 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = opaque.ModuleExportGet(v4.sq) {impure}
    v9: double = opaque.DynamicCall(v8, v0) {impure}
    v10: double = add v6, v9 {pure}
    v11: int = const 1
    v12: int = add v5, v11 {pure}
    br bb1(v12, v10)
```

## In-module static method

```babble
;; QUESTIONABLE : a static method of a local record is not resolved statically either
;; (opaque.DynamicSelect + opaque.DynamicCall), and the canonical HIR prints
;; (get-this-field x) without its receiver P.make(k)
def P = record (x : double, y : double) () static (
    def make = fn (k : double) : P -> ( this(k, k) )
)
def f = fn (k : double) : double -> ( P.make(k).x )
println(f(2.0))
```

```stdout
2.0
```

```hir
;; hir -O0 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
```

```hir
;; hir -O1 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read P {pure}
    v2: any = opaque.DynamicSelect(v1.make) {impure}
    v3: any = opaque.DynamicCall(v2, v0) {impure}
    v4: double = load_field v3.x {read:frozen,throws}
    ret v4
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read P {pure}
    v2: any = opaque.DynamicSelect(v1.make) {impure}
    v3: any = opaque.DynamicCall(v2, v0) {impure}
    v4: double = load_field v3.x {read:frozen,throws}
    ret v4
```

## Cross-module static method

```babble
def geo = import("geo.ba")
def f = fn (k : double) : double -> ( geo.P.make(k).x )
println(f(2.0))
```

```stdout
2.0
```

```hir
;; hir -O0 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
```

```hir
;; hir -O1 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = opaque.ModuleExportGet(v1.P) {impure}
    v3: any = opaque.DynamicSelect(v2.make) {impure}
    v4: any = opaque.DynamicCall(v3, v0) {impure}
    v5: double = load_field v4.x {read:frozen,throws}
    ret v5
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = opaque.ModuleExportGet(v1.P) {impure}
    v3: any = opaque.DynamicSelect(v2.make) {impure}
    v4: any = opaque.DynamicCall(v3, v0) {impure}
    v5: double = load_field v4.x {read:frozen,throws}
    ret v5
```
