# Unified HIR SCCP and Dead Code Elimination (BIP039)

Tests `HirSccpPass` (sparse conditional constant propagation across structured joins and foldable
JDK calls) and `HirDcePass` (backward-liveness dead statement and dead loop-carried variable
elimination) with explicit `-O2` HIR snapshots.

## SCCP propagates constants across diamond join and folds constant BrIf

```babble
def foldBranch = fn (flag: bool): int -> (
    def k = if flag then (4) else (4)
    def d = if k == 4 then (k * 10 + 2) else (999)
    d
)
assert(foldBranch(true) == 42 and foldBranch(false) == 42)
```

```hir
;; hir -O0 fn=foldBranch
fn foldBranch (flag:bool) -> int {nothrow unique}
  (def k (if var(flag) (block 4:int) (block 4:int)))
  (def d (if (==:bool var(k) 4:int) (block (+:int (*:int var(k) 10:int) 2:int)) (block 999:int)))
  var(d)
```

```hir
;; hir -O1 fn=foldBranch
fn foldBranch (flag:bool) -> int {nothrow unique}
  (def k (if var(flag) 4:int 4:int))
  (def d (if (==:bool var(k) 4:int) (+:int (*:int var(k) 10:int) 2:int) 999:int))
  var(d)
```

```hir
;; hir -O2 fn=foldBranch
fn foldBranch (flag:bool) -> int {nothrow unique}
  42:int
```

## SCCP folds foldable static JDK calls and primitive conversions

```babble
def foldMath = fn (flag: bool): double -> (
    def a = if flag then (-9) else (-9)
    def b = java.lang.Math.abs(a)
    def s = "ab" + "cd"
    (b as double) + s.length()
)
assert(foldMath(true) == 13.0 and foldMath(false) == 13.0)
```

```hir
;; hir -O0 fn=foldMath
fn foldMath (flag:bool) -> double {pure unique}
  (def a (if var(flag) (block -9:int) (block -9:int)))
  (def b (jcall Math.abs var(a)))
  (def s (+:obj "ab" "cd"))
  (+:double (block (as var(b) double|double|)) (convert[double] (jcall String.length var(s))))
```

```hir
;; hir -O1 fn=foldMath
fn foldMath (flag:bool) -> double {pure unique}
  (def a (if var(flag) -9:int -9:int))
  (def b (jcall Math.abs var(a)))
  (+:double (as var(b) double|double|) 4.0:double)
```

```hir
;; hir -O2 fn=foldMath
fn foldMath (flag:bool) -> double {pure unique}
  (+:double (as 9:int double|double|) 4.0:double)
```

## SCCP preserves IEEE-754 signed zero and NaN semantics

```babble
def ieee = fn (neg: bool): double -> (
    def z = if neg then (-0.0) else (0.0)
    1.0 / z
)
assert(ieee(true) < 0.0 and ieee(false) > 0.0)
```

```hir
;; hir -O0 fn=ieee
fn ieee (neg:bool) -> double {nothrow unique}
  (def z (if var(neg) (block -0.0:double) (block 0.0:double)))
  (/:double 1.0:double var(z))
```

```hir
;; hir -O1 fn=ieee
fn ieee (neg:bool) -> double {nothrow unique}
  (def z (if var(neg) -0.0:double 0.0:double))
  (/:double 1.0:double var(z))
```

```hir
;; hir -O2 fn=ieee
fn ieee (neg:bool) -> double {nothrow unique}
  (def z (if var(neg) -0.0:double 0.0:double))
  (/:double 1.0:double var(z))
```

## MirDce eliminates dead pure computations and dead loop-carried block parameters

```babble
def deadLoopParam = fn (n: int): int -> (
    var live = 0
    var dead = 1
    var i = 0
    while i < n then (
        live += i
        dead = dead * 3 + 1
        i += 1
    )
    live
)
assert(deadLoopParam(5) == 10)
```

```hir
;; hir -O0 fn=deadLoopParam
fn deadLoopParam (n:int) -> int {nothrow unique}
  (def live 0:int)
  (def dead 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set live += var(i))
      (set dead (+:int (*:int var(dead) 3:int) 1:int))
      (set i += 1:int)))
  var(live)
```

```hir
;; hir -O1 fn=deadLoopParam
fn deadLoopParam (n:int) -> int {nothrow unique}
  (def live 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set live += var(i))
      (set i += 1:int)))
  var(live)
```

```hir
;; hir -O2 fn=deadLoopParam
fn deadLoopParam (n:int) -> int {nothrow unique}
  (def live 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set live += var(i))
      (set i += 1:int)))
  var(live)
```

