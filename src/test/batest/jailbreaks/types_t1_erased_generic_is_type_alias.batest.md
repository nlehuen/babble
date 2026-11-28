# Jailbreak : types T1/T4 erased generic is/match bypassed via type alias
<!-- Surface: types (T1/T4) | Severity: S0/S1 | Repro: POST /crib?modes=all&fresh=true -->

## Type alias to parameterized class must not bypass erased generic is check

```babble
def Cell of T = class (var v: T) ()
def StrCell = type (Cell of string)
def x: any = Cell of int (42)
if x is StrCell then (
    println(x.v.length())
) else (
    println(0)
)
```

```error
Cannot test erased type arguments with 'is'
```

## Type alias to parameterized class must not bypass erased generic match check

```babble
def Cell of T = class (var v: T) ()
def StrCell = type (Cell of string)
def x: any = Cell of int (42)
match x
case is StrCell then (println(x.v.length()))
else (println(0))
```

```error
Cannot test erased type arguments with 'is'
```
