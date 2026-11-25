# Java effect annotations (org.babblelang.effects.*), SAM propagation, and Java axiom targets

References : BIP007, BIP016, BIP019, EffectChecker

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def HF = tests.EffectsTestCase.HostFixtures
def APC = tests.EffectsTestCase.AnnotatedPureClass
def MH = tests.EffectsTestCase.MutableHolder
```

## Case 1

```babble
def fPure = fn (x : int) : int -> ( HF.pureCalc(x) + APC.helper(x) )
def fNothrow = fn nothrow (x : int) : int -> ( HF.pureCalc(x) )
assert(fPure(2) == 17)
assert(fNothrow(2) == 5)
```

## Case 2

```babble
def fIdemp = fn idempotent (x : double) : double -> ( HF.clampUnit(x) )
def fAssocComm = fn associative commutative (a : int, b : int) : int -> ( HF.bitXor(a, b) )
def swapXor = fn commutative (a : int, b : int) : int -> ( HF.bitXor(b, a) )
assert(fIdemp(1.5) == 1.0)
assert(fAssocComm(3, 5) == 6)
assert(swapXor(3, 5) == 6)
```

## Case 3

```babble
def fMono = fn monotone (x : double) : double -> ( HF.expStep(x) )
def fInj = fn injective (x : int) : string -> ( HF.hexKey(x) )
assert(fMono(0.0) == 1.0)
assert(fInj(255) == "ff")
```

## Case 4

```babble
def fHerm = fn (d : int) : int -> (
    def h1 = MH(10)
    def h2 = MH(5)
    h1.bump(d)
    HF.mutateParam(h2, h1)
    h2.value
)
def fSam = fn (x : int) : int -> ( HF.applyOp(x, fn (v : int) : int -> ( v + 1 )) )
assert(fHerm(3) == 13)
assert(fSam(9) == 10)
```

## @IO method overrides class-level @Pure

```babble
def bad = fn (x : int) : int -> ( APC.escapeHatch(x) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Function bad violates pure guarantee (performs I/O or external mutati...

```babble
def bad = fn (x : int) : int -> ( HF.annotatedIO(x) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Java SAM callback mutating enclosing local violates pure

```babble
def countPure = fn () : int -> (
    def xs = list("a", "bb")
    var total = 0
    xs.forEach(fn (s : string) -> ( total += 1 ))
    total
)
```

```error
Function countPure violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## BIP019 structured Java axiom overrides

```babble
axiom java.lang.StringBuilder.append: fn mut shared
axiom java.lang.Math.abs: fn pure nothrow
def sb = java.lang.StringBuilder()
sb.append("ok")
assert(sb.toString() == "ok")
assert(java.lang.Math.abs(-3) == 3)
```

## export axiom inside a non-top-level block

```babble
def BadPlace = ( export axiom java.lang.Math.abs: fn pure ; 1 )
```

```error
export only applies to the top-level definitions of a file
```
