# Jailbreak : interop J3 abstract Java method waives array element nullness
<!-- Surface: interop (J3) | Severity: S1 | Repro: POST /crib?modes=all&fresh=true -->

## Collection.toArray must not accept non-null array of string for nullable array parameter

```babble
def target = array.of("a")
def _ = (list of string ()).toArray(target)
println(target.get(0).length())
```

```error
Type error
```
