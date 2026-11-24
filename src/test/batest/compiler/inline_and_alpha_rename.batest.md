# Intra-module inlining, alpha-renaming, devirtualization guards, and constructor leak scans

References : `BIP014-I2`, `BIP027-I4`, `org.babblelang.opt.hir.InlinePass`.

```babble preamble
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

## InlinePass.checkParamsAndBody : Java SAM parameter disqualifies inlining

```babble
def applyOp = fn io (x: int, shared op: java.util.function.IntUnaryOperator): int -> (op.applyAsInt(x))
def run = fn io (n: int): int -> (applyOp(n, fn (v: int): int -> (v * 2)))
assert(run(6) == 12)
```

```hir -O1 fn=run check
contains (call-fn applyOp
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

```hir -O1 fn=run check
!contains .setGet
!contains .mix
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

```hir -O0 fn=f check
count 34 (call-fn inc
```

```hir -O1 fn=f check
!contains (call-fn inc
```

## InlinePass.inlineExpr : identity-order named args on record method skip inlining

```babble
def run = fn (x: int, y: int): int -> (
    def p = Pair(10, 0)
    p.add(a: x, b: y)
)
assert(run(2, 3) == 15)
```

```hir -O1 fn=run check
contains .add
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

## InlinePass.alphaRename : VarRead widening substitution, uninitialized Def, and for-body local def

```babble
def choose = fn (fl: bool, a: int, b: int): int -> (def y: int? ; if fl then (y = a) else (y = b) ; y!)
def sum2 = fn (xs: list of int): int -> (def s = 0 ; for e in xs then (def d = e * 2 ; s += d) ; s)
def run = fn (p: int, fl: bool): double -> (
    widen(p) + double(choose(fl, p, 9) + sum2(list(1, 2)))
)
assert(run(3, true) == 12.5 and run(3, false) == 18.5)
```

```hir -O1 fn=run check
!contains (call-fn widen
!contains (call-fn choose
!contains (call-fn sum2
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

## InlinePass.alphaRename : borrow-lambda devirtualization guards for arity mismatch and non-literal callee

```babble
def apOpt = fn (x: int, f: fn (int): int): int -> (f(x))
def run = fn (x: int, dyn: fn (int): int): int -> (
    apOpt(x, fn (a: int, b: int = 1): int -> (a + b)) + apOpt(x, dyn)
)
assert(run(4, fn (z: int): int -> (z * 2)) == 13)
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
