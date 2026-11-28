# Jailbreak : ownership O1/O2 for-loop protocol misses frozen self-iterator and shared this escape
<!-- Surface: ownership (O1/O2) | Severity: S0/S2 | Repro: POST /crib?modes=all&fresh=true -->

## For loop over frozen Babble class returning self as Java Iterator must be rejected

```babble
def SelfIter = class (var v: int = 0) implements java.util.Iterator of int (
    def iterator = fn (): java.util.Iterator of int -> (this)
    def hasNext = fn nothrow (): bool -> (this.v == 0)
    def next = fn mut (): int -> (this.v = 99; 99)
)
def corruptFrozen = fn (s: frozen SelfIter): int -> (
    var r = 0
    for x in s then (r = x)
    r
)
def f = freeze(SelfIter(0))
def before = f.v
def _ = corruptFrozen(f)
println("" + before + ":" + f.v)
```

```error
frozen
```

## For loop over borrowed param must reject iterator() with shared this

```babble
var leaked: Sneaky? = null
def It = class () (
    def hasNext = fn (): bool -> (false)
    def next = fn (): int -> (0)
)
def Sneaky = class () (
    def iterator = fn io shared (): It -> (leaked = this; It())
)
def steal = fn io (s: Sneaky) -> (
    for x in s then (discard(x))
)
steal(Sneaky())
println(leaked != null)
```

```error
borrowed
```
