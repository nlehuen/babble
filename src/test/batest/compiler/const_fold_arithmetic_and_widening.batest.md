# Constant Folding : Arithmetic and Primitive Widening

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
```



## Case 1

```babble
def f = fn (): bool -> (
    def b1 = true ; def b2 = false
    ((b1 == b2) or (b1 != b2)) and (b1 == b1) and not (b1 != b1) and
    ('a' < 'b') and ('a' <= 'a') and ('b' > 'a') and ('b' >= 'b') and ('a' == 'a') and ('a' != 'b') and
    not (false and b1) and (true and b1) and (true or b2) and (false or b1) and (not false)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def b1 true:bool)
  (def b2 false:bool)
  (and (and (and (and (and (and (and (and (and (and (and (and (and (block (or (block (==:bool var(b1) var(b2))) (block (<>:bool var(b1) var(b2))))) (block (==:bool var(b1) var(b1)))) (not (block (<>:bool var(b1) var(b1))))) (block (<:bool 'a' 'b'))) (block (<=:bool 'a' 'a'))) (block (>:bool 'b' 'a'))) (block (>=:bool 'b' 'b'))) (block (==:bool 'a' 'a'))) (block (<>:bool 'a' 'b'))) (not (block (and false:bool var(b1))))) (block (and true:bool var(b1)))) (block (or true:bool var(b2)))) (block (or false:bool var(b1)))) (block (not false:bool)))
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

## Case 2

```babble
def f = fn (): bool -> (
    (-5 < 2) and (2 <= 2) and not (3 <= 2) and (3 > 2) and (3 >= 3) and (4 == 4) and (4 != 5) and not (4 != 4) and
    (-5L < 2L) and (2L <= 2L) and (3L > 2L) and (3L >= 3L) and (4L == 4L) and (4L != 5L) and
    (byte(1) < byte(2)) and (short(1) < short(2))
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -5:int 2:int)) (block (<=:bool 2:int 2:int))) (not (block (<=:bool 3:int 2:int)))) (block (>:bool 3:int 2:int))) (block (>=:bool 3:int 3:int))) (block (==:bool 4:int 4:int))) (block (<>:bool 4:int 5:int))) (not (block (<>:bool 4:int 4:int)))) (block (<:bool -5:long 2:long))) (block (<=:bool 2:long 2:long))) (block (>:bool 3:long 2:long))) (block (>=:bool 3:long 3:long))) (block (==:bool 4:long 4:long))) (block (<>:bool 4:long 5:long))) (block (<:bool (call name(byte) 1:int) (call name(byte) 2:int)))) (block (<:bool (call name(short) 1:int) (call name(short) 2:int))))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (and (<:bool (call name(byte) 1:int) (call name(byte) 2:int)) (<:bool (call name(short) 1:int) (call name(short) 2:int)))
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: int = const 1
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: byte = convert.byte v2
    v4: any = opaque.NameRead() {impure}
    v5: int = const 2
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: byte = convert.byte v6
    v8: bool = lt v3, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: any = opaque.NameRead() {impure}
    v11: int = const 1
    v12: any = opaque.DynamicCall(v10, v11) {impure}
    v13: short = convert.short v12
    v14: any = opaque.NameRead() {impure}
    v15: int = const 2
    v16: any = opaque.DynamicCall(v14, v15) {impure}
    v17: short = convert.short v16
    v18: bool = lt v13, v17 {pure}
    br bb2(v18)
  bb2(v19: bool):
    ret v19
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: int = const 1
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: byte = convert.byte v2
    v4: any = opaque.NameRead() {impure}
    v5: int = const 2
    v6: any = opaque.DynamicCall(v4, v5) {impure}
    v7: byte = convert.byte v6
    v8: bool = lt v3, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: any = opaque.NameRead() {impure}
    v11: any = opaque.DynamicCall(v10, v1) {impure}
    v12: short = convert.short v11
    v13: any = opaque.NameRead() {impure}
    v14: any = opaque.DynamicCall(v13, v5) {impure}
    v15: short = convert.short v14
    v16: bool = lt v12, v15 {pure}
    br bb2(v16)
  bb2(v17: bool):
    ret v17
```

