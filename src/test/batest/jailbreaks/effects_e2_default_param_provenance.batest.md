# Jailbreak : effects E2 default parameter expressions discard provenance
<!-- Surface: effects (E2) | Severity: S2/S0 (io CTF PWNED!) | Repro: POST /crib?modes=all&fresh=true -->

## Default parameter must not launder calling io callback via shared parameter

```babble
def launder = fn calling f (f: fn (): int, shared g: fn (): int = f): fn (): int -> (g)
def sneaky: fn (): int = launder(fn io (): int -> (print("PWNED!"); 0))
def pureRun = fn (): int -> (sneaky())
def _ = pureRun()
```

```error
escape
```

## Default mut parameter must not alias non-mut borrowed parameter to mutate frozen

```babble
def Box = class (var x: int) ()
def sneaky = fn (a: Box, mut b: Box = a): int -> (b.x = 99; b.x)
def corruptFrozen = fn (fb: frozen Box): int -> (sneaky(fb))
def f = freeze(Box(1))
def before = f.x
def _ = corruptFrozen(f)
println("" + before + ":" + f.x)
```

```error
mut
```

## Default unique parameter must not alias earlier parameter for FBIP reuse

```babble
def sneaky = fn (a: list of int, unique b: list of int = a): list of int -> (
    def ys = list of int (from: move(b))
    ys.add(99)
    ys
)
def test = fn (): string -> (
    def a = list of int (1, 2)
    def ys = sneaky(a)
    "" + a.size() + ":" + ys.size()
)
println(test())
```

```error
unique
```
