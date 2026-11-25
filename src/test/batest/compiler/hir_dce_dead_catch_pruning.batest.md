# HIR DCE : Dead-Catch and Pure Try/Finally Elimination

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble
;; babble module=dce_mod.ba
export def val = 10
```

```babble
;; babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```



## HirLicmPass & HirPurity.isDeterministic : loop-invariant ArrayOp.SIZE, As, TypeApply, Logic, and If

```babble
def idT of T = fn (x: T): T -> (x)
def run = fn (arr: array of int, flag: bool, n: int): double -> (
  def i = 0 ; def acc = 0.0
  while i < n then (
    def k = double(arr.size()) + (if flag and true then (idT of double(1.0)) else (0.0)) + (1 as double)
    acc += k ; i += 1
  )
  acc
)
assert(run((array of int).of(10, 20), true, 3) == 12.0)
```

```hir
;; hir -O0 fn=run
fn run (arr:obj, flag:bool, n:int) -> double {pure unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def k (+:double (+:double (call name(double) (array-op SIZE var(arr))) (block (if (and var(flag) true:bool) (block (call (type-apply name(idT)) 1.0:double)) (block 0.0:double)))) (block (as 1:int double|double|))))
      (set acc += var(k))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (arr:obj, flag:bool, n:int) -> double {pure unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def k (+:double (+:double (call name(double) (array-op SIZE var(arr))) (if (and var(flag) true:bool) (call (type-apply name(idT)) 1.0:double) 0.0:double)) (as 1:int double|double|)))
      (set acc += var(k))
      (set i += 1:int)))
  var(acc)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool, v2: int) -> double:
  bb0:
    v3: int = const 0
    v4: double = const 0.0
    v5: any = const null
    br bb1(v5, v3, v4)
  bb1(v6: any, v7: int, v8: double):
    v9: bool = lt v7, v2 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: any = opaque.NameRead(double) {read:shared,throws}
    v11: int = array_len v0 {read:frozen,throws}
    v12: double = opaque.DynamicCall(v10, v11) {impure}
    v13: bool = const false
    br_if v1, bb4, bb5(v13)
  bb4:
    v14: bool = const true
    br bb5(v14)
  bb5(v15: bool):
    br_if v15, bb7, bb6
  bb6:
    v16: double = const 0.0
    br bb8(v16)
  bb7:
    v17: any = opaque.NameRead(idT) {read:shared}
    v18: double = const 1.0
    v19: double = opaque.DynamicCall(v17, v18) {impure}
    br bb8(v19)
  bb8(v20: double):
    v21: double = add v12, v20 {pure}
    v22: int = const 1
    v23: any = convert.any v22
    v24: any = checkcast v23 <double|double|>
    v25: double = convert.double v24
    v26: double = add v21, v25 {pure}
    v27: double = add v8, v26 {pure}
    v28: int = const 1
    v29: int = add v7, v28 {pure}
    v30: any = convert.any v29
    br bb1(v30, v29, v27)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool, v2: int) -> double:
  bb0:
    v3: int = const 0
    v4: double = const 0.0
    br bb1(v3, v4)
  bb1(v5: int, v6: double):
    v7: bool = lt v5, v2 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = opaque.NameRead(double) {read:shared,throws}
    v9: int = array_len v0 {read:frozen,throws}
    v10: double = opaque.DynamicCall(v8, v9) {impure}
    v11: bool = const false
    br_if v1, bb4, bb5(v11)
  bb4:
    v12: bool = const true
    br bb5(v12)
  bb5(v13: bool):
    br_if v13, bb7, bb6
  bb6:
    br bb8(v4)
  bb7:
    v14: any = opaque.NameRead(idT) {read:shared}
    v15: double = const 1.0
    v16: double = opaque.DynamicCall(v14, v15) {impure}
    br bb8(v16)
  bb8(v17: double):
    v18: double = add v10, v17 {pure}
    v19: int = const 1
    v20: any = const 1
    v21: any = checkcast v20 <double|double|>
    v22: double = convert.double v21
    v23: double = add v18, v22 {pure}
    v24: double = add v6, v23 {pure}
    v25: int = add v5, v19 {pure}
    br bb1(v25, v24)
