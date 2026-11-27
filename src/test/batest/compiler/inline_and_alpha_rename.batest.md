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
    def Local = class (var x: int) (
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

```hir
;; hir -O2 fn=make
fn make (k:int) -> int {nothrow unique}
  (def Local (class-ref Local))
  (call-method (call var(Local) 3:int).addK 4:int)
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

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {io}
  (call-fn applyOp var(n) (java-sam IntUnaryOperator (fn-ref <anon#0>)))
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

```hir
;; hir -O2 record=Counter
record Counter (n:int)
  (def sum (fn-ref Counter.sum))
  fn Counter.sum (k:int) -> int {nothrow}
    (if (<=:bool var(k) 0:int) (get-this-field n) (+:int var(k) (call-method this.sum (-:int var(k) 1:int))))
```

## InlinePass.scanCalleeBody and alphaRename : mutable class method ThisFieldAssign inlined

```babble
def MutBox = class (var x: int) (
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

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {nothrow unique}
  (def b (call name(MutBox) 1:int))
  (def a
    (block
      (set-this-field x var(n))
      var(n)))
  (+:int var(a) 7:int)
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

```hir
;; hir -O2 fn=f
fn f (x:int) -> int {nothrow unique}
  (block
    (def $cse0 (+:int var(x) 1:int))
    (def $cse1 (+:int var($cse0) var($cse0)))
    (def $cse2 (+:int var($cse1) var($cse0)))
    (def $cse3 (+:int var($cse2) var($cse0)))
    (def $cse4 (+:int var($cse3) var($cse0)))
    (def $cse5 (+:int var($cse4) var($cse0)))
    (def $cse6 (+:int var($cse5) var($cse0)))
    (def $cse7 (+:int var($cse6) var($cse0)))
    (def $cse8 (+:int var($cse7) var($cse0)))
    (def $cse9 (+:int var($cse8) var($cse0)))
    (def $cse10 (+:int var($cse9) var($cse0)))
    (def a (+:int var($cse10) var($cse0)))
    (def c var($cse9)))
  (+:int (+:int var(a) var(a)) var(c))
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

```hir
;; hir -O2 fn=run
fn run (x:int, y:int) -> int {nothrow unique}
  (def p (record-new Pair 10:int 0:int))
  (call-method var(p).add a=var(x) b=var(y))
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
  (def boxed (box (call-fn inc var(p))))
  (+:int var(g) (block (as var(boxed) int|int|)))
```

```hir
;; hir -O1 fn=run
fn run (p:int) -> int {pure unique}
  (def g (call (type-apply name(idG)) var(p)))
  (def boxed (box (+:int var(p) 1:int)))
  (+:int var(g) (as var(boxed) int|int|))
```

```hir
;; hir -O2 fn=run
fn run (p:int) -> int {pure unique}
  (def g (call (type-apply name(idG)) var(p)))
  (def boxed (box (+:int var(p) 1:int)))
  (+:int var(g) (as var(boxed) int|int|))
```

## InlinePass.performInline : cell receiver spill, arg mutated in later arg, and conditional local

```babble
def combine = fn (a: int, b: int): int -> (a * 10 + b)
def run = fn (n: int, fl: bool): int -> (
    def p = Pair(n, 0) ; def cap = fn (): int -> (p.x) ; var x = n
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
        (def b#2 var(y))
        (+:int (+:int (get-this-field x) 1:int) var(b#2)))) (call-fn cap))
```

```hir
;; hir -O2 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def p (record-new Pair var(n) 0:int))
  (def cap (fn-ref cap))
  (def r1
    (block
      (def b#1
        (block
          (set x (+:int var(n) 1:int))
          var(x)))
      (+:int (*:int var(n) 10:int) var(b#1))))
  (def y (if var(fl) 2:int 3:int))
  (+:int (+:int var(r1) (block
        (def b#2 var(y))
        (+:int (+:int (get-this-field x) 1:int) var(b#2)))) (call-fn cap))
```

## InlinePass.alphaRename : VarRead widening substitution, uninitialized Def, and for-body local def

```babble
def choose = fn (fl: bool, a: int, b: int): int -> (var y: int? = null ; if fl then (y = a) else (y = b) ; y!)
def sum2 = fn (xs: list of int): int -> (var s = 0 ; for e in xs then (def d = e * 2 ; s += d) ; s)
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
  (+:double (+:double (convert[double] var(p)) 0.5:double) (call name(double) (+:int (block
          (if var(fl) (set y (box var(p))) (set y (box 9:int)))
          (nonnull var(y))) (block
          (def xs (core-new list 1:int 2:int))
          (def s 0:int)
          (for e in var(xs)
            (block
              (def d (*:int var(e) 2:int))
              (set s += var(d))))
          var(s)))))
```

```hir
;; hir -O2 fn=run
fn run (p:int, fl:bool) -> double {pure unique}
  (+:double (+:double (convert[double] var(p)) 0.5:double) (call name(double) (+:int (block
          (if var(fl) (set y (box var(p))) (set y (box 9:int)))
          (nonnull var(y))) (block
          (def xs (core-new list 1:int 2:int))
          (def s 0:int)
          (for e in var(xs)
            (block
              (def d (*:int var(e) 2:int))
              (set s += var(d))))
          var(s)))))
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

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {pure unique}
  (def xs#1
    (block
      (def xs#2 (core-new list var(n) (+:int var(n) 1:int)))
      (move var(xs#2))))
  (def nl (move null))
  (+:int (+:int (jcall List.get var(xs#1) 0:int) (jcall List.get var(xs#1) 1:int)) (if (==:bool var(nl) null) 0:int 1:int))
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

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {nothrow unique}
  (+:int (call (fn-ref <anon#0>) b=2:int a=var(x)) (call (fn-ref <anon#1>) a=var(x) b=3:int))
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

```hir
;; hir -O2 fn=run
fn run (x:int, dyn:obj) -> int {pure unique}
  (+:int (call (fn-ref <anon#0>) var(x)) (call var(dyn) var(x)))
```

## InlinePass.isMethodDefinitelyDefinedForInline and scanThisLeak : statements, default method, and sibling leak

```babble
def Tag = interface (def tag = fn (): int -> (7))
def Good = class (p: Pair) implements Tag (
    def y = p.x + 1 ; init ( assert(p.x > 0) ) ; def get = fn (): int -> (this.y)
)
def Leak2 = class (var x: int) (
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

```hir
;; hir -O2 top-level
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
  (def cond (fn-ref cond))
  (+:int (+:int var(n) 1:int) (call-fn cond var(n)))
```

```hir
;; hir -O2 fn=run
fn run (n:int, fl:bool) -> int {nothrow unique}
  (def cond (fn-ref cond))
  (block
    (def $cse0 (+:int var(n) 1:int))
    (+:int var($cse0) (call-fn cond var(n))))
```
