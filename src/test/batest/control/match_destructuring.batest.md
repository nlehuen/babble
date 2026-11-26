# Record and sequence destructuring in `match` (`BIP038`)

References : `BIP038` §4.3 (Babble record destructuring: positional, named, partial, nested, generic, `frozen` propagation), §4.4 (`list` and `array` destructuring, trailing `...` and `...def tail` fresh slices).

```babble
;; babble preamble
def Point = record (x: int, y: int) ()
def Rect = record (origin: Point, w: int, h: int) ()
def Box of T = record (value: T) ()
def Pair of (A, B) = record (first: A, second: B) ()
def Tag = class (id: int) ()
```

## Positional record destructuring and wildcard

```babble
def f = fn (p: Point): string -> (
    match p
    case is Point(0, 0) then ("o")
    case is Point(0, def y) then ("y:" + y)
    case is Point(def x, def y) if x == y then ("d:" + x)
    case is Point(_, def y) then ("r:" + y)
)
assert(f(Point(0, 0)) == "o" and f(Point(0, 5)) == "y:5")
assert(f(Point(4, 4)) == "d:4" and f(Point(2, 9)) == "r:9")
```

## Named and partial record destructuring with trailing ellipsis

```babble
def f = fn (r: Rect): string -> (
    match r
    case is Rect(w: 0) then ("fw")
    case is Rect(is Point(0, 0), ...) then ("org")
    case is Rect(origin: is Point(def x, _), w: def w) then ("" + x + ":" + w)
)
assert(f(Rect(Point(1, 2), 0, 5)) == "fw")
assert(f(Rect(Point(0, 0), 3, 4)) == "org")
assert(f(Rect(Point(7, 8), 9, 10)) == "7:9")
```

## Binding whole record alongside destructured fields

```babble
def f = fn (v: Point | int): string -> (
    match v
    case def p: Point(def x, def y) if x > y then ("" + p.x + ">" + y)
    case def p: Point(def x, def y) then ("" + p.y + ">=" + x)
    case def n: int then ("n:" + n)
)
assert(f(Point(5, 2)) == "5>2" and f(Point(1, 4)) == "4>=1" and f(9) == "n:9")
```

## Generic record destructuring and union instantiation

```babble
def u = fn (b: Box of int | Box of string): string -> (
    match b
    case is Box(def v: int) then ("i:" + (v + 1))
    case is Box(def s: string) then ("s:" + s.length())
)
def sw = fn (p: Pair of (int, string)): string -> (match p case is Pair(def a, def b) then (b + ":" + (a * 2)))
assert(u(Box(41)) == "i:42" and u(Box("hi")) == "s:2" and sw(Pair(3, "ok")) == "ok:6")
```

## Recursive ADT evaluation via nested record destructuring

```babble
def Num = record (n: int) ()
def Add = record (l: Num | Add, r: Num | Add) ()
def eval = fn (e: Num | Add): int -> (
    match e
    case is Num(def n) then (n)
    case is Add(is Num(0), def r) then (recurse(r))
    case is Add(def l, def r) then (recurse(l) + recurse(r))
)
assert(eval(Add(Num(0), Add(Num(2), Num(5)))) == 7)
assert(eval(Add(Num(10), Num(20))) == 30)
```

## List destructuring exact length and trailing rest slice

```babble
def f = fn (xs: list of int): string -> (
    match xs
    case is list() then ("e")
    case is list(def a) then ("1:" + a)
    case is list(0, def b, ...) then ("z:" + b)
    case is list(def h, ...def t) then ("" + h + "+" + t.size())
)
assert(f(list()) == "e" and f(list(7)) == "1:7")
assert(f(list(0, 9, 8)) == "z:9" and f(list(1, 2, 3, 4)) == "1+3")
```

## List rest binding produces a fresh independent slice

```babble
def xs = list(10, 20, 30)
def tail = match xs
case is list(def h, ...def t) then (
    t.add(99)
    t
)
else (list of int())
assert(xs.size() == 3)
assert(tail.size() == 3)
assert(tail.get(0) == 20 and tail.get(2) == 99)
```

## Array destructuring (primitive and reference) with fresh slice

```babble
def headSum = fn (a: array of int): int -> (
    match a
    case is array() then (0)
    case is array(def x) then (x)
    case is array(def x, ...def rest) then (x + rest.size() * 100)
)
def a = array of int.of(5, 6, 7)
assert(headSum(array of int.of()) == 0)
assert(headSum(array of int.of(42)) == 42)
assert(headSum(a) == 205)
```

## Explicit typed array destructuring from any

```babble
def inspect = fn io (v: any): string -> (
    match v
    case is array of int(def a, def b) then ("i:" + (a + b))
    case is array of string(def s, ...) then ("s:" + s)
    case is list(def x, ...) then ("l:" + x)
    else ("o")
)
assert(inspect(array of int.of(3, 4)) == "i:7" and inspect(array of string.of("hi", "yo")) == "s:hi")
assert(inspect(list(99)) == "l:99" and inspect(123) == "o")
```

## Frozen record and list destructuring preserves frozen

```babble
def Holder = record (items: list of int) ()
def h: frozen Holder = freeze(Holder(list(1, 2, 3)))
def n = match h
case is Holder(is list(def first, ...def rest)) then (first + rest.size())
else (0)
assert(n == 3)
```

## Error : destructuring a non-record class rejected

```babble
def t = Tag(1)
match t
case is Tag(def id) then (id)
else (0)
```

```error
# error regex
.*Not a record type : Tag.*
```

## Error : wrong arity in positional record pattern

```babble
def p = Point(1, 2)
match p
case is Point(def x) then (x)
else (0)
```

```error
# error regex
.*Wrong number of fields in record pattern.*
```

## Error : unknown field in named record pattern

```babble
def p = Point(1, 2)
match p
case is Point(z: 1) then (1)
else (0)
```

```error
# error regex
.*Unknown record field : z.*
```

## Error : duplicate field in named record pattern

```babble
def p = Point(1, 2)
match p
case is Point(x: 1, x: 2) then (1)
else (0)
```

```error
# error regex
.*Duplicate record field : x.*
```

## Error : positional after named in record pattern

```babble
def p = Point(1, 2)
match p
case is Point(x: 1, 2) then (1)
else (0)
```

```error
# error regex
.*Positional argument after named argument.*
```

## Error : rest binding rejected in record pattern

```babble
def p = Point(1, 2)
match p
case is Point(def x, ...def r) then (x)
else (0)
```

```error
# error regex
.*Rest binding is not allowed in record pattern.*
```
