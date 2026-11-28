# Jailbreak : opt R3 mixed int/long vs float comparison precision divergence
<!-- Surface: opt (R3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Comparing int with float must not lossily round int to float in compiled or Wasm modes

```babble
def cmp = fn (a: int, b: float): bool -> (a == b)
println(cmp(16777217, float(16777216)))
println(16777217 > float(16777216))
```

```stdout
false
true
```
