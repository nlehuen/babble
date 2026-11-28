# Jailbreak : effects E3 transitive untyped HOF forwarding drops thisFlow
<!-- Surface: effects (E3) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->

## Transitive untyped HOF forwarding must preserve thisFlow and reject frozen mutation

```babble
def Box = class (var x: int) (
    def inner = fn (f) -> (f(this))
    def outer = fn (f) -> (this.inner(f))
)
def pureMutFrozen = fn (fb: frozen Box): int -> (
    def _ = fb.outer(fn (mut b: Box) -> (b.x = 99))
    fb.x
)
def fb = freeze(Box(1))
def before = fb.x
def after = pureMutFrozen(fb)
println("" + before + ":" + after)
```

```error
frozen
```
