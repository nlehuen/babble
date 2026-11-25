# Intra-module inlining, alpha-renaming, devirtualization guards, and constructor leak scans

References : `BIP014-I2`, `BIP027-I4`, `org.babblelang.opt.hir.InlinePass`.

```babble
;; babble preamble
def Pair = record (x: int, y: int) (
    def add = fn (a: int, b: int): int -> (this.x + a + b)
    def mix = fn (o: Pair): int -> (this.x + o.y)
)
def inc = fn (x: int): int -> (x + 1)
def widen = fn (x: int): double -> (x + 0.5)
def idG of T = fn (x: T): T -> (x)
```

## InlinePass.isMethodInlineCandidate : local class capturing outer var is not inlined

```babble
def make = fn (k: int): int -> (
    def Local = class (def x: int) (
        def addK = fn (y: int): int -> (this.x + y + k)
    )
    Local(3).addK(4)
)
assert(make(10) == 17)
```

```hir
;; hir -O0,-O1 fn=make
fn make (k:int) -> int {nothrow unique}
  (def Local (class-ref Local))
  (call-method (call var(Local) 3:int).addK 4:int)
```

```mir
;; mir -O2 fn=make stage=before,final
fn make() -> int:
  bb0:
    v0: any = opaque.ClassExpr(Local) {impure}
    v1: int = const 3
    v2: any = opaque.DynamicCall(v0, v1) {impure}
    v3: int = const 4
    v4: int = call_method v2.addK(v3) {impure}
    ret v4
```

## InlinePass.checkParamsAndBody : Java SAM parameter disqualifies inlining

```babble
def applyOp = fn io (x: int, shared op: java.util.function.IntUnaryOperator): int -> (op.applyAsInt(x))
def run = fn io (n: int): int -> (applyOp(n, fn (v: int): int -> (v * 2)))
assert(run(6) == 12)
```

```hir
;; hir -O0,-O1 fn=run
fn run (n:int) -> int {io}
  (call-fn applyOp var(n) (java-sam IntUnaryOperator (fn-ref <anon#0>)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead(applyOp) {read:shared}
    v2: any = opaque.FunctionRef(<anon>) {pure}
    v3: any = opaque.JavaSamAdapt(v2) {impure}
    v4: int = call applyOp(v0, v3) {impure}
    ret v4
```

## InlinePass.scanCalleeBody : self-recursive method call and for-body cell capture

```babble
def Counter = record (n: int) (
    def sum = fn (k: int): int -> (if k <= 0 then (this.n) else (k + this.sum(k - 1)))
)
def forBodyCell = fn (xs: list of int): int -> (
    def fs = (list of (fn (): int))()
    for e in xs then (def d = e * 2 ; fs.add(fn (): int -> (d)))
    fs.get(0)()
)
assert(Counter(1).sum(3) == 7 and forBodyCell(list(5, 6)) == 10 and inc(1) == 2)
```

```hir
;; hir -O0 record=Counter
record Counter (n:int)
  (def sum (fn-ref Counter.sum))
  fn Counter.sum (k:int) -> int {nothrow}
    (if (<=:bool var(k) 0:int) (block (get-this-field n)) (block (+:int var(k) (call-method this.sum (-:int var(k) 1:int)))))
```

```hir
;; hir -O1 record=Counter
record Counter (n:int)
  (def sum (fn-ref Counter.sum))
  fn Counter.sum (k:int) -> int {nothrow}
    (if (<=:bool var(k) 0:int) (get-this-field n) (+:int var(k) (call-method this.sum (-:int var(k) 1:int))))
```

```mir
;; mir -O2 record=Counter stage=before,final
record Counter:
  fn Counter.sum(v0: int) -> int:
    bb0:
      v1: int = const 0
      v2: bool = lte v0, v1 {pure}
      br_if v2, bb2, bb1
    bb1:
      v3: any = slot_read this {pure}
      v4: int = const 1
      v5: int = sub v0, v4 {pure}
      v6: int = call_method v3.sum(v5) {pure}
      v7: int = add v0, v6 {pure}
      br bb3(v7)
    bb2:
      v8: any = slot_read this {pure}
      v9: int = load_field v8.n {read:frozen}
      br bb3(v9)
    bb3(v10: int):
      ret v10
```

