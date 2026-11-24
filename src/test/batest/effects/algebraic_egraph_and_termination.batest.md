# E-graph algebraic verification, partial-derivative sign domain, polynomial SCP, and finite-collection termi...

References : BIP027 (BIP027-I6, BIP027-D1, BIP027-D8), BIP007 (BIP007-D8)

```babble
;; babble preamble
def Box = class (def v : int) ()
def Pair = record (a : int, b : int) ()
def Node = record (v : int, next : Node?) ()

def fib = fn (n : int) : int -> (
    if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) )
)

def sumList = fn (xs : list of int) : int -> (
    def s = 0
    for x in xs then ( s += x )
    s
)
```

## Case 1

```babble
def sqSum = fn commutative (a : int, b : int) : int -> (
    def s = a + b
    s * s
)
def sqDiff = fn commutative (a : int, b : int) : int -> (
    def d = a - b
    d * d
)
assert(sqSum(2, 3) == 25)
assert(sqDiff(2, 5) == 9)
```

## Case 2

```babble
def myMin = fn commutative associative monotone (a : int, b : int) : int -> (
    if a < b then ( a ) else ( b )
)
def myMax = fn commutative associative monotone (a : int, b : int) : int -> (
    if a > b then ( a ) else ( b )
)
assert(myMin(3, 7) == 3)
assert(myMax(3, 7) == 7)
```

## IEEE-754 double min via `<` is not commutative (NaN / signed zero)

```babble
def badDMin = fn commutative (a : double, b : double) : double -> (
    if a < b then ( a ) else ( b )
)
```

```error
Function badDMin declared commutative violates commutative guarantee
```

## Case 4

```babble
def clamp = fn idempotent monotone (x : int) : int -> (
    def lo = if x < 0 then ( 0 ) else ( x )
    if lo > 100 then ( 100 ) else ( lo )
)
assert(clamp(-5) == 0 and clamp(150) == 100 and clamp(42) == 42)
```

## Case 5

```babble
def absInt = fn idempotent (x : int) : int -> (
    if x < 0 then ( -x ) else ( x )
)
def earlyClamp = fn idempotent (x : int) : int -> (
    if x < 0 then ( return 0 )
    x
)
assert(absInt(-7) == 7 and earlyClamp(-3) == 0)
```

## Case 6

```babble
def absDiff = fn commutative (a : int, b : int) : int -> (
    def d = a - b
    if d < 0 then ( -d ) else ( d )
)
assert(absDiff(3, 9) == 6)
assert(absDiff(9, 3) == 6)
```

## Case 7

```babble
def Accum = class (def v : int) (
    def clampPos = fn mut idempotent () -> (
        this.v = if this.v < 0 then ( 0 ) else ( this.v )
    )
    def addTwice = fn mut commutative (d : int) -> (
        def step = d * 2
        this.v += step
    )
)
def a = Accum(-4)
a.clampPos()
a.addTwice(3)
assert(a.v == 6)
```

## Case 8

```babble
def MaxAccum = class (def v : int) (
    def keepMax = fn mut idempotent commutative (d : int) -> (
        this.v = if d > this.v then ( d ) else ( this.v )
    )
)
def a = MaxAccum(2)
a.keepMax(5)
a.keepMax(3)
assert(a.v == 5)
```

## IEEE-754 double accumulation is not associative, hence not mut commutative

```babble
def DAccum = class (def v : double) (
    def add = fn mut commutative (d : double) -> ( this.v += d )
)
```

```error
Function add declared commutative violates commutative guarantee
```

## Case 10

```babble
def addD = fn monotone (a : double, b : double) : double -> ( a + b * 2.0 )
def shiftSecond = fn monotone (tag : Box, x : double) : double -> ( x / 2.0 + 1.0 )
assert(addD(1.0, 2.0) == 5.0)
assert(shiftSecond(Box(0), 4.0) == 3.0)
```

## Case 11

```babble
def oddConst = fn injective (x : int) : int -> (
    def k = 1 + 2
    x * k + 5
)
def triPair = fn injective (x : int, y : int) : Pair -> ( Pair(x, x + y) )
assert(oddConst(2) == 11)
assert(triPair(2, 3) == Pair(2, 5))
```

