# Int arithmetic, comparisons, range limits, wraparound, and errors

References : BIP011 (core scalar types), BIP002 (operator methods)
Runtime arithmetic inputs go through a mutable-state helper so -O1 cannot constant-fold them away.

```babble
;; babble preamble
def _i = 0
def rt = fn io (x : int) : int -> ( _i = x ; _i )

def Checked = record (x : int) (
    def div = fn (k : int) : int -> (
        assert(k != 0, "Divide by zero")
        int(this.x / k)
    )
)
```

## Case 1

```babble
def calc = fn (a : int, b) -> ( Checked(a) / b )
assert(calc(10, 2) == 5)
```

## Error : Divide by zero

```babble
def calc = fn (a : int, b) -> ( Checked(a) / b )
discard(calc(10, 0))
```

```error
Divide by zero
```

## Error : Type error : expected int, got string

```babble
def calc = fn (a : int, b) -> ( Checked(a) / b )
discard(calc(10, "two"))
```

```error
Type error : expected int, got string
```

## Case 4

```babble
def minV = rt(-2147483648)
def maxV = rt(2147483647)
assert(maxV + 1 == -2147483648)
assert(minV - 1 == 2147483647)
assert(-minV == -2147483648)
assert(minV * -1 == -2147483648)
```

## Int division always produces double

```babble
def five = rt(5)
def four = rt(4)
def two = rt(2)
assert(string(classof(five / two)) == "java.lang.Double")
assert(five / two == 2.5)
assert(four / two == 2.0)
```

## Case 6

```babble
def one = rt(1)
def two = rt(2)
def three = rt(3)
def four = rt(4)
assert(one < two)
assert(two <= two)
assert(three > two)
assert(not (three >= four))
assert(two == two)
assert(two != three)
assert(two <> three)
```

## Error : Integer out of range : 99999999999

```babble
def x = 99999999999
```

```error
Integer out of range : 99999999999
```

## Error : Integer out of range : -2147483649

```babble
def x = -2147483649
```

```error
Integer out of range : -2147483649
```

## Compound assignment: +=, -=, *= keep int; /= widens int variable to double

```babble
def n = rt(10)
n += rt(5) ; n -= rt(3) ; n *= rt(2)
assert(n == 24)
assert(string(classof(n)) == "java.lang.Integer")
def d = rt(10)
d /= rt(4)
assert(d == 2.5)
assert(string(classof(d)) == "java.lang.Double")
```
