# Unique ownership, move, freeze, frozen receivers/arguments, and reachability widening

References : `BIP007` (effects & ownership), `BIP027` (`CallEffectAnalyzer`, `EffectBodyWalker`, `Reachability`).

```babble
;; babble preamble
def Box = class (def v: int) (
    def inc = fn mut (): int -> ( this.v += 1 ; this.v )
    def self = fn (): Box -> ( this )
    def bumpSelf = fn mut (): Box -> ( this.v += 1 ; this )
)
def Holder = class (def child: Box?) (
    def link = fn mut (shared n: Box): int -> ( this.child = n ; n.v )
    def bumpChild = fn mut () -> ( def c = this.child ; if c != null then ( c.v += 1 ) )
)
def Pair = class (def a: Box, def b: Box) ()
def Node = class (def x: Box, def y: Box) ()
def Root = class (def a: Node) ()
def consumeBox = fn (unique b: Box): int -> ( b.v )
```

## Move rejects borrowed parameter

```babble
def f = fn (b: Box): Box -> ( move(b) )
```

```error
Cannot move borrowed parameter b
```

## Move rejects non-unique aliased local

```babble
def f = fn (): Box -> ( def a = Box(1) ; def b = a ; discard(a) ; move(b) )
```

```error
Cannot move b : variable is not unique
```

## Move rejects already-moved local

```babble
def f = fn (): Box -> ( def b = Box(1) ; def c = move(b) ; discard(c) ; move(b) )
```

```error
Use of moved variable b
```

## Freeze rejects already-moved local

```babble
def f = fn (): frozen Box -> ( def b = Box(1) ; def c = move(b) ; discard(c) ; freeze(b) )
```

```error
Use of moved variable b
```

## Freeze on deeply immutable scalar and null succeeds

```babble
def f = fn (): int -> (
    def a = freeze(42)
    def n: frozen Box? = freeze(null)
    if n == null then ( a ) else ( 0 )
)
assert(f() == 42)
```

## Freeze consumes unique parameter so later read is rejected

```babble
def f = fn (unique b: Box): int -> ( def fb = freeze(b) ; fb.v + b.v )
```

```error
Use of moved variable b
```

## Freeze rejects borrowed parameter even after local reassignment

```babble
def f = fn (b: Box): frozen Box -> ( b = Box(1) ; freeze(b) )
```

```error
Cannot freeze borrowed parameter b
```

## Reading moved unique parameter is rejected

```babble
def f = fn (unique b: Box): int -> ( def c = move(b) ; b.v + c.v )
```

```error
Use of moved variable b
```

## Compound assignment on moved local with operator plus is rejected

```babble
def Counter = class (def n: int) (
    def plus = fn (d: int): Counter -> ( Counter(this.n + d) )
)
def f = fn (): Counter -> ( def c = Counter(1) ; def d = move(c) ; c += 1 ; d )
```

```error
Use of moved variable c
```

## Passing unique local directly to Babble unique parameter moves it

```babble
def f = fn (): int -> ( def b = Box(1) ; def n = consumeBox(b) ; n + b.v )
```

```error
Use of moved variable b
```

## Passing frozen value to Babble unique parameter is rejected

```babble
def f = fn (fb: frozen Box): int -> ( consumeBox(fb) )
```

```error
Argument for unique parameter b cannot be frozen
```

## Passing non-unique parameter to Babble unique parameter is rejected

```babble
def f = fn (b: Box): int -> ( consumeBox(b) )
```

```error
Argument for unique parameter b must be unique
```

## Passing rebound borrowed parameter to Babble unique parameter is rejected

```babble
def f = fn (b: Box): int -> ( b = Box(1) ; consumeBox(b) )
```

```error
Cannot move borrowed parameter b
```

## Generic unique parameter accepts deeply immutable scalar

```babble
def take of T = fn (unique x: T): T -> ( x )
assert(take(7) == 7)
assert(consumeBox(Box(9)) == 9)
```

## Passing already-moved local to Babble unique parameter is rejected

```babble
def f = fn (): int -> ( def b = Box(1) ; def c = move(b) ; discard(c) ; consumeBox(b) )
```

```error
Use of moved variable b
```

## Calling mut Babble method on frozen receiver is rejected

```babble
def f = fn io (fb: frozen Box): int -> ( fb.inc() )
```

```error
Cannot call mut method on frozen value
```

## Calling shared Babble method on frozen receiver is rejected

```babble
def Leaker = class (def v: int) (
    def leak = fn io shared (): Leaker -> ( this )
)
def f = fn io (fl: frozen Leaker): Leaker -> ( fl.leak() )
```

