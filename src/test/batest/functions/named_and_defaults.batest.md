# Default parameter evaluation (including dependent defaults), named arguments, and function-type arity subty...

References : BIP021 (optional parameters and named arguments)

## Case 1

```babble
def fmt = fn (a : int, b : int = 10, c : int = a + b) : string -> (
    "" + a + ":" + b + ":" + c
)
assert(fmt(1) == "1:10:11")
assert(fmt(1, 2) == "1:2:3")
assert(fmt(1, 2, 3) == "1:2:3")
assert(fmt(c: 99, a: 2) == "2:10:99")
assert(fmt(5, c: 20) == "5:10:20")
```

## Function with defaulted parameters passed through a higher-order function parameter

```babble
def invoke = fn (func : fn (x : int, y : int, z : int) : string) : string -> (
    func(10, 20, 30)
)
def fmt = fn (a : int = 1, b : int = 2, c : int = 3) : string -> ( "" + a + "-" + b + "-" + c )
assert(invoke(fmt) == "10-20-30")
```

## Error : No such parameter : z

```babble
def f = fn (a : int, b : int = 1) : int -> ( a + b )
print(f(1, z: 2))
```

```error
No such parameter : z
```

## Error : Parameter given twice : a

```babble
def f = fn (a : int, b : int = 1, c : int = 2) : int -> ( a + b + c )
print(f(1, a: 2))
```

```error
Parameter given twice : a
```

## Error : Parameter given twice : a (2)

```babble
def f = fn (a : int, b : int = 10) : int -> ( a + b )
print(f(a: 1, a: 5))
```

```error
Parameter given twice : a
```

## Error : Missing parameter : b

```babble
def f = fn (a : int, b : int, c : int = 5) : int -> ( a + b + c )
print(f(a: 1))
```

```error
Missing parameter : b
```

## Function with defaulted trailing parameter satisfies shorter function type

```babble
def f = fn (a : int, b : int = a * 2) : int -> ( a + b )
def indirect : fn (a : int) : int = f
assert(indirect(4) == 12)
```

## Error : Type error : expected (a : int) : int, got (a : int, b : int) : int

```babble
def f = fn (a : int, b : int) : int -> ( a + b )
def indirect : fn (a : int) : int = f
```

```error
Type error : expected (a : int) : int, got (a : int, b : int) : int
```
