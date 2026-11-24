# Self tail calls run in constant stack space, each call a fresh activation, and code that

never ends and cannot throw must say so with forever.
References : BIP024 (D1 self calls only, D3 tail position, D5 fresh activations,
D7 recursion without a base case, D8 loops without an exit, D9 forever),
analysis.TailCalls, Function.invoke (interpreter), HirEmitter.emitSelfTailCall.
Deep enough to overflow the stack in every mode without the optimization.

```babble
;; babble preamble
def deep = 1000000
```

## The default value is evaluated again for each activation : the last one has n == 0.

```babble
def f = fn (n: int, seen: int = n * 10): int -> (if n == 0 then (seen) else (recurse(n - 1)))
assert(f(deep) == 0)
assert(f(0, 7) == 7)
```

## Case 2

```babble
def sum = fn (n: int, acc: int): int -> (if n == 0 then (acc) else (sum(acc: acc + 1, n: n - 1)))
assert(sum(deep, 0) == deep)
```

## Each activation has its own def : the closure keeps the one of the activation that made it.

```babble
def f = fn (n: int, prev: fn (): int): int -> (
    def mine = n
    if n == 0 then (prev()) else (recurse(n - 1, fn (): int -> (mine)))
)
assert(f(3, fn (): int -> (99)) == 1)
```

## Case 4

```babble
def o = object (
    def down = fn (n: int): int -> (if n == 0 then (42) else (this.down(n - 1)))
)
assert(o.down(deep) == 42)
```

## An error in an optimized loop reports the activation that failed.

```babble
def f = fn (n: int): int -> (
    assert(n > 0, "reached " + n)
    recurse(n - 1)
)
f(deep)
```

```error
reached 0
```

## Mutual recursion is not optimized (D1).

```babble
def isEven = fn (n: int): bool -> (if n == 0 then (true) else (isOdd(n - 1)))
def isOdd = fn (n: int): bool -> (if n == 0 then (false) else (isEven(n - 1)))
isEven(deep)
```

```error
Stack overflow
```

## Error : Recursion without a base case : f. If this is intended, wrap the expr...

```babble
def f = fn (n: int): int -> (if n > 0 then (recurse(n + 1)) else (f(1)))
```

```error
Recursion without a base case : f. If this is intended, wrap the expression with forever
```

## Case 8

```babble
def f = fn (n: int): int -> (if n > 0 then (recurse(n + 1)) else (forever f(1)))
def g = fn (n: int): int -> (forever recurse(n + 1))
```

## continue does not leave the loop, and break only leaves the inner one.

```babble
def x = 0
while true then (
    x += 1
    while true then (break)
    continue
)
```

```error
Loop without an exit : while true. If this is intended, wrap the expression with forever
```

## forever is the expression itself.

```babble
def v = forever (1 + 2)
assert(v == 3)
```

## More than 8 parameters, reordered by name : the arguments go through an array.

```babble
def many = fn (n: int, a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int): int -> (
    if n == 0 then (a + h) else (recurse(h: h, g: g, f: f, e: e, d: d, c: c, b: b, a: a, n: n - 1))
)
assert(many(deep, 1, 2, 3, 4, 5, 6, 7, 8) == 9)
```

## An omitted optional parameter of an unboxed signature : its mask is passed again, so the

```babble
;; tail call takes the default step of 1 even after a first call with 3.
def f = fn (n: int, step: int = 1): int -> (if n <= 0 then (n) else (recurse(n - step)))
assert(f(deep) == 0)
assert(f(deep, 3) == 0)
assert(f(2, 3) == -1)
```

## forever may sit on a returned value, an else if branch, or a loop in parentheses.

```babble
def a = fn (n: int): int -> (if n > 0 then (return forever recurse(n - 1)) else (a(1)))
def b = fn (n: int): int -> (if n > 0 then (b(n)) else if n < 0 then (forever b(n)) else (b(1)))
def c = fn (): int -> (
    def i = 0
    forever (while (true) then (i += 1))
    i
)
```

## An interface method may be overridden : its call to itself is neither optimized nor reported.

```babble
def I = interface (
    def m = fn (n: int): int -> (this.m(n + 1))
)
```

## Class method self tail call via this and call to sibling method

