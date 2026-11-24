# Small-function inlining, alpha-renaming, inline-guard scans, and dead-code elimination (DCE)

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble module=dce_mod.ba
export def val = 10
```

```babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```

## Top-level, function, class body/static, and interface default/static inlining + self-recursion

```babble
def topRes = addMul(5, 2)
def ICalc = interface (
    def compute = fn (v: int): int -> (addMul(v, 2) + ICalc.BASE)
) static (def BASE = addMul(4, 1))
def Calc = class (def n: int) implements ICalc (
    def bodyVal = addMul(this.n, 1)
) static (def TAG = addMul(6, 2))
def c = Calc(4)
assert(topRes == 21 and c.bodyVal == 15 and c.compute(3) == 20 and Calc.TAG == 32 and fib(7) == 13)
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

## InlinePass error line-number preservation (inlined cast failure vs interpreter)

```babble
def toInt = fn (v: any?): int -> ( v as int )
def caller = fn (v: any?): int -> ( toInt(v) )
caller(null)
```

```error
Type error : expected int, got null
```

## InlinePass alpha-renaming across if/else inner defs, for loops, and small while loops

```babble
def inlBlocks = fn (n: int, xs: list of int): int -> (
    def res = 0
    if n > 0 then (def inner = n * 2 ; res += inner) else (res = 1)
    for e in xs then (res += e)
    res
)
def smallWhile = fn (x: int): int -> (def i = x ; while i < 3 then (i += 1) ; i)
assert(inlBlocks(3, list(1, 2)) == 9 and inlBlocks(0, list(4)) == 5 and smallWhile(2) == 3)
```

## HirDcePass top-level user discard shadowing + InlinePass parameter widening and RecurseRef scan

```babble
def discard = fn io (x: int): int -> (x * 2)
def callShadow = fn io (n: int): int -> (discard(n) ; n + 1)
def widenInside = fn (x: int): double -> (x + 0.5)
def passRec = fn (n: int): int -> (if n <= 0 then (0) else (def r = recurse ; r(n - 1) + 1))
def p = 3
assert(discard(7) == 14 and callShadow(4) == 5 and widenInside(5) == 5.5 and widenInside(p) == 3.5 and passRec(3) == 3)
```

## InlinePass.scanCalleeBody guards : local interface and block cell capture

```babble
def smallIface = fn (x: any): int -> (def LI = interface () ; if x is LI then (0) else (1))
def smallBlkCell = fn (x: int): int -> ((def c = x + 1 ; def f = fn (): int -> (c) ; f()))
assert(smallIface(1) == 1 and smallBlkCell(4) == 5)
```

## InlinePass.scanCalleeBody guard : for-loop cell capture

```babble
def smallForCell = fn (xs: list of int): int -> (
    def fs = (list of (fn (): int))()
    for e in xs then (fs.add(fn (): int -> (e)))
    fs.get(0)()
)
assert(smallForCell(list(9, 10)) == 9)
```

## InlinePass : reassigned parameter in callee (freshParam allocation) and self-recursion by name

```babble
def incMut = fn (mut x: int): int -> (x = x + 1 ; x * 2)
def selfByName = fn (n: int): int -> (if n <= 0 then (0) else (selfByName(n - 1) + 1))
def run = fn io (v: int): int -> (incMut(v) + incMut(4) + selfByName(v))
assert(run(3) == 21)
```

## InlinePass : isNoOpWidening across ranks 1/3/4/5 and alphaRename widened Const substitution

```babble
def wS = fn (x: byte): short -> (x)
def wL = fn (x: int): long -> (x + 1L)
def wF = fn (x: int): float -> (x + 1.5f)
def wD = fn (x: float): double -> (x + 0.5)
def run = fn (b: byte, i: int, f: float): bool -> (
  wS(b) == short(2) and wS(byte(3)) == short(3) and wL(i) == 5L and wL(5) == 6L and
  wF(i) == 5.5f and wF(2) == 3.5f and wD(f) == 2.0 and wD(2.5f) == 3.0
)
assert(run(byte(2), 4, 1.5f))
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

## InlinePass : widened single-stmt inline call and trailing-return callee

```babble
def idI = fn (x: int): int -> (x)
def idR = fn (x: int): int -> (return x)
def run = fn (p: int): double -> (
  def d: double = idI(p)
  d + double(idR(p))
)
assert(run(3) == 6.0)
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