## Anti-monotone in second parameter must not satisfy monotone

```babble
def subD = fn monotone (a : double, b : double) : double -> ( a - b )
```

```error
Function subD declared monotone violates monotone guarantee
```

## BIP027-D8 : SCP-certified fib triggers Unused pure expression when discarded

```babble
def f = fn (n : int) : int -> (
    fib(n)
    n
)
```

```error
Unused pure expression
```

## BIP027-D8 : discard(...) is the explicit opt-out for SCP-certified calls

```babble
def f = fn nothrow (n : int) : int -> (
    discard(fib(n))
    n + 1
)
assert(f(5) == 6)
```

## BIP027-D8 : finite-collection for-loop function triggers Unused pure expression

```babble
def f = fn (xs : list of int) : int -> (
    sumList(xs)
    0
)
```

```error
Unused pure expression
```

## BIP027-D8 : side-effect-free finite-collection for loop in statement position

```babble
def f = fn (xs : list of int) : int -> (
    for x in xs then ( x + 1 )
    0
)
```

```error
Unused pure expression
```

## Case 17

```babble
def gcdSub = fn (a : int, b : int) : int -> (
    if a <= 0 or b <= 0 then ( a + b )
    else if a > b then ( gcdSub(a - b, b) )
    else ( gcdSub(a, b - a) )
)
assert(gcdSub(48, 18) == 6)
```

## Case 18

```babble
def gcdMod = fn (a : int, b : int) : int -> (
    if b <= 0 then ( a ) else ( gcdMod(b, java.lang.Math.floorMod(a, b)) )
)
def log2 = fn (n : int) : int -> ( if n <= 1 then ( 0 ) else ( 1 + log2(int(n / 2)) ) )
assert(gcdMod(48, 18) == 6 and log2(16) == 4)
```

## Error : Unused pure expression

```babble
def len = fn (xs : Node?) : int -> ( if xs == null then ( 0 ) else ( 1 + len(xs.next) ) )
def bad = fn (xs : Node?) : int -> (
    len(xs)
    0
)
```

```error
Unused pure expression
```

## Unguarded `n == 0` recursion is NOT certified terminating, so discarding it is not flagged

```babble
def unguarded = fn (n : int) : int -> ( if n == 0 then ( 0 ) else ( unguarded(n - 1) ) )
def ok = fn (n : int) : int -> (
    unguarded(n)
    n
)
assert(ok(3) == 3)
```

## SCP : Math.floorDiv, long guarded descent, flipped constant guards (`0L < n`), and `n + (-1L)`

```babble
def halving = fn (n : int) : int -> (
    if n <= 0 then ( 0 ) else ( 1 + halving(java.lang.Math.floorDiv(n, 2)) )
)
def downL = fn (n : long) : long -> (
    if 0L < n then ( 1L + downL(n + (-1L)) ) else ( 0L )
)
assert(halving(8) == 4 and downL(4L) == 4L)
```

## SCP : boolean `not`, `and`, `!=` tightening, and early-return guard refinement

```babble
def fNot = fn (n : int) : int -> (
    if not (n <= 0) then ( 1 + fNot(n - 1) ) else ( 0 )
)
def fNeq = fn (n : int) : int -> (
    if n >= 0 and n != 0 then ( 1 + fNeq(n - 1) ) else ( 0 )
)
def fEarly = fn (n : int) : int -> (
    if n <= 0 then ( return 0 )
    1 + fEarly(n - 1)
)
assert(fNot(3) == 3 and fNeq(3) == 3 and fEarly(3) == 3)
```

## SCP : early-return in else-branch (`alwaysExitsBlock`), `a < b`, and `a >= b` parameter guards

```babble
def fElseExit = fn (n : int) : int -> (
    if n > 0 then ( 0 ) else ( return 0 )
    1 + fElseExit(n - 1)
)
def subLt = fn (a : int, b : int) : int -> (
    if a >= 1 and a < b then ( subLt(a, b - a) ) else ( a + b )
)
assert(fElseExit(3) == 3 and subLt(3, 10) == 4)
```

