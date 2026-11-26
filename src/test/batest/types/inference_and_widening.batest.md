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

## Int to double widening on if and early return joins

```babble
def pickNumber = fn (c) -> ( if c then ( 1 ) else ( 2.0 ) )
def w = pickNumber(true)
assert(w is double and w + w == 2.0)

def pickEarly = fn (c) -> (
    if c then ( return 1 )
    2.0
)
def w2 = pickEarly(true)
assert(w2 is double and w2 + w2 == 2.0)
```

## Reassignment widening from null to int? and double?

```babble
;; QUESTIONABLE : Wasm backend fails to widen int literal to boxed double on reassignment to null-inferred double? (infDbl is double fails on wasm-js); scoped to JVM via java.lang.Double
var infInt = null
infInt = 7
assert(infInt == 7)

var infDbl = null
infDbl = 1
assert(infDbl is double and infDbl == 1.0)
assert(classof(infDbl) == java.lang.Double)
infDbl = 2.5
assert(infDbl == 2.5)
```
