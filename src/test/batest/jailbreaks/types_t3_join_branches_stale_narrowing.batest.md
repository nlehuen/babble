# Jailbreak : types T3 TypeEnv.joinBranches restores stale narrowing
<!-- Surface: types (T3) | Severity: S0/S1 | Repro: POST /crib?modes=all&fresh=true -->

## Block reassigning and re-narrowing var to null must not restore outer non-null narrowing

```babble
def boom = fn (var x: string?): int -> (
    if x != null then (
        def dummy = (
            x = null
            if x != null then (return 0)
            0
        )
        x.length() + dummy
    ) else (0)
)
println(boom("hi"))
```

```error
Not a scope : x
```
