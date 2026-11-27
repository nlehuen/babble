# Jailbreak : `ownership` (`O3`) a `view` of a `fresh` Java container is `unique` though it aliases a `shared` argument

- **Surface:** `ownership` (amplified by `opt` `R1`)
- **Vector:** `O3` (`unique` aliasing), `R1` (`FbipReusePass`)
- **Severity:** `S0` (`-O1`/`-O2` mutate an aliased list in place), `S2` (`unique` aliased)
- **Repro:** `POST /crib?modes=interpret,O0,O1,O2&fresh=true`
- **Observed:** `java.util.Optional.of` is curated `fresh (shared p0)` and `Optional.get` / `orElseThrow` are `view`; `java.util.Optional.of(a).get()` is accepted as a `unique` argument while `a` stays live, so `list(from: move(xs))` reuses `a` in place : `interpret`/`-O0` print `[1, 2][1, 2, 9]`, `-O1`/`-O2` print `[1, 2, 9][1, 2, 9]`.

```babble
;; babble preamble
def addL = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
```

## View of fresh Optional holding a live local must not be unique

```babble
def t = fn (): string -> (
    def a = list(1, 2)
    def c = addL(java.util.Optional.of(a).get())
    string(a) + string(c)
)
println(t())
```

```error
Argument for unique parameter xs must be unique
```

## Record field of a view of a fresh Optional must not be unique

```babble
def R = record (xs: list of int) ()
def t = fn (): string -> (
    def a = list(1, 2)
    def b = java.util.Optional.of(R(a)).orElseThrow()
    def c = addL(b.xs)
    string(a) + string(c)
)
println(t())
```

```error
Argument for unique parameter xs must be unique
```
