# Jailbreak : verifiers A1 EffectAstExtractors mishandles non-tail return and nested var assignments
<!-- Surface: verifiers (A1) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Non-tail return in subexpression must not be certified idempotent

```babble
def badIdem = fn idempotent (x: int): int -> (
    def t = (if x == 1 then (return 2) else (0))
    1
)
def run = fn (x: int): int -> (badIdem(badIdem(x)))
assert(run(0) == 2)
```

```error
idempotent
```

## Nested block reassigning outer var must not be certified idempotent

```babble
def badIdem = fn idempotent (x: int): int -> (
    var r = 1
    def _ = (r = x + 1; 0)
    r
)
def run = fn (x: int): int -> (badIdem(badIdem(x)))
assert(run(0) == 2)
```

```error
idempotent
```
