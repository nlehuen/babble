# Jailbreak : effects E4 compound assignment invokes io operator method without io (print("PWNED!"))
<!-- Surface: effects (E4) | Severity: S2 (io CTF flag) | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: stdout="PWNED!" across all 6 modes (interpret, -O0, -O1, -O2, wasm-O1, wasm-O2) — EffectBodyWalker.evalAssign does not propagate operator method effects on compound assignments (+=, -=, *=, /=, //=, %=). -->

## pure function must not invoke io plus operator method via compound assignment +=

```babble
def Evil = class (var v: int) (
    def plus = fn io (other: Evil): Evil -> (print("PWNED!"); Evil(this.v + other.v))
)
def pureFn = fn (a: Evil, b: Evil): int -> (
    var x = a
    x += b
    x.v
)
pureFn(Evil(1), Evil(2))
```

```error
violates pure guarantee
```
