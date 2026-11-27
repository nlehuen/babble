# LICM Receiver Purity, Frozen Fields, and Method Calls

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## LICM in for-loop (Logic, DIV, Block) and DCE of unused MakeOverloadSet

```babble
def run = fn (a: int, b: int, flag: bool, xs: list of int): double -> (
  def unusedOv = overload (fn (n: int): int -> (n + 1), fn (s: string): int -> (s.length()))
  var acc = 0.0
  for e in xs then (if flag and (a > 0) then (acc += (a / b) + (a + b) + e))
  acc
)
assert(run(10, 2, true, list(1, 2, 3)) == 57.0)
```

```hir
;; hir -O0 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def unusedOv (overload (fn-ref unusedOv#1) (fn-ref unusedOv#2)))
  (def acc 0.0:double)
  (for e in var(xs)
    (block
      (if (and var(flag) (block (>:bool var(a) 0:int))) (box (block (set acc += (+:double (+:double (block (/:double (convert[double] var(a)) (convert[double] var(b)))) (convert[double] (block (+:int var(a) var(b))))) (convert[double] var(e)))))))))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def unusedOv (overload (fn-ref unusedOv#1) (fn-ref unusedOv#2)))
  (def acc 0.0:double)
  (block
    (def $licm0 (+:double (/:double (convert[double] var(a)) (convert[double] var(b))) (convert[double] (+:int var(a) var(b)))))
    (for e in var(xs)
      (block
        (if (and var(flag) (>:bool var(a) 0:int)) (box (set acc += (+:double var($licm0) (convert[double] var(e)))))))))
  var(acc)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: int, v2: bool, v3: any) -> double:
  bb0:
    v4: any = opaque.FunctionRef(unusedOv) {pure}
    v5: any = opaque.FunctionRef(unusedOv) {pure}
    v6: any = opaque.MakeOverloadSet(v4, v5) {pure}
    v7: double = const 0.0
    v8: double = convert.double v0
    v9: double = convert.double v1
    v10: double = div v8, v9 {pure}
    v11: int = add v0, v1 {pure}
    v12: double = convert.double v11
    v13: double = add v10, v12 {pure}
    v14: any = for_init.java_iterable v3
    v15: any = const null
    br bb1(v15, v7)
  bb1(v16: any, v17: double):
    v18: bool = for_has_next v14
    br_if v18, bb3, bb2
  bb2:
    ret v17
  bb3:
    v19: any = for_next v14
    v20: any = checkcast v19 <int|int|>
    v21: int = convert.int v20
    v22: bool = const false
    br_if v2, bb4, bb5(v22)
  bb4:
    v23: int = const 0
    v24: bool = gt v0, v23 {pure}
    br bb5(v24)
  bb5(v25: bool):
    br_if v25, bb7, bb6
  bb6:
    v26: any = const null
    br bb8(v26, v17)
  bb7:
    v27: double = convert.double v21
    v28: double = add v13, v27 {pure}
    v29: double = add v17, v28 {pure}
    v30: any = convert.any v29
    br bb8(v30, v29)
  bb8(v31: any, v32: double):
    br bb1(v31, v32)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: int, v2: bool, v3: any) -> double:
  bb0:
    v4: double = const 0.0
    v5: double = convert.double v0
    v6: double = convert.double v1
    v7: double = div v5, v6 {pure}
    v8: int = add v0, v1 {pure}
    v9: double = convert.double v8
    v10: double = add v7, v9 {pure}
    v11: any = for_init.java_iterable v3
    v12: int = const 0
    v13: bool = gt v0, v12 {pure}
    br bb1(v4)
  bb1(v14: double):
    v15: bool = for_has_next v11
    br_if v15, bb3, bb2
  bb2:
    ret v14
  bb3:
    v16: any = for_next v11
    v17: any = checkcast v16 <int|int|>
    v18: int = convert.int v17
    v19: bool = const false
    br_if v2, bb4, bb5(v19)
  bb4:
    br bb5(v13)
  bb5(v20: bool):
    br_if v20, bb7, bb6
  bb6:
    br bb8(v14)
  bb7:
    v21: double = convert.double v18
    v22: double = add v10, v21 {pure}
    v23: double = add v14, v22 {pure}
    br bb8(v23)
  bb8(v24: double):
    br bb1(v24)
```

