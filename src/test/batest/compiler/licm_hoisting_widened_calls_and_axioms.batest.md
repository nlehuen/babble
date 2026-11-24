# LICM Hoisting : Widened Calls, Java Axioms, and Captures

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



## Hoisting widened non-inlineable float calls mixed with mutated float

```babble
def tF = fn nothrow (m: bool, v: float): float -> (def r = if m then (v) else (0.0f) ; return r ; r)
def run = fn (i: int): float -> (
  def a = 0.0f ; def mf = true ; def mfl = 1.0f ; def k = 0
  while k < 2 then (a += tF(mf, bigInt(i)) + (mfl + bigInt(i)) ; mf = not mf ; mfl += 1.0f ; k += 1) ; a
)
assert(run(3) == 12.0f)
```

```hir
;; hir -O0 fn=run
fn run (i:int) -> float {nothrow unique}
  (def a 0.0:float)
  (def mf true:bool)
  (def mfl 1.0:float)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:float (call-fn tF var(mf) (call-fn bigInt var(i))) (block (+:float var(mfl) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set mfl += 1.0:float)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (i:int) -> float {nothrow unique}
  (def a 0.0:float)
  (def mf true:bool)
  (def mfl 1.0:float)
  (def k 0:int)
  (block
    (def $licm0 (block var(i)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:float (block
              (def r (if var(mf) var($licm0) 0.0:float))
              var(r)) (+:float var(mfl) var($licm1))))
        (set mf (not var(mf)))
        (set mfl += 1.0:float)
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> float:
  bb0:
    v1: float = const 0.0f
    v2: bool = const true
    v3: float = const 1.0f
    v4: int = const 0
    v5: float = convert.float v0
    v6: any = const null
    br bb1(v6, v4, v1, v2, v3)
  bb1(v7: any, v8: int, v9: float, v10: bool, v11: float):
    v12: int = const 2
    v13: bool = lt v8, v12 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v9
  bb3:
    v14: any = convert.any v9
    br_if v10, bb5, bb4
  bb4:
    v15: float = const 0.0f
    br bb6(v15)
  bb5:
    br bb6(v5)
  bb6(v16: float):
    v17: float = convert.float v0
    v18: float = add v11, v17 {pure}
    v19: float = add v16, v18 {pure}
    v20: any = convert.any v19
    v21: any = add v14, v20 {impure}
    v22: float = convert.float v21
    v23: bool = not v10 {pure}
    v24: any = convert.any v11
    v25: float = const 1.0f
    v26: any = convert.any v25
    v27: any = add v24, v26 {impure}
    v28: float = convert.float v27
    v29: int = const 1
    v30: int = add v8, v29 {pure}
    v31: any = convert.any v30
    br bb1(v31, v30, v22, v23, v28)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> float:
  bb0:
    v1: float = const 0.0f
    v2: bool = const true
    v3: float = const 1.0f
    v4: int = const 0
    v5: float = convert.float v0
    br bb1(v4, v1, v2, v3)
  bb1(v6: int, v7: float, v8: bool, v9: float):
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
  bb6(v13: float):
    v14: float = add v9, v5 {pure}
    v15: float = add v13, v14 {pure}
    v16: any = convert.any v15
    v17: any = add v12, v16 {impure}
    v18: float = convert.float v17
    v19: bool = not v8 {pure}
    v20: any = convert.any v9
    v21: any = const 1.0f
    v22: any = add v20, v21 {impure}
    v23: float = convert.float v22
    v24: int = const 1
    v25: int = add v6, v24 {pure}
    br bb1(v25, v18, v19, v23)
```

## Hoisting widened non-inlineable double calls mixed with mutated double

```babble
def tD = fn nothrow (m: bool, v: double): double -> (def r = if m then (v) else (0.0) ; return r ; r)
def run = fn (fl: float, i: int): double -> (
  def a = 0.0 ; def mf = true ; def md = 1.0 ; def k = 0
  while k < 2 then (a += tD(mf, bigFloat(fl)) + (md + bigInt(i)) ; mf = not mf ; md += 1.0 ; k += 1) ; a
)
assert(run(1.5f, 3) == 10.5)
```

```hir
;; hir -O0 fn=run
fn run (fl:float, i:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def mf true:bool)
  (def md 1.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (call-fn tD var(mf) (call-fn bigFloat var(fl))) (block (+:double var(md) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set md += 1.0:double)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (fl:float, i:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def mf true:bool)
  (def md 1.0:double)
  (def k 0:int)
  (block
    (def $licm0 (block var(fl)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (block
              (def r (if var(mf) var($licm0) 0.0:double))
              var(r)) (+:double var(md) var($licm1))))
        (set mf (not var(mf)))
        (set md += 1.0:double)
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: float, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: bool = const true
    v4: double = const 1.0
    v5: int = const 0
    v6: double = convert.double v0
    v7: any = const null
    br bb1(v7, v5, v2, v3, v4)
  bb1(v8: any, v9: int, v10: double, v11: bool, v12: double):
    v13: int = const 2
    v14: bool = lt v9, v13 {pure}
    br_if v14, bb3, bb2
  bb2:
    ret v10
  bb3:
    br_if v11, bb5, bb4
  bb4:
    v15: double = const 0.0
    br bb6(v15)
  bb5:
    br bb6(v6)
  bb6(v16: double):
    v17: double = convert.double v1
    v18: double = add v12, v17 {pure}
    v19: double = add v16, v18 {pure}
    v20: double = add v10, v19 {pure}
    v21: bool = not v11 {pure}
    v22: double = const 1.0
    v23: double = add v12, v22 {pure}
    v24: int = const 1
    v25: int = add v9, v24 {pure}
    v26: any = convert.any v25
    br bb1(v26, v25, v20, v21, v23)
```