## Case 3

```babble
def f = fn (): bool -> (
    (-1.5f < 2.0f) and (2.0f <= 2.0f) and (3.0f > 2.0f) and (3.0f >= 3.0f) and (4.0f == 4.0f) and (4.0f != 5.0f) and
    (-2.5 < 1.5) and (1.5 <= 1.5) and (3.5 > 1.5) and (3.5 >= 3.5) and (4.5 == 4.5) and (4.5 != 5.5)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -1.5:float 2.0:float)) (block (<=:bool 2.0:float 2.0:float))) (block (>:bool 3.0:float 2.0:float))) (block (>=:bool 3.0:float 3.0:float))) (block (==:bool 4.0:float 4.0:float))) (block (<>:bool 4.0:float 5.0:float))) (block (<:bool -2.5:double 1.5:double))) (block (<=:bool 1.5:double 1.5:double))) (block (>:bool 3.5:double 1.5:double))) (block (>=:bool 3.5:double 3.5:double))) (block (==:bool 4.5:double 4.5:double))) (block (<>:bool 4.5:double 5.5:double)))
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

## Case 4

```babble
def f = fn (): bool -> (
    ((10 + 2 - 4) * 3 == 24) and ((10L + 2L - 4L) * 3L == 24L) and
    (10 / 2 == 5) and (10L / 2L == 5L) and
    ((1.5f + 2.5f - 1.0f) * 2.0f / 3.0f == 2.0f) and
    ((1.5 + 2.5 - 1.0) * 2.0 / 3.0 == 2.0) and
    (-(1.5f + 2.5f) == -4.0f) and (-(1L + 2L) == -3L)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (block (==:bool (*:int (block (-:int (+:int 10:int 2:int) 4:int)) 3:int) 24:int)) (block (==:bool (*:long (block (-:long (+:long 10:long 2:long) 4:long)) 3:long) 24:long))) (block (==:bool (/:double 10:int 2:int) 5:int))) (block (==:bool (/:double 10:long 2:long) 5:long))) (block (==:bool (/:float (*:float (block (-:float (+:float 1.5:float 2.5:float) 1.0:float)) 2.0:float) 3.0:float) 2.0:float))) (block (==:bool (/:double (*:double (block (-:double (+:double 1.5:double 2.5:double) 1.0:double)) 2.0:double) 3.0:double) 2.0:double))) (block (==:bool (neg (block (+:float 1.5:float 2.5:float))) -4.0:float))) (block (==:bool (neg (block (+:long 1:long 2:long))) -3:long)))
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

## Local constant propagation across primitive widening chains

```babble
def f = fn (): bool -> (
    def bConst: byte = byte(3)
    def sConst: short = bConst
    def iConst: int = sConst
    def lConst: long = iConst
    def fConst: float = 2.5f
    def dConst: double = fConst
    lConst == 3L and dConst == 2.5
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst var(bConst))
  (def iConst var(sConst))
  (def lConst var(iConst))
  (def fConst 2.5:float)
  (def dConst var(fConst))
  (and (==:bool var(lConst) 3:long) (==:bool var(dConst) 2.5:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst var(bConst))
  (def iConst var(sConst))
  (def lConst var(iConst))
  (and (==:bool var(lConst) 3:long) true:bool)
```

```mir
;; mir -O2 fn=f stage=before,final
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: int = const 3
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: byte = convert.byte v2
    v4: short = convert.short v3
    v5: int = convert.int v4
    v6: long = convert.long v5
    v7: long = const 3L
    v8: bool = eq v6, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: bool = const true
    br bb2(v10)
  bb2(v11: bool):
    ret v11
```

## Direct literal widening conversions, string, and null constants

