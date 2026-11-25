# LICM Hoisting : Record Chains and Non-Inlineable Calls

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



## Record-based LICM : String chain, As, and DIV

```babble
def RV = record (a: byte, d: double, s: string) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
    while k < n then (
      r += double(("  " + this.s + "  ").trim().length()) + (this.a as double) + (this.d + 1.0) / 2.0 ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 1.0, "hi").run(2) == 10.0)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim (block (+:obj (+:obj "  " (get-this-field s)) "  "))))) (block (as (get-this-field a) double|double|))) (/:double (block (+:double (get-this-field d) 1.0:double)) 2.0:double)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (+:obj (+:obj "  " (get-this-field s)) "  "))
    (def $licm1 (get-this-field a))
    (def $licm2 (/:double (+:double (get-this-field d) 1.0:double) 2.0:double))
    (while (<:bool var(k) var(n))
      (block
        (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim var($licm0)))) (as var($licm1) double|double|)) var($licm2)))
        (set k += 1:int))))
  var(r)
```

```mir
;; mir -O2 fn=RV.run stage=before
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = const "  "
    v4: any = slot_read this {pure}
    v5: any = load_field v4.s {read:frozen}
    v6: any = strcat v3, v5 {pure}
    v7: any = const "  "
    v8: any = strcat v6, v7 {pure}
    v9: any = slot_read this {pure}
    v10: byte = load_field v9.a {read:frozen}
    v11: any = slot_read this {pure}
    v12: double = load_field v11.d {read:frozen}
    v13: double = const 1.0
    v14: double = add v12, v13 {pure}
    v15: double = const 2.0
    v16: double = div v14, v15 {pure}
    v17: any = const null
    br bb1(v17, v2, v1)
  bb1(v18: any, v19: int, v20: double):
    v21: bool = lt v19, v0 {pure}
    br_if v21, bb3, bb2
  bb2:
    ret v20
  bb3:
    v22: any = name_read double {pure}
    v23: any = call_java v8.trim() {impure}
    v24: any = checkcast v23 <string|string|>
    v25: int = call_java v24.length() {pure,throws}
    v26: double = opaque.DynamicCall(v22, v25) {impure}
    v27: any = convert.any v10
    v28: any = checkcast v27 <double|double|>
    v29: double = convert.double v28
    v30: double = add v26, v29 {pure}
    v31: double = add v30, v16 {pure}
    v32: double = add v20, v31 {pure}
    v33: int = const 1
    v34: int = add v19, v33 {pure}
    v35: any = convert.any v34
    br bb1(v35, v34, v32)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = const "  "
    v4: any = slot_read this {pure}
    v5: any = load_field v4.s {read:frozen}
    v6: any = strcat v3, v5 {pure}
    v7: any = strcat v6, v3 {pure}
    v8: byte = load_field v4.a {read:frozen}
    v9: double = load_field v4.d {read:frozen}
    v10: double = const 1.0
    v11: double = add v9, v10 {pure}
    v12: double = const 2.0
    v13: double = div v11, v12 {pure}
    v14: any = name_read double {pure}
    v15: any = convert.any v8
    br bb1(v2, v1)
  bb1(v16: int, v17: double):
    v18: bool = lt v16, v0 {pure}
    br_if v18, bb3, bb2
  bb2:
    ret v17
  bb3:
    v19: any = call_java v7.trim() {impure}
    v20: any = checkcast v19 <string|string|>
    v21: int = call_java v20.length() {pure,throws}
    v22: double = opaque.DynamicCall(v14, v21) {impure}
    v23: any = checkcast v15 <double|double|>
    v24: double = convert.double v23
    v25: double = add v22, v24 {pure}
    v26: double = add v25, v13 {pure}
    v27: double = add v17, v26 {pure}
    v28: int = const 1
    v29: int = add v16, v28 {pure}
    br bb1(v29, v27)
```

## Record-based LICM : bigSink over As + Logic

