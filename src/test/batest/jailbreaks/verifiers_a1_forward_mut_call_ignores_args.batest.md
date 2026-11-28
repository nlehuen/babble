# Jailbreak : verifiers A1 ForwardMutCall ignores arguments for idempotent and commutative
<!-- Surface: verifiers (A1) | Severity: S3 | Repro: POST /crib?modes=all&fresh=true -->

## Forwarding to mut idempotent method with state-dependent argument must not be certified idempotent

```babble
def Box = class (var v: int) (
    def setVal = fn mut idempotent (x: int) -> (this.v = x)
)
def evil = fn idempotent (mut b: Box, x: int) -> (
    b.setVal(b.v + x)
)
def b = Box(0)
evil(b, 1)
evil(b, 1)
println(b.v)
```

```error
violates idempotent guarantee
```

## Forwarding to mut commutative method with state-dependent argument must not be certified commutative

```babble
def Box = class (var v: int) (
    def addVal = fn mut commutative (x: int) -> (this.v += x)
)
def evil = fn commutative (mut b: Box, x: int) -> (
    b.addVal(b.v * 10 + x)
)
def b1 = Box(1)
evil(b1, 2)
evil(b1, 3)
println(b1.v)
```

```error
violates commutative guarantee
```
