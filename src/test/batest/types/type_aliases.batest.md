# Type aliases : `def Name = type T`, generic aliases, module exports, nullability, and errors

References : BIP023 (D1-D7 transparent type aliases, `type` memberName)

```babble module=shapes.ba
export def Shape = interface ( def area: fn (): double )
export def Named = interface ( def name: fn (): string )
export def ShapeWithName = type Shape & Named
export def Box of T = type list of T
export def Tile = class () implements Shape, Named (
    def area = fn (): double -> ( 1.5 )
    def name = fn (): string -> ( "tile" )
)
```

```babble preamble
def Shape = interface ( def area: fn (): double )
def Named = interface ( def name: fn (): string )
def Label = interface ( def text: fn (): string )
def ShapeWithText = type Shape & (Named | Label)
def Tile = class () implements Shape, Named (
    def area = fn (): double -> ( 1.5 )
    def name = fn (): string -> ( "tile" )
)
def Sign = class () implements Shape, Label (
    def area = fn (): double -> ( 2.0 )
    def text = fn (): string -> ( "sign" )
)
def Disk = class () implements Shape ( def area = fn (): double -> ( 3.0 ) )
```

## Case 1

```babble
def describe = fn (x: ShapeWithText): string -> ( if x is Named then ( x.name() ) else ( x.text() ) )
assert(describe(Tile()) == "tile")
assert(describe(Sign()) == "sign")
```

## Case 2

```babble
def maybe: ShapeWithText? = null
def a: any = Sign()
assert(maybe == null)
assert(a is ShapeWithText)
assert((a as ShapeWithText).area() == 2.0)
```

## `is T?` accepts null while `is T` does not

```babble
def maybe: ShapeWithText? = null
assert(not (maybe is ShapeWithText))
assert(maybe is ShapeWithText?)
maybe = Tile()
assert(maybe is ShapeWithText)
assert(maybe is ShapeWithText?)
```

## Case 4

```babble
def d: Disk? = null
assert(d is Shape?)
assert(not (d is Shape))
assert(d is Named?)
assert(d is Label?)
d = Disk()
assert(d is Shape?)
assert(d is Shape)
assert(not (d is Named?))
assert(not (d is Label?))
```

## Case 5

```babble
def Pair of (A, B) = type entry of (A, B)
def p: Pair of (string, int) = entry("a", 1)
assert(p.getKey() == "a")
assert(p.getValue() + 1 == 2)
```

## Case 6

```babble
def Num = type int | double
def Again = type Num
def twice = fn (n: Again): Num -> ( if n is int then ( n * 2 ) else ( n * 2.0 ) )
assert(twice(2) == 4)
assert(twice(1.5) == 3.0)
```

## Case 7

```babble
def shapes = import("shapes.ba")
def f = fn (x: shapes.ShapeWithName): string -> ( x.name() + ":" + x.area() )
def b: shapes.Box of int = list.of(1, 2)
assert(f(shapes.Tile()) == "tile:1.5")
assert(b.size() == 2)
```

## Error : A type alias is not a value : T

```babble
def T = type Shape & Named
println(T)
```

```error
A type alias is not a value : T
```

## Error : A type alias is the value of a def : type int

```babble
def f = fn () -> ( type int )
```

```error
A type alias is the value of a def : type int
```

## Error : A type alias takes no annotation : T

```babble
def T: int = type int
```

```error
A type alias takes no annotation : T
```

## Error : Recursive type alias : L

```babble
def L = type (int | L)?
```

```error
Recursive type alias : L
```

## Error : Missing type arguments : Box

```babble
def Box of A = type list of A
def x: Box = list.of(1)
```

```error
Missing type arguments : Box
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
def Box of A = type list of A
def x: Box of (int, string) = list.of(1)
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Wrong number of type arguments : expected 0, got 1

```babble
def T = type int
def x: T of int = 1
```

```error
Wrong number of type arguments : expected 0, got 1
```

## `type` remains a valid member name after `.`

```babble
assert(string(java.lang.ProcessBuilder.Redirect.INHERIT.type()) == "INHERIT")
```
