# Operator typing matrix and flow-sensitive type narrowing

References : `BIP008` (typing & narrowing), `analysis.OperatorTypes`, `analysis.Narrowing`, `analysis.TypeChecker` (`visitNegate`, `visitNonNullAssertion`).

```babble preamble
def Base = interface (def id : fn () : int)
def Sub = interface extends Base (def sub : fn () : int)
def Item = record (v : int) implements Sub (
    def id = fn () : int -> ( this.v )
    def sub = fn () : int -> ( this.v * 2 )
)
def PlusOnly = record (v : int) (
    def plus = fn (n : int) : int -> ( this.v + n )
)
def Plain = record (v : int) ()
```

## Recursive return-type seeding across addition, division, and numeric union operands

```babble
def f1 = fn (n : int) -> ( if n <= 0 then ( 1 ) else ( f1(n - 1) + 2 ) )
def f2 = fn (n : int) -> ( if n <= 0 then ( 1 ) else ( 2 + f2(n - 1) ) )
def f3 = fn (n : int) -> ( if n <= 0 then ( 8.0 ) else ( f3(n - 1) / 2 ) )
def f4 = fn (n : int, u : int | double) -> ( if n <= 0 then ( 1.0 ) else ( f4(n - 1, u) + u ) )
assert(f1(3) == 7 and f2(3) == 7 and f3(2) == 2.0 and f4(2, 1.5) == 4.0)
```

## Java Number arithmetic, division, negation, and builtin functions

```babble
def n : java.lang.Number = java.math.BigDecimal.valueOf(6)!
assert(n + 2 == 8.0 and 2 + n == 8.0 and n / 2 == 3.0 and -n == -6.0)
def Box = class (def v : int) ()
def useBuiltins = fn () -> ( def b = Box(1) ; discard(freeze(move(b))) )
useBuiltins()
```

## Non-Number Java object rejected in arithmetic

```babble
def sb = java.lang.StringBuilder("x")
def bad = sb + 1
```

```error
Not a number : sb
```

## Union of instances with missing plus method fails binary addition

```babble
def f = fn (x : PlusOnly | Plain) -> ( x + 1 )
```

```error
No such member : plus
```

## Union of instances with missing neg method fails unary negation

```babble
def f = fn (x : PlusOnly | Plain) -> ( -x )
```

```error
No such member : neg
```

## Comparable Java values and bottom-type comparison

```babble
def a = java.math.BigInteger.valueOf(3)
def b = java.math.BigInteger.valueOf(5)
def dead = fn io (flag : bool) : bool -> (
    if flag then ( (throw java.lang.RuntimeException("x")) < 1 ) else ( a < b )
)
assert(dead(false))
```

## Bottom-type operands in unary negation and non-null assertion

```babble
def dead2 = fn io (flag : bool) : int -> (
    if flag then ( -(throw java.lang.RuntimeException("x")) ) else ( 1 )
)
def dead3 = fn io (flag : bool) : int -> (
    if flag then ( (throw java.lang.RuntimeException("x"))! ) else ( 2 )
)
assert(dead2(false) == 1 and dead3(false) == 2)
```

## Mismatched Java Comparable classes are not comparable

```babble
def f = fn (a : java.math.BigInteger, b : string) -> ( a < b )
```

```error
Not comparable : a and b
```

## Non-Comparable Java objects are not comparable

```babble
def f = fn (a : java.lang.Object, b : java.lang.Object) -> ( a < b )
```

```error
Not comparable : a and b
```

## Narrowing prefers more specific type across conjunctive is checks

```babble
def f = fn (x : any) : int -> (
    def a = if x is Sub and x is Base then ( x.sub() ) else ( 0 )
    def b = if x is Base and x is Sub then ( x.sub() ) else ( 0 )
    def c = if x is Base and x is Base then ( x.id() ) else ( 0 )
    a + b + c
)
assert(f(Item(3)) == 15)
```

## Narrowing parenthesized expressions, parenthesized null, and always-null variables

```babble
def f = fn (x : int?) : int -> (
    def n : null = null
    def r1 = if (x) != (null) then ( x + 1 ) else ( 0 )
    def r2 = if (def t = 1 ; (x) is int) then ( 1 ) else ( 0 )
    def r3 = if n == null then ( 1 ) else ( 0 )
    def r4 = if n then ( 0 ) else ( 1 )
    r1 + r2 + r3 + r4
)
assert(f(4) == 8)
```

## Narrowing redundant supertype check vs subtype refinement

```babble
def f = fn (y : Base, s : Sub) : int -> (
    def r5 = if s is Base then ( s.id() ) else ( 0 )
    def r6 = if y is Sub then ( y.sub() ) else ( y.id() )
    r5 + r6
)
assert(f(Item(2), Item(3)) == 7)
```

## Narrowing for-loop variables, catch variables, and assigned-local skipping in nested objects

```babble
def sum = 0
for x in list of int?(1, null, 3) then (
    if x != null then ( sum += x )
)
def caught = try ( throw Item(5) ) catch e then (
    if e is Item then ( e.v ) else ( 0 )
)
def done = false
while not done then (
    def o = object ( def k = 1 ; this.k = 2 )
    done = true
)
assert(sum == 4 and caught == 5)
```

## Reassigned local is not narrowable across closure boundary

```babble
def x : int? = 5
def bad = fn volatile () : int -> ( if x != null then ( x + 1 ) else ( 0 ) )
x = null
```

```error
Not a number : x
```