```mir
;; mir -O2 fn=run
fn run(v0: float, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: bool = const true
    v4: double = const 1.0
    v5: int = const 0
    v6: double = convert.double v0
    v7: double = convert.double v1
    br bb1(v5, v2, v3, v4)
  bb1(v8: int, v9: double, v10: bool, v11: double):
    v12: int = const 2
    v13: bool = lt v8, v12 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v9
  bb3:
    br_if v10, bb5, bb4
  bb4:
    br bb6(v2)
  bb5:
    br bb6(v6)
  bb6(v14: double):
    v15: double = add v11, v7 {pure}
    v16: double = add v14, v15 {pure}
    v17: double = add v9, v16 {pure}
    v18: bool = not v10 {pure}
    v19: double = add v11, v4 {pure}
    v20: int = const 1
    v21: int = add v8, v20 {pure}
    br bb1(v21, v17, v18, v19)
```

## Pure Java axioms hoisted (primitive/string args) vs mutable Point arg rejected by allArgsPrimitiveOrImmutable

```babble
axiom java.lang.Integer.bitCount: fn pure nothrow
axiom java.util.Objects.nonNull: fn pure nothrow
def run = fn io (ro: int): int -> (
  def a = 0 ; def pt = java.awt.Point(1, 2) ; def k = 0
  while k < 2 then (
    a += java.lang.Integer.bitCount(ro) + (if java.util.Objects.nonNull("ab") and java.util.Objects.nonNull(pt) then (1) else (0)) ; k += 1
  ) ; a
)
assert(run(4) == 4)
```

```hir
;; hir -O0 fn=run
fn run (ro:int) -> int {io}
  (def a 0:int)
  (def pt (jnew Point 1:int 2:int))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (jcall Integer.bitCount var(ro)) (block (if (and (jcall Objects.nonNull "ab") (jcall Objects.nonNull var(pt))) (block 1:int) (block 0:int)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (ro:int) -> int {io}
  (def a 0:int)
  (def pt (jnew Point 1:int 2:int))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (jcall Integer.bitCount var(ro)) (if (and (jcall Objects.nonNull "ab") (jcall Objects.nonNull var(pt))) 1:int 0:int)))
      (set k += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    v3: int = const 2
    v4: any = new_java Point(v2, v3) {pure}
    v5: int = const 0
    v6: any = const null
    br bb1(v6, v5, v1)
  bb1(v7: any, v8: int, v9: int):
    v10: int = const 2
    v11: bool = lt v8, v10 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v9
  bb3:
    v12: int = call_java Integer.bitCount(v0) {pure!}
    v13: any = const "ab"
    v14: bool = call_java Objects.nonNull(v13) {pure!}
    v15: bool = const false
    br_if v14, bb4, bb5(v15)
  bb4:
    v16: bool = call_java Objects.nonNull(v4) {pure!}
    br bb5(v16)
  bb5(v17: bool):
    br_if v17, bb7, bb6
  bb6:
    v18: int = const 0
    br bb8(v18)
  bb7:
    v19: int = const 1
    br bb8(v19)
  bb8(v20: int):
    v21: int = add v12, v20 {pure}
    v22: int = add v9, v21 {pure}
    v23: int = const 1
    v24: int = add v8, v23 {pure}
    v25: any = convert.any v24
    br bb1(v25, v24, v22)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    v3: int = const 2
    v4: any = new_java Point(v2, v3) {pure}
    br bb1(v1, v1)
  bb1(v5: int, v6: int):
    v7: bool = lt v5, v3 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: int = call_java Integer.bitCount(v0) {pure!}
    v9: any = const "ab"
    v10: bool = call_java Objects.nonNull(v9) {pure!}
    v11: bool = const false
    br_if v10, bb4, bb5(v11)
  bb4:
    v12: bool = call_java Objects.nonNull(v4) {pure!}
    br bb5(v12)
  bb5(v13: bool):
    br_if v13, bb7, bb6
  bb6:
    br bb8(v1)
  bb7:
    br bb8(v2)
  bb8(v14: int):
    v15: int = add v8, v14 {pure}
    v16: int = add v6, v15 {pure}
    v17: int = add v5, v2 {pure}
    br bb1(v17, v16)
```