## InlinePass.scanCalleeBody and alphaRename : mutable class method ThisFieldAssign inlined

```babble
def MutBox = class (def x: int) (
    def setGet = fn mut (v: int): int -> (this.x = v ; this.x)
)
def run = fn (n: int): int -> (
    def b = MutBox(1)
    def a = b.setGet(n)
    a + Pair(2, 3).mix(Pair(4, 5))
)
assert(run(9) == 16)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {nothrow unique}
  (def b (call name(MutBox) 1:int))
  (def a (call-method var(b).setGet var(n)))
  (+:int var(a) (call-method (record-new Pair 2:int 3:int).mix (record-new Pair 4:int 5:int)))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {nothrow unique}
  (def b (call name(MutBox) 1:int))
  (def a
    (block
      (set-this-field x var(n))
      (get-this-field x)))
  (+:int var(a) (block
      (block
        (def $sr_$this_0 2:int)
        (def $sr_$this_1 3:int))
      (block (def $sr_o_1 5:int))
      (+:int var($sr_$this_0) var($sr_o_1))))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead(MutBox) {read:shared}
    v2: int = const 1
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    store_field v3.x, v0 {write:shared,throws}
    v4: int = load_field v3.x {read:shared,throws}
    v5: int = const 2
    v6: int = const 3
    v7: int = const 5
    v8: int = add v5, v7 {pure}
    v9: int = add v4, v8 {pure}
    ret v9
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead(MutBox) {read:shared}
    v2: int = const 1
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    store_field v3.x, v0 {write:shared,throws}
    v4: int = const 7
    v5: int = add v0, v4 {pure}
    ret v5
```

## InlinePass.inlineExpr : per-owner inline budget exhaustion (>32 calls)

```babble
def f = fn (x: int): int -> (
    def a = inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)
    def b = inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)
    def c = inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)+inc(x)
    a + b + c
)
assert(f(1) == 68)
```

```hir
;; hir -O0 fn=f
fn f (x:int) -> int {nothrow unique}
  (def a (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (call-fn inc var(x)) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))))
  (def b (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (call-fn inc var(x)) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))))
  (def c (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (call-fn inc var(x)) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))) (call-fn inc var(x))))
  (+:int (+:int var(a) var(b)) var(c))
```

```hir
;; hir -O1 fn=f
fn f (x:int) -> int {nothrow unique}
  (def a (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(x) var(x)) 3:int) var(x)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)))
  (def b (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(x) var(x)) 3:int) var(x)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)))
  (def c (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(x) var(x)) 3:int) var(x)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)) (+:int var(x) 1:int)))
  (+:int (+:int var(a) var(b)) var(c))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: int) -> int:
  bb0:
    v1: int = add v0, v0 {pure}
    v2: int = const 3
    v3: int = add v1, v2 {pure}
    v4: int = add v3, v0 {pure}
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: int = add v4, v6 {pure}
    v8: int = const 1
    v9: int = add v0, v8 {pure}
    v10: int = add v7, v9 {pure}
    v11: int = const 1
    v12: int = add v0, v11 {pure}
    v13: int = add v10, v12 {pure}
    v14: int = const 1
    v15: int = add v0, v14 {pure}
    v16: int = add v13, v15 {pure}
    v17: int = const 1
    v18: int = add v0, v17 {pure}
    v19: int = add v16, v18 {pure}
    v20: int = const 1
    v21: int = add v0, v20 {pure}
    v22: int = add v19, v21 {pure}
    v23: int = const 1
    v24: int = add v0, v23 {pure}
    v25: int = add v22, v24 {pure}
    v26: int = const 1
    v27: int = add v0, v26 {pure}
    v28: int = add v25, v27 {pure}
    v29: int = const 1
    v30: int = add v0, v29 {pure}
    v31: int = add v28, v30 {pure}
    v32: int = add v0, v0 {pure}
    v33: int = const 3
    v34: int = add v32, v33 {pure}
    v35: int = add v34, v0 {pure}
    v36: int = const 1
    v37: int = add v0, v36 {pure}
    v38: int = add v35, v37 {pure}
    v39: int = const 1
    v40: int = add v0, v39 {pure}
    v41: int = add v38, v40 {pure}
    v42: int = const 1
    v43: int = add v0, v42 {pure}
    v44: int = add v41, v43 {pure}
    v45: int = const 1
    v46: int = add v0, v45 {pure}
    v47: int = add v44, v46 {pure}
    v48: int = const 1
    v49: int = add v0, v48 {pure}
    v50: int = add v47, v49 {pure}
    v51: int = const 1
    v52: int = add v0, v51 {pure}
    v53: int = add v50, v52 {pure}
    v54: int = const 1
    v55: int = add v0, v54 {pure}
    v56: int = add v53, v55 {pure}
    v57: int = const 1
    v58: int = add v0, v57 {pure}
    v59: int = add v56, v58 {pure}
    v60: int = const 1
    v61: int = add v0, v60 {pure}
    v62: int = add v59, v61 {pure}
    v63: int = add v0, v0 {pure}
    v64: int = const 3
    v65: int = add v63, v64 {pure}
    v66: int = add v65, v0 {pure}
    v67: int = const 1
    v68: int = add v0, v67 {pure}
    v69: int = add v66, v68 {pure}
    v70: int = const 1
    v71: int = add v0, v70 {pure}
    v72: int = add v69, v71 {pure}
    v73: int = const 1
    v74: int = add v0, v73 {pure}
    v75: int = add v72, v74 {pure}
    v76: int = const 1
    v77: int = add v0, v76 {pure}
    v78: int = add v75, v77 {pure}
    v79: int = const 1
    v80: int = add v0, v79 {pure}
    v81: int = add v78, v80 {pure}
    v82: int = const 1
    v83: int = add v0, v82 {pure}
    v84: int = add v81, v83 {pure}
    v85: int = const 1
    v86: int = add v0, v85 {pure}
    v87: int = add v84, v86 {pure}
    v88: int = add v31, v62 {pure}
    v89: int = add v88, v87 {pure}
    ret v89
```