```babble
def C = class () (
    def other = fn (n: int): int -> ( n + 1 )
    def down = fn (n: int): int -> (
        if n == 0 then ( this.other(41) ) else ( this.down(n - 1) )
    )
)
assert(C().down(deep) == 42)
```

## Closure inside a class method calling this.m is not a self tail call

```babble
def C = class () (
    def f = fn (n: int): int -> (
        def g = fn (): int -> ( if n == 0 then ( 1 ) else ( this.f(n - 1) ) )
        g()
    )
)
assert(C().f(5) == 1)
```

## Early return with non-recursive value provides a base case

```babble
def f = fn (n: int): int -> (
    if n == 0 then ( return 7 )
    recurse(n - 1)
)
assert(f(deep) == 7)
```

## Early return that also self-recurses still lacks a base case

```babble
def f = fn (n: int): int -> (
    if n == 0 then ( return recurse(1) )
    recurse(n + 1)
)
```

```error
Recursion without a base case : f. If this is intended, wrap the expression with forever
```

## Early return wrapped in forever acknowledges endless recursion

```babble
def f = fn (n: int): int -> (
    if n == 0 then ( return forever recurse(1) )
    recurse(n + 1)
)
assert(f != null)
```

## forever inside a parenthesized block and in the then branch acknowledges recursion

```babble
def a = fn (n: int): int -> ( ( forever recurse(n + 1) ) )
def b = fn (n: int): int -> ( if n > 0 then ( forever recurse(n + 1) ) else ( recurse(n - 1) ) )
assert(a != null and b != null)
```

## Nested function, class, object, and interface returns do not count as base cases

```babble
def f = fn (n: int): int -> (
    def h = fn (): int -> ( return 1 )
    def C = class () ( def m = fn (): int -> ( return 1 ) )
    def o = object ( def m = fn (): int -> ( return 1 ) )
    def I = interface ( def m = fn (): int -> ( return 1 ) )
    recurse(n + 1)
)
```

```error
Recursion without a base case : f. If this is intended, wrap the expression with forever
```

## Empty block or if without else does not trigger endless recursion error

```babble
def f = fn (n: int): any? -> ( if n > 0 then ( recurse(n - 1) ) )
def g = fn (n: int): any? -> ( if n > 0 then ( () ) else ( recurse(n - 1) ) )
assert(f(3) == null and g(1) == null)
```

## while true containing a return exits cleanly

```babble
def f = fn (): int -> (
    while true then ( return 9 )
    0
)
assert(f() == 9)
```

## while true with break only inside an inner for loop or return inside nested fn has no exit

```babble
def f = fn () -> (
    while true then (
        def h = fn (): int -> ( return 1 )
        for x in list(1, 2) then ( break )
    )
)
```

```error
Loop without an exit : while true. If this is intended, wrap the expression with forever
```

## forever around multi-expression block does not acknowledge inner while true

```babble
def i = 0
forever ( i += 1 ; while true then ( i += 1 ) )
```

```error
Loop without an exit : while true. If this is intended, wrap the expression with forever
```

## Self tail call in catch without finally runs in constant stack space

```babble
def f = fn (n: int): int -> (
    if n == 0 then ( 0 )
    else (
        try ( throw exception("next") )
        catch e then ( recurse(n - 1) )
    )
)
assert(f(20000) == 0)
```

## Self call in catch with finally is not tail-optimized

```babble
def ticks = 0
def f = fn io (n: int): int -> (
    if n == 0 then ( 0 )
    else (
        try ( throw exception("next") )
        catch e then ( recurse(n - 1) )
        finally ( ticks += 1 )
    )
)
assert(f(5) == 0 and ticks == 5)
```

## Variadic and spread self-calls bypass static tail-call binding

```babble
def vr = fn (n: int, xs: int...): int -> ( if n == 0 then ( def c = 0 ; for x in xs then ( c += 1 ) ; c ) else ( recurse(n - 1, 1) ) )
def sp = fn (n: int, xs: list of int): int -> ( if n == 0 then ( xs.size() ) else ( sp(n - 1, ...list(1, 2, 3)) ) )
assert(vr(3, 1) == 1 and sp(3, list(1)) == 3)
```
