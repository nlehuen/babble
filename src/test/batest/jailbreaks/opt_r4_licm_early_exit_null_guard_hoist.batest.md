# Jailbreak : opt R4 HirLicmPass hoists early-exit null-guarded field load above zero-trip loop
<!-- Surface: opt (R4) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Zero-trip while loop with early-exit null check must not hoist p.x before loop

```babble
def Point = record (x: int) ()
def test = fn (p: Point?, n: int): int -> (
    var s = 0
    var i = 0
    while i < n then (
        if p == null then (return s)
        s += p.x
        i += 1
    )
    s
)
println(test(null, 0))
```

```stdout
0
```
