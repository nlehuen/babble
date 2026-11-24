# Union types (`A | B`), nullables (`T?`), parenthesized type annotations, and nullability casts/errors

References : BIP008 (unions, nullables, and §11 nullable casts)

```babble preamble
def Named = interface ( def name: fn (): string )
```

## Case 1

```babble
def fmt = fn (v: int | string | null): string -> (
    if v == null then ( "nil" )
    else if v is int then ( "int:" + v )
    else ( "str:" + v )
)
assert(fmt(42) == "int:42")
assert(fmt("ok") == "str:ok")
assert(fmt(null) == "nil")
```

## Case 2

```babble
def maybe: (int | string)? = 7
assert(maybe == 7)
maybe = "hi"
assert(maybe == "hi")
maybe = null
assert(maybe == null)
```

## Error : Type error : expected int, got null

```babble
def x: int = null
```

```error
Type error : expected int, got null
```

## Error : Not a scope : c

```babble
def C = class (def x: int) ()
def c: C? = null
print(c.x)
```

```error
Not a scope : c
```

## Late null reassignment widens an inferred non-null local to T?

```babble
def x = 10
assert(x == 10)
x = null
assert(x == null)
```

## T? is T | null, so null matches `is T?` and `as T?` for any T, but not `is T`

```babble
def Disk = class () ( def r = 1 )
def d: Disk? = null
def i: int? = null
assert(d is Named?)
assert((d as Named?) == null)
assert(not (d is Named))
assert((i as string?) == null)
```

## `as T` rejects null at runtime

```babble
def a: any? = null
def n = a as Named
```

```error
Type error : expected Named, got null
```

## `int?` and `string` share no value, so the cast is rejected statically

```babble
def i: int? = null
def s = i as string
```

```error
Type error : expected string, got int?
```
