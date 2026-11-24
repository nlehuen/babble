# Flow-sensitive type narrowing, `is`/`as` runtime checks, eviction on reassignment, and compound assignment

References : BIP008 (narrowing, `is`, `as`, reassignment join)

```babble preamble
def Point = class (def x: int) ()
def Pair = class (def x: int, def y: int) ()
def I1 = interface ( def a = fn (): int -> ( 1 ) )
def I2 = interface ( def b = fn (): int -> ( 2 ) )
def Both = class () implements I1, I2, java.lang.Runnable (
    def run = fn () -> ( )
)
```

## Case 1

```babble
def describe = fn (v: Point | Pair | string | null): string -> (
    if v is Pair then ( "pair:" + (v.x + v.y) )
    else if v is Point then ( "pt:" + v.x )
    else if v is string then ( "str:" + v )
    else ( "null" )
)
assert(describe(Point(3)) == "pt:3")
assert(describe(Pair(4, 5)) == "pair:9")
assert(describe("ok") == "str:ok")
assert(describe(null) == "null")
```

## Case 2

```babble
def checkAnd = fn (p: any?): bool -> ( p is Point and p.x > 0 )
def checkOr = fn (p: any?): bool -> ( not (p is Point) or p.x > 0 )
assert(checkAnd(Point(2)))
assert(not checkAnd(Point(-1)))
assert(not checkAnd(null))
assert(checkOr(Point(2)))
assert(not checkOr(Point(-1)))
assert(checkOr("x"))
```

## Case 3

```babble
def getX = fn (p: any?): int -> (
    if not (p is Point) then ( return -1 )
    p.x
)
assert(getX(Point(9)) == 9)
assert(getX("nope") == -1)
```

## Case 4

```babble
def unwrap = fn (p: Point?): int -> (
    if p == null then ( return 0 )
    p.x
)
assert(unwrap(Point(7)) == 7)
assert(unwrap(null) == 0)
```

## Error : Not a scope : p

```babble
def bad = fn (p: any) -> (
    if not (p is Point) then ( p.x ) else ( 0 )
)
```

```error
Not a scope : p
```

## Narrowing is preserved across unrelated var mutations and evicted on while-loop reassignment

```babble
def testWhileEvict = fn (p: Point?): int -> (
    def cur = p
    def n = 0
    if cur != null then (
        def f = fn (): int -> ( n = n + 1 ; n )
        f()
        assert(cur.x == 3)
        while n < 2 then (
            cur = null
            n = n + 1
        )
    )
    n
)
assert(testWhileEvict(Point(3)) == 2)
```

## Narrowing is killed after an `if` branch that reassigns the narrowed variable

```babble
def testBranchKill = fn (p: Point?, flag: bool): int -> (
    def cur = p
    if cur != null then (
        if flag then ( cur = null ) else ( cur.x )
        if cur != null then ( cur.x ) else ( -1 )
    ) else (
        -2
    )
)
assert(testBranchKill(Point(5), true) == -1)
assert(testBranchKill(Point(5), false) == 5)
assert(testBranchKill(null, false) == -2)
```

## Error : Not a scope : cur

```babble
def bad = fn (p: Point?) -> (
    def cur = p
    def n = 0
    if cur != null then (
        while n < 1 then (
            cur = null
            n = n + 1
        )
        cur.x
    ) else (
        0
    )
)
```

```error
Not a scope : cur
```

## Case 9

```babble
def checkIfaces = fn (x: I1, y: java.lang.Runnable, u: int | string, d: any) -> (
    assert(x is I2)
    assert(x is java.lang.Runnable)
    assert(y is I1)
    assert(not (y is java.lang.StringBuilder))
    assert(u is int)
    assert(d is string)
    assert(d is any)
)
def obj = Both()
checkIfaces(obj, obj, 42, "hi")
```

## Case 10

```babble
def i1: I1 = Both()
def r1: I2 = i1 as I2
def r2: java.lang.Runnable = i1 as java.lang.Runnable
def r3: I1 = r2 as I1
def r4: double = 3 as double
def u: int | string = 5
def r5: int = u as int
assert(r1.b() == 2)
assert(r3.a() == 1)
assert(r4 == 3.0)
assert(r5 == 5)
```

## Error : Type error : expected int, got double

```babble
def bad = 3.5 as int
```

```error
Type error : expected int, got double
```

## Error : Type error : expected int, got null

```babble
def bad = null as int
```

```error
Type error : expected int, got null
```

## Error : Type error : expected string, got java.lang.Runnable

```babble
def bad = fn (x: java.lang.Runnable): java.lang.String -> ( x as java.lang.String )
```

```error
Type error : expected string, got java.lang.Runnable
```

## Case 14

```babble
def Counter = class () (
    def v: int = 1
    def inc = fn mut (d: int): int -> ( this.v += d ; this.v )
)
def obj = object ( def n = 2 )
obj.n += 3
def x = 1
x += 2.5
assert(Counter().inc(4) == 5)
assert(obj.n == 5)
assert(x == 3.5)
```

## Case 15

```babble
def y = 1
y = null
def z = null
z = "ok"
def f = fn (a: int): int -> ( a + 1 )
f = fn (a: int): int -> ( a + 2 )
assert(y == null)
assert(z == "ok")
assert(f(10) == 12)
```
