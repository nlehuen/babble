# Jailbreak : opt R5 TailCalls misclassifies reassigned var function field as self tail call
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Reassigning var method field before tail call must invoke the new function

```babble
def C = class () (
    var step: fn mut (int): int = fn mut (n: int): int -> (
        if n <= 0 then (0)
        else (
            this.step = fn mut (k: int): int -> (999)
            this.step(n - 1)
        )
    )
)
def c = C()
println(c.step(2))
```

```stdout
999
```
