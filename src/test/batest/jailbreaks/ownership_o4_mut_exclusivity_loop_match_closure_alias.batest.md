# Jailbreak : `ownership` (`O4`) `mut` exclusivity misses aliases bound by `for`, `match` and closure returns

- **Surface:** `ownership`
- **Vector:** `O4` (call-site `mut` exclusivity)
- **Severity:** `S2` (`mut` + `mut` on the same object at one call site)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `both(mut a: Box, mut b: Box)` rejects `both(xs.get(0), b)` when `xs` holds `b` (round 4), but accepts the same alias when it comes from a `for` loop variable over `xs` (a list or an array), from a `match` binding destructuring `xs`, or from a local closure returning the captured `b`. Every mode prints `2` (the second write through the alias), with no exclusivity error.

```babble
;; babble preamble
def Box = class (var v: int) ()
def both = fn (mut a: Box, mut b: Box): int -> (a.v = 1 ; b.v = 2 ; a.v)
```

## For loop variable over a list holding b aliases b

```babble
def t = fn io (): int -> (
    def b = Box(0)
    var r = 0
    for y in list(b) then (r = both(y, b))
    r
)
```

```error
Exclusivity error : mut target y aliases argument b
```

## For loop variable over an array holding b aliases b

```babble
def t = fn io (): int -> (
    def b = Box(0)
    var r = 0
    for y in array.of(b) then (r = both(y, b))
    r
)
```

```error
Exclusivity error : mut target y aliases argument b
```

## Match binding destructuring a list holding b aliases b

```babble
def t = fn io (): int -> (
    def b = Box(0)
    match list(b) case is list(def y) then (both(y, b)) else (0)
)
```

```error
Exclusivity error : mut target y aliases argument b
```

## Local closure returning the captured b aliases b

```babble
def t = fn io (): int -> (
    def b = Box(0)
    def get = fn (): Box -> (b)
    both(get(), b)
)
```

```error
Exclusivity error : mut target get() aliases argument b
```
