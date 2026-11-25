# Primitive Unboxing : Scalar Parameters, Arithmetic, Truthiness, and Comparisons

References : BIP011 (§7 primitive unboxing), BIP001 (RM001/RM003 lowering)



## 8-primitive parameter lowering including 2-slot long/double and branch selection

```babble
def mix = fn (b: byte, s: short, i: int, l: long, f: float, d: double, c: char, ok: bool): double -> (
    def sl = l + long(i) + long(b) + long(s)
    def sf = f + float(i)
    if ok and c == 'A' then ( double(sl) + double(sf) + d ) else ( 0.0 )
)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'A', true) == 116.5)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'B', true) == 0.0)
```

```hir
;; hir -O0 fn=mix
fn mix (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, ok:bool) -> double {nothrow}
  (def sl (+:long (+:long (+:long var(l) (call name(long) var(i))) (call name(long) var(b))) (call name(long) var(s))))
  (def sf (+:float var(f) (call name(float) var(i))))
  (if (and var(ok) (==:bool var(c) 'A')) (block (+:double (+:double (call name(double) var(sl)) (call name(double) var(sf))) var(d))) (block 0.0:double))
```

```hir
;; hir -O1 fn=mix
fn mix (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, ok:bool) -> double {nothrow}
  (def sl (+:long (+:long (+:long var(l) (call name(long) var(i))) (call name(long) var(b))) (call name(long) var(s))))
  (def sf (+:float var(f) (call name(float) var(i))))
  (if (and var(ok) (==:bool var(c) 'A')) (+:double (+:double (call name(double) var(sl)) (call name(double) var(sf))) var(d)) 0.0:double)
```

```mir
;; mir -O2 fn=mix stage=before,final
fn mix(v0: byte, v1: short, v2: int, v3: long, v4: float, v5: double, v6: char, v7: bool) -> double:
  bb0:
    v8: any = opaque.NameRead() {impure}
    v9: long = opaque.DynamicCall(v8, v2) {impure}
    v10: long = add v3, v9 {pure}
    v11: any = opaque.NameRead() {impure}
    v12: long = opaque.DynamicCall(v11, v0) {impure}
    v13: long = add v10, v12 {pure}
    v14: any = opaque.NameRead() {impure}
    v15: long = opaque.DynamicCall(v14, v1) {impure}
    v16: long = add v13, v15 {pure}
    v17: any = opaque.NameRead() {impure}
    v18: float = opaque.DynamicCall(v17, v2) {impure}
    v19: float = add v4, v18 {pure}
    v20: bool = const false
    br_if v7, bb1, bb2(v20)
  bb1:
    v21: char = const 'A'
    v22: bool = eq v6, v21 {pure}
    br bb2(v22)
  bb2(v23: bool):
    br_if v23, bb4, bb3
  bb3:
    v24: double = const 0.0
    br bb5(v24)
  bb4:
    v25: any = opaque.NameRead() {impure}
    v26: double = opaque.DynamicCall(v25, v16) {impure}
    v27: any = opaque.NameRead() {impure}
    v28: double = opaque.DynamicCall(v27, v19) {impure}
    v29: double = add v26, v28 {pure}
    v30: double = add v29, v5 {pure}
    br bb5(v30)
  bb5(v31: double):
    ret v31
```

## Unboxed unary negation and arithmetic on narrow/wide primitives (parameterized against -O1 folding)

```babble
def negMix = fn (b : byte, s : short, l : long, f : float) : double -> (
    def nb : int = -b
    def ns : int = -s
    def nl : long = -l + 10L
    def nf : float = -f + 1.5f
    double(nb + ns) + double(l + nl) + double(f + nf)
)
assert(negMix(byte(2), short(3), 100L, 2.5f) == 6.5)
```

```hir
;; hir -O0,-O1 fn=negMix
fn negMix (b:byte, s:short, l:long, f:float) -> double {nothrow unique}
  (def nb (neg var(b)))
  (def ns (neg var(s)))
  (def nl (+:long (neg var(l)) 10:long))
  (def nf (+:float (neg var(f)) 1.5:float))
  (+:double (+:double (call name(double) (+:int var(nb) var(ns))) (call name(double) (+:long var(l) var(nl)))) (call name(double) (+:float var(f) var(nf))))
```

