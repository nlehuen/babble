# SSA MIR SCCP and Dead Code Elimination (BIP014-I3)

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
  (+:double (+:float (+:long var(a) var(b)) var(c)) var(e))
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
  (+:double (+:float (+:long var(a) var(b)) var(c)) var(e))
```

```mir
;; mir -O2 fn=sccpNum stage=before
fn sccpNum(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = const 6
    br bb3(v1)
  bb2:
    v2: int = const 6
    br bb3(v2)
  bb3(v3: int):
    br_if v0, bb5, bb4
  bb4:
    v4: long = const 10L
    br bb6(v4)
  bb5:
    v5: long = const 10L
    br bb6(v5)
  bb6(v6: long):
    br_if v0, bb8, bb7
  bb7:
    v7: float = const 4.0f
    br bb9(v7)
  bb8:
    v8: float = const 4.0f
    br bb9(v8)
  bb9(v9: float):
    br_if v0, bb11, bb10
  bb10:
    v10: double = const 8.0
    br bb12(v10)
  bb11:
    v11: double = const 8.0
    br bb12(v11)
  bb12(v12: double):
    v13: int = neg v3 {pure}
    v14: int = const 2
    v15: int = sub v3, v14 {pure}
    v16: int = add v13, v15 {pure}
    v17: long = neg v6 {pure}
    v18: long = const 2L
    v19: long = add v6, v18 {pure}
    v20: long = const 1L
    v21: long = sub v19, v20 {pure}
    v22: long = const 2L
    v23: long = mul v21, v22 {pure}
    v24: long = add v17, v23 {pure}
    v25: float = neg v9 {pure}
    v26: float = const 2.0f
    v27: float = add v9, v26 {pure}
    v28: float = const 1.0f
    v29: float = sub v27, v28 {pure}
    v30: float = const 2.0f
    v31: float = mul v29, v30 {pure}
    v32: float = const 2.0f
    v33: float = div v31, v32 {pure}
    v34: float = add v25, v33 {pure}
    v35: double = neg v12 {pure}
    v36: double = const 2.0
    v37: double = sub v12, v36 {pure}
    v38: double = const 2.0
    v39: double = div v37, v38 {pure}
    v40: double = add v35, v39 {pure}
    v41: long = convert.long v16
    v42: long = add v41, v24 {pure}
    v43: float = convert.float v42
    v44: float = add v43, v34 {pure}
    v45: double = convert.double v44
    v46: double = add v45, v40 {pure}
    ret v46
```

```mir
;; mir -O2 fn=sccpNum
fn sccpNum(v0: bool) -> double:
  bb0:
    br bb1
  bb1:
    v1: double = const 6.0
    ret v1
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
  (if (and (block (==:bool var(b) true:bool)) (block (<>:bool var(b) false:bool))) (block (set r += 1:int)))
  (if (and (and (block (>=:bool var(c) 'a')) (block (<=:bool var(c) 'c'))) (block (<>:bool var(c) 'z'))) (block (set r += 2:int)))
  (if (==:bool var(s) null) (block (set r += 4:int)))
  var(r)
```

```hir
;; hir -O1 fn=sccpBoolChar
fn sccpBoolChar (p:bool) -> int {nothrow unique}
  (def b (if var(p) true:bool true:bool))
  (def c (if var(p) 'b' 'b'))
  (def s (if var(p) null null))
  (def r 0:int)
  (if (and var(b) var(b)) (set r += 1:int))
  (if (and (and (>=:bool var(c) 'a') (<=:bool var(c) 'c')) (<>:bool var(c) 'z')) (set r += 2:int))
  (if (==:bool var(s) null) (set r += 4:int))
  var(r)
```

```mir
;; mir -O2 fn=sccpBoolChar stage=before
fn sccpBoolChar(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: bool = const true
    br bb3(v1)
  bb2:
    v2: bool = const true
    br bb3(v2)
  bb3(v3: bool):
    br_if v0, bb5, bb4
  bb4:
    v4: char = const 'b'
    br bb6(v4)
  bb5:
    v5: char = const 'b'
    br bb6(v5)
  bb6(v6: char):
    br_if v0, bb8, bb7
  bb7:
    v7: any = const null
    br bb9(v7)
  bb8:
    v8: any = const null
    br bb9(v8)
  bb9(v9: any):
    v10: int = const 0
    v11: bool = const false
    br_if v3, bb10, bb11(v11)
  bb10:
    br bb11(v3)
  bb11(v12: bool):
    br_if v12, bb13, bb12
  bb12:
    v13: any = const null
    br bb14(v13, v10)
  bb13:
    v14: int = const 1
    v15: int = add v10, v14 {pure}
    v16: any = convert.any v15
    br bb14(v16, v15)
  bb14(v17: any, v18: int):
    v19: char = const 'a'
    v20: bool = gte v6, v19 {pure}
    v21: bool = const false
    br_if v20, bb15, bb16(v21)
  bb15:
    v22: char = const 'c'
    v23: bool = lte v6, v22 {pure}
    br bb16(v23)
  bb16(v24: bool):
    v25: bool = const false
    br_if v24, bb17, bb18(v25)
  bb17:
    v26: char = const 'z'
    v27: bool = neq v6, v26 {pure}
    br bb18(v27)
  bb18(v28: bool):
    br_if v28, bb20, bb19
  bb19:
    v29: any = const null
    br bb21(v29, v18)
  bb20:
    v30: int = const 2
    v31: int = add v18, v30 {pure}
    v32: any = convert.any v31
    br bb21(v32, v31)
  bb21(v33: any, v34: int):
    v35: any = const null
    v36: any = eq v9, v35 {pure,throws}
    br_if v36, bb23, bb22
  bb22:
    v37: any = narrow.nonnull v9 <non-null>
    v38: any = const null
    br bb24(v38, v34)
  bb23:
    v39: int = const 4
    v40: int = add v34, v39 {pure}
    v41: any = convert.any v40
    br bb24(v41, v40)
  bb24(v42: any, v43: int):
    ret v43
```

```mir
;; mir -O2 fn=sccpBoolChar
fn sccpBoolChar(v0: bool) -> int:
  bb0:
    br bb1
  bb1:
    br_if v0, bb3, bb2
  bb2:
    v1: any = const null
    br bb4(v1)
  bb3:
    v2: any = const null
    br bb4(v2)
  bb4(v3: any):
    br bb5
  bb5:
    v4: int = const 3
    br bb6(v4)
  bb6(v5: int):
    v6: any = const null
    v7: any = eq v3, v6 {pure,throws}
    br_if v7, bb8, bb7
  bb7:
    br bb9(v5)
  bb8:
    v8: int = const 7
    br bb9(v8)
  bb9(v9: int):
    ret v9
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
  (if (and (block (<=:bool var(l) 5:long)) (block (<>:bool var(l) 6:long))) (block (set r += 1:int)))
  (if (and (block (>=:bool var(f) 2.5:float)) (block (>:bool var(d) 3.0:double))) (block (set r += 2:int)))
  var(r)
```

```hir
;; hir -O1 fn=sccpRel
fn sccpRel (p:bool) -> int {nothrow unique}
  (def l (if var(p) 5:long 5:long))
  (def f (if var(p) 2.5:float 2.5:float))
  (def d (if var(p) 3.5:double 3.5:double))
  (def r 0:int)
  (if (and (<=:bool var(l) 5:long) (<>:bool var(l) 6:long)) (set r += 1:int))
  (if (and (>=:bool var(f) 2.5:float) (>:bool var(d) 3.0:double)) (set r += 2:int))
  var(r)
```

```mir
;; mir -O2 fn=sccpRel stage=before
fn sccpRel(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: long = const 5L
    br bb3(v1)
  bb2:
    v2: long = const 5L
    br bb3(v2)
  bb3(v3: long):
    br_if v0, bb5, bb4
  bb4:
    v4: float = const 2.5f
    br bb6(v4)
  bb5:
    v5: float = const 2.5f
    br bb6(v5)
  bb6(v6: float):
    br_if v0, bb8, bb7
  bb7:
    v7: double = const 3.5
    br bb9(v7)
  bb8:
    v8: double = const 3.5
    br bb9(v8)
  bb9(v9: double):
    v10: int = const 0
    v11: long = const 5L
    v12: bool = lte v3, v11 {pure}
    v13: bool = const false
    br_if v12, bb10, bb11(v13)
  bb10:
    v14: long = const 6L
    v15: bool = neq v3, v14 {pure}
    br bb11(v15)
  bb11(v16: bool):
    br_if v16, bb13, bb12
  bb12:
    v17: any = const null
    br bb14(v17, v10)
  bb13:
    v18: int = const 1
    v19: int = add v10, v18 {pure}
    v20: any = convert.any v19
    br bb14(v20, v19)
  bb14(v21: any, v22: int):
    v23: float = const 2.5f
    v24: bool = gte v6, v23 {pure}
    v25: bool = const false
    br_if v24, bb15, bb16(v25)
  bb15:
    v26: double = const 3.0
    v27: bool = gt v9, v26 {pure}
    br bb16(v27)
  bb16(v28: bool):
    br_if v28, bb18, bb17
  bb17:
    v29: any = const null
    br bb19(v29, v22)
  bb18:
    v30: int = const 2
    v31: int = add v22, v30 {pure}
    v32: any = convert.any v31
    br bb19(v32, v31)
  bb19(v33: any, v34: int):
    ret v34
```

```mir
;; mir -O2 fn=sccpRel
fn sccpRel(v0: bool) -> int:
  bb0:
    br bb1
  bb1:
    v1: int = const 3
    br bb2(v1)
  bb2(v2: int):
    ret v2
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
  (+:float (+:float (jcall Math.abs var(l)) (jcall Math.abs var(f))) var(k))
```

```hir
;; hir -O1 fn=sccpJdk
fn sccpJdk (p:bool) -> double {nothrow unique}
  (def l (if var(p) -7:long -7:long))
  (def f (if var(p) -2.5:float -2.5:float))
  (def c (if var(p) 'a' 'a'))
  (def s (if var(p) "hi" "hi"))
  (def k (+:int (+:int (if var(l) 1:int 0:int) (if var(s) 2:int 0:int)) (if var(c) 1:int 0:int)))
  (+:float (+:float (jcall Math.abs var(l)) (jcall Math.abs var(f))) var(k))
```

```mir
;; mir -O2 fn=sccpJdk stage=before
fn sccpJdk(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: long = const -7L
    br bb3(v1)
  bb2:
    v2: long = const -7L
    br bb3(v2)
  bb3(v3: long):
    br_if v0, bb5, bb4
  bb4:
    v4: float = const -2.5f
    br bb6(v4)
  bb5:
    v5: float = const -2.5f
    br bb6(v5)
  bb6(v6: float):
    br_if v0, bb8, bb7
  bb7:
    v7: char = const 'a'
    br bb9(v7)
  bb8:
    v8: char = const 'a'
    br bb9(v8)
  bb9(v9: char):
    br_if v0, bb11, bb10
  bb10:
    v10: any = const "hi"
    br bb12(v10)
  bb11:
    v11: any = const "hi"
    br bb12(v11)
  bb12(v12: any):
    br_if v3, bb14, bb13
  bb13:
    v13: int = const 0
    br bb15(v13)
  bb14:
    v14: int = const 1
    br bb15(v14)
  bb15(v15: int):
    br_if v12, bb17, bb16
  bb16:
    v16: int = const 0
    br bb18(v16)
  bb17:
    v17: int = const 2
    br bb18(v17)
  bb18(v18: int):
    v19: int = add v15, v18 {pure}
    br_if v9, bb20, bb19
  bb19:
    v20: int = const 0
    br bb21(v20)
  bb20:
    v21: int = const 1
    br bb21(v21)
  bb21(v22: int):
    v23: int = add v19, v22 {pure}
    v24: long = call_java Math.abs(v3) {pure}
    v25: float = convert.float v24
    v26: float = call_java Math.abs(v6) {pure}
    v27: float = add v25, v26 {pure}
    v28: float = convert.float v23
    v29: float = add v27, v28 {pure}
    v30: double = convert.double v29
    ret v30
```

```mir
;; mir -O2 fn=sccpJdk
fn sccpJdk(v0: bool) -> double:
  bb0:
    br bb1
  bb1:
    v1: double = const 13.5
    ret v1
```
