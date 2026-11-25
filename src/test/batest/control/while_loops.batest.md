# While loops : zero/N iterations, nested loops, nullable return, any truthiness, and loop-local closure capture

References : TypeChecker, Interpreter, HirBuilder

## Case 1

```babble
var total = 0
var i = 1
while i <= 4 then (
    var j = 1
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
var x = 0
def r = while x > 0 then (
    x -= 1
    x
)
assert(r == null)
assert(classof(r) == null)
```

## Case 3

```babble
var i = 0
def r = while i < 3 then (
    i += 1
    i * 10
)
assert(r == 30)
```

## Truthy condition of type any stops when value becomes 0

```babble
def asAny = fn (v : int) : any -> ( v )
var n = 3
var sum = 0
while asAny(n) then (
    sum += n
    n -= 1
)
assert(sum == 6)
assert(n == 0)
```

## Case 5

```babble
var i = 0
var lastFn : fn () : int = fn () -> ( 0 )
while i < 3 then (
    def snap = i * 2
    lastFn = fn () : int -> ( snap )
    i += 1
)
assert(lastFn() == 4)
```