```mir
;; mir -O2 fn=negMix stage=before,final
fn negMix(v0: byte, v1: short, v2: long, v3: float) -> double:
  bb0:
    v4: int = convert.int v0
    v5: int = neg v4 {pure}
    v6: int = convert.int v1
    v7: int = neg v6 {pure}
    v8: long = neg v2 {pure}
    v9: long = const 10L
    v10: long = add v8, v9 {pure}
    v11: float = neg v3 {pure}
    v12: float = const 1.5f
    v13: float = add v11, v12 {pure}
    v14: any = opaque.NameRead() {impure}
    v15: int = add v5, v7 {pure}
    v16: double = opaque.DynamicCall(v14, v15) {impure}
    v17: any = opaque.NameRead() {impure}
    v18: long = add v2, v10 {pure}
    v19: double = opaque.DynamicCall(v17, v18) {impure}
    v20: double = add v16, v19 {pure}
    v21: any = opaque.NameRead() {impure}
    v22: float = add v3, v13 {pure}
    v23: double = opaque.DynamicCall(v21, v22) {impure}
    v24: double = add v20, v23 {pure}
    ret v24
```

## Parameterized against -O1 folding to exercise unboxed byte/short truthiness

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short) -> (
    assert((if b0 then ( 1 ) else ( 0 )) == 0 and (if b1 then ( 1 ) else ( 0 )) == 1)
    assert((if s0 then ( 1 ) else ( 0 )) == 0 and (if s1 then ( 1 ) else ( 0 )) == 1)
)
check(byte(0), byte(5), short(0), short(7))
```

```hir
;; hir -O0 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short) -> bool {pure unique}
  (call name(assert) (and (==:bool (block (if var(b0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(b1) (block 1:int) (block 0:int))) 1:int)))
  (call name(assert) (and (==:bool (block (if var(s0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(s1) (block 1:int) (block 0:int))) 1:int)))
```

```hir
;; hir -O1 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short) -> bool {pure unique}
  (call name(assert) (and (==:bool (if var(b0) 1:int 0:int) 0:int) (==:bool (if var(b1) 1:int 0:int) 1:int)))
  (call name(assert) (and (==:bool (if var(s0) 1:int 0:int) 0:int) (==:bool (if var(s1) 1:int 0:int) 1:int)))
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: byte, v1: byte, v2: short, v3: short) -> bool:
  bb0:
    v4: any = opaque.NameRead() {impure}
    br_if v0, bb2, bb1
  bb1:
    v5: int = const 0
    br bb3(v5)
  bb2:
    v6: int = const 1
    br bb3(v6)
  bb3(v7: int):
    v8: int = const 0
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb4, bb8(v10)
  bb4:
    br_if v1, bb6, bb5
  bb5:
    v11: int = const 0
    br bb7(v11)
  bb6:
    v12: int = const 1
    br bb7(v12)
  bb7(v13: int):
    v14: int = const 1
    v15: bool = eq v13, v14 {pure}
    br bb8(v15)
  bb8(v16: bool):
    v17: bool = opaque.DynamicCall(v4, v16) {impure}
    v18: any = opaque.NameRead() {impure}
    br_if v2, bb10, bb9
  bb9:
    v19: int = const 0
    br bb11(v19)
  bb10:
    v20: int = const 1
    br bb11(v20)
  bb11(v21: int):
    v22: int = const 0
    v23: bool = eq v21, v22 {pure}
    v24: bool = const false
    br_if v23, bb12, bb16(v24)
  bb12:
    br_if v3, bb14, bb13
  bb13:
    v25: int = const 0
    br bb15(v25)
  bb14:
    v26: int = const 1
    br bb15(v26)
  bb15(v27: int):
    v28: int = const 1
    v29: bool = eq v27, v28 {pure}
    br bb16(v29)
  bb16(v30: bool):
    v31: bool = opaque.DynamicCall(v18, v30) {impure}
    ret v31
