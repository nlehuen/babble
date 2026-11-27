# Jailbreak : verifiers A2 AlgebraicVerifier certifies double * (-0.0) as monotone
<!-- Surface: verifiers (A2) | Severity: S3 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: compiles without error and prints ESCAPE:MONOTONE:0.0>-0.0 across all 6 modes (interpret, -O0, -O1, -O2, wasm-O1, wasm-O2) — AlgebraicVerifier.evalBinaryRel checks k >= 0.0 for MUL, treating -0.0 (and 0.0 on -Infinity -> NaN) as monotone. -->

## double multiplication by -0.0 must not be certified monotone

```babble
def f = fn monotone (x: double): double -> (x * (-0.0))
def a = f(-1.0)
def b = f(1.0)
if a > b then (println("ESCAPE:MONOTONE:" + a + ">" + b))
```

```error
monotone
```
