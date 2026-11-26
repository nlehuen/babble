# Cross-module calls : in-module vs imported functions and static methods

References : BIP005, BIP006, BIP014 ; targets `HirBuilder`, `InlinePass`, `MirLowerer`, `CallEmitter`

Each pair calls the same function twice over : defined in the case's own module, and imported
from `geo.ba`. Both in-module and non-cyclic cross-module calls lower to `DirectBabbleCall` at
`-O0`, inline small pure exports at `-O1`/`-O2`, and lower non-inlineable pure cross-module calls
to first-class `export_get` + `call` in `MIR` (eligible for GVN/CSE and LICM hoisting).

```babble
;; babble module=geo.ba
def helperSq = fn (x : double) : double -> ( x * x )
export def sq = fn (x : double) : double -> ( x * x )
export def sqAlias = helperSq
export def bigSq = fn (x : double) : double -> ( if x < 0.0 then ( return x * x ) ; x * x )
export def P = record (x : double, y : double) () static (
    def make = fn (k : double) : P -> ( this(k, k) )
)
```

```babble
;; babble module=facade.ba
def geo = import("geo.ba")
export def sq = geo.sq
export def bigSq = geo.bigSq
```

```babble
;; babble module=dyn_mod.ba
def pick = fn () : string -> ( "geo.ba" )
def unused = fn io () -> ( import(pick()) )
export def bigSq = fn (x : double) : double -> ( if x < 0.0 then ( return x * x ) ; x * x )
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
  (+:double (call-fn geo.ba#sq var(a)) 1.0:double)
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
      (set s += (call-fn geo.ba#sq var(a)))
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

## Non-inlineable cross-module call (direct INVOKESTATIC, pure export_get and CSE)

```babble
def geo = import("geo.ba")
def f = fn (a : double) : double -> ( geo.bigSq(a) + geo.bigSq(a) )
assert(f(3.0) == 18.0)
```

```hir
;; hir -O0,-O1 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (call-fn geo.ba#bigSq var(a)) (call-fn geo.ba#bigSq var(a)))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = export_get v1.bigSq {pure}
    v3: double = call geo.ba#bigSq(v0) {pure}
    v4: any = name_read geo {pure}
    v5: any = export_get v4.bigSq {pure}
    v6: double = call geo.ba#bigSq(v0) {pure}
    v7: double = add v3, v6 {pure}
    ret v7
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read geo {pure}
    v2: any = export_get v1.bigSq {pure}
    v3: double = call geo.ba#bigSq(v0) {pure}
    v4: double = add v3, v3 {pure}
    ret v4
```

## Non-inlineable cross-module call in a loop (LICM hoisting)

```babble
def geo = import("geo.ba")
def f = fn (a : double, n : int) : double -> (
    var s = 0.0
    var i = 0
    while i < n then (
        s += geo.bigSq(a)
        i += 1
    )
    s
)
assert(f(3.0, 4) == 36.0)
```

```hir
;; hir -O0 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (call-fn geo.ba#bigSq var(a)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (call-fn geo.ba#bigSq var(a)))
    (while (<:bool var(i) var(n))
      (block
        (set s += var($licm0))
        (set i += 1:int))))
  var(s)
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = name_read geo {pure}
    v5: any = export_get v4.bigSq {pure}
    v6: double = call geo.ba#bigSq(v0) {pure}
    v7: any = const null
    br bb1(v7, v3, v2)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v1 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: double = add v10, v6 {pure}
    v13: int = const 1
    v14: int = add v9, v13 {pure}
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
    v5: any = export_get v4.bigSq {pure}
    v6: double = call geo.ba#bigSq(v0) {pure}
    br bb1(v3, v2)
  bb1(v7: int, v8: double):
    v9: bool = lt v7, v1 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: double = add v8, v6 {pure}
    v11: int = const 1
    v12: int = add v7, v11 {pure}
    br bb1(v12, v10)
```

## Cross-module alias and transitive re-export

```babble
def geo = import("geo.ba")
def fac = import("facade.ba")
def f = fn (a : double) : double -> ( geo.sqAlias(a) + fac.sq(a) + fac.bigSq(a) )
assert(f(3.0) == 27.0)
```

```hir
;; hir -O0 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (+:double (call-fn geo.ba#sqAlias var(a)) (call-fn geo.ba#sq var(a))) (call-fn geo.ba#bigSq var(a)))
```

```hir
;; hir -O1 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (+:double (*:double var(a) var(a)) (*:double var(a) var(a))) (call-fn geo.ba#bigSq var(a)))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: double = mul v0, v0 {pure}
    v2: double = mul v0, v0 {pure}
    v3: double = add v1, v2 {pure}
    v4: any = name_read fac {pure}
    v5: any = export_get v4.bigSq {pure}
    v6: double = call geo.ba#bigSq(v0) {pure}
    v7: double = add v3, v6 {pure}
    ret v7
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: double = mul v0, v0 {pure}
    v2: double = add v1, v1 {pure}
    v3: any = name_read fac {pure}
    v4: any = export_get v3.bigSq {pure}
    v5: double = call geo.ba#bigSq(v0) {pure}
    v6: double = add v2, v5 {pure}
    ret v6
```

## Dynamic-import module keeps export_get conservatively throwing

```babble
def dyn = import("dyn_mod.ba")
def f = fn (a : double) : double -> ( dyn.bigSq(a) )
assert(f(3.0) == 9.0)
```

```hir
;; hir -O0,-O1 fn=f
fn f (a:double) -> double {nothrow unique}
  (call-fn dyn_mod.ba#bigSq var(a))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read dyn {pure}
    v2: any = export_get v1.bigSq {read:shared,throws}
    v3: double = call dyn_mod.ba#bigSq(v0) {pure}
    ret v3
```

```mir
;; mir -O2 fn=f stage=final
fn f(v0: double) -> double:
  bb0:
    v1: any = name_read dyn {pure}
    v2: any = export_get v1.bigSq {read:shared,throws}
    v3: double = call dyn_mod.ba#bigSq(v0) {pure}
    ret v3
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
    v2: any = export_get v1.P {pure}
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
    v2: any = export_get v1.P {pure}
    v3: any = opaque.DynamicSelect(v2.make) {impure}
    v4: any = opaque.DynamicCall(v3, v0) {impure}
    v5: double = load_field v4.x {read:frozen,throws}
    ret v5
```
