# Jailbreak : opt R3 match range pattern numeric parity on -0.0, NaN, and stepped overflow
<!-- Surface: opt (R3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Match double range pattern agrees across all modes on -0.0 and NaN

```babble
def check = fn (x: double, hi: double): int -> (
    match x
        case 0.0..hi then (1)
        else (0)
)
assert(check(-0.0, 1.0) == 1)
assert(check(0.0 / 0.0, 0.0 / 0.0) == 0)
```

## Match stepped int and long range patterns handle full-span widths without overflow

```babble
def checkInt = fn (x: int): int -> (
    match x
        case ((-2147483647 - 1)..2147483647).step(3) then (1)
        else (0)
)
def checkLong = fn (x: long): int -> (
    match x
        case ((-9223372036854775807L - 1L)..9223372036854775807L).step(3L) then (1)
        else (0)
)
assert(checkInt(2147483647) == 1)
assert(checkLong(9223372036854775807L) == 1)
```
