# LICM Hoisting : Loops and Class Field Invariance

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



## While loop hoisting : record fields, Math.hypot, unary negate, logic + array.size, if, and block

```babble
def Vec = record (x: double, y: double) ()
def run = fn (n: int, v: Vec, xs: array of int, f: bool): double -> (
  def a = 0.0 ; def i = 0
  while i < n then (
    def c = if f and (xs.size() > 0) then (1.5) else (2.5)
    a += java.lang.Math.hypot(v.x, v.y) + (-v.y) + c + (def t = v.x * 2.0 ; t) ; i += 1
  )
  a
)
assert(run(3, Vec(3.0, 4.0), array.of(1, 2), true) == 25.5)
```

```hir
;; hir -O0 fn=run
fn run (n:int, v:obj, xs:obj, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def c (if (and var(f) (block (>:bool (array-op SIZE var(xs)) 0:int))) (block 1.5:double) (block 2.5:double)))
      (set a += (+:double (+:double (+:double (jcall Math.hypot (get-this-field x) (get-this-field y)) (block (neg (get-this-field y)))) var(c)) (block
            (def t (*:double (get-this-field x) 2.0:double))
            var(t))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, v:obj, xs:obj, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (+:double (jcall Math.hypot (get-this-field x) (get-this-field y)) (neg (get-this-field y))))
    (def $licm1 (*:double (get-this-field x) 2.0:double))
    (while (<:bool var(i) var(n))
      (block
        (def c (if (and var(f) (>:bool (array-op SIZE var(xs)) 0:int)) 1.5:double 2.5:double))
        (set a += (+:double (+:double var($licm0) var(c)) (block
              (def t var($licm1))
              var(t))))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any, v2: any, v3: bool) -> double:
  bb0:
    v4: double = const 0.0
    v5: int = const 0
    v6: double = load_field v1.x {read:frozen}
    v7: double = load_field v1.y {read:frozen}
    v8: double = call_java Math.hypot(v6, v7) {pure}
    v9: double = load_field v1.y {read:frozen}
    v10: double = neg v9 {pure}
    v11: double = add v8, v10 {pure}
    v12: double = load_field v1.x {read:frozen}
    v13: double = const 2.0
    v14: double = mul v12, v13 {pure}
    v15: any = const null
    br bb1(v15, v5, v4)
  bb1(v16: any, v17: int, v18: double):
    v19: bool = lt v17, v0 {pure}
    br_if v19, bb3, bb2
  bb2:
    ret v18
  bb3:
    v20: bool = const false
    br_if v3, bb4, bb5(v20)
  bb4:
    v21: int = array_len v2 {read:frozen,throws}
    v22: int = const 0
    v23: bool = gt v21, v22 {pure}
    br bb5(v23)
  bb5(v24: bool):
    br_if v24, bb7, bb6
  bb6:
    v25: double = const 2.5
    br bb8(v25)
  bb7:
    v26: double = const 1.5
    br bb8(v26)
  bb8(v27: double):
    v28: double = add v11, v27 {pure}
    v29: double = add v28, v14 {pure}
    v30: double = add v18, v29 {pure}
    v31: int = const 1
    v32: int = add v17, v31 {pure}
    v33: any = convert.any v32
    br bb1(v33, v32, v30)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any, v2: any, v3: bool) -> double:
  bb0:
    v4: double = const 0.0
    v5: int = const 0
    v6: double = load_field v1.x {read:frozen}
    v7: double = load_field v1.y {read:frozen}
    v8: double = call_java Math.hypot(v6, v7) {pure}
    v9: double = neg v7 {pure}
    v10: double = add v8, v9 {pure}
    v11: double = const 2.0
    v12: double = mul v6, v11 {pure}
    br bb1(v5, v4)
  bb1(v13: int, v14: double):
    v15: bool = lt v13, v0 {pure}
    br_if v15, bb3, bb2
  bb2:
    ret v14
  bb3:
    v16: bool = const false
    br_if v3, bb4, bb5(v16)
  bb4:
    v17: int = array_len v2 {read:frozen,throws}
    v18: bool = gt v17, v5 {pure}
    br bb5(v18)
  bb5(v19: bool):
    br_if v19, bb7, bb6
  bb6:
    v20: double = const 2.5
    br bb8(v20)
  bb7:
    v21: double = const 1.5
    br bb8(v21)
  bb8(v22: double):
    v23: double = add v10, v22 {pure}
    v24: double = add v23, v12 {pure}
    v25: double = add v14, v24 {pure}
    v26: int = const 1
    v27: int = add v13, v26 {pure}
    br bb1(v27, v25)
```

