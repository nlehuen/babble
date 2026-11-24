# Constant Folding : If-Pruning and Foldable JDK Calls

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
```



## Constant if-pruning and convertConstToKind widening

```babble
def f = fn (): bool -> (
  def e = if false then (1)
  def s: short = if true then (byte(7)) else (short(8))
  def i: int = if true then (short(7)) else (8)
  def l: long = if true then (9) else (10L)
  def fl: float = if false then (1.0f) else (float(4))
  def d: double = if true then (5) else (6.5)
  e == null and s == short(7) and i == 7 and l == 9L and fl == 4.0f and d == 5.0
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def e (if false:bool (block 1:int)))
  (def s (if true:bool (block (call name(byte) 7:int)) (block (call name(short) 8:int))))
  (def i (if true:bool (block (call name(short) 7:int)) (block 8:int)))
  (def l (if true:bool (block 9:long) (block 10:long)))
  (def fl (if false:bool (block 1.0:float) (block (call name(float) 4:int))))
  (def d (if true:bool (block 5:int) (block 6.5:double)))
  (and (and (and (and (and (==:bool var(e) null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) (==:bool var(l) 9:long)) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def s (if true:bool (block (call name(byte) 7:int)) (call name(short) 8:int)))
  (def i (if true:bool (block (call name(short) 7:int)) 8:int))
  (def fl (call name(float) 4:int))
  (def d (if true:bool (block 5:int) 6.5:double))
  (and (and (and (and (and (==:bool null null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) true:bool) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: bool = const true
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead() {impure}
    v2: int = const 8
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: short = convert.short v3
    br bb3(v4)
  bb2:
    v5: any = opaque.NameRead() {impure}
    v6: int = const 7
    v7: any = opaque.DynamicCall(v5, v6) {impure}
    v8: any = widen#1 v7
    v9: short = convert.short v8
    br bb3(v9)
  bb3(v10: short):
    v11: bool = const true
    br_if v11, bb5, bb4
  bb4:
    v12: int = const 8
    br bb6(v12)
  bb5:
    v13: any = opaque.NameRead() {impure}
    v14: int = const 7
    v15: any = opaque.DynamicCall(v13, v14) {impure}
    v16: any = widen#2 v15
    v17: int = convert.int v16
    br bb6(v17)
  bb6(v18: int):
    v19: any = opaque.NameRead() {impure}
    v20: int = const 4
    v21: any = opaque.DynamicCall(v19, v20) {impure}
    v22: float = convert.float v21
    v23: bool = const true
    br_if v23, bb8, bb7
  bb7:
    v24: double = const 6.5
    br bb9(v24)
  bb8:
    v25: int = const 5
    v26: double = convert.double v25
    br bb9(v26)
  bb9(v27: double):
    v28: any = const null
    v29: any = const null
    v30: any = eq v28, v29 {pure,throws}
    v31: bool = not v30 {pure}
    v32: bool = not v31 {pure}
    v33: bool = const false
    br_if v32, bb10, bb11(v33)
  bb10:
    v34: any = opaque.NameRead() {impure}
    v35: int = const 7
    v36: any = opaque.DynamicCall(v34, v35) {impure}
    v37: short = convert.short v36
    v38: bool = eq v10, v37 {pure}
    br bb11(v38)
  bb11(v39: bool):
    v40: bool = const false
    br_if v39, bb12, bb13(v40)
  bb12:
    v41: int = const 7
    v42: bool = eq v18, v41 {pure}
    br bb13(v42)
  bb13(v43: bool):
    v44: bool = const false
    br_if v43, bb14, bb15(v44)
  bb14:
    v45: bool = const true
    br bb15(v45)
  bb15(v46: bool):
    v47: bool = const false
    br_if v46, bb16, bb17(v47)
  bb16:
    v48: float = const 4.0f
    v49: bool = eq v22, v48 {pure}
    br bb17(v49)
  bb17(v50: bool):
    v51: bool = const false
    br_if v50, bb18, bb19(v51)
  bb18:
    v52: double = const 5.0
    v53: bool = eq v27, v52 {pure}
    br bb19(v53)
  bb19(v54: bool):
    ret v54
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: bool = const true
    br bb1
  bb1:
    v1: any = opaque.NameRead() {impure}
    v2: int = const 7
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: any = widen#1 v3
    v5: short = convert.short v4
    br bb2(v5)
  bb2(v6: short):
    br bb3
  bb3:
    v7: any = opaque.NameRead() {impure}
    v8: any = opaque.DynamicCall(v7, v2) {impure}
    v9: any = widen#2 v8
    v10: int = convert.int v9
    br bb4(v10)
  bb4(v11: int):
    v12: any = opaque.NameRead() {impure}
    v13: int = const 4
    v14: any = opaque.DynamicCall(v12, v13) {impure}
    v15: float = convert.float v14
    br bb5
  bb5:
    v16: any = const null
    v17: any = eq v16, v16 {pure,throws}
    v18: bool = not v17 {pure}
    v19: bool = not v18 {pure}
    v20: bool = const false
    br_if v19, bb6, bb7(v20)
  bb6:
    v21: any = opaque.NameRead() {impure}
    v22: any = opaque.DynamicCall(v21, v2) {impure}
    v23: short = convert.short v22
    v24: bool = eq v6, v23 {pure}
    br bb7(v24)
  bb7(v25: bool):
    br_if v25, bb8, bb9(v20)
  bb8:
    v26: bool = eq v11, v2 {pure}
    br bb9(v26)
  bb9(v27: bool):
    br_if v27, bb10, bb11(v20)
  bb10:
    br bb11(v0)
  bb11(v28: bool):
    br_if v28, bb12, bb13(v20)
  bb12:
    v29: float = const 4.0f
    v30: bool = eq v15, v29 {pure}
    br bb13(v30)
  bb13(v31: bool):
    br_if v31, bb14, bb15(v20)
  bb14:
    br bb15(v0)
  bb15(v32: bool):
    ret v32
```

## Constant if-pruning with folded arithmetic branches and multi-statement blocks

```babble
def f = fn (): bool -> (
    def wL: long = if true then (5 + 1) else (2)
    def wF: float = if true then (3 + 1) else (2)
    def wD: double = if true then (4 + 2) else (1)
    def a = if true then (def x = 2 ; x + 3) else (1)
    def b: double = if false then (1.0) else (4)
    wL == 6L and wF == 4.0f and wD == 6.0 and a == 5 and b == 4.0
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block (+:int 5:int 1:int)) (block 2:long)))
  (def wF (if true:bool (block (+:int 3:int 1:int)) (block 2:int)))
  (def wD (if true:bool (block (+:int 4:int 2:int)) (block 1:int)))
  (def a
    (if true:bool
      (block
        (def x 2:int)
        (+:int var(x) 3:int))
      (block 1:int)))
  (def b (if false:bool (block 1.0:double) (block 4:int)))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) (==:bool var(a) 5:int)) (==:bool var(b) 4.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block 6:int) 2:long))
  (def wF (if true:bool (block 4:int) (block 2:int)))
  (def wD (if true:bool (block 6:int) (block 1:int)))
  (def b (if false:bool 1.0:double (block 4:int)))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) true:bool) (==:bool var(b) 4.0:double))
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: bool = const true
    br_if v0, bb2, bb1
  bb1:
    v1: long = const 2L
    br bb3(v1)
  bb2:
    v2: int = const 6
    v3: long = convert.long v2
    br bb3(v3)
  bb3(v4: long):
    v5: bool = const true
    br_if v5, bb5, bb4
  bb4:
    v6: int = const 2
    v7: float = convert.float v6
    br bb6(v7)
  bb5:
    v8: int = const 4
    v9: float = convert.float v8
    br bb6(v9)
  bb6(v10: float):
    v11: bool = const true
    br_if v11, bb8, bb7
  bb7:
    v12: int = const 1
    v13: double = convert.double v12
    br bb9(v13)
  bb8:
    v14: int = const 6
    v15: double = convert.double v14
    br bb9(v15)
  bb9(v16: double):
    v17: bool = const false
    br_if v17, bb11, bb10
  bb10:
    v18: int = const 4
    v19: double = convert.double v18
    br bb12(v19)
  bb11:
    v20: double = const 1.0
    br bb12(v20)
  bb12(v21: double):
    v22: long = const 6L
    v23: bool = eq v4, v22 {pure}
    v24: bool = const false
    br_if v23, bb13, bb14(v24)
  bb13:
    v25: float = const 4.0f
    v26: bool = eq v10, v25 {pure}
    br bb14(v26)
  bb14(v27: bool):
    v28: bool = const false
    br_if v27, bb15, bb16(v28)
  bb15:
    v29: double = const 6.0
    v30: bool = eq v16, v29 {pure}
    br bb16(v30)
  bb16(v31: bool):
    v32: bool = const false
    br_if v31, bb17, bb18(v32)
  bb17:
    v33: bool = const true
    br bb18(v33)
  bb18(v34: bool):
    v35: bool = const false
    br_if v34, bb19, bb20(v35)
  bb19:
    v36: double = const 4.0
    v37: bool = eq v21, v36 {pure}
    br bb20(v37)
  bb20(v38: bool):
    ret v38
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: bool = const true
    br bb1
  bb1:
    br bb2(v0)
  bb2(v1: bool):
    ret v1
