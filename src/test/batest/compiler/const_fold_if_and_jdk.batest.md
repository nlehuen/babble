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
  (def e (if false:bool (box (block 1:int))))
  (def s (if true:bool (widen[short] (block (call name(byte) 7:int))) (block (call name(short) 8:int))))
  (def i (if true:bool (widen[int] (block (call name(short) 7:int))) (block 8:int)))
  (def l (if true:bool (block 9:long) (block 10:long)))
  (def fl (if false:bool (block 1.0:float) (block (call name(float) 4:int))))
  (def d (if true:bool (widen[double] (block 5:int)) (block 6.5:double)))
  (and (and (and (and (and (==:bool var(e) null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) (==:bool var(l) 9:long)) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def s (widen[short] (call name(byte) 7:int)))
  (def i (widen[int] (call name(short) 7:int)))
  (def fl (call name(float) 4:int))
  (and (and (and (and (and (==:bool null null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) true:bool) (==:bool var(fl) 4.0:float)) true:bool)
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: any = name_read byte {pure}
    v1: int = const 7
    v2: byte = opaque.DynamicCall(v0, v1) {impure}
    v3: short = convert.short v2
    v4: any = name_read short {pure}
    v5: int = const 7
    v6: short = opaque.DynamicCall(v4, v5) {impure}
    v7: int = convert.int v6
    v8: any = name_read float {pure}
    v9: int = const 4
    v10: float = opaque.DynamicCall(v8, v9) {impure}
    v11: any = const null
    v12: any = const null
    v13: any = eq v11, v12 {pure,throws}
    v14: bool = not v13 {pure}
    v15: bool = not v14 {pure}
    v16: bool = const false
    br_if v15, bb1, bb2(v16)
  bb1:
    v17: any = name_read short {pure}
    v18: int = const 7
    v19: short = opaque.DynamicCall(v17, v18) {impure}
    v20: bool = eq v3, v19 {pure}
    br bb2(v20)
  bb2(v21: bool):
    v22: bool = const false
    br_if v21, bb3, bb4(v22)
  bb3:
    v23: int = const 7
    v24: bool = eq v7, v23 {pure}
    br bb4(v24)
  bb4(v25: bool):
    v26: bool = const false
    br_if v25, bb5, bb6(v26)
  bb5:
    v27: bool = const true
    br bb6(v27)
  bb6(v28: bool):
    v29: bool = const false
    br_if v28, bb7, bb8(v29)
  bb7:
    v30: float = const 4.0f
    v31: bool = eq v10, v30 {pure}
    br bb8(v31)
  bb8(v32: bool):
    v33: bool = const false
    br_if v32, bb9, bb10(v33)
  bb9:
    v34: bool = const true
    br bb10(v34)
  bb10(v35: bool):
    ret v35
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: any = name_read byte {pure}
    v1: int = const 7
    v2: byte = opaque.DynamicCall(v0, v1) {impure}
    v3: short = convert.short v2
    v4: any = name_read short {pure}
    v5: short = opaque.DynamicCall(v4, v1) {impure}
    v6: int = convert.int v5
    v7: any = name_read float {pure}
    v8: int = const 4
    v9: float = opaque.DynamicCall(v7, v8) {impure}
    v10: any = const null
    v11: any = eq v10, v10 {pure,throws}
    v12: bool = not v11 {pure}
    v13: bool = not v12 {pure}
    v14: bool = const false
    br_if v13, bb1, bb2(v14)
  bb1:
    v15: short = opaque.DynamicCall(v4, v1) {impure}
    v16: bool = eq v3, v15 {pure}
    br bb2(v16)
  bb2(v17: bool):
    br_if v17, bb3, bb4(v14)
  bb3:
    v18: bool = eq v6, v1 {pure}
    br bb4(v18)
  bb4(v19: bool):
    br_if v19, bb5, bb6(v14)
  bb5:
    v20: bool = const true
    br bb6(v20)
  bb6(v21: bool):
    br_if v21, bb7, bb8(v14)
  bb7:
    v22: float = const 4.0f
    v23: bool = eq v9, v22 {pure}
    br bb8(v23)
  bb8(v24: bool):
    br_if v24, bb9, bb10(v14)
  bb9:
    v25: bool = const true
    br bb10(v25)
  bb10(v26: bool):
    ret v26
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
  (def wL (if true:bool (widen[long] (block (+:int 5:int 1:int))) (block 2:long)))
  (def wF (if true:bool (widen[float] (block (+:int 3:int 1:int))) (widen[float] (block 2:int))))
  (def wD (if true:bool (widen[double] (block (+:int 4:int 2:int))) (widen[double] (block 1:int))))
  (def a
    (if true:bool
      (block
        (def x 2:int)
        (+:int var(x) 3:int))
      (block 1:int)))
  (def b (if false:bool (block 1.0:double) (widen[double] (block 4:int))))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) (==:bool var(a) 5:int)) (==:bool var(b) 4.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
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
  (def wL (if true:bool (widen[long] (block (+:int 4:int 2:int))) (block 1:long)))
  (def wF (if true:bool (widen[float] (block (+:int 3:int 2:int))) (block 1.5:float)))
  (def wD (if true:bool (widen[double] (block (+:int 7:int 1:int))) (block 2.5:double)))
  (def wStr (if true:bool (block "ok") (block "no")))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) (==:bool var(wStr) "ok"))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: bool = const true
    ret v0
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
  (def wBl (if true:bool (widen[double] (block (+:int var(p) 1:int))) (widen[double] (block (+:int var(p) 2:int)))))
  (def wArg (call-fn widenSinkD (if true:bool (widen[double] (block (+:int var(p) 1:int))) (widen[double] (block (+:int var(p) 2:int))))))
  (def nc (if (>:bool var(p) 0:int) (block 1:int) (block 2:int)))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```hir
;; hir -O1 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (widen[double] (+:int var(p) 1:int)))
  (def wArg
    (block
      (def v (widen[double] (+:int var(p) 1:int)))
      (+:double var(v) 0.5:double)))
  (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```mir
;; mir -O2 fn=checkIf stage=before
fn checkIf(v0: int) -> bool:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: double = convert.double v2
    v4: int = const 1
    v5: int = add v0, v4 {pure}
    v6: double = convert.double v5
    v7: double = const 0.5
    v8: double = add v6, v7 {pure}
    v9: int = const 0
    v10: bool = gt v0, v9 {pure}
    br_if v10, bb2, bb1
  bb1:
    v11: int = const 2
    br bb3(v11)
  bb2:
    v12: int = const 1
    br bb3(v12)
  bb3(v13: int):
    v14: double = const 3.0
    v15: bool = eq v3, v14 {pure}
    v16: bool = const false
    br_if v15, bb4, bb5(v16)
  bb4:
    v17: double = const 3.5
    v18: bool = eq v8, v17 {pure}
    br bb5(v18)
  bb5(v19: bool):
    v20: bool = const false
    br_if v19, bb6, bb7(v20)
  bb6:
    v21: int = const 1
    v22: bool = eq v13, v21 {pure}
    br bb7(v22)
  bb7(v23: bool):
    ret v23
```

```mir
;; mir -O2 fn=checkIf
fn checkIf(v0: int) -> bool:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: double = convert.double v2
    v4: double = const 0.5
    v5: double = add v3, v4 {pure}
    v6: int = const 0
    v7: bool = gt v0, v6 {pure}
    br_if v7, bb2, bb1
  bb1:
    v8: int = const 2
    br bb3(v8)
  bb2:
    br bb3(v1)
  bb3(v9: int):
    v10: double = const 3.0
    v11: bool = eq v3, v10 {pure}
    v12: bool = const false
    br_if v11, bb4, bb5(v12)
  bb4:
    v13: double = const 3.5
    v14: bool = eq v5, v13 {pure}
    br bb5(v14)
  bb5(v15: bool):
    br_if v15, bb6, bb7(v12)
  bb6:
    v16: bool = eq v9, v1 {pure}
    br bb7(v16)
  bb7(v17: bool):
    ret v17
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