## HirPurity.isDeterministic : loop-invariant non-static String.length, If, FunctionRef, CoreCollectionRef

```babble
def useRef = fn nothrow (f: fn nothrow (int): int, c: any): int -> (return f(1) ; 0)
def run = fn (s: string, flag: bool, n: int): int -> (
  var i = 0 ; var acc = 0
  while i < n then (
    acc += s.length() + (if flag then (1) else (2)) + useRef(fn nothrow (x: int): int -> (x + 1), list)
    i += 1
  )
  acc
)
assert(run("abc", true, 3) == 18)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, flag:bool, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (+:int (jcall String.length var(s)) (block (if var(flag) (block 1:int) (block 2:int)))) (call-fn useRef (fn-ref <anon#0>) core-col(list))))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, flag:bool, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (+:int (jcall String.length var(s)) (if var(flag) 1:int 2:int)) (+:int 1:int 1:int)))
      (set i += 1:int)))
  var(acc)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: any = const null
    br bb1(v5, v3, v4)
  bb1(v6: any, v7: int, v8: int):
    v9: bool = lt v7, v2 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: int = call_java v0.length() {pure,throws}
    br_if v1, bb5, bb4
  bb4:
    v11: int = const 2
    br bb6(v11)
  bb5:
    v12: int = const 1
    br bb6(v12)
  bb6(v13: int):
    v14: int = add v10, v13 {pure}
    v15: int = const 1
    v16: int = const 1
    v17: int = add v15, v16 {pure}
    v18: int = add v14, v17 {pure}
    v19: int = add v8, v18 {pure}
    v20: int = const 1
    v21: int = add v7, v20 {pure}
    v22: any = convert.any v21
    br bb1(v22, v21, v19)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool, v2: int) -> int:
  bb0:
    v3: int = const 0
    br bb1(v3, v3)
  bb1(v4: int, v5: int):
    v6: bool = lt v4, v2 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v5
  bb3:
    v7: int = call_java v0.length() {pure,throws}
    br_if v1, bb5, bb4
  bb4:
    v8: int = const 2
    br bb6(v8)
  bb5:
    v9: int = const 1
    br bb6(v9)
  bb6(v10: int):
    v11: int = add v7, v10 {pure}
    v12: int = const 1
    v13: int = const 2
    v14: int = add v11, v13 {pure}
    v15: int = add v5, v14 {pure}
    v16: int = add v4, v12 {pure}
    br bb1(v16, v15)
```

## HirEGraphSimplifyPass : byte/short constant widening and char/bool comparisons

```babble
def run = fn (p: byte): bool -> (
  def s: short = if true then (byte(3)) else (p)
  def c1 = 'a' < 'b' and 'a' <= 'a' and 'b' > 'a' and 'b' >= 'b' and 'a' != 'b'
  def b1 = (true == true) and (true != false) and not (false == true)
  s == short(3) and c1 and b1
)
assert(run(byte(1)))
```

```hir
;; hir -O0 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (if true:bool (widen[short] (block (call name(byte) 3:int))) (widen[short] (block var(p)))))
  (def c1 (and (and (and (and (<:bool 'a' 'b') (<=:bool 'a' 'a')) (>:bool 'b' 'a')) (>=:bool 'b' 'b')) (<>:bool 'a' 'b')))
  (def b1 (and (and (block (==:bool true:bool true:bool)) (block (<>:bool true:bool false:bool))) (not (block (==:bool false:bool true:bool)))))
  (and (and (==:bool var(s) (call name(short) 3:int)) var(c1)) var(b1))
```