```

## Canonical HIR golden : dead-catch elimination on pure nothrow try body

```babble
def safeAdd = fn (x: int): int -> (
    try (x + 1) catch : exception then (0)
)
assert(safeAdd(41) == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (try
    (block
      (+:int var(x) 1:int))
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      0:int))
```

```hir
;; hir -O1 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (+:int var(x) 1:int)
```

```mir
;; mir -O2 fn=safeAdd stage=before,final
fn safeAdd(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    ret v2
```

## HirDcePass : pure try with dead catch and non-empty finally strips catch only

```babble
def log = java.lang.StringBuilder()
def keepFinally = fn io (x: int): int -> (
    try (x + 1) catch : exception then (0) finally (log.append("F"))
)
assert(keepFinally(41) == 42 and log.toString() == "F")
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=keepFinally
fn keepFinally (x:int) -> int {io}
  (try
    (block
      (+:int var(x) 1:int))
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      0:int)
    (finally
      (jcall StringBuilder.append name(log) "F")))
```

```hir
;; hir -O1 fn=keepFinally
fn keepFinally (x:int) -> int {io}
  (try
    (block
      (+:int var(x) 1:int))
    (finally
      (jcall StringBuilder.append name(log) "F")))
```

```mir
;; mir -O2 fn=keepFinally stage=before
fn keepFinally(v0: int) -> int:
  bb0:
    br bb1
  bb1 [unwind: * -> bb2]:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    br bb7
  bb2(v3: any) [handler]:
    br bb3
  bb3:
    br bb4
  bb4 [unwind: Throwable -> bb5]:
    v4: any = opaque.NameRead(log) {read:shared,throws}
    v5: any = const "F"
    v6: any = call_java v4.append(v5) {impure}
    v7: any = checkcast v6 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb6
  bb5(v8: any) [handler]:
    add_suppressed v3, v8
    br bb6
  bb6:
    rethrow v3
  bb7:
    v9: any = opaque.NameRead(log) {read:shared,throws}
    v10: any = const "F"
    v11: any = call_java v9.append(v10) {impure}
    v12: any = checkcast v11 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb8
  bb8:
    ret v2
```

```mir
;; mir -O2 fn=keepFinally
fn keepFinally(v0: int) -> int:
  bb0:
    br bb1
  bb1 [unwind: * -> bb2]:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    br bb6
  bb2(v3: any) [handler]:
    br bb3
  bb3 [unwind: Throwable -> bb4]:
    v4: any = opaque.NameRead(log) {read:shared,throws}
    v5: any = const "F"
    v6: any = call_java v4.append(v5) {impure}
    v7: any = checkcast v6 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb5
  bb4(v8: any) [handler]:
    add_suppressed v3, v8
    br bb5
  bb5:
    rethrow v3
  bb6:
    v9: any = opaque.NameRead(log) {read:shared,throws}
    v10: any = const "F"
    v11: any = call_java v9.append(v10) {impure}
    v12: any = checkcast v11 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb7
  bb7:
    ret v2
