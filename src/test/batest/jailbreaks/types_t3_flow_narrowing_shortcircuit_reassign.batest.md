# Jailbreak : `types` (`T3`) stale flow narrowing after short-circuit condition reassignment

- **Surface:** `types`
- **Vector:** `T3` (Flow narrowing invalidation)
- **Severity:** `S1` (Cast-free `ClassCastException` / null dereference) + `S0` (JVM vs Wasm parity divergence)
- **Observed:** Reassigning a narrowed `var` inside the RHS of a short-circuit `and`/`or` condition (`if x is string and (x = 42; true) then (x.length())`, `if x == null or (x = null; false) then (0) else (x.length())`, `while x != null and (x = null; true) then (...)`) does not invalidate the left-hand narrowing. All modes compile with zero errors (and even emit `Warning : try block cannot throw`). At runtime, `x is string and (x = 42; true)` throws `BabbleException: Not a scope : x` in `interpret`, `java.lang.ClassCastException: Integer cannot be cast to String` in `-O0`/`-O1`/`-O2`, and `RuntimeError: illegal cast` in `wasm-O1`/`wasm-O2`.

## Short-circuit and reassigning type-narrowed var must invalidate narrowing

```babble
def f = fn (var x: string | int): int -> (
    if x is string and (x = 42; true) then (x.length()) else (0)
)
f("hi")
```

```error
No such member : length
```

## Short-circuit or reassigning null-narrowed var must invalidate else narrowing

```babble
def f = fn (var x: string?): int -> (
    if x == null or (x = null; false) then (0) else (x.length())
)
f("hi")
```

```error
No such member : length
```

## While condition short-circuit and reassigning narrowed var must invalidate body narrowing

```babble
def f = fn (var x: string?): int -> (
    var r = 0
    while x != null and (x = null; true) then (
        r = x.length()
    )
    r
)
f("hi")
```

```error
No such member : length
```