```hir
;; hir -O1 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (widen[short] (call name(byte) 3:int)))
  (and (and (==:bool var(s) (call name(short) 3:int)) true:bool) true:bool)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: byte) -> bool:
  bb0:
    v1: any = name_read byte {pure}
    v2: int = const 3
    v3: byte = opaque.DynamicCall(v1, v2) {impure}
    v4: short = convert.short v3
    v5: any = name_read short {pure}
    v6: int = const 3
    v7: short = opaque.DynamicCall(v5, v6) {impure}
    v8: bool = eq v4, v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: bool = const true
    br bb2(v10)
  bb2(v11: bool):
    v12: bool = const false
    br_if v11, bb3, bb4(v12)
  bb3:
    v13: bool = const true
    br bb4(v13)
  bb4(v14: bool):
    ret v14
```

```mir
;; mir -O2 fn=run
fn run(v0: byte) -> bool:
  bb0:
    v1: any = name_read byte {pure}
    v2: int = const 3
    v3: byte = opaque.DynamicCall(v1, v2) {impure}
    v4: short = convert.short v3
    v5: any = name_read short {pure}
    v6: short = opaque.DynamicCall(v5, v2) {impure}
    v7: bool = eq v4, v6 {pure}
    v8: bool = const false
    br_if v7, bb1, bb2(v8)
  bb1:
    v9: bool = const true
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb3, bb4(v8)
  bb3:
    v11: bool = const true
    br bb4(v11)
  bb4(v12: bool):
    ret v12
```

## Canonical HIR golden : loop-invariant Math.cos hoisted to preheader (-O0 vs -O1)

```babble
def sumScaled = fn (n: int, angle: double): double -> (
    var i = 0
    var acc = 0.0
    while i < n then (
        acc += java.lang.Math.cos(angle) * 2.0
        i += 1
    )
    acc
)
assert(sumScaled(4, 0.0) == 8.0)
```

```hir
;; hir -O0 fn=sumScaled
fn sumScaled (n:int, angle:double) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (*:double (jcall Math.cos var(angle)) 2.0:double))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumScaled
fn sumScaled (n:int, angle:double) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (jcall Math.cos var(angle)) 2.0:double))
    (while (<:bool var(i) var(n))
      (block
        (set acc += var($licm0))
        (set i += 1:int))))
  var(acc)
```

```mir
;; mir -O2 fn=sumScaled stage=before
fn sumScaled(v0: int, v1: double) -> double:
  bb0:
    v2: int = const 0
    v3: double = const 0.0
    v4: double = call_java Math.cos(v1) {pure}
    v5: double = const 2.0
    v6: double = mul v4, v5 {pure}
    v7: any = const null
    br bb1(v7, v2, v3)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: double = add v10, v6 {pure}
    v13: int = const 1
    v14: int = add v9, v13 {pure}
    v15: any = convert.any v14
    br bb1(v15, v14, v12)
```

```mir
;; mir -O2 fn=sumScaled
fn sumScaled(v0: int, v1: double) -> double:
  bb0:
    v2: int = const 0
    v3: double = const 0.0
    v4: double = call_java Math.cos(v1) {pure}
    v5: double = const 2.0
    v6: double = mul v4, v5 {pure}
    br bb1(v2, v3)
  bb1(v7: int, v8: double):
    v9: bool = lt v7, v0 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: double = add v8, v6 {pure}
    v11: int = const 1
    v12: int = add v7, v11 {pure}
    br bb1(v12, v10)
```

## HirLicmPass : frozen class field read and unary negation

```babble
def FC = class (def v: int) ()
def run = fn (n: int, fc: frozen FC): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += fc.v + (-fc.v) + 1
        i += 1
    ) ; a
)
assert(run(3, freeze(FC(5))) == 3)
```

