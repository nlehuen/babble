# Jailbreak : types T1 generic record covariance allows mutable field heap pollution
<!-- Surface: types (T1) | Severity: S1 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: compiles with zero as casts, silently inserts an int into list of string through Box of any, and crashes on read with parityOk=false — JVM modes throw Type error : expected string, got int and wasm-O1/wasm-O2 trap with RuntimeError: illegal cast. -->

## generic record holding mutable list must not be covariant

```babble
def Box of T = record (xs: list of T) ()
def strs = list of string ("hi")
def b: Box of any = Box of string (strs)
b.xs.add(42)
def s: string = strs.get(1)
println("ESCAPE:TYPE:" + s.length())
```

```error
Type error
```
