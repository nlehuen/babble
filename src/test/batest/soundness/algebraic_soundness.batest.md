# Soundness regressions : `verifiers` (`A1`, `A2`) algebraic properties & floating-point monotonicity

Promoted from `BIP032` quarantine (`verifiers_a2_monotone_double_zero_mul.batest.md`, `verifiers_a1_forward_mut_call_ignores_args.batest.md`, `verifiers_a2_monotone_double_neg_and_add.batest.md`) and sister vectors covering `AlgebraicVerifier` and `EffectAstExtractors`.

## Double multiplication by -0.0 must not be certified monotone

```babble
def f = fn monotone (x: double): double -> (x * (-0.0))
```

```error
Function f declared monotone violates monotone guarantee
```

## Double multiplication by +0.0 must not be certified monotone

```babble
def f = fn monotone (x: double): double -> (x * 0.0)
```

```error
Function f declared monotone violates monotone guarantee
```

## Float multiplication by -0.0f and 0.0f must not be certified monotone

```babble
def f = fn monotone (x: float): float -> (x * (-0.0f))
```

```error
Function f declared monotone violates monotone guarantee
```

## Double addition with non-finite constant must not be certified monotone

```babble
def f = fn monotone (x: double): double -> (x + (0.0 / 0.0))
```

```error
Function f declared monotone violates monotone guarantee
```

## Strictly non-zero finite affine double expressions remain monotone

```babble
def f = fn monotone (x: double): double -> (x * 2.0 + 1.0)
def g = fn monotone (x: double): double -> (x / 2.0 - 3.0)
assert(f(1.0) == 3.0 and g(4.0) == -1.0)
```

## Forwarding to mut idempotent method with state-dependent argument must not be idempotent

```babble
def Box = class (var v: int) (
    def setVal = fn mut idempotent (x: int) -> (this.v = x)
)
def evil = fn idempotent (mut b: Box, x: int) -> (b.setVal(b.v + x))
```

```error
Function evil declared idempotent violates idempotent guarantee
```

## Forwarding to mut commutative method with state-dependent argument must not be commutative

```babble
def Box = class (var v: int) (
    def addVal = fn mut commutative (x: int) -> (this.v += x)
)
def evil = fn commutative (mut b: Box, x: int) -> (b.addVal(b.v * 10 + x))
```

```error
Function evil declared commutative violates commutative guarantee
```

## Negated clamped double expression must not be certified monotone due to NaN

```babble
def badMono = fn monotone (x: double): double -> (
    -(if -x < 0.0 then (-x) else (0.0))
)
```

```error
Function badMono declared monotone violates monotone guarantee
```

## Float max-pattern ite must not be certified monotone due to NaN falling to else

```babble
def badMax = fn monotone (x: double): double -> (
    if x > 0.0 then (x) else (0.0)
)
```

```error
Function badMax declared monotone violates monotone guarantee
```

## Two-parameter double addition must not be certified monotone due to -Inf + Inf = NaN

```babble
def badAdd = fn monotone (x: double, y: double): double -> (x + y)
```

```error
Function badAdd declared monotone violates monotone guarantee
```

## Double addition with captured non-finite double must not be certified monotone

```babble
def inf = 1.0 / 0.0
def f = fn monotone (x: double): double -> (x + inf)
```

```error
Function f declared monotone violates monotone guarantee
```
