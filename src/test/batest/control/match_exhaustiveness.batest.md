# Pattern-match exhaustiveness, reachability, ownership, and termination (`BIP038`)

References : `BIP038` §5 (Maranget exhaustiveness over `bool`, `T?`, unions, ADT aliases, nested records, `list`/`array` lengths; counterexample witnesses; unreachable arms; open-domain constant/range shadowing), §6 (`@BORROWED`/`@UNIQUE` ownership & `SizeChangeTermination`).

```babble
;; babble preamble
def Circle = record (r: int) ()
def Rect = record (w: int, h: int) ()
def Shape = type Circle | Rect
def Pair of (A, B) = record (a: A, b: B) ()
def Num = record (n: int) ()
def Add = record (l: Num | Add, r: Num | Add) ()
def Wrap = record (items: list of int) ()
```

## Exhaustive nested record bool matrix without else

```babble
def xor = fn (p: Pair of (bool, bool)): bool -> (
    match p
    case is Pair(true, true) then (false)
    case is Pair(true, false) then (true)
    case is Pair(false, true) then (true)
    case is Pair(false, false) then (false)
)
assert(not xor(Pair(true, true)) and xor(Pair(true, false)))
assert(xor(Pair(false, true)) and not xor(Pair(false, false)))
```

## Exhaustive ADT alias and nullable union without else

```babble
def area = fn (s: Shape?): int -> (
    match s
    case null then (0)
    case is Circle(def r) then (r * r * 3)
    case is Rect(def w, def h) then (w * h)
)
assert(area(null) == 0 and area(Circle(2)) == 12 and area(Rect(3, 4)) == 12)
```

## Exhaustive sequence length decomposition on list of bool

```babble
def firstKind = fn (xs: list of bool): string -> (
    match xs
    case is list() then ("empty")
    case is list(true, ...) then ("t")
    case is list(false, ...) then ("f")
)
assert(firstKind(list of bool()) == "empty")
assert(firstKind(list(true, false)) == "t")
assert(firstKind(list(false)) == "f")
```

## Exhaustive sequence length decomposition on array

```babble
def sz = fn (a: array of int): string -> (
    match a
    case is array() then ("0")
    case is array(_) then ("1")
    case is array(_, _, ...) then ("2+")
)
assert(sz(array of int.of()) == "0" and sz(array of int.of(9)) == "1")
assert(sz(array of int.of(1, 2, 3)) == "2+")
```

## Borrowing match keeps local unique live and returns borrowed field

```babble
def peek = fn (w: Wrap): list of int -> (
    match w
    case is Wrap(def xs) then (xs)
)
def take = fn (unique xs: list of int): int -> (xs.size())
def run = fn (): int -> (
    def a = list(1, 2)
    def n = match a case is list(def h, ...) then (h) else (0)
    n + take(move(a)) + peek(Wrap(list(9))).get(0)
)
assert(run() == 12)
```

## Consuming match move transfers unique ownership to top-level binding

```babble
def consume = fn (unique xs: list of int): int -> (xs.size())
def f = fn (b: bool): int -> (
    def xs = list(10, 20, 30)
    match move(xs)
    case def ys if b then (consume(move(ys)))
    else def zs then (consume(move(zs)) * 10)
)
assert(f(true) == 3 and f(false) == 30)
```

## Recursive match functions certified pure terminating

```babble
def eval = fn (e: Num | Add): int -> (
    match e
    case is Num(def n) then (n)
    case is Add(def l, def r) then (recurse(l) + recurse(r))
)
def fact = fn (n: int): int -> (
    match n
    case < 0 then (0)
    case 0 then (1)
    else (n * recurse(n - 1))
)
assert(eval(Add(Num(4), Add(Num(5), Num(6)))) == 15 and fact(5) == 120)
```

## Error : non-exhaustive bool names missing constructor

```babble
def f = fn (b: bool): int -> (
    match b
    case true then (1)
)
```

```error
# error regex
.*Non-exhaustive match : missing false.*
```

## Error : non-exhaustive nullable names missing null

```babble
def f = fn (x: int?): int -> (
    match x
    case def n: int then (n)
)
```

```error
# error regex
.*Non-exhaustive match : missing null.*
```

## Error : non-exhaustive nullable names missing non-null type

```babble
def f = fn (x: int?): int -> (
    match x
    case null then (0)
)
```

```error
# error regex
.*Non-exhaustive match : missing int.*
```

## Error : non-exhaustive ADT union names missing variant

```babble
def f = fn (s: Shape): int -> (
    match s
    case is Circle(def r) then (r)
)
```

```error
# error regex
.*Non-exhaustive match : missing Rect.*
```

## Error : non-exhaustive nested record names missing tuple

```babble
def f = fn (p: Pair of (bool, bool)): int -> (
    match p
    case is Pair(true, _) then (1)
    case is Pair(false, false) then (2)
)
```

```error
# error regex
.*Non-exhaustive match : missing Pair\(false, true\).*
```

## Error : non-exhaustive list names shortest uncovered length

```babble
def f = fn (xs: list of int): int -> (
    match xs
    case is list() then (0)
    case is list(_) then (1)
)
```

```error
# error regex
.*Non-exhaustive match : missing list\(_, _\).*
```

## Error : non-exhaustive open domain asks for else

```babble
def f = fn (n: int): string -> (
    match n
    case 0 then ("z")
    case 1 then ("o")
)
```

```error
# error regex
.*Non-exhaustive match : add an else branch.*
```

## Error : guarded case does not satisfy exhaustiveness

```babble
def f = fn (b: bool): int -> (
    match b
    case true if b then (1)
    case false then (0)
)
```

```error
# error regex
.*Non-exhaustive match : missing true.*
```

## Error : unreachable duplicate constant arm

```babble
def f = fn (n: int): int -> (
    match n
    case 1 then (10)
    case 1 then (20)
    else (0)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable scalar shadowed by constant range

```babble
def f = fn (n: int): int -> (
    match n
    case 0..10 then (1)
    case 5 then (2)
    else (0)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable sub-range shadowed by interval union

```babble
def f = fn (n: int): int -> (
    match n
    case 0..5 then (1)
    case 6..10 then (2)
    case 3..8 then (3)
    else (0)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable stepped range element

```babble
def f = fn (n: int): int -> (
    match n
    case (0..10).step(2) then (1)
    case 4 then (2)
    else (0)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable union type arm after earlier match

```babble
def f = fn (x: int | string): int -> (
    match x
    case is int then (1)
    case is int then (2)
    case is string then (3)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable sequence pattern shadowed by earlier rest

```babble
def f = fn (xs: list of bool): int -> (
    match xs
    case is list(true, ...) then (1)
    case is list(true, false) then (2)
    else (0)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : unreachable guarded arm after exhaustive prefix

```babble
def f = fn (b: bool): int -> (
    match b
    case true then (1)
    case false then (2)
    case true if b then (3)
)
```

```error
# error regex
.*Unreachable case : already matched by earlier case.*
```

## Error : impossible pattern on incompatible scrutinee type

```babble
def f = fn (n: int): int -> (
    match n
    case is string then (1)
    else (0)
)
```

```error
# error regex
.*Impossible pattern : int cannot match is string.*
```