## For loop hoisting : pure calls, record method, and invariant negation

```babble
def Vec = record (x: double, y: double) (
  def len = fn nothrow (): double -> (java.lang.Math.hypot(this.x, this.y))
)
def run = fn (v: Vec, xs: array of int): double -> (
  def a = 0.0
  for e in xs then (a += java.lang.Math.sin(v.y) + double(e) + (if e < 0 then (-v.x) else (0.0)))
  a + v.len()
)
assert(java.lang.Math.round(run(Vec(3.0, 4.0), array.of(1, 2)) * 100.0) == 649L)
```

```hir
;; hir -O0 fn=run
fn run (v:obj, xs:obj) -> double {nothrow unique}
  (def a 0.0:double)
  (for e in var(xs)
    (block
      (set a += (+:double (+:double (jcall Math.sin (get-this-field y)) (call name(double) var(e))) (block (if (<:bool var(e) 0:int) (block (neg (get-this-field x))) (block 0.0:double)))))))
  (+:double var(a) (call-method var(v).len))
```

```hir
;; hir -O1 fn=run
fn run (v:obj, xs:obj) -> double {nothrow unique}
  (def a 0.0:double)
  (block
    (def $licm0 (jcall Math.sin (get-this-field y)))
    (def $licm1 (neg (get-this-field x)))
    (for e in var(xs)
      (block
        (set a += (+:double (+:double var($licm0) (call name(double) var(e))) (if (<:bool var(e) 0:int) var($licm1) 0.0:double))))))
  (+:double var(a) (jcall Math.hypot (get-this-field x) (get-this-field y)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any) -> double:
  bb0:
    v2: double = const 0.0
    v3: double = load_field v0.y {read:frozen}
    v4: double = call_java Math.sin(v3) {pure}
    v5: double = load_field v0.x {read:frozen}
    v6: double = neg v5 {pure}
    v7: any = for_init.array v1
    v8: any = const null
    v9: int = array_len v7 {read:frozen}
    v10: int = const 0
    br bb1(v8, v10, v2)
  bb1(v11: any, v12: int, v13: double):
    v14: bool = for_has_next v7, v12, v9
    br_if v14, bb3, bb2
  bb2:
    v15: double = load_field v0.x {read:frozen}
    v16: double = load_field v0.y {read:frozen}
    v17: double = call_java Math.hypot(v15, v16) {pure}
    v18: double = add v13, v17 {pure}
    ret v18
  bb3:
    v19: int = for_next v7, v12
    v20: any = opaque.NameRead() {impure}
    v21: double = opaque.DynamicCall(v20, v19) {impure}
    v22: double = add v4, v21 {pure}
    v23: int = const 0
    v24: bool = lt v19, v23 {pure}
    br_if v24, bb5, bb4
  bb4:
    v25: double = const 0.0
    br bb6(v25)
  bb5:
    br bb6(v6)
  bb6(v26: double):
    v27: double = add v22, v26 {pure}
    v28: double = add v13, v27 {pure}
    v29: any = convert.any v28
    br bb7
  bb7:
    v30: int = const 1
    v31: int = add v12, v30 {pure}
    br bb1(v29, v31, v28)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any) -> double:
  bb0:
    v2: double = const 0.0
    v3: double = load_field v0.y {read:frozen}
    v4: double = call_java Math.sin(v3) {pure}
    v5: double = load_field v0.x {read:frozen}
    v6: double = neg v5 {pure}
    v7: any = for_init.array v1
    v8: int = array_len v7 {read:frozen}
    v9: int = const 0
    br bb1(v9, v2)
  bb1(v10: int, v11: double):
    v12: bool = for_has_next v7, v10, v8
    br_if v12, bb3, bb2
  bb2:
    v13: double = call_java Math.hypot(v5, v3) {pure}
    v14: double = add v11, v13 {pure}
    ret v14
  bb3:
    v15: int = for_next v7, v10
    v16: any = opaque.NameRead() {impure}
    v17: double = opaque.DynamicCall(v16, v15) {impure}
    v18: double = add v4, v17 {pure}
    v19: bool = lt v15, v9 {pure}
    br_if v19, bb5, bb4
  bb4:
    br bb6(v2)
  bb5:
    br bb6(v6)
  bb6(v20: double):
    v21: double = add v18, v20 {pure}
    v22: double = add v11, v21 {pure}
    br bb7
  bb7:
    v23: int = const 1
    v24: int = add v10, v23 {pure}
    br bb1(v24, v22)
```

