# Jailbreak : types T1 type alias erased generic catch
<!-- Surface: types (T1) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Type alias to parameterized class must not bypass erased generic catch check

```babble
def Cell of T = class (def v: T) ()
def StrCell = type (Cell of string)
try (
    throw Cell of int (42)
) catch e: StrCell then (
    println(e.v.length())
)
```

```error
Cannot test erased generic type
```
