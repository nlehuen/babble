# Jailbreak : interop J3 Faces SAM adapter missing inbound null/type check
<!-- Surface: interop (J3) | Severity: S0/S1 | Repro: POST /crib?modes=all&fresh=true -->

## Cast-free HashMap.compute passing null to BiFunction SAM must check inbound parameter consistently

```babble
def m = java.util.HashMap of (string, string) ()
def f: java.util.function.BiFunction of (string, string, string) = fn (k: string, v: string): string -> ("" + v.length())
println(m.compute("missing", f))
```

```error
Type error : expected string, got null
```
