# Soundness regressions : `ownership` (`O3`, `O4`) unique capture & projection exclusivity

Promoted from `BIP032` quarantine (`ownership_o3_closure_capture_unique_fbip.batest.md`, `ownership_o4_collection_get_mut_exclusivity.batest.md`) and sister vectors covering `InitEffectAnalyzer`, `Reachability`, and `CallEffectAnalyzer`.

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
