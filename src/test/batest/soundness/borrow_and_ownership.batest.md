# Soundness regressions : `ownership` (`O2`, `O3`, `O4`) unique capture, projection, fresh containers & exclusivity

Promoted from `BIP032` quarantine (`ownership_o3_closure_capture_unique_fbip.batest.md`, `ownership_o4_collection_get_mut_exclusivity.batest.md`, `ownership_o4_mut_exclusivity_field_alias.batest.md`, `ownership_o4_mut_exclusivity_closure_capture.batest.md`, `ownership_o4_mut_exclusivity_match_rest_slice.batest.md`, `ownership_o3_fresh_container_view_aliases_shared_arg.batest.md`) and sister vectors covering `InitEffectAnalyzer`, `MatchEffectAnalyzer`, `Reachability`, `EffectBodyWalker`, and `CallEffectAnalyzer`.

```babble
;; babble preamble
def Box = class (var x: int) ()
def consume = fn (unique xs: list of int): list of int -> (
    def ys = list of int (from: move(xs))
    ys.add(99)
    ys
)
def mutateBoth = fn (mut a: list of int, mut b: list of int): bool -> (
    a.add(1)
    same(a, b)
)
def swap = fn (mut a: Box, mut b: Box) -> (
    def t = a.x
    a.x = b.x
    b.x = t
)
```

## Closure capturing unique local invalidates uniqueness before move

```babble
def test = fn volatile (): string -> (
    def xs = list of int (1, 2)
    def f = fn volatile (): int -> (xs.size())
    def ys = consume(move(xs))
    "" + f() + ":" + ys.size()
)
test()
```

```error
Argument for unique parameter xs must be unique
```

## Closure capturing unique parameter invalidates uniqueness before move

```babble
def bad = fn volatile (unique xs: list of int): int -> (
    def f = fn volatile (): int -> (xs.size())
    def ys = consume(move(xs))
    f() + ys.size()
)
```

```error
Argument for unique parameter xs must be unique
```

## Closure capturing already-moved unique local is rejected

```babble
def bad = fn volatile (): int -> (
    def xs = list of int (1, 2)
    def ys = consume(move(xs))
    def f = fn volatile (): int -> (xs.size())
    f() + ys.size()
)
```

```error
Use of moved variable xs
```

## Object capturing unique local invalidates uniqueness before move

```babble
def bad = fn volatile (): int -> (
    def xs = list of int (1, 2)
    def o = object (def n = fn volatile (): int -> (xs.size()))
    def ys = consume(move(xs))
    o.n() + ys.size()
)
```

```error
Argument for unique parameter xs must be unique
```

## Local class capturing unique local invalidates uniqueness before move

```babble
def bad = fn volatile (): int -> (
    def xs = list of int (1, 2)
    def Reader = class () (def n = fn volatile (): int -> (xs.size()))
    def r = Reader()
    def ys = consume(move(xs))
    r.n() + ys.size()
)
```

```error
Argument for unique parameter xs must be unique
```

## Freeze after closure captures unique local is rejected