```

## Constant if-pruning with literal/folded branches and target widening

```babble
def f = fn (): bool -> (
    def wL: long = if true then (4 + 2) else (1L)
    def wF: float = if true then (3 + 2) else (1.5f)
    def wD: double = if true then (7 + 1) else (2.5)
    def wStr: string = if true then ("ok") else ("no")
    wL == 6L and wF == 5.0f and wD == 8.0 and wStr == "ok"
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block (+:int 4:int 2:int)) (block 1:long)))
  (def wF (if true:bool (block (+:int 3:int 2:int)) (block 1.5:float)))
  (def wD (if true:bool (block (+:int 7:int 1:int)) (block 2.5:double)))
  (def wStr (if true:bool (block "ok") (block "no")))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) (==:bool var(wStr) "ok"))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (block 6:int) 1:long))
  (def wF (if true:bool (block 5:int) 1.5:float))
  (def wD (if true:bool (block 8:int) 2.5:double))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) true:bool)
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: bool = const true
    br_if v0, bb2, bb1
  bb1:
    v1: long = const 1L
    br bb3(v1)
  bb2:
    v2: int = const 6
    v3: long = convert.long v2
    br bb3(v3)
  bb3(v4: long):
    v5: bool = const true
    br_if v5, bb5, bb4
  bb4:
    v6: float = const 1.5f
    br bb6(v6)
  bb5:
    v7: int = const 5
    v8: float = convert.float v7
    br bb6(v8)
  bb6(v9: float):
    v10: bool = const true
    br_if v10, bb8, bb7
  bb7:
    v11: double = const 2.5
    br bb9(v11)
  bb8:
    v12: int = const 8
    v13: double = convert.double v12
    br bb9(v13)
  bb9(v14: double):
    v15: long = const 6L
    v16: bool = eq v4, v15 {pure}
    v17: bool = const false
    br_if v16, bb10, bb11(v17)
  bb10:
    v18: float = const 5.0f
    v19: bool = eq v9, v18 {pure}
    br bb11(v19)
  bb11(v20: bool):
    v21: bool = const false
    br_if v20, bb12, bb13(v21)
  bb12:
    v22: double = const 8.0
    v23: bool = eq v14, v22 {pure}
    br bb13(v23)
  bb13(v24: bool):
    v25: bool = const false
    br_if v24, bb14, bb15(v25)
  bb14:
    v26: bool = const true
    br bb15(v26)
  bb15(v27: bool):
    ret v27
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: bool = const true
    br bb1
  bb1:
    br bb2(v0)
  bb2(v1: bool):
    ret v1
