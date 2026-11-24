# LICM Operand Invariance, Blocks, Casts, and Overloads

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirPurity.collectModifiedVars : SelfRecurseCall and nested while inside a while loop

```babble
def recWhile = fn (n: int): int -> (
  def i = 0 ; def s = 0
  while i < 1 then (
    def j = 0 ; while j < 2 then (j += 1)
    if n > 0 then (s += recurse(n - 1) + 1) ; s += j ; i += 1
  )
  s
)
assert(recWhile(2) == 8)
```

```hir
;; hir -O0 fn=recWhile
fn recWhile (n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 1:int)
    (block
      (def j 0:int)
      (while (<:bool var(j) 2:int)
        (block (set j += 1:int)))
      (if (>:bool var(n) 0:int) (block (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int))))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=recWhile
fn recWhile (n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 1:int)
    (block
      (def j 0:int)
      (while (<:bool var(j) 2:int)
        (set j += 1:int))
      (if (>:bool var(n) 0:int) (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int)))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

```mir
;; mir -O2 fn=recWhile stage=before
fn recWhile(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v1, v2)
  bb1(v4: any, v5: int, v6: int):
    v7: int = const 1
    v8: bool = lt v5, v7 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v6
  bb3:
    v9: int = const 0
    v10: any = const null
    br bb4(v10, v9)
  bb4(v11: any, v12: int):
    v13: int = const 2
    v14: bool = lt v12, v13 {pure}
    br_if v14, bb9, bb5
  bb5:
    v15: int = const 0
    v16: bool = gt v0, v15 {pure}
    br_if v16, bb7, bb6
  bb6:
    v17: any = const null
    br bb8(v17, v6)
  bb7:
    v18: int = const 1
    v19: int = sub v0, v18 {pure}
    v20: int = call_self recWhile(v19) {pure!}
    v21: int = const 1
    v22: int = add v20, v21 {pure}
    v23: int = add v6, v22 {pure}
    v24: any = convert.any v23
    br bb8(v24, v23)
  bb8(v25: any, v26: int):
    v27: int = add v26, v12 {pure}
    v28: int = const 1
    v29: int = add v5, v28 {pure}
    v30: any = convert.any v29
    br bb1(v30, v29, v27)
  bb9:
    v31: int = const 1
    v32: int = add v12, v31 {pure}
    v33: any = convert.any v32
    br bb4(v33, v32)
