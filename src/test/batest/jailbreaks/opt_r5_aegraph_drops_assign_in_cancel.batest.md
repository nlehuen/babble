# Jailbreak : opt R5 AeGraph drops VarAssign side effects during injective comparison cancellation
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Injective comparison cancellation must preserve side-effecting assignments in operands

```babble
def bump = fn injective (x: int): int -> (x + 1)
def test = fn (a: int, b: int): int -> (
    var s = 0
    var t = 0
    def eq = (s = bump(a)) == (t = bump(b))
    s * 100 + t * 10 + (if eq then (1) else (0))
)
assert(test(2, 3) == 340)
```