```error
Cannot call shared method on frozen value
```

## Calling shared Babble method on unique local demotes uniqueness

```babble
def Leaker = class (def v: int) (
    def leak = fn io shared (): Leaker -> ( this )
)
def f = fn io (): frozen Leaker -> ( def x = Leaker(1) ; discard(x.leak()) ; freeze(x) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Calling mut method on union containing frozen is rejected

```babble
def f = fn io (x: (frozen Box) | Box): int -> ( x.inc() )
```

```error
Cannot call mut method on frozen value
```

## Calling mut Java method on frozen list receiver is rejected

```babble
def f = fn io (xs: frozen list of int) -> ( xs.add(1) )
```

```error
Cannot call mut method add on frozen value
```

## Calling io Java method on frozen receiver is rejected

```babble
axiom java.lang.StringBuilder.capacity: fn io (): int
def f = fn io (sb: frozen java.lang.StringBuilder): int -> ( sb.capacity() )
```

```error
# error regex
Cannot call .*method capacity on frozen value
```

## Calling pure Java method on frozen receiver is allowed

```babble
axiom java.lang.StringBuilder.new: fn pure fresh (s: string!)
axiom java.lang.StringBuilder.capacity: fn shared pure (): int
def f = fn (sb: frozen java.lang.StringBuilder): int -> ( sb.capacity() )
def mk = fn (): frozen java.lang.StringBuilder -> ( def sb = java.lang.StringBuilder("ab") ; freeze(sb) )
assert(f(mk()) >= 2)
```

## Passing frozen list to mut Java parameter is rejected

```babble
axiom java.util.Collections.reverse: fn (mut l: (java.util.List of any!)!): null
def f = fn io (xs: frozen list of int) -> ( java.util.Collections.reverse(xs) )
```

```error
Cannot pass frozen value to mut parameter
```

## Passing frozen value to unique Java parameter is rejected

```babble
axiom java.util.Collections.reverse: fn (unique l: (java.util.List of any!)!): null
def f = fn io (xs: frozen list of int) -> ( java.util.Collections.reverse(xs) )
```

```error
Argument for unique parameter cannot be frozen
```

## Passing frozen element to non-borrow Java parameter is rejected

```babble
def f = fn (mut xs: list of frozen Box, fb: frozen Box) -> ( xs.add(fb) )
```

```error
Cannot pass frozen value to non-borrow Java parameter
```

## Passing non-unique parameter to unique Java parameter is rejected

```babble
axiom java.util.Objects.requireNonNull: fn of T (unique obj: T): T
def f = fn (b: Box): Box -> ( java.util.Objects.requireNonNull(b) )
```

```error
Argument for unique parameter must be unique
```

## Passing live unique local to unique Java parameter moves it

```babble
axiom java.util.Objects.requireNonNull: fn of T (unique obj: T): T
def f = fn (): int -> ( def b = Box(1) ; def r = java.util.Objects.requireNonNull(b) ; r.v + b.v )
```

```error
Use of moved variable b
```

## Passing rebound borrowed parameter to unique Java parameter is rejected

```babble
axiom java.util.Objects.requireNonNull: fn of T (unique obj: T): T
def f = fn (b: Box): Box -> ( b = Box(1) ; java.util.Objects.requireNonNull(b) )
```

```error
Cannot move borrowed parameter b
```

## Unique Java parameter accepts fresh allocation, scalar, and frozen hashCode

```babble
axiom java.util.Objects.requireNonNull: fn of T (unique obj: T): T
def ok = fn volatile (): int -> (
    def b = java.util.Objects.requireNonNull(Box(5))
    def n = java.util.Objects.requireNonNull(7)
    def fb = freeze(Box(2))
    assert(java.util.Objects.hashCode(fb) != 0)
    b.v + n
)
assert(ok() == 12)
```

## Passing already-moved local to unique Java parameter is rejected

```babble
axiom java.util.Objects.requireNonNull: fn of T (unique obj: T): T
def f = fn (): Box -> ( def b = Box(1) ; def c = move(b) ; discard(c) ; java.util.Objects.requireNonNull(b) )
```

```error
Use of moved variable b
```

## Storing unique local into Java list demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def xs = list of Box () ; def b = Box(1) ; xs.add(b) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing unique local into list literal, entry, array.of, and array.set demotes uniqueness

```babble
def f1 = fn (): frozen Box -> ( def b = Box(1) ; def xs = list(b) ; discard(xs) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing unique local into entry demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def b = Box(1) ; def e = entry("k", b) ; discard(e) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing unique local into array.of demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def b = Box(1) ; def a = (array of Box).of(b) ; discard(a) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing unique local into array.set demotes uniqueness

```babble
def f = fn (): frozen Box -> (
    def a = (array of Box?)(1)
    def b = Box(1)
    a.set(0, b)
    freeze(b)
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing into local fresh container absorbs element provenance without escaping

```babble
def f = fn (b: Box): int -> (
    def h = Holder(null) ; h.child = b ; def c = h.child
    def h2 = Holder(null) ; def n = h2.link(b)
    def h3 = Holder(null) ; h3.child = Box(3) ; def fh = freeze(h3) ; def c3 = fh.child
    (if c != null then ( c.v ) else ( 0 )) + n + (if c3 != null then ( c3.v ) else ( 0 ))
)
assert(f(Box(4)) == 11)
```

## Storing unique local into local fresh container demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def h = Holder(null) ; def b = Box(1) ; h.child = b ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Local fresh container mut method with extra mut parameter stores normally

```babble
def Linker = class (def child: Box?) (
    def linkMut = fn mut (shared n: Box, mut other: Box): int -> ( this.child = n ; other.v += 1 ; n.v )
)
def f = fn (shared b: Box, mut o: Box): int -> ( def l = Linker(null) ; l.linkMut(b, o) )
def o = Box(1)
assert(f(Box(5), o) == 5 and o.v == 2)
```

## Deep mutation on local fresh container holding borrowed element detects parameter mutation

```babble
def f = fn (b: Box) -> ( def h = Holder(null) ; h.child = b ; h.bumpChild() )
```

```error
Cannot mutate parameter b (mut method h.bumpChild) : mark parameter as mut b
```

## Constructors : body field store and unique parameter store

```babble
def Wrap = class (b: Box) ( def slot: Box? = null ; this.slot = b )
def WrapU = class (unique u: Box) ( def slot: Box? = null ; this.slot = u )
def WrapUField = class (unique u: Box) ( def slot = u )
def w = WrapU(Box(2)) ; def wf = WrapUField(Box(3)) ; def w2 = Wrap(Box(1))
assert(wf.slot.v == 3 and w.slot != null and w2.slot != null)
```

## Constructors : non-stored borrowed parameter and non-hermetic constructor

```babble
def ReadOnly = class (b: Box) ( def v: int = b.v )
def Loud = class (def b: Box) ( println(this.b.v) )
def f = fn (b: Box): int -> ( ReadOnly(b).v )
def g = fn io (b: Box): int -> ( Loud(b).b.v )
assert(f(Box(4)) == 4 and g(Box(5)) == 5)
```

```stdout
5
```

## Passing unique local to storing constructor demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def b = Box(1) ; def h = Holder(b) ; discard(h) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Self-assignment this.next = this is allowed without shared

```babble
def Chain = class (def next: Chain?) (
    def cycle = fn mut () -> ( this.next = this )
)
def c = Chain(null)
c.cycle()
assert(same(c.next, c))
```

## Escaping this into parameter field requires shared

```babble
def Chain = class (def next: Chain?) (
    def attach = fn mut (mut p: Chain) -> ( p.next = this )
)
```

```error
Borrowed this escapes via field .next : mark function as shared
```

## Inline object storing unique local demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def u = Box(1) ; def o = object ( def b = u ) ; discard(o) ; freeze(u) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Frame-local closure assigning unique local to outer local demotes uniqueness

```babble
def f = fn (): frozen Box -> (
    def slot: Box? = null
    def h = fn (): frozen Box -> ( def u = Box(1) ; slot = u ; freeze(u) )
    h()
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Assigning unique local to global variable demotes uniqueness

```babble
def gSlot: Box? = null
def f = fn io (): frozen Box -> ( def u = Box(1) ; gSlot = u ; freeze(u) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Assigning unique local to static class field demotes uniqueness

```babble
def Cache = class () () static ( def slot: Box? = null )
def f = fn io (): frozen Box -> ( def u = Box(1) ; Cache.slot = u ; freeze(u) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Escaped closure capturing unique local demotes uniqueness

```babble
def makeReader = fn (flag: bool): fn (): int -> (
    def u = Box(1)
    def cap = u
    if flag then ( discard(freeze(u)) )
    fn (): int -> ( cap.v )
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Throwing unique local caught locally or conditionally escaping demotes uniqueness

```babble
def ErrBox = class (def v: int) ()
def f = fn (): frozen ErrBox -> (
    def u = ErrBox(1)
    try ( throw u ) catch e: ErrBox then ( discard(e) )
    freeze(u)
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Conditionally throwing unique local out of function demotes uniqueness

```babble
def ErrBox = class (def v: int) ()
def f = fn (flag: bool): frozen ErrBox -> (
    def u = ErrBox(1)
    if flag then ( throw u )
    freeze(u)
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Early return moves unique local and discarded self-returning call preserves uniqueness

```babble
def mk = fn (): unique Box -> ( def u = Box(5) ; return u )
def ok = fn (): frozen Box -> ( def b = Box(1) ; b.bumpSelf() ; freeze(b) )
assert(mk().v == 5 and ok().v == 2)
```

## Used self-returning call on unique local demotes uniqueness

```babble
def f = fn (): frozen Box -> ( def b = Box(1) ; def a = b.self() ; discard(a) ; freeze(b) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Copied spread of unique local into shared rest parameter demotes uniqueness

```babble
def sink = fn (shared items: Box...): int -> ( def n = 0 ; for x in items then ( n += 1 ) ; n )
def f = fn (): frozen list of Box -> ( def xs = list(Box(1)) ; discard(sink(...xs)) ; freeze(xs) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Copied spread of unique local into returned rest parameter demotes uniqueness

```babble
def idRest = fn (items: Box...): java.lang.Iterable of Box -> ( items )
def f = fn (): frozen list of Box -> ( def xs = list(Box(1)) ; def ys = idRest(...xs) ; discard(ys) ; freeze(xs) )
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Fresh rvalue spread into rest parameter succeeds

```babble
def sink = fn (shared items: Box...): int -> ( def n = 0 ; for x in items then ( n += 1 ) ; n )
assert(sink(...list(Box(1), Box(2))) == 2)
```

## Reachability PathKey.widen and Origin.widen converge on delayed sibling-field loop shift

```babble
def bad = fn (r: Root, n: int) -> (
    def a0 = r.a.x ; def a1: Box? = null ; def a2: Box? = null ; def a3: Box? = null ; def a4 = r.a.y
    def i = 0
    while i < n then (
        if i == 0 then ( def tmp = Box(0) ; discard(tmp) )
        a4 = a3 ; a3 = a2 ; a2 = a1 ; a1 = a0 ; i += 1
    )
    if a4 != null then ( a4.v = 1 )
)
```

```error
Cannot mutate parameter r (field assignment .v) : mark parameter as mut r
```

## Reachability Origin.join flattens element taint across param and this branches

```babble
def Mix = class (def v: int) (
    def joinPT = fn shared (f: bool, shared b: Box, o: list of any): int -> (
        def l1 = if f then ( list of any (b) ) else ( o )
        def l2 = if f then ( list of any (this) ) else ( o )
        def l3 = if f then ( list of Box () ) else ( list(b) )
        l1.size() + l2.size() + l3.size()
    )
)
assert(Mix(1).joinPT(true, Box(2), list of any ()) == 2)
```

## Reachability Origin.join and Provenance.union combine param, this, and external taint

```babble
def gBox = Box(7)
def Mix = class (def v: int) (
    def joinExt = fn io shared (flag: bool, shared b: Box, other: list of any): int -> (
        def l = if flag then ( list of any (gBox) ) else ( other )
        def mix = list of any () ; mix.add(b) ; mix.add(this) ; mix.add(gBox)
        l.size() + mix.size()
    )
)
assert(Mix(1).joinExt(true, Box(2), list of any ()) == 4)
```

## Reachability staticallySameObject detects branch-joined root alias against field path

```babble
def mutBoxReadPair = fn (mut b: Box, p: Pair): int -> ( b.v += 1 ; p.a.v )
def bad = fn (flag: bool, mut p: Pair, p2: Pair): int -> (
    def alias = if flag then ( p ) else ( p2 )
    mutBoxReadPair(p.a, alias)
)
```

```error
Exclusivity error : mut target p.a aliases argument alias
```

## Reachability staticallySameObject detects reverse order and truncates deep field paths

```babble
def mutPairReadBox = fn (mut p: Pair, b: Box): int -> ( p.a.v += 1 ; b.v )
def bad = fn (flag: bool, mut p: Pair, mut p2: Pair): int -> (
    def alias = if flag then ( p ) else ( p2 )
    mutPairReadBox(alias, p.a)
)
```

```error
Exclusivity error : mut target alias aliases argument p.a
```

## Reachability PathKey.append truncates five-level field chains cleanly

```babble
def Link = class (def next: Link?) ()
def read5 = fn (n: Link): bool -> (
    def a = n.next ; if a == null then ( false ) else (
    def b = a.next ; if b == null then ( false ) else (
    def c = b.next ; if c == null then ( false ) else (
    def d = c.next ; if d == null then ( false ) else ( d.next == null ))))
)
assert(read5(Link(null)) == false)
```