```mir
;; mir -O2 fn=f
fn f(v0: int) -> int:
  bb0:
    v1: int = add v0, v0 {pure}
    v2: int = const 3
    v3: int = add v1, v2 {pure}
    v4: int = add v3, v0 {pure}
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    v7: int = add v4, v6 {pure}
    v8: int = add v7, v6 {pure}
    v9: int = add v8, v6 {pure}
    v10: int = add v9, v6 {pure}
    v11: int = add v10, v6 {pure}
    v12: int = add v11, v6 {pure}
    v13: int = add v12, v6 {pure}
    v14: int = add v13, v6 {pure}
    v15: int = add v14, v6 {pure}
    v16: int = add v15, v15 {pure}
    v17: int = add v16, v13 {pure}
    ret v17
```

## InlinePass.inlineExpr : identity-order named args on record method skip inlining

```babble
def run = fn (x: int, y: int): int -> (
    def p = Pair(10, 0)
    p.add(a: x, b: y)
)
assert(run(2, 3) == 15)
```

```hir
;; hir -O0,-O1 fn=run
fn run (x:int, y:int) -> int {nothrow unique}
  (def p (record-new Pair 10:int 0:int))
  (call-method var(p).add a=var(x) b=var(y))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int, v1: int) -> int:
  bb0:
    v2: any = opaque.NameRead(Pair) {read:shared}
    v3: int = const 10
    v4: int = const 0
    v5: any = record_new Pair[v2](v3, v4) {pure}
    v6: int = call_method v5.add(v0, v1) {pure}
    ret v6
```

## InlinePass.canInlineReturnKind and performInline : generic return kind mismatch and boxed any result

```babble
def run = fn (p: int): int -> (
    def g = idG of int(p)
    def boxed: any = inc(p)
    g + (boxed as int)
)
assert(run(5) == 11)
```

```hir
;; hir -O0 fn=run
fn run (p:int) -> int {pure unique}
  (def g (call (type-apply name(idG)) var(p)))
  (def boxed (call-fn inc var(p)))
  (+:int var(g) (block (as var(boxed) int|int|)))
```