```babble
def RV = record (a: byte, i: int, f: bool) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
    while k < n then (
      r += bigSink((this.a as double) + (if this.f and (this.i > 0) or (not this.f) then (1.0) else (2.0))) ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 3, true).run(2) == 6.0)
```

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set r += (call-fn bigSink (+:double (block (as (get-this-field a) double|double|)) (block (if (or (and (get-this-field f) (block (>:bool (get-this-field i) 0:int))) (block (not (get-this-field f)))) (block 1.0:double) (block 2.0:double))))))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field a))
    (def $licm1 (get-this-field f))
    (def $licm2 (>:bool (get-this-field i) 0:int))
    (def $licm3 (not (get-this-field f)))
    (while (<:bool var(k) var(n))
      (block
        (set r +=
          (block
            (def x (+:double (as var($licm0) double|double|) (if (or (and var($licm1) var($licm2)) var($licm3)) 1.0:double 2.0:double)))
            var(x)))
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
    v4: byte = load_field v3.a {read:frozen}
    v5: any = slot_read this {pure}
    v6: bool = load_field v5.f {read:frozen}
    v7: any = slot_read this {pure}
    v8: int = load_field v7.i {read:frozen}
    v9: int = const 0
    v10: bool = gt v8, v9 {pure}
    v11: any = slot_read this {pure}
    v12: bool = load_field v11.f {read:frozen}
    v13: bool = not v12 {pure}
    v14: any = const null
    br bb1(v14, v2, v1)
  bb1(v15: any, v16: int, v17: double):
    v18: bool = lt v16, v0 {pure}
    br_if v18, bb3, bb2
  bb2:
    ret v17
  bb3:
    v19: any = convert.any v4
    v20: any = checkcast v19 <double|double|>
    v21: double = convert.double v20
    v22: bool = const false
    br_if v6, bb4, bb5(v22)
  bb4:
    br bb5(v10)
  bb5(v23: bool):
    v24: bool = const true
    br_if v23, bb7(v24), bb6
  bb6:
    br bb7(v13)
  bb7(v25: bool):
    br_if v25, bb9, bb8
  bb8:
    v26: double = const 2.0
    br bb10(v26)
  bb9:
    v27: double = const 1.0
    br bb10(v27)
  bb10(v28: double):
    v29: double = add v21, v28 {pure}
    v30: double = add v17, v29 {pure}
    v31: int = const 1
    v32: int = add v16, v31 {pure}
    v33: any = convert.any v32
    br bb1(v33, v32, v30)
```

```mir
;; mir -O2 fn=RV.run
fn RV.run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: byte = load_field v3.a {read:frozen}
    v5: bool = load_field v3.f {read:frozen}
    v6: int = load_field v3.i {read:frozen}
    v7: bool = gt v6, v2 {pure}
    v8: bool = not v5 {pure}
    v9: any = convert.any v4
    br bb1(v2, v1)
  bb1(v10: int, v11: double):
    v12: bool = lt v10, v0 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v11
  bb3:
    v13: any = checkcast v9 <double|double|>
    v14: double = convert.double v13
    v15: bool = const false
    br_if v5, bb4, bb5(v15)
  bb4:
    br bb5(v7)
  bb5(v16: bool):
    v17: bool = const true
    br_if v16, bb7(v17), bb6
  bb6:
    br bb7(v8)
  bb7(v18: bool):
    br_if v18, bb9, bb8
  bb8:
    v19: double = const 2.0
    br bb10(v19)
  bb9:
    v20: double = const 1.0
    br bb10(v20)
  bb10(v21: double):
    v22: double = add v14, v21 {pure}
    v23: double = add v11, v22 {pure}
    v24: int = const 1
    v25: int = add v10, v24 {pure}
    br bb1(v25, v23)
```

## Non-inlineable DirectBabbleCall hoisting : implicit return, optional param, and local fn alias

```babble
def bigOpt = fn nothrow (x: double?): double -> (def r = if x == null then (0.0) else (1.0) ; return r ; r)
def run = fn (d: double, f: bool): double -> (
  var a = 0.0 ; def loc = bigSink ; var k = 0
  while k < 2 then (a += bigImpl(d) + bigSink((-d) + (if f then (1.0) else (2.0))) + bigOpt(if f then (d)) + loc(d) ; k += 1) ; a
)
assert(java.lang.Math.round(run(1.0, true) * 100.0) == 568L)
```

```hir
;; hir -O0 fn=run
fn run (d:double, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def loc name(bigSink))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (+:double (+:double (call-fn bigImpl var(d)) (call-fn bigSink (+:double (block (neg var(d))) (block (if var(f) (block 1.0:double) (block 2.0:double)))))) (call-fn bigOpt (if var(f) (block var(d))))) (call var(loc) var(d))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (d:double, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def loc name(bigSink))
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def s (jcall Math.sin var(d)))
        var(s)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (+:double var($licm0) (block
                  (def x#1 (+:double (neg var(d)) (if var(f) 1.0:double 2.0:double)))
                  var(x#1))) (block
                (def x#2 (if var(f) var(d)))
                (def r (if (==:bool var(x#2) null) 0.0:double 1.0:double))
                var(r))) (call var(loc) var(d))))
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: double, v1: bool) -> double:
  bb0:
    v2: double = const 0.0
    v3: any = name_read bigSink {pure}
    v4: int = const 0
    v5: double = call_java Math.sin(v0) {pure}
    v6: any = const null
    br bb1(v6, v4, v2)
  bb1(v7: any, v8: int, v9: double):
    v10: int = const 2
    v11: bool = lt v8, v10 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v9
  bb3:
    v12: double = neg v0 {pure}
    br_if v1, bb5, bb4
  bb4:
    v13: double = const 2.0
    br bb6(v13)
  bb5:
    v14: double = const 1.0
    br bb6(v14)
  bb6(v15: double):
    v16: double = add v12, v15 {pure}
    v17: double = add v5, v16 {pure}
    br_if v1, bb8, bb7
  bb7:
    v18: any = const null
    br bb9(v18)
  bb8:
    v19: any = convert.any v0
    br bb9(v19)
  bb9(v20: any):
    v21: any = widen#5 v20
    v22: any = const null
    v23: any = eq v21, v22 {pure,throws}
    br_if v23, bb11, bb10
  bb10:
    v24: any = narrow.nonnull v21 <non-null>
    v25: double = const 1.0
    br bb12(v25)
  bb11:
    v26: double = const 0.0
    br bb12(v26)
  bb12(v27: double):
    v28: double = add v17, v27 {pure}
    v29: double = opaque.DynamicCall(v3, v0) {impure}
    v30: double = add v28, v29 {pure}
    v31: double = add v9, v30 {pure}
    v32: int = const 1
    v33: int = add v8, v32 {pure}
    v34: any = convert.any v33
    br bb1(v34, v33, v31)
```

```mir
;; mir -O2 fn=run
fn run(v0: double, v1: bool) -> double:
  bb0:
    v2: double = const 0.0
    v3: any = name_read bigSink {pure}
    v4: int = const 0
    v5: double = call_java Math.sin(v0) {pure}
    v6: any = const null
    v7: double = neg v0 {pure}
    v8: any = convert.any v0
    br bb1(v4, v2)
  bb1(v9: int, v10: double):
    v11: int = const 2
    v12: bool = lt v9, v11 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v10
  bb3:
    br_if v1, bb5, bb4
  bb4:
    v13: double = const 2.0
    br bb6(v13)
  bb5:
    v14: double = const 1.0
    br bb6(v14)
  bb6(v15: double):
    v16: double = add v7, v15 {pure}
    v17: double = add v5, v16 {pure}
    br_if v1, bb8, bb7
  bb7:
    br bb9(v6)
  bb8:
    br bb9(v8)
  bb9(v18: any):
    v19: any = widen#5 v18
    v20: any = eq v19, v6 {pure,throws}
    br_if v20, bb11, bb10
  bb10:
    v21: double = const 1.0
    br bb12(v21)
  bb11:
    br bb12(v2)
  bb12(v22: double):
    v23: double = add v17, v22 {pure}
    v24: double = opaque.DynamicCall(v3, v0) {impure}
    v25: double = add v23, v24 {pure}
    v26: double = add v10, v25 {pure}
    v27: int = const 1
    v28: int = add v9, v27 {pure}
    br bb1(v28, v26)
```

## Hoisting widened non-inlineable calls passed to multi-arg functions (short/int)

```babble
def tS = fn nothrow (m: bool, v: short): int -> (def r = if m then (int(v)) else (0) ; return r ; r)
def tI = fn nothrow (m: bool, v: int): int -> (def r = if m then (v) else (0) ; return r ; r)
def run = fn (b: byte): int -> (
  var a = 0 ; var mf = true ; var k = 0
  while k < 2 then (a += tS(mf, bigByte(b)) + tI(mf, bigByte(b)) ; mf = not mf ; k += 1) ; a
)
assert(run(byte(2)) == 4)
```

```hir
;; hir -O0 fn=run
fn run (b:byte) -> int {nothrow unique}
  (def a 0:int)
  (def mf true:bool)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (call-fn tS var(mf) (call-fn bigByte var(b))) (call-fn tI var(mf) (call-fn bigByte var(b)))))
      (set mf (not var(mf)))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (b:byte) -> int {nothrow unique}
  (def a 0:int)
  (def mf true:bool)
  (def k 0:int)
  (block
    (def $licm0 (block var(b)))
    (def $licm1 (block var(b)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:int (block
              (def r#1 (if var(mf) (call name(int) var($licm0)) 0:int))
              var(r#1)) (block
              (def r#2 (if var(mf) var($licm1) 0:int))
              var(r#2))))
        (set mf (not var(mf)))
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: byte) -> int:
  bb0:
    v1: int = const 0
    v2: bool = const true
    v3: int = const 0
    v4: short = convert.short v0
    v5: int = convert.int v0
    v6: any = const null
    br bb1(v6, v3, v1, v2)
  bb1(v7: any, v8: int, v9: int, v10: bool):
    v11: int = const 2
    v12: bool = lt v8, v11 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v9
  bb3:
    br_if v10, bb5, bb4
  bb4:
    v13: int = const 0
    br bb6(v13)
  bb5:
    v14: any = name_read int {pure}
    v15: int = opaque.DynamicCall(v14, v4) {impure}
    br bb6(v15)
  bb6(v16: int):
    br_if v10, bb8, bb7
  bb7:
    v17: int = const 0
    br bb9(v17)
  bb8:
    br bb9(v5)
  bb9(v18: int):
    v19: int = add v16, v18 {pure}
    v20: int = add v9, v19 {pure}
    v21: bool = not v10 {pure}
    v22: int = const 1
    v23: int = add v8, v22 {pure}
    v24: any = convert.any v23
    br bb1(v24, v23, v20, v21)
```

```mir
;; mir -O2 fn=run
fn run(v0: byte) -> int:
  bb0:
    v1: int = const 0
    v2: bool = const true
    v3: short = convert.short v0
    v4: int = convert.int v0
    v5: any = name_read int {pure}
    br bb1(v1, v1, v2)
  bb1(v6: int, v7: int, v8: bool):
    v9: int = const 2
    v10: bool = lt v6, v9 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v7
  bb3:
    br_if v8, bb5, bb4
  bb4:
    br bb6(v1)
  bb5:
    v11: int = opaque.DynamicCall(v5, v3) {impure}
    br bb6(v11)
  bb6(v12: int):
    br_if v8, bb8, bb7
  bb7:
    br bb9(v1)
  bb8:
    br bb9(v4)
  bb9(v13: int):
    v14: int = add v12, v13 {pure}
    v15: int = add v7, v14 {pure}
    v16: bool = not v8 {pure}
    v17: int = const 1
    v18: int = add v6, v17 {pure}
    br bb1(v18, v15, v16)
```

## Hoisting widened non-inlineable calls passed to multi-arg functions (long) and mixed with mutated long

```babble
def tL = fn nothrow (m: bool, v: long): long -> (def r = if m then (v) else (0L) ; return r ; r)
def run = fn (i: int): long -> (
  var a = 0L ; var mf = true ; var ml = 1L ; var k = 0
  while k < 2 then (a += tL(mf, bigInt(i)) + (ml + bigInt(i)) ; mf = not mf ; ml += 1L ; k += 1) ; a
)
assert(run(3) == 12L)
```

```hir
;; hir -O0 fn=run
fn run (i:int) -> long {nothrow unique}
  (def a 0:long)
  (def mf true:bool)
  (def ml 1:long)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:long (call-fn tL var(mf) (call-fn bigInt var(i))) (block (+:long var(ml) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set ml += 1:long)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (i:int) -> long {nothrow unique}
  (def a 0:long)
  (def mf true:bool)
  (def ml 1:long)
  (def k 0:int)
  (block
    (def $licm0 (block var(i)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:long (block
              (def r (if var(mf) var($licm0) 0:long))
              var(r)) (+:long var(ml) var($licm1))))
        (set mf (not var(mf)))
        (set ml += 1:long)
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> long:
  bb0:
    v1: long = const 0L
    v2: bool = const true
    v3: long = const 1L
    v4: int = const 0
    v5: long = convert.long v0
    v6: any = const null
    br bb1(v6, v4, v1, v2, v3)
  bb1(v7: any, v8: int, v9: long, v10: bool, v11: long):
    v12: int = const 2
    v13: bool = lt v8, v12 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v9
  bb3:
    v14: any = convert.any v9
    br_if v10, bb5, bb4
  bb4:
    v15: long = const 0L
    br bb6(v15)
  bb5:
    br bb6(v5)
  bb6(v16: long):
    v17: long = convert.long v0
    v18: long = add v11, v17 {pure}
    v19: long = add v16, v18 {pure}
    v20: any = convert.any v19
    v21: any = add v14, v20 {impure}
    v22: long = convert.long v21
    v23: bool = not v10 {pure}
    v24: any = convert.any v11
    v25: long = const 1L
    v26: any = convert.any v25
    v27: any = add v24, v26 {impure}
    v28: long = convert.long v27
    v29: int = const 1
    v30: int = add v8, v29 {pure}
    v31: any = convert.any v30
    br bb1(v31, v30, v22, v23, v28)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> long:
  bb0:
    v1: long = const 0L
    v2: bool = const true
    v3: long = const 1L
    v4: int = const 0
    v5: long = convert.long v0
    br bb1(v4, v1, v2, v3)
  bb1(v6: int, v7: long, v8: bool, v9: long):
    v10: int = const 2
    v11: bool = lt v6, v10 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v7
  bb3:
    v12: any = convert.any v7
    br_if v8, bb5, bb4
  bb4:
    br bb6(v1)
  bb5:
    br bb6(v5)
  bb6(v13: long):
    v14: long = add v9, v5 {pure}
    v15: long = add v13, v14 {pure}
    v16: any = convert.any v15
    v17: any = add v12, v16 {impure}
    v18: long = convert.long v17
    v19: bool = not v8 {pure}
    v20: any = convert.any v9
    v21: any = const 1L
    v22: any = add v20, v21 {impure}
    v23: long = convert.long v22
    v24: int = const 1
    v25: int = add v6, v24 {pure}
    br bb1(v25, v18, v19, v23)
```
