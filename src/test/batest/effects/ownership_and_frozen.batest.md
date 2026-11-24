# Ownership, frozen values, unused-pure checks, and return-through-borrow

References : BIP007 (BIP007-I2, BIP007-D7), BIP016 (BIP016-D10)

```babble preamble
def MyRecord = record (a: int) ()
def Box = class (def n: int) ()
def id of T = fn (x: T): T -> ( x )
```

## BIP007-D7 : narrowing frozen any via `is MyRecord` must not lose frozen

```babble
def a: frozen any = MyRecord(5)
def b: MyRecord? = if a is MyRecord then ( a ) else ( null )
```

```error
Type error : expected MyRecord?, got frozen MyRecord
```

## BIP007-D7 : narrowing frozen any via `is MyRecord` preserves frozen

```babble
def a: frozen any = MyRecord(5)
def c: frozen MyRecord? = if a is MyRecord then ( a ) else ( null )
assert(c != null and c.a == 5)
```

## Unique local builder mutated inside a pure function and frozen for read-only sharing

```babble
def Counter = class (def n: int = 0) (
    def inc = fn mut nothrow () -> ( this.n = this.n + 1 )
    def value = fn nothrow (): int -> ( this.n )
)
def build = fn (): frozen Counter -> (
    def c = Counter(0)
    c.inc()
    c.inc()
    freeze(c)
)
assert(build().value() == 2)
```

## Error : Use of moved variable b

```babble
def f = fn (): int -> (
    def b = Box(1)
    def c = move(b)
    b.n + c.n
)
```

```error
Use of moved variable b
```

## Error : Unused pure expression

```babble
def f = fn (x: int): int -> (
    x + 1
    x
)
```

```error
Unused pure expression
```

## Case 6

```babble
def f = fn nothrow (x: int): int -> (
    discard(x + 1)
    x * 2
)
assert(f(21) == 42)
```

## BIP016-D10 : id and minBy return borrowed parameters without requiring shared

```babble
def minBy of T = fn (a: T, b: T, cmp: fn (x: T, y: T): int): T -> (
    if cmp(a, b) <= 0 then ( a ) else ( b )
)
def smallest = fn (a: Box, b: Box): Box -> (
    minBy(a, b, fn (x: Box, y: Box): int -> ( x.n - y.n ))
)
assert(id(Box(9)).n == 9)
assert(smallest(Box(7), Box(3)).n == 3)
```

## BIP016-D10 soundness : returning a unique local through id demotes uniqueness so freeze fails

```babble
def bad = fn (): frozen Box -> (
    def u = Box(1)
    def alias = id(u)
    discard(alias.n)
    freeze(u)
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```