```hir
;; hir -O1 fn=run
fn run (p:int) -> int {pure unique}
  (def g (call (type-apply name(idG)) var(p)))
  (def boxed (+:int var(p) 1:int))
  (+:int var(g) (as var(boxed) int|int|))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead(idG) {read:shared}
    v2: int = opaque.DynamicCall(v1, v0) {impure}
    v3: int = const 1
    v4: int = add v0, v3 {pure}
    v5: any = convert.any v4
    v6: any = checkcast v5 <int|int|>
    v7: int = convert.int v6
    v8: int = add v2, v7 {pure}
    ret v8
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.NameRead(idG) {read:shared}
    v2: int = opaque.DynamicCall(v1, v0) {impure}
    v3: int = const 1
    v4: int = add v0, v3 {pure}
    v5: int = add v2, v4 {pure}
    ret v5
```

## InlinePass.performInline : cell receiver spill, arg mutated in later arg, and conditional local

```babble
def combine = fn (a: int, b: int): int -> (a * 10 + b)
def run = fn (n: int, fl: bool): int -> (
    def p = Pair(n, 0) ; def cap = fn (): int -> (p.x) ; def x = n
    def r1 = combine(x, (x = x + 1 ; x))
    def g = if false then (combine(y, 1)) else (0)
    def y = if fl then (2) else (3)
    r1 + g + p.add(1, y) + cap()
)
assert(run(4, true) == 56 and run(4, false) == 57)
```

```hir
;; hir -O0 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def p (record-new Pair var(n) 0:int))
  (def cap (fn-ref cap))
  (def x var(n))
  (def r1 (call-fn combine var(x) (block
        (set x (+:int var(x) 1:int))
        var(x))))
  (def g (if false:bool (block (call-fn combine var(y) 1:int)) (block 0:int)))
  (def y (if var(fl) (block 2:int) (block 3:int)))
  (+:int (+:int (+:int var(r1) var(g)) (call-method var(p).add 1:int var(y))) (call-fn cap))
```

```hir
;; hir -O1 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def p (record-new Pair var(n) 0:int))
  (def cap (fn-ref cap))
  (def x var(n))
  (def r1
    (block
      (def a var(x))
      (def b#1
        (block
          (set x (+:int var(x) 1:int))
          var(x)))
      (+:int (*:int var(a) 10:int) var(b#1))))
  (def y (if var(fl) 2:int 3:int))
  (+:int (+:int var(r1) (block
        (def $this var(p))
        (def b#2 var(y))
        (+:int (+:int (get-this-field x) 1:int) var(b#2)))) (call-fn cap))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(Pair) {read:shared}
    v3: int = const 0
    v4: any = record_new Pair[v2](v0, v3) {pure}
    slot_init p, v4 {write:shared}
    v5: any = opaque.FunctionRef(cap) {read:shared}
    v6: int = const 1
    v7: int = add v0, v6 {pure}
    v8: int = const 10
    v9: int = mul v0, v8 {pure}
    v10: int = add v9, v7 {pure}
    br_if v1, bb2, bb1
  bb1:
    v11: int = const 3
    br bb3(v11)
  bb2:
    v12: int = const 2
    br bb3(v12)
  bb3(v13: int):
    slot_init y, v13 {write:shared,throws}
    v14: any = slot_read p {read:shared}
    v15: int = slot_read y {read:shared,throws}
    v16: int = load_field v14.x {read:frozen}
    v17: int = const 1
    v18: int = add v16, v17 {pure}
    v19: int = add v18, v15 {pure}
    v20: int = add v10, v19 {pure}
    v21: int = call cap() {impure}
    v22: int = add v20, v21 {pure}
    ret v22
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(Pair) {read:shared}
    v3: int = const 0
    v4: any = record_new Pair[v2](v0, v3) {pure}
    slot_init p, v4 {write:shared}
    v5: any = opaque.FunctionRef(cap) {read:shared}
    v6: int = const 1
    v7: int = add v0, v6 {pure}
    v8: int = const 10
    v9: int = mul v0, v8 {pure}
    v10: int = add v9, v7 {pure}
    br_if v1, bb2, bb1
  bb1:
    v11: int = const 3
    br bb3(v11)
  bb2:
    v12: int = const 2
    br bb3(v12)
  bb3(v13: int):
    slot_init y, v13 {write:shared,throws}
    v14: any = slot_read p {read:shared}
    v15: int = slot_read y {read:shared,throws}
    v16: int = load_field v14.x {read:frozen}
    v17: int = add v16, v6 {pure}
    v18: int = add v17, v15 {pure}
    v19: int = add v10, v18 {pure}
    v20: int = call cap() {impure}
    v21: int = add v19, v20 {pure}
    ret v21
```