## Throwing and impure calls must not be hoisted out of loops

```babble
def tickCount = 0
def tick = fn io (): int -> (tickCount += 1 ; tickCount)
def unsafeDiv = 0 ; def j = 0
while j < 0 then (unsafeDiv += java.lang.Math.floorDiv(10, j) ; j += 1)
def ticks = 0 ; def k = 0
while k < 3 then (ticks += tick() ; k += 1)
assert(unsafeDiv == 0 and ticks == 6 and tickCount == 3)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def bigByte (fn-ref bigByte))
  (def bigInt (fn-ref bigInt))
  (def bigFloat (fn-ref bigFloat))
  (def bigSink (fn-ref bigSink))
  (def bigImpl (fn-ref bigImpl))
  (def nonPureThrow (fn-ref nonPureThrow))
  (def tickCount 0:int)
  (def tick (fn-ref tick))
  (def unsafeDiv 0:int)
  (def j 0:int)
  (while (<:bool name(j) 0:int)
    (block
      (set-name unsafeDiv += (jcall Math.floorDiv 10:int name(j)))
      (set-name j += 1:int)))
  (def ticks 0:int)
  (def k 0:int)
  (while (<:bool name(k) 3:int)
    (block
      (set-name ticks += (call-fn tick))
      (set-name k += 1:int)))
  (call name(assert) (and (and (==:bool name(unsafeDiv) 0:int) (==:bool name(ticks) 6:int)) (==:bool name(tickCount) 3:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Class-based LICM hoisting across widening ranks 1..4 (short, int, long, float)

```babble
def Box = class (def x: double) (
  def run = fn (n: int, b: byte): double -> (
    def a = 0.0 ; def i = 0
    while i < n then (
      def l1: short = b ; def l2: int = short(4) + int(b)
      def l3: long = int(5) + 1 ; def l4: float = 1.5f + 2.5f
      a += double(l1) + double(l2) + double(l3) + double(l4) ; i += 1
    )
    a
  )
)
assert(Box(1.0).run(2, byte(2)) == 36.0)
```

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int, b:byte) -> double {pure unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def l1 var(b))
      (def l2 (+:int (call name(short) 4:int) (call name(int) var(b))))
      (def l3 (+:int (call name(int) 5:int) 1:int))
      (def l4 (+:float 1.5:float 2.5:float))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) var(l4))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int, b:byte) -> double {pure unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def l1 var(b))
      (def l2 (+:int (call name(short) 4:int) (call name(int) var(b))))
      (def l3 (+:int (call name(int) 5:int) 1:int))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) 4.0:float)))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=Box.run stage=before
fn Box.run(v0: int, v1: byte) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: double):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: short = convert.short v1
    v10: any = opaque.NameRead() {impure}
    v11: int = const 4
    v12: short = opaque.DynamicCall(v10, v11) {impure}
    v13: int = convert.int v12
    v14: any = opaque.NameRead() {impure}
    v15: int = opaque.DynamicCall(v14, v1) {impure}
    v16: int = add v13, v15 {pure}
    v17: any = opaque.NameRead() {impure}
    v18: int = const 5
    v19: int = opaque.DynamicCall(v17, v18) {impure}
    v20: int = const 1
    v21: int = add v19, v20 {pure}
    v22: long = convert.long v21
    v23: any = opaque.NameRead() {impure}
    v24: double = opaque.DynamicCall(v23, v9) {impure}
    v25: any = opaque.NameRead() {impure}
    v26: double = opaque.DynamicCall(v25, v16) {impure}
    v27: double = add v24, v26 {pure}
    v28: any = opaque.NameRead() {impure}
    v29: double = opaque.DynamicCall(v28, v22) {impure}
    v30: double = add v27, v29 {pure}
    v31: any = opaque.NameRead() {impure}
    v32: float = const 4.0f
    v33: double = opaque.DynamicCall(v31, v32) {impure}
    v34: double = add v30, v33 {pure}
    v35: double = add v7, v34 {pure}
    v36: int = const 1
    v37: int = add v6, v36 {pure}
    v38: any = convert.any v37
    br bb1(v38, v37, v35)
```

