# Records : structural equality, hashCode, toString, read-only fields, and invalid modifier error

References : BIP002 (value classes), BIP012-D6 (`record` keyword)

```babble
;; babble preamble
def Point = record (x : int, y : double, flag : bool = true) ()
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
