# Jailbreak : effects E2 local compound assignment overwrites origin with IMMUTABLE
<!-- Surface: effects (E2) | Severity: S2 (io CTF PWNED!) | Repro: POST /crib?modes=all&fresh=true -->

## Compound assignment must not wipe calling callback provenance

```babble
def Wrap = class (var cb: fn (): int) (
    def plus = fn (n: int): Wrap -> (this)
)
def launder = fn calling f (f: fn (): int): Wrap -> (
    var w = Wrap(fn (): int -> (0))
    w.cb = f
    w += 0
    w
)
def pureRun = fn (w: Wrap): int -> (w.cb())
def leaked = launder(fn io (): int -> (print("PWNED!"); 0))
def _ = pureRun(leaked)
```

```error
escape
```

## Compound assignment must not wipe borrowed parameter provenance

```babble
def Box = class (var v: int) (
    def plus = fn (n: int): Box -> (this)
)
var leaked: Box? = null
def steal = fn io (b: Box) -> (
    var x = b
    x += 0
    leaked = x
)
steal(Box(1))
println(leaked != null)
```

```error
borrowed
```