```mir
;; mir -O2 fn=Box.run
fn Box.run(v0: int, v1: byte) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: short = convert.short v1
    br bb1(v3, v2)
  bb1(v5: int, v6: double):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = opaque.NameRead() {impure}
    v9: int = const 4
    v10: short = opaque.DynamicCall(v8, v9) {impure}
    v11: int = convert.int v10
    v12: any = opaque.NameRead() {impure}
    v13: int = opaque.DynamicCall(v12, v1) {impure}
    v14: int = add v11, v13 {pure}
    v15: any = opaque.NameRead() {impure}
    v16: int = const 5
    v17: int = opaque.DynamicCall(v15, v16) {impure}
    v18: int = const 1
    v19: int = add v17, v18 {pure}
    v20: long = convert.long v19
    v21: any = opaque.NameRead() {impure}
    v22: double = opaque.DynamicCall(v21, v4) {impure}
    v23: any = opaque.NameRead() {impure}
    v24: double = opaque.DynamicCall(v23, v14) {impure}
    v25: double = add v22, v24 {pure}
    v26: any = opaque.NameRead() {impure}
    v27: double = opaque.DynamicCall(v26, v20) {impure}
    v28: double = add v25, v27 {pure}
    v29: any = opaque.NameRead() {impure}
    v30: float = const 4.0f
    v31: double = opaque.DynamicCall(v29, v30) {impure}
    v32: double = add v28, v31 {pure}
    v33: double = add v6, v32 {pure}
    v34: int = add v5, v18 {pure}
    br bb1(v34, v33)
```

## Class-based LICM hoisting : Unary/Binary/DIV, ThisRef, and fnVar

```babble
def Box = class (def x: double) (
  def run = fn (n: int): double -> (
    def a = 0.0 ; def i = 0 ; def fv = fn (z: double): double -> (java.lang.Math.sin(z))
    while i < n then (
      a += java.lang.Math.sin(this.x) / 2.0 + (-java.lang.Math.toRadians(this.x)) + fv(this.x) ; i += 1
    )
    a
  )
)
assert(java.lang.Math.round(Box(1.0).run(2) * 100.0) == 249L)
```

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (def fv (fn-ref fv))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (+:double (/:double (jcall Math.sin (get-this-field x)) 2.0:double) (block (neg (jcall Math.toRadians (get-this-field x))))) (call-fn fv (get-this-field x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (def fv (fn-ref fv))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (+:double (/:double (jcall Math.sin (get-this-field x)) 2.0:double) (neg (jcall Math.toRadians (get-this-field x)))) (block
            (def z (get-this-field x))
            (jcall Math.sin var(z)))))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=Box.run stage=before
fn Box.run(v0: int) -> double:
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
    v10: double = load_field v9.x {read:shared,throws}
    v11: double = call_java Math.sin(v10) {pure}
    v12: double = const 2.0
    v13: double = div v11, v12 {pure}
    v14: any = slot_read this {pure}
    v15: double = load_field v14.x {read:shared,throws}
    v16: double = call_java Math.toRadians(v15) {pure}
    v17: double = neg v16 {pure}
    v18: double = add v13, v17 {pure}
    v19: any = slot_read this {pure}
    v20: double = load_field v19.x {read:shared,throws}
    v21: double = call_java Math.sin(v20) {pure}
    v22: double = add v18, v21 {pure}
    v23: double = add v7, v22 {pure}
    v24: int = const 1
    v25: int = add v6, v24 {pure}
    v26: any = convert.any v25
    br bb1(v26, v25, v23)
```

```mir
;; mir -O2 fn=Box.run
fn Box.run(v0: int) -> double:
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
    v7: double = load_field v6.x {read:shared,throws}
    v8: double = call_java Math.sin(v7) {pure}
    v9: double = const 2.0
    v10: double = div v8, v9 {pure}
    v11: double = call_java Math.toRadians(v7) {pure}
    v12: double = neg v11 {pure}
    v13: double = add v10, v12 {pure}
    v14: double = add v13, v8 {pure}
    v15: double = add v4, v14 {pure}
    v16: int = const 1
    v17: int = add v3, v16 {pure}
    br bb1(v17, v15)
