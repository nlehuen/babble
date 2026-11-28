# Jailbreak : interop J2 java.lang.Iterable ignores Iterator.next() io/throw effects
<!-- Surface: interop (J2) | Severity: S2 (io CTF flag) | Repro: POST /crib?modes=all&fresh=true -->

## Java Iterable for-loop and list(from:) must not invoke io Iterator.next() inside pure nothrow fn

```babble
def BombIter = class (var done: bool = false) implements java.util.Iterator of int (
    def hasNext = fn nothrow (): bool -> (not this.done)
    def next = fn io mut (): int -> (
        this.done = true
        print("PWNED!")
        42
    )
)
def Bomb = class () implements java.lang.Iterable of int (
    def iterator = fn shared nothrow (): java.util.Iterator of int -> (BombIter(false))
)
def leakFor = fn nothrow (xs: java.lang.Iterable of int): int -> (
    var s = 0
    for x in xs then (s = s + x)
    s
)
def leakList = fn nothrow (xs: java.lang.Iterable of int): list of int -> (
    list(from: xs)
)
def _ = leakFor(Bomb())
def _2 = leakList(Bomb())
```

```error
violates pure guarantee
```
