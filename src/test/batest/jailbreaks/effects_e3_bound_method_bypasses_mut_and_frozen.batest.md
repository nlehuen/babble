# Jailbreak : effects E3/E5 / ownership O2 bound mut methods & bound getters on frozen
<!-- Surface: effects (E3/E5) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->

## Bound mut method must not mutate frozen receiver or global inside pure function

```babble
def Box = class (var n: int) (
    def inc = fn mut (): int -> (this.n = this.n + 1; this.n)
)
def globalBox = Box(0)
def bumpGlobal = globalBox.inc
def pureMut = fn (fb: frozen Box): int -> (
    (fb.inc)() + bumpGlobal()
)
def fb: frozen Box = freeze(Box(1))
println(pureMut(fb))
```

```error
frozen
```

## Bound getter on frozen receiver must not return unfrozen mutable inner object

```babble
def Box = class (var n: int) ()
def Outer = class (def inner: Box) (
    def getInner = fn (): Box -> (this.inner)
)
def fo: frozen Outer = freeze(Outer(Box(1)))
def leaked: Box = (fo.getInner)()
leaked.n = 99
println(fo.inner.n)
```

```error
Type error
```
