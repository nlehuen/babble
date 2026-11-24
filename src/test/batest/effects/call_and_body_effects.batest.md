# Call effects, body walker control forms, EffectSet lattice, and Purity

References : `BIP007` (effects & purity), `BIP027` (`CallEffectAnalyzer`, `EffectBodyWalker`, `EffectSet`, `Purity`).

```babble module=eff_exports.ba
export def x = 1
export def mutCounter = 0
mutCounter = 1
export def IoCons = class () implements java.util.function.Consumer of any (
    def accept = fn io (v: any) -> ( println(v) )
)
```

```babble preamble
def Box = class (def v: int) (
    def inc = fn mut (): int -> ( this.v += 1 ; this.v )
)
def ListPair = class (def xs: list of int, def ys: list of int) (
    def copyInto = fn mut () -> ( for x in this.xs then ( this.ys.add(x) ) )
    def addTo = fn (mut b: Box) -> ( for x in this.xs then ( b.v += x ) )
)
def gBox = Box(10)
def gList1 = list(1, 2)
def gList2 = list(3)
def runCb = fn calling f (f: fn (): int, b: Box): int -> ( f() + b.v )
def apply1 = fn calling f (f: fn (int): int, x: int): int -> ( f(x) )
```

## For-loop overlap matrix covers local fresh, distinct params, this, and external collections

```babble
def sumAll = fn (xs: list of int, mut b: Box): int -> (
    def tmp = Box(0) ; for x in xs then ( tmp.v += x )
    def loc = list(1, 2) ; for x in loc then ( b.v += x )
    for x in xs then ( b.v += x )
    tmp.v + b.v
)
def b = Box(0) ; def lp = ListPair(list(1, 2), list(3))
lp.copyInto() ; lp.addTo(b)
assert(sumAll(list(4, 5), b) == 24 and lp.ys.size() == 3)
```

## For-loop overlap matrix covers external collections and non-hermetic body

```babble
def ioLoops = fn io (xs: list of int, mut b: Box): int -> (
    for x in gList1 then ( gList2.add(x) )
    for x in gList1 then ( b.v += x )
    for x in xs then ( println(x) )
    b.v
)
def b = Box(0)
assert(ioLoops(list(9), b) == 3 and gList2.size() == 3)
```

```stdout
9
```

## For-loop over Java Iterable with mut iterator axiom requires mut parameter

```babble
axiom java.util.LinkedList.iterator: fn mut (): java.util.Iterator of any
def bad = fn (xs: java.util.LinkedList of int): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
```

```error
Cannot mutate parameter xs (iterator) : mark parameter as mut xs
```

## For-loop over Java Iterable with view volatile iterator axiom

```babble
axiom java.util.LinkedList.iterator: fn view volatile (): java.util.Iterator of any
def f = fn volatile (xs: java.util.LinkedList of int): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
def ll = java.util.LinkedList of int () ; ll.add(3) ; ll.add(4)
assert(f(ll) == 7)
```

## For-loop over pure Babble Iterable with mut iterator requires mut parameter

```babble
def BIter = class (def n: int) (
    def hasNext = fn (): bool -> ( this.n > 0 )
    def next = fn mut (): int -> ( this.n -= 1 ; this.n )
)
def BSeq = class (def n: int) (
    def iterator = fn mut (): BIter -> ( BIter(this.n) )
)
def bad = fn (it: BSeq): int -> ( def s = 0 ; for x in it then ( s += x ) ; s )
```

```error
Cannot mutate parameter it (iterator) : mark parameter as mut it
```

## For-loop over pure Babble Iterator with mut hasNext requires mut parameter

```babble
def BIter = class (def n: int) (
    def hasNext = fn mut (): bool -> ( this.n > 0 )
    def next = fn (): int -> ( this.n )
)
def bad = fn (it: BIter): int -> ( def s = 0 ; for x in it then ( s += x ) ; s )
```