## SCP : multi-param Ackermann (lexicographic) and parameter-swap (Kuhn bipartite matching)

```babble
def ack = fn (m : int, n : int) : int -> (
    if m <= 0 then ( n + 1 )
    else if n <= 0 then ( ack(m - 1, 1) )
    else ( ack(m - 1, ack(m, n - 1)) )
)
def swapDown = fn (a : int, b : int) : int -> (
    if a > 0 and b > 0 then ( 1 + swapDown(b, a - 1) ) else ( 0 )
)
assert(ack(2, 2) == 7 and swapDown(3, 2) == 4)
```

## SCP : fan-out calls (Criterion 2 max-measure) and guarded mutual recursion

```babble
def fanOut = fn (a : int, b : int) : int -> (
    if a > 0 and b > 0 then ( fanOut(a - 1, a - 1) + fanOut(b - 1, b - 1) ) else ( 1 )
)
def evenM = fn (n : int) : bool -> ( if n <= 0 then ( true ) else ( oddM(n - 1) ) )
def oddM = fn (n : int) : bool -> ( if n <= 0 then ( false ) else ( evenM(n - 1) ) )
assert(fanOut(2, 2) == 4 and evenM(4) and not oddM(4))
```

## SCP : recursive method `this.down(n - 1)` certified terminating (BIP027-D8)

```babble
def Counter = record (k : int) (
    def down = fn (n : int) : int -> (
        if n <= 0 then ( this.k ) else ( 1 + this.down(n - 1) )
    )
)
def bad = fn (c : Counter, n : int) : int -> (
    c.down(n)
    0
)
```

```error
Unused pure expression
```

## SCP : negative cases (reassigned param, escaping ref) stay non-terminating

```babble
def fReassign = fn (n : int) : int -> ( if n <= 0 then ( 0 ) else ( n = n - 1 ; fReassign(n) ) )
def fEscape = fn (n : int) : int -> ( def g = fEscape ; if n <= 0 then ( 0 ) else ( g(n - 1) ) )
def ok = fn (n : int) : int -> ( fReassign(n) ; fEscape(n) ; n )
assert(ok(2) == 2)
```

## SCP : `forever` and recursive `toString` stay non-terminating

```babble
def fFor = fn (n : int) : int -> ( if n <= 0 then ( 0 ) else ( forever ( fFor(n - 1) ) ) )
def RStr = class (def n : int) (
    def toString = fn () : string -> ( if this.n <= 0 then ( "" ) else ( "" + RStr(this.n - 1) ) )
)
def ok = fn (n : int) : int -> ( fFor(n) ; discard(RStr(0).toString()) ; n )
assert(ok(1) == 1)
```

## RelSignDomain : scalar widenings (char/short/int/float) and bool not

```babble
def c2i = fn monotone injective (c : char) : int -> ( int(c) )
def s2d = fn monotone injective (s : short) : double -> ( double(s) )
def i2l = fn monotone injective (x : int) : long -> ( long(x) )
assert(c2i('a') == 97 and s2d(short(2)) == 2.0 and i2l(3) == 3L)
```

## Case 30

```babble
def f2d = fn monotone injective (x : float) : double -> ( double(x) )
def bNot = fn injective (b : bool) : bool -> ( not b )
assert(f2d(1.5f) == 1.5 and not bNot(true))
```

## RelSignDomain : constant-folded multipliers, negative slopes, long odd mul, non-injective Pair

```babble
def scaleD = fn monotone (x : double) : double -> ( (2.0 + 1.0) * x + (-(-x)) / 2.0 )
def scaleL = fn injective (x : long) : long -> ( (2L + 1L) * x - 4L )
def negDiv = fn monotone (x : double) : double -> ( -(x / (-2.0)) )
assert(scaleD(2.0) == 7.0 and scaleL(3L) == 5L and negDiv(4.0) == 2.0)
```

## RelSignDomain : non-triangular multi-param record ctor is rejected as injective

```babble
def badPair = fn injective (a : int, b : int) : Pair -> ( Pair(a + b, a + b) )
```

```error
Function badPair declared injective violates injective guarantee
```

## Mut algebraic properties on `mut` parameter and with `return`

