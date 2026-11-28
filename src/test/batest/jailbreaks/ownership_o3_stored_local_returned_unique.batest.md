# Jailbreak : `ownership` (`O3`) a local stored into a mut parameter or this is still returned as a unique value

- **Surface:** `ownership`, amplified to `opt` `R1` by `FbipReusePass`
- **Vector:** `O3` (`unique` aliasing through a returned local)
- **Severity:** `S0` (`-O1`, `-O2`, `wasm-O1` and `wasm-O2` reuse the returned list in place while the caller's container still holds it, printing `[1, 9]` there and `[1]` in `interpret` and `-O0`)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** after `b.xs = a` (a `mut` parameter's field), `s.add(a)` (a `mut` list parameter), `this.xs = a` (a `mut` method), or storing `a` into a fresh local container that is then stored into a `mut` parameter, the same frame correctly refuses to consume or move `a`, but returning `a` hands the caller a `unique` result : the store demotes `a` without recording the target, so `a` still looks like an unaliased frame local on return.

```babble
;; babble preamble
def addL = fn (unique xs: list of int): list of int -> ( def r = list(from: move(xs)) ; r.add(9) ; r )
def LB = class (var xs: (list of int)?) ()
```

## A local stored into a mut parameter's field is not a unique result

```babble
def mk = fn (mut b: LB): list of int -> (def a = list(1) ; b.xs = a ; a)
def t = fn (): string -> (def box = LB(null) ; def r = addL(mk(box)) ; string(box.xs) + string(r))
```

```error
Argument for unique parameter xs must be unique
```

## A local added to a mut list parameter is not a unique result

```babble
def mk = fn (mut s: list of (list of int)): list of int -> (def a = list(1) ; s.add(a) ; a)
def t = fn (): string -> (def s = list of (list of int) () ; def r = addL(mk(s)) ; string(s) + string(r))
```

```error
Argument for unique parameter xs must be unique
```

## A local stored into this is not a unique result

```babble
def H = class (var xs: (list of int)?) (
    def mk = fn mut (): list of int -> (def a = list(1) ; this.xs = a ; a)
)
def t = fn (): string -> (def h = H(null) ; def r = addL(h.mk()) ; string(h.xs) + string(r))
```

```error
Argument for unique parameter xs must be unique
```

## A local inside a fresh container that escapes is not a unique result

```babble
def mk = fn (mut sink: list of LB): list of int -> (
    def a = list(1)
    def box = LB(null)
    box.xs = a
    sink.add(box)
    a
)
def t = fn (): string -> (def s = list of LB () ; def r = addL(mk(s)) ; string(s.get(0).xs) + string(r))
```

```error
Argument for unique parameter xs must be unique
```