```error
Cannot mutate parameter it (hasNext) : mark parameter as mut it
```

## For-loop multi-step alias shift triggers widening and detects parameter mutation

```babble
def bad = fn (xs: list of int, p: Box) -> (
    def a0 = p ; def a1: Box? = null ; def a2: Box? = null ; def a3: Box? = null
    for x in xs then ( discard(x) ; a3 = a2 ; a2 = a1 ; a1 = a0 )
    if a3 != null then ( a3.v = 1 )
)
```

```error
Cannot mutate parameter p (field assignment .v) : mark parameter as mut p
```

## Forwarded mut callback on frozen receiver is rejected

```babble
def bad = fn (fb: frozen Box, b: Box): int -> ( runCb(fb.inc, b) )
```

```error
Cannot call mut method on frozen value
```

## Forwarded mut callback aliasing another argument is rejected

```babble
def bad = fn (mut b: Box): int -> ( runCb(b.inc, b) )
```

```error
Exclusivity error : mut target b aliases argument b
```

## Forwarded mut callback held in local variable is rejected in pure function

```babble
def bad = fn (mut b: Box, other: Box): int -> ( def m = b.inc ; runCb(m, other) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Forwarded mut callback held in local variable succeeds in io function

```babble
def ok = fn io (mut b: Box, other: Box): int -> ( def m = b.inc ; runCb(m, other) )
assert(ok(Box(2), Box(5)) == 8)
```

## Nested captured calling clauses exercise direct and outer forwardsParameter

```babble
def direct = fn calling f (f: fn (int): int, x: int): int -> (
    def h = fn calling f (a: int): int -> ( f(a) ) ; h(x)
)
def viaOuter = fn (f: fn (int): int) -> (
    fn calling f (x: int): int -> ( def h = fn calling f (a: int): int -> ( f(a) ) ; h(x) )
)
def dbl = fn (n: int): int -> ( n * 2 )
assert(direct(dbl, 3) == 6 and viaOuter(dbl)(4) == 8)
```

## Nested non-calling helper inside calling function exercises isForwardedParamExpr

```babble
def nested = fn calling f (f: fn (int): int, x: int): int -> (
    def inner = fn (y: int): int -> ( def h = fn calling f (a: int): int -> ( f(a) ) ; apply1(f, h(y)) )
    inner(x)
)
assert(nested(fn (n: int): int -> ( n * 2 ), 5) == 20)
```

## Own calling parameter escaping via outer assignment is rejected

```babble
def sink: (fn (int): int)? = null
def bad = fn io calling f (f: fn (int): int) -> ( sink = f )
```

```error regex
.*calling.*escapes.*|Borrowed parameter f escapes.*
```

## Captured pseudo-forwarded callback escaping through returned non-calling closure is rejected

```babble
def outer = fn (f: fn (int): int) -> (
    fn calling f (a: int): fn (): int -> ( fn (): int -> ( f(a) ) )
)
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Try inside captured calling closure suppresses dead-catch warning

```babble
def wrap = fn (f: fn (int): int) -> (
    fn calling f (a: int): int -> ( try ( f(a) ) catch then ( 0 ) )
)
assert(wrap(fn (x: int): int -> ( x + 1 ))(4) == 5)
```

## Array protocol methods : equals, hashCode, toString, named set, parenthesized and reassigned size

```babble
def f = fn (): bool -> (
    def a = (array of int).of(1, 2)
    def b = (array of int).of(1, 2)
    a.set(0, value: 9)
    def s1 = (a).size()
    a = (array of int).of(3, 4, 5)
    def s2 = a.size()
    def t = "" + a + "!"
    not a.equals(b) and a.hashCode() != 0 and b.toString() == "[1, 2]" and s1 == 2 and s2 == 3 and t == "[3, 4, 5]!"
)
assert(f())
```

## Array toString on external array in pure function violates determinism

