# BIP034-P2 : EffectChecker precision for def vs var and REPL determinism soundness

References : `BIP034-P2`, `EffectBodyWalker`, `EffectChecker`, `AnalysisSession`.

## Regular class def vs var fields : pure def getter and mut bump

```babble
def Counter = class (def id: int, var count: int) (
    def getId = fn (): int -> ( this.id )
    def getCount = fn (): int -> ( this.count )
    def bump = fn mut (): int -> ( this.count += 1 ; this.count )
)
def c = Counter(7, 1)
assert(c.getId() == 7 and c.getCount() == 1 and c.bump() == 2)
```

## Static def constant is pure while private static var requires volatile and io

```babble
def C = class () () static (
    def zero = 0
    private var total = 0
    def addTotal = fn io (n: int): int -> ( this.total += n ; this.total )
    def getTotal = fn volatile (): int -> ( this.total )
    def getZero = fn (): int -> ( this.zero )
)
assert(C.getZero() == 0 and C.addTotal(5) == 5 and C.getTotal() == 5)
```

## Reading private static var in pure function requires volatile

```babble
def C = class () () static (
    private var total = 0
    def bad = fn (): int -> ( this.total )
)
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Reading mutable var callback field on external object requires volatile

```babble
def Holder = class (var cb: fn (): int) ()
def h = Holder(fn (): int -> ( 1 ))
def bad = fn (): int -> ( h.cb() )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Escaped closure capturing local def scalar stays pure while captured var requires volatile

```babble
def makeConst = fn (n: int): fn (): int -> (
    def k = n + 1
    fn (): int -> ( k )
)
def f = makeConst(41)
assert(f() == 42)
```

## Escaped closure reading captured local var requires volatile

```babble
def makeBad = fn (): fn (): int -> (
    var n = 0
    def inc = fn io () -> ( n += 1 )
    discard(inc)
    fn (): int -> ( n )
)
```

```error
Function violates deterministic guarantee : mark function as volatile (or io)
```

## Module-private var encapsulated by exported volatile getter and io setter

```babble
;; babble module=state.ba
var count = 0
export def getCount = fn volatile (): int -> ( count )
export def bump = fn io (d: int): int -> ( count += d ; count )
```

```babble
def st = import("state.ba")
def run = fn io (): int -> (
    def a = st.getCount()
    def b = st.bump(3)
    def c = st.getCount()
    a + b + c
)
assert(run() == 6)
```

## Module-private var getter cannot be called from pure function

```babble
;; babble module=state2.ba
var count = 0
export def getCount = fn volatile (): int -> ( count )
```

```babble
def st = import("state2.ba")
def bad = fn (): int -> ( st.getCount() )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## REPL session : earlier def is pure and immutable, earlier var requires volatile

```babble
;; babble session
def k = 42
var c = 1
```

```babble
;; babble session
def readK = fn (): int -> ( k )
def readC = fn volatile (): int -> ( c )
c = 10
assert(readK() == 42 and readC() == 10)
```

```babble
;; babble session
def badReadC = fn (): int -> ( c )
```

```error
Function badReadC violates deterministic guarantee : mark function as volatile (or io)
```
