# Jailbreak : verifiers A3 indirect toString/operator recursion and java.lang.Iterable termination DCE
<!-- Surface: verifiers (A3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Indirect toString recursion through record must not be deleted by DCE

```babble
def Wrap = record (b: Bomb) ()
def Bomb = class () (
    def toString = fn nothrow (): string -> (
        def w = Wrap(this)
        "" + w
    )
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.toString()
    42
)
println(run())
```

```error
Stack overflow
```

## Compound assignment operator recursion on narrowed union must not be deleted by DCE

```babble
def Bomb = class () (
    def plus = fn nothrow (n: int): int -> (
        var x: Bomb | int = this
        if x is Bomb then (x += n)
        0
    )
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.plus(1)
    42
)
println(run())
```

```error
Stack overflow
```

## For loop over java.lang.Iterable must not certify throwing Iterator as nothrow and terminating

```babble
def BoomIter = class () implements java.util.Iterator of int (
    def hasNext = fn nothrow (): bool -> (true)
    def next = fn mut (): int -> (throw java.lang.RuntimeException("BOOM"))
)
def BoomIterable = class () implements java.lang.Iterable of int (
    def iterator = fn shared nothrow (): java.util.Iterator of int -> (BoomIter())
)
def boom = fn nothrow (): int -> (
    def it: java.lang.Iterable of int = BoomIterable()
    for x in it then (def _ = x)
    0
)
def run = fn (): int -> (
    def unused = boom()
    42
)
println(run())
```

```error
nothrow
```
