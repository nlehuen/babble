# Java instance/static fields, reflective Java arrays, and field assignment errors

References : BIP001, BIP008 (static typing on Java fields)

## Case 1

```babble
def pt = java.awt.Point(3, 4)
def origX = pt.x
pt.x = 10
pt.y += 6
assert(origX == 3)
assert(pt.x == 10)
assert(pt.y == 10)
assert(java.lang.Integer.MIN_VALUE == -2147483648)
```

## Case 2

```babble
def Array = java.lang.reflect.Array
def arr = Array.newInstance(java.lang.Integer.TYPE, 3)
Array.setInt(arr, 0, 10)
Array.setInt(arr, 1, 20)
Array.setInt(arr, 2, 30)
assert(Array.getLength(arr) == 3)
assert(Array.getInt(arr, 0) + Array.getInt(arr, 2) == 40)
```

## Error : Cannot assign final field : java.lang.Integer.MAX_VALUE

```babble
java.lang.Integer.MAX_VALUE = 0
```

```error
Cannot assign final field : java.lang.Integer.MAX_VALUE
```

## Error : Type error : expected int, got string

```babble
def pt = java.awt.Point(1, 2)
pt.x = "nope"
```

```error
Type error : expected int, got string
```
