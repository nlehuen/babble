# Floored modulo (`mod`) and integer division (`//`, `//=`)

References : `BIP031` (`mod`, `//`, `//=`, floored division identity, `MIN_VALUE // -1`, operator methods, power-of-two lowering).

```babble
;; babble preamble
def expectInt = fn io (actual: int, expected: int) -> (
    assert(actual == expected, "expected " + expected + ", got " + actual)
)

def expectLong = fn io (actual: long, expected: long) -> (
    assert(actual == expected, "expected " + expected + ", got " + actual)
)
```

## Floored semantics across all four sign combinations for int

```babble
expectInt(7 // 3, 2)
expectInt(7 mod 3, 1)
expectInt(-7 // 3, -3)
expectInt(-7 mod 3, 2)
expectInt(7 // -3, -3)
expectInt(7 mod -3, -2)
expectInt(-7 // -3, 2)
expectInt(-7 mod -3, -1)
```

## Division identity across negative and power-of-two divisors

```babble
for a in -13..13 then (
    for b in list of int(-8, -5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 8) then (
        def q = a // b
        def r = a mod b
        assert(b * q + r == a)
        if b > 0 then ( assert(r >= 0 and r < b) ) else ( assert(r <= 0 and r > b) )
    )
)
```

## Power-of-two constant divisors for int and long

```babble
var x = -7
expectInt(x // 4, -2)
expectInt(x mod 4, 1)
expectInt(x // 1, -7)
expectInt(x mod 1, 0)
expectInt(x // 8, -1)
expectInt(x mod 8, 1)

var y = -17l
expectLong(y // 8l, -3l)
expectLong(y mod 8l, 7l)
expectLong(y // 1l, -17l)
expectLong(y mod 1l, 0l)
```

## MIN_VALUE floorDiv and mod by -1 wrap without trapping

```babble
var minI = -2147483648
var neg1 = -1
expectInt(minI // neg1, minI)
expectInt(minI mod neg1, 0)

var minL = -9223372036854775807l - 1l
var neg1L = -1l
expectLong(minL // neg1L, minL)
expectLong(minL mod neg1L, 0l)
```

## Integral promotion (byte, short, int, long) and compound assignment //=

```babble
expectInt(byte(-7) // short(3), -3)
expectInt(byte(-7) mod short(3), 2)
expectLong(int(-7) // 3l, -3l)
expectLong(int(-7) mod 3l, 2l)

var n = -17
n //= 4
expectInt(n, -5)
n //= -2
expectInt(n, 2)

var m = -17l
m //= 4l
expectLong(m, -5l)
```

## Precedence with *, /, +, - and range operators

```babble
expectInt(10 + 7 mod 3 * 2, 12)
expectInt(20 // 3 + 20 mod 3, 8)
expectInt(100 // 3 // 2, 16)
expectInt(-7 // 2 + 1, -3)
def r = (10 // 3)..(10 mod 7)
assert(r.start() == 3 and r.end() == 3)
```

## Operator methods a.mod(b) and a.floorDiv(b) on records

```babble
def Wrap = record (v: int) (
    def floorDiv = fn (other: Wrap): Wrap -> ( Wrap(this.v // other.v) )
    def mod = fn (other: Wrap): Wrap -> ( Wrap(this.v mod other.v) )
)
def a = Wrap(-7)
def b = Wrap(3)
assert((a // b) == Wrap(-3))
assert((a mod b) == Wrap(2))
```

## Division by zero with // throws ArithmeticException

```babble
var d = 0
def x = 10 // d
```

```error
java.lang.ArithmeticException : / by zero
```

## Modulo by zero with mod throws ArithmeticException

```babble
var d = 0l
def x = 10l mod d
```

```error
java.lang.ArithmeticException : / by zero
```

## Int modulo by zero throws ArithmeticException

```babble
var d = 0
def x = 10 mod d
```

```error
java.lang.ArithmeticException : / by zero
```

## Long floor division by zero throws ArithmeticException

```babble
var d = 0l
def x = 10l // d
```

```error
java.lang.ArithmeticException : / by zero
```

## Floating-point operand rejected by mod

```babble
def x = 1.5 mod 2
```

```error
Not an integer : 1.5
```

## Floating-point operand rejected by floor division

```babble
def x = 1.5 // 2
```

```error
Not an integer : 1.5
```

## Function-local int power-of-two and non-negative dividend lowering

```babble
def fastInt = fn (x: int, s: string, xs: list of int, m: map of (string, int), arr: array of int): int -> (
    (x // 4) + (x mod 4) + (s.length() mod 3) + (xs.size() // 3) + (m.size() mod 3) + (arr.size() // 3) + ((x mod 7) // 3) + (int('z') mod 5)
)
assert(fastInt(-7, "hello", list(1, 2, 3, 4), (map of (string, int)).of("a", 1, "b", 2), array of int(4)) == 7)
```

## Function-local long power-of-two and non-negative dividend lowering

```babble
def fastLong = fn (y: long, arr: array of int): long -> (
    (y // 8l) + (y mod 8l) + (long(arr.size()) mod 3l) + ((y mod 7l) // 3l)
)
var fl = fastLong
assert(fl(-17l, array of int(4)) == 6l)
```

## Function-local branch-merged int divisors and constant folding

```babble
def sccpI = fn (x: int, fl: bool): int -> (
    var a = 0 ; var b = 0 ; var d = 0 ; var m = 0 ; var t = 0
    if fl then ( a = -7 ; b = 3 ; d = 1 ; m = -1 ; t = 3 ) else ( a = -7 ; b = 3 ; d = 1 ; m = -1 ; t = 3 )
    (a // b) + (a mod b) + (x // d) + (x // m) + (x mod d) + (x mod m) + (x // t) + (x mod t)
)
var fi = sccpI
assert(fi(10, true) == 3 and fi(10, false) == 3)
```

## Function-local branch-merged long divisors and constant folding

```babble
def sccpL = fn (x: long, fl: bool): long -> (
    var a = 0l ; var b = 0l ; var d = 0l ; var m = 0l ; var t = 0l
    if fl then ( a = -7l ; b = 3l ; d = 1l ; m = -1l ; t = 3l ) else ( a = -7l ; b = 3l ; d = 1l ; m = -1l ; t = 3l )
    (a // b) + (a mod b) + (x // d) + (x // m) + (x mod d) + (x mod m) + (x // t) + (x mod t)
)
var fl2 = sccpL
assert(fl2(10l, true) == 3l and fl2(10l, false) == 3l)
```

## Function-local branch-merged int zero divisor throws ArithmeticException

```babble
def gzI = fn (x: int, fl: bool): int -> (
    var z = 1
    if fl then ( z = 0 ) else ( z = 0 )
    x // z
)
var fg = gzI
fg(5, true)
```

```error
java.lang.ArithmeticException : / by zero
```

## Function-local branch-merged long zero divisor throws ArithmeticException

```babble
def gzL = fn (y: long, fl: bool): long -> (
    var z = 1l
    if fl then ( z = 0l ) else ( z = 0l )
    y mod z
)
var fg = gzL
fg(5l, false)
```

```error
java.lang.ArithmeticException : / by zero
```