```hir
;; hir -O0 fn=run
fn run (n:int, fc:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (get-this-field v) (block (neg (get-this-field v)))) 1:int))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, fc:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (+:int (+:int (get-this-field v) (neg (get-this-field v))) 1:int))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: int = const 0
    v3: int = const 0
    v4: int = load_field v1.v {read:frozen}
    v5: int = load_field v1.v {read:frozen}
    v6: int = neg v5 {pure}
    v7: int = add v4, v6 {pure}
    v8: int = const 1
    v9: int = add v7, v8 {pure}
    v10: any = const null
    br bb1(v10, v3, v2)
  bb1(v11: any, v12: int, v13: int):
    v14: bool = lt v12, v0 {pure}
    br_if v14, bb3, bb2
  bb2:
    ret v13
  bb3:
    v15: int = add v13, v9 {pure}
    v16: int = const 1
    v17: int = add v12, v16 {pure}
    v18: any = convert.any v17
    br bb1(v18, v17, v15)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: int = const 0
    v3: int = load_field v1.v {read:frozen}
    v4: int = neg v3 {pure}
    v5: int = add v3, v4 {pure}
    v6: int = const 1
    v7: int = add v5, v6 {pure}
    br bb1(v2, v2)
  bb1(v8: int, v9: int):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: int = add v9, v7 {pure}
    v12: int = add v8, v6 {pure}
    br bb1(v12, v11)
```

## HirLicmPass : captured this (cell ThisRef) and immediately-invoked FunctionRef callee

```babble
def CR = record (x: int) (
    def run = fn (n: int): int -> (
        def cap = fn (): int -> (this.x)
        var a = 0 ; var i = 0
        while i < n then (
            a += this.x + (fn nothrow (k: int): int -> (return k + 1 ; k))(n)
            i += 1
        )
        a + cap()
    )
)
assert(CR(3).run(2) == 15)
```

```hir
;; hir -O0 fn=CR.run
fn CR.run (n:int) -> int {nothrow unique}
  (def cap (fn-ref cap))
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (get-this-field x) (call-fn <anon#0> var(n))))
      (set i += 1:int)))
  (+:int var(a) (call-fn cap))
```

```hir
;; hir -O1 fn=CR.run
fn CR.run (n:int) -> int {nothrow unique}
  (def cap (fn-ref cap))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field x))
    (block
      (def $licm1 (+:int var($licm0) (+:int var(n) 1:int)))
      (while (<:bool var(i) var(n))
        (block
          (set a += var($licm1))
          (set i += 1:int)))))
  (+:int var(a) (call-fn cap))
```

```mir
;; mir -O2 fn=CR.run stage=before
fn CR.run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef(cap) {read:shared}
    v2: int = const 0
    v3: int = const 0
    v4: any = slot_read this {pure}
    v5: int = load_field v4.x {read:frozen}
    v6: int = const 1
    v7: int = add v0, v6 {pure}
    v8: int = add v5, v7 {pure}
    v9: any = const null
    br bb1(v9, v3, v2)
  bb1(v10: any, v11: int, v12: int):
    v13: bool = lt v11, v0 {pure}
    br_if v13, bb3, bb2
  bb2:
    v14: int = call cap() {impure}
    v15: int = add v12, v14 {pure}
    ret v15
  bb3:
    v16: int = add v12, v8 {pure}
    v17: int = const 1
    v18: int = add v11, v17 {pure}
    v19: any = convert.any v18
    br bb1(v19, v18, v16)
```

```mir
;; mir -O2 fn=CR.run
fn CR.run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef(cap) {read:shared}
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: int = load_field v3.x {read:frozen}
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: int = add v4, v6 {pure}
    br bb1(v2, v2)
  bb1(v8: int, v9: int):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    v11: int = call cap() {impure}
    v12: int = add v9, v11 {pure}
    ret v12
  bb3:
    v13: int = add v9, v7 {pure}
    v14: int = add v8, v5 {pure}
    br bb1(v14, v13)
```