```

## HirDcePass : top-level namespace try with dead catch

```babble
def topTry = try (def t = 20 ; t + 22) catch : exception then (0)
assert(topTry == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (block
    (def $try0
      (try
        (block
          (def t 20:int)
          (+:int var(t) 22:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          0:int)))
    (def topTry var($try0)))
  (call name(assert) (==:bool name(topTry) 42:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def topTry 42:int)
  (call name(assert) (==:bool name(topTry) 42:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## HirDcePass : two catches with DCE and finally-only DCE

```babble
def log = java.lang.StringBuilder()
def twoCatches = fn io (s: string): int -> (
    try (java.lang.Integer.parseInt(s))
    catch : java.lang.NumberFormatException then (def d1 = 1 + 2 ; -1)
    catch : exception then (def d2 = 3 + 4 ; -2)
    finally (def df = 5 + 6 ; log.append("X"))
)
assert(twoCatches("bad") == -1 and log.toString() == "X")
```

```hir
;; hir -O0 fn=twoCatches
fn twoCatches (s:obj) -> int {io}
  (try
    (block
      (jcall Integer.parseInt var(s)))
    (catch _:java|java.lang.NumberFormatException|java.lang.NumberFormatException
      (def d1 (+:int 1:int 2:int))
      -1:int)
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      (def d2 (+:int 3:int 4:int))
      -2:int)
    (finally
      (def df (+:int 5:int 6:int))
      (jcall StringBuilder.append name(log) "X")))
```

```hir
;; hir -O1 fn=twoCatches
fn twoCatches (s:obj) -> int {io}
  (try
    (block
      (jcall Integer.parseInt var(s)))
    (catch _:java|java.lang.NumberFormatException|java.lang.NumberFormatException
      -1:int)
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      -2:int)
    (finally
      (jcall StringBuilder.append name(log) "X")))
```

```mir
;; mir -O2 fn=twoCatches stage=before
fn twoCatches(v0: any) -> int:
  bb0:
    br bb1
  bb1 [unwind: NumberFormatException -> bb5, BabbleException -> bb2, * -> bb7]:
    v1: int = call_java Integer.parseInt(v0) {impure}
    br bb13
  bb2(v2: any) [handler]:
    br bb3
  bb3 [unwind: * -> bb7]:
    v3: int = const -2
    br bb4
  bb4:
    v4: any = opaque.NameRead(log) {read:shared,throws}
    v5: any = const "X"
    v6: any = call_java v4.append(v5) {impure}
    v7: any = checkcast v6 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb14(v3)
  bb5(v8: any) [handler]:
    br bb6
  bb6 [unwind: * -> bb7]:
    v9: int = const -1
    br bb12
  bb7(v10: any) [handler]:
    br bb8
  bb8:
    br bb9
  bb9 [unwind: Throwable -> bb10]:
    v11: any = opaque.NameRead(log) {read:shared,throws}
    v12: any = const "X"
    v13: any = call_java v11.append(v12) {impure}
    v14: any = checkcast v13 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb11
  bb10(v15: any) [handler]:
    add_suppressed v10, v15
    br bb11
  bb11:
    rethrow v10
  bb12:
    v16: any = opaque.NameRead(log) {read:shared,throws}
    v17: any = const "X"
    v18: any = call_java v16.append(v17) {impure}
    v19: any = checkcast v18 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb14(v9)
  bb13:
    v20: any = opaque.NameRead(log) {read:shared,throws}
    v21: any = const "X"
    v22: any = call_java v20.append(v21) {impure}
    v23: any = checkcast v22 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb14(v1)
  bb14(v24: int):
    ret v24
```

```mir
;; mir -O2 fn=twoCatches
fn twoCatches(v0: any) -> int:
  bb0:
    br bb1
  bb1 [unwind: NumberFormatException -> bb5, BabbleException -> bb2, * -> bb7]:
    v1: int = call_java Integer.parseInt(v0) {impure}
    br bb12
  bb2(v2: any) [handler]:
    br bb3
  bb3 [unwind: * -> bb7]:
    v3: int = const -2
    br bb4
  bb4:
    v4: any = opaque.NameRead(log) {read:shared,throws}
    v5: any = const "X"
    v6: any = call_java v4.append(v5) {impure}
    v7: any = checkcast v6 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb13(v3)
  bb5(v8: any) [handler]:
    br bb6
  bb6 [unwind: * -> bb7]:
    v9: int = const -1
    br bb11
  bb7(v10: any) [handler]:
    br bb8
  bb8 [unwind: Throwable -> bb9]:
    v11: any = opaque.NameRead(log) {read:shared,throws}
    v12: any = const "X"
    v13: any = call_java v11.append(v12) {impure}
    v14: any = checkcast v13 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb10
  bb9(v15: any) [handler]:
    add_suppressed v10, v15
    br bb10
  bb10:
    rethrow v10
  bb11:
    v16: any = opaque.NameRead(log) {read:shared,throws}
    v17: any = const "X"
    v18: any = call_java v16.append(v17) {impure}
    v19: any = checkcast v18 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb13(v9)
  bb12:
    v20: any = opaque.NameRead(log) {read:shared,throws}
    v21: any = const "X"
    v22: any = call_java v20.append(v21) {impure}
    v23: any = checkcast v22 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb13(v1)
  bb13(v24: int):
    ret v24
```
