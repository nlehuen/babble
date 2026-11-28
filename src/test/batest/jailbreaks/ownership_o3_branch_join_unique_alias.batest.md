# Jailbreak : `ownership` (`O3`) a branch expression yielding a `unique` local aliases it without consuming uniqueness

- **Surface:** `ownership` (amplified to `opt` `R1` by `FbipReusePass`)
- **Vector:** `O3` (`unique` aliasing across `if` / `else`, `match` and `try` joins)
- **Severity:** `S0` (`-O1`, `-O2`, `wasm-O1` and `wasm-O2` reuse the aliased list in place while `interpret` and `-O0` copy it)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `def alias = a` (or a block, a `var` reassignment, an else-less `if`) makes a later `addL(a)` fail with `Argument for unique parameter xs must be unique`, but `def alias = if c then (a) else (list(2))`, a `match` arm or a `try` body yielding `a` (also directly as a call argument) is accepted. `addL`'s `list(from: move(xs))` then reuses `a` in place in the optimized modes, so `alias` prints `[1, 9]` there and `[1]` in `interpret` and `-O0`.

```babble
;; babble preamble
def addL = fn (unique xs: list of int): list of int -> ( def r = list(from: move(xs)) ; r.add(9) ; r )
```

## If else expression yielding a unique local aliases it

```babble
def t = fn (c: bool): string -> (
    def a = list(1)
    def alias = if c then (a) else (list(2))
    def b = addL(a)
    string(alias) + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## Match arm yielding a unique local aliases it

```babble
def t = fn (n: int): string -> (
    def a = list(1)
    def alias = match n case 0 then (a) else (list(2))
    def b = addL(a)
    string(alias) + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## Try body yielding a unique local aliases it

```babble
def t = fn (): string -> (
    def a = list(1)
    def alias = try (a) catch e : java.lang.RuntimeException then (list(2))
    def b = addL(a)
    string(alias) + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## If else argument yielding a unique local aliases a later unique argument

```babble
def show = fn (x: list of int, y: list of int): string -> ( string(x) + string(y) )
def t = fn (c: bool): string -> (
    def a = list(1)
    show(if c then (a) else (list(2)), addL(a))
)
```

```error
Argument for unique parameter xs must be unique
```
