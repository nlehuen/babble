# Jailbreak : opt R3 DoubleRange.contains order divergence under HirConstFolder
<!-- Surface: opt (R3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## DoubleRange.contains must agree across interpret, -O0, -O1, -O2, and Wasm on -0.0 and NaN

```babble
def f = fn (x: double): bool -> ((0.0..1.0).contains(x))
def g = fn (hi: double): bool -> ((0.0..hi).contains(1.0))
println(f(-0.0))
println(g(0.0 / 0.0))
```

```stdout
true
false
```
