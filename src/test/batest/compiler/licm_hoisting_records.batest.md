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
  def a = 0.0 ; def i = 0 ; def c = 1 ; def bump = fn io (): int -> (c += 1 ; c)
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
      (if (and var(f) false:bool) (block (set a += (call name(double) (call-fn bump)))))
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
      (if (and var(f) false:bool) (set a += (call name(double) (call-fn bump))))
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
    v6: any = opaque.FunctionRef() {read:shared}
    v7: any = const null
    br bb1(v7, v4, v3)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: any = opaque.NameRead() {impure}
    v13: int = const 2
    v14: any = opaque.DynamicCall(v12, v13) {impure}
    v15: any = checkcast v14 <double|double|>
    v16: double = convert.double v15
    v17: any = opaque.NameRead() {impure}
    v18: any = opaque.DynamicCall(v17, v1) {impure}
    v19: int = convert.int v18
    v20: int = const 1
    v21: int = add v19, v20 {pure}
    v22: any = opaque.NameRead() {impure}
    v23: any = opaque.DynamicCall(v22, v21) {impure}
    v24: double = convert.double v23
    v25: double = add v16, v24 {pure}
    v26: double = add v10, v25 {pure}
    v27: bool = const false
    br_if v2, bb4, bb5(v27)
  bb4:
    v28: bool = const false
    br bb5(v28)
  bb5(v29: bool):
    br_if v29, bb7, bb6
  bb6:
    v30: any = const null
    br bb8(v30, v26)
  bb7:
    v31: any = opaque.NameRead() {impure}
    v32: int = call bump() {impure}
    v33: any = opaque.DynamicCall(v31, v32) {impure}
    v34: double = convert.double v33
    v35: double = add v26, v34 {pure}
    v36: any = convert.any v35
    br bb8(v36, v35)
  bb8(v37: any, v38: double):
    v39: int = const 1
    v40: int = add v9, v39 {pure}
    v41: any = convert.any v40
    br bb1(v41, v40, v38)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: byte, v2: bool) -> double:
  bb0:
    v3: double = const 0.0
    v4: int = const 0
    v5: int = const 1
    slot_init c, v5 {write:shared}
    br bb1(v4, v3)
  bb1(v6: int, v7: double):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: any = opaque.NameRead() {impure}
    v10: int = const 2
    v11: any = opaque.DynamicCall(v9, v10) {impure}
    v12: any = checkcast v11 <double|double|>
    v13: double = convert.double v12
    v14: any = opaque.NameRead() {impure}
    v15: any = opaque.DynamicCall(v14, v1) {impure}
    v16: int = convert.int v15
    v17: int = add v16, v5 {pure}
    v18: any = opaque.NameRead() {impure}
    v19: any = opaque.DynamicCall(v18, v17) {impure}
    v20: double = convert.double v19
    v21: double = add v13, v20 {pure}
    v22: double = add v7, v21 {pure}
    br bb4
  bb4:
    br bb5(v22)
  bb5(v23: double):
    v24: int = add v6, v5 {pure}
    br bb1(v24, v23)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 1..3 (short/int/long)

