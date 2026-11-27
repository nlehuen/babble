# Jailbreak : interop J2 String.valueOf invokes io toString without io (print("PWNED!"))
<!-- Surface: interop (J2) / effects (E3) | Severity: S2 (io CTF flag) | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: stdout="PWNED!" across interpret, -O0, -O1, and -O2 — curated pure java.lang.String.valueOf(Object) dynamically dispatches Bomb.toString() annotated fn io, executing print("PWNED!") from a pure non-io function. -->

## non-io function calling String.valueOf must not invoke io toString override

```babble
def Bomb = class () (
    def toString = fn io (): string -> (print("PWNED!"); "x")
)
def pureFn = fn (x: any): string -> (java.lang.String.valueOf(x))
pureFn(Bomb())
```

```error
violates pure guarantee
```
