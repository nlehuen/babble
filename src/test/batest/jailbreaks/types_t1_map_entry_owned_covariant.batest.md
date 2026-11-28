# Jailbreak : types T1 Map.Entry owned covariance allows setValue heap pollution
<!-- Surface: types (T1) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Owned Map.Entry must not widen covariantly because setValue mutates value

```babble
def m = map of (string, string) (entry("a", "hi"))
def e = m.entrySet().iterator().next()
def e2: java.util.Map.Entry of (string, any) = e
e2.setValue(42)
def s: string = m.get("a")!
println(s.length())
```

```error
Type error
```
