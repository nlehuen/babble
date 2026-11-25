# Block scoping, shadowing, variable hoisting, forward reference errors, and block expressions

References : BlockScopes, Resolver

## Case 1

```babble
def x = 10
def y = (
    def x = 20
    def z = x + 5
    z
)
assert(x == 10)
assert(y == 25)
```

## Error : No such name : inner

```babble
def y = (
    def inner = 42
    inner
)
print(inner)
```

```error
No such name : inner
```

## Error : Name already defined : x

```babble
def x = 1
def x = 2
```

```error
Name already defined : x
```

## Variable read before definition in same block (hoisted slot)

```babble
def f = fn volatile () -> ( a )
def g = fn () -> (
    def x = a
    def a = 5
    x
)
print(g())
```

```error
Name used before definition : a
```

## Assignment before definition in same block

```babble
def f = fn () -> (
    a = 10
    var a = 5
)
print(f())
```

```error
Name used before definition : a
```

## Read captured variable from closure before def runs

```babble
def maker = fn () -> (
    def readA = fn () : int -> ( a )
    def r = readA()
    def a = 1
    r
)
print(maker())
```

```error
Name used before definition : a
```

## Empty, single-expression, and semicolon-separated blocks

```babble
def a : any? = ( ; ; )
def b = ( 7 )
def c = ( discard(1) ; discard(2) ; 3 )
assert(a == null)
assert(b == 7)
assert(c == 3)
```

## Empty unit script exercises zero-unit slot convergence

```babble
()
```

## Function type parameter validation : nullable rest and optional before keyword-only

```babble
def f: fn (a: int = ..., ..., b: int): int = fn (a: int = 1, ..., b: int): int -> ( a + b )
def g: fn (xs: int?...): int = fn (xs: int?...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
assert(f(b: 4) == 5 and g(1, null) == 2)
```

## Function type rejects two variadic parameters

```babble
def f: fn (a: int..., b: int...): int = fn (a: int): int -> ( a )
```

```error
More than one variadic parameter : b
```

## Function type rejects variadic parameter with keyword-only separator

```babble
def f: fn (..., a: int...): int = fn (a: int): int -> ( a )
```

```error
More than one variadic parameter : a
```

## Function type rejects variadic parameter with default

```babble
def f: fn (a: int... = ...): int = fn (a: int): int -> ( a )
```

```error
Variadic parameter with a default : a
```

## Function type rejects required parameter after optional positional parameter

```babble
def f: fn (a: int = ..., b: int): int = fn (a: int, b: int): int -> ( a + b )
```

```error
Required parameter after an optional one : b
```

## Axiom declaration inside a while test is rejected

```babble
while axiom java.lang.Math.abs: fn pure then ( () )
```

```error
Axiom declaration in a while test
```

## Assignment to a literal expression is rejected

```babble
1 = 2
```

```error
Cannot assign to expression
```

## Assignment through safe navigation is rejected

```babble
def C = class (def x: int) ()
def c: C? = C(1)
c?.x = 2
```

```error
Cannot assign to expression
```

## Loop continue inside a finally block is allowed

```babble
var sum = 0
try ( () ) finally (
    var i = 0
    while i < 4 then (
        i += 1
        if i == 2 then ( continue )
        sum += i
    )
)
assert(sum == 8)
```

## Shadowing import calls the local function instead of static module loading

```babble
def import = fn (name: string): string -> ( "local:" + name )
assert(import("nowhere.ba") == "local:nowhere.ba")
```

## Unannotated function called then escaping wakes parameter readers and requires annotation

```babble
def f = fn (x) -> ( x + 1 )
def a = f(1)
def g = f
```

```error
Missing type : x
```

## Unannotated function using recurse as value requires parameter annotation

```babble
def f = fn (x) -> ( def self = recurse ; x + 1 )
f(1)
```

```error
Missing type : x
```

## Multi-variable narrowing eviction and branch joins preserve surviving narrowings

```babble
def f = fn (b: bool): int -> (
  def a: int? = 1 ; var x: int? = 2 ; var y: int? = 3 ; var z = 0 ; z = 1
  if a != null and x != null and y != null then (
    def r1 = fn (): int -> (a + 1)
    if b then (x = null) else (y = null)
    def k = a + r1()
    x = null ; y = null
    def r2 = fn (): int -> (z)
    k + r2()
  ) else (0)
)
assert(f(true) == 4 and f(false) == 4)
```
