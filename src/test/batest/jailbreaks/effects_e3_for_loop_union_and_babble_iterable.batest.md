# Jailbreak : effects E3 / ownership O2 for-loop DYNAMIC_UNION & BABBLE_ITERABLE
<!-- Surface: effects (E3) | Severity: S0/S2 (io CTF flag + parity) | Repro: POST /crib?modes=all&fresh=true -->

## For loop over union of Babble iterables must not drop io or diverge across backends

```babble
def BombIter = class () (
    def hasNext = fn io (): bool -> (print("PWNED!"); false)
    def next = fn (): int -> (0)
)
def Bomb1 = class () (
    def iterator = fn (): BombIter -> (BombIter())
)
def Bomb2 = class () (
    def iterator = fn (): BombIter -> (BombIter())
)
def leak = fn (u: Bomb1 | Bomb2): int -> (
    for x in u then (def _ = x)
    0
)
def _ = leak(Bomb1())
```

```error
violates pure guarantee
```

## For loop over frozen Babble iterable returning this must not mutate frozen receiver

```babble
def SelfIter = class (var v: int = 0) (
    def iterator = fn (): SelfIter -> (this)
    def hasNext = fn (): bool -> (this.v == 0)
    def next = fn mut (): int -> (this.v = 99; 99)
)
def mutPure = fn (s: frozen SelfIter): int -> (
    var r = 0
    for x in s then (r = x)
    r
)
def f: frozen SelfIter = freeze(SelfIter(0))
def _ = mutPure(f)
```

```error
Cannot mutate
```