## SCCP folds unary negation and arithmetic across long, float, and double

```babble
def sccpNum = fn (p: bool): double -> (
    def i = if p then (6) else (6)
    def l = if p then (10L) else (10L)
    def f = if p then (4.0f) else (4.0f)
    def d = if p then (8.0) else (8.0)
    def a = (-i) + (i - 2)
    def b = (-l) + (l + 2L - 1L) * 2L
    def c = (-f) + (f + 2.0f - 1.0f) * 2.0f / 2.0f
    def e = (-d) + (d - 2.0) / 2.0
    a + b + c + e
)
assert(sccpNum(true) == 6.0)
```

```hir
;; hir -O0 fn=sccpNum
fn sccpNum (p:bool) -> double {nothrow unique}
  (def i (if var(p) (block 6:int) (block 6:int)))
  (def l (if var(p) (block 10:long) (block 10:long)))
  (def f (if var(p) (block 4.0:float) (block 4.0:float)))
  (def d (if var(p) (block 8.0:double) (block 8.0:double)))
  (def a (+:int (block (neg var(i))) (block (-:int var(i) 2:int))))
  (def b (+:long (block (neg var(l))) (*:long (block (-:long (+:long var(l) 2:long) 1:long)) 2:long)))
  (def c (+:float (block (neg var(f))) (/:float (*:float (block (-:float (+:float var(f) 2.0:float) 1.0:float)) 2.0:float) 2.0:float)))
  (def e (+:double (block (neg var(d))) (/:double (block (-:double var(d) 2.0:double)) 2.0:double)))
  (+:double (convert[double] (+:float (convert[float] (+:long (convert[long] var(a)) var(b))) var(c))) var(e))
```

```hir
;; hir -O1 fn=sccpNum
fn sccpNum (p:bool) -> double {nothrow unique}
  (def i (if var(p) 6:int 6:int))
  (def l (if var(p) 10:long 10:long))
  (def f (if var(p) 4.0:float 4.0:float))
  (def d (if var(p) 8.0:double 8.0:double))
  (def a (+:int (neg var(i)) (-:int var(i) 2:int)))
  (def b (+:long (neg var(l)) (*:long (-:long (+:long var(l) 2:long) 1:long) 2:long)))
  (def c (+:float (neg var(f)) (/:float (*:float (-:float (+:float var(f) 2.0:float) 1.0:float) 2.0:float) 2.0:float)))
  (def e (+:double (neg var(d)) (/:double (-:double var(d) 2.0:double) 2.0:double)))
  (+:double (convert[double] (+:float (convert[float] (+:long (convert[long] var(a)) var(b))) var(c))) var(e))
```

```hir
;; hir -O2 fn=sccpNum
fn sccpNum (p:bool) -> double {nothrow unique}
  6.0:double
```

## SCCP folds bool, char, and null comparisons

```babble
def sccpBoolChar = fn (p: bool): int -> (
    def b = if p then (true) else (true)
    def c = if p then ('b') else ('b')
    def s: string? = if p then (null) else (null)
    var r = 0
    if (b == true) and (b <> false) then (r += 1)
    if (c >= 'a') and (c <= 'c') and (c <> 'z') then (r += 2)
    if s == null then (r += 4)
    r
)
assert(sccpBoolChar(true) == 7)
```

```hir
;; hir -O0 fn=sccpBoolChar
fn sccpBoolChar (p:bool) -> int {nothrow unique}
  (def b (if var(p) (block true:bool) (block true:bool)))
  (def c (if var(p) (block 'b') (block 'b')))
  (def s (if var(p) (block null) (block null)))
  (def r 0:int)
  (if (and (block (==:bool var(b) true:bool)) (block (<>:bool var(b) false:bool))) (box (block (set r += 1:int))))
  (if (and (and (block (>=:bool var(c) 'a')) (block (<=:bool var(c) 'c'))) (block (<>:bool var(c) 'z'))) (box (block (set r += 2:int))))
  (if (==:bool var(s) null) (box (block (set r += 4:int))))
  var(r)
```

```hir
;; hir -O1 fn=sccpBoolChar
fn sccpBoolChar (p:bool) -> int {nothrow unique}
  (def b (if var(p) true:bool true:bool))
  (def c (if var(p) 'b' 'b'))
  (def s (if var(p) null null))
  (def r 0:int)
  (if (and var(b) var(b)) (box (set r += 1:int)))
  (if (and (and (>=:bool var(c) 'a') (<=:bool var(c) 'c')) (<>:bool var(c) 'z')) (box (set r += 2:int)))
  (if (==:bool var(s) null) (box (set r += 4:int)))
  var(r)
```

