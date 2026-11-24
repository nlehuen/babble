# SSA MIR SCCP and Dead Code Elimination (BIP014-P3)

Tests `SccpPass` (sparse conditional constant propagation across CFG joins and foldable
JDK calls) and `MirDcePass` (dead instruction, dead block-parameter, and empty-trampoline
elimination) with explicit `-O2` before/after MIR snapshots.

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

```mir
;; mir -O2 fn=foldBranch stage=before
fn foldBranch(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = const 4
    br bb3(v1)
  bb2:
    v2: int = const 4
    br bb3(v2)
  bb3(v3: int):
    v4: int = const 4
    v5: bool = eq v3, v4 {pure}
    br_if v5, bb5, bb4
  bb4:
    v6: int = const 999
    br bb6(v6)
  bb5:
    v7: int = const 10
    v8: int = mul v3, v7 {pure}
    v9: int = const 2
    v10: int = add v8, v9 {pure}
    br bb6(v10)
  bb6(v11: int):
    ret v11
```

```mir
;; mir -O2 fn=foldBranch stage=after:SccpPass
fn foldBranch(v0: bool) -> int:
  bb0:
    br bb1
  bb1:
    v1: int = const 42
    br bb2(v1)
  bb2(v2: int):
    ret v2
```

```mir
;; mir -O2 fn=foldBranch
fn foldBranch(v0: bool) -> int:
  bb0:
    br bb1
  bb1:
    v1: int = const 42
    br bb2(v1)
  bb2(v2: int):
    ret v2
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
  (+:double (block (as var(b) double|double|)) (jcall String.length var(s)))
```

```hir
;; hir -O1 fn=foldMath
fn foldMath (flag:bool) -> double {pure unique}
  (def a (if var(flag) -9:int -9:int))
  (def b (jcall Math.abs var(a)))
  (+:double (as var(b) double|double|) 4:int)
```

```mir
;; mir -O2 fn=foldMath stage=before
fn foldMath(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = const -9
    br bb3(v1)
  bb2:
    v2: int = const -9
    br bb3(v2)
  bb3(v3: int):
    v4: int = call_java Math.abs(v3) {pure}
    v5: any = convert.any v4
    v6: any = checkcast v5 <double|double|>
    v7: double = convert.double v6
    v8: int = const 4
    v9: double = convert.double v8
    v10: double = add v7, v9 {pure}
    ret v10
```

```mir
;; mir -O2 fn=foldMath
fn foldMath(v0: bool) -> double:
  bb0:
    br bb1
  bb1:
    v1: any = const 9
    v2: any = checkcast v1 <double|double|>
    v3: double = convert.double v2
    v4: double = const 4.0
    v5: double = add v3, v4 {pure}
    ret v5
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

```mir
;; mir -O2 fn=ieee stage=before,final
fn ieee(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: double = const 0.0
    br bb3(v1)
  bb2:
    v2: double = const -0.0
    br bb3(v2)
  bb3(v3: double):
    v4: double = const 1.0
    v5: double = div v4, v3 {pure}
    ret v5
```

## MirDce eliminates dead pure computations and dead loop-carried block parameters

```babble
def deadLoopParam = fn (n: int): int -> (
    def live = 0
    def dead = 1
    def i = 0
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
  (def dead 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set live += var(i))
      (set dead (+:int (*:int var(dead) 3:int) 1:int))
      (set i += 1:int)))
  var(live)
```

```mir
;; mir -O2 fn=deadLoopParam stage=before
fn deadLoopParam(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v1, v2)
  bb1(v5: any, v6: int, v7: int, v8: int):
    v9: bool = lt v6, v0 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: int = add v7, v6 {pure}
    v11: int = const 3
    v12: int = mul v8, v11 {pure}
    v13: int = const 1
    v14: int = add v12, v13 {pure}
    v15: int = const 1
    v16: int = add v6, v15 {pure}
    v17: any = convert.any v16
    br bb1(v17, v16, v10, v14)
```

```mir
;; mir -O2 fn=deadLoopParam
fn deadLoopParam(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    br bb1(v1, v1)
  bb1(v3: int, v4: int):
    v5: bool = lt v3, v0 {pure}
    br_if v5, bb3, bb2
  bb2:
    ret v4
  bb3:
    v6: int = add v4, v3 {pure}
    v7: int = add v3, v2 {pure}
    br bb1(v7, v6)
```
