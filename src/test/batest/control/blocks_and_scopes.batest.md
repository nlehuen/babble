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
    def a = 5
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
