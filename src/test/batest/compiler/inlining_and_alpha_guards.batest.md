# Function Inlining, Alpha-Renaming, and Callee Scan Guards

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

```hir
;; hir -O0 class=Calc
class Calc (n:int)
  (def bodyVal (call-fn addMul (get-this-field n) 1:int))
  static:
    (def TAG (call-fn addMul 6:int 2:int))
```

```hir
;; hir -O1 class=Calc
class Calc (n:int)
  (def bodyVal
    (block
      (def a (get-this-field n))
      (def s (+:int var(a) 1:int))
      (def d (-:int var(a) 1:int))
      (*:int var(s) var(d))))
  static:
    (def TAG 32:int)
```

```hir
;; hir -O2 class=Calc
class Calc (n:int)
  (def bodyVal
    (block
      (def a (get-this-field n))
      (def s (+:int var(a) 1:int))
      (def d (-:int var(a) 1:int))
      (*:int var(s) var(d))))
  static:
    (def TAG 32:int)
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

```hir
;; hir -O0 fn=caller
fn caller (v:obj) -> int {pure unique}
  (call-fn toInt var(v))
```

```hir
;; hir -O1 fn=caller
fn caller (v:obj) -> int {pure unique}
  (as var(v) int|int|)
```

## InlinePass alpha-renaming across if/else inner defs, for loops, and small while loops

```babble
def inlBlocks = fn (n: int, xs: list of int): int -> (
    var res = 0
    if n > 0 then (def inner = n * 2 ; res += inner) else (res = 1)
    for e in xs then (res += e)
    res
)
def smallWhile = fn (x: int): int -> (var i = x ; while i < 3 then (i += 1) ; i)
assert(inlBlocks(3, list(1, 2)) == 9 and inlBlocks(0, list(4)) == 5 and smallWhile(2) == 3)
```

```hir
;; hir -O0 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def inlBlocks (fn-ref inlBlocks))
  (def smallWhile (fn-ref smallWhile))
  (call name(assert) (and (and (==:bool (call-fn inlBlocks 3:int (core-new list 1:int 2:int)) 9:int) (==:bool (call-fn inlBlocks 0:int (core-new list 4:int)) 5:int)) (==:bool (call-fn smallWhile 2:int) 3:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def inlBlocks (fn-ref inlBlocks))
  (def smallWhile (fn-ref smallWhile))
  (call name(assert) (and (and (==:bool (block
            (def xs#1 (core-new list 1:int 2:int))
            (def res#1 0:int)
            (set res#1 += 6:int)
            (for e#1 in var(xs#1)
              (block
                (set res#1 += var(e#1))))
            var(res#1)) 9:int) (==:bool (block
            (def xs#2 (core-new list 4:int))
            (def res#2 0:int)
            (set res#2 1:int)
            (for e#2 in var(xs#2)
              (block
                (set res#2 += var(e#2))))
            var(res#2)) 5:int)) (==:bool (block
          (def i 2:int)
          (while (<:bool var(i) 3:int)
            (set i += 1:int))
          var(i)) 3:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def inlBlocks (fn-ref inlBlocks))
  (def smallWhile (fn-ref smallWhile))
  (call name(assert) (and (and (==:bool (block
            (def xs#1 (core-new list 1:int 2:int))
            (def res#1 0:int)
            (set res#1 6:int)
            (for e#1 in var(xs#1)
              (block
                (set res#1 += var(e#1))))
            var(res#1)) 9:int) (==:bool (block
            (def xs#2 (core-new list 4:int))
            (def res#2 0:int)
            (set res#2 1:int)
            (for e#2 in var(xs#2)
              (block
                (set res#2 += var(e#2))))
            var(res#2)) 5:int)) (==:bool (block
          (def i 2:int)
          (while (<:bool var(i) 3:int)
            (set i += 1:int))
          var(i)) 3:int)))
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

```hir
;; hir -O0,-O1 fn=callShadow
fn callShadow (n:int) -> int {io}
  (call-fn discard var(n))
  (+:int var(n) 1:int)
```

```hir
;; hir -O0 fn=passRec
fn passRec (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```hir
;; hir -O1 fn=passRec
fn passRec (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```hir
;; hir -O2 fn=passRec
fn passRec (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

## InlinePass.scanCalleeBody guards : local interface and block cell capture

```babble
def smallIface = fn (x: any): int -> (def LI = interface () ; if x is LI then (0) else (1))
def smallBlkCell = fn (x: int): int -> ((def c = x + 1 ; def f = fn (): int -> (c) ; f()))
assert(smallIface(1) == 1 and smallBlkCell(4) == 5)
```

```hir
;; hir -O0 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallIface (fn-ref smallIface))
  (def smallBlkCell (fn-ref smallBlkCell))
  (call name(assert) (and (==:bool (call-fn smallIface (box 1:int)) 1:int) (==:bool (call-fn smallBlkCell 4:int) 5:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallIface (fn-ref smallIface))
  (def smallBlkCell (fn-ref smallBlkCell))
  (call name(assert) (and (==:bool (call-fn smallBlkCell 4:int) 5:int) (==:bool (call-fn smallIface (box 1:int)) 1:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallIface (fn-ref smallIface))
  (def smallBlkCell (fn-ref smallBlkCell))
  (call name(assert) (and (==:bool (call-fn smallBlkCell 4:int) 5:int) (==:bool (call-fn smallIface (box 1:int)) 1:int)))
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

```hir
;; hir -O0,-O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallForCell (fn-ref smallForCell))
  (call name(assert) (==:bool (call-fn smallForCell (core-new list 9:int 10:int)) 9:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def smallForCell (fn-ref smallForCell))
  (call name(assert) (==:bool (call-fn smallForCell (core-new list 9:int 10:int)) 9:int))
```

## InlinePass : reassigned parameter in callee (freshParam allocation) and self-recursion by name

```babble
def incMut = fn (var mut x: int): int -> (x = x + 1 ; x * 2)
def selfByName = fn (n: int): int -> (if n <= 0 then (0) else (selfByName(n - 1) + 1))
def run = fn io (v: int): int -> (incMut(v) + incMut(4) + selfByName(v))
assert(run(3) == 21)
```

```hir
;; hir -O0 fn=run
fn run (v:int) -> int {io}
  (+:int (+:int (call-fn incMut var(v)) (call-fn incMut 4:int)) (call-fn selfByName var(v)))
```

```hir
;; hir -O1 fn=run
fn run (v:int) -> int {io}
  (+:int (+:int (block
        (def x#1 var(v))
        (set x#1 (+:int var(x#1) 1:int))
        (*:int var(x#1) 2:int)) (block
        (def x#2 4:int)
        (set x#2 (+:int var(x#2) 1:int))
        (*:int var(x#2) 2:int))) (call-fn selfByName var(v)))
```

```hir
;; hir -O2 fn=run
fn run (v:int) -> int {io}
  (+:int (+:int (block
        (def x var(v))
        (set x (+:int var(x) 1:int))
        (*:int var(x) 2:int)) 10:int) (call-fn selfByName var(v)))
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

```hir
;; hir -O0 fn=run
fn run (b:byte, i:int, f:float) -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (call-fn wS var(b)) (call name(short) 2:int)) (==:bool (call-fn wS (call name(byte) 3:int)) (call name(short) 3:int))) (==:bool (call-fn wL var(i)) 5:long)) (==:bool (call-fn wL 5:int) 6:long)) (==:bool (call-fn wF var(i)) 5.5:float)) (==:bool (call-fn wF 2:int) 3.5:float)) (==:bool (call-fn wD var(f)) 2.0:double)) (==:bool (call-fn wD 2.5:float) 3.0:double))
```

```hir
;; hir -O1 fn=run
fn run (b:byte, i:int, f:float) -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (widen[short] var(b)) (call name(short) 2:int)) (==:bool (block
                    (def x (call name(byte) 3:int))
                    (widen[short] var(x))) (call name(short) 3:int))) true:bool) true:bool) true:bool) (==:bool (+:long (convert[long] var(i)) 1:long) 5:long)) (==:bool (+:float (convert[float] var(i)) 1.5:float) 5.5:float)) (==:bool (+:double (convert[double] var(f)) 0.5:double) 2.0:double))
```

```hir
;; hir -O2 fn=run
fn run (b:byte, i:int, f:float) -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (widen[short] var(b)) (call name(short) 2:int)) (==:bool (block
                    (def x (call name(byte) 3:int))
                    (widen[short] var(x))) (call name(short) 3:int))) true:bool) true:bool) true:bool) (==:bool (+:long (convert[long] var(i)) 1:long) 5:long)) (==:bool (+:float (convert[float] var(i)) 1.5:float) 5.5:float)) (==:bool (+:double (convert[double] var(f)) 0.5:double) 2.0:double))
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

```hir
;; hir -O0 fn=run
fn run (p:int) -> double {nothrow monotone unique}
  (def d (widen[double] (call-fn idI var(p))))
  (+:double var(d) (call name(double) (call-fn idR var(p))))
```

```hir
;; hir -O1 fn=run
fn run (p:int) -> double {nothrow monotone unique}
  (def d (widen[double] var(p)))
  (+:double var(d) (call name(double) var(p)))
```

```hir
;; hir -O2 fn=run
fn run (p:int) -> double {nothrow monotone unique}
  (def d (widen[double] var(p)))
  (+:double var(d) (call name(double) var(p)))
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

```hir
;; hir -O0 fn=caller
fn caller (x:int) -> int {nothrow unique}
  (def dead (call-fn addMul var(x) 1:int))
  (call name(discard) var(dead))
  (return (call-fn addMul var(x) 2:int))
  (call-fn addMul var(x) 99:int)
```

```hir
;; hir -O1 fn=caller
fn caller (x:int) -> int {nothrow unique}
  (return (block
      (def s (+:int var(x) 2:int))
      (def d (-:int var(x) 2:int))
      (*:int var(s) var(d))))
```

```hir
;; hir -O2 fn=caller
fn caller (x:int) -> int {nothrow unique}
  (return (block
      (def s (+:int var(x) 2:int))
      (def d (-:int var(x) 2:int))
      (*:int var(s) var(d))))
```
