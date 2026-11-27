# Jailbreak : `opt` (`R5`) `-O2` `HirAeGraphPass` forwards stale load across `mut` call on `unique` parameter

- **Surface:** `opt`
- **Vector:** `R5` (`-O2` `HirAeGraphPass` redundant load elimination / `UniqueParam` state invalidation)
- **Severity:** `S0` (Silent miscompilation in `-O2` and `wasm-O2` — wrong observable result)
- **Observed:** Inside a function taking `unique b: Box`, reading `b.v`, mutating `b` via a `mut` call (`bump(b)` or a `mut` method), and reading `b.v` again prints `313` in `interpret`, `-O0`, `-O1`, and `wasm-O1`, but silently prints `303` in `-O2` and `wasm-O2` because `HirAeGraphPass` fails to advance the `UniqueParam` memory version across the mutating call.

## Unique parameter field load must not be forwarded across mut call

```babble
def Box = class (var v: int) ()
def bump = fn (mut b: Box) -> (b.v += 10)
def test = fn (unique b: Box): int -> (
    def a = b.v
    bump(b)
    def c = b.v
    a * 100 + c
)
println(test(Box(3)))
```

```stdout
313
```
