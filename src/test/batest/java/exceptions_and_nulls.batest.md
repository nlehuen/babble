# Unwrapped Java exceptions and nullable returns on Java generic boundaries

References : BIP009-D7 (`JavaTypes` invocation unwrapping), BIP011 (Java nullability)

## Error : java.lang.NumberFormatException : For input string: "not_an_int"

```babble
def n = java.lang.Integer.parseInt("not_an_int")
```

```error
java.lang.NumberFormatException : For input string: "not_an_int"
```

## Error : java.lang.IndexOutOfBoundsException : Index 0 out of bounds for length 0

```babble
def xs: java.util.List of int = java.util.ArrayList()
def first = xs.get(0)
```

```error
java.lang.IndexOutOfBoundsException : Index 0 out of bounds for length 0
```

## Error : java.lang.ArithmeticException : / by zero

```babble
def q = java.lang.Math.floorDiv(1, 0)
```

```error
java.lang.ArithmeticException : / by zero
```

## Error : java.lang.IllegalArgumentException : Illegal Capacity: -1

```babble
def xs: java.util.List of int = java.util.ArrayList(-1)
```

```error
java.lang.IllegalArgumentException : Illegal Capacity: -1
```

## Case 5

```babble
def m: java.util.Map of (string, int) = java.util.HashMap()
m.put("a", 10)
def v1: int? = m.get("a")
def v2: int? = m.get("missing")
assert(v1 == 10)
assert(v2 == null)
```
