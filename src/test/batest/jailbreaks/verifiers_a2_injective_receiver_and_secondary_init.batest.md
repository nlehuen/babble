# Jailbreak : verifiers A2 injective receiver confusion and secondary record init
<!-- Surface: verifiers (A2) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Instance method injective in parameter must not certify receiver as injective

```babble
def Box = record (v: int) (
    def f = fn injective (y: int): int -> (y + 1)
)
def bad = fn injective (x: int): int -> (Box(x).f(0))
def check = fn (a: int, b: int): bool -> (bad(a) == bad(b))
assert(check(1, 2))
```

```error
injective
```

## Secondary record init constructor must not be certified injective

```babble
def Box = record (tag: string) (
    init (x: int) -> (this("const"))
)
def bad = fn injective (x: int): Box -> (Box(x))
def check = fn (a: int, b: int): bool -> (bad(a) == bad(b))
assert(check(1, 2))
```

```error
injective
```
