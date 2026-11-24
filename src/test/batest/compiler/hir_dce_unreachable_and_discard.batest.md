# HIR DCE : Dead Pure Bindings, Unreachable Code, and Discard

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



## HirDcePass : dead pure bindings, discard(...), and unreachable code after return

```babble
def log = java.lang.StringBuilder()
def step = fn io (t: string): int -> (log.append(t) ; 1)
def run = fn io (): int -> (
    def deadPure = addMul(9, 4)
    discard(deadPure + fib(4))
    step("A")
    return pick(3)
    step("Z")
)
assert(run() == 8 and log.toString() == "A")
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def deadPure (call-fn addMul 9:int 4:int))
  (call name(discard) (+:int var(deadPure) (call-fn fib 4:int)))
  (call-fn step "A")
  (return (call-fn pick 3:int))
  (call-fn step "Z")
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (call-fn step "A")
  (return 8:int)
```

```mir
;; mir -O2 fn=run stage=before,final
fn run() -> int:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: any = const "A"
    v2: int = call step(v1) {impure}
    v3: int = const 8
    ret v3
```

## HirDcePass : unreachable code after continue and break in a for loop

```babble
def log = java.lang.StringBuilder()
def step = fn io (t: string): int -> (log.append(t) ; 1)
def run = fn io (xs: list of int): int -> (
    def total = 0
    for x in xs then (
        if x < 0 then (continue ; step("C"))
        if x > 10 then (break ; step("B"))
        total += x
    )
    total
)
assert(run(list(-1, 2, 5, 20, 99)) == 7 and log.toString() == "")
```

```hir
;; hir -O0 fn=run
fn run (xs:obj) -> int {io}
  (def total 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int)
        (block
          (continue)
          (call-fn step "C")))
      (if (>:bool var(x) 10:int)
        (block
          (break)
          (call-fn step "B")))
      (set total += var(x))))
  var(total)
```

```hir
;; hir -O1 fn=run
fn run (xs:obj) -> int {io}
  (def total 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (block (continue)))
      (if (>:bool var(x) 10:int) (block (break)))
      (set total += var(x))))
  var(total)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.java_iterable v0
    v3: any = const null
    br bb1(v3, v1)
  bb1(v4: any, v5: int):
    v6: bool = for_has_next v2
    br_if v6, bb2, bb8(v4)
  bb2:
    v7: any = for_next v2
    v8: any = checkcast v7 <int|int|>
    v9: int = convert.int v8
    v10: int = const 0
    v11: bool = lt v9, v10 {pure}
    br_if v11, bb9, bb3
  bb3:
    v12: any = const null
    br bb4
  bb4:
    v13: int = const 10
    v14: bool = gt v9, v13 {pure}
    br_if v14, bb7, bb5
  bb5:
    v15: any = const null
    br bb6
  bb6:
    v16: int = add v5, v9 {pure}
    v17: any = convert.any v16
    br bb1(v17, v16)
  bb7:
    v18: any = const null
    br bb8(v18)
  bb8(v19: any):
    ret v5
  bb9:
    br bb1(v4, v5)
```

```mir
;; mir -O2 fn=run
fn run(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.java_iterable v0
    br bb1(v1)
  bb1(v3: int):
    v4: bool = for_has_next v2
    br_if v4, bb2, bb5
  bb2:
    v5: any = for_next v2
    v6: any = checkcast v5 <int|int|>
    v7: int = convert.int v6
    v8: bool = lt v7, v1 {pure}
    br_if v8, bb6, bb3
  bb3:
    v9: int = const 10
    v10: bool = gt v7, v9 {pure}
    br_if v10, bb5, bb4
  bb4:
    v11: int = add v3, v7 {pure}
    br bb1(v11)
  bb5:
    ret v3
  bb6:
    br bb1(v3)
```

## HirDcePass & HirPurity : dead CoreCollectionRef, FunctionRef, TypeApply, As, and recursive-by-name bindings