```babble
def RV = record (a: byte, i: int) (
  def run = fn (n: int): long -> (
    def r = 0L ; def k = 0
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
      (def hs (call-fn bigByte (get-this-field a)))
      (def hi (call-fn bigByte (get-this-field a)))
      (def hl (call-fn bigInt (get-this-field i)))
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
        (def hs var($licm0))
        (def hi var($licm1))
        (def hl var($licm2))
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
    v18: any = opaque.NameRead() {impure}
    v19: any = opaque.DynamicCall(v18, v14) {impure}
    v20: long = convert.long v19
    v21: any = opaque.NameRead() {impure}
    v22: any = opaque.DynamicCall(v21, v15) {impure}
    v23: long = convert.long v22
    v24: long = add v20, v23 {pure}
    v25: long = add v24, v16 {pure}
    v26: any = convert.any v25
    v27: any = add v17, v26 {impure}
    v28: long = convert.long v27
    v29: int = const 1
    v30: int = add v11, v29 {pure}
    v31: any = convert.any v30
    br bb1(v31, v30, v28)
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
    br bb1(v2, v1)
  bb1(v9: int, v10: long):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: any = convert.any v10
    v13: any = opaque.NameRead() {impure}
    v14: any = opaque.DynamicCall(v13, v6) {impure}
    v15: long = convert.long v14
    v16: any = opaque.NameRead() {impure}
    v17: any = opaque.DynamicCall(v16, v7) {impure}
    v18: long = convert.long v17
    v19: long = add v15, v18 {pure}
    v20: long = add v19, v8 {pure}
    v21: any = convert.any v20
    v22: any = add v12, v21 {impure}
    v23: long = convert.long v22
    v24: int = const 1
    v25: int = add v9, v24 {pure}
    br bb1(v25, v23)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 4..5 (float/double)

```babble
def RV = record (i: int, fl: float) (
  def run = fn (n: int): double -> (
    def r = 0.0 ; def k = 0
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
      (def hf (call-fn bigInt (get-this-field i)))
      (def hd (call-fn bigFloat (get-this-field fl)))
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
        (def hf var($licm0))
        (def hd var($licm1))
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
    v14: any = opaque.NameRead() {impure}
    v15: any = opaque.DynamicCall(v14, v12) {impure}
    v16: double = convert.double v15
    v17: double = add v16, v13 {pure}
    v18: double = add v10, v17 {pure}
    v19: int = const 1
    v20: int = add v9, v19 {pure}
    v21: any = convert.any v20
    br bb1(v21, v20, v18)
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
    br bb1(v2, v1)
  bb1(v8: int, v9: double):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: any = opaque.NameRead() {impure}
    v12: any = opaque.DynamicCall(v11, v6) {impure}
    v13: double = convert.double v12
    v14: double = add v13, v7 {pure}
    v15: double = add v9, v14 {pure}
    v16: int = const 1
    v17: int = add v8, v16 {pure}
    br bb1(v17, v15)
```

## Record-based LICM : while loop with fnDyn and Logic + frozen array.size

```babble
def RV = record (d: double, f: bool, xs: frozen array of int) (
  def run = fn (n: int): double -> (
    def a = 0.0 ; def k = 0 ; def fnD = fn (v: double): double -> (java.lang.Math.sin(v))
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
  (def fnD (fn-ref fnD))
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:double (block
            (def v (get-this-field d))
            (jcall Math.sin var(v))) (if (and (get-this-field f) (>:bool (array-op SIZE (get-this-field xs)) 0:int)) 1.0:double 2.0:double)))
      (set k += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = opaque.FunctionRef() {pure}
    v4: any = const null
    br bb1(v4, v2, v1)
  bb1(v5: any, v6: int, v7: double):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: any = slot_read this {pure}
    v10: double = load_field v9.d {read:frozen}
    v11: double = call_java Math.sin(v10) {pure}
    v12: any = slot_read this {pure}
    v13: bool = load_field v12.f {read:frozen}
    v14: bool = const false
    br_if v13, bb4, bb5(v14)
  bb4:
    v15: any = slot_read this {pure}
    v16: any = load_field v15.xs {read:frozen}
    v17: int = array_len v16 {read:frozen,throws}
    v18: int = const 0
    v19: bool = gt v17, v18 {pure}
    br bb5(v19)
  bb5(v20: bool):
    br_if v20, bb7, bb6
  bb6:
    v21: double = const 2.0
    br bb8(v21)
  bb7:
    v22: double = const 1.0
    br bb8(v22)
  bb8(v23: double):
    v24: double = add v11, v23 {pure}
    v25: double = add v7, v24 {pure}
    v26: int = const 1
    v27: int = add v6, v26 {pure}
    v28: any = convert.any v27
    br bb1(v28, v27, v25)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    br bb1(v2, v1)
  bb1(v3: int, v4: double):
    v5: bool = lt v3, v0 {pure}
    br_if v5, bb3, bb2
  bb2:
    ret v4
  bb3:
    v6: any = slot_read this {pure}
    v7: double = load_field v6.d {read:frozen}
    v8: double = call_java Math.sin(v7) {pure}
    v9: bool = load_field v6.f {read:frozen}
    v10: bool = const false
    br_if v9, bb4, bb5(v10)
  bb4:
    v11: any = load_field v6.xs {read:frozen}
    v12: int = array_len v11 {read:frozen,throws}
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
    v18: double = add v8, v17 {pure}
    v19: double = add v4, v18 {pure}
    v20: int = const 1
    v21: int = add v3, v20 {pure}
    br bb1(v21, v19)
```

## Record-based LICM : for loop over frozen array hoisting Math.sin(this.d)

```babble
def RV = record (d: double, arr: frozen array of int) (
  def run = fn (): double -> (
    def a = 0.0
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
    v15: any = opaque.NameRead() {impure}
    v16: any = opaque.DynamicCall(v15, v14) {impure}
    v17: double = convert.double v16
    v18: double = add v3, v17 {pure}
    v19: double = add v12, v18 {pure}
    v20: any = convert.any v19
    br bb4
  bb4:
    v21: int = const 1
    v22: int = add v11, v21 {pure}
    br bb1(v20, v22, v19)
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
    br bb1(v7, v0)
  bb1(v8: int, v9: double):
    v10: bool = for_has_next v5, v8, v6
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: int = for_next v5, v8
    v12: any = opaque.NameRead() {impure}
    v13: any = opaque.DynamicCall(v12, v11) {impure}
    v14: double = convert.double v13
    v15: double = add v3, v14 {pure}
    v16: double = add v9, v15 {pure}
    br bb4
  bb4:
    v17: int = const 1
    v18: int = add v8, v17 {pure}
    br bb1(v18, v16)
```
