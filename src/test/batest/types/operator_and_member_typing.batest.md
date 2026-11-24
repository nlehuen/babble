# Operator and member typing : unary negation, integer literal ranges, binary operators

and operator methods on classes/unions/Java `Number`, compound assignment, safe selector `?.`,
and member/static assignment checks.
References : BIP002 (operator methods), BIP008 (static typing), BIP011 (core types), TypeChecker

```babble preamble
def Vec = class (def x : int) (
    def plus = fn (o : Vec) : Vec -> ( Vec(this.x + o.x) )
    def minus = fn (o : Vec) : Vec -> ( Vec(this.x - o.x) )
    def times = fn (k : int) : Vec -> ( Vec(this.x * k) )
    def div = fn (k : int) : Vec -> ( Vec(this.x + k) )
    def neg = fn () : Vec -> ( Vec(-(this.x)) )
)
def Box = class (def v : int) (
    def get = fn () : int -> ( this.v )
)
```

## Case 1

```babble
def minI = -2147483648
assert(minI == -2147483648)
def u1 : int | double = 5
def u2 : int | double = 2.5
assert(-u1 == -5 and -u2 == -2.5)
def u3 : int | double | Vec = Vec(3)
def r = -u3
assert(r is Vec and r.x == -3)
```

## Error : Integer out of range : -2147483649

```babble
-2147483649
```

```error
Integer out of range : -2147483649
```

## Error : Integer out of range : 9999999999

```babble
9999999999
```

```error
Integer out of range : 9999999999
```

## Error : Integer out of range : -9999999999999

```babble
-9999999999999
```

```error
Integer out of range : -9999999999999
```

## Error : Not a number : bad

```babble
def bad : int? = 1
-bad
```

```error
Not a number : bad
```

## Error : Not a number : bad (2)

```babble
def bad : int | string = 1
-bad
```

```error
Not a number : bad
```

## Case 7

```babble
def v1 = Vec(3)
def v2 = Vec(2)
def u : Vec | Vec = Vec(4)
assert((v1 + v2).x == 5 and (v1 - v2).x == 1)
assert((v1 * 3).x == 9 and (v1 / 1).x == 4 and (-v1).x == -3)
assert((u + Vec(5)).x == 9 and (-u).x == -4)
```

## Case 8

```babble
def Box2 = class (def x : int) (
    def plus = fn (o : Vec) : Vec -> ( Vec(this.x + o.x + 10) )
)
def pick = fn (flag : bool) : Vec | Box2 -> ( if flag then ( Vec(3) ) else ( Box2(1) ) )
assert((pick(false) + Vec(2)).x == 13)
assert((pick(true) + Vec(2)).x == 5)
```

## Case 9

```babble
def WidenOp = class () (
    def plus = fn (n) -> ( n + 1 )
)
def w = WidenOp()
assert(w + 1 == 2.0 and w + 2.5 == 3.5)
```

## Case 10

```babble
def i1 = java.lang.Integer.valueOf(3)
def i2 = java.lang.Integer.valueOf(5)
assert(i1 < i2)
assert(i1 + i2 == 8)
assert(not (1 == true))
```

## Error : Bad().plus is not callable

```babble
def Bad = class () ( def plus = 42 )
Bad() + 1
```

```error
Bad().plus is not callable
```

## Error : Too many parameters : 0 declared, 1 given

```babble
def Bad = class () ( def plus = fn () : int -> ( 1 ) )
Bad() + 1
```

```error
Too many parameters : 0 declared, 1 given
```

## Error : Missing parameter : b

```babble
def Bad = class () ( def plus = fn (a : int, b : int) : int -> ( a + b ) )
Bad() + 1
```

```error
Missing parameter : b
```

## Error : Not a number : "a"

```babble
def bad = "a" - 1
```

```error
Not a number : "a"
```

## Error : Not comparable : 1 and "a"

```babble
def bad = 1 < "a"
```

```error
Not comparable : 1 and "a"
```

## Error : Not comparable : A() and B()

```babble
def A = class () ()
def B = class () ()
A() < B()
```

```error
Not comparable : A() and B()
```

## Error : Not comparable : f1 and f2

```babble
def f1 = fn () -> ( 1 )
def f2 = fn () -> ( 2 )
f1 < f2
```

```error
Not comparable : f1 and f2
```

## Case 18

```babble
def s = "hi"
s += "!"
def addParam = fn (p : int) : int -> ( p += 4 ; p )
def Counter = class (def x : int) (
    def bump = fn mut (d : int) : int -> (
        this.x = 5
        this.x += d
        this.x
    )
)
assert(s == "hi!" and addParam(6) == 10 and Counter(1).bump(2) == 7)
```

## Case 19

