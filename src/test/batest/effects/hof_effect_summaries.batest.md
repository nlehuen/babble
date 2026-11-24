# HOF symbolic effect summaries : thisFlow, extFlow, shared/mut propagation, and optional callbacks

References : `BIP007` (HOF effects), `BIP027` (`SymbolicEffectSummary`, `CallEffectAnalyzer.instantiateSymbolicCall`, `EffectBodyWalker.recordCallbackArgFlow`).

```babble
;; babble module=hof_summaries_lib.ba
export def Box = class (def v: int) ()
export def Holder = class () ( def ref: Box? = null )
export def idBox = fn (b: Box): Box -> ( b )
export def readBox = fn (b: Box): int -> ( b.v )
export def storeBox = fn (mut h: Holder, shared b: Box) -> ( h.ref = b )
export def applyOpt = fn (b: Box, f: fn (Box): Box = idBox, g: fn (Box): int = readBox): int -> ( g(f(b)) )
export def fwdStore = fn (mut h: Holder, shared b: Box, f: fn (mut Holder, shared Box)) -> ( f(h, b) )
```

```babble
;; babble preamble
def Box = class (def v: int) ()
def Holder = class () ( def ref: any? = null )
def Cell = class (def v: int) (
    def applySelf = fn mut (f: fn (mut Cell): int): int -> ( f(this) )
    def inspectSelf = fn (f: fn (Cell): int): int -> ( f(this) )
    def bumpAndCall = fn mut (f: fn (int): int): int -> ( this.v += 1 ; f(this.v) )
    def shareSelf = fn shared (mut h: Holder, f: fn (mut Holder, shared Cell)) -> ( f(h, this) )
)
def incCell = fn (mut c: Cell): int -> ( c.v += 1 ; c.v )
def readCell = fn (c: Cell): int -> ( c.v )
def storeBox = fn (mut h: Holder, shared b: Box) -> ( h.ref = b )
```

## Symbolic thisFlow mutThis and deepMutThis propagation on non-calling HOF methods

```babble
def c = Cell(2)
def r1 = c.applySelf(incCell)
def r2 = c.bumpAndCall(fn (n: int): int -> ( n * 2 ))
assert(r1 == 3 and r2 == 8 and c.v == 4)
```

## Symbolic thisFlow readsThis propagation on non-calling HOF method

```babble
def g = Cell(6)
def readFrozen = fn (fc: frozen Cell): int -> ( fc.inspectSelf(readCell) )
def readGlobal = fn volatile (): int -> ( g.inspectSelf(readCell) )
assert(readFrozen(freeze(Cell(5))) == 5 and readGlobal() == 6)
```

## Symbolic thisFlow readsThis rejects pure function reading global Cell via method HOF

