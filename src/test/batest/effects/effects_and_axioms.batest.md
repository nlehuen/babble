# Core effect system : pure-by-default, algebraic markers, mut/borrow parameters, exclusivity, and BIP019 axioms

References : BIP007, BIP016, BIP019, EffectChecker

```babble preamble
def Box = class (def value : int) (
    def addTo = fn mut (k : int) : int -> ( this.value += k ; this.value )
    def read = fn () : int -> ( this.value )
)
```

## Case 1

```babble
def inc = fn nothrow monotone (x : double) : double -> ( x + 1.0 )
def add = fn nothrow commutative associative (a : int, b : int) : int -> ( a + b )
def oddScale = fn nothrow injective (x : int) : int -> ( x * 3 )
def keep = fn idempotent (x : int) : int -> ( x )
def herm = fn (x : int) : int -> ( x * 2 )
assert(inc(1.5) == 2.5)
assert(add(oddScale(1), 2) + keep(3) == 8)
assert(herm(5) == 10)
```

## Wrapping 32-bit int addition cannot satisfy monotone guarantee

```babble
def badMono = fn monotone (x : int) : int -> ( x + 1 )
```

```error
Function badMono declared monotone violates monotone guarantee
```

## Case 3

```babble
def bumpBox = fn (mut b : Box, delta : int) : int -> ( b.addTo(delta) )
def inspectBox = fn (b : Box) : int -> ( b.read() )
def box = Box(10)
assert(bumpBox(box, 5) == 15)
assert(inspectBox(box) == 15)
```

## Case 4

```babble
axiom java.lang.Math.abs: fn pure nothrow
axiom java.util.List.add: fn mut
axiom java.util.List.size: fn pure
def compute = fn (x : int) : int -> ( java.lang.Math.abs(x) )
def l : java.util.List of int = java.util.ArrayList()
l.add(-7)
assert(compute(-7) == 7)
assert(l.size() == 1)
```

## Error : Cannot mutate parameter b (mut method b.addTo) : mark parameter as mut b

```babble
def bad = fn (b : Box) -> ( b.addTo(1) )
```

```error
Cannot mutate parameter b (mut method b.addTo) : mark parameter as mut b
```

## Error : Exclusivity error : mut target x aliases argument x

```babble
def swapAdd = fn (mut a : Box, mut b : Box) -> ( a.addTo(b.value) )
def x = Box(3)
swapAdd(x, x)
```

```error
Exclusivity error : mut target x aliases argument x
```

## Error : Function badPure violates pure guarantee (performs I/O or external mu...

```babble
def g = 0
def badPure = fn () : int -> ( g += 1 ; g )
```

```error
Function badPure violates pure guarantee (performs I/O or external mutation) : mark function as io
```
