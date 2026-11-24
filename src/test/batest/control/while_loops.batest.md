# While loops : zero/N iterations, nested loops, nullable return, any truthiness, and loop-local closure capture

References : TypeChecker, Interpreter, HirBuilder

## Case 1

```babble
def total = 0
def i = 1
while i <= 4 then (
    def j = 1
    while j <= i then (
        total += j
        j += 1
    )
    i += 1
)
assert(total == 20)
```

## Case 2

```babble
def x = 0
def r = while x > 0 then (
    x -= 1
    x
)
assert(r == null)
assert(classof(r) == null)
```

## Case 3

```babble
def i = 0
def r = while i < 3 then (
    i += 1
    i * 10
)
assert(r == 30)
```

## Truthy condition of type any stops when value becomes 0

```babble
def asAny = fn (v : int) : any -> ( v )
def n = 3
def sum = 0
while asAny(n) then (
    sum += n
    n -= 1
)
assert(sum == 6)
assert(n == 0)
```

## Case 5

```babble
def i = 0
def lastFn : fn () : int = fn () -> ( 0 )
while i < 3 then (
    def snap = i * 2
    lastFn = fn () : int -> ( snap )
    i += 1
)
assert(lastFn() == 4)
```