```

## Class-based LICM hoisting : Logic + array.size, String.trim/length, and As

```babble
def Box = class (def nm: string) (
  def run = fn (n: int, f: bool, xs: array of int): int -> (
    def a = 0 ; def i = 0 ; def raw: any = 1
    while i < n then (
      def l6 = if f or (not f and (xs.size() > 0)) then (1) else (2)
      a += l6 + this.nm.trim().length() + (if i == 0 then ((raw as int) + 0) else (0)) ; i += 1
    )
    a
  )
)
assert(Box("  hi  ").run(2, true, array.of(7)) == 7)
```

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int, f:bool, xs:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (def raw 1:int)
  (while (<:bool var(i) var(n))
    (block
      (def l6 (if (or var(f) (block (and (not var(f)) (block (>:bool (array-op SIZE var(xs)) 0:int))))) (block 1:int) (block 2:int)))
      (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim (get-this-field nm)))) (block (if (==:bool var(i) 0:int) (block (+:int (block (as var(raw) int|int|)) 0:int)) (block 0:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int, f:bool, xs:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (def raw 1:int)
  (while (<:bool var(i) var(n))
    (block
      (def l6 (if (or var(f) (and (not var(f)) (>:bool (array-op SIZE var(xs)) 0:int))) 1:int 2:int))
      (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim (get-this-field nm)))) (if (==:bool var(i) 0:int) (as var(raw) int|int|) 0:int)))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=Box.run stage=before
fn Box.run(v0: int, v1: bool, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: int = const 1
    v6: any = convert.any v5
    v7: any = const null
    br bb1(v7, v4, v3)
  bb1(v8: any, v9: int, v10: int):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: bool = const true
    br_if v1, bb7(v12), bb4
  bb4:
    v13: bool = not v1 {pure}
    v14: bool = const false
    br_if v13, bb5, bb6(v14)
  bb5:
    v15: int = array_len v2 {read:frozen,throws}
    v16: int = const 0
    v17: bool = gt v15, v16 {pure}
    br bb6(v17)
  bb6(v18: bool):
    br bb7(v18)
  bb7(v19: bool):
    br_if v19, bb9, bb8
  bb8:
    v20: int = const 2
    br bb10(v20)
  bb9:
    v21: int = const 1
    br bb10(v21)
  bb10(v22: int):
    v23: any = slot_read this {pure}
    v24: any = load_field v23.nm {read:shared,throws}
    v25: any = call_java v24.trim() {impure}
    v26: any = checkcast v25 <string|string|>
    v27: int = call_java v26.length() {pure,throws}
    v28: int = add v22, v27 {pure}
    v29: int = const 0
    v30: bool = eq v9, v29 {pure}
    br_if v30, bb12, bb11
  bb11:
    v31: int = const 0
    br bb13(v31)
  bb12:
    v32: any = checkcast v6 <int|int|>
    v33: int = convert.int v32
    br bb13(v33)
  bb13(v34: int):
    v35: int = add v28, v34 {pure}
    v36: int = add v10, v35 {pure}
    v37: int = const 1
    v38: int = add v9, v37 {pure}
    v39: any = convert.any v38
    br bb1(v39, v38, v36)
```

```mir
;; mir -O2 fn=Box.run
fn Box.run(v0: int, v1: bool, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 1
    v5: bool = not v1 {pure}
    br bb1(v3, v3)
  bb1(v6: int, v7: int):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: bool = const true
    br_if v1, bb7(v9), bb4
  bb4:
    v10: bool = const false
    br_if v5, bb5, bb6(v10)
  bb5:
    v11: int = array_len v2 {read:frozen,throws}
    v12: bool = gt v11, v3 {pure}
    br bb6(v12)
  bb6(v13: bool):
    br bb7(v13)
  bb7(v14: bool):
    br_if v14, bb9, bb8
  bb8:
    v15: int = const 2
    br bb10(v15)
  bb9:
    br bb10(v4)
  bb10(v16: int):
    v17: any = slot_read this {pure}
    v18: any = load_field v17.nm {read:shared,throws}
    v19: any = call_java v18.trim() {impure}
    v20: any = checkcast v19 <string|string|>
    v21: int = call_java v20.length() {pure,throws}
    v22: int = add v16, v21 {pure}
    v23: bool = eq v6, v3 {pure}
    br_if v23, bb12, bb11
  bb11:
    br bb13(v3)
  bb12:
    br bb13(v4)
  bb13(v24: int):
    v25: int = add v22, v24 {pure}
    v26: int = add v7, v25 {pure}
    v27: int = add v6, v4 {pure}
    br bb1(v27, v26)
```