```

```mir
;; mir -O2 fn=check
fn check(v0: byte, v1: byte, v2: short, v3: short) -> bool:
  bb0:
    v4: any = opaque.NameRead() {impure}
    br_if v0, bb2, bb1
  bb1:
    v5: int = const 0
    br bb3(v5)
  bb2:
    v6: int = const 1
    br bb3(v6)
  bb3(v7: int):
    v8: int = const 0
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb4, bb8(v10)
  bb4:
    br_if v1, bb6, bb5
  bb5:
    br bb7(v8)
  bb6:
    v11: int = const 1
    br bb7(v11)
  bb7(v12: int):
    v13: int = const 1
    v14: bool = eq v12, v13 {pure}
    br bb8(v14)
  bb8(v15: bool):
    v16: bool = opaque.DynamicCall(v4, v15) {impure}
    v17: any = opaque.NameRead() {impure}
    br_if v2, bb10, bb9
  bb9:
    br bb11(v8)
  bb10:
    v18: int = const 1
    br bb11(v18)
  bb11(v19: int):
    v20: bool = eq v19, v8 {pure}
    br_if v20, bb12, bb16(v10)
  bb12:
    br_if v3, bb14, bb13
  bb13:
    br bb15(v8)
  bb14:
    v21: int = const 1
    br bb15(v21)
  bb15(v22: int):
    v23: int = const 1
    v24: bool = eq v22, v23 {pure}
    br bb16(v24)
  bb16(v25: bool):
    v26: bool = opaque.DynamicCall(v17, v25) {impure}
    ret v26