```babble
def f = fn (): bool -> (
    def bs: short = byte(3)
    def sl: long = short(4)
    def il: long = 5
    def ifl: float = 6
    def id: double = 7
    def kStr: string = "hi"
    def kNull: string? = null
    bs == short(3) and sl == 4L and il == 5L and ifl == 6.0f and id == 7.0 and kStr == "hi" and kNull == null
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bs (call name(byte) 3:int))
  (def sl (call name(short) 4:int))
  (def il 5:long)
  (def ifl 6:int)
  (def id 7:int)
  (def kStr "hi")
  (def kNull null)
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) (==:bool var(sl) 4:long)) (==:bool var(il) 5:long)) (==:bool var(ifl) 6.0:float)) (==:bool var(id) 7.0:double)) (==:bool var(kStr) "hi")) (==:bool var(kNull) null))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bs (call name(byte) 3:int))
  (def sl (call name(short) 4:int))
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) (==:bool var(sl) 4:long)) true:bool) true:bool) true:bool) true:bool) (==:bool null null))
```

```mir
;; mir -O2 fn=f stage=before
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: int = const 3
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = widen#1 v2
    v4: short = convert.short v3
    v5: any = opaque.NameRead() {impure}
    v6: int = const 4
    v7: any = opaque.DynamicCall(v5, v6) {impure}
    v8: any = widen#3 v7
    v9: long = convert.long v8
    v10: any = opaque.NameRead() {impure}
    v11: int = const 3
    v12: any = opaque.DynamicCall(v10, v11) {impure}
    v13: short = convert.short v12
    v14: bool = eq v4, v13 {pure}
    v15: bool = const false
    br_if v14, bb1, bb2(v15)
  bb1:
    v16: long = const 4L
    v17: bool = eq v9, v16 {pure}
    br bb2(v17)
  bb2(v18: bool):
    v19: bool = const false
    br_if v18, bb3, bb4(v19)
  bb3:
    v20: bool = const true
    br bb4(v20)
  bb4(v21: bool):
    v22: bool = const false
    br_if v21, bb5, bb6(v22)
  bb5:
    v23: bool = const true
    br bb6(v23)
  bb6(v24: bool):
    v25: bool = const false
    br_if v24, bb7, bb8(v25)
  bb7:
    v26: bool = const true
    br bb8(v26)
  bb8(v27: bool):
    v28: bool = const false
    br_if v27, bb9, bb10(v28)
  bb9:
    v29: bool = const true
    br bb10(v29)
  bb10(v30: bool):
    v31: bool = const false
    br_if v30, bb11, bb12(v31)
  bb11:
    v32: any = const null
    v33: any = const null
    v34: any = eq v32, v33 {pure,throws}
    v35: bool = not v34 {pure}
    v36: bool = not v35 {pure}
    br bb12(v36)
  bb12(v37: bool):
    ret v37
```

```mir
;; mir -O2 fn=f
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: int = const 3
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: any = widen#1 v2
    v4: short = convert.short v3
    v5: any = opaque.NameRead() {impure}
    v6: int = const 4
    v7: any = opaque.DynamicCall(v5, v6) {impure}
    v8: any = widen#3 v7
    v9: long = convert.long v8
    v10: any = opaque.NameRead() {impure}
    v11: any = opaque.DynamicCall(v10, v1) {impure}
    v12: short = convert.short v11
    v13: bool = eq v4, v12 {pure}
    v14: bool = const false
    br_if v13, bb1, bb2(v14)
  bb1:
    v15: long = const 4L
    v16: bool = eq v9, v15 {pure}
    br bb2(v16)
  bb2(v17: bool):
    br_if v17, bb3, bb4(v14)
  bb3:
    v18: bool = const true
    br bb4(v18)
  bb4(v19: bool):
    br_if v19, bb5, bb6(v14)
  bb5:
    v20: bool = const true
    br bb6(v20)
  bb6(v21: bool):
    br_if v21, bb7, bb8(v14)
  bb7:
    v22: bool = const true
    br bb8(v22)
  bb8(v23: bool):
    br_if v23, bb9, bb10(v14)
  bb9:
    v24: bool = const true
    br bb10(v24)
  bb10(v25: bool):
    br_if v25, bb11, bb12(v14)
  bb11:
    v26: any = const null
    v27: any = eq v26, v26 {pure,throws}
    v28: bool = not v27 {pure}
    v29: bool = not v28 {pure}
    br bb12(v29)
  bb12(v30: bool):
    ret v30
```
