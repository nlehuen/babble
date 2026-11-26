# Range and floored division effects and algebraic properties

References : `BIP031` (`range of T`, `mod`, `//`, `nothrow` / `@TERMINATING` certification, `idempotent` & division-identity algebraic verification).

## Pure, nothrow, and terminating ranges and counted loops

```babble
def sumRange = fn nothrow (n: int): int -> (var a = 0 ; for i in 0..<n then (a += i) ; a)
def sumStep = fn nothrow (n: int): int -> (var a = 0 ; for i in (0..<n).step(2).reversed() then (a += i) ; a)
def sumStored = fn nothrow (r: range of int): int -> (var a = 0 ; for i in r then (a += i) ; a)
assert(sumRange(5) == 10 and sumStep(6) == 6 and sumStored(1..4) == 10)
```

## Nothrow constant-divisor mod/floorDiv, stepped range construction, and counted sum of squares

```babble
def divMod = fn nothrow (x: int): int -> ( (x // 4) + (x mod 4) )
def mkStep = fn nothrow (a: int, b: int): range of int -> ( (a..b).step(3).reversed() )
def sumSq = fn nothrow (n: int): int -> (
    var s = 0 ; for i in 1..n then ( s += i * i ) ; s
)
assert(divMod(-7) == -1 and mkStep(1, 10).contains(4) and sumSq(4) == 30)
```

## Variable divisor in mod violates nothrow

```babble
def bad = fn nothrow (x: int, d: int): int -> ( x mod d )
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Variable divisor in floor division violates nothrow

```babble
def bad = fn nothrow (x: int, d: int): int -> ( x // d )
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Zero constant divisor in mod violates nothrow

```babble
def bad = fn nothrow (x: int): int -> ( x mod 0 )
```

```stderr
Warning : Modulo by zero : always throws java.lang.ArithmeticException
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Variable step on range violates nothrow

```babble
def bad = fn nothrow (k: int): range of int -> ( (1..10).step(k) )
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Variable step in counted for loop violates nothrow

```babble
def bad = fn nothrow (k: int): int -> (
    var acc = 0
    for i in (1..10).step(k) then ( acc += i )
    acc
)
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Algebraic E-graph certification for floored mod idempotence

```babble
def wrap5 = fn idempotent (x: int): int -> ( x mod 5 )
def wrapNeg3 = fn idempotent (x: int): int -> ( x mod -3 )
def wrap1 = fn idempotent (x: int): int -> ( x mod 1 )
def bucket = fn idempotent (x: int): int -> ( x mod 8 )
assert(wrap5(-7) == 3 and wrapNeg3(7) == -2 and wrap1(42) == 0 and bucket(-1) == 7)
```

## Algebraic E-graph certification for floored division identity

```babble
def reconSum = fn commutative (a: int, b: int): int -> ( (5 * (a // 5) + (a mod 5)) + b )
assert(reconSum(-7, 10) == 3 and reconSum(10, -7) == 3)
```

## Floor division by 2 is not idempotent

```babble
def bad = fn idempotent (x: int): int -> ( x // 2 )
```

```error
Function bad declared idempotent violates idempotent guarantee
```