## InlinePass.alphaRename : VarRead widening substitution, uninitialized Def, and for-body local def

```babble
def choose = fn (fl: bool, a: int, b: int): int -> (def y: int? ; if fl then (y = a) else (y = b) ; y!)
def sum2 = fn (xs: list of int): int -> (def s = 0 ; for e in xs then (def d = e * 2 ; s += d) ; s)
def run = fn (p: int, fl: bool): double -> (
    widen(p) + double(choose(fl, p, 9) + sum2(list(1, 2)))
)
assert(run(3, true) == 12.5 and run(3, false) == 18.5)
```

```hir
;; hir -O0 fn=run
fn run (p:int, fl:bool) -> double {pure unique}
  (+:double (call-fn widen var(p)) (call name(double) (+:int (call-fn choose var(fl) var(p) 9:int) (call-fn sum2 (core-new list 1:int 2:int)))))
```

```hir
;; hir -O1 fn=run
fn run (p:int, fl:bool) -> double {pure unique}
  (+:double (+:double var(p) 0.5:double) (call name(double) (+:int (block
          (def y)
          (if var(fl) (set y var(p)) (set y 9:int))
          (nonnull var(y))) (block
          (def xs (core-new list 1:int 2:int))
          (def s 0:int)
          (for e in var(xs)
            (block
              (def d (*:int var(e) 2:int))
              (set s += var(d))))
          var(s)))))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: bool) -> double:
  bb0:
    v2: double = convert.double v0
    v3: double = const 0.5
    v4: double = add v2, v3 {pure}
    v5: any = opaque.NameRead(double) {read:shared,throws}
    v6: any = const null
    br_if v1, bb2, bb1
  bb1:
    v7: int = const 9
    v8: any = convert.any v7
    v9: int = convert.int v8
    br bb3(v9, v8)
  bb2:
    v10: any = convert.any v0
    v11: int = convert.int v10
    br bb3(v11, v10)
  bb3(v12: int, v13: any):
    v14: int = nonnull v13
    v15: int = const 1
    v16: int = const 2
    v17: any = opaque.CoreCollectionCall(list, v15, v16) {impure}
    v18: int = const 0
    v19: any = for_init.java_iterable v17
    v20: any = const null
    br bb4(v20, v18)
  bb4(v21: any, v22: int):
    v23: bool = for_has_next v19
    br_if v23, bb6, bb5
  bb5:
    v24: int = add v14, v22 {pure}
    v25: double = opaque.DynamicCall(v5, v24) {impure}
    v26: double = add v4, v25 {pure}
    ret v26
  bb6:
    v27: any = for_next v19
    v28: any = checkcast v27 <int|int|>
    v29: int = convert.int v28
    v30: int = const 2
    v31: int = mul v29, v30 {pure}
    v32: int = add v22, v31 {pure}
    v33: any = convert.any v32
    br bb4(v33, v32)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: bool) -> double:
  bb0:
    v2: double = convert.double v0
    v3: double = const 0.5
    v4: double = add v2, v3 {pure}
    v5: any = opaque.NameRead(double) {read:shared,throws}
    br_if v1, bb2, bb1
  bb1:
    v6: any = const 9
    br bb3(v6)
  bb2:
    v7: any = convert.any v0
    br bb3(v7)
  bb3(v8: any):
    v9: int = nonnull v8
    v10: int = const 1
    v11: int = const 2
    v12: any = opaque.CoreCollectionCall(list, v10, v11) {impure}
    v13: int = const 0
    v14: any = for_init.java_iterable v12
    br bb4(v13)
  bb4(v15: int):
    v16: bool = for_has_next v14
    br_if v16, bb6, bb5
  bb5:
    v17: int = add v9, v15 {pure}
    v18: double = opaque.DynamicCall(v5, v17) {impure}
    v19: double = add v4, v18 {pure}
    ret v19
  bb6:
    v20: any = for_next v14
    v21: any = checkcast v20 <int|int|>
    v22: int = convert.int v21
    v23: int = mul v22, v11 {pure}
    v24: int = add v15, v23 {pure}
    br bb4(v24)
```

## InlinePass.alphaRename : Move with unique parameter substitution

