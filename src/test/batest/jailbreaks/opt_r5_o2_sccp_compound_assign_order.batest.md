# Jailbreak : opt R5 -O2 HirSccpPass compound assignment evaluates rhs before reading lhs
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: parityOk=false — interpret, -O0, -O1, and wasm-O1 print 3, while -O2 and wasm-O2 print 12 (HirSccpPass.evalVarAssign reads oldLat after evaluating rhs, folding 10 + 2 instead of 1 + 2). -->

## compound assignment must read lhs before rhs side effects at -O2

```babble
def test = fn (): int -> (
    var x = 1
    x += (x = 10; 2)
    x
)
println(test())
```

```stdout
3
```