## Canonical HIR golden : small-function inlining and dead-code elimination (-O0 vs -O1)

```babble
def caller = fn (x: int): int -> (
    def dead = addMul(x, 1)
    discard(dead)
    return addMul(x, 2)
    addMul(x, 99)
)
assert(caller(5) == 21)
```

```hir -O0 fn=caller check
count 3 (call-fn addMul
contains (def dead
```

```hir -O1 fn=caller check
!contains (call-fn addMul
!contains (def dead
!contains 99:int
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

```hir -O0 fn=safeAdd check
contains (try
contains (catch
```

```hir -O1 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (+:int var(x) 1:int)
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

```hir -O1 fn=keepFinally check
contains (try
!contains (catch
contains (finally
```

## HirDcePass : top-level namespace try with dead catch

```babble
def topTry = try (def t = 20 ; t + 22) catch : exception then (0)
assert(topTry == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
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

## HirDcePass : unused uninitialized def and fn/class param defaults with dead code

```babble
def fnDef = fn (a: int = (def d = 1 ; 1 + 2), b: int = (def e = 2 ; 3 + 4)): int -> (
    def unused: int?
    a + b
)
def ClsDef = class (def x: int = (def d = 1 ; 2 + 3)) ()
assert(fnDef() == 10 and ClsDef().x == 5)
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

## HirDcePass : named discard argument stays preserved

```babble
def runNamed = fn (x: int): int -> (discard(value: x + 1) ; x)
assert(runNamed(5) == 5)
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (1-arg)

```babble
def discard = fn (x: int): int -> (x * 2)
discard = fn (x: int): int -> (x * 3)
def run = fn io (x: int): int -> (discard(x))
assert(run(4) == 12)
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (2-arg)

```babble
def discard = fn (a: int, b: int): int -> (a + b)
discard = fn (a: int, b: int): int -> (a + b + 1)
def run = fn io (x: int): int -> (discard(x, 3))
assert(run(4) == 8)
```

## HirDcePass.collectAllVarRefs : local defs alongside ThisRef, SuperCall, and RecurseRef

```babble
def Base = interface (def calc = fn (x: int): int -> (x + 1))
def Sub = class (def k: int) implements Base (
    def calc = fn (x: int): int -> (def tmp = this.k + 1 ; super.calc(x) + tmp)
)
def recVal = fn (n: int): int -> (
    def step = 1
    if n <= 0 then (0) else (def r = recurse ; r(n - step) + step)
)
assert(Sub(4).calc(5) == 11 and recVal(3) == 3)
```

## HirRewriter : ModuleExportGet, SpreadAdapt, and TypeApply child rewrites

```babble
def mod = import("dce_mod.ba")
def idG of T = fn (x: T): T -> (x)
def sumV = fn io (xs: int...): int -> (def s = 0 ; for e in xs then (s += e) ; s)
def run = fn io (arr: array of int): int -> (
    (discard(1) ; mod).val + sumV(...(discard(1) ; arr)) + ((discard(1) ; idG) of int)(3)
)
assert(run((array of int).of(1, 2)) == 16)
```

## HirRewriter : For iterable and SelfRecurseCall argument rewrites

```babble
def idL = fn (xs: list of int): list of int -> (xs)
def recN = fn (n: int): int -> (if n <= 0 then (0) else (1 + recurse(n - (1 + 0))))
def run = fn (xs: list of int): int -> (
    def b = 0 ; for e in idL(xs) then (b += e) ; b + recN(2)
)
assert(run(list(4, 5)) == 11)
```

## HirRewriter : SafeCall and MakeOverloadSet child rewrites

```babble
def run = fn (s: string?, n: int): int -> (
    def l1 = (discard(1) ; s)?.length()
    def l2 = s?.substring(0 + 1)?.length()
    def ov = overload((discard(1) ; fn (x: int): int -> (x + 1)), fn (t: string): int -> (t.length()))
    (if l1 != null then (l1) else (0)) + (if l2 != null then (l2) else (0)) + ov(n)
)
assert(run("abcd", 3) == 11 and run(null, 3) == 4)
```

## HirRewriter : RecordNew classRef and Move operand rewrites

```babble
def Pair = record (a: int, b: int) ()
def run = fn (n: int): int -> (
    def p = (discard(1) ; Pair)(n, 2)
    def xs = list(n)
    def m1 = move(xs)
    p.a + p.b + m1.get(0)
)
assert(run(3) == 8)
```