```babble
def mvUniq = fn (unique xs: list of int): list of int -> (move(xs))
def mvOpt = fn (unique xs: (list of int)?): (list of int)? -> (move(xs))
def run = fn (n: int): int -> (
    def xs = mvUniq(list(n, n + 1))
    def nl = mvOpt(null)
    xs.get(0) + xs.get(1) + (if nl == null then (0) else (1))
)
assert(run(4) == 9)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def xs (call-fn mvUniq (core-new list var(n) (+:int var(n) 1:int))))
  (def nl (call-fn mvOpt null))
  (+:int (+:int (jcall List.get var(xs) 0:int) (jcall List.get var(xs) 1:int)) (block (if (==:bool var(nl) null) (block 0:int) (block 1:int))))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (def xs#1
    (block
      (def xs#2 (core-new list var(n) (+:int var(n) 1:int)))
      (move var(xs#2))))
  (def nl (move null))
  (+:int (+:int (jcall List.get var(xs#1) 0:int) (jcall List.get var(xs#1) 1:int)) (if (==:bool var(nl) null) 0:int 1:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: any = opaque.CoreCollectionCall(list, v0, v2) {impure}
    v4: any = move v3
    v5: any = const null
    v6: any = move v5
    v7: int = const 0
    v8: any = call_java v4.get(v7) {impure}
    v9: any = checkcast v8 <int|int|>
    v10: int = convert.int v9
    v11: int = const 1
    v12: any = call_java v4.get(v11) {impure}
    v13: any = checkcast v12 <int|int|>
    v14: int = convert.int v13
    v15: int = add v10, v14 {pure}
    v16: any = const null
    v17: any = eq v6, v16 {pure,throws}
    br_if v17, bb2, bb1
  bb1:
    v18: any = narrow.nonnull v6 <non-null>
    v19: int = const 1
    br bb3(v19)
  bb2:
    v20: int = const 0
    br bb3(v20)
  bb3(v21: int):
    v22: int = add v15, v21 {pure}
    ret v22
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: any = opaque.CoreCollectionCall(list, v0, v2) {impure}
    v4: any = move v3
    v5: any = const null
    v6: int = const 0
    v7: any = call_java v4.get(v6) {impure}
    v8: any = checkcast v7 <int|int|>
    v9: int = convert.int v8
    v10: any = call_java v4.get(v1) {impure}
    v11: any = checkcast v10 <int|int|>
    v12: int = convert.int v11
    v13: int = add v9, v12 {pure}
    v14: any = eq v5, v5 {pure,throws}
    br_if v14, bb2, bb1
  bb1:
    br bb3(v1)
  bb2:
    br bb3(v6)
  bb3(v15: int):
    v16: int = add v13, v15 {pure}
    ret v16
```

## InlinePass.alphaRename : borrow-lambda devirtualization guards for named arguments

```babble
def apReord = fn (x: int, f: fn (a: int, b: int): int): int -> (f(b: 2, a: x))
def apIdNam = fn (x: int, f: fn (a: int, b: int): int): int -> (f(a: x, b: 3))
def run = fn (x: int): int -> (
    apReord(x, fn (a: int, b: int): int -> (a * 10 + b)) +
    apIdNam(x, fn (a: int, b: int): int -> (a * 10 + b))
)
assert(run(4) == 85)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {nothrow unique}
  (+:int (call-fn apReord var(x) (fn-ref <anon#0>)) (call-fn apIdNam var(x) (fn-ref <anon#1>)))
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {nothrow unique}
  (+:int (call (fn-ref <anon#0>) b=2:int a=var(x)) (call (fn-ref <anon#1>) a=var(x) b=3:int))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef(<anon>) {pure}
    v2: int = const 2
    v3: int = opaque.DynamicCall(v1, v2, v0) {impure}
    v4: any = opaque.FunctionRef(<anon>) {pure}
    v5: int = const 3
    v6: int = opaque.DynamicCall(v4, v0, v5) {impure}
    v7: int = add v3, v6 {pure}
    ret v7
```

## InlinePass.alphaRename : borrow-lambda devirtualization guards for arity mismatch and non-literal callee

```babble
def apOpt = fn (x: int, f: fn (int): int): int -> (f(x))
def run = fn (x: int, dyn: fn (int): int): int -> (
    apOpt(x, fn (a: int, b: int = 1): int -> (a + b)) + apOpt(x, dyn)
)
assert(run(4, fn (z: int): int -> (z * 2)) == 13)
```

