# Jailbreak : types T6 FieldInitAnalysis short-circuit and/or definite assignment
<!-- Surface: types (T6) | Severity: S0/S1 | Repro: POST /crib?modes=all&fresh=true -->

## Short-circuit and RHS assignment must not count as definite field initialization

```babble
def Box = class () (
    def x: string
    init (cond: bool) -> (
        def _ = cond and (this.x = "ok"; true)
    )
)
println(Box(false).x.length())
```

```error
Field x might not be initialized
```
