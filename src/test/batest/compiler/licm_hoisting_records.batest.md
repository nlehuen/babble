# LICM Hoisting : Record Fields, As-Widening, and Frozen Arrays

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigByte = fn nothrow (x: byte): byte -> (return x ; x)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigFloat = fn nothrow (x: float): float -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
def bigImpl = fn (x: double): double -> (def s = java.lang.Math.sin(x) ; return s ; s)
def nonPureThrow = fn (x: int): double -> (def q = 10 / x ; return double(q) ; double(q))
```



## Class-based LICM hoisting : As widening, Block, and dead cell-bump branch

```babble
def run = fn io (n: int, b: byte, f: bool): double -> (
  var a = 0.0 ; var i = 0 ; var c = 1 ; def bump = fn io (): int -> (c += 1 ; c)
  while i < n then (
    a += (byte(2) as double) + (def t = int(b) + 1 ; double(t))
    if f and false then (a += double(bump())) ; i += 1
  )
  a
)
assert(run(2, byte(2), true) == 10.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, b:byte, f:bool) -> double {io}
  (def a 0.0:double)
  (def i 0:int)
  (def c 1:int)
  (def bump (fn-ref bump))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (block (as (call name(byte) 2:int) double|double|)) (block
            (def t (+:int (call name(int) var(b)) 1:int))
            (call name(double) var(t)))))
      (if (and var(f) false:bool) (box (block (set a += (call name(double) (call-fn bump))))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, b:byte, f:bool) -> double {io}
  (def a 0.0:double)
  (def i 0:int)
  (def c 1:int)
  (def bump (fn-ref bump))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (as (call name(byte) 2:int) double|double|) (block
            (def t (+:int (call name(int) var(b)) 1:int))
            (call name(double) var(t)))))
      (if (and var(f) false:bool) (box (set a += (call name(double) (call-fn bump)))))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: byte, v2: bool) -> double:
  bb0:
    v3: double = const 0.0
    v4: int = const 0
    v5: int = const 1
    slot_init c, v5 {write:shared}
    v6: any = opaque.FunctionRef(bump) {read:shared}
    v7: any = const null
    br bb1(v7, v4, v3)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: any = name_read byte {pure}
    v13: int = const 2
    v14: byte = opaque.DynamicCall(v12, v13) {impure}
    v15: any = convert.any v14
    v16: any = checkcast v15 <double|double|>
    v17: double = convert.double v16
    v18: any = name_read int {pure}
    v19: int = opaque.DynamicCall(v18, v1) {impure}
    v20: int = const 1
    v21: int = add v19, v20 {pure}
    v22: any = name_read double {pure}
    v23: double = opaque.DynamicCall(v22, v21) {impure}
    v24: double = add v17, v23 {pure}
    v25: double = add v10, v24 {pure}
    v26: bool = const false
    br_if v2, bb4, bb5(v26)
  bb4:
    v27: bool = const false
    br bb5(v27)
  bb5(v28: bool):
    br_if v28, bb7, bb6
  bb6:
    v29: any = const null
    br bb8(v29, v25)
  bb7:
    v30: any = name_read double {pure}
    v31: int = call bump() {impure}
    v32: double = opaque.DynamicCall(v30, v31) {impure}
    v33: double = add v25, v32 {pure}
    v34: any = convert.any v33
    br bb8(v34, v33)
  bb8(v35: any, v36: double):
    v37: int = const 1
    v38: int = add v9, v37 {pure}
    v39: any = convert.any v38
    br bb1(v39, v38, v36)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: byte, v2: bool) -> double:
  bb0:
    v3: double = const 0.0
    v4: int = const 0
    v5: int = const 1
    slot_init c, v5 {write:shared}
    v6: any = name_read byte {pure}
    v7: any = name_read int {pure}
    v8: any = name_read double {pure}
    br bb1(v4, v3)
  bb1(v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: int = const 2
    v13: byte = opaque.DynamicCall(v6, v12) {impure}
    v14: any = convert.any v13
    v15: any = checkcast v14 <double|double|>
    v16: double = convert.double v15
    v17: int = opaque.DynamicCall(v7, v1) {impure}
    v18: int = add v17, v5 {pure}
    v19: double = opaque.DynamicCall(v8, v18) {impure}
    v20: double = add v16, v19 {pure}
    v21: double = add v10, v20 {pure}
    br bb4
  bb4:
    br bb5(v21)
  bb5(v22: double):
    v23: int = add v9, v5 {pure}
    br bb1(v23, v22)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 1..3 (short/int/long)

```babble
def RV = record (a: byte, i: int) (
  def run = fn (n: int): long -> (
    var r = 0L ; var k = 0
    while k < n then (
      def hs: short = bigByte(this.a) ; def hi: int = bigByte(this.a) ; def hl: long = bigInt(this.i)
      r += long(hs) + long(hi) + hl ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 3).run(2) == 14L)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> long {nothrow unique}
  (def r 0:long)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (def hs (widen[short] (call-fn bigByte (get-this-field a))))
      (def hi (widen[int] (call-fn bigByte (get-this-field a))))
      (def hl (widen[long] (call-fn bigInt (get-this-field i))))
      (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> long {nothrow unique}
  (def r 0:long)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def x#1 (get-this-field a))
        var(x#1)))
    (def $licm1
      (block
        (def x#2 (get-this-field a))
        var(x#2)))
    (def $licm2
      (block
        (def x#3 (get-this-field i))
        var(x#3)))
    (while (<:bool var(k) var(n))
      (block
        (def hs (widen[short] var($licm0)))
        (def hi (widen[int] var($licm1)))
        (def hl (widen[long] var($licm2)))
        (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
        (set k += 1:int))))
  var(r)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run(v0: int) -> long:
  bb0:
    v1: long = const 0L
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: byte = load_field v3.a {read:frozen}
    v5: any = slot_read this {pure}
    v6: byte = load_field v5.a {read:frozen}
    v7: any = slot_read this {pure}
    v8: int = load_field v7.i {read:frozen}
    v9: any = const null
    br bb1(v9, v2, v1)
  bb1(v10: any, v11: int, v12: long):
    v13: bool = lt v11, v0 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v12
  bb3:
    v14: short = convert.short v4
    v15: int = convert.int v6
    v16: long = convert.long v8
    v17: any = convert.any v12
    v18: any = name_read long {pure}
    v19: long = opaque.DynamicCall(v18, v14) {impure}
    v20: any = name_read long {pure}
    v21: long = opaque.DynamicCall(v20, v15) {impure}
    v22: long = add v19, v21 {pure}
    v23: long = add v22, v16 {pure}
    v24: any = convert.any v23
    v25: any = add v17, v24 {impure}
    v26: long = convert.long v25
    v27: int = const 1
    v28: int = add v11, v27 {pure}
    v29: any = convert.any v28
    br bb1(v29, v28, v26)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> long:
  bb0:
    v1: long = const 0L
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: byte = load_field v3.a {read:frozen}
    v5: int = load_field v3.i {read:frozen}
    v6: short = convert.short v4
    v7: int = convert.int v4
    v8: long = convert.long v5
    v9: any = name_read long {pure}
    br bb1(v2, v1)
  bb1(v10: int, v11: long):
    v12: bool = lt v10, v0 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v11
  bb3:
    v13: any = convert.any v11
    v14: long = opaque.DynamicCall(v9, v6) {impure}
    v15: long = opaque.DynamicCall(v9, v7) {impure}
    v16: long = add v14, v15 {pure}
    v17: long = add v16, v8 {pure}
    v18: any = convert.any v17
    v19: any = add v13, v18 {impure}
    v20: long = convert.long v19
    v21: int = const 1
    v22: int = add v10, v21 {pure}
    br bb1(v22, v20)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 4..5 (float/double)

```babble
def RV = record (i: int, fl: float) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
    while k < n then (
      def hf: float = bigInt(this.i) ; def hd: double = bigFloat(this.fl)
      r += double(hf) + hd ; k += 1
    )
    r
  )
)
assert(RV(3, 1.5f).run(2) == 9.0)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (def hf (widen[float] (call-fn bigInt (get-this-field i))))
      (def hd (widen[double] (call-fn bigFloat (get-this-field fl))))
      (set r += (+:double (call name(double) var(hf)) var(hd)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def x#1 (get-this-field i))
        var(x#1)))
    (def $licm1
      (block
        (def x#2 (get-this-field fl))
        var(x#2)))
    (while (<:bool var(k) var(n))
      (block
        (def hf (widen[float] var($licm0)))
        (def hd (widen[double] var($licm1)))
        (set r += (+:double (call name(double) var(hf)) var(hd)))
        (set k += 1:int))))
  var(r)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: int = load_field v3.i {read:frozen}
    v5: any = slot_read this {pure}
    v6: float = load_field v5.fl {read:frozen}
    v7: any = const null
    br bb1(v7, v2, v1)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: float = convert.float v4
    v13: double = convert.double v6
    v14: any = name_read double {pure}
    v15: double = opaque.DynamicCall(v14, v12) {impure}
    v16: double = add v15, v13 {pure}
    v17: double = add v10, v16 {pure}
    v18: int = const 1
    v19: int = add v9, v18 {pure}
    v20: any = convert.any v19
    br bb1(v20, v19, v17)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: int = load_field v3.i {read:frozen}
    v5: float = load_field v3.fl {read:frozen}
    v6: float = convert.float v4
    v7: double = convert.double v5
    v8: any = name_read double {pure}
    br bb1(v2, v1)
  bb1(v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: double = opaque.DynamicCall(v8, v6) {impure}
    v13: double = add v12, v7 {pure}
    v14: double = add v10, v13 {pure}
    v15: int = const 1
    v16: int = add v9, v15 {pure}
    br bb1(v16, v14)
```

## Record-based LICM : while loop with fnDyn and Logic + frozen array.size

```babble
def RV = record (d: double, f: bool, xs: frozen array of int) (
  def run = fn (n: int): double -> (
    var a = 0.0 ; var k = 0 ; def fnD = fn (v: double): double -> (java.lang.Math.sin(v))
    while k < n then (a += fnD(this.d) + (if this.f and (this.xs.size() > 0) then (1.0) else (2.0)) ; k += 1) ; a
  )
)
assert(java.lang.Math.round(RV(1.0, true, freeze(array.of(5))).run(2) * 100.0) == 368L)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def k 0:int)
  (def fnD (fn-ref fnD))
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:double (call-fn fnD (get-this-field d)) (block (if (and (get-this-field f) (block (>:bool (array-op SIZE (get-this-field xs)) 0:int))) (block 1.0:double) (block 2.0:double)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field d))
    (def $licm1 (get-this-field f))
    (def $licm2 (get-this-field xs))
    (while (<:bool var(k) var(n))
      (block
        (set a += (+:double (block
              (def v var($licm0))
              (jcall Math.sin var(v))) (if (and var($licm1) (>:bool (array-op SIZE var($licm2)) 0:int)) 1.0:double 2.0:double)))
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: double = load_field v3.d {read:frozen}
    v5: any = slot_read this {pure}
    v6: bool = load_field v5.f {read:frozen}
    v7: any = slot_read this {pure}
    v8: any = load_field v7.xs {read:frozen}
    v9: any = const null
    br bb1(v9, v2, v1)
  bb1(v10: any, v11: int, v12: double):
    v13: bool = lt v11, v0 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v12
  bb3:
    v14: double = call_java Math.sin(v4) {pure}
    v15: bool = const false
    br_if v6, bb4, bb5(v15)
  bb4:
    v16: int = array_len v8 {read:frozen,throws}
    v17: int = const 0
    v18: bool = gt v16, v17 {pure}
    br bb5(v18)
  bb5(v19: bool):
    br_if v19, bb7, bb6
  bb6:
    v20: double = const 2.0
    br bb8(v20)
  bb7:
    v21: double = const 1.0
    br bb8(v21)
  bb8(v22: double):
    v23: double = add v14, v22 {pure}
    v24: double = add v12, v23 {pure}
    v25: int = const 1
    v26: int = add v11, v25 {pure}
    v27: any = convert.any v26
    br bb1(v27, v26, v24)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: double = load_field v3.d {read:frozen}
    v5: bool = load_field v3.f {read:frozen}
    v6: any = load_field v3.xs {read:frozen}
    v7: double = call_java Math.sin(v4) {pure}
    br bb1(v2, v1)
  bb1(v8: int, v9: double):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: bool = const false
    br_if v5, bb4, bb5(v11)
  bb4:
    v12: int = array_len v6 {read:frozen,throws}
    v13: bool = gt v12, v2 {pure}
    br bb5(v13)
  bb5(v14: bool):
    br_if v14, bb7, bb6
  bb6:
    v15: double = const 2.0
    br bb8(v15)
  bb7:
    v16: double = const 1.0
    br bb8(v16)
  bb8(v17: double):
    v18: double = add v7, v17 {pure}
    v19: double = add v9, v18 {pure}
    v20: int = const 1
    v21: int = add v8, v20 {pure}
    br bb1(v21, v19)
```

## Record-based LICM : for loop over frozen array hoisting Math.sin(this.d)

```babble
def RV = record (d: double, arr: frozen array of int) (
  def run = fn (): double -> (
    var a = 0.0
    for e in this.arr then (a += java.lang.Math.sin(this.d) + double(e))
    a
  )
)
assert(java.lang.Math.round(RV(1.0, freeze(array.of(5, 6))).run() * 100.0) == 1268L)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run () -> double {nothrow unique}
  (def a 0.0:double)
  (for e in (get-this-field arr)
    (block
      (set a += (+:double (jcall Math.sin (get-this-field d)) (call name(double) var(e))))))
  var(a)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run () -> double {nothrow unique}
  (def a 0.0:double)
  (block
    (def $licm0 (jcall Math.sin (get-this-field d)))
    (for e in (get-this-field arr)
      (block
        (set a += (+:double var($licm0) (call name(double) var(e)))))))
  var(a)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run() -> double:
  bb0:
    v0: double = const 0.0
    v1: any = slot_read this {pure}
    v2: double = load_field v1.d {read:frozen}
    v3: double = call_java Math.sin(v2) {pure}
    v4: any = slot_read this {pure}
    v5: any = load_field v4.arr {read:frozen}
    v6: any = for_init.array v5
    v7: any = const null
    v8: int = array_len v6 {read:frozen}
    v9: int = const 0
    br bb1(v7, v9, v0)
  bb1(v10: any, v11: int, v12: double):
    v13: bool = for_has_next v6, v11, v8
    br_if v13, bb3, bb2
  bb2:
    ret v12
  bb3:
    v14: int = for_next v6, v11
    v15: any = name_read double {pure}
    v16: double = opaque.DynamicCall(v15, v14) {impure}
    v17: double = add v3, v16 {pure}
    v18: double = add v12, v17 {pure}
    v19: any = convert.any v18
    br bb4
  bb4:
    v20: int = const 1
    v21: int = add v11, v20 {pure}
    br bb1(v19, v21, v18)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run() -> double:
  bb0:
    v0: double = const 0.0
    v1: any = slot_read this {pure}
    v2: double = load_field v1.d {read:frozen}
    v3: double = call_java Math.sin(v2) {pure}
    v4: any = load_field v1.arr {read:frozen}
    v5: any = for_init.array v4
    v6: int = array_len v5 {read:frozen}
    v7: int = const 0
    v8: any = name_read double {pure}
    br bb1(v7, v0)
  bb1(v9: int, v10: double):
    v11: bool = for_has_next v5, v9, v6
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: int = for_next v5, v9
    v13: double = opaque.DynamicCall(v8, v12) {impure}
    v14: double = add v3, v13 {pure}
    v15: double = add v10, v14 {pure}
    br bb4
  bb4:
    v16: int = const 1
    v17: int = add v9, v16 {pure}
    br bb1(v17, v15)
```