```babble
def gArr = (array of int).of(1, 2)
def bad = fn (): string -> ( gArr.toString() )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Array set on frozen array is rejected

```babble
def bad = fn io (a: frozen array of int) -> ( a.set(0, 1) )
```

```error
Cannot call mut method set on frozen value
```

## Core collections : sortedset and sortedmap with Babble and Java comparators

```babble
def f = fn (): int -> (
    def cmp = fn (a: int, b: int): int -> ( a - b )
    def s1 = (sortedset of int)(comparator: cmp, from: list(3, 1))
    def m1 = (sortedmap of (string, int))(entry("a", 1))
    s1.size() + m1.size()
)
def fJava = fn io (): int -> ( (sortedset of int)(comparator: java.util.Comparator.naturalOrder(), from: list(2, 1)).size() )
assert(f() == 3 and fJava() == 2)
```

## Core collections : sortedmap with comparator and typed java.util.List from

```babble
def m2 = (sortedmap of (string, int))(comparator: fn (a: string, b: string): int -> ( a.compareTo(b) ), from: map(entry("b", 2)))
def rawFrom = fn (r: java.util.List of any): int -> ( (array of any)(from: r).size() + (list of any)(from: r).size() )
assert(m2.size() == 1 and rawFrom(list(1, 2)) == 4)
```

## Char numeric widenings and shadowed importe function

```babble
def wide = fn nothrow (c: char): double -> ( double(long(int(c))) + double(float(int(c))) + double(int(c)) )
def importe = fn (s: string): int -> ( s.length() )
assert(wide('A') == 195.0 and importe("hi") == 2)
```

## Sortedset with volatile comparator requires volatile caller

```babble
axiom java.util.Comparator.compare: fn volatile (o1: any, o2: any): int
def makeVol = fn volatile (): sortedset of int -> (
    (sortedset of int)(comparator: fn volatile (a: int, b: int): int -> ( (a + gBox.v) - (b + gBox.v) ), from: list(2, 1))
)
assert(makeVol().size() == 2)
```

## Array from non-hermetic Java iterator marks construction non-terminating

```babble
axiom java.util.LinkedList.iterator: fn io (): java.util.Iterator of any
def f = fn io (xs: java.util.LinkedList of int): int -> ( (array of int)(from: xs).size() )
def ll = java.util.LinkedList of int () ; ll.add(1)
assert(f(ll) == 1)
```

## Java deterministic return origin joins multiple mutable arguments and varargs Class array

```babble
axiom java.util.Objects.requireNonNullElse: fn of T pure (obj: T, defaultObj: T): T
def pick = fn (a: Box, b: Box): Box -> ( java.util.Objects.requireNonNullElse(a, b) )
def fmt = fn (): string -> ( java.lang.String.format("%s", "ok") )
assert(pick(Box(3), Box(4)).v == 3 and fmt() == "ok")
```

## Java SAM callback propagation rejects imported io Consumer in pure function

```babble
def m = import("eff_exports.ba")
def bad = fn (xs: list of int) -> ( xs.forEach(m.IoCons()) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Java SAM callback propagation rejects io function parameter in pure function

```babble
def bad = fn (xs: list of int, cb: fn io (any): null) -> ( xs.forEach(cb) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Java SAM callback propagation rejects Java Consumer parameter in pure function

```babble
def bad = fn (xs: list of int, c: java.util.function.Consumer of any) -> ( xs.forEach(c) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Java SAM UnaryOperator argument to replaceAll succeeds in io function

```babble
def f = fn io (mut xs: list of int): int -> (
    def op: java.util.function.UnaryOperator of int = fn (x: int): int -> ( x + 1 )
    xs.replaceAll(op)
    xs.get(0)
)
assert(f(list(4)) == 5)
```

## Prior TypeChecks diagnostic suppresses secondary errors before, on same line, and after

```babble
def m = import("eff_exports.ba")
def f = fn (): int -> (
    1 + 2
    m.x = 1 ; 1 + 2
    1 + 2 ; m.x = 1
    1 + 2
    0
)
```

```error
Cannot assign to an export : x
```

## Prior TypeChecks diagnostic inside single-line and multi-line try suppresses dead-catch warning

```babble
def m = import("eff_exports.ba")
def f = fn (): int -> (
    def a = try ( m.x = 1 ; 1 ) catch then ( 0 )
    def b = try (
        m.x = 1
        2
    ) catch then ( 0 )
    a + b
)
```

```error
Cannot assign to an export : x
```

## Reading mutable global box field in pure function violates determinism

```babble
def bad = fn (): int -> ( gBox.v )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Dynamic cast on any violates nothrow

```babble
def bad = fn nothrow (x: any): int -> ( x as int )
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Three-level nested frame-local closures propagate read and write owners

```babble
def outer = fn (): int -> (
    def acc = 0
    def mid = fn (): int -> (
        def inner = fn (): int -> ( acc = acc + 1 ; acc )
        inner() + inner()
    )
    mid()
)
assert(outer() == 3)
```

## Recursive mut method exercises self-receiver Origin.isEmpty

```babble
def Dec = class (def n: int) (
    def dec = fn mut (k: int): int -> ( if k <= 0 then ( this.n ) else ( this.n -= 1 ; recurse(k - 1) ) )
)
assert(Dec(5).dec(3) == 2)
```

## Unannotated lambda infers mutParams alongside explicit mut and shared methods

```babble
def store: any? = null
def C = class (def v: int) (
    def bump = fn mut () -> ( this.v += 1 )
    def bumpInner = fn mut () -> ( def h = fn mut () -> ( this.v += 1 ) ; h() )
    def leak = fn io shared () -> ( store = this )
)
def incP = fn (b) -> ( b.v += 1 ; b.v )
def c = C(1) ; c.bump() ; c.bumpInner() ; c.leak()
assert(c.v == 3 and incP(Box(4)) == 5)
```

## Body walker control forms : discarded block, multi-break join, abstract member, and external HOF arg

```babble
def HasX = interface ( def x: int )
def findBox = fn (flag: bool): Box? -> (
    ( def a = 1 ; discard(a) )
    while true then ( if flag then ( break Box(1) ) else ( break Box(2) ) )
)
def callExt = fn volatile (f: fn (Box): int): int -> ( f(gBox) )
assert((findBox(true)!).v == 1 and callExt(fn (b: Box): int -> ( b.v )) == 10)
```

## EffectSet.satisfies rejects mut bound method assigned to non-mut function type

```babble
def b = Box(1)
def bad: fn (): int = b.inc
```

```error
Type error : expected () : int, got mut () : int
```

## EffectSet.intersect joins mut, volatile, and io function branches

```babble
def f1 = fn io (flag: bool, mut b: Box): int -> (
    def m1 = if flag then ( b.inc ) else ( fn (): int -> ( 1 ) )
    def m2 = if flag then ( fn volatile (): int -> ( gBox.v ) ) else ( fn (): int -> ( 2 ) )
    def m3 = if flag then ( fn volatile (): int -> ( gBox.v ) ) else ( fn volatile (): int -> ( gBox.v ) )
    m1() + m2() + m3()
)
assert(f1(false, Box(1)) == 13)
```

## EffectSet.intersect joins io-volatile and io-io function branches

```babble
def f2 = fn io (flag: bool): int -> (
    def m4 = if flag then ( fn io (): int -> ( gBox.v ) ) else ( fn volatile (): int -> ( gBox.v ) )
    def m5 = if flag then ( fn io (): int -> ( gBox.v ) ) else ( fn io (): int -> ( gBox.v ) )
    m4() + m5()
)
assert(f2(true) == 20)
```

## EffectSet.intersect joins unique and non-terminating function branches

```babble
def f3 = fn (c: bool, b: Box): int -> (
  def u1 = if c then ( fn (x: Box): Box -> ( Box(1) ) ) else ( fn (x: Box): Box -> ( x ) )
  def u2 = if c then ( fn (): Box -> ( Box(1) ) ) else ( fn (): Box -> ( Box(2) ) )
  def nt = if c then ( fn (n: int): int -> ( while n > 0 then ( n -= 1 ) ; n ) ) else ( fn (n: int): int -> ( n ) )
  u1(b).v + u2().v + nt(1)
)
assert(f3(true, Box(9)) == 2)
```

## EffectSet.intersect joins shared and non-shared method branches

```babble
def S = class (def v: int) (
    def a = fn shared (): S -> ( this )
    def b = fn (): S -> ( discard(Box(1)) ; this )
)
def pick = fn (flag: bool, shared s: S): S -> (
    def m = if flag then ( s.a ) else ( s.b )
    m()
)
assert(pick(true, S(3)).v == 3)
```

## EffectSet.combineForwarded handles volatile and io HOFs

```babble
def fVol = fn volatile calling f (f: fn (): int): int -> ( gBox.v + f() )
def fIo = fn io calling f (f: fn (): int): int -> ( gBox.v + f() )
def runAll = fn io (): int -> ( fVol(fn (): int -> ( 1 )) + fIo(fn (): int -> ( 2 )) )
assert(runAll() == 23)
```

## EffectSet.combineForwarded handles volatile and non-terminating callbacks

```babble
def fPure = fn calling f (f: fn (): int): int -> ( f() )
def runAll = fn volatile (): int -> (
    def c = fPure(fn volatile (): int -> ( gBox.v ))
    def d = fPure(fn (): int -> ( def i = 1 ; while i > 0 then ( i -= 1 ) ; i ))
    c + d
)
assert(runAll() == 10)
```

## EffectSet.toString formats pure throwing, volatile, io, and nothrow unique functions in HIR

```babble
def pureThrow = fn (s: string): int -> ( int(s) )
def volFn = fn volatile (): int -> ( gBox.v )
def ioFn = fn io (): int -> ( println("x") ; 1 )
def ntUniq = fn (): Box -> ( Box(1) )
assert(pureThrow("3") == 3 and volFn() == 10 and ntUniq().v == 1)
```

```hir -O1 fn=pureThrow check
contains fn pureThrow
```

```hir -O1 fn=volFn check
contains fn volFn
```

```hir -O1 fn=ioFn check
contains fn ioFn
```

```hir -O1 fn=ntUniq check
contains fn ntUniq
```

## EffectSet.toString formats mut-param throwing unique, mut-param throwing int, and shared method in HIR

```babble
def mutThrowUniq = fn (mut b: Box, s: string): Box -> ( b.v = int(s) ; Box(b.v) )
def mutThrowInt = fn (mut b: Box, s: string): int -> ( b.v = int(s) ; b.v )
def SharedCls = class (def v: int) (
    def leak = fn io shared (): SharedCls -> ( this )
)
def b = Box(0)
assert(mutThrowUniq(b, "4").v == 4 and mutThrowInt(b, "5") == 5 and SharedCls(2).leak().v == 2)
```

```hir -O1 fn=mutThrowUniq check
contains fn mutThrowUniq
```

```hir -O1 fn=mutThrowInt check
contains fn mutThrowInt
```

```hir -O1 fn=SharedCls.leak check
contains fn SharedCls.leak
```

## Purity CONTEXTUALLY_SIDE_EFFECT_FREE via volatile mut-parameter helper

```babble
def volMut = fn volatile (mut b: Box): int -> ( b.v += gBox.v ; b.v )
def run = fn volatile (mut b: Box): int -> ( volMut(b) )
def x = Box(2)
assert(run(x) == 12)
```