```babble
def setVal = fn idempotent (mut b : Box, v : int) -> ( return (b.v = v) )
def addVal = fn commutative (mut b : Box, d : int) -> ( b.v += d )
def b = Box(0)
setVal(b, 5)
setVal(b, 5)
addVal(b, 3)
assert(b.v == 8)
```

## BIP027-D8 : `discard(sumList(xs))` and `discard(for ...)` stay valid and execute cleanly

```babble
def f = fn (xs : list of int) : int -> (
    discard(sumList(xs))
    discard(for x in xs then ( x + 1 ))
    xs.size()
)
assert(f(list(1, 2, 3)) == 3)
```

## SCP : SAME edges (`n - 0`, `int(n / 1)`), `a >= b` guard, local `class`, and default param

```babble
def fSame = fn (n : int, m : int = 0) : int -> (
    def L = class (def k : int) ()
    if n <= 0 then ( m + L(1).k )
    else if m >= 1 and n >= m then ( fSame(n - m, m - 0) )
    else ( fSame(int(n / 1) - 1, m) )
)
assert(fSame(4, 2) == 3 and fSame(3) == 1)
```

## SCP : inner closure referencing `recurse` and spread call stay non-terminating

```babble
def fClos = fn (n : int) : int -> (
    def h = fn (k : int) : int -> ( if k <= 0 then ( 0 ) else ( fClos(k - 1) ) )
    if n <= 0 then ( 0 ) else ( h(n - 1) )
)
def fSpread = fn (a : int, xs : int...) : int -> (
    if a <= 0 then ( 0 ) else ( fSpread(a - 1, ...xs) )
)
def ok = fn (n : int) : int -> ( fClos(n) ; fSpread(n, 0) ; n )
assert(ok(2) == 2)
```

## Algebraic : local compound assign, mut-param read+write (`b.v = b.v + d`), float/double literals

```babble
def sumSq = fn commutative (a : int, b : int) : int -> (
    def s = a
    s += b
    s * s
)
def addBox = fn commutative (mut b : Box, d : int) -> ( b.v = b.v + d )
def monoF = fn monotone (x : float) : float -> ( x * 2.0f + 1.0f )
def b = Box(1)
addBox(b, 4)
assert(sumSq(2, 3) == 25 and b.v == 5 and monoF(2.0f) == 5.0f)
```

## Algebraic : ForwardMutCall (`mut commutative` delegating to another mut method)

```babble
def addOne = fn commutative (mut b : Box, d : int) -> ( b.v += d )
def fwdAdd = fn commutative (mut b : Box, d : int) -> ( addOne(b, d) )
def b = Box(2)
fwdAdd(b, 5)
assert(b.v == 7)
```

## Algebraic : `else if` monotone and boolean commutative/associative laws

```babble
def pickShift = fn monotone (x : double) : double -> (
    if 0 == 1 then ( x + 1.0 ) else if 1 == 1 then ( x + 2.0 ) else ( x + 3.0 )
)
def both = fn commutative associative (a : bool, b : bool) : bool -> ( a and b )
assert(pickShift(5.0) == 7.0 and both(true, true) and not both(true, false))
```

## SCP : flipped constant comparisons (`0 >= n`, `1 <= n`, `0 == n` with `0 <= n`), nested both-return `if`

```babble
def fFlip = fn (n : int) : int -> (
    if 0 >= n then ( return 0 )
    if 1 <= n and 0 != n then ( 1 + fFlip(n - 1) ) else ( 0 )
)
def fBothExit = fn (n : int) : int -> (
    if n <= 0 then ( if n == 0 then ( return 0 ) else ( return 0 ) )
    1 + fBothExit(n - 1)
)
assert(fFlip(3) == 3 and fBothExit(3) == 3)
```

## Algebraic : `byte` lossless widening, `char` / `string` concatenation injective

```babble
def b2d = fn monotone injective (b : byte) : double -> ( double(b) )
def tagStr = fn injective (s : string) : string -> ( "k:" + s )
def tagInt = fn injective (n : int) : string -> ( "n=" + n )
assert(b2d(byte(3)) == 3.0 and tagStr("a") == "k:a" and tagInt(5) == "n=5")
```
