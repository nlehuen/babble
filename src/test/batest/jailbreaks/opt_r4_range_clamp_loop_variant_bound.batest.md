# Jailbreak : `opt` (`R4`) `-O2` range bound clamping hoists a bound the loop body reassigns

- **Surface:** `opt` (`BIP042` `PipelineRewritePass`, rule `R6` direct range bound clamping)
- **Vector:** `R4` (hoisting reads of mutated locals out of a loop)
- **Severity:** `S0` (`-O2` and `wasm-O2` print a different result than `interpret`, `-O0` and `-O1`)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `for x in lo..hi then ( if x <= lim then ( ... ; lim += 1 ) )` is rewritten to `for x in lo..min(hi, lim)`, reading `lim` once before the loop although the body reassigns it. The same happens for `<`, a `>=` lower bound, and `long` ranges ; a local closure called in the body that reassigns the bound is missed too, while a bound read from a field mutated in the body is left alone.

## Upper bound reassigned in the body is not loop-invariant

```babble
def f = fn (n : int) : int -> (
    var lim = 2
    var acc = 0
    for x in 0..n then ( if x <= lim then ( acc += x ; lim += 1 ) )
    acc
)
println(f(10))
```

```stdout
55
```

## Exclusive upper bound reassigned in the body is not loop-invariant

```babble
def f = fn (n : int) : int -> (
    var lim = 2
    var acc = 0
    for x in 0..<n then ( if x < lim then ( acc += x ; lim += 1 ) )
    acc
)
println(f(10))
```

```stdout
45
```

## Lower bound reassigned in the body is not loop-invariant

```babble
def f = fn (n : int) : int -> (
    var lo = 0
    var acc = 0
    for x in 0..n then ( if x >= lo then ( acc += x ; lo += 2 ) )
    acc
)
println(f(10))
```

```stdout
30
```

## Long bound reassigned in the body is not loop-invariant

```babble
def f = fn (n : long) : long -> (
    var lim = 2L
    var acc = 0L
    for x in 0L..n then ( if lim >= x then ( acc += x ; lim += 1L ) )
    acc
)
println(f(10L))
```

```stdout
55
```

## Upper bound reassigned by a local closure called in the body is not loop-invariant

```babble
def f = fn (n : int) : int -> (
    var lim = 2
    def bump = fn () -> ( lim += 1 )
    var acc = 0
    for x in 0..n then ( if x <= lim then ( acc += x ; bump() ) )
    acc
)
println(f(10))
```

```stdout
55
```
