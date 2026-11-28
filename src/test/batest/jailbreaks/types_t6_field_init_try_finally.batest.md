# Jailbreak : types T6 FieldInitAnalysis walks try finally with post-try DA
<!-- Surface: types (T6) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Constructor finally must not see def field assigned inside try as initialized

```babble
def C = class () (
    def s: string
    init (mut sink: list of C) -> (
        try (
            def _ = 1 // 0
            this.s = "ok"
        ) finally (
            sink.add(this)
        )
    )
)
def sink = list of C ()
try (C(sink)) catch e then ()
println(sink.get(0).s.length())
```

```error
not initialized
```
