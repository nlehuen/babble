# Jailbreak : effects E2 isBorrowCallSlot misclassifies return-through-borrow as non-escaping
<!-- Surface: effects (E2) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->

## Passing frame-local mutating closure to return-through-borrow must not escape as pure

```babble
def id = fn (f: fn (): int): fn (): int -> (f)
def makeCounter = fn (): fn (): int -> (
    var n = 0
    id(fn (): int -> (n = n + 1; n))
)
def c = makeCounter()
def pureCall = fn (): int -> (c())
println("" + pureCall() + ":" + pureCall())
```

```error
pure
```

## Passing closure capturing borrowed param to return-through-borrow must not escape

```babble
def Box = class (var v: int) ()
def id = fn (g: fn (): Box): fn (): Box -> (g)
var leaked: (fn (): Box)? = null
def steal = fn io (b: Box) -> (
    leaked = id(fn (): Box -> (b))
)
steal(Box(1))
println(leaked != null)
```

```error
borrowed
```