```

## Constant if-pruning with non-constant widened branches and non-constant if

```babble
def checkIf = fn (p: int): bool -> (
    def wBl: double = if true then (p + 1) else (p + 2)
    def wArg = widenSinkD(if true then (p + 1) else (p + 2))
    def nc = if p > 0 then (1) else (2)
    wBl == 3.0 and wArg == 3.5 and nc == 1
)
assert(checkIf(2))
```

```hir
;; hir -O0 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
  (def wArg (call-fn widenSinkD (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int)))))
  (def nc (if (>:bool var(p) 0:int) (block 1:int) (block 2:int)))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```hir
;; hir -O1 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
  (def wArg
    (block
      (def v (if true:bool (block (+:int var(p) 1:int)) (block (+:int var(p) 2:int))))
      (+:double var(v) 0.5:double)))
  (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```mir
;; mir -O2 fn=checkIf stage=before
fn checkIf(v0: int) -> bool:
  bb0:
    v1: bool = const true
    br_if v1, bb2, bb1
  bb1:
    v2: int = const 2
    v3: int = add v0, v2 {pure}
    v4: double = convert.double v3
    br bb3(v4)
  bb2:
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: double = convert.double v6
    br bb3(v7)
  bb3(v8: double):
    v9: bool = const true
    br_if v9, bb5, bb4
  bb4:
    v10: int = const 2
    v11: int = add v0, v10 {pure}
    v12: double = convert.double v11
    br bb6(v12)
  bb5:
    v13: int = const 1
    v14: int = add v0, v13 {pure}
    v15: double = convert.double v14
    br bb6(v15)
  bb6(v16: double):
    v17: double = const 0.5
    v18: double = add v16, v17 {pure}
    v19: int = const 0
    v20: bool = gt v0, v19 {pure}
    br_if v20, bb8, bb7
  bb7:
    v21: int = const 2
    br bb9(v21)
  bb8:
    v22: int = const 1
    br bb9(v22)
  bb9(v23: int):
    v24: double = const 3.0
    v25: bool = eq v8, v24 {pure}
    v26: bool = const false
    br_if v25, bb10, bb11(v26)
  bb10:
    v27: double = const 3.5
    v28: bool = eq v18, v27 {pure}
    br bb11(v28)
  bb11(v29: bool):
    v30: bool = const false
    br_if v29, bb12, bb13(v30)
  bb12:
    v31: int = const 1
    v32: bool = eq v23, v31 {pure}
    br bb13(v32)
  bb13(v33: bool):
    ret v33
