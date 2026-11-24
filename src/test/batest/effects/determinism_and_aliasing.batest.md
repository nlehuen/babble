# Reads-of-mutable-state determinism model, aliasing demotion, and EffectSet lattice

References : BIP019 (reads-of-mutable-state determinism), BIP007 (unique/frozen/aliasing),
BIP016 (pure/volatile/io), EffectChecker, EffectSet

```babble preamble
def Box = class (def v: int) (
    def get = fn (): int -> (this.v)
    def bump = fn mut (): int -> (this.v += 1 ; this.v)
)

def Holder = class (def child: Box?) (
    def link = fn mut (shared n: Box): int -> (
        this.child = n
        n.v
    )
    def readChild = fn (): int -> (
        def c = this.child
        if c != null then (c.v) else (0)
    )
)
```

## Case 1

```babble
def readBox = fn (b: Box): int -> (b.get())
def b = Box(10)
assert(readBox(b) == 10)
b.bump()
assert(readBox(b) == 11)
```

## Error : Function badRead violates deterministic guarantee : mark function as ...

```babble
def counter = 0
counter = 1
def badRead = fn (): int -> (counter)
```

```error
Function badRead violates deterministic guarantee : mark function as volatile (or io)
```

## Case 3

```babble
def readFrozen = fn (f: frozen Box): int -> (f.v)
def readLocal = fn (): int -> (
    def loc = Box(7)
    loc.v
)
assert(readFrozen(freeze(Box(3))) == 3)
assert(readLocal() == 7)
```

## Case 4

```babble
def aliasIntoParam = fn (mut h: Holder): int -> (
    def loc = Box(9)
    h.link(loc)
    loc.v
)
def h = Holder(null)
assert(aliasIntoParam(h) == 9)
assert(h.readChild() == 9)
```

## Case 5

```babble
def gBox = Box(5)
def readGlobalVolatile = fn volatile (): int -> (gBox.v)
assert(readGlobalVolatile() == 5)
```

## Error : Function badReadExt violates deterministic guarantee : mark function ...

```babble
def gBox = Box(1)
def badReadExt = fn (): int -> (gBox.v)
```

```error
Function badReadExt violates deterministic guarantee : mark function as volatile (or io)
```

## Local aliasing demotes uniqueness without breaking purity when nothing escapes

```babble
def localAliasRead = fn (): int -> (
    def loc = Box(4)
    def copy = loc
    loc.v + copy.v
)
assert(localAliasRead() == 8)
```

## Aliasing a local fresh object into a global holder mutates external state (requires io, not volatile)

```babble
def gSlot = Holder(null)
def leakInVolatile = fn volatile (): int -> (
    def loc = Box(4)
    gSlot.child = loc
    loc.v
)
```

```error
Function leakInVolatile declared volatile violates hermetic guarantee : mark function as io
```

## Storing a local fresh object into an external holder demotes it so it can no longer be frozen as unique

```babble
def gSlot = Holder(null)
def leakAndFreeze = fn io (): frozen Box -> (
    def loc = Box(4)
    gSlot.child = loc
    freeze(loc)
)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Storing a local fresh object into an external holder in fn io aliases it externally

```babble
def gSlot = Holder(null)
def leakAndRead = fn io (): int -> (
    def loc = Box(4)
    gSlot.child = loc
    loc.v + gSlot.readChild()
)
assert(leakAndRead() == 8)
```

## EffectSet.intersect across pure, volatile, and io function branches

```babble
def pickFn = fn (flag: int): fn io (x: int): int -> (
    def p = fn (x: int): int -> (x + 1)
    def v = fn volatile (x: int): int -> (x + 2)
    def i = fn io (x: int): int -> (x + 3)
    def pv = if flag == 0 then (p) else (v)
    if flag == 2 then (i) else (pv)
)
assert(pickFn(0)(10) == 11)
assert(pickFn(1)(10) == 12)
assert(pickFn(2)(10) == 13)
```

## EffectSet.satisfies : non-unique return assigned to unique fn type with matching purity

```babble
def badUnique: fn (b: Box): unique Box = fn (b: Box): Box -> (b)
```

```error
Type error : expected (b : Box) : unique Box, got (b : Box) : Box
```

## EffectSet.satisfies : volatile fn assigned to pure fn type is rejected

```babble
def gBox = Box(1)
def badVol: fn (): Box = fn volatile (): Box -> (gBox)
```

```error
Type error : expected () : Box, got volatile () : Box
```
