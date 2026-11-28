# Jailbreak : verifiers A2 double unary NEG and non-const ADD monotonicity on NaN and Infinity
<!-- Surface: verifiers (A2) | Severity: S3 | Repro: POST /crib?modes=all&fresh=true -->

## Double unary negation composition must not be certified monotone under Double.compare total order

```babble
def badMono = fn monotone (x: double): double -> (
    -(if -x < 0.0 then (-x) else (0.0))
)
def a = 1.0
def b = 0.0 / 0.0
println("" + (a < b) + ":" + (badMono(a) <= badMono(b)))
```

```error
violates monotone guarantee
```

## Two-parameter double addition must not be certified monotone due to -Inf + Inf = NaN

```babble
def badAdd = fn monotone (x: double, y: double): double -> (x + y)
def inf = 1.0 / 0.0
println("" + ((-inf) < 0.0) + ":" + (badAdd(-inf, inf) <= badAdd(0.0, inf)))
```

```error
violates monotone guarantee
```

## Double addition with captured non-finite double must not be certified monotone

```babble
def inf = 1.0 / 0.0
def f = fn monotone (x: double): double -> (x + inf)
println("" + ((-inf) < 0.0) + ":" + (f(-inf) <= f(0.0)))
```

```error
violates monotone guarantee
```
