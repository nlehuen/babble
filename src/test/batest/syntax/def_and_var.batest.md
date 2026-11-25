# Immutable def and mutable var across all declaration sites

References : `BIP034-P1` (`BIP034-D1` through `BIP034-D11`), `BlockScopes`, `TypeChecks`, `ClassShape`, `Slot`, `Members`.

## Local and top-level var mutation and def immutability

```babble
def c = 10
var acc = c
acc += 5
assert(c == 10 and acc == 15)
```

## var accepted as memberName in selector and named argument (not a syntax error)

```babble
"hi".var
```

```error
No such name in java.lang.String : var
```

## var accepted as named argument label (not a syntax error)

```babble
def f = fn (x: int): int -> ( x )
f(var: 1)
```

```error
No such parameter : var
```

## Mutable fn parameter (var) is local-only and matches fn (int) type

```babble
def bump = fn (var n: int, k: int): int -> ( n += k ; n )
def f: fn (int, int): int = bump
def g = fn (var mut xs: list of int): int -> ( xs.add(9) ; xs = list(1, 2) ; xs.size() )
def buf = list(0)
assert(bump(3, 4) == 7 and f(10, 5) == 15 and g(buf) == 2 and buf.size() == 2)
```

## var f = fn ... in class and object is a mutable field, not a method

```babble
def C = class () (
    var cb: fn (int): int = fn (x: int): int -> ( x + 1 )
    def run = fn (v: int): int -> ( this.cb(v) )
)
def inst = C()
assert(inst.run(4) == 5)
inst.cb = fn (x: int): int -> ( x * 10 )
assert(inst.run(4) == 40)
def o = object ( var f = fn (x: int): int -> ( x + 2 ) )
assert(o.f(3) == 5)
o.f = fn (x: int): int -> ( x * 3 )
assert(o.f(3) == 9)
```

## Class header def vs var fields and private var

```babble
def Box = class (def id: int, var count: int, private var hidden: int = 0) (
    def bump = fn mut (d: int): int -> ( this.count += d ; this.hidden += d ; this.hidden )
)
def b = Box(1, 10)
b.count = 20
assert(b.id == 1 and b.count == 20 and b.bump(5) == 5 and b.count == 25)
```

## Static def and private static var encapsulation

```babble
def C = class () () static (
    def tag = "C"
    private var total = 0
    def add = fn io (n: int): int -> ( this.total += n ; this.total )
    def get = fn volatile (): int -> ( this.total )
)
assert(C.tag == "C" and C.add(3) == 3 and C.get() == 3)
```

## Interface def requirement satisfied by both def and var class fields

```babble
def HasX = interface ( def x: int )
def ConstX = class (def x: int) implements HasX ()
def MutX = class (var x: int) implements HasX ()
def readX = fn (h: HasX): int -> ( h.x )
def m = MutX(3)
m.x = 9
assert(readX(ConstX(2)) == 2 and readX(m) == 9)
```

## Error : Cannot assign to def : x

```babble
def x = 1
x = 2
```

```error
Cannot assign to def : x
```

## Error : Cannot assign to parameter : x

```babble
def f = fn (x: int): int -> ( x = 2 ; x )
f(1)
```

```error
Cannot assign to parameter : x
```

## Error : Cannot assign to bare class parameter : x

```babble
def C = class (x: int) ( def y = ( x = 2 ; x ) )
C(1)
```

```error
Cannot assign to parameter : x
```

## Error : Cannot assign to def member on class

```babble
def C = class (def x: int) ()
def c = C(1)
c.x = 2
```

```error
Cannot assign to def member : x
```

## Error : Cannot assign to def member via this inside class body

```babble
def C = class (def x: int) (
    def m = fn mut () -> ( this.x = 2 )
)
C(1).m()
```

```error
Cannot assign to def member : x
```

## Error : Cannot assign to def member dynamically through any receiver

```babble
def C = class (def x: int) ()
def dyn = fn (o: any) -> ( o )
def c = dyn(C(1))
(c as C).x = 2
```

```error
Cannot assign to def member : x
```

## Error : Cannot assign to static def member

```babble
def C = class () () static ( def s = 1 )
C.s = 2
```

```error
Cannot assign to def member : s
```

## Error : Cannot assign to def member on object literal

```babble
def o = object ( def v = 1 )
o.v = 2
```

```error
Cannot assign to def member : v
```

## Error : Declaration requires an initializer on def

```babble
def x: int?
```

```error
Declaration requires an initializer : x
```

## Error : Declaration requires an initializer on var

```babble
var x: int?
```

```error
Declaration requires an initializer : x
```

## Error : Cannot export a var

```babble
export var count = 0
```

```error
Cannot export a var : count
```

## Error : Value class members cannot be var (header)

```babble
def P = record (var x: int) ()
```

```error
Value class members cannot be var : x
```

## Error : Value class members cannot be var (body)

```babble
def P = record (x: int) ( var y = 1 )
```

```error
Value class members cannot be var : y
```

## Error : Interface members cannot be var

```babble
def I = interface ( var x: int )
```

```error
Interface members cannot be var : x
```

## Error : Public static members cannot be var

```babble
def C = class () () static ( var s = 1 )
```

```error
Public static members cannot be var : s
```

## Error : Type parameters only apply to def

```babble
var id of T = fn (x: T): T -> ( x )
```

```error
Type parameters only apply to def : id
```

## Error : Type alias must be declared with def

```babble
var Num = type int | double
```

```error
Type alias must be declared with def : Num
```

## REPL session : earlier def cannot be reassigned, earlier var can

```babble
;; babble session
def x = 1
var y = 10
```

```babble
;; babble session
y = y + x
assert(y == 11)
```

```babble
;; babble session
x = 2
```

```error
Cannot assign to def : x
```