```hir
;; hir -O0 fn=run
fn run (x:int, dyn:obj) -> int {pure unique}
  (+:int (call-fn apOpt var(x) (fn-ref <anon#0>)) (call-fn apOpt var(x) var(dyn)))
```

```hir
;; hir -O1 fn=run
fn run (x:int, dyn:obj) -> int {pure unique}
  (+:int (call (fn-ref <anon#0>) var(x)) (call var(dyn) var(x)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: any = opaque.FunctionRef(<anon>) {pure}
    v3: int = opaque.DynamicCall(v2, v0) {impure}
    v4: int = opaque.DynamicCall(v1, v0) {impure}
    v5: int = add v3, v4 {pure}
    ret v5
```

## InlinePass.isMethodDefinitelyDefinedForInline and scanThisLeak : statements, default method, and sibling leak

```babble
def Tag = interface (def tag = fn (): int -> (7))
def Good = class (p: Pair) implements Tag (
    assert(p.x > 0) ; def y = p.x + 1 ; def get = fn (): int -> (this.y)
)
def Leak2 = class (def x: int) (
    def box = list(this, 1) ; def get = fn (): int -> (this.x)
)
assert(Good(Pair(3, 0)).get() + Good(Pair(3, 0)).tag() + Leak2(3).get() == 14)
```

```hir
;; hir -O0 top-level
top-level:
  (def Pair (class-ref Pair))
  (def inc (fn-ref inc))
  (def widen (fn-ref widen))
  (def idG (fn-ref idG))
  (def Tag (iface-ref Tag))
  (def Good (class-ref Good))
  (def Leak2 (class-ref Leak2))
  (call name(assert) (==:bool (+:int (+:int (call-method (call name(Good) (record-new Pair 3:int 0:int)).get) (call (select (call name(Good) (record-new Pair 3:int 0:int)).tag))) (call-method (call name(Leak2) 3:int).get)) 14:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def Pair (class-ref Pair))
  (def inc (fn-ref inc))
  (def widen (fn-ref widen))
  (def idG (fn-ref idG))
  (def Tag (iface-ref Tag))
  (def Good (class-ref Good))
  (def Leak2 (class-ref Leak2))
  (call name(assert) (==:bool (+:int (+:int (block
            (def $this (call name(Good) (record-new Pair 3:int 0:int)))
            (get-this-field y)) (call (select (call name(Good) (record-new Pair 3:int 0:int)).tag))) (call-method (call name(Leak2) 3:int).get)) 14:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## InlinePass.isDefinitelyDefinedPureCallee : local definitely-defined vs forward-referenced function

```babble
def run = fn (n: int, fl: bool): int -> (
    def loc = fn (v: int): int -> (v + 1)
    def g = if false then (cond(n)) else (0)
    def cond = fn (v: int): int -> (if fl then (v + 2) else (v + 3))
    loc(n) + g + cond(n)
)
assert(run(3, true) == 9 and run(3, false) == 10)
```

```hir
;; hir -O0 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def loc (fn-ref loc))
  (def g (if false:bool (block (call-fn cond var(n))) (block 0:int)))
  (def cond (fn-ref cond))
  (+:int (+:int (call-fn loc var(n)) var(g)) (call-fn cond var(n)))
```

```hir
;; hir -O1 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def loc (fn-ref loc))
  (def cond (fn-ref cond))
  (+:int (+:int var(n) 1:int) (call-fn cond var(n)))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef(loc) {pure}
    v2: any = opaque.FunctionRef(cond) {read:shared}
    slot_init cond, v2 {write:shared,throws}
    v3: int = const 1
    v4: int = add v0, v3 {pure}
    v5: any = slot_read cond {read:shared,throws}
    v6: int = call cond(v0) {impure}
    v7: int = add v4, v6 {pure}
    ret v7
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: any = opaque.FunctionRef(cond) {read:shared}
    slot_init cond, v1 {write:shared,throws}
    v2: int = const 1
    v3: int = add v0, v2 {pure}
    v4: any = slot_read cond {read:shared,throws}
    v5: int = call cond(v0) {impure}
    v6: int = add v3, v5 {pure}
    ret v6
```
