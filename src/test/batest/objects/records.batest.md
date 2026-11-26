# Records : structural equality, hashCode, toString, read-only fields, and invalid modifier error

References : BIP002 (value classes), BIP012-D6 (`record` keyword)

```babble
;; babble preamble
def Point = record (x : int, y : double, flag : bool = true) ()
def AllPrimitives = record (
    b : byte, s : short, i : int, l : long, f : float, d : double, flag : bool, c : char, name : string
) (
    private def hidden = 99
    def sum = fn () : int -> ( this.i + this.hidden )
)
def mkAll = fn (f : float, d : double) : AllPrimitives -> (
    AllPrimitives(byte(1), short(2), 3, 4L, f, d, true, 'z', "ok")
)
```

## Case 1

```babble
def p1 = Point(3, 4.0)
def p2 = Point(3, 4.0)
def p3 = Point(3, 5.0)
assert(string(p1) == "Point(x: 3, y: 4.0, flag: true)")
assert(p1 == p2)
assert(not (p1 == p3))
assert(p1 != p3)
assert(not same(p1, p2))
```

## Case 2

```babble
def p1 = Point(3, 4.0)
def p2 = Point(3, 4.0)
assert(java.util.Objects.equals(p1, p2))
assert(java.util.Objects.hashCode(p1) == java.util.Objects.hashCode(p2))
```

## Case 3

```babble
def Pair = record (left : string, right : int) ()
def a = Pair("k", 1)
def b = Pair("k", 1)
assert(a == b)
assert(a.left == "k")
assert(a.right == 1)
```

## Error : Value members are read-only : x

```babble
def p = Point(1, 2.0)
p.x = 2
```

```error
Value members are read-only : x
```

## BIP012-D6 : only class and record exist, no modifier keywords before class

```babble
def Bad = final class (x : int) ()
```

```error
Syntax error : unexpected 'class'
```

## Record with all 8 primitives, private hidden field, and toString

```babble
def a = mkAll(0.0f / 0.0f, 0.0 / 0.0)
def exp = "AllPrimitives(b: 1, s: 2, i: 3, l: 4, f: NaN, d: NaN, flag: true, c: z, name: ok)"
assert(("" + a) == exp)
assert(java.util.Objects.toString(a) == exp)
assert(a.sum() == 102)
```

## Record floating-point equality : NaN equals NaN and -0.0 differs from 0.0

```babble
def a = mkAll(0.0f / 0.0f, 0.0 / 0.0)
def b = mkAll(0.0f / 0.0f, 0.0 / 0.0)
def nz = mkAll(0.0f, -0.0)
def pz = mkAll(0.0f, 0.0)
assert(a == b and java.util.Objects.equals(a, b))
assert(java.util.Objects.hashCode(a) == java.util.Objects.hashCode(b))
assert(nz != pz and not java.util.Objects.equals(nz, pz))
```

## Record cross-numeric equality on any field and custom toString override

```babble
def O = java.util.Objects
def Box = record (n : any) ()
def b1 = Box(1) ; def b2 = Box(1.0)
assert(b1 == b2 and O.equals(b1, b2) and O.hashCode(b1) == O.hashCode(b2))
def Named = record (x : int) (
    def toString = fn () : string -> ( "Named<" + this.x + ">" )
)
assert(("" + Named(7)) == "Named<7>" and O.toString(Named(7)) == "Named<7>")
```

## Closure-generated record classes have distinct nominal identity

```babble
def mk = fn (offset : int) -> (
    record (x : int) (
        def tag = fn () : int -> ( this.x + offset )
    )
)
def R1 = mk(1)
def R2 = mk(2)
assert(R1(5) != R2(5))
assert(not java.util.Objects.equals(R1(5), R2(5)))
assert(R1(5).tag() + R2(5).tag() == 13)
```
