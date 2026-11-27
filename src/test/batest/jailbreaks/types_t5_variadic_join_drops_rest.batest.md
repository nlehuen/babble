# Jailbreak : `types` (`T5`) joining two variadic functions drops the `rest` flag

- **Surface:** `types`
- **Vector:** `T5` (variadic `rest` function joins, `TypeAlgebra.sameParameters` / `mergeFunctions`, `BIP032` §1.2 finding 2)
- **Severity:** `S3` (static algebra inconsistency : the join of `fn (xs: int...)` with itself is not `fn (xs: int...)`)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `if ... then (a) else (b)` over two `fn (xs: int...): int` values types as `fn (xs: int): int`, so `c(1, 2, 3)` and `c()` fail with `Too many parameters : 1 declared, 3 given` in every mode.

## Joining two variadic functions keeps the rest parameter

```babble
def a = fn (xs: int...): int -> (var s = 0 ; for x in xs then (s += x) ; s)
def b = fn (xs: int...): int -> (7)
var k = 0
def c = if k == 0 then (a) else (b)
println(c(1, 2, 3))
println(c())
```

```stdout
6
0
```