```babble
def Comp = class (def i : int, def d : double, def s : string, def u : int | double) (
    def run = fn mut () -> (
        this.i += 2 ; this.i -= 1 ; this.d /= 2 ; this.s += "!" ; this.u += 1.5
    )
)
def c = Comp(5, 9.0, "hi", 2)
c.i += 3 ; c.u /= 2
assert(c.i == 8 and c.u == 1.0)
c.run()
assert(c.i == 9 and c.d == 4.5 and c.s == "hi!" and c.u == 2.5)
```

## Error : Not a number : b

```babble
def b = true
b += false
```

```error
Not a number : b
```

## Error : Not a number : s

```babble
def s = "hi"
s -= "i"
```

```error
Not a number : s
```

## Error : Value members are read-only : x

```babble
def Val = record (def x : int) ()
def v = Val(1)
v.x += 1
```

```error
Value members are read-only : x
```

## Case 23

```babble
def readSafe = fn (b : Box?) : int? -> ( b?.v )
def callSafe = fn (b : Box?) : int? -> ( b?.get() )
def nilBox : Box? = null
assert(readSafe(null) == null and readSafe(Box(4)) == 4)
assert(callSafe(null) == null and callSafe(Box(5)) == 5)
assert(nilBox?.v == null)
```

## Error : Not nullable : x

```babble
def x = 5
x?.foo
```

```error
Not nullable : x
```

## Error : Not a scope : opt

```babble
def opt : Box? = Box(1)
opt.v
```

```error
Not a scope : opt
```

## Case 26

```babble
def GBox of T = class (def v : T) () static (
    def count = 0
)
def b = GBox(10)
b.v = 20
GBox.count = 5
assert(b.v == 20 and GBox.count == 5)
```

## Error : Not a scope : (5)

```babble
(5).x = 1
```

```error
Not a scope : (5)
```

## Error : Not a scope : 42

```babble
42.x = 1
```

```error
Not a scope : 42
```

## Error : No such member : missing

```babble
def c = Box(1)
c.missing = 1
```

```error
No such member : missing
```

## Error : No such member : missing (2)

```babble
def C = class () () static ( def s = 1 )
C.missing = 1
```

```error
No such member : missing
```

## Error : x is private

```babble
def C = class () ( private def x : int = 1 )
def c = C()
c.x = 2
```

```error
x is private
```

## Error : p is private

```babble
def Priv = class (private def p : int) ()
def b = Priv(1)
b.p = 2
```

```error
p is private
```

## Error : No such field in array of char : length (use size())

```babble
def arr = java.lang.String("abc").toCharArray()
arr.length = 5
```

```error
No such field in array of char : length (use size())
```

## Assigning to a field of a frozen value is rejected

```babble
def Cell = class (def v : int) ()
def fb : frozen Cell = freeze(Cell(1))
fb.v = 2
```

```error
Cannot assign to field of frozen value
```

## Reading and writing fields through a bounded TypeVar and a generic instance

```babble
def GCell of T = class (def v : T) ()
def setCell of (T : GCell of int) = fn (mut b : T, x : int) -> ( b.v = x )
def b = GCell of int (1)
setCell(b, 9)
assert(b.v == 9)
```

## Selecting a member on an unbounded TypeVar is rejected

```babble
def bad of T = fn (x : T) -> ( x.foo )
```

```error
Not a scope : x
```

## CoreCollection static member errors and sortedset/sortedmap/entry selection

```babble
def bad = sortedset.missing
```

```error
No such name in java.util.SortedSet : missing
```

## Error : No such name in java.util.List : missing

```babble
def bad = (list of int).missing
```

```error
No such name in java.util.List : missing
```

## Error : No such name in java.util.SortedMap : missing

```babble
def bad = sortedmap.missing
```

```error
No such name in java.util.SortedMap : missing
```

## Error : No such name in java.util.Map.Entry : missing

```babble
def bad = entry.missing
```

```error
No such name in java.util.Map.Entry : missing
```

## Interface conformance : incompatible override return type is rejected

```babble
def IFoo = interface ( def get : fn () : int )
def BadFoo = class () implements IFoo ( def get = fn () : string -> ( "no" ) )
```

```error
Type error : expected () : int, got () : string
```

## Inherited default method conformance mismatch against sibling required interface

```babble
def IBase = interface ( def get = fn () : any -> ( 1 ) )
def IReq = interface ( def get : fn () : int )
def BadSub = class () implements IBase, IReq ()
```

```error
Type error : expected () : int, got () : any
```

## Inherited member lookup across multi-level interface hierarchy and Java field selection

```babble
def IA = interface ( def a = fn () : int -> ( 1 ) )
def IB = interface extends IA ( def b = fn () : int -> ( 2 ) )
def Impl = class () implements IB ()
def p = java.awt.Point(3, 4)
assert(Impl().a() + Impl().b() + p.x + p.y == 10)
```
