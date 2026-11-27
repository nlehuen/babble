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

```hir
;; hir -O2 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (*:double var(a) var(a)) 1.0:double)
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

```hir
;; hir -O2 fn=f
fn f (a:double) -> double {nothrow unique}
  (+:double (*:double var(a) var(a)) 1.0:double)
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

```hir
;; hir -O2 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (*:double var(a) var(a)))
      (set i += 1:int)))
  var(s)
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

```hir
;; hir -O2 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (*:double var(a) var(a)))
      (set i += 1:int)))
  var(s)
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

```hir
;; hir -O2 fn=f
fn f (a:double) -> double {nothrow unique}
  (block
    (def $cse0 (export name(geo).bigSq))
    (def $cse1 (call-fn geo.ba#bigSq var(a)))
    (+:double var($cse1) var($cse1)))
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
  (while (<:bool var(i) var(n))
    (block
      (set s += (call-fn geo.ba#bigSq var(a)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O2 fn=f
fn f (a:double, n:int) -> double {nothrow unique}
  (def s 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s += (call-fn geo.ba#bigSq var(a)))
      (set i += 1:int)))
  var(s)
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

```hir
;; hir -O2 fn=f
fn f (a:double) -> double {nothrow unique}
  (block
    (def $cse0 (*:double var(a) var(a)))
    (+:double (+:double var($cse0) var($cse0)) (call-fn geo.ba#bigSq var(a))))
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

```hir
;; hir -O2 fn=f
fn f (a:double) -> double {nothrow unique}
  (call-fn dyn_mod.ba#bigSq var(a))
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

```hir
;; hir -O2 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
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

```hir
;; hir -O2 fn=f
fn f (k:double) -> double {nothrow unique}
  (get-this-field x)
```