```babble
def g = Cell(5)
def bad = fn (): int -> ( g.inspectSelf(readCell) )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Symbolic extFlow with mut callback removes pure, hermetic, and deterministic

```babble
def g = Cell(1)
def applyGlobal = fn io (f: fn (mut Cell): int): int -> ( f(g) )
assert(applyGlobal(incCell) == 2 and g.v == 2)
```

## Symbolic extFlow with mut callback rejects pure HOF declaration

```babble
def g = Cell(1)
def bad = fn (f: fn (mut Cell): int): int -> ( f(g) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Symbolic extFlow with reading callback removes deterministic and marks volatile

```babble
def g = Cell(9)
def readG = fn (f) -> ( f(g) )
def v = fn volatile (): int -> ( readG(readCell) )
assert(v() == 9)
```

## Symbolic extFlow with reading callback rejects pure HOF declaration

```babble
def g = Cell(9)
def bad = fn (f: fn (Cell): int): int -> ( f(g) )
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Symbolic non-borrowed callback parameter propagates shared to HOF parameter

```babble
def fwdStore = fn (mut h: Holder, shared b: Box, f: fn (mut Holder, shared Box)) -> ( f(h, b) )
def h = Holder()
fwdStore(h, Box(7), storeBox)
assert(h.ref != null)
```

## Symbolic non-borrowed callback parameter rejects borrowed argument escape

```babble
def fwdStore = fn (mut h: Holder, shared b: Box, f: fn (mut Holder, shared Box)) -> ( f(h, b) )
def bad = fn (mut h: Holder, b: Box) -> ( fwdStore(h, b, storeBox) )
```

```error
Borrowed parameter b escapes via call to fwdStore : mark parameter as shared b
```

## Symbolic non-borrowed callback parameter propagates borrowThis false via thisFlow

```babble
def storeCell = fn (mut h: Holder, shared c: Cell) -> ( h.ref = c )
def h = Holder() ; def c = Cell(4)
c.shareSelf(h, storeCell)
assert(same(h.ref, c))
```

## Symbolic borrowThis false rejects borrowed receiver escape at call site

```babble
def storeCell = fn (mut h: Holder, shared c: Cell) -> ( h.ref = c )
def bad = fn (mut h: Holder, c: Cell) -> ( c.shareSelf(h, storeCell) )
```

```error
Borrowed parameter c escapes via method call c.shareSelf : mark parameter as shared c
```

## Symbolic two-callback HOF merges frameReadOwners and frameWriteOwners across callbacks

```babble
def applyBoth = fn (f: fn (int): int, g: fn (int): int, x: int): int -> ( g(f(x)) )
def outer = fn (): int -> (
    def acc = 0
    def r = applyBoth(fn (a: int): int -> ( acc += a ; acc ), fn (b: int): int -> ( acc += b ; acc ), 3)
    r + acc
)
assert(outer() == 12)
```

## Symbolic HOF with omitted default second callback uses defaultCallbackEffects

```babble
def idBox = fn (b: Box): Box -> ( b )
def readBox = fn (b: Box): int -> ( b.v )
def pipeOpt = fn (b: Box, f: fn (Box): Box = idBox, g: fn (Box): int = readBox): int -> ( g(f(b)) )
assert(pipeOpt(Box(7), fn (b: Box): Box -> ( Box(b.v + 1) )) == 8)
```

## Symbolic HOF with omitted first callback via named argument scans past null callback

```babble
def idBox = fn (b: Box): Box -> ( b )
def readBox = fn (b: Box): int -> ( b.v )
def pipeOpt = fn (b: Box, f: fn (Box): Box = idBox, g: fn (Box): int = readBox): int -> ( g(f(b)) )
assert(pipeOpt(Box(7), g: fn (b: Box): int -> ( b.v * 3 )) == 21)
```

## Symbolic HOF with all callbacks omitted returns base summary

```babble
def idBox = fn (b: Box): Box -> ( b )
def readBox = fn (b: Box): int -> ( b.v )
def pipeOpt = fn (b: Box, f: fn (Box): Box = idBox, g: fn (Box): int = readBox): int -> ( g(f(b)) )
assert(pipeOpt(Box(7)) == 7)
```

## Symbolic HOF called with abstract parameter callback uses Type.Function fallback

```babble
def Cb = type fn (mut Holder, shared Box, int, Box): int
def run4 = fn (mut h: Holder, shared b: Box, n: int, r: Box, cb: Cb): int -> ( cb(h, b, n, r) )
def out = fn (mut h: Holder, shared b: Box, r: Box, f: Cb): int -> ( run4(h, b, 3, r, f) )
def cb: Cb = fn (mut h: Holder, shared b: Box, n: int, r: Box): int -> ( h.ref = b ; n + r.v )
assert(out(Holder(), Box(2), Box(4), cb) == 7)
```

## Symbolic zero-arg callback HOF with null paramFlow row

```babble
def run0 = fn (f: fn (): int): int -> ( f() + 1 )
def outer = fn (): int -> (
    def acc = 1
    def a = run0(fn (): int -> ( 2 ))
    def b = run0(fn (): int -> ( acc += 2 ; acc ))
    a + b + acc
)
assert(outer() == 10)
```

## Symbolic io HOF with called callback finalizes non-hermetic residual

```babble
def ioHof = fn io (f: fn (int): int, x: int): int -> ( println(x) ; f(x) )
assert(ioHof(fn (n: int): int -> ( n + 1 ), 5) == 6)
```

```stdout
5
```

## Cross-module symbolic HOF with defaulted callback and shared propagation

```babble
def lib = import("hof_summaries_lib.ba")
def h = lib.Holder()
def r = lib.applyOpt(lib.Box(4), fn (b: lib.Box): lib.Box -> ( lib.Box(b.v * 2) ))
lib.fwdStore(h, lib.Box(9), lib.storeBox)
assert(r == 8 and h.ref != null)
```

## Transitive HOF forwarding composes paramFlow through mut and shared wrappers

```babble
def inner = fn (mut h: Holder, shared b: Box, f: fn (mut Holder, shared Box)) -> ( f(h, b) )
def outer = fn (mut h: Holder, shared b: Box, g: fn (mut Holder, shared Box)) -> ( inner(h, b, g) )
def h = Holder()
outer(h, Box(6), storeBox)
assert(h.ref != null)
```

## Transitive HOF forwarding rejects borrowed argument escape

```babble
def inner = fn (mut h: Holder, shared b: Box, f: fn (mut Holder, shared Box)) -> ( f(h, b) )
def outer = fn (mut h: Holder, shared b: Box, g: fn (mut Holder, shared Box)) -> ( inner(h, b, g) )
def bad = fn (mut h: Holder, b: Box) -> ( outer(h, b, storeBox) )
```

```error
Borrowed parameter b escapes via call to outer : mark parameter as shared b
```
