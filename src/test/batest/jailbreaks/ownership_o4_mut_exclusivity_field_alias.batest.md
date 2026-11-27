# Jailbreak : `ownership` (`O4`) `mut` exclusivity bypassed via two fields of a record aliasing the same mutable object

- **Surface:** `ownership`
- **Vector:** `O4` (`mut` exclusivity / call-site aliasing)
- **Severity:** `S2` (Exclusive `mut` borrow invariant violated: `same(x, y)` is `true` for two `mut` parameters)
- **Observed:** Storing the same mutable `Box` in two distinct fields of `Pair(b, b)` and passing `p.a` and `p.b` to `fn (mut x: Box, mut y: Box)` compiles cleanly and evaluates `same(x, y)` to `true` across all 6 modes (`interpret`, `-O0`, `-O1`, `-O2`, `wasm-O1`, `wasm-O2`).

## Two fields of the same record aliasing one mutable instance must not satisfy two mut parameters

```babble
def Box = class (var x: int) ()
def Pair = record (a: Box, b: Box) ()
def check = fn (mut x: Box, mut y: Box): bool -> (same(x, y))
def b = Box(1)
def p = Pair(b, b)
check(p.a, p.b)
```

```error
Cannot pass aliased mutable arguments
```
