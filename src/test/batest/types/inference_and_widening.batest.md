# Type inference, numeric int -> double widening across locals/fields/nullables, and call-site parameter infe...

References : BIP008 (D6 reassignment widening, P7 call-site parameter inference)

## Reassignment to double widens the local across its entire scope (BIP008-D6)

```babble
var x = 1
def asInt = x + 2
x = 2.5
def asDouble = x + 1
assert(asInt == 3.0)
assert(string(classof(asInt)) == "java.lang.Double")
assert(asDouble == 3.5)
assert(string(classof(x)) == "java.lang.Double")
```

## Case 2

```babble
var x: int? = 3
def y: double? = x
def z: double? = 4
assert(x == 3)
assert(y == 3.0)
assert(z == 4.0)
assert(string(classof(y)) == "java.lang.Double")
```

## Error : Type error : expected int, got double

```babble
def Box = class (var x: int) ()
def b = Box(1)
b.x = 2.5
```

```error
Type error : expected int, got double
```

## Case 4

```babble
def transform = fn (n, func: fn (k: int): double) -> ( func(n) )
def res = transform(5, fn (k) -> ( k * 2.5 ))
assert(res == 12.5)
assert(string(classof(res)) == "java.lang.Double")
```

## Error : Missing type : x

```babble
def unused = fn (x) -> ( x + 1 )
```

```error
Missing type : x
```