```babble
def idG of T = fn (x: T): T -> (x)
def sRec = fn (n: int): int -> (if n <= 0 then (0) else (sRec(n - 1) + 1))
def run = fn (x: int): int -> (
  def d1 = list ; def d2 = fn (k: int): int -> (k + 1)
  def d3 = idG of int ; def d4 = x as double ; def d5 = sRec(2)
  x + 1
)
assert(run(4) == 5)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {pure unique}
  (def d1 core-col(list))
  (def d2 (fn-ref d2))
  (def d3 (type-apply name(idG)))
  (def d4 (as var(x) double|double|))
  (def d5 (call-fn sRec 2:int))
  (+:int var(x) 1:int)
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {pure unique}
  (def d2 (fn-ref d2))
  (def d3 (type-apply name(idG)))
  (def d4 (as var(x) double|double|))
  (+:int var(x) 1:int)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef() {pure}
    v2: any = opaque.NameRead() {impure}
    v3: any = convert.any v0
    v4: any = checkcast v3 <double|double|>
    v5: double = convert.double v4
    v6: int = const 1
    v7: int = add v0, v6 {pure}
    ret v7
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: any = convert.any v0
    v3: any = checkcast v2 <double|double|>
    v4: int = const 1
    v5: int = add v0, v4 {pure}
    ret v5
```

## HirDcePass & HirPurity : dead pure-local Block with nested Block + finite-collection for loop

```babble
def sumL = fn (xs: list of int): int -> (def s = 0 ; for e in xs then (s += e) ; s)
def run = fn (xs: list of int, p: int): int -> (
  def deadSum = sumL(xs)
  def deadBlk = (def a = p + 1 ; (def b = a + 2 ; b))
  xs.size()
)
assert(run(list(1, 2, 3), 4) == 3)
```

```hir
;; hir -O0 fn=run
fn run (xs:obj, p:int) -> int {nothrow unique}
  (def deadSum (call-fn sumL var(xs)))
  (def deadBlk
    (block
      (def a (+:int var(p) 1:int))
      (block
        (def b (+:int var(a) 2:int))
        var(b))))
  (jcall List.size var(xs))
```

```hir
;; hir -O1 fn=run
fn run (xs:obj, p:int) -> int {nothrow unique}
  (jcall List.size var(xs))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: int) -> int:
  bb0:
    v2: int = call_java v0.size() {pure,throws}
    ret v2
```

## HirPurity : dead mutable-arg call and throwing/non-terminating for loop stay preserved

```babble
def Box = class (def v: int) ()
def touch = fn (mut b: Box): int -> (b.v += 1 ; b.v)
def run = fn (mut b: Box, xs: list of int): int -> (
  def d1 = touch(b)
  def d2 = for x in xs then (assert(x > 0) ; x)
  b.v
)
assert(run(Box(0), list(1, 2)) == 1)
```

```hir
;; hir -O0 fn=run
fn run (b:obj, xs:obj) -> int {unique}
  (def d1 (call-fn touch var(b)))
  (def d2
    (for x in var(xs)
      (block
        (call name(assert) (>:bool var(x) 0:int))
        var(x))))
  (get-this-field v)
```

```hir
;; hir -O1 fn=run
fn run (b:obj, xs:obj) -> int {unique}
  (def d1
    (block
      (set-field var(b).v 1:int)
      (get-this-field v)))
  (def d2
    (for x in var(xs)
      (block
        (call name(assert) (>:bool var(x) 0:int))
        var(x))))
  (get-this-field v)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 1
    v3: any = opaque.DynamicFieldAssign(v0, v2) {impure}
    v4: int = load_field v0.v {read:shared,throws}
    v5: any = for_init.java_iterable v1
    v6: any = const null
    br bb1(v6)
  bb1(v7: any):
    v8: bool = for_has_next v5
    br_if v8, bb3, bb2
  bb2:
    v9: int = load_field v0.v {read:shared,throws}
    ret v9
  bb3:
    v10: any = for_next v5
    v11: any = checkcast v10 <int|int|>
    v12: int = convert.int v11
    v13: any = opaque.NameRead() {impure}
    v14: int = const 0
    v15: bool = gt v12, v14 {pure}
    v16: any = opaque.DynamicCall(v13, v15) {impure}
    v17: any = convert.any v12
    br bb1(v17)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any) -> int:
  bb0:
    v2: int = const 1
    v3: any = opaque.DynamicFieldAssign(v0, v2) {impure}
    v4: int = load_field v0.v {read:shared,throws}
    v5: any = for_init.java_iterable v1
    br bb1
  bb1:
    v6: bool = for_has_next v5
    br_if v6, bb3, bb2
  bb2:
    v7: int = load_field v0.v {read:shared,throws}
    ret v7
  bb3:
    v8: any = for_next v5
    v9: any = checkcast v8 <int|int|>
    v10: int = convert.int v9
    v11: any = opaque.NameRead() {impure}
    v12: int = const 0
    v13: bool = gt v10, v12 {pure}
    v14: any = opaque.DynamicCall(v11, v13) {impure}
    br bb1
```