```hir
;; hir -O2 fn=sccpBoolChar
fn sccpBoolChar (p:bool) -> int {nothrow unique}
  (box (set r 1:int))
  (box (set r 3:int))
  (if (==:bool null null) (box (set r 7:int)))
  var(r)
```

## SCCP folds long, float, and double relational comparisons

```babble
def sccpRel = fn (p: bool): int -> (
    def l = if p then (5L) else (5L)
    def f = if p then (2.5f) else (2.5f)
    def d = if p then (3.5) else (3.5)
    var r = 0
    if (l <= 5L) and (l <> 6L) then (r += 1)
    if (f >= 2.5f) and (d > 3.0) then (r += 2)
    r
)
assert(sccpRel(true) == 3)
```

```hir
;; hir -O0 fn=sccpRel
fn sccpRel (p:bool) -> int {nothrow unique}
  (def l (if var(p) (block 5:long) (block 5:long)))
  (def f (if var(p) (block 2.5:float) (block 2.5:float)))
  (def d (if var(p) (block 3.5:double) (block 3.5:double)))
  (def r 0:int)
  (if (and (block (<=:bool var(l) 5:long)) (block (<>:bool var(l) 6:long))) (box (block (set r += 1:int))))
  (if (and (block (>=:bool var(f) 2.5:float)) (block (>:bool var(d) 3.0:double))) (box (block (set r += 2:int))))
  var(r)
```

```hir
;; hir -O1 fn=sccpRel
fn sccpRel (p:bool) -> int {nothrow unique}
  (def l (if var(p) 5:long 5:long))
  (def f (if var(p) 2.5:float 2.5:float))
  (def d (if var(p) 3.5:double 3.5:double))
  (def r 0:int)
  (if (and (<=:bool var(l) 5:long) (<>:bool var(l) 6:long)) (box (set r += 1:int)))
  (if (and (>=:bool var(f) 2.5:float) (>:bool var(d) 3.0:double)) (box (set r += 2:int)))
  var(r)
```

```hir
;; hir -O2 fn=sccpRel
fn sccpRel (p:bool) -> int {nothrow unique}
  (box (set r 1:int))
  (box (set r 3:int))
  3:int
```

## SCCP folds constant truthiness and long/float/char JDK calls

```babble
def sccpJdk = fn (p: bool): double -> (
  def l = if p then (-7L) else (-7L)
  def f = if p then (-2.5f) else (-2.5f)
  def c = if p then ('a') else ('a')
  def s = if p then ("hi") else ("hi")
  def k = (if l then (1) else (0)) + (if s then (2) else (0)) + (if c then (1) else (0))
  java.lang.Math.abs(l) + java.lang.Math.abs(f) + k
)
assert(sccpJdk(true) == 13.5)
```

```hir
;; hir -O0 fn=sccpJdk
fn sccpJdk (p:bool) -> double {nothrow unique}
  (def l (if var(p) (block -7:long) (block -7:long)))
  (def f (if var(p) (block -2.5:float) (block -2.5:float)))
  (def c (if var(p) (block 'a') (block 'a')))
  (def s (if var(p) (block "hi") (block "hi")))
  (def k (+:int (+:int (block (if var(l) (block 1:int) (block 0:int))) (block (if var(s) (block 2:int) (block 0:int)))) (block (if var(c) (block 1:int) (block 0:int)))))
  (widen[double] (+:float (+:float (convert[float] (jcall Math.abs var(l))) (jcall Math.abs var(f))) (convert[float] var(k))))
```

```hir
;; hir -O1 fn=sccpJdk
fn sccpJdk (p:bool) -> double {nothrow unique}
  (def l (if var(p) -7:long -7:long))
  (def f (if var(p) -2.5:float -2.5:float))
  (def c (if var(p) 'a' 'a'))
  (def s (if var(p) "hi" "hi"))
  (def k (+:int (+:int (if var(l) 1:int 0:int) (if var(s) 2:int 0:int)) (if var(c) 1:int 0:int)))
  (widen[double] (+:float (+:float (convert[float] (jcall Math.abs var(l))) (jcall Math.abs var(f))) (convert[float] var(k))))
```

```hir
;; hir -O2 fn=sccpJdk
fn sccpJdk (p:bool) -> double {nothrow unique}
  13.5:double
```