```babble
def bad = fn volatile (): int -> (
    def xs = list of int (1, 2)
    def f = fn volatile (): int -> (xs.size())
    def fr = freeze(move(xs))
    f() + fr.size()
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Frame-local closure updating local var and local list mutation remain pure

```babble
def sumUp = fn (n: int): int -> (
    var acc = 0
    def add = fn (v: int) -> (acc += v)
    for i in 0..n then (add(i))
    acc
)
assert(sumUp(4) == 10)
```

## Passing aliased list.get(0) twice to two mut parameters fails exclusivity

```babble
def inner = list of int (0)
def outer = list of (list of int) (inner)
mutateBoth(outer.get(0), outer.get(0))
```

```error
Exclusivity error : mut target outer.get(0) aliases argument outer.get(0)
```

## Passing list.get(0) alongside stored inner element fails exclusivity

```babble
def inner = list of int (0)
def outer = list of (list of int) (inner)
mutateBoth(outer.get(0), inner)
```

```error
Exclusivity error : mut target outer.get(0) aliases argument inner
```

## Passing list.get(0) and list.get(1) fails when same inner element was stored twice

```babble
def inner = list of int (0)
def outer = list of (list of int) (inner, inner)
mutateBoth(outer.get(0), outer.get(1))
```

```error
Exclusivity error : mut target outer.get(0) aliases argument outer.get(1)
```

## Passing aliased array.get(0) twice fails exclusivity

```babble
def arr = (array of Box).of(Box(0), Box(1))
swap(arr.get(0), arr.get(0))
```

```error
Exclusivity error : mut target arr.get(0) aliases argument arr.get(0)
```

## Passing array.get with dynamic indices i and j fails exclusivity

```babble
def bad = fn (i: int, j: int) -> (
    def arr = (array of Box).of(Box(0), Box(1))
    swap(arr.get(i), arr.get(j))
)
```

```error
Exclusivity error : mut target arr.get(i) aliases argument arr.get(j)
```

## Passing aliased map.get twice fails exclusivity

```babble
def m = map of (string, Box) (entry("k", Box(1)))
swap(m.get("k")!, m.get("k")!)
```

```error
Exclusivity error : mut target m.get("k")! aliases argument m.get("k")!
```

## Passing overlapping subList and reversed views fails exclusivity

```babble
def xs = list of int (1, 2, 3)
def sub = xs.subList(0, 2)
def rev = xs.reversed()
mutateBoth(sub, rev)
```

```error
Exclusivity error : mut target sub aliases argument rev
```

## Passing conditional branch alias alongside one branch fails exclusivity

```babble
def bad = fn (cond: bool, mut a: Box, mut b: Box) -> (
    def c = if cond then (a) else (b)
    swap(c, a)
)
```

```error
Exclusivity error : mut target c aliases argument a
```

## Passing return-through-borrow identity alongside original fails exclusivity

```babble
def id = fn (x: list of int): list of int -> (x)
def xs = list of int (1)
mutateBoth(id(xs), xs)
```

```error
Exclusivity error : mut target id(xs) aliases argument xs
```

## Distinct constant indices on fresh array elements remain allowed

```babble
def arr = (array of Box).of(Box(1), Box(2))
swap(arr.get(0), arr.get(1))
assert(arr.get(0).x == 2 and arr.get(1).x == 1)
```

## Two fields of the same record aliasing one mutable instance must fail exclusivity

```babble
def Pair = record (a: Box, b: Box) ()
def b = Box(1)
def p = Pair(b, b)
swap(p.a, p.b)
```

```error
Exclusivity error : mut target p.a aliases argument p.b
```

## Record field aliasing an outer mutable local must fail exclusivity against that local

```babble
def Pair = record (a: Box, b: Box) ()
def b = Box(1)
def p = Pair(b, Box(2))
swap(p.a, b)
```

```error
Exclusivity error : mut target p.a aliases argument b
```

## Mutating class fields to alias the same mutable local must fail exclusivity

```babble
def Pair = class (var a: Box, var b: Box) ()
def b = Box(1)
def p = Pair(Box(2), Box(3))
p.a = b
p.b = b
swap(p.a, p.b)
```

```error
Exclusivity error : mut target p.a aliases argument p.b
```

## Disjoint record fields from fresh or distinct boxes remain allowed

```babble
def Pair = record (a: Box, b: Box) ()
def p1 = Pair(Box(1), Box(2))
swap(p1.a, p1.b)
def x = Box(3)
def y = Box(4)
def p2 = Pair(x, y)
swap(p2.a, p2.b)
assert(p1.a.x == 2 and p1.b.x == 1 and p2.a.x == 4 and p2.b.x == 3)
```

## Closure reading a mutable local must fail exclusivity alongside mut borrow of same local

```babble
def run = fn volatile calling f (f: fn volatile (): int, mut b: Box): bool -> (
    def v1 = f()
    b.x = 99
    def v2 = f()
    v1 != v2
)
def b = Box(1)
run(fn volatile (): int -> (b.x), b)
```

```error
Exclusivity error : mut target b aliases argument fnvolatile():int->(b.x)
```

## Closure mutating a mutable local must fail exclusivity alongside mut borrow of same local

```babble
def run = fn io calling f (f: fn io (), mut b: Box): int -> (
    b.x = 1
    f()
    b.x
)
def b = Box(0)
run(fn io () -> (b.x = 99), b)
```

```error
Exclusivity error : mut target b aliases argument fnio()->(b.x=99)
```

## Local closure variable capturing mutable local fails exclusivity inside function

```babble
def run = fn volatile calling f (f: fn volatile (): int, mut b: Box): int -> (
    b.x = 99
    f()
)
def bad = fn io (): int -> (
    def b = Box(1)
    def reader = fn volatile (): int -> (b.x)
    run(reader, b)
)
bad()
```

```error
Exclusivity error : mut target b aliases argument reader
```

## Closure capturing a disjoint mutable local remains allowed alongside mut borrow

```babble
def run = fn volatile calling f (f: fn volatile (): int, mut b: Box): int -> (
    b.x = 99
    f() + b.x
)
def test = fn volatile (): int -> (
    def a = Box(1)
    def b = Box(2)
    run(fn volatile (): int -> (a.x), b)
)
assert(test() == 100)
```

## List match rest slice element must fail exclusivity against original list element

```babble
def xs = list of Box (Box(0), Box(1))
match xs
case is list(_, ...def tail) then (swap(tail.get(0), xs.get(1)))
else ()
```

```error
Exclusivity error : mut target tail.get(0) aliases argument xs.get(1)
```

## Array match rest slice element must fail exclusivity against original array element

```babble
def arr = (array of Box).of(Box(0), Box(1))
match arr
case is array(_, ...def tail) then (swap(tail.get(0), arr.get(1)))
else ()
```

```error
Exclusivity error : mut target tail.get(0) aliases argument arr.get(1)
```

## Freezing a borrowed mutable-element list match rest slice must be rejected

```babble
def b = Box(1)
def xs = list of Box (b, b)
match xs
case is list(_, ...def tail) then (freeze(tail))
else (null)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## View of fresh Optional holding a live local must not be unique

```babble
def addL = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def t = fn (): string -> (
    def a = list(1, 2)
    def c = addL(java.util.Optional.of(a).get())
    string(a) + string(c)
)
```

```error
Argument for unique parameter xs must be unique
```

## Record field of a view of a fresh Optional must not be unique

```babble
def addL = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def R = record (xs: list of int) ()
def t = fn (): string -> (
    def a = list(1, 2)
    def c = addL(java.util.Optional.of(R(a)).orElseThrow().xs)
    string(a) + string(c)
)
```

```error
Argument for unique parameter xs must be unique
```

## View returning its argument must not be unique

```babble
def addL = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def t = fn (): string -> (
    def a = list(1, 2)
    def e: java.util.Optional of (list of int) = java.util.Optional.empty()
    def c = addL(e.orElse(a))
    string(a) + string(c)
)
```

```error
Argument for unique parameter xs must be unique
```

## View of a fresh Optional of a fresh list stays unique

```babble
def addL = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def t = fn (): string -> (string(addL(java.util.Optional.of(list(1, 2)).get())))
assert(t() == "[1, 2, 9]")
```