```

## Parameterized against -O1 folding to exercise unboxed long/float truthiness

```babble
def check = fn (l0 : long, l1 : long, f0 : float, f1 : float) -> (
    assert((if l0 then ( 1 ) else ( 0 )) == 0 and (if l1 then ( 1 ) else ( 0 )) == 1)
    assert((if f0 then ( 1 ) else ( 0 )) == 0 and (if f1 then ( 1 ) else ( 0 )) == 1)
)
check(0L, 10000000000L, 0.0f, 1.5f)
```

```hir
;; hir -O0 fn=check
fn check (l0:long, l1:long, f0:float, f1:float) -> bool {pure unique}
  (call name(assert) (and (==:bool (block (if var(l0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(l1) (block 1:int) (block 0:int))) 1:int)))
  (call name(assert) (and (==:bool (block (if var(f0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(f1) (block 1:int) (block 0:int))) 1:int)))
```

```hir
;; hir -O1 fn=check
fn check (l0:long, l1:long, f0:float, f1:float) -> bool {pure unique}
  (call name(assert) (and (==:bool (if var(l0) 1:int 0:int) 0:int) (==:bool (if var(l1) 1:int 0:int) 1:int)))
  (call name(assert) (and (==:bool (if var(f0) 1:int 0:int) 0:int) (==:bool (if var(f1) 1:int 0:int) 1:int)))
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: long, v1: long, v2: float, v3: float) -> bool:
  bb0:
    v4: any = opaque.NameRead() {impure}
    br_if v0, bb2, bb1
  bb1:
    v5: int = const 0
    br bb3(v5)
  bb2:
    v6: int = const 1
    br bb3(v6)
  bb3(v7: int):
    v8: int = const 0
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb4, bb8(v10)
  bb4:
    br_if v1, bb6, bb5
  bb5:
    v11: int = const 0
    br bb7(v11)
  bb6:
    v12: int = const 1
    br bb7(v12)
  bb7(v13: int):
    v14: int = const 1
    v15: bool = eq v13, v14 {pure}
    br bb8(v15)
  bb8(v16: bool):
    v17: bool = opaque.DynamicCall(v4, v16) {impure}
    v18: any = opaque.NameRead() {impure}
    br_if v2, bb10, bb9
  bb9:
    v19: int = const 0
    br bb11(v19)
  bb10:
    v20: int = const 1
    br bb11(v20)
  bb11(v21: int):
    v22: int = const 0
    v23: bool = eq v21, v22 {pure}
    v24: bool = const false
    br_if v23, bb12, bb16(v24)
  bb12:
    br_if v3, bb14, bb13
  bb13:
    v25: int = const 0
    br bb15(v25)
  bb14:
    v26: int = const 1
    br bb15(v26)
  bb15(v27: int):
    v28: int = const 1
    v29: bool = eq v27, v28 {pure}
    br bb16(v29)
  bb16(v30: bool):
    v31: bool = opaque.DynamicCall(v18, v30) {impure}
    ret v31
```

```mir
;; mir -O2 fn=check
fn check(v0: long, v1: long, v2: float, v3: float) -> bool:
  bb0:
    v4: any = opaque.NameRead() {impure}
    br_if v0, bb2, bb1
  bb1:
    v5: int = const 0
    br bb3(v5)
  bb2:
    v6: int = const 1
    br bb3(v6)
  bb3(v7: int):
    v8: int = const 0
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb4, bb8(v10)
  bb4:
    br_if v1, bb6, bb5
  bb5:
    br bb7(v8)
  bb6:
    v11: int = const 1
    br bb7(v11)
  bb7(v12: int):
    v13: int = const 1
    v14: bool = eq v12, v13 {pure}
    br bb8(v14)
  bb8(v15: bool):
    v16: bool = opaque.DynamicCall(v4, v15) {impure}
    v17: any = opaque.NameRead() {impure}
    br_if v2, bb10, bb9
  bb9:
    br bb11(v8)
  bb10:
    v18: int = const 1
    br bb11(v18)
  bb11(v19: int):
    v20: bool = eq v19, v8 {pure}
    br_if v20, bb12, bb16(v10)
  bb12:
    br_if v3, bb14, bb13
  bb13:
    br bb15(v8)
  bb14:
    v21: int = const 1
    br bb15(v21)
  bb15(v22: int):
    v23: int = const 1
    v24: bool = eq v22, v23 {pure}
    br bb16(v24)
  bb16(v25: bool):
    v26: bool = opaque.DynamicCall(v17, v25) {impure}
    ret v26
```

## Parameterized against -O1 folding to exercise unboxed primitive comparisons

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short, l1 : long, f1 : float, c0 : char, c1 : char) -> (
    assert(b0 < b1 and s1 > s0 and l1 > 5000000000L and f1 >= 1.5f and c0 < c1 and c0 == 'a')
)
check(byte(0), byte(5), short(0), short(7), 10000000000L, 1.5f, 'a', 'z')
```

```hir
;; hir -O0,-O1 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short, l1:long, f1:float, c0:char, c1:char) -> bool {pure unique}
  (call name(assert) (and (and (and (and (and (<:bool var(b0) var(b1)) (>:bool var(s1) var(s0))) (>:bool var(l1) 5000000000:long)) (>=:bool var(f1) 1.5:float)) (<:bool var(c0) var(c1))) (==:bool var(c0) 'a')))
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: byte, v1: byte, v2: short, v3: short, v4: long, v5: float, v6: char, v7: char) -> bool:
  bb0:
    v8: any = opaque.NameRead() {impure}
    v9: bool = lt v0, v1 {pure}
    v10: bool = const false
    br_if v9, bb1, bb2(v10)
  bb1:
    v11: bool = gt v3, v2 {pure}
    br bb2(v11)
  bb2(v12: bool):
    v13: bool = const false
    br_if v12, bb3, bb4(v13)
  bb3:
    v14: long = const 5000000000L
    v15: bool = gt v4, v14 {pure}
    br bb4(v15)
  bb4(v16: bool):
    v17: bool = const false
    br_if v16, bb5, bb6(v17)
  bb5:
    v18: float = const 1.5f
    v19: bool = gte v5, v18 {pure}
    br bb6(v19)
  bb6(v20: bool):
    v21: bool = const false
    br_if v20, bb7, bb8(v21)
  bb7:
    v22: bool = lt v6, v7 {pure}
    br bb8(v22)
  bb8(v23: bool):
    v24: bool = const false
    br_if v23, bb9, bb10(v24)
  bb9:
    v25: char = const 'a'
    v26: bool = eq v6, v25 {pure}
    br bb10(v26)
  bb10(v27: bool):
    v28: bool = opaque.DynamicCall(v8, v27) {impure}
    ret v28
```

```mir
;; mir -O2 fn=check
fn check(v0: byte, v1: byte, v2: short, v3: short, v4: long, v5: float, v6: char, v7: char) -> bool:
  bb0:
    v8: any = opaque.NameRead() {impure}
    v9: bool = lt v0, v1 {pure}
    v10: bool = const false
    br_if v9, bb1, bb2(v10)
  bb1:
    v11: bool = gt v3, v2 {pure}
    br bb2(v11)
  bb2(v12: bool):
    br_if v12, bb3, bb4(v10)
  bb3:
    v13: long = const 5000000000L
    v14: bool = gt v4, v13 {pure}
    br bb4(v14)
  bb4(v15: bool):
    br_if v15, bb5, bb6(v10)
  bb5:
    v16: float = const 1.5f
    v17: bool = gte v5, v16 {pure}
    br bb6(v17)
  bb6(v18: bool):
    br_if v18, bb7, bb8(v10)
  bb7:
    v19: bool = lt v6, v7 {pure}
    br bb8(v19)
  bb8(v20: bool):
    br_if v20, bb9, bb10(v10)
  bb9:
    v21: char = const 'a'
    v22: bool = eq v6, v21 {pure}
    br bb10(v22)
  bb10(v23: bool):
    v24: bool = opaque.DynamicCall(v8, v23) {impure}
    ret v24
```

## Case 8

```babble
def H = class (def b: byte, def s: short, def l: long, def f: float, def c: char) (
    def sum = fn (x: long): long -> ( this.l + x + long(this.b) + long(this.s) )
)
def h = H(byte(3), short(9), 1000L, 2.25f, 'K')
assert(h.sum(50L) == 1062L and h.f == 2.25f and h.c == 'K')
h.b = byte(4); h.s = short(12); h.l = 2000L; h.f = 3.5f; h.c = 'Z'
assert(h.sum(1L) == 2017L and h.f == 3.5f and h.c == 'Z')
```

```hir
;; hir -O0,-O1 class=H
class H (b:byte, s:short, l:long, f:float, c:char)
  (def sum (fn-ref H.sum))
  fn H.sum (x:long) -> long {nothrow unique}
    (+:long (+:long (+:long (get-this-field l) var(x)) (call name(long) (get-this-field b))) (call name(long) (get-this-field s)))
```

```mir
;; mir -O2 class=H stage=before
class H:
  fn H.sum(v0: long) -> long:
    bb0:
      v1: any = slot_read this {pure}
      v2: long = load_field v1.l {read:shared,throws}
      v3: long = add v2, v0 {pure}
      v4: any = opaque.NameRead() {impure}
      v5: any = slot_read this {pure}
      v6: byte = load_field v5.b {read:shared,throws}
      v7: long = opaque.DynamicCall(v4, v6) {impure}
      v8: long = add v3, v7 {pure}
      v9: any = opaque.NameRead() {impure}
      v10: any = slot_read this {pure}
      v11: short = load_field v10.s {read:shared,throws}
      v12: long = opaque.DynamicCall(v9, v11) {impure}
      v13: long = add v8, v12 {pure}
      ret v13
```

```mir
;; mir -O2 class=H
class H:
  fn H.sum(v0: long) -> long:
    bb0:
      v1: any = slot_read this {pure}
      v2: long = load_field v1.l {read:shared,throws}
      v3: long = add v2, v0 {pure}
      v4: any = opaque.NameRead() {impure}
      v5: byte = load_field v1.b {read:shared,throws}
      v6: long = opaque.DynamicCall(v4, v5) {impure}
      v7: long = add v3, v6 {pure}
      v8: any = opaque.NameRead() {impure}
      v9: short = load_field v1.s {read:shared,throws}
      v10: long = opaque.DynamicCall(v8, v9) {impure}
      v11: long = add v7, v10 {pure}
      ret v11
```

## Case 9

```babble
def Scaler = interface (
    def scale : fn (x : long, factor : float) : double
)
def MyScaler = class (def base : long) implements Scaler (
    def scale = fn (x : long, factor : float) : double -> (
        double(this.base + x) * double(factor)
    )
)
def runScaler = fn (sc : Scaler) : double -> ( sc.scale(5L, 2.5f) )
assert(runScaler(MyScaler(3L)) == 20.0)
```

```hir
;; hir -O0,-O1 fn=runScaler
fn runScaler (sc:obj) -> double {pure unique}
  (call-iface var(sc).scale 5:long 2.5:float)
```

```mir
;; mir -O2 fn=runScaler stage=before,final
fn runScaler(v0: any) -> double:
  bb0:
    v1: long = const 5L
    v2: float = const 2.5f
    v3: double = call_iface v0.scale(v1, v2) {pure,throws}
    ret v3
```

## Case 10

```babble
def op : java.util.function.LongUnaryOperator = fn (x : long) : long -> ( x * 3L + 1L )
assert(op.applyAsLong(10L) == 31L)
def func : java.util.function.ToLongFunction of string = fn (s : string) : long -> ( long(s.length()) * 100L )
assert(func.applyAsLong("hello") == 500L)
```

```hir
;; hir -O0,-O1 fn=op
fn op (x:long) -> long {nothrow injective unique}
  (+:long (*:long var(x) 3:long) 1:long)
```

```mir
;; mir -O2 fn=op stage=before,final
fn op(v0: long) -> long:
  bb0:
    v1: long = const 3L
    v2: long = mul v0, v1 {pure}
    v3: long = const 1L
    v4: long = add v2, v3 {pure}
    ret v4
```

## Case 11

```babble
def unwrapAll = fn (b : byte?, s : short?, l : long?, f : float?, c : char?) : string -> (
    def ub : byte = b!; def us : short = s!; def ul : long = l!; def uf : float = f!; def uc : char = c!
    string(ub) + ":" + string(us) + ":" + string(ul) + ":" + string(uf) + ":" + string(uc)
)
assert(unwrapAll(byte(1), short(2), 3L, 4.5f, 'Q') == "1:2:3:4.5:Q")
```

```hir
;; hir -O0,-O1 fn=unwrapAll
fn unwrapAll (b:obj, s:obj, l:obj, f:obj, c:obj) -> obj {pure unique}
  (def ub (nonnull var(b)))
  (def us (nonnull var(s)))
  (def ul (nonnull var(l)))
  (def uf (nonnull var(f)))
  (def uc (nonnull var(c)))
  (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (call name(string) var(ub)) ":") (call name(string) var(us))) ":") (call name(string) var(ul))) ":") (call name(string) var(uf))) ":") (call name(string) var(uc)))
```

```mir
;; mir -O2 fn=unwrapAll stage=before
fn unwrapAll(v0: any, v1: any, v2: any, v3: any, v4: any) -> any:
  bb0:
    v5: byte = nonnull v0
    v6: short = nonnull v1
    v7: long = nonnull v2
    v8: float = nonnull v3
    v9: char = nonnull v4
    v10: any = opaque.NameRead() {impure}
    v11: any = opaque.DynamicCall(v10, v5) {impure}
    v12: any = const ":"
    v13: any = strcat v11, v12 {pure}
    v14: any = opaque.NameRead() {impure}
    v15: any = opaque.DynamicCall(v14, v6) {impure}
    v16: any = strcat v13, v15 {pure}
    v17: any = const ":"
    v18: any = strcat v16, v17 {pure}
    v19: any = opaque.NameRead() {impure}
    v20: any = opaque.DynamicCall(v19, v7) {impure}
    v21: any = strcat v18, v20 {pure}
    v22: any = const ":"
    v23: any = strcat v21, v22 {pure}
    v24: any = opaque.NameRead() {impure}
    v25: any = opaque.DynamicCall(v24, v8) {impure}
    v26: any = strcat v23, v25 {pure}
    v27: any = const ":"
    v28: any = strcat v26, v27 {pure}
    v29: any = opaque.NameRead() {impure}
    v30: any = opaque.DynamicCall(v29, v9) {impure}
    v31: any = strcat v28, v30 {pure}
    ret v31
```

```mir
;; mir -O2 fn=unwrapAll
fn unwrapAll(v0: any, v1: any, v2: any, v3: any, v4: any) -> any:
  bb0:
    v5: byte = nonnull v0
    v6: short = nonnull v1
    v7: long = nonnull v2
    v8: float = nonnull v3
    v9: char = nonnull v4
    v10: any = opaque.NameRead() {impure}
    v11: any = opaque.DynamicCall(v10, v5) {impure}
    v12: any = const ":"
    v13: any = strcat v11, v12 {pure}
    v14: any = opaque.NameRead() {impure}
    v15: any = opaque.DynamicCall(v14, v6) {impure}
    v16: any = strcat v13, v15 {pure}
    v17: any = strcat v16, v12 {pure}
    v18: any = opaque.NameRead() {impure}
    v19: any = opaque.DynamicCall(v18, v7) {impure}
    v20: any = strcat v17, v19 {pure}
    v21: any = strcat v20, v12 {pure}
    v22: any = opaque.NameRead() {impure}
    v23: any = opaque.DynamicCall(v22, v8) {impure}
    v24: any = strcat v21, v23 {pure}
    v25: any = strcat v24, v12 {pure}
    v26: any = opaque.NameRead() {impure}
    v27: any = opaque.DynamicCall(v26, v9) {impure}
    v28: any = strcat v25, v27 {pure}
    ret v28
```

## Case 12

```babble
def capParams = fn (b : byte, s : short, l : long, f : float, c : char) -> (
    fn () : string -> ( string(b) + ":" + string(s) + ":" + string(l) + ":" + string(f) + ":" + string(c) )
)
assert(capParams(byte(1), short(2), 3L, 4.5f, 'Z')() == "1:2:3:4.5:Z")
```

```hir
;; hir -O0,-O1 fn=capParams
fn capParams (b:byte, s:short, l:long, f:float, c:char) -> obj {nothrow}
  (fn-ref <anon#0>)
```

```mir
;; mir -O2 fn=capParams stage=before,final
fn capParams() -> any:
  bb0:
    v0: any = opaque.FunctionRef() {read:shared}
    ret v0
```
