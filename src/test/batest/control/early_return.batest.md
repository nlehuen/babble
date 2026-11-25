# Early return inside functions, loops, if branches, type widening, nullable inference, and top-level error

References : TypeChecker, Interpreter, HirBuilder

## Case 1

```babble
def findFirstEven = fn (n : int) : int -> (
    var i = 1
    while i <= n then (
        if i == 4 then (
            return i
        )
        i += 1
    )
    -1
)
assert(findFirstEven(10) == 4)
assert(findFirstEven(2) == -1)
```

## Early return widens int to double when matching explicit return type

```babble
def f = fn (flag : bool) : double -> (
    if flag then (
        return 3
    )
    4.5
)
assert(f(true) == 3.0)
assert(string(classof(f(true))) == "java.lang.Double")
assert(f(false) == 4.5)
```

## Case 3

```babble
def f = fn (x : int) -> (
    if x < 0 then (
        return null
    )
    x * 2
)
assert(f(-1) == null)
assert(f(5) == 10)
```

## Error : Type error : expected int, got string

```babble
def f = fn (x : int) : int -> (
    if x < 0 then (
        return "bad"
    )
    x
)
```

```error
Type error : expected int, got string
```

## Error : return outside of a function

```babble
return 1
```

```error
return outside of a function
```
