# Jailbreak : ownership O4 list.get(0) bypasses mut exclusivity
<!-- Surface: ownership (O4) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: compiles cleanly and prints ESCAPE:MUT_GET (same(a, b) == true) across all 6 modes (interpret, -O0, -O1, -O2, wasm-O1, wasm-O2) — mut exclusivity does not track aliasing through container/array element projections. -->

## passing aliased list.get(0) to two mut parameters must fail exclusivity check

```babble
def mutateBoth = fn (mut a: list of int, mut b: list of int): bool -> (
    a.add(1)
    same(a, b)
)
def inner = list of int (0)
def outer = list of (list of int) (inner)
if mutateBoth(outer.get(0), outer.get(0)) then (
    println("ESCAPE:MUT_GET")
)
```

```error
Exclusivity error
```
