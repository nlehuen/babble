# Jailbreak : types T2 nested array covariance leaks null and pollutes union
<!-- Surface: types (T2) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Nested array of non-null string must not widen covariantly to array of any

```babble
def a = array.of(array.of("hi"))
def b: array of any = a
def evil = (array of string?).of(null)
b.set(0, evil)
def s: string = a.get(0).get(0)
println(s.length())
```

```error
Type error
```

## Nested array of union must not widen covariantly to array of any

```babble
def a = array.of((array of (string | int)).of("x"))
def b: array of any = a
b.set(0, (array of any).of(true))
def v: string | int = a.get(0).get(0)
if v is string then (println(v.length())) else (println(v + 1))
```

```error
Type error
```
