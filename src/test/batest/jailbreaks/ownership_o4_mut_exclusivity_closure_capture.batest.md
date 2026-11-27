# Jailbreak : `ownership` (`O4`) `mut` exclusivity bypassed via closure capturing the same mutable object passed alongside `mut`

- **Surface:** `ownership`
- **Vector:** `O4` (`mut` exclusivity / closure capture aliasing)
- **Severity:** `S2` (Exclusive `mut` borrow invariant violated: callee mutates `mut b` while its `calling f` callback reads or mutates the same `b`)
- **Observed:** Passing a closure capturing `b` (either reading `b.x` or mutating `b.x = 99`) alongside `b` for a `mut b: Box` parameter in a `calling f` function compiles cleanly and observes/mutates `b` through both aliases across all 6 modes (`interpret`, `-O0`, `-O1`, `-O2`, `wasm-O1`, `wasm-O2`).

## Closure reading a mutable local must not be passed alongside mut borrow of the same local

```babble
def Box = class (var x: int) ()
def run = fn volatile calling f (f: fn volatile (): int, mut b: Box): bool -> (
    def v1 = f()
    b.x = 99
    def v2 = f()
    v1 != v2
)
def b = Box(1)
run(fn volatile (): int -> (b.x), b)
```

```error
Cannot pass aliased mutable arguments
```

## Closure mutating a mutable local must not be passed alongside mut borrow of the same local

```babble
def Box = class (var x: int) ()
def run = fn io calling f (f: fn io (), mut b: Box): int -> (
    b.x = 1
    f()
    b.x
)
def b = Box(0)
run(fn io () -> (b.x = 99), b)
```

```error
Cannot pass aliased mutable arguments
```
