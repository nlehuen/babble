# Jailbreak : effects E2 / ownership O1/O4 operator method provenance & exclusivity
<!-- Surface: effects (E2) | Severity: S2 (io CTF flag) | Repro: POST /crib?modes=all&fresh=true -->

## Binary operator plus must not launder io callback out of calling clause

```babble
def Wrap = class () (
    def plus = fn (f: fn (): int): fn (): int -> (f)
)
def launder = fn calling f (f: fn (): int): fn (): int -> (Wrap() + f)
def runPure = fn (g: fn (): int): int -> (g())
def sneaky: fn (): int = launder(fn io (): int -> (print("PWNED!"); 0))
def _ = runPure(sneaky)
```

```error
escapes via return
```

## Unary operator neg must not escape borrowed parameter

```babble
def Box = class (var n: int) (
    def neg = fn (): Box -> (this)
)
var leaked: Box? = null
def steal = fn io (b: Box) -> (leaked = -b)
def w = Box(1)
steal(w)
```

```error
Borrowed parameter b escapes
```

## Binary operator plus must enforce mut exclusivity between receiver and argument

```babble
def Box = class (var n: int) (
    def plus = fn mut (mut other: Box): bool -> (
        this.n = 1
        same(this, other)
    )
)
def b = Box(0)
println(b + b)
```

```error
Exclusivity error
```
