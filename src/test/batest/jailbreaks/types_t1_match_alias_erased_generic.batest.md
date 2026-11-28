# Jailbreak : types T1 type alias erased generic record destructuring in match
<!-- Surface: types (T1) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Type alias to parameterized record must not bypass erased generic match destructuring

```babble
def Wrap of T = record (v: T) ()
def StrWrap = type (Wrap of string)
def x: any = Wrap of int (42)
match x
case is StrWrap (def s) then (println(s.length()))
else (println(0))
```

```error
Cannot test erased generic type
```