## HirDcePass : unused uninitialized def and fn/class param defaults with dead code

```babble
def fnDef = fn (a: int = (def d = 1 ; 1 + 2), b: int = (def e = 2 ; 3 + 4)): int -> (
    def unused: int?
    a + b
)
def ClsDef = class (def x: int = (def d = 1 ; 2 + 3)) ()
assert(fnDef() == 10 and ClsDef().x == 5)
```

```hir
;; hir -O0 fn=fnDef
fn fnDef (a:int=(block
    (def d 1:int)
    (+:int 1:int 2:int)), b:int=(block
    (def e 2:int)
    (+:int 3:int 4:int))) -> int {nothrow unique}
  (def unused)
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=fnDef
fn fnDef (a:int=3:int, b:int=7:int) -> int {nothrow unique}
  (+:int var(a) var(b))
```

```mir
;; mir -O2 fn=fnDef stage=before,final
fn fnDef(v0: int, v1: int) -> int:
  bb0:
    v2: int = add v0, v1 {pure}
    ret v2
```

## HirDcePass : unreachable code after throw is eliminated

```babble
def log = java.lang.StringBuilder()
def afterThrow = fn io (b: bool): int -> (
    if b then (throw exception("boom") ; log.append("DEAD"))
    1
)
assert(afterThrow(false) == 1 and (try (afterThrow(true)) catch : exception then (9)) == 9 and log.toString() == "")
```

```hir
;; hir -O0 fn=afterThrow
fn afterThrow (b:bool) -> int {io}
  (if var(b)
    (block
      (throw (jnew BabbleException "boom"))
      (jcall StringBuilder.append name(log) "DEAD")))
  1:int
```

```hir
;; hir -O1 fn=afterThrow
fn afterThrow (b:bool) -> int {io}
  (if var(b) (throw (jnew BabbleException "boom")))
  1:int
```

```mir
;; mir -O2 fn=afterThrow stage=before
fn afterThrow(v0: bool) -> int:
  bb0:
    br_if v0, bb3, bb1
  bb1:
    v1: any = const null
    br bb2
  bb2:
    v2: int = const 1
    ret v2
  bb3:
    v3: any = const "boom"
    v4: any = new_java BabbleException(v3) {pure!}
    rethrow v4
```

```mir
;; mir -O2 fn=afterThrow
fn afterThrow(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = const 1
    ret v1
  bb2:
    v2: any = const "boom"
    v3: any = new_java BabbleException(v2) {pure!}
    rethrow v3
```

## HirDcePass : named discard argument stays preserved

```babble
def runNamed = fn (x: int): int -> (discard(value: x + 1) ; x)
assert(runNamed(5) == 5)
```

```hir
;; hir -O0,-O1 fn=runNamed
fn runNamed (x:int) -> int {nothrow unique}
  (call name(discard) value=(+:int var(x) 1:int))
  var(x)
```

```mir
;; mir -O2 fn=runNamed stage=before,final
fn runNamed(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: int = const 1
    v3: int = add v0, v2 {pure}
    v4: any = opaque.DynamicCall(v1, v3) {impure}
    ret v0
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (1-arg)

```babble
def discard = fn (x: int): int -> (x * 2)
discard = fn (x: int): int -> (x * 3)
def run = fn io (x: int): int -> (discard(x))
assert(run(4) == 12)
```

```hir
;; hir -O0,-O1 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: any = opaque.DynamicCall(v1, v0) {impure}
    v3: int = convert.int v2
    ret v3
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (2-arg)

```babble
def discard = fn (a: int, b: int): int -> (a + b)
discard = fn (a: int, b: int): int -> (a + b + 1)
def run = fn io (x: int): int -> (discard(x, 3))
assert(run(4) == 8)
```

```hir
;; hir -O0,-O1 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x) 3:int)
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead() {impure}
    v2: int = const 3
    v3: any = opaque.DynamicCall(v1, v0, v2) {impure}
    v4: int = convert.int v3
    ret v4
```
