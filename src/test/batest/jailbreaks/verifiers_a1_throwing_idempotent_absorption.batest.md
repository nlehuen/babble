# Jailbreak : verifiers A1 throwing function certified idempotent via absorption rule
<!-- Surface: verifiers (A1) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Throwing function must not be certified idempotent by exception-dropping absorption

```babble
def f = fn idempotent (x: int): int -> (0 * (10 // x))
def run = fn (x: int): int -> (f(f(x)))
println(run(1))
```

```error
idempotent
```
