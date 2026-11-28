# Jailbreak : effects E3 unary negate on union drops io effect
<!-- Surface: effects (E3) | Severity: S2 (io CTF flag) | Repro: POST /crib?modes=all&fresh=true -->

## Unary negate on class-numeric union must not drop io effect of neg method

```babble
def Bomb = class () (
    def neg = fn io (): int -> (print("PWNED!"); 0)
)
def leak = fn nothrow (u: Bomb | int): int -> (-u)
def _ = leak(Bomb())
```

```error
violates pure guarantee
```
