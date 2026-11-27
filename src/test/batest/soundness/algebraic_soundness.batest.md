# Soundness regressions : `verifiers` (`A2`) floating-point monotonicity

Promoted from `BIP032` quarantine (`verifiers_a2_monotone_double_zero_mul.batest.md`) and sister IEEE-754 edge cases covering `AlgebraicVerifier`.

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