## Hoisted pure DirectBabbleCall alongside non-hoistable mutated Math.sin and throwing call

```babble
def run = fn (ro: int): double -> (
  def a = 0.0 ; def ml = 1.0 ; def k = 0
  while k < 2 then (
    a += double(bigInt("ok".length())) + java.lang.Math.sin(ml) + nonPureThrow(ro) ; ml += 0.5 ; k += 1
  ) ; a
)
assert(java.lang.Math.round(run(4) * 100.0) == 1084L)
```

```hir
;; hir -O0 fn=run
fn run (ro:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def ml 1.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (+:double (call name(double) (call-fn bigInt (jcall String.length "ok"))) (jcall Math.sin var(ml))) (call-fn nonPureThrow var(ro))))
      (set ml += 0.5:double)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (ro:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def ml 1.0:double)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def q (/:double 10:int var(ro)))
        (call name(double) var(q))))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (call name(double) 2:int) (jcall Math.sin var(ml))) var($licm0)))
        (set ml += 0.5:double)
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: double = const 1.0
    v3: int = const 0
    v4: int = const 10
    v5: double = convert.double v4
    v6: double = convert.double v0
    v7: double = div v5, v6 {pure}
    v8: any = opaque.NameRead() {impure}
    v9: any = opaque.DynamicCall(v8, v7) {impure}
    v10: double = convert.double v9
    v11: any = const null
    br bb1(v11, v3, v1, v2)
  bb1(v12: any, v13: int, v14: double, v15: double):
    v16: int = const 2
    v17: bool = lt v13, v16 {pure}
    br_if v17, bb3, bb2
  bb2:
    ret v14
  bb3:
    v18: any = opaque.NameRead() {impure}
    v19: int = const 2
    v20: any = opaque.DynamicCall(v18, v19) {impure}
    v21: double = convert.double v20
    v22: double = call_java Math.sin(v15) {pure}
    v23: double = add v21, v22 {pure}
    v24: double = add v23, v10 {pure}
    v25: double = add v14, v24 {pure}
    v26: double = const 0.5
    v27: double = add v15, v26 {pure}
    v28: int = const 1
    v29: int = add v13, v28 {pure}
    v30: any = convert.any v29
    br bb1(v30, v29, v25, v27)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> double:
  bb0:
    v1: double = const 0.0
    v2: double = const 1.0
    v3: int = const 0
    v4: double = const 10.0
    v5: double = convert.double v0
    v6: double = div v4, v5 {pure}
    v7: any = opaque.NameRead() {impure}
    v8: any = opaque.DynamicCall(v7, v6) {impure}
    v9: double = convert.double v8
    br bb1(v3, v1, v2)
  bb1(v10: int, v11: double, v12: double):
    v13: int = const 2
    v14: bool = lt v10, v13 {pure}
    br_if v14, bb3, bb2
  bb2:
    ret v11
  bb3:
    v15: any = opaque.NameRead() {impure}
    v16: any = opaque.DynamicCall(v15, v13) {impure}
    v17: double = convert.double v16
    v18: double = call_java Math.sin(v12) {pure}
    v19: double = add v17, v18 {pure}
    v20: double = add v19, v9 {pure}
    v21: double = add v11, v20 {pure}
    v22: double = const 0.5
    v23: double = add v12, v22 {pure}
    v24: int = const 1
    v25: int = add v10, v24 {pure}
    br bb1(v25, v21, v23)
```

## Captured variable mutated by closure blocks LICM on bigInt(cap)

```babble
def run = fn io (): int -> (
  def a = 0 ; def cap = 2 ; def bump = fn io (): int -> (cap += 1 ; cap) ; def k = 0
  while k < 2 then (
    a += bigInt(cap)
    if false then (a += bump())
    k += 1
  ) ; a
)
assert(run() == 4)
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
  (def bump (fn-ref bump))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (call-fn bigInt var(cap)))
      (if false:bool (block (set a += (call-fn bump))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
  (def bump (fn-ref bump))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a +=
        (block
          (def x var(cap))
          var(x)))
      (set k += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: int = const 0
    v1: int = const 2
    slot_init cap, v1 {write:shared}
    v2: any = opaque.FunctionRef() {read:shared}
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v0)
  bb1(v5: any, v6: int, v7: int):
    v8: int = const 2
    v9: bool = lt v6, v8 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: int = slot_read cap {read:shared}
    v11: int = add v7, v10 {pure}
    v12: int = const 1
    v13: int = add v6, v12 {pure}
    v14: any = convert.any v13
    br bb1(v14, v13, v11)
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: int = const 0
    v1: int = const 2
    slot_init cap, v1 {write:shared}
    br bb1(v0, v0)
  bb1(v2: int, v3: int):
    v4: bool = lt v2, v1 {pure}
    br_if v4, bb3, bb2
  bb2:
    ret v3
  bb3:
    v5: int = slot_read cap {read:shared}
    v6: int = add v3, v5 {pure}
    v7: int = const 1
    v8: int = add v2, v7 {pure}
    br bb1(v8, v6)
```