```

```mir
;; mir -O2 fn=checkIf
fn checkIf(v0: int) -> bool:
  bb0:
    br bb1
  bb1:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: double = convert.double v2
    br bb2(v3)
  bb2(v4: double):
    br bb3
  bb3:
    br bb4(v3)
  bb4(v5: double):
    v6: double = const 0.5
    v7: double = add v5, v6 {pure}
    v8: int = const 0
    v9: bool = gt v0, v8 {pure}
    br_if v9, bb6, bb5
  bb5:
    v10: int = const 2
    br bb7(v10)
  bb6:
    br bb7(v1)
  bb7(v11: int):
    v12: double = const 3.0
    v13: bool = eq v4, v12 {pure}
    v14: bool = const false
    br_if v13, bb8, bb9(v14)
  bb8:
    v15: double = const 3.5
    v16: bool = eq v7, v15 {pure}
    br bb9(v16)
  bb9(v17: bool):
    br_if v17, bb10, bb11(v14)
  bb10:
    v18: bool = eq v11, v1 {pure}
    br bb11(v18)
  bb11(v19: bool):
    ret v19
```

## Whitelisted foldable String and Math calls, plus non-folding Math.floorDiv

```babble
def f = fn (): bool -> (
    "  babble  ".trim() == "babble" and "abcd".length() == 4 and "".isEmpty() and
    ("hello" + " world" == "hello world") and
    java.lang.Math.sqrt(9.0) == 3.0 and java.lang.Math.round(2.5) == 3L and
    java.lang.Math.toDegrees(0.0) == 0.0 and java.lang.Math.floorDiv(10, 3) == 3
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (jcall String.trim "  babble  ") "babble") (==:bool (jcall String.length "abcd") 4:int)) (jcall String.isEmpty "")) (block (==:bool (+:obj "hello" " world") "hello world"))) (==:bool (jcall Math.sqrt 9.0:double) 3.0:double)) (==:bool (jcall Math.round 2.5:double) 3:long)) (==:bool (jcall Math.toDegrees 0.0:double) 0.0:double)) (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int)
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: int = const 10
    v1: int = const 3
    v2: int = call_java Math.floorDiv(v0, v1) {pure,throws}
    v3: int = const 3
    v4: bool = eq v2, v3 {pure}
    ret v4
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: int = const 10
    v1: int = const 3
    v2: int = call_java Math.floorDiv(v0, v1) {pure,throws}
    v3: bool = eq v2, v1 {pure}
    ret v3
```

## Whitelisted foldable Math.abs/min/max across int, long, float, and double

```babble
def f = fn (): bool -> (
    java.lang.Math.abs(-7) == 7 and java.lang.Math.abs(-9L) == 9L and
    java.lang.Math.abs(-2.5f) == 2.5f and java.lang.Math.abs(-3.5) == 3.5 and
    java.lang.Math.min(3, java.lang.Math.max(1, 2)) == 2 and
    java.lang.Math.min(4L, 9L) == 4L and java.lang.Math.max(1.5f, 2.5f) == 2.5f
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (==:bool (jcall Math.abs -7:int) 7:int) (==:bool (jcall Math.abs -9:long) 9:long)) (==:bool (jcall Math.abs -2.5:float) 2.5:float)) (==:bool (jcall Math.abs -3.5:double) 3.5:double)) (==:bool (jcall Math.min 3:int (jcall Math.max 1:int 2:int)) 2:int)) (==:bool (jcall Math.min 4:long 9:long) 4:long)) (==:bool (jcall Math.max 1.5:float 2.5:float) 2.5:float))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```mir
;; mir -O2 fn=f stage=before,final
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
```