```

```mir
;; mir -O2 fn=recWhile
fn recWhile(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = gt v0, v1 {pure}
    v3: int = const 1
    v4: int = sub v0, v3 {pure}
    br bb1(v1, v1)
  bb1(v5: int, v6: int):
    v7: bool = lt v5, v3 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    br bb4(v1)
  bb4(v8: int):
    v9: int = const 2
    v10: bool = lt v8, v9 {pure}
    br_if v10, bb9, bb5
  bb5:
    br_if v2, bb7, bb6
  bb6:
    br bb8(v6)
  bb7:
    v11: int = call_self recWhile(v4) {pure!}
    v12: int = add v11, v3 {pure}
    v13: int = add v6, v12 {pure}
    br bb8(v13)
  bb8(v14: int):
    v15: int = add v14, v8 {pure}
    v16: int = add v5, v3 {pure}
    br bb1(v16, v15)
  bb9:
    v17: int = add v8, v3 {pure}
    br bb4(v17)
```

## HirLicmPass.checkOperandsInvariant & HirPurity.isDeterministic : Logic, Unary.NOT, Block, and ArrayOp.SIZE

```babble
def bigBool = fn nothrow (b: bool): int -> (def r = if b then (1) else (0) ; return r ; r)
def run = fn (f: bool, i: int, arr: frozen array of int): int -> (
  def a = 0 ; def k = 0
  while k < 2 then (
    a += bigBool(f and (i > 0)) + bigBool(not f) + bigInt((discard(i + 1) ; i + 2)) + bigInt(arr.size()) ; k += 1
  ) ; a
)
assert(run(true, 3, freeze(array.of(1, 2))) == 16)
```

```hir
;; hir -O0 fn=run
fn run (f:bool, i:int, arr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (+:int (+:int (call-fn bigBool (and var(f) (block (>:bool var(i) 0:int)))) (call-fn bigBool (not var(f)))) (call-fn bigInt (block
                (call name(discard) (+:int var(i) 1:int))
                (+:int var(i) 2:int)))) (call-fn bigInt (array-op SIZE var(arr)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (f:bool, i:int, arr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int (+:int (block
            (def b#1 (and var(f) (>:bool var(i) 0:int)))
            (def r#1 (if var(b#1) 1:int 0:int))
            var(r#1)) (block
            (def b#2 (not var(f)))
            (def r#2 (if var(b#2) 1:int 0:int))
            var(r#2))) (block
          (def x#1 (+:int var(i) 2:int))
          var(x#1))))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:int var($licm0) (block
              (def x#2 (array-op SIZE var(arr)))
              var(x#2))))
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool, v1: int, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: bool = const false
    br_if v0, bb1, bb2(v5)
  bb1:
    v6: int = const 0
    v7: bool = gt v1, v6 {pure}
    br bb2(v7)
  bb2(v8: bool):
    br_if v8, bb4, bb3
  bb3:
    v9: int = const 0
    br bb5(v9)
  bb4:
    v10: int = const 1
    br bb5(v10)
  bb5(v11: int):
    v12: bool = not v0 {pure}
    br_if v12, bb7, bb6
  bb6:
    v13: int = const 0
    br bb8(v13)
  bb7:
    v14: int = const 1
    br bb8(v14)
  bb8(v15: int):
    v16: int = add v11, v15 {pure}
    v17: int = const 2
    v18: int = add v1, v17 {pure}
    v19: int = add v16, v18 {pure}
    v20: any = const null
    br bb9(v20, v4, v3)
  bb9(v21: any, v22: int, v23: int):
    v24: int = const 2
    v25: bool = lt v22, v24 {pure}
    br_if v25, bb11, bb10
  bb10:
    ret v23
  bb11:
    v26: int = array_len v2 {read:frozen,throws}
    v27: int = add v19, v26 {pure}
    v28: int = add v23, v27 {pure}
    v29: int = const 1
    v30: int = add v22, v29 {pure}
    v31: any = convert.any v30
    br bb9(v31, v30, v28)
```

```mir
;; mir -O2 fn=run
fn run(v0: bool, v1: int, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: bool = const false
    br_if v0, bb1, bb2(v4)
  bb1:
    v5: bool = gt v1, v3 {pure}
    br bb2(v5)
  bb2(v6: bool):
    br_if v6, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v7: int = const 1
    br bb5(v7)
  bb5(v8: int):
    v9: bool = not v0 {pure}
    br_if v9, bb7, bb6
  bb6:
    br bb8(v3)
  bb7:
    v10: int = const 1
    br bb8(v10)
  bb8(v11: int):
    v12: int = add v8, v11 {pure}
    v13: int = const 2
    v14: int = add v1, v13 {pure}
    v15: int = add v12, v14 {pure}
    br bb9(v3, v3)
  bb9(v16: int, v17: int):
    v18: bool = lt v16, v13 {pure}
    br_if v18, bb11, bb10
  bb10:
    ret v17
  bb11:
    v19: int = array_len v2 {read:frozen,throws}
    v20: int = add v15, v19 {pure}
    v21: int = add v17, v20 {pure}
    v22: int = const 1
    v23: int = add v16, v22 {pure}
    br bb9(v23, v21)
```

## HirLicmPass.checkOperandsInvariant : Block with declaredVars, As (checked & unchecked), NameRead, and Const...

```babble
def topRO = 4
def run = fn (b: byte, raw: any): double -> (
  def a = 0.0 ; def k = 0
  while k < 2 then (
    a += bigSink(b as double) + double(bigInt(topRO) + bigInt((def t = int(b) ; t)) + bigInt(raw as int) + "  hi  ".trim().length()) ; k += 1
  ) ; a
)
assert(run(byte(2), 3) == 26.0)
```

```hir
;; hir -O0 fn=run
fn run (b:byte, raw:obj) -> double {pure unique}
  (def a 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (call-fn bigSink (as var(b) double|double|)) (call name(double) (+:int (+:int (+:int (call-fn bigInt name(topRO)) (call-fn bigInt (block
                      (def t (call name(int) var(b)))
                      var(t)))) (call-fn bigInt (as var(raw) int|int|))) (jcall String.length (jcall String.trim "  hi  "))))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (b:byte, raw:obj) -> double {pure unique}
  (def a 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (block
            (def x#1 (as var(b) double|double|))
            var(x#1)) (call name(double) (+:int (+:int (+:int (block
                    (def x#2 name(topRO))
                    var(x#2)) (block
                    (def x#3
                      (block
                        (def t (call name(int) var(b)))
                        var(t)))
                    var(x#3))) (block
                  (def x#4 (as var(raw) int|int|))
                  var(x#4))) 2:int))))
      (set k += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: byte, v1: any) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: double):
    v8: int = const 2
    v9: bool = lt v6, v8 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: any = convert.any v0
    v11: any = checkcast v10 <double|double|>
    v12: any = widen#5 v11
    v13: double = convert.double v12
    v14: any = opaque.NameRead() {impure}
    v15: any = opaque.NameRead() {impure}
    v16: any = widen#2 v15
    v17: int = convert.int v16
    v18: any = opaque.NameRead() {impure}
    v19: any = opaque.DynamicCall(v18, v0) {impure}
    v20: int = convert.int v19
    v21: int = add v17, v20 {pure}
    v22: any = checkcast v1 <int|int|>
    v23: any = widen#2 v22
    v24: int = convert.int v23
    v25: int = add v21, v24 {pure}
    v26: int = const 2
    v27: int = add v25, v26 {pure}
    v28: any = opaque.DynamicCall(v14, v27) {impure}
    v29: double = convert.double v28
    v30: double = add v13, v29 {pure}
    v31: double = add v7, v30 {pure}
    v32: int = const 1
    v33: int = add v6, v32 {pure}
    v34: any = convert.any v33
    br bb1(v34, v33, v31)
```

```mir
;; mir -O2 fn=run
fn run(v0: byte, v1: any) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: any = convert.any v0
    br bb1(v3, v2)
  bb1(v5: int, v6: double):
    v7: int = const 2
    v8: bool = lt v5, v7 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v6
  bb3:
    v9: any = checkcast v4 <double|double|>
    v10: any = widen#5 v9
    v11: double = convert.double v10
    v12: any = opaque.NameRead() {impure}
    v13: any = opaque.NameRead() {impure}
    v14: any = widen#2 v13
    v15: int = convert.int v14
    v16: any = opaque.NameRead() {impure}
    v17: any = opaque.DynamicCall(v16, v0) {impure}
    v18: int = convert.int v17
    v19: int = add v15, v18 {pure}
    v20: any = checkcast v1 <int|int|>
    v21: any = widen#2 v20
    v22: int = convert.int v21
    v23: int = add v19, v22 {pure}
    v24: int = add v23, v7 {pure}
    v25: any = opaque.DynamicCall(v12, v24) {impure}
    v26: double = convert.double v25
    v27: double = add v11, v26 {pure}
    v28: double = add v6, v27 {pure}
    v29: int = const 1
    v30: int = add v5, v29 {pure}
    br bb1(v30, v28)
```

## HirLicmPass : local non-capturing fn (DirectBabbleCall with VarRead callee) and non-value class ThisFieldGet

```babble
def C = class (def v: int) (
  def run = fn (n: int): int -> (
    def loc = fn nothrow (x: int): int -> (return x + 1 ; x)
    def a = 0 ; def k = 0
    while k < n then (a += loc(n) + this.v ; k += 1) ; a
  )
)
assert(C(3).run(2) == 12)
```

```hir
;; hir -O0 fn=C.run
fn C.run (n:int) -> int {nothrow unique}
  (def loc (fn-ref loc))
  (def a 0:int)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:int (call-fn loc var(n)) (get-this-field v)))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=C.run
fn C.run (n:int) -> int {nothrow unique}
  (def loc (fn-ref loc))
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int var(n) 1:int))
    (while (<:bool var(k) var(n))
      (block
        (set a += (+:int var($licm0) (get-this-field v)))
        (set k += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=C.run stage=before
fn C.run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef() {pure}
    v2: int = const 0
    v3: int = const 0
    v4: int = const 1
    v5: int = add v0, v4 {pure}
    v6: any = const null
    br bb1(v6, v3, v2)
  bb1(v7: any, v8: int, v9: int):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: any = slot_read this {pure}
    v12: int = load_field v11.v {read:shared,throws}
    v13: int = add v5, v12 {pure}
    v14: int = add v9, v13 {pure}
    v15: int = const 1
    v16: int = add v8, v15 {pure}
    v17: any = convert.any v16
    br bb1(v17, v16, v14)
```

```mir
;; mir -O2 fn=C.run
fn C.run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    v3: int = add v0, v2 {pure}
    br bb1(v1, v1)
  bb1(v4: int, v5: int):
    v6: bool = lt v4, v0 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v5
  bb3:
    v7: any = slot_read this {pure}
    v8: int = load_field v7.v {read:shared,throws}
    v9: int = add v3, v8 {pure}
    v10: int = add v5, v9 {pure}
    v11: int = add v4, v2 {pure}
    br bb1(v11, v10)
```

## LICM / HirPurity : loop-invariant as, TypeApply, array.size(), and pure overloaded helper

```babble
def id of T = fn (x: T): int -> (1)
def ov = overload (fn (a: int): int -> (a + 1), fn (a: int, b: int): int -> (a + b))
def run = fn (x: any, arr: array of int): int -> (
  def i = 0 ; def acc = 0
  while i < 4 then (acc += (x as int) + (id of int)(x as int) + arr.size() + ov(2) + ov(1, 2) ; i += 1) ; acc
)
assert(run(5, array.of(10, 20, 30)) == 60)
```

```hir
;; hir -O0 fn=run
fn run (x:obj, arr:obj) -> int {pure unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) 4:int)
    (block
      (set acc += (+:int (+:int (+:int (+:int (block (as var(x) int|int|)) (call (block (type-apply name(id))) (as var(x) int|int|))) (array-op SIZE var(arr))) (call-fn ov#1 2:int)) (call-fn ov#2 1:int 2:int)))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (x:obj, arr:obj) -> int {pure unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) 4:int)
    (block
      (set acc += (+:int (+:int (+:int (+:int (as var(x) int|int|) (call (type-apply name(id)) (as var(x) int|int|))) (array-op SIZE var(arr))) (block
              (overload-select #0 name(ov))
              3:int)) (block
            (overload-select #1 name(ov))
            3:int)))
      (set i += 1:int)))
  var(acc)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v2, v3)
  bb1(v5: any, v6: int, v7: int):
    v8: int = const 4
    v9: bool = lt v6, v8 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: any = checkcast v0 <int|int|>
    v11: int = convert.int v10
    v12: any = opaque.NameRead() {impure}
    v13: any = checkcast v0 <int|int|>
    v14: any = opaque.DynamicCall(v12, v13) {impure}
    v15: int = convert.int v14
    v16: int = add v11, v15 {pure}
    v17: int = array_len v1 {read:frozen,throws}
    v18: int = add v16, v17 {pure}
    v19: any = opaque.NameRead() {impure}
    v20: any = opaque.OverloadSelect(v19) {pure}
    v21: int = const 3
    v22: int = add v18, v21 {pure}
    v23: any = opaque.NameRead() {impure}
    v24: any = opaque.OverloadSelect(v23) {pure}
    v25: int = const 3
    v26: int = add v22, v25 {pure}
    v27: int = add v7, v26 {pure}
    v28: int = const 1
    v29: int = add v6, v28 {pure}
    v30: any = convert.any v29
    br bb1(v30, v29, v27)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 0
    br bb1(v2, v2)
  bb1(v3: int, v4: int):
    v5: int = const 4
    v6: bool = lt v3, v5 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v4
  bb3:
    v7: any = checkcast v0 <int|int|>
    v8: int = convert.int v7
    v9: any = opaque.NameRead() {impure}
    v10: any = checkcast v0 <int|int|>
    v11: any = opaque.DynamicCall(v9, v10) {impure}
    v12: int = convert.int v11
    v13: int = add v8, v12 {pure}
    v14: int = array_len v1 {read:frozen,throws}
    v15: int = add v13, v14 {pure}
    v16: any = opaque.NameRead() {impure}
    v17: int = const 3
    v18: int = add v15, v17 {pure}
    v19: any = opaque.NameRead() {impure}
    v20: int = add v18, v17 {pure}
    v21: int = add v4, v20 {pure}
    v22: int = const 1
    v23: int = add v3, v22 {pure}
    br bb1(v23, v21)
```
